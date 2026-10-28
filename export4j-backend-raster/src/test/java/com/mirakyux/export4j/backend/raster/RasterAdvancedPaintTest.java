// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.raster;

import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.BlendMode;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Paint;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.SpotColor;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RasterAdvancedPaintTest {
    @Test void isolatedAlphaDiffersFromIndividualSourceOverAndRestoresOuterState() throws Exception {
        var red = new RgbColor(1, 0, 0); var blue = new RgbColor(0, 0, 1);
        var page = new DisplayPage(new PageSize(90, 30), List.of(new DrawCommand.Group(new Rect(0, 0, 60, 30), List.of(new DrawCommand.FillRect(new Rect(0, 0, 40, 30), red), new DrawCommand.FillRect(new Rect(20, 0, 40, 30), blue)), .5, BlendMode.NORMAL), new DrawCommand.FillRect(new Rect(60, 0, 30, 30), red)));
        var output = new ByteArrayOutputStream(); new RasterBackend(72).writePage(page, output); var image = ImageIO.read(new ByteArrayInputStream(output.toByteArray()));
        assertEquals(image.getRGB(30, 15), image.getRGB(50, 15)); assertEquals(0xff0000, image.getRGB(75, 15) & 0xffffff); assertTrue((image.getRGB(30, 15) >> 16 & 255) >= 127);
    }
    @Test void printColorRequiresExplicitDegradationAndIgnoreStillReportsLosses() throws Exception {
        var path = new VectorPath(List.of(new VectorPath.MoveTo(new Point(0, 0)), new VectorPath.LineTo(new Point(20, 0)), new VectorPath.LineTo(new Point(20, 20)), new VectorPath.Close()));
        var page = new DisplayPage(new PageSize(20, 20), List.of(new DrawCommand.PaintFill(path, new Paint.Solid(new SpotColor("Ink", .5, RgbColor.BLACK)))));
        var output = new ByteArrayOutputStream(); assertThrows(ProcessingException.class, () -> new RasterBackend(72).writePage(page, output)); assertEquals(0, output.size());
        var policy = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE).build(); var report = new RasterBackend(72).writePage(page, output, policy);
        assertEquals(ProcessingReport.Phase.ACTUAL, report.phase()); assertEquals(1, report.losses().size()); assertTrue(report.warnings().isEmpty());
    }
    @Test void evenOddPathClipPreservesHoleAcrossTransforms() throws Exception {
        var path = new VectorPath(List.of(new VectorPath.MoveTo(new Point(0, 0)), new VectorPath.LineTo(new Point(30, 0)), new VectorPath.LineTo(new Point(30, 30)), new VectorPath.LineTo(new Point(0, 30)), new VectorPath.Close(), new VectorPath.MoveTo(new Point(10, 10)), new VectorPath.LineTo(new Point(20, 10)), new VectorPath.LineTo(new Point(20, 20)), new VectorPath.LineTo(new Point(10, 20)), new VectorPath.Close()));
        var page = new DisplayPage(new PageSize(40, 40), List.of(new DrawCommand.Transform(AffineTransform.translation(5, 5)), new DrawCommand.ClipPath(path, DrawCommand.FillRule.EVEN_ODD), new DrawCommand.FillRect(new Rect(0, 0, 30, 30), RgbColor.BLACK)));
        var output = new ByteArrayOutputStream(); new RasterBackend(72).writePage(page, output); var image = ImageIO.read(new ByteArrayInputStream(output.toByteArray())); assertEquals(0, image.getRGB(10, 10) & 0xffffff); assertEquals(0xffffff, image.getRGB(20, 20) & 0xffffff); assertEquals(0xffffff, image.getRGB(2, 2) & 0xffffff);
    }
}
