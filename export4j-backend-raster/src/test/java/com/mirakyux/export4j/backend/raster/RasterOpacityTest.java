// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.raster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.model.PageSize;

class RasterOpacityTest {
    @Test void fillStrokeImageAndNestedRestoresUseIndependentSourceOverOpacity() throws Exception {
        var red = new RgbColor(1, 0, 0);
        var blue = new RgbColor(0, 0, 1);
        var line = new VectorPath(List.of(new VectorPath.MoveTo(new Point(0, 30)), new VectorPath.LineTo(new Point(60, 30))));
        var page = new DisplayPage(new PageSize(80, 40), List.of(
                new DrawCommand.Opacity(0.5, 0.25), new DrawCommand.FillRect(new Rect(0, 0, 10, 10), red),
                new DrawCommand.Save(), new DrawCommand.Opacity(0, 1), new DrawCommand.FillRect(new Rect(10, 0, 10, 10), red),
                new DrawCommand.Save(), new DrawCommand.Opacity(1, 1), new DrawCommand.FillRect(new Rect(20, 0, 10, 10), red),
                new DrawCommand.Restore(), new DrawCommand.FillRect(new Rect(30, 0, 10, 10), red), new DrawCommand.Restore(),
                new DrawCommand.FillRect(new Rect(40, 0, 10, 10), red), new DrawCommand.StrokePath(line, blue, 4),
                new DrawCommand.Image(new RgbImage(1, 1, new byte[] {0, 0, (byte) 255}, new byte[] {(byte) 128}), new Rect(60, 0, 10, 10))));
        var bytes = new ByteArrayOutputStream(); new RasterBackend(72).writePage(page, bytes);
        var image = ImageIO.read(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(0xff7f7f, image.getRGB(5, 5) & 0xffffff);
        assertEquals(0xffffff, image.getRGB(15, 5) & 0xffffff);
        assertEquals(0xff0000, image.getRGB(25, 5) & 0xffffff);
        assertEquals(0xffffff, image.getRGB(35, 5) & 0xffffff);
        assertEquals(0xff7f7f, image.getRGB(45, 5) & 0xffffff);
        assertEquals(0xbfbfff, image.getRGB(5, 30) & 0xffffff);
        assertEquals(0xbfbfff, image.getRGB(65, 5) & 0xffffff);
    }

    @Test void invalidAlphaIsRejectedBeforeAnyDisplayCanBeBuilt() {
        for (double value : new double[] {-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new DrawCommand.Opacity(value, 1));
            assertThrows(IllegalArgumentException.class, () -> new DrawCommand.Opacity(1, value));
        }
    }
}
