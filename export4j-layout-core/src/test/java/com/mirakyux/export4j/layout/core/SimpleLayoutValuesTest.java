// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.font.FontFaceCache;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.text.FieldBox;
import com.mirakyux.export4j.text.GlyphRun;
import com.mirakyux.export4j.text.ImageBox;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.PositionedGlyph;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextLine;
import com.mirakyux.export4j.text.TextOutline;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SimpleLayoutValuesTest {
    private static FontData font;
    private static FontData otherIdentity;

    @BeforeAll static void loadFonts() throws IOException {
        font = loadFont();
        otherIdentity = loadFont();
    }

    @Test void reusesEqualImmutableValuesWithoutLosingSourceFontStyleOrExactAdvances() {
        var values = new SimpleLayoutValues();
        var ordinaryRun = run(font, 10, 0, 0, false, false, 6);
        var ordinary = layout(100, 0, 1, ordinaryRun);
        var equal = copy(ordinary);
        assertNotSame(ordinary, equal);
        assertSame(ordinary, values.reuse(ordinary));
        assertSame(ordinary, values.reuse(equal));
        assertThrows(UnsupportedOperationException.class,
                () -> ordinary.lines().getFirst().runs().getFirst().glyphs().clear());
        assertNotSame(font, otherIdentity);
        assertEquals(font.id(), otherIdentity.id());

        var diagnostic = new TextDiagnostic(TextDiagnostic.Code.FONT_FALLBACK, 0, 1, font.id());
        for (var distinct : List.of(
                layout(100, 1, 2, ordinaryRun),
                layout(100, 0, 2, ordinaryRun),
                layout(100, 0, 1, run(otherIdentity, 10, 0, 0, false, false, 6)),
                layout(100, 0, 1, run(font, 11, 0, 0, false, false, 6)),
                layout(100, 0, 1, run(font, 10, 0xcc2200, 0, false, false, 6)),
                layout(100, 0, 1, run(font, 10, 0, 1, false, false, 6)),
                layout(100, 0, 1, run(font, 10, 0, 0, true, false, 6)),
                layout(100, 0, 1, run(font, 10, 0, 0, false, true, 6)),
                layout(100, 0, 1, run(font, 10, 0, 0, false, false, Math.nextUp(6.0))),
                new ParagraphLayout(ordinary.width(), ordinary.height(), ordinary.lines(), List.of(diagnostic)))) {
            assertSame(distinct, values.reuse(distinct));
            assertSame(distinct, values.reuse(copy(distinct)));
        }
        var positiveZero = layout(100, 0, 1, run(font, 10, 0, 0, false, false, 0.0));
        var negativeZero = layout(100, 0, 1, run(font, 10, 0, 0, false, false, -0.0));
        assertSame(positiveZero, values.reuse(positiveZero));
        assertSame(negativeZero, values.reuse(negativeZero));
        assertSame(negativeZero, values.reuse(copy(negativeZero)));
        assertSame(ordinary, values.reuse(copy(ordinary)));
    }

    @Test void leavesOutlinesImagesAndUnresolvedFieldsOutsideThePool() throws IOException {
        var values = new SimpleLayoutValues();
        var marker = new TextOutline("\ufffc", List.of(), 0);
        var image = new ImageBox(0, new InlineImage(image(), 10, 10, 0, ""), 0);
        var field = new FieldBox(0, new InlineField(InlineField.Kind.PAGE, "", 10, InlineStyle.of(10)), 0, 0);
        for (var line : List.of(
                new TextLine(0, 1, 0, 8, 10, List.of(), List.of(marker)),
                new TextLine(0, 1, 0, 8, 10, List.of(), List.of(), List.of(image)),
                new TextLine(0, 1, 0, 8, 10, List.of(), List.of(marker), List.of(), List.of(field)))) {
            var measured = new ParagraphLayout(100, 12, List.of(line), List.of());
            var equal = copy(measured);
            assertEquals(measured, equal);
            assertNotSame(measured, equal);
            assertSame(measured, values.reuse(measured));
            assertSame(equal, values.reuse(equal));
        }
    }

    @Test void entryAndUnitEvictionPreserveAlreadyEmittedValuesAndAllowRebuilding() {
        assertEvictionPreservesValues(1100, 1);
        assertEvictionPreservesValues(3, 32_766);
    }

    @Test void oversizedGlyphOrDiagnosticValuesDoNotDisplaceReusableSmallLayouts() {
        var values = new SimpleLayoutValues();
        var small = layout(100, 0, 1, run(font, 10, 0, 0, false, false, 6));
        assertSame(small, values.reuse(small));
        var manyGlyphs = new GlyphRun(font, 10,
                Collections.nCopies(65_535, new PositionedGlyph('A', 1, 6)));
        var diagnostic = new TextDiagnostic(TextDiagnostic.Code.FONT_FALLBACK, 0, 1, font.id());
        for (var oversized : List.of(
                layout(manyGlyphs.width() + 10, 0, manyGlyphs.glyphs().size(), manyGlyphs),
                new ParagraphLayout(small.width(), small.height(), small.lines(),
                        Collections.nCopies(65_534, diagnostic)))) {
            var equal = copy(oversized);
            assertSame(oversized, values.reuse(oversized));
            assertSame(equal, values.reuse(equal));
            assertSame(small, values.reuse(copy(small)));
        }
    }

    private static void assertEvictionPreservesValues(int additionalLayouts, int glyphCount) {
        var values = new SimpleLayoutValues();
        var run = new GlyphRun(font, 10,
                Collections.nCopies(glyphCount, new PositionedGlyph('A', 1, 6)));
        double width = run.width() + 10;
        var first = layout(width, 0, glyphCount, run);
        var snapshot = copy(first);
        assertSame(first, values.reuse(first));
        for (int index = 1; index <= additionalLayouts; index++) {
            var next = layout(width + index, 0, glyphCount, run);
            assertSame(next, values.reuse(next));
        }
        var rebuilt = copy(first);
        assertSame(rebuilt, values.reuse(rebuilt));
        assertNotSame(first, rebuilt);
        assertEquals(snapshot, first);
        assertEquals(first, rebuilt);
        assertSame(rebuilt, values.reuse(copy(first)));
    }

    private static GlyphRun run(FontData data, double size, int rgb, double shift,
            boolean underline, boolean strikeThrough, double advance) {
        return new GlyphRun(data, size, List.of(new PositionedGlyph('A', 1, advance)),
                rgb, shift, underline, strikeThrough);
    }

    private static ParagraphLayout layout(double width, int sourceStart, int sourceEnd, GlyphRun run) {
        return new ParagraphLayout(width, 12,
                List.of(new TextLine(sourceStart, sourceEnd, 0, 8, run.width(), List.of(run))), List.of());
    }

    private static ParagraphLayout copy(ParagraphLayout original) {
        return new ParagraphLayout(original.width(), original.height(), original.lines(), original.diagnostics());
    }

    private static FontData loadFont() throws IOException {
        // The pool must distinguish equal font content held by independently parsed font objects.
        try (var cache = FontFaceCache.builder().enabled(false).build();
                var input = SimpleLayoutValuesTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            if (input == null) { throw new IOException("missing explicit test font"); }
            return cache.load(input).data();
        }
    }

    private static ImageResource image() throws IOException {
        var raster = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        try (var bytes = new ByteArrayOutputStream()) {
            if (!ImageIO.write(raster, "png", bytes)) { throw new IOException("PNG writer unavailable"); }
            return ImageResource.load(bytes.toByteArray());
        } finally {
            raster.flush();
        }
    }
}
