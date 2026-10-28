// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.opentype;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirakyux.export4j.font.MissingGlyphPolicy;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OpenTypeTest {
    private static byte[] bytes(String name) throws IOException {
        try (var input = OpenTypeTest.class.getResourceAsStream("/fonts/" + name)) {
            if (input == null) { throw new IOException("test font is missing"); }
            return input.readAllBytes();
        }
    }
    private static OpenTypeFont font(String name) throws IOException { return OpenTypeFont.load(bytes(name)); }
    private static ComponentContext context(double width, double height) throws IOException {
        return new ComponentContext(width, height, List.of(TrueTypeFontLoader.load(bytes("LiberationSans-Regular.ttf"))), 0, 0);
    }
    @Test void validatesFontAndExplicitAxesBeforeNativeUse() throws Exception {
        assertThrows(OpenTypeException.class, () -> OpenTypeFont.load(new byte[12]));
        var f = font("RobotoFlex.ttf");
        assertEquals(13, f.axes().size());
        assertEquals(OpenTypeException.Code.INVALID_AXIS, assertThrows(OpenTypeException.class, () -> f.withAxes(Map.of("fake", 1d))).code());
        assertThrows(OpenTypeException.class, () -> f.withAxes(Map.of("wght", Double.NaN)));
        assertThrows(OpenTypeException.class, () -> f.withAxes(Map.of("wght", 1001d)));
        var values = new HashMap<String, Double>(); values.put("wght", 800d); var instance = f.withAxes(values); values.clear();
        assertEquals(Map.of("wght", 800d), instance.coordinates());
        assertTrue(f.coordinates().isEmpty());
    }
    @Test void everyVariableAxisChangesRealOutlines() throws Exception {
        var f = font("RobotoFlex.ttf"); String source = "Hamburgefontsiv HÊjgpy 0123456789";
        var normal = OpenTypeShaper.shape(f, source, OpenTypeShaper.Options.of(24));
        for (var axis : f.axes()) {
            double coordinate = axis.maximum() != axis.defaultValue() ? axis.maximum() : axis.minimum();
            var varied = OpenTypeShaper.shape(f.withAxes(Map.of(axis.tag(), coordinate)), source, OpenTypeShaper.Options.of(24));
            assertNotEquals(normal.glyphs().stream().map(OpenTypeShaper.Glyph::outlines).toList(), varied.glyphs().stream().map(OpenTypeShaper.Glyph::outlines).toList(), axis.tag());
        }
    }
    @Test void variableCff2ChangesOutlineAndAdvance() throws Exception {
        var f = font("SourceSans3VF-Upright.otf");
        assertEquals(OpenTypeFont.OutlineFormat.CFF2, f.outlineFormat());
        var light = OpenTypeShaper.shape(f, "Hamburgefontsiv", OpenTypeShaper.Options.of(24));
        var bold = OpenTypeShaper.shape(f.withAxes(Map.of("wght", 900d)), "Hamburgefontsiv", OpenTypeShaper.Options.of(24));
        assertNotEquals(light.xAdvance(), bold.xAdvance()); assertNotEquals(light.glyphs().getFirst().outlines(), bold.glyphs().getFirst().outlines());
    }
    @Test void verticalMetricsAndSubstitutionAreActuallyApplied() throws Exception {
        var f = font("LXGWWenKaiTC-Regular.ttf");
        var horizontal = OpenTypeShaper.shape(f, "「天地」，", OpenTypeShaper.Options.of(18));
        var vertical = OpenTypeShaper.shape(f, "「天地」，", OpenTypeShaper.Options.of(18).withDirection(OpenTypeShaper.Direction.TOP_TO_BOTTOM));
        assertEquals(0, vertical.xAdvance()); assertTrue(vertical.yAdvance() > 0);
        assertNotEquals(horizontal.glyphs().getFirst().glyphId(), vertical.glyphs().getFirst().glyphId());
        assertTrue(vertical.glyphs().stream().allMatch(value -> value.sourceEnd() > value.sourceStart()));
    }
    @ParameterizedTest
    @CsvSource({"NotoNaskhArabic-Regular.ttf,السَّلَامُ", "NotoSansDevanagari-Regular.ttf,नमस्ते", "NotoSansThai-Regular.ttf,ภาษาไทย",
            "NotoSansBengali-Regular.ttf,বাংলা", "NotoSansTamil-Regular.ttf,தமிழ்", "NotoSansMyanmar-Regular.ttf,မြန်မာ",
            "NotoSansKhmer-Regular.ttf,ភាសាខ្មែរ", "NotoSansSinhala-Regular.ttf,සිංහල", "NotoSansHebrew-Regular.ttf,שָׁלוֹם"})
    void explicitScriptsRetainWholeUtf16Clusters(String font, String source) throws Exception {
        var run = OpenTypeShaper.shape(font(font), source, OpenTypeShaper.Options.of(18));
        assertFalse(run.glyphs().isEmpty()); assertTrue(run.glyphs().stream().allMatch(value -> value.glyphId() != 0));
        assertEquals(source.length(), run.glyphs().stream().mapToInt(OpenTypeShaper.Glyph::sourceEnd).max().orElseThrow());
        for (var value : run.glyphs()) { assertTrue(OpenTypeShaper.boundary(source, value.sourceStart())); assertTrue(OpenTypeShaper.boundary(source, value.sourceEnd())); }
    }
    @Test void sourceRangesSurviveVerticalPagingAndTateChuYoko() throws Exception {
        String source = "天地12玄黃宇宙洪荒「文字」，".repeat(5);
        var component = OpenTypeText.builder(source, List.of(font("LXGWWenKaiTC-Regular.ttf"))).fontSize(18).lineAdvance(28)
                .flow(OpenTypeText.Flow.VERTICAL_RIGHT_TO_LEFT).tateChuYoko(2, 4).build();
        var result = component.layoutDetails(context(70, 90), 90);
        assertTrue(result.parts().size() > 1);
        var placements = result.placements().stream().sorted(java.util.Comparator.comparingInt(OpenTypeText.Placement::sourceStart)).toList();
        int cursor = 0;
        for (var placement : placements) { assertEquals(cursor, placement.sourceStart()); cursor = placement.sourceEnd(); }
        assertEquals(source.length(), cursor);
        assertTrue(placements.stream().anyMatch(value -> value.tateChuYoko() && value.sourceStart() == 2 && value.sourceEnd() == 4));
    }
    @Test void shortInitialRegionAndCrLfPreserveSourceExactlyOnce() throws Exception {
        String source = "Alpha\r\nBeta\n\nGamma";
        var component = OpenTypeText.builder(source, List.of(font("RobotoFlex.ttf"))).fontSize(18).lineAdvance(28).build();
        var result = component.layoutDetails(context(70, 60), 5);
        assertTrue(result.parts().size() >= 2);
        assertEquals(source.length(), result.placements().stream().mapToInt(value -> value.sourceEnd() - value.sourceStart()).sum());
    }
    @Test void missingGlyphsFailOrRetainOriginalTextWithWarning() throws Exception {
        var f = font("LiberationSans-Regular.ttf");
        assertEquals(OpenTypeException.Code.MISSING_GLYPH, assertThrows(OpenTypeException.class,
                () -> OpenTypeShaper.shape(f, "中", OpenTypeShaper.Options.of(12))).code());
        var result = OpenTypeText.builder("中", List.of(f)).missingGlyphPolicy(MissingGlyphPolicy.PLACEHOLDER_WITH_WARNING, '?').build().layoutDetails(context(100, 100), 100);
        assertEquals(1, result.parts().getFirst().diagnostics().size());
        assertEquals("中", result.parts().getFirst().commands().stream().filter(value -> value instanceof DrawCommand.OutlineText).map(value -> ((DrawCommand.OutlineText) value).logicalText()).reduce("", String::concat));
    }
    @Test void malformedTateChuYokoAndInsufficientWorkAreRejected() throws Exception {
        var f = font("RobotoFlex.ttf");
        assertThrows(IllegalArgumentException.class, () -> OpenTypeText.builder("a\u0301", List.of(f)).flow(OpenTypeText.Flow.VERTICAL_LEFT_TO_RIGHT).tateChuYoko(0, 1).build());
        var component = OpenTypeText.builder("Alpha Beta Gamma", List.of(f)).workBudget(1, 100).build();
        assertEquals(OpenTypeException.Code.BUDGET_EXCEEDED, assertThrows(OpenTypeException.class, () -> component.layoutParts(context(30, 100))).code());
    }
    @Test void mixedDirectionFallbackRetainsLogicalOrderAndWholeRunDiagnostics() throws Exception {
        String source = "Java السَّلَامُ שָׁלוֹם 123";
        var result = OpenTypeText.builder(source, List.of(font("RobotoFlex.ttf"), font("NotoNaskhArabic-Regular.ttf"), font("NotoSansHebrew-Regular.ttf")))
                .fontSize(18).lineAdvance(45).build().layoutDetails(context(500, 100), 100);
        String logical = result.parts().stream().flatMap(part -> part.commands().stream()).filter(command -> command instanceof DrawCommand.OutlineText)
                .map(command -> ((DrawCommand.OutlineText) command).logicalText()).reduce("", String::concat);
        assertEquals(source, logical);
        assertTrue(result.parts().getFirst().diagnostics().stream().anyMatch(value -> value.sourceEnd() - value.sourceStart() > 1));
    }
    @Test void colorLayersPalettesAndBitmapHaveVisiblePixels() throws Exception {
        for (String name : List.of("Export4j-ColorV0-Test.ttf", "Export4j-ColorV1-Test.ttf", "NotoColorEmoji-COLRv1.ttf", "NotoColorEmoji-sbix.ttf")) {
            var f = font(name); String text = name.startsWith("Export4j") ? "A" : "😀";
            var run = OpenTypeShaper.shape(f, text, OpenTypeShaper.Options.of(36));
            assertTrue(run.glyphs().getFirst().outlines().isEmpty());
            var image = (DrawCommand.Image) run.glyphs().getFirst().colorCommands().getFirst();
            boolean visible = false; for (byte alpha : image.image().alpha()) { if (alpha != 0) { visible = true; break; } }
            assertTrue(visible, name);
            if (name.startsWith("Export4j")) {
                var second = OpenTypeShaper.shape(f.withPalette(1), text, OpenTypeShaper.Options.of(36));
                var other = (DrawCommand.Image) second.glyphs().getFirst().colorCommands().getFirst();
                assertFalse(java.util.Arrays.equals(image.image().pixels(), other.image().pixels()), name);
            }
        }
    }
    @Test void allColrv1CompositeAndGradientGlyphsRenderWithoutMonochromeFallback() throws Exception {
        var f = font("Export4j-ColorV1-Test.ttf");
        for (int cp = 65; cp < 97; cp++) {
            var run = OpenTypeShaper.shape(f, Character.toString(cp), OpenTypeShaper.Options.of(18));
            assertTrue(run.glyphs().getFirst().outlines().isEmpty());
        }
    }
    @Test void colorPixelBudgetAndCooperativeCancellationFailExplicitly() throws Exception {
        var f = font("NotoColorEmoji-COLRv1.ttf");
        var options = new OpenTypeShaper.Options(100, OpenTypeShaper.Direction.AUTO, "", "und", List.of(), 144, 10, 100);
        assertEquals(OpenTypeException.Code.BUDGET_EXCEEDED, assertThrows(OpenTypeException.class, () -> OpenTypeShaper.shape(f, "😀", options)).code());
        var execution = ExecutionOptions.builder().cancelled(() -> true).build();
        assertThrows(ExecutionException.class, () -> ExecutionContext.execute(execution, () -> OpenTypeShaper.shape(f, "😀", OpenTypeShaper.Options.of(18))));
    }
    @Test void complexColorGlyphCannotAllocateUnboundedIntermediateSurfaces() throws Exception {
        var f = font("NotoColorEmoji-COLRv1.ttf");
        var options = new OpenTypeShaper.Options(1000, OpenTypeShaper.Direction.AUTO, "", "und", List.of(), 144, 16_000_000, 100);
        var failure = assertThrows(OpenTypeException.class, () -> OpenTypeShaper.shape(f, "😀", options));
        assertEquals(OpenTypeException.Code.BUDGET_EXCEEDED, failure.code());
        assertTrue(failure.getMessage().contains("intermediate"));
    }
}
