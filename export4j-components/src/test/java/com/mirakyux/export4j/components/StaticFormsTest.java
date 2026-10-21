// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.MissingGlyphPolicy;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.RegionFlow;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.text.InlineGraphic;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.StyledSpan;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextLayoutException;
import com.mirakyux.export4j.text.TextOutline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticFormsTest {
    private static FontGroup fonts;
    private static final ParagraphStyle STYLE = ParagraphStyle.builder().fontSize(10).lineHeight(16).build();
    @BeforeAll static void fonts() throws IOException {
        try (var input = StaticFormsTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }
    private static ComponentContext context(double width, double height) { return new ComponentContext(width, height, fonts, 0, 0, 1); }
    private static String logical(ComponentLayout part) {
        return part.commands().stream().map(command -> {
            if (command instanceof DrawCommand.OutlineText text) { return text.logicalText(); }
            if (command instanceof DrawCommand.GlyphText text) { return text.run().glyphs().stream().map(g -> g.text()).collect(Collectors.joining()); }
            return "";
        }).collect(Collectors.joining());
    }

    @Test void marksDoNotRequestFontGlyphsAndKeepOriginalUtf16Ranges() {
        assertTrue(fonts.fonts().stream().noneMatch(face -> face.glyph('☑').isPresent()));
        var text = FormText.builder("A☑ B□ C● D○").style(STYLE).build();
        var measured = text.measure(context(240, 100));
        assertEquals(text.text(), measured.lines().stream().flatMap(line -> line.outlines().stream()).map(TextOutline::logicalText).collect(Collectors.joining()));
        assertTrue(measured.diagnostics().isEmpty());
        assertEquals(0, measured.lines().getFirst().sourceStart());
        assertEquals(text.text().length(), measured.lines().getLast().sourceEnd());
        assertTrue(measured.lines().stream().allMatch(line -> line.fields().isEmpty() && line.images().isEmpty()));
        assertEquals(text.text(), logical(text.layout(context(240, 100))));
    }

    @Test void wrappingContinuationSkipsShortInitialRegionAndPrintsEveryOptionOnce() {
        var choices = ChoiceGroup.builder().style(STYLE).vertical(true);
        for (int index = 1; index <= 19; index++) { choices.option("OPT" + index, index % 2 == 0); }
        var group = choices.build();
        var parts = group.layoutRegions(context(130, 64), new FlowRegions(List.of(8.0, 32.0), 64));
        assertEquals(1, parts.getFirst().regionIndex());
        var text = parts.stream().map(p -> logical(p.layout())).collect(Collectors.joining());
        for (int index = 1; index <= 19; index++) { assertTrue(text.contains((index % 2 == 0 ? "☑" : "□") + " OPT" + index)); }
        assertEquals(19, text.chars().filter(c -> c == '□' || c == '☑').count());
        assertEquals(parts, group.layoutRegions(context(130, 64), new FlowRegions(List.of(8.0, 32.0), 64)));
        assertThrows(IllegalArgumentException.class, () -> group.layout(context(130, 64)));
    }

    @Test void strictAndPlaceholderPoliciesStillApplyToLabelsAtOriginalPositions() {
        String source = "☑ A\uD83D\uDE00 B\n□ C\uD83D\uDE00";
        var text = FormText.builder(source).style(STYLE).build();
        var failure = assertThrows(TextLayoutException.class, () -> text.layout(context(200, 100)));
        assertEquals(TextLayoutException.Code.MISSING_GLYPH, failure.code());
        assertEquals(3, failure.sourceIndex());
        var group = FontGroup.builder().fonts(fonts.fonts()).missingGlyphPolicy(MissingGlyphPolicy.PLACEHOLDER_WITH_WARNING).placeholderCodePoint('?').build();
        var parts = text.layoutRegions(context(200, 16).withFonts(group), new FlowRegions(List.of(), 16));
        var diagnostics = parts.stream().flatMap(part -> part.layout().diagnostics().stream())
                .filter(d -> d.code() == TextDiagnostic.Code.MISSING_GLYPH_REPLACED).toList();
        assertEquals(List.of(3, 11), diagnostics.stream().map(TextDiagnostic::sourceStart).toList());
        assertEquals(List.of(5, 13), diagnostics.stream().map(TextDiagnostic::sourceEnd).toList());
        assertTrue(parts.stream().map(p -> logical(p.layout())).collect(Collectors.joining()).contains("☑"));
    }

    @Test void vectorObjectsRejectInvalidGeometryMarkersAndOverflow() {
        var ink = new TextOutline("☑", List.of(new TextOutline.Segment(TextOutline.Operation.MOVE, List.of(0.0, -4.0))), -1);
        var graphic = new InlineGraphic(8, 4, 0, ink);
        var spans = List.of(new StyledSpan("\ufffc", fonts, 10, -1, 0, false, false));
        var layouter = new ParagraphLayouter();
        assertThrows(IllegalArgumentException.class, () -> new InlineGraphic(8, 3, 0, ink));
        assertThrows(IllegalArgumentException.class, () -> new InlineGraphic(Double.NaN, 4, 0, ink));
        assertThrows(IllegalArgumentException.class, () -> layouter.layout(spans, 100, STYLE, Map.of(), Map.of(), Map.of(1, graphic)));
        assertThrows(TextLayoutException.class, () -> layouter.layout(spans, 4, STYLE, Map.of(), Map.of(), Map.of(0, graphic)));
        assertThrows(TextLayoutException.class, () -> layouter.layout(spans, 100, STYLE, Map.of(), Map.of(),
                Map.of(0, new InlineGraphic(8, 40, 0, new TextOutline("X", List.of(), -1)))));
        assertThrows(IllegalArgumentException.class, () -> FormText.builder("☑".repeat(1025)).build().layout(context(200, 100)));
        assertThrows(IllegalArgumentException.class, () -> ChoiceGroup.builder().kind(ChoiceGroup.Kind.RADIO).option("A", true).option("B", true).build());
        assertThrows(IllegalArgumentException.class, () -> ChoiceGroup.builder().option("□", false));
    }

    @Test void bidiAndCombiningLabelsPreserveLogicalOrderAndVectorInk() throws IOException {
        var faces = new java.util.ArrayList<>(fonts.fonts());
        try (var input = StaticFormsTest.class.getResourceAsStream("/fonts/NotoNaskhArabic-Regular.ttf")) {
            faces.add(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES));
        }
        var text = FormText.builder("☑ العربية a\u0301 ○ ABC").style(STYLE.toBuilder().lineHeight(22).build()).build();
        var part = text.layout(context(240, 100).withFonts(FontGroup.strict(faces)));
        assertEquals(text.text(), logical(part));
        assertTrue(part.commands().stream().anyMatch(c -> c instanceof DrawCommand.FillPath));
    }

    @Test void mergedGridSplitsFormLinesRepeatsHeaderAndKeepsShortLabelUnique() {
        var source = IntStream.rangeClosed(1, 30).mapToObj(i -> "☑ CELL" + i).collect(Collectors.joining("\n"));
        var table = GridTable.builder().columns(1, 2).headerRows(1).allowRowSplit(true)
                .defaults(cell -> cell.padding(2).textStyle(STYLE))
                .row(row -> row.cells("HEAD-A", "HEAD-B"))
                .row(row -> row.cell("LABEL", cell -> cell.rowSpan(2)).cell(FormText.builder(source).style(STYLE).build(), cell -> cell.rowSpan(2)))
                .row(row -> { }).build();
        var parts = table.layoutRegions(context(240, 100), new FlowRegions(List.of(20.0), 100));
        assertTrue(parts.size() > 3);
        assertEquals(1, parts.getFirst().regionIndex());
        var text = parts.stream().map(p -> logical(p.layout())).collect(Collectors.joining());
        assertEquals(1, text.split("LABEL", -1).length - 1);
        assertEquals(30, text.chars().filter(c -> c == '☑').count());
        assertTrue(parts.stream().allMatch(p -> logical(p.layout()).contains("HEAD-A") && logical(p.layout()).contains("HEAD-B")));
    }

    @Test void standaloneMarksAndWritingLinesNeedNoGlyphsAndKeepInkInside() {
        var mark = CheckMark.builder().selected(true).size(8).build();
        var part = mark.layout(context(30, 20));
        assertEquals(8, part.width()); assertEquals(8, part.height());
        assertEquals("☑", logical(part));
        var line = FillLine.builder().height(20).bottomInset(4).strokeWidth(2).build().layout(context(40, 30));
        var command = (DrawCommand.StrokePath) line.commands().getFirst();
        assertEquals(2, command.width());
        assertEquals(0, line.textLines());
        assertThrows(IllegalArgumentException.class, () -> FillLine.builder().height(2).bottomInset(1).strokeWidth(4).build());
        assertThrows(IllegalArgumentException.class, () -> mark.layout(context(4, 20)));
    }

    @Test void labelValueContinuesWithFixedLabelWidthAndSingleLabel() {
        var value = IntStream.rangeClosed(1, 15).mapToObj(i -> "VALUE" + i).collect(Collectors.joining("\n"));
        var field = LabelValue.builder("NAME", value).labelWidth(60).gap(6).style(STYLE).color(RgbColor.BLACK).build();
        var parts = field.layoutRegions(context(240, 80), new FlowRegions(List.of(20.0), 80));
        var text = parts.stream().map(p -> logical(p.layout())).collect(Collectors.joining());
        assertEquals(1, text.split("NAME", -1).length - 1);
        assertEquals(value.replace("\n", ""), text.replace("NAME", ""));
        assertThrows(IllegalArgumentException.class, () -> field.layout(context(60, 80)));
        assertEquals(field, field.toBuilder().build());
    }

    @Test void buildersSnapshotOptionsAndInlineMarks() {
        var builder = FormText.builder("A").checkbox(true).append("B").radio(false).style(STYLE);
        var first = builder.build(); builder.append("C");
        assertEquals("A☑B○", first.text());
        assertEquals(first, first.toBuilder().build());
        var choices = ChoiceGroup.builder().option("A", false);
        var group = choices.build(); choices.option("B", true);
        assertEquals(1, group.options().size());
        assertEquals(group, group.toBuilder().build());
        assertThrows(UnsupportedOperationException.class, () -> group.options().clear());
    }

    @Test void cancelledFormLayoutRestoresOuterContextAndCanBeRetried() {
        var options = ExecutionOptions.builder().cancelled(() -> true).timeout(Duration.ofSeconds(5)).build();
        assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options,
                () -> FormText.builder("☑ CANCELLED").build().layout(context(240, 100))));
        assertFalse(FormText.builder("☑ RETRY").build().layout(context(240, 100)).commands().isEmpty());
    }
}
