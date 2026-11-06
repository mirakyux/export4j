// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.layout.CellFragment;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.TableRowFragment;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.ImageBox;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.TextLine;

class DocumentPainterSessionTest {
    private static final PageSize SIZE = new PageSize(100, 100);
    private static final Rect CONTENT = new Rect(0, 0, 100, 100);
    private static final ParagraphLayout EMPTY = new ParagraphLayout(1, 1,
            List.of(new TextLine(0, 0, 0, 0, 0, List.of())), List.of());

    @Test
    void sessionReusesImagesAndPathsAcrossPagesWhileIndependentSessionsDoNot() throws IOException {
        var image = imageLayout();
        DisplayPage first;
        DisplayPage second;
        try (var session = DocumentPainter.openSession()) {
            first = session.paintPage(page(0, image));
            second = session.paintPage(page(1, image));
        }
        assertEquals(first.commands(), second.commands());
        assertSame(stroke(first).path(), stroke(second).path());
        assertSame(image(first).image(), image(second).image());
        try (var independent = DocumentPainter.openSession()) {
            var third = independent.paintPage(page(0, image));
            assertEquals(first.commands().size(), third.commands().size());
            assertEquals(stroke(first), stroke(third));
            assertEquals(image(first).bounds(), image(third).bounds());
            assertNotSame(stroke(first).path(), stroke(third).path());
            assertNotSame(image(first).image(), image(third).image());
        }
        assertEquals(new Rect(10, 30, 6, 6), image(first).bounds());
        assertEquals(5, stroke(first).path().segments().size());
    }

    @Test
    void closureIsIdempotentAndNeverInvalidatesAlreadyPaintedCommands() throws IOException {
        var session = DocumentPainter.openSession();
        var page = page(0, imageLayout());
        var output = session.paintPage(page);
        var commands = List.copyOf(output.commands());
        session.close();
        session.close();
        assertEquals(commands, output.commands());
        assertThrows(IllegalStateException.class, () -> session.paintPage(page));
        assertEquals(new Rect(10, 30, 6, 6), image(output).bounds());
    }

    @Test
    void sessionRejectsPaintingAndClosureFromAnotherThread() throws Exception {
        var page = page(0, imageLayout());
        try (var session = DocumentPainter.openSession(); var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                assertThrows(IllegalStateException.class, () -> session.paintPage(page));
                assertThrows(IllegalStateException.class, session::close);
            }).get(10, TimeUnit.SECONDS);
            assertEquals(2, session.paintPage(page).commands().size());
        }
    }

    private static LayoutPage page(int index, ParagraphLayout image) {
        var cell = new CellFragment(0, new Rect(0, 0, 20, 10), new Point(0, 0), EMPTY);
        var table = new TableFragment("table", 0, new Point(10, 10), 20, 10,
                List.of(new TableRowFragment(0, false, false, List.of(cell))));
        return new LayoutPage(index, SIZE, CONTENT, List.of(table,
                new ParagraphFragment("image", 1, 0, new Point(10, 30), image)));
    }

    private static DrawCommand.StrokePath stroke(DisplayPage page) {
        return page.commands().stream().filter(DrawCommand.StrokePath.class::isInstance)
                .map(DrawCommand.StrokePath.class::cast).findFirst().orElseThrow();
    }

    private static DrawCommand.Image image(DisplayPage page) {
        return page.commands().stream().filter(DrawCommand.Image.class::isInstance)
                .map(DrawCommand.Image.class::cast).findFirst().orElseThrow();
    }

    private static ParagraphLayout imageLayout() throws IOException {
        var bitmap = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        ImageResource resource;
        try {
            bitmap.setRGB(0, 0, 0x80ff0022);
            try (var bytes = new ByteArrayOutputStream()) {
                if (!ImageIO.write(bitmap, "png", bytes)) { throw new IOException("PNG writer unavailable"); }
                resource = ImageResource.load(bytes.toByteArray());
            }
        } finally {
            bitmap.flush();
        }
        var image = new InlineImage(resource, 6, 6, 0, "sample");
        var line = new TextLine(0, 1, 0, 6, 6, List.of(), List.of(), List.of(new ImageBox(0, image, 0)));
        return new ParagraphLayout(8, 8, List.of(line), List.of());
    }
}
