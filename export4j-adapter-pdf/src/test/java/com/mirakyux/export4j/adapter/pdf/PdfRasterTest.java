// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfRasterTest {
    @TempDir Path directory;
    @Test void rendersPhysicalCropRotationAndUserUnitWithExactImageSnapshot() throws Exception {
        var adapter = new PdfAdapter(); byte[] bytes;
        try (var pdf = new PDDocument()) {
            var page = new PDPage(new PDRectangle(-10, -20, 100, 80)); pdf.addPage(page);
            page.setCropBox(new PDRectangle(-5, -15, 90, 70)); page.setUserUnit(2); page.setRotation(90);
            try (var content = new PDPageContentStream(pdf, page)) { content.setNonStrokingColor(Color.RED); content.addRect(0, 0, 30, 20); content.fill(); }
            bytes = save(pdf);
        }
        var request = adapter.render(adapter.input(bytes, "").allPages()).dpi(72); var prepared = request.prepare(); request.dpi(144);
        var image = prepared.images().getFirst(); assertEquals(140, image.width()); assertEquals(180, image.height()); assertEquals(List.of(0), image.sourcePages());
        var decoded = ImageIO.read(new ByteArrayInputStream(image.bytes())); assertEquals(140, decoded.getWidth()); assertEquals(180, decoded.getHeight());
        assertTrue(countColor(decoded, 0xFF0000) > 2000); assertTrue(countColor(decoded, 0xFFFFFF) > 5000);
        var zip = new ByteArrayOutputStream(); var result = prepared.toZip(zip); assertTrue(result.report().issues().isEmpty());
        try (var archive = new ZipInputStream(new ByteArrayInputStream(zip.toByteArray()))) { assertEquals("page-0001.png", archive.getNextEntry().getName()); assertArrayEquals(image.bytes(), archive.readAllBytes()); }
        var mutable = image.bytes(); mutable[0] = 0; assertEquals((byte) 0x89, image.bytes()[0]);
    }
    @Test void pngAlphaAndExplicitJpegBackgroundDifferAsRequested() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(blank(), "");
        var transparent = adapter.render(input.allPages()).dpi(72).transparent(true).prepare().images().getFirst();
        assertEquals(0, ImageIO.read(new ByteArrayInputStream(transparent.bytes())).getRGB(0, 0) >>> 24);
        assertThrows(ProcessingException.class, () -> adapter.render(input.allPages()).format(PdfRasterRequest.Format.JPEG).prepare());
        var policy = ProcessingPolicy.builder().feature("JPEG_ENCODING", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE).build();
        var jpeg = adapter.render(input.allPages()).dpi(72).format(PdfRasterRequest.Format.JPEG).background(0x00FF00).policy(policy).prepare();
        var decoded = ImageIO.read(new ByteArrayInputStream(jpeg.images().getFirst().bytes()));
        assertEquals(0x00FF00, decoded.getRGB(0, 0) & 0xFFFFFE); assertEquals(1, jpeg.preview().losses().size()); assertTrue(jpeg.preview().warnings().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> adapter.render(input.allPages()).format(PdfRasterRequest.Format.JPEG).transparent(true).prepare());
    }
    @Test void rendersOnlyEmbeddedExplicitFontsAndSkipsWholePageForUnembeddedFonts() throws Exception {
        var adapter = new PdfAdapter(); byte[] bytes;
        try (var pdf = new PDDocument()) {
            var page = new PDPage(new PDRectangle(200, 100)); pdf.addPage(page);
            var font = PDType0Font.load(pdf, new ByteArrayInputStream(Files.readAllBytes(Path.of("../assets/fonts/LiberationSans-Regular.ttf"))));
            try (var stream = new PDPageContentStream(pdf, page)) { stream.beginText(); stream.setFont(font, 18); stream.newLineAtOffset(10, 50); stream.showText("EMBEDDED"); stream.endText(); }
            bytes = save(pdf);
        }
        var rendered = adapter.render(adapter.input(bytes, "").allPages()).dpi(72).prepare();
        assertTrue(countColor(ImageIO.read(new ByteArrayInputStream(rendered.images().getFirst().bytes())), 0) > 80);
        var glyphBound = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxTextCharacters(3).build()).build();
        var embedded = adapter.input(bytes, "");
        assertEquals(PdfOperationException.Code.TEXT_LIMIT, assertThrows(PdfOperationException.class, () -> glyphBound.render(embedded.allPages()).prepare()).code());
        var unembedded = adapter.input(PdfObjectEditorTest.source(), "");
        var policy = ProcessingPolicy.builder().feature("FORMS", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
                .feature("RENDER_FONT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.SKIP_BLOCK).build();
        assertThrows(ProcessingException.class, () -> adapter.render(unembedded.allPages()).prepare());
        var skipped = adapter.render(unembedded.allPages()).policy(policy).prepare(); assertEquals(1, skipped.images().size()); assertEquals(List.of(1), skipped.images().getFirst().sourcePages());
        assertTrue(skipped.preview().issues().stream().anyMatch(issue -> issue.feature().equals("RENDER_FONT")));
    }
    @Test void textWithoutFontCannotTriggerDefaultFontFallback() throws Exception {
        var adapter = new PdfAdapter();
        try (var pdf = Loader.loadPDF(blank())) {
            var content = new org.apache.pdfbox.pdmodel.common.PDStream(pdf);
            try (var stream = content.createOutputStream()) { stream.write("BT (no font) Tj ET".getBytes(StandardCharsets.US_ASCII)); }
            pdf.getPage(0).setContents(content); var input = adapter.input(save(pdf), "");
            assertThrows(PdfOperationException.class, () -> adapter.render(input.allPages()).prepare());
        }
    }
    @Test void extractsUniqueSharedImagesFromFormsAndPreservesSoftMaskPixels() throws Exception {
        var adapter = new PdfAdapter(); var prepared = adapter.extractImages(adapter.input(images(), "").pages(1, 0)).prepare();
        assertEquals(1, prepared.images().size()); var image = prepared.images().getFirst(); assertEquals(List.of(1, 0), image.sourcePages()); assertEquals(4, image.width()); assertEquals(3, image.height());
        var decoded = ImageIO.read(new ByteArrayInputStream(image.bytes())); assertEquals(0x80FF0000, decoded.getRGB(0, 0)); assertEquals(0xFF00FF00, decoded.getRGB(3, 2));
        assertTrue(prepared.preview().issues().isEmpty());
    }
    @Test void imageStencilIsNotPretendedToHaveAnIndependentColor() throws Exception {
        var adapter = new PdfAdapter(); byte[] source;
        try (var pdf = new PDDocument()) {
            var page = new PDPage(new PDRectangle(100, 100)); pdf.addPage(page); page.setResources(new PDResources());
            var image = new PDImageXObject(pdf); image.setWidth(2); image.setHeight(2); image.setBitsPerComponent(1); image.setStencil(true);
            try (var stream = image.getCOSObject().createOutputStream()) { stream.write(new byte[] {0, 0}); }
            page.getResources().put(COSName.getPDFName("Mask"), image); source = save(pdf);
        }
        var input = adapter.input(source, ""); assertThrows(ProcessingException.class, () -> adapter.extractImages(input.allPages()).prepare());
        var policy = ProcessingPolicy.builder().feature("IMAGE_STENCIL", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.SKIP_MATCH).build();
        var prepared = adapter.extractImages(input.allPages()).policy(policy).prepare(); assertTrue(prepared.images().isEmpty()); assertEquals(1, prepared.preview().issues().size());
    }
    @Test void rasterPixelAndInputOutputLimitsFailBeforeDestinationChanges() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(blank(), "");
        var pixelBound = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxImagePixels(100).build()).build();
        assertEquals(PdfOperationException.Code.IMAGE_LIMIT, assertThrows(PdfOperationException.class, () -> pixelBound.render(input.allPages()).prepare()).code());
        var byteBound = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxInputBytes(10).build()).build();
        assertEquals(PdfOperationException.Code.INPUT_LIMIT, assertThrows(PdfOperationException.class, () -> byteBound.render(input.allPages()).prepare()).code());
        var outputBound = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxOutputBytes(20).build()).build();
        assertEquals(PdfOperationException.Code.OUTPUT_LIMIT, assertThrows(PdfOperationException.class, () -> outputBound.render(input.allPages()).prepare()).code());
        assertThrows(PdfOperationException.class, () -> adapter.render(input.pages(7)).prepare());
        assertThrows(IllegalArgumentException.class, () -> adapter.render(input.allPages()).dpi(Double.NaN));
    }
    @Test void cancellationSnapshotBorrowedStreamAndAtomicTargetContractsHold() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(blank(), ""); var cancelled = new AtomicBoolean();
        var prepared = adapter.render(input.allPages()).execution(ExecutionOptions.builder().cancelled(cancelled::get).build()).prepare();
        class Borrowed extends ByteArrayOutputStream { boolean closed; boolean flushed; @Override public void close() { closed = true; } @Override public void flush() { flushed = true; } }
        var stream = new Borrowed(); prepared.toZip(stream); assertFalse(stream.closed); assertFalse(stream.flushed);
        var target = directory.resolve("pages.zip"); Files.writeString(target, "OLD"); cancelled.set(true);
        assertThrows(ExecutionException.class, () -> prepared.toZip(target)); assertEquals("OLD", Files.readString(target));
        assertThrows(ExecutionException.class, () -> prepared.images().getFirst().to(target)); assertEquals("OLD", Files.readString(target));
        try (var paths = Files.list(directory)) { assertEquals(1, paths.count()); }
    }
    @Test void sourceMetadataAndFormRasterLossesCannotBeSilentlyDropped() throws Exception {
        var adapter = new PdfAdapter(); byte[] bytes;
        try (var pdf = Loader.loadPDF(blank())) { pdf.getDocumentInformation().setTitle("SOURCE"); bytes = save(pdf); }
        var input = adapter.input(bytes, ""); assertThrows(ProcessingException.class, () -> adapter.render(input.allPages()).prepare());
        var policy = ProcessingPolicy.builder().feature("DOCUMENT_METADATA", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DISCARD).build();
        var prepared = adapter.render(input.allPages()).policy(policy).prepare(); var output = new ByteArrayOutputStream(); var result = prepared.toZip(output);
        assertEquals(prepared.preview().issues(), result.report().issues()); assertEquals(1, result.report().losses().size()); assertTrue(result.report().warnings().isEmpty());
    }
    @Test void rendersCurrentEmbeddedFormAppearanceAndReportsRemovedEditability() throws Exception {
        var adapter = new PdfAdapter(); byte[] bytes;
        try (var pdf = Loader.loadPDF(blank())) {
            var form = new org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm(pdf); pdf.getDocumentCatalog().setAcroForm(form);
            form.setDefaultResources(new PDResources());
            var font = PDType0Font.load(pdf, new ByteArrayInputStream(Files.readAllBytes(Path.of("../assets/fonts/LiberationSans-Regular.ttf"))), false);
            form.getDefaultResources().put(COSName.getPDFName("Embedded"), font); form.setDefaultAppearance("/Embedded 12 Tf 0 g"); form.setNeedAppearances(false);
            var field = new org.apache.pdfbox.pdmodel.interactive.form.PDTextField(form); field.setPartialName("field");
            var widget = field.getWidgets().getFirst(); widget.setRectangle(new PDRectangle(5, 20, 90, 25)); widget.setPage(pdf.getPage(0));
            pdf.getPage(0).getAnnotations().add(widget); form.getFields().add(field); field.setValue("READY"); bytes = save(pdf);
        }
        var input = adapter.input(bytes, ""); var policy = ProcessingPolicy.builder().feature("FORMS", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
        var prepared = adapter.render(input.allPages()).dpi(144).policy(policy).prepare();
        int black = countColor(ImageIO.read(new ByteArrayInputStream(prepared.images().getFirst().bytes())), 0);
        assertTrue(black > 20, "form appearance black pixels: " + black);
        assertEquals("FORMS", prepared.preview().losses().getFirst().feature());
    }
    @Test void rasterAndResourceExtractionEnforceEncryptedInputPermissions() throws Exception {
        var adapter = new PdfAdapter(); byte[] bytes;
        try (var pdf = Loader.loadPDF(blank())) {
            var permissions = new org.apache.pdfbox.pdmodel.encryption.AccessPermission(); permissions.setCanExtractContent(false);
            pdf.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner", "reader", permissions)); bytes = save(pdf);
        }
        var reader = adapter.input(bytes, "reader");
        assertEquals(PdfOperationException.Code.PERMISSION_DENIED, assertThrows(PdfOperationException.class, () -> adapter.render(reader.allPages()).prepare()).code());
        assertEquals(PdfOperationException.Code.PERMISSION_DENIED, assertThrows(PdfOperationException.class, () -> adapter.extractImages(reader.allPages()).prepare()).code());
        var owner = adapter.input(bytes, "owner"); assertEquals(1, adapter.render(owner.allPages()).prepare().images().size());
    }
    private static int countColor(BufferedImage image, int rgb) { int count = 0; for (int y = 0; y < image.getHeight(); y++) { for (int x = 0; x < image.getWidth(); x++) { if ((image.getRGB(x, y) & 0xFFFFFF) == rgb) { count++; } } } return count; }
    static byte[] blank() throws IOException { try (var pdf = new PDDocument()) { pdf.addPage(new PDPage(new PDRectangle(100, 80))); return save(pdf); } }
    static byte[] images() throws IOException {
        try (var pdf = new PDDocument()) {
            var pixels = new BufferedImage(4, 3, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 3; y++) { for (int x = 0; x < 4; x++) { pixels.setRGB(x, y, x == 0 ? 0x80FF0000 : 0xFF00FF00); } }
            var image = LosslessFactory.createFromImage(pdf, pixels); pixels.flush(); var resources = new PDResources(); resources.put(COSName.getPDFName("Photo"), image);
            var form = new PDFormXObject(pdf); form.setBBox(new PDRectangle(4, 3)); form.setResources(new PDResources()); form.getResources().put(COSName.getPDFName("Same"), image);
            try (var stream = form.getContentStream().createOutputStream()) { stream.write("q 4 0 0 3 0 0 cm /Same Do Q".getBytes(StandardCharsets.US_ASCII)); }
            resources.put(COSName.getPDFName("Nested"), form);
            for (int index = 0; index < 2; index++) { var page = new PDPage(new PDRectangle(100, 80)); page.setResources(resources); pdf.addPage(page); }
            return save(pdf);
        }
    }
    private static byte[] save(PDDocument document) throws IOException { var out = new ByteArrayOutputStream(); document.save(out); return out.toByteArray(); }
}
