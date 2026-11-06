// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class PhysicalFontIsolationTest {
    private static final String SOURCE = "AVATAR office a\u0301 and repeated words";
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    private record Variant(FontGroup fonts, double size, int rgb, double shift,
            boolean underline, boolean strikeThrough) { }

    @Test
    void oneProgramKeepsEachSizeAndRunFormatIndependentAcrossParagraphs() throws IOException {
        var fonts = FontGroup.strict(face("LiberationSans-Regular.ttf"));
        var variants = List.of(new Variant(fonts, 10, -1, 0, false, false),
                new Variant(fonts, 28, 0x114488, 2, true, true),
                new Variant(fonts, 16, 0x882211, -3, false, true),
                new Variant(fonts, 8, 0x008833, 1, true, false));
        var expected = variants.stream().map(PhysicalFontIsolationTest::layout).toList();
        assertFalse(expected.getFirst().lines().getFirst().outlines().isEmpty());
        assertFalse(expected.getFirst().equals(expected.get(1)));

        for (int round = 0; round < 3; round++) {
            for (int index = variants.size() - 1; index >= 0; index--) {
                assertEquals(expected.get(index), layout(variants.get(index)),
                        "another paragraph's size or run format polluted the cached physical font");
            }
        }
    }

    @Test
    void fontReloadAfterRepeatedLruEvictionKeepsTheOriginalProgramAndGlyphGeometry() throws Exception {
        var faces = List.of(face("LiberationSans-Regular.ttf"), face("LiberationSans-Bold.ttf"),
                face("LiberationSans-Italic.ttf"), face("LiberationSans-BoldItalic.ttf"));
        var cache = new ExplicitPhysicalFontCache(2, 64L * 1024 * 1024);
        for (int round = 0; round < 3; round++) {
            for (int index = 0; index < faces.size(); index++) {
                var data = faces.get((index + round) % faces.size()).data();
                float size = 9 + round * 7;
                Font actual = cache.get(data).deriveFont(size);
                Font expected;
                try (var input = new ByteArrayInputStream(data.bytes())) {
                    expected = Font.createFont(Font.TRUETYPE_FONT, input).deriveFont(size);
                }
                assertEquals(expected.getStringBounds(SOURCE, FRC), actual.getStringBounds(SOURCE, FRC));
                var expectedGlyphs = expected.createGlyphVector(FRC, SOURCE);
                var actualGlyphs = actual.createGlyphVector(FRC, SOURCE);
                assertEquals(expectedGlyphs.getNumGlyphs(), actualGlyphs.getNumGlyphs());
                assertArrayEquals(expectedGlyphs.getGlyphPositions(0, expectedGlyphs.getNumGlyphs() + 1, null),
                        actualGlyphs.getGlyphPositions(0, actualGlyphs.getNumGlyphs() + 1, null));
                for (int glyph = 0; glyph < expectedGlyphs.getNumGlyphs(); glyph++) {
                    assertEquals(expectedGlyphs.getGlyphOutline(glyph).getBounds2D(),
                            actualGlyphs.getGlyphOutline(glyph).getBounds2D());
                }
            }
        }
    }

    @Test
    @Timeout(45)
    void concurrentProgramsAndSizesPreserveTheirSerialLayoutResults() throws Exception {
        var variants = new ArrayList<Variant>();
        for (var name : List.of("LiberationSans-Regular.ttf", "LiberationSans-Bold.ttf",
                "LiberationSans-Italic.ttf", "LiberationSans-BoldItalic.ttf")) {
            var fonts = FontGroup.strict(face(name));
            variants.add(new Variant(fonts, 11, 0x225588, 0, false, true));
            variants.add(new Variant(fonts, 23, 0x882244, -2, true, false));
        }
        var expected = variants.stream().map(PhysicalFontIsolationTest::layout).toList();
        var gate = new CyclicBarrier(variants.size());
        var results = new ArrayList<Future<List<ParagraphLayout>>>();
        var executor = Executors.newFixedThreadPool(variants.size());
        try {
            for (var variant : variants) {
                results.add(executor.submit(() -> {
                    gate.await(10, TimeUnit.SECONDS);
                    var layouts = new ArrayList<ParagraphLayout>();
                    for (int round = 0; round < 3; round++) { layouts.add(layout(variant)); }
                    return List.copyOf(layouts);
                }));
            }
            for (int index = 0; index < results.size(); index++) {
                for (var actual : results.get(index).get(20, TimeUnit.SECONDS)) {
                    assertEquals(expected.get(index), actual,
                            "concurrent font or size changed another paragraph's outlines or sources");
                }
            }
        } finally {
            for (var result : results) { result.cancel(true); }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "layout workers did not terminate");
        }
    }

    private static ParagraphLayout layout(Variant variant) {
        var span = new StyledSpan(SOURCE, variant.fonts(), variant.size(), variant.rgb(), variant.shift(),
                variant.underline(), variant.strikeThrough());
        return new ParagraphLayouter().layoutOpenType(List.of(span), 180,
                new ParagraphStyle(variant.size(), 64, 0, 0, TextAlignment.LEFT));
    }

    private static FontFace face(String name) throws IOException {
        try (var input = PhysicalFontIsolationTest.class.getResourceAsStream("/fonts/" + name)) {
            return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
    }
}
