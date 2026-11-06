// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.ImageBox;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.TextLine;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class TextLayoutImageReuseTest {
    @Test
    void sharedCacheReusesEncodedIdentityWithoutDroppingPlacementCommands() throws IOException {
        var resource = resource(1, 1, 0x80ff0022);
        var equivalent = ImageResource.load(resource.bytes());
        var cache = new TextLayoutPainter.ImageCache();
        var builder = new DisplayListBuilder(new PageSize(64, 64));
        TextLayoutPainter.draw(builder, layout(resource, 6, 5, 2), new Point(3, 4), RgbColor.BLACK, cache);
        TextLayoutPainter.draw(builder, layout(equivalent, 8, 4, -1), new Point(13, 14), RgbColor.BLACK, cache);
        var commands = builder.build().commands();
        assertEquals(2, commands.size());
        var first = (DrawCommand.Image) commands.getFirst();
        var second = (DrawCommand.Image) commands.getLast();
        assertSame(first.image(), second.image());
        assertEquals(new Rect(3, 9, 6, 5), first.bounds());
        assertEquals(new Rect(13, 17, 8, 4), second.bounds());
        cache.clear();
        assertNotSame(first.image(), draw(resource, cache));
    }

    @Test
    void entryLimitEvictsLeastRecentlyUsedResources() throws IOException {
        var red = resource(1, 1, 0xffff0000);
        var green = resource(1, 1, 0xff00ff00);
        var blue = resource(1, 1, 0xff0000ff);
        var cache = new TextLayoutPainter.ImageCache(2, 1024);
        var firstRed = draw(red, cache);
        var firstGreen = draw(green, cache);
        assertSame(firstRed, draw(red, cache));
        draw(blue, cache);
        assertSame(firstRed, draw(red, cache));
        assertNotSame(firstGreen, draw(green, cache));
    }

    @Test
    void decodedByteLimitEvictsResourcesIndependentlyOfTheEntryLimit() throws IOException {
        var red = resource(2, 1, 0xffff0000);
        var green = resource(2, 1, 0xff00ff00);
        var cache = new TextLayoutPainter.ImageCache(8, 8);
        var firstRed = draw(red, cache);
        draw(green, cache);
        assertNotSame(firstRed, draw(red, cache));
    }

    @Test
    void oversizedResourcesAreNotRetainedAndDoNotDisplaceHotSmallResources() throws IOException {
        var small = resource(1, 1, 0xffff0000);
        var oversized = resource(2, 1, 0xff00ff00);
        var cache = new TextLayoutPainter.ImageCache(2, 4);
        var firstSmall = draw(small, cache);
        var firstOversized = draw(oversized, cache);
        assertNotSame(firstOversized, draw(oversized, cache));
        assertSame(firstSmall, draw(small, cache));
    }

    @Test
    void aWarmCacheStillObservesCancellationBeforeEmittingCommands() throws IOException {
        var resource = resource(1, 1, 0xffff0000);
        var cache = new TextLayoutPainter.ImageCache();
        draw(resource, cache);
        var cancelled = new AtomicBoolean();
        var options = ExecutionOptions.builder().cancelled(cancelled::get).build();
        var builder = new DisplayListBuilder(new PageSize(64, 64));
        var layout = layout(resource, 6, 6, 0);
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options, () -> {
            cancelled.set(true);
            TextLayoutPainter.draw(builder, layout, new Point(0, 0), RgbColor.BLACK, cache);
            return null;
        }));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        assertEquals(List.of(), builder.build().commands());
    }

    private static RgbImage draw(ImageResource resource, TextLayoutPainter.ImageCache cache) {
        var builder = new DisplayListBuilder(new PageSize(64, 64));
        TextLayoutPainter.draw(builder, layout(resource, 6, 6, 0), new Point(0, 0), RgbColor.BLACK, cache);
        return ((DrawCommand.Image) builder.build().commands().getFirst()).image();
    }

    private static ParagraphLayout layout(ImageResource resource, double width, double height, double shift) {
        var image = new InlineImage(resource, width, height, shift, "sample");
        var line = new TextLine(0, 1, 0, 8, width, List.of(), List.of(), List.of(new ImageBox(0, image, 0)));
        return new ParagraphLayout(32, 16, List.of(line), List.of());
    }

    private static ImageResource resource(int width, int height, int argb) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        try {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    image.setRGB(x, y, argb);
                }
            }
            try (var bytes = new ByteArrayOutputStream()) {
                if (!ImageIO.write(image, "png", bytes)) {
                    throw new IOException("PNG writer unavailable");
                }
                return ImageResource.load(bytes.toByteArray());
            }
        } finally {
            image.flush();
        }
    }
}
