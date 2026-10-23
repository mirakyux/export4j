// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfPageEditorTest {
    @TempDir Path directory;
    private static final ProcessingPolicy WARN = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();

    @Test void rotatesScalesAndCropsWithExplicitVisibilityLossAndPreservesMetadata() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(source(), "");
        var prepared = adapter.editPages(input).policy(WARN).rotate(0, 450).scale(0, .5)
                .crop(0, new PdfPageEditor.Rectangle(10, 10, 100, 100)).prepare();
        var output = new ByteArrayOutputStream(); var result = prepared.to(output);
        assertEquals(3, result.changes()); assertEquals(prepared.preview().issues(), result.report().issues());
        assertEquals("OUTSIDE_CROP_HIDDEN_NOT_REMOVED", result.report().losses().getFirst().effects().getFirst());
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            assertEquals(90, pdf.getPage(0).getRotation()); assertEquals(120, pdf.getPage(0).getMediaBox().getWidth());
            assertEquals(10, pdf.getPage(0).getCropBox().getLowerLeftX()); assertEquals(40, pdf.getPage(0).getCropBox().getLowerLeftY());
            assertEquals("KEEP-METADATA", pdf.getDocumentInformation().getTitle());
            assertTrue(new String(pdf.getPage(0).getContents().readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1).contains("SOURCE"), "cropping retains hidden source operators");
        }
        assertEquals(240, adapter.read(input).pages().getFirst().mediaBox().width());
    }
    @Test void croppingRequiresRealTreatmentAndIgnoreStillReturnsLosses() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(source(), ""); var box = new PdfPageEditor.Rectangle(0, 0, 100, 100);
        assertThrows(ProcessingException.class, () -> adapter.editPages(input).crop(0, box).prepare());
        var ignore = ProcessingPolicy.builder().feature("PAGE_CROP", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE).build();
        var prepared = adapter.editPages(input).policy(ignore).crop(0, box).prepare();
        assertTrue(prepared.preview().warnings().isEmpty()); assertEquals(1, prepared.preview().losses().size());
        var preserve = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.PRESERVE).build();
        assertThrows(IllegalArgumentException.class, () -> adapter.editPages(input).policy(preserve).crop(0, box).prepare());
    }
    @Test void mediaOriginAndUserUnitDetermineNativeCropCoordinatesBeforeRotation() throws Exception {
        var adapter = new PdfAdapter(); byte[] source;
        try (var pdf = Loader.loadPDF(source())) {
            var page = pdf.getPage(0); page.setMediaBox(new PDRectangle(10, 20, 240, 300)); page.setCropBox(page.getMediaBox()); page.setUserUnit(2); page.setRotation(90);
            source = save(pdf);
        }
        var output = new ByteArrayOutputStream();
        adapter.editPages(adapter.input(source, "")).policy(WARN).crop(0, new PdfPageEditor.Rectangle(20, 40, 200, 300)).prepare().to(output);
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            var page = pdf.getPage(0); assertEquals(20, page.getCropBox().getLowerLeftX()); assertEquals(150, page.getCropBox().getLowerLeftY());
            assertEquals(100, page.getCropBox().getWidth()); assertEquals(150, page.getCropBox().getHeight()); assertEquals(2, page.getUserUnit()); assertEquals(90, page.getRotation());
        }
    }
    @Test void scaleTransformsActualContentAlongWithAllPageBoxes() throws Exception {
        var adapter = new PdfAdapter(); var output = new ByteArrayOutputStream();
        adapter.editPages(adapter.input(source(), "")).scale(0, .5).prepare().to(output);
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            var image = new PDFRenderer(pdf).renderImage(0, 1);
            assertEquals(120, image.getWidth()); assertEquals(150, image.getHeight());
            assertTrue((image.getRGB(15, 130) & 0xffffff) < 0x101010, "the filled source rectangle scales to [10,125,10,10]");
            assertEquals(0xffffff, image.getRGB(35, 130) & 0xffffff); image.flush();
        }
    }
    @Test void explicitFontTextIsEmbeddedAndOriginalContentStreamIsUntouched() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(source(), ""); var output = new ByteArrayOutputStream();
        adapter.editPages(input).text(0, "STAMP", 25, 85, style(), PdfPageEditor.Layer.OVER_CONTENT).prepare().to(output);
        try (var before = Loader.loadPDF(source()); var after = Loader.loadPDF(output.toByteArray())) {
            var streams = after.getPage(0).getContentStreams(); boolean original = false;
            var bytes = before.getPage(0).getContents().readAllBytes();
            while (streams.hasNext()) { if (Arrays.equals(bytes, streams.next().createInputStream().readAllBytes())) { original = true; } }
            assertTrue(original); String text = new PDFTextStripper().getText(after); assertTrue(text.contains("SOURCE")); assertTrue(text.contains("STAMP"));
            assertTrue(after.getPage(0).getResources().getFontNames().iterator().hasNext());
        }
    }
    @Test void imageLayerOrderChangesVisiblePixelsWithoutDroppingSource() throws Exception {
        var adapter = new PdfAdapter();
        for (var layer : PdfPageEditor.Layer.values()) {
            var output = new ByteArrayOutputStream();
            adapter.editPages(adapter.input(source(), "")).image(0, red(), new PdfPageEditor.Rectangle(20, 250, 20, 20), layer).prepare().to(output);
            try (var pdf = Loader.loadPDF(output.toByteArray())) {
                var image = new PDFRenderer(pdf).renderImage(0, 1);
                assertEquals(layer == PdfPageEditor.Layer.OVER_CONTENT ? 0xff0000 : 0, image.getRGB(25, 255) & 0xffffff); image.flush();
                assertTrue(new PDFTextStripper().getText(pdf).contains("SOURCE"));
            }
        }
    }
    @Test void unsupportedShapingCanOnlySkipTheStampWithAQueryableIssue() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(source(), "");
        assertThrows(ProcessingException.class, () -> adapter.editPages(input).text(0, "a\u0301", 10, 70, style(), PdfPageEditor.Layer.OVER_CONTENT).prepare());
        var policy = ProcessingPolicy.builder().feature("TEXT_SHAPING", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.SKIP_MATCH).build();
        var prepared = adapter.editPages(input).policy(policy).text(0, "a\u0301", 10, 70, style(), PdfPageEditor.Layer.OVER_CONTENT).prepare();
        assertEquals(1, prepared.preview().issues().size()); assertTrue(prepared.preview().warnings().isEmpty());
        var output = new ByteArrayOutputStream(); assertEquals(0, prepared.to(output).changes());
        try (var pdf = Loader.loadPDF(output.toByteArray())) { assertEquals("SOURCE", new PDFTextStripper().getText(pdf).strip()); }
    }
    @Test void signedOrInteractiveInputStillCannotPassStaticEditing() throws Exception {
        var adapter = new PdfAdapter();
        try (var pdf = Loader.loadPDF(source())) {
            var note = new PDAnnotationText(); note.setContents("KEEP"); note.setRectangle(new PDRectangle(10, 10, 20, 20)); pdf.getPage(0).getAnnotations().add(note);
            assertEquals(PdfOperationException.Code.UNSUPPORTED_FEATURE, assertThrows(PdfOperationException.class,
                    () -> adapter.editPages(adapter.input(save(pdf), "")).rotate(0, 90).prepare()).code());
            pdf.getPage(0).getCOSObject().removeItem(COSName.ANNOTS); pdf.getDocumentCatalog().getCOSObject().setString(COSName.BYTERANGE, "invalid");
            assertThrows(PdfOperationException.class, () -> adapter.editPages(adapter.input(save(pdf), "")).prepare());
        }
    }
    @Test void decryptionNeedsExplicitOwnerAndReportsActualProtectionLoss() throws Exception {
        byte[] encrypted;
        try (var pdf = Loader.loadPDF(source())) { pdf.protect(new StandardProtectionPolicy("owner", "user", new AccessPermission())); encrypted = save(pdf); }
        var strict = new PdfAdapter();
        assertThrows(PdfOperationException.class, () -> strict.editPages(strict.input(encrypted, "owner")).prepare());
        var adapter = PdfAdapter.builder().allowDecryptedCopy(true).build();
        assertThrows(PdfOperationException.class, () -> adapter.editPages(adapter.input(encrypted, "user")).policy(WARN).prepare());
        assertThrows(ProcessingException.class, () -> adapter.editPages(adapter.input(encrypted, "owner")).prepare());
        var prepared = adapter.editPages(adapter.input(encrypted, "owner")).policy(WARN).prepare(); var output = new ByteArrayOutputStream(); prepared.to(output);
        assertEquals("ENCRYPTION_REMOVED", prepared.preview().issues().getFirst().feature());
        try (var pdf = Loader.loadPDF(output.toByteArray())) { assertFalse(pdf.isEncrypted()); }
    }
    @Test void invalidSelectionGeometryGlyphsAndBudgetsFailBeforeOutput() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(source(), "");
        assertThrows(IllegalArgumentException.class, () -> adapter.editPages(input).rotate(0, 1));
        assertThrows(PdfOperationException.class, () -> adapter.editPages(input).rotate(1, 90).prepare());
        assertThrows(IllegalArgumentException.class, () -> adapter.editPages(input).scale(0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> adapter.editPages(input).scale(0, 1e6).prepare());
        assertThrows(IllegalArgumentException.class, () -> adapter.editPages(input).policy(WARN).crop(0, new PdfPageEditor.Rectangle(0, 0, 1000, 1000)).prepare());
        assertThrows(IllegalArgumentException.class, () -> adapter.editPages(input).text(0, "\uD800", 1, 1, style(), PdfPageEditor.Layer.OVER_CONTENT));
        assertThrows(IllegalArgumentException.class, () -> adapter.editPages(input).text(0, "\u4e2d", 10, 80, style(), PdfPageEditor.Layer.OVER_CONTENT).prepare());
        var small = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxOutputBytes(20).build()).build();
        assertThrows(IOException.class, () -> small.editPages(input).prepare());
    }
    @Test void prepareSnapshotOutputOwnershipFailureAndCancellationAreExplicit() throws Exception {
        var cancelled = new AtomicBoolean(); var adapter = new PdfAdapter(); var request = adapter.editPages(adapter.input(source(), ""))
                .execution(ExecutionOptions.builder().cancelled(cancelled::get).build()).rotate(0, 90);
        var prepared = request.prepare(); request.rotate(0, 90);
        var closed = new AtomicBoolean(); var flushed = new AtomicBoolean();
        var out = new ByteArrayOutputStream() { @Override public void close() { closed.set(true); } @Override public void flush() { flushed.set(true); } };
        prepared.to(out); assertFalse(closed.get()); assertFalse(flushed.get());
        try (var pdf = Loader.loadPDF(out.toByteArray())) { assertEquals(90, pdf.getPage(0).getRotation()); }
        assertThrows(IOException.class, () -> prepared.to(new OutputStream() { @Override public void write(int value) throws IOException { throw new IOException("controlled"); } }));
        var target = directory.resolve("report.pdf"); Files.writeString(target, "OLD"); cancelled.set(true);
        assertThrows(ExecutionException.class, () -> prepared.to(target)); assertEquals("OLD", Files.readString(target));
        try (var paths = Files.list(directory)) { assertEquals(1, paths.count()); }
        cancelled.set(false); prepared.to(target); assertArrayEquals(out.toByteArray(), Files.readAllBytes(target));
    }
    private static PdfPageEditor.TextStyle style() throws IOException { return new PdfPageEditor.TextStyle(PdfFont.of(Files.readAllBytes(Path.of("../assets/fonts/LiberationSans-Regular.ttf"))), 12, 0x224466, .7); }
    private static ImageResource red() throws IOException {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB); var graphics = image.createGraphics(); graphics.setColor(Color.RED); graphics.fillRect(0, 0, 2, 2); graphics.dispose();
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes); image.flush(); return ImageResource.load(bytes.toByteArray());
    }
    private static byte[] source() throws IOException {
        try (var pdf = new PDDocument()) {
            var page = new PDPage(new PDRectangle(240, 300)); pdf.addPage(page); pdf.getDocumentInformation().setTitle("KEEP-METADATA");
            try (var content = new PDPageContentStream(pdf, page)) {
                content.setNonStrokingColor(0f); content.addRect(20, 30, 20, 20); content.fill();
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12); content.newLineAtOffset(20, 240); content.showText("SOURCE"); content.endText();
            }
            return save(pdf);
        }
    }
    private static byte[] save(PDDocument document) throws IOException { var bytes = new ByteArrayOutputStream(); document.save(bytes); return bytes.toByteArray(); }
}
