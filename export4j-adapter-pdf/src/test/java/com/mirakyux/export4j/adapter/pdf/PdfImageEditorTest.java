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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfImageEditorTest {
    @TempDir Path directory;
    @Test void replacesOneActualInvocationAndPrivatelyClonesItsFormPath() throws Exception {
        var adapter = new PdfAdapter(); byte[] source = source(false); var input = adapter.input(source, "");
        assertEquals(List.of(0, 1, 2, 3, 0, 1, 2, 3), adapter.editImages(input).instances().stream().map(PdfImageEditor.Instance::occurrence).toList());
        var output = bytes(adapter.editImages(input).replace(0, 2, image(Color.BLUE, false)).prepare());
        try (var before = Loader.loadPDF(source); var after = Loader.loadPDF(output)) {
            var rendered = new PDFRenderer(after).renderImage(0);
            assertEquals(Color.RED.getRGB(), rendered.getRGB(20, 70));
            assertEquals(Color.RED.getRGB(), rendered.getRGB(60, 70));
            assertEquals(Color.BLUE.getRGB(), rendered.getRGB(100, 70));
            assertEquals(Color.RED.getRGB(), rendered.getRGB(140, 70));
            try (var left = before.getPage(1).getContents(); var right = after.getPage(1).getContents()) { assertArrayEquals(left.readAllBytes(), right.readAllBytes()); }
            assertEquals(Color.RED.getRGB(), new PDFRenderer(after).renderImage(1).getRGB(100, 70));
            var resources = after.getPage(0).getResources(); var forms = new java.util.ArrayList<PDFormXObject>();
            for (var name : resources.getXObjectNames()) { if (resources.getXObject(name) instanceof PDFormXObject form) { forms.add(form); } }
            assertTrue(forms.size() >= 2, "changed Form use must have its own resource");
        }
    }
    @Test void maskedSourceRequiresAnActualDiscardAndReplacementKeepsItsOwnAlpha() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(source(true), "");
        assertTrue(adapter.editImages(input).instances().stream().allMatch(PdfImageEditor.Instance::masked));
        assertThrows(ProcessingException.class, () -> adapter.editImages(input).replace(0, 0, image(Color.BLUE, true)).prepare());
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var strategy = ProcessingPolicy.builder().feature("IMAGE_MASK", action, ProcessingPolicy.Treatment.DISCARD).build();
            var candidate = adapter.editImages(input).replace(0, 0, image(Color.BLUE, true)).policy(strategy).prepare();
            assertEquals(1, candidate.preview().losses().size()); assertEquals(action == ProcessingPolicy.Action.WARN ? 1 : 0, candidate.preview().warnings().size());
            try (var pdf = Loader.loadPDF(bytes(candidate))) {
                int rgb = new PDFRenderer(pdf).renderImage(0).getRGB(20, 70); assertTrue((rgb & 255) > (rgb >> 16 & 255));
            }
        }
    }
    @Test void replacementRetainsSourceImageOptionalContentMembership() throws Exception {
        byte[] source;
        try (var pdf = new PDDocument()) {
            var page = new PDPage(new PDRectangle(100, 100)); pdf.addPage(page);
            var raster = raster(Color.RED, false); var image = LosslessFactory.createFromImage(pdf, raster); raster.flush();
            var off = new COSDictionary(); off.setName(COSName.TYPE, "OCG"); off.setString(COSName.NAME, "Hidden image");
            image.getCOSObject().setItem(COSName.OC, off);
            var groups = new COSArray(); groups.add(off);
            var config = new COSDictionary(); config.setName(COSName.getPDFName("BaseState"), "OFF");
            var layers = new COSDictionary(); layers.setItem(COSName.getPDFName("OCGs"), groups); layers.setItem(COSName.D, config);
            pdf.getDocumentCatalog().getCOSObject().setItem(COSName.OCPROPERTIES, layers);
            try (var content = new PDPageContentStream(pdf, page)) { content.drawImage(image, 10, 10, 40, 40); }
            var bytes = new ByteArrayOutputStream(); pdf.save(bytes); source = bytes.toByteArray();
        }
        var adapter = PdfAdapter.builder().allowPassiveStructures(true).build();
        var result = bytes(adapter.editImages(adapter.input(source, "")).replace(0, 0, image(Color.BLUE, false)).prepare());
        try (var pdf = Loader.loadPDF(result)) {
            var pixels = new PDFRenderer(pdf).renderImage(0);
            assertEquals(Color.WHITE.getRGB(), pixels.getRGB(20, 70), "replacement must remain hidden in the default View");
            var groups = pdf.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.OCPROPERTIES).getCOSArray(COSName.getPDFName("OCGs"));
            boolean found = false;
            for (var name : pdf.getPage(0).getResources().getXObjectNames()) {
                var object = pdf.getPage(0).getResources().getXObject(name).getCOSObject();
                if (object.containsKey(COSName.OC)) { assertTrue(object.getDictionaryObject(COSName.OC) == groups.getObject(0)); found = true; }
            }
            assertTrue(found);
        }
    }
    @Test void invalidSelectionBudgetAndCancellationNeverPublishOverOldTarget() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(source(false), "");
        assertEquals(PdfOperationException.Code.INVALID_SELECTION, assertThrows(PdfOperationException.class,
                () -> adapter.editImages(input).replace(0, 4, image(Color.BLUE, false)).prepare()).code());
        var bounded = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxImagePixels(3).build()).build();
        assertThrows(PdfOperationException.class, () -> bounded.editImages(input).replace(0, 0, image(Color.BLUE, false)).prepare());
        var cancelled = new AtomicBoolean(); var prepared = adapter.editImages(input).replace(0, 0, image(Color.BLUE, false))
                .execution(ExecutionOptions.builder().cancelled(cancelled::get).build()).prepare();
        var target = directory.resolve("keep.pdf"); Files.writeString(target, "OLD"); cancelled.set(true);
        assertThrows(ExecutionException.class, () -> prepared.to(target)); assertEquals("OLD", Files.readString(target));
    }
    private static byte[] source(boolean alpha) throws Exception {
        try (var pdf = new PDDocument()) {
            var raster = raster(Color.RED, alpha); var image = LosslessFactory.createFromImage(pdf, raster); raster.flush();
            var form = new PDFormXObject(pdf); form.setBBox(new PDRectangle(80, 40)); form.setResources(new PDResources());
            try (var content = new PDFormContentStream(form)) { content.drawImage(image, 0, 0, 40, 40); content.drawImage(image, 40, 0, 40, 40); }
            for (int i = 0; i < 2; i++) {
                var page = new PDPage(new PDRectangle(160, 100)); pdf.addPage(page);
                try (var content = new PDPageContentStream(pdf, page)) {
                    content.saveGraphicsState(); content.transform(Matrix.getTranslateInstance(0, 10)); content.drawForm(form); content.restoreGraphicsState();
                    content.saveGraphicsState(); content.transform(Matrix.getTranslateInstance(80, 10)); content.drawForm(form); content.restoreGraphicsState();
                }
            }
            var output = new ByteArrayOutputStream(); pdf.save(output); return output.toByteArray();
        }
    }
    private static BufferedImage raster(Color color, boolean alpha) {
        var image = new BufferedImage(2, 2, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 2; y++) { for (int x = 0; x < 2; x++) { image.setRGB(x, y, (alpha ? 0x80000000 : 0xff000000) | color.getRGB() & 0xffffff); } } return image;
    }
    private static ImageResource image(Color color, boolean alpha) throws Exception {
        var raster = raster(color, alpha); var output = new ByteArrayOutputStream(); ImageIO.write(raster, "png", output); raster.flush(); return ImageResource.load(output.toByteArray());
    }
    private static byte[] bytes(PreparedPdf prepared) throws Exception { var output = new ByteArrayOutputStream(); prepared.to(output); return output.toByteArray(); }
}
