// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.BlendMode;
import com.mirakyux.export4j.graphics.CmykColor;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.IccColor;
import com.mirakyux.export4j.graphics.Paint;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.graphics.SpotColor;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfAdvancedPaintTest {
    private static final RgbColor RED = new RgbColor(1, 0, 0), BLUE = new RgbColor(0, 0, 1);
    @Test void mixedGradientSpacesRequireExplicitLossPolicy() throws Exception {
        var paint = new Paint.Linear(new Point(0, 0), new Point(30, 0), List.of(new Paint.Stop(0, RED), new Paint.Stop(1, new CmykColor(1, 0, 0, 0))));
        var document = new DisplayDocument(DocumentMetadata.EMPTY, List.of(new DisplayListBuilder(new PageSize(30, 30)).fillPath(rect(0, 0, 30, 30), paint).build()));
        var output = new ByteArrayOutputStream(); assertThrows(com.mirakyux.export4j.model.processing.ProcessingException.class, () -> new PdfBackend().write(document, output)); assertEquals(0, output.size());
        var policy = com.mirakyux.export4j.model.processing.ProcessingPolicy.builder().defaultRule(com.mirakyux.export4j.model.processing.ProcessingPolicy.Action.IGNORE, com.mirakyux.export4j.model.processing.ProcessingPolicy.Treatment.DEGRADE).build();
        var report = new PdfBackend().write(document, output, policy); assertEquals(1, report.losses().size()); assertTrue(report.warnings().isEmpty()); assertEquals("gradient-color-space", report.losses().getFirst().feature());
        try (var pdf = Loader.loadPDF(output.toByteArray())) { assertTrue(pdf.getPage(0).getResources().getShadingNames().iterator().hasNext()); }
    }
    @Test void functionalGradientsKeepRepeatingColorAndIndependentStopAlpha() throws Exception {
        var stops = List.of(new Paint.Stop(0, RED), new Paint.Stop(1, BLUE));
        var builder = new DisplayListBuilder(new PageSize(120, 90));
        builder.fillPath(rect(0, 0, 120, 30), new Paint.Linear(new Point(10, 0), new Point(30, 0), stops, Paint.Spread.REPEAT, AffineTransform.IDENTITY));
        builder.fillPath(rect(0, 30, 120, 30), new Paint.Linear(new Point(0, 30), new Point(120, 30), List.of(new Paint.Stop(0, RED, 0), new Paint.Stop(1, BLUE))));
        builder.fillPath(rect(0, 60, 120, 30), new Paint.Radial(new Point(60, 75), new Point(55, 73), 30, stops, Paint.Spread.REFLECT, AffineTransform.IDENTITY));
        try (var pdf = Loader.loadPDF(bytes(builder))) {
            var resources = pdf.getPage(0).getResources(); assertTrue(resources.getShadingNames().iterator().hasNext());
            var image = new PDFRenderer(pdf).renderImage(0);
            assertTrue((image.getRGB(12, 12) >> 16 & 255) > 200); assertTrue((image.getRGB(28, 12) & 255) > 200);
            for (int channel = 0; channel < 3; channel++) { assertTrue(Math.abs((image.getRGB(12, 12) >> channel * 8 & 255) - (image.getRGB(32, 12) >> channel * 8 & 255)) < 4); }
            assertTrue((image.getRGB(3, 45) >> 8 & 255) > 240, "transparent red stop must reveal white");
            assertTrue((image.getRGB(115, 45) & 255) > 240);
            assertNotEquals(image.getRGB(55, 73), image.getRGB(80, 73));
        }
    }
    @Test void isolatedGroupCompositesOverlapOnceAndNativeTileResourcesAreApplied() throws Exception {
        var builder = new DisplayListBuilder(new PageSize(120, 60));
        builder.group(new Rect(0, 0, 80, 30), List.of(new DrawCommand.FillRect(new Rect(0, 0, 50, 30), RED), new DrawCommand.FillRect(new Rect(30, 0, 50, 30), BLUE)), .5, BlendMode.NORMAL);
        var tile = new Paint.Tile(new Rect(0, 0, 10, 10), 10, 10, AffineTransform.IDENTITY, List.of(new DrawCommand.FillRect(new Rect(0, 0, 5, 10), RED)));
        builder.save().opacity(.5, .75).fillPath(rect(0, 35, 120, 20), tile).restore();
        try (var pdf = Loader.loadPDF(bytes(builder))) {
            var resources = pdf.getPage(0).getResources(); assertTrue(resources.getXObjectNames().iterator().hasNext());
            boolean nativeTile = false;
            for (var name : resources.getXObjectNames()) {
                if (resources.getXObject(name) instanceof org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form
                        && form.getResources().getPatternNames().iterator().hasNext()) { nativeTile = true; }
            }
            assertTrue(nativeTile, "transparent tile must retain its native pattern inside the isolated paint form");
            var image = new PDFRenderer(pdf).renderImage(0);
            assertEquals(image.getRGB(40, 15), image.getRGB(65, 15), "overlap must share the same group alpha");
            assertTrue(Math.abs((image.getRGB(2, 45) >> 8 & 255) - 127.5) <= .5, "tile must retain the outer paint alpha"); assertEquals(0xffffff, image.getRGB(7, 45) & 0xffffff);
        }
    }
    @Test void nativePrintColorsPreserveCmykIccAndNamedSeparation() throws Exception {
        var builder = new DisplayListBuilder(new PageSize(90, 30)); var profile = java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB).getData();
        builder.fillPath(rect(0, 0, 30, 30), new Paint.Solid(new CmykColor(1, 0, 0, 0)));
        builder.fillPath(rect(30, 0, 30, 30), new Paint.Solid(new IccColor(profile, List.of(.2, .4, .8))));
        builder.fillPath(rect(60, 0, 30, 30), new Paint.Solid(new SpotColor("Test Ink", .75, RED)));
        try (var pdf = Loader.loadPDF(bytes(builder))) {
            var names = new java.util.HashSet<String>(); var resources = pdf.getPage(0).getResources(); for (var name : resources.getColorSpaceNames()) { names.add(resources.getColorSpace(name).getName()); }
            assertTrue(names.contains("ICCBased")); assertTrue(names.contains("Separation"));
            var image = new PDFRenderer(pdf).renderImage(0); assertTrue((image.getRGB(75, 15) >> 16 & 255) > 245); assertTrue((image.getRGB(75, 15) & 255) < 80);
        }
    }
    @Test void nestedPatternsRemainSubjectToCumulativeCommandAndImageBudgets() {
        var image = new RgbImage(2, 2, new byte[12]);
        var tile = new Paint.Tile(new Rect(0, 0, 10, 10), 10, 10, AffineTransform.IDENTITY, List.of(new DrawCommand.Image(image, new Rect(0, 0, 10, 10))));
        var builder = new DisplayListBuilder(new PageSize(30, 30)).fillPath(rect(0, 0, 30, 30), tile);
        var limits = new PdfLimits(10, 100, 64, 3, 100, 100, 1000); var output = new ByteArrayOutputStream();
        assertThrows(PdfRenderException.class, () -> new PdfBackend(limits).write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(builder.build())), output)); assertEquals(0, output.size());
    }
    private static byte[] bytes(DisplayListBuilder builder) throws Exception { var output = new ByteArrayOutputStream(); new PdfBackend().write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(builder.build())), output); return output.toByteArray(); }
    private static VectorPath rect(double x, double y, double w, double h) { return new VectorPath(List.of(new VectorPath.MoveTo(new Point(x, y)), new VectorPath.LineTo(new Point(x + w, y)), new VectorPath.LineTo(new Point(x + w, y + h)), new VectorPath.LineTo(new Point(x, y + h)), new VectorPath.Close())); }
}
