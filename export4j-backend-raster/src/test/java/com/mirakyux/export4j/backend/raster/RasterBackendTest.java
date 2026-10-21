// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.raster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;

class RasterBackendTest {
    @Test void transformsClipsAlphaAndRestoresArePaintedAtExplicitDpi() throws IOException {
        var page = new DisplayPage(new PageSize(60, 40), List.of(new DrawCommand.Save(), new DrawCommand.Transform(AffineTransform.translation(10, 0)),
                new DrawCommand.Clip(new Rect(0, 0, 10, 10)), new DrawCommand.FillRect(new Rect(0, 0, 30, 30), new RgbColor(1, 0, 0)), new DrawCommand.Restore(),
                new DrawCommand.Image(new RgbImage(1, 1, new byte[] {0, 0, (byte) 255}, new byte[] {(byte) 128}), new Rect(30, 0, 10, 10))));
        var bytes = new TrackingOutput(); new RasterBackend(144).writePage(page, bytes);
        assertFalse(bytes.closed);
        var image = ImageIO.read(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(120, image.getWidth()); assertEquals(80, image.getHeight());
        assertEquals(0xff0000, image.getRGB(30, 10) & 0xffffff);
        assertEquals(0xffffff, image.getRGB(50, 10) & 0xffffff);
        assertEquals(0x7f7fff, image.getRGB(70, 10) & 0xffffff);
    }
    @Test void pageZipAndPixelBudgetAreEnforced() throws IOException {
        var page = new DisplayPage(new PageSize(60, 40), List.of());
        var bytes = new TrackingOutput(); new RasterBackend(72).write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(page, page)), bytes);
        assertFalse(bytes.closed);
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertEquals("page-00001.png", zip.getNextEntry().getName()); zip.closeEntry();
            assertEquals("page-00002.png", zip.getNextEntry().getName());
        }
        var huge = new DisplayPage(new PageSize(14_400, 14_400), List.of());
        var output = new ByteArrayOutputStream();
        assertThrows(IllegalArgumentException.class, () -> new RasterBackend(1200).writePage(huge, output)); assertEquals(0, output.size());
    }
    private static final class TrackingOutput extends ByteArrayOutputStream { private boolean closed; @Override public void close() { closed = true; } }
}
