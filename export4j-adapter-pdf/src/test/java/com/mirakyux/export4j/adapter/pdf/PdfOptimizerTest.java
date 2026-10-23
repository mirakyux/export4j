// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfOptimizerTest {
    @TempDir Path directory;
    private static final ProcessingPolicy LOSS = ProcessingPolicy.builder()
            .feature("IMAGE_RESAMPLING", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
            .feature("JPEG_ENCODING", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
            .feature("IMAGE_RESAMPLING_FORMAT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.SKIP_MATCH).build();

    @Test void losslessRecompressionPreservesDecodedCommandsAndDeduplicatesIdenticalImages() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(images(false), "");
        var prepared = adapter.optimize(input).prepare(); var output = write(prepared);
        assertTrue(prepared.statistics().recompressedStreams() >= 1); assertEquals(1, prepared.statistics().deduplicatedResources());
        assertEquals(0, prepared.statistics().resampledImages()); assertTrue(prepared.preview().issues().isEmpty());
        assertEquals(output.length, prepared.statistics().outputBytes()); assertTrue(output.length < input.byteSize());
        try (var before = Loader.loadPDF(input.bytes()); var after = Loader.loadPDF(output)) {
            assertArrayEquals(before.getPage(0).getContents().readAllBytes(), after.getPage(0).getContents().readAllBytes());
            var resources = after.getPage(0).getResources();
            assertSame(resources.getXObject(COSName.getPDFName("A")).getCOSObject(), resources.getXObject(COSName.getPDFName("B")).getCOSObject());
        }
    }
    @Test void differentImageViewingHintsAreNeverDeduplicated() throws Exception {
        byte[] bytes;
        try (var pdf = Loader.loadPDF(images(false))) {
            pdf.getPage(0).getResources().getXObject(COSName.getPDFName("B")).getCOSObject().setBoolean(COSName.INTERPOLATE, true); bytes = save(pdf);
        }
        var adapter = new PdfAdapter(); assertEquals(0, adapter.optimize(adapter.input(bytes, "")).prepare().statistics().deduplicatedResources());
    }
    @Test void imageResamplingRequiresRealLossPolicyAndPreservesAspectRatio() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(images(false), "");
        assertThrows(ProcessingException.class, () -> adapter.optimize(input).downsampleImages(15, 15).prepare());
        var request = adapter.optimize(input).downsampleImages(15, 15).policy(LOSS); var prepared = request.prepare(); request.downsampleImages(1, 1);
        assertEquals(2, prepared.statistics().resampledImages()); assertEquals(1200, prepared.statistics().resampledPixelsBefore());
        assertEquals(300, prepared.statistics().resampledPixelsAfter()); assertEquals(2, prepared.preview().losses().size());
        try (var pdf = Loader.loadPDF(write(prepared))) {
            var image = (PDImageXObject) pdf.getPage(0).getResources().getXObject(COSName.getPDFName("A"));
            assertEquals(15, image.getWidth()); assertEquals(10, image.getHeight()); assertEquals(0x2266AA, image.getImage().getRGB(2, 2) & 0xFFFFFF);
        }
    }
    @Test void masksAndTheirParentImagesRemainUnchangedEvenWhenSharedAsOrdinaryResources() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(images(true), "");
        var prepared = adapter.optimize(input).downsampleImages(4, 4).policy(LOSS).prepare(); assertEquals(0, prepared.statistics().resampledImages());
        assertTrue(prepared.preview().issues().stream().allMatch(issue -> issue.feature().equals("IMAGE_RESAMPLING_FORMAT")));
        try (var before = Loader.loadPDF(input.bytes()); var after = Loader.loadPDF(write(prepared))) {
            for (String name : new String[] {"A", "B", "Mask"}) {
                var left = (PDImageXObject) before.getPage(0).getResources().getXObject(COSName.getPDFName(name));
                var right = (PDImageXObject) after.getPage(0).getResources().getXObject(COSName.getPDFName(name));
                assertEquals(left.getWidth(), right.getWidth()); assertEquals(left.getHeight(), right.getHeight());
                assertArrayEquals(left.getImage().getRGB(0, 0, 30, 20, null, 0, 30), right.getImage().getRGB(0, 0, 30, 20, null, 0, 30));
            }
        }
    }
    @Test void jpegIsExplicitAndUnsupportedTreatmentsFailRatherThanPretendToOptimize() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(images(false), "");
        assertThrows(IllegalArgumentException.class, () -> adapter.optimize(input).jpegQuality(.8).prepare());
        var prepared = adapter.optimize(input).downsampleImages(15, 10).jpegQuality(.9).policy(LOSS).prepare();
        assertEquals(4, prepared.preview().losses().size());
        try (var pdf = Loader.loadPDF(write(prepared))) { assertEquals(COSName.DCT_DECODE, pdf.getPage(0).getResources().getXObject(COSName.getPDFName("A")).getCOSObject().getDictionaryObject(COSName.FILTER)); }
        var wrong = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DISCARD).build();
        assertThrows(IllegalArgumentException.class, () -> adapter.optimize(input).downsampleImages(15, 10).policy(wrong).prepare());
    }
    @Test void passiveObjectsAndAttachmentsKeepTheirValuesAndBytes() throws Exception {
        var adapter = new PdfAdapter(); var buffer = new ByteArrayOutputStream();
        adapter.editObjects(adapter.input(PdfObjectEditorTest.source(), "")).metadata("Title", "KEEP-TITLE")
                .attachment("opaque.txt", "keep payload".getBytes(StandardCharsets.UTF_8), "text/plain").prepare().to(buffer);
        var input = adapter.input(buffer.toByteArray(), ""); var before = adapter.readObjects(input);
        var after = adapter.readObjects(adapter.input(write(adapter.optimize(input).prepare()), ""));
        assertEquals(before.fields(), after.fields()); assertEquals(before.annotations(), after.annotations());
        assertEquals(before.bookmarks(), after.bookmarks()); assertEquals(before.metadata(), after.metadata());
        assertArrayEquals(before.attachments().getFirst().bytes(), after.attachments().getFirst().bytes());
    }
    @Test void tinyIncompressibleStreamAtDecodeLimitIsRetained() throws Exception {
        byte[] bytes;
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage(new PDRectangle(100, 100))); var stream = new PDStream(pdf);
            try (var output = stream.createOutputStream()) { output.write("q Q".getBytes(StandardCharsets.US_ASCII)); }
            pdf.getPage(0).setContents(stream); var output = new ByteArrayOutputStream(); pdf.save(output, org.apache.pdfbox.pdfwriter.compress.CompressParameters.NO_COMPRESSION); bytes = output.toByteArray();
        }
        var adapter = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxDecodedStreamBytes(3).build()).build();
        assertEquals(0, adapter.optimize(adapter.input(bytes, "")).prepare().statistics().recompressedStreams());
    }
    @Test void pageContentArraysKeepTheirTypeAndBalancedCombinedGraphicsState() throws Exception {
        byte[] bytes;
        try (var pdf = new PDDocument()) {
            var page = new PDPage(new PDRectangle(100, 100)); pdf.addPage(page);
            var first = new PDStream(pdf); var last = new PDStream(pdf);
            try (var output = first.createOutputStream()) { output.write("q\n".getBytes(StandardCharsets.US_ASCII)); }
            try (var output = last.createOutputStream()) { output.write("Q\n".getBytes(StandardCharsets.US_ASCII)); }
            page.setContents(java.util.List.of(first, last)); bytes = save(pdf);
        }
        var adapter = new PdfAdapter();
        try (var pdf = Loader.loadPDF(write(adapter.optimize(adapter.input(bytes, "")).prepare()))) {
            var streams = pdf.getPage(0).getContentStreams();
            while (streams.hasNext()) { assertFalse(streams.next().getCOSObject().containsKey(COSName.SUBTYPE)); }
        }
    }
    @Test void cancellationOutputLimitAndBorrowedStreamFailureKeepOldTarget() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(images(false), ""); var cancelled = new AtomicBoolean();
        var request = adapter.optimize(input).execution(ExecutionOptions.builder().cancelled(cancelled::get).build());
        var prepared = request.prepare(); var path = directory.resolve("old.pdf"); Files.writeString(path, "OLD"); cancelled.set(true);
        assertThrows(ExecutionException.class, () -> prepared.to(path)); assertEquals("OLD", Files.readString(path));
        assertThrows(ExecutionException.class, request::prepare);
        var bounded = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxOutputBytes(100).build()).build();
        assertEquals(PdfOperationException.Code.OUTPUT_LIMIT, assertThrows(PdfOperationException.class, () -> bounded.optimize(input).prepare()).code());
        var stream = new ByteArrayOutputStream() { boolean closed; @Override public void close() { closed = true; } };
        adapter.optimize(input).prepare().to(stream); assertFalse(stream.closed);
    }
    private static byte[] write(PreparedPdfOptimization value) throws Exception { var out = new ByteArrayOutputStream(); var result = value.to(out); assertEquals(value.preview().issues(), result.report().issues()); return out.toByteArray(); }
    private static byte[] save(PDDocument pdf) throws Exception { var output = new ByteArrayOutputStream(); pdf.save(output); return output.toByteArray(); }
    private static byte[] images(boolean alpha) throws Exception {
        try (var pdf = new PDDocument()) {
            var page = new PDPage(new PDRectangle(160, 100)); pdf.addPage(page); page.setResources(new PDResources());
            var image = new BufferedImage(30, 20, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 20; y++) { for (int x = 0; x < 30; x++) { image.setRGB(x, y, (alpha ? 0x80000000 : 0xFF000000) | 0x2266AA); } }
            var a = LosslessFactory.createFromImage(pdf, image); var b = LosslessFactory.createFromImage(pdf, image);
            page.getResources().put(COSName.getPDFName("A"), a); page.getResources().put(COSName.getPDFName("B"), b);
            if (alpha) { page.getResources().put(COSName.getPDFName("Mask"), a.getSoftMask()); }
            try (var content = new PDPageContentStream(pdf, page, PDPageContentStream.AppendMode.OVERWRITE, false)) {
                content.drawImage(a, 10, 10, 60, 40); content.drawImage(b, 85, 10, 60, 40);
            }
            var padding = new PDStream(pdf);
            try (var stream = padding.createOutputStream()) { stream.write(("\n% " + "padding ".repeat(4000) + "\n").getBytes(StandardCharsets.US_ASCII)); }
            page.setContents(java.util.List.of(new PDStream((org.apache.pdfbox.cos.COSStream) page.getCOSObject().getDictionaryObject(COSName.CONTENTS)), padding));
            return save(pdf);
        }
    }
}
