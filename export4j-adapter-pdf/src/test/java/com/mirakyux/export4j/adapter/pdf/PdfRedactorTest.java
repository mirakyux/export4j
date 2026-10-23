// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfRedactorTest {
    @TempDir Path directory;
    static final ProcessingPolicy POLICY = ProcessingPolicy.builder()
            .defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
            .feature("RASTERIZATION", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
            .feature("PAGE_GEOMETRY", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
            .feature("FORMS", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
            .feature("COMMENTS", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
    @Test void removesPixelsAndAllOriginalTextResourcesMetadataAttachmentsAndHistory() throws Exception {
        var adapter = new PdfAdapter(); var packageOutput = new ByteArrayOutputStream();
        adapter.editObjects(adapter.input(PdfTextEditorTest.source(false, false), "")).metadata("Title", "SECRET-METADATA")
                .attachment("secret.txt", "SECRET-ATTACHMENT".getBytes(StandardCharsets.UTF_8), "text/plain").prepare().to(packageOutput);
        var prepared = adapter.redact(adapter.input(packageOutput.toByteArray(), "")).area(0, 18, 25, 68, 22).dpi(144).policy(POLICY).prepare();
        byte[] bytes = write(prepared); assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains("SECRET"));
        try (var pdf = Loader.loadPDF(bytes)) {
            assertTrue(new PDFTextStripper().getText(pdf).isBlank()); assertEquals(0, pdf.getDocumentInformation().getCOSObject().size());
            assertFalse(pdf.getDocumentCatalog().getCOSObject().containsKey(COSName.NAMES)); assertFalse(pdf.getDocumentCatalog().getCOSObject().containsKey(COSName.METADATA));
            var page = pdf.getPage(0); assertFalse(page.getResources().getFontNames().iterator().hasNext()); assertEquals(0, page.getAnnotations().size());
            var names = page.getResources().getXObjectNames().iterator(); var image = (PDImageXObject) page.getResources().getXObject(names.next()); assertFalse(names.hasNext());
            var raster = image.getImage();
            for (int y = 51; y < 93; y++) { for (int x = 37; x < 171; x++) { assertEquals(0, raster.getRGB(x, y) & 0xFFFFFF); } }
            int visible = 0; for (int y = 65; y < 85; y++) { for (int x = 178; x < 250; x++) { if ((raster.getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) { visible++; } } } assertTrue(visible > 20);
            for (var key : pdf.getDocument().getXrefTable().keySet()) {
                var object = pdf.getDocument().getObjectFromPool(key);
                if (object.getObject() instanceof COSStream stream) { try (var content = stream.createInputStream()) { assertFalse(new String(content.readAllBytes(), StandardCharsets.ISO_8859_1).contains("SECRET")); } }
            }
        }
        assertTrue(prepared.preview().issues().stream().anyMatch(issue -> issue.feature().equals("ATTACHMENTS")));
    }
    @Test void usesRotatedCroppedPhysicalViewAndNormalizesOutputGeometry() throws Exception {
        byte[] bytes;
        try (var pdf = Loader.loadPDF(PdfTextEditorTest.source(false, false))) {
            var page = pdf.getPage(0); page.setCropBox(new PDRectangle(10, 10, 200, 100)); page.setUserUnit(2); page.setRotation(90);
            var out = new ByteArrayOutputStream(); pdf.save(out); bytes = out.toByteArray();
        }
        var adapter = new PdfAdapter();
        try (var result = Loader.loadPDF(write(adapter.redact(adapter.input(bytes, "")).area(0, 0, 0, 20, 30).dpi(72).policy(POLICY).prepare()))) {
            var page = result.getPage(0); assertEquals(200, page.getMediaBox().getWidth()); assertEquals(400, page.getMediaBox().getHeight()); assertEquals(0, page.getRotation()); assertEquals(1, page.getUserUnit());
        }
    }
    @Test void removesEmbeddedImagePixelsWithoutRetainingTheOriginalImage() throws Exception {
        byte[] bytes;
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage(new PDRectangle(160, 100)); pdf.addPage(page);
            var image = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics(); graphics.setColor(java.awt.Color.RED); graphics.fillRect(0, 0, 16, 16); graphics.dispose();
            var resource = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(pdf, image);
            try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) { content.drawImage(resource, 20, 40, 80, 40); }
            var output = new ByteArrayOutputStream(); pdf.save(output); bytes = output.toByteArray();
        }
        var adapter = new PdfAdapter();
        try (var result = Loader.loadPDF(write(adapter.redact(adapter.input(bytes, "")).area(0, 18, 18, 84, 44).dpi(72).policy(POLICY).prepare()))) {
            var page = result.getPage(0); var names = page.getResources().getXObjectNames().iterator();
            var image = (PDImageXObject) page.getResources().getXObject(names.next()); assertFalse(names.hasNext()); assertEquals(160, image.getWidth());
            for (int y = 20; y < 60; y++) { for (int x = 20; x < 100; x++) { assertEquals(0, image.getImage().getRGB(x, y) & 0xFFFFFF); } }
        }
    }
    @Test void cannotSkipUnrenderablePagesOrKeepUnsupportedOriginalObjectsInSanitizedOutput() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(PdfObjectEditorTest.source(), "");
        assertThrows(ProcessingException.class, () -> adapter.redact(input).area(0, 0, 0, 10, 10).prepare());
        var policy = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DISCARD)
                .feature("RASTERIZATION", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE)
                .feature("FORMS", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE)
                .feature("RENDER_FONT", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.SKIP_BLOCK).build();
        assertThrows(PdfOperationException.class, () -> adapter.redact(input).area(0, 0, 0, 10, 10).policy(policy).prepare());
    }
    @Test void regionOutputAndPixelBudgetsAndCancellationProtectExistingDestination() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(PdfTextEditorTest.source(false, false), "");
        assertThrows(IllegalStateException.class, () -> adapter.redact(input).prepare());
        assertThrows(IllegalArgumentException.class, () -> adapter.redact(input).area(0, 230, 0, 20, 20).policy(POLICY).prepare());
        var limited = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxImagePixels(100).build()).build();
        assertEquals(PdfOperationException.Code.IMAGE_LIMIT, assertThrows(PdfOperationException.class, () -> limited.redact(input).area(0, 0, 0, 20, 20).policy(POLICY).prepare()).code());
        var cancelled = new AtomicBoolean(); var prepared = adapter.redact(input).area(0, 18, 25, 68, 22).policy(POLICY).execution(ExecutionOptions.builder().cancelled(cancelled::get).build()).prepare();
        var target = directory.resolve("old.pdf"); Files.writeString(target, "OLD"); cancelled.set(true); assertThrows(ExecutionException.class, () -> prepared.to(target)); assertEquals("OLD", Files.readString(target));
    }
    private static byte[] write(PreparedPdf prepared) throws Exception { var out = new ByteArrayOutputStream(); var result = prepared.to(out); assertEquals(prepared.preview().issues(), result.report().issues()); return out.toByteArray(); }
}
