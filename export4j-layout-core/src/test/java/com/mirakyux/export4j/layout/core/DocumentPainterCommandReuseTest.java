// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.GlyphRun;
import com.mirakyux.export4j.text.PositionedGlyph;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DocumentPainterCommandReuseTest {
    private static final PageSize SIZE = new PageSize(4096, 4096);
    private static final Rect CONTENT = new Rect(0, 0, 4096, 4096);
    private static FontData font;
    private static FontData independentIdentity;

    @BeforeAll
    static void fonts() throws IOException {
        font = loadFont();
        independentIdentity = loadFont();
    }

    @Test
    void equalRepeatedInkKeepsEveryCommandSlotAndItsExactFontIdentity() {
        var original = text(run(font, 10, 0, 5), new Point(3, 8), RgbColor.BLACK);
        var equal = text(run(font, 10, 0, 5), new Point(3, 8), RgbColor.BLACK);
        try (var session = DocumentPainter.openSession()) {
            var first = session.paintPage(page(0, List.of(original, original)));
            var second = session.paintPage(page(1, List.of(equal, equal)));
            assertEquals(first.commands(), second.commands());
            var firstInk = glyphs(first);
            var secondInk = glyphs(second);
            assertEquals(2, secondInk.size());
            assertSame(firstInk.getFirst(), secondInk.getFirst());
            assertSame(firstInk.getFirst(), secondInk.getLast());
            assertSame(font, secondInk.getFirst().run().font());
            for (var different : List.of(
                    text(run(independentIdentity, 10, 0, 5), new Point(3, 8), RgbColor.BLACK),
                    text(run(font, 11, 0, 5), new Point(3, 8), RgbColor.BLACK),
                    text(run(font, 10, 0x220000, 5), new Point(3, 8), RgbColor.BLACK),
                    text(run(font, 10, 0, Math.nextUp(5.0)), new Point(3, 8), RgbColor.BLACK),
                    text(run(font, 10, 0, 5), new Point(Math.nextUp(3.0), 8), RgbColor.BLACK),
                    text(run(font, 10, 0, 5), new Point(3, 8), new RgbColor(.5, 0, 0)))) {
                var output = glyphs(session.paintPage(page(2, List.of(different)))).getFirst();
                assertNotSame(firstInk.getFirst(), output);
                assertEquals(different, output);
            }
        }
    }

    @Test
    void oversizedInkCannotEvictSmallReusableCommandsOrChangeEarlierOutput() {
        var firstInk = text(run(font, 10, 0, 5), new Point(3, 8), RgbColor.BLACK);
        var hugeRun = new GlyphRun(font, 10,
                Collections.nCopies(65_536, new PositionedGlyph('A', 1, 5)));
        try (var session = DocumentPainter.openSession()) {
            var first = session.paintPage(page(0, List.of(firstInk)));
            var huge = text(hugeRun, new Point(0, 8), RgbColor.BLACK);
            var oversized = session.paintPage(page(1, List.of(huge)));
            assertSame(huge, glyphs(oversized).getFirst());
            var second = session.paintPage(page(2, List.of(text(run(font, 10, 0, 5), new Point(3, 8), RgbColor.BLACK))));
            assertSame(glyphs(first).getFirst(), glyphs(second).getFirst());
            assertEquals(List.of(firstInk), glyphs(first));
        }
    }

    @Test
    void uniqueGeometryEvictsOldCommandsWithoutChangingTheirValuesOrOrder() {
        var firstFill = new DrawCommand.FillRect(new Rect(1, 2, 3, 4), RgbColor.BLACK);
        try (var session = DocumentPainter.openSession()) {
            var first = session.paintPage(page(0, List.of(firstFill)));
            var unique = new ArrayList<DrawCommand>();
            for (int index = 0; index < 1100; index++) {
                unique.add(new DrawCommand.FillRect(new Rect(index + 10, 2, 3, 4), RgbColor.BLACK));
            }
            var middle = session.paintPage(page(1, unique));
            assertEquals(unique, middle.commands().subList(2, middle.commands().size() - 1));
            var equal = new DrawCommand.FillRect(new Rect(1, 2, 3, 4), RgbColor.BLACK);
            var last = session.paintPage(page(2, List.of(equal)));
            assertNotSame(first.commands().get(2), last.commands().get(2));
            assertEquals(first.commands(), last.commands());
        }
    }

    private static LayoutPage page(int index, List<DrawCommand> commands) {
        return new LayoutPage(index, SIZE, CONTENT, List.of(new ComponentFragment("component", 0,
                new Point(0, 0), new ComponentLayout(4096, 4096, 0, commands, List.of()))));
    }

    private static List<DrawCommand.GlyphText> glyphs(DisplayPage page) {
        return page.commands().stream().filter(DrawCommand.GlyphText.class::isInstance)
                .map(DrawCommand.GlyphText.class::cast).toList();
    }

    private static DrawCommand.GlyphText text(GlyphRun run, Point baseline, RgbColor color) {
        return new DrawCommand.GlyphText(run, baseline, color);
    }

    private static GlyphRun run(FontData data, double size, int rgb, double advance) {
        return new GlyphRun(data, size, List.of(new PositionedGlyph('A', 1, advance)), rgb, 0, false, false);
    }

    private static FontData loadFont() throws IOException {
        try (var input = DocumentPainterCommandReuseTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            if (input == null) { throw new IOException("missing explicit test font"); }
            return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES).data();
        }
    }
}
