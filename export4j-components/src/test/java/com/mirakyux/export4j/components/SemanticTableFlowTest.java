// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.ComponentSemantics;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextOutline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticTableFlowTest {
    private static final PageSettings PAGE = new PageSettings(new PageSize(400, 240), PageMargins.uniform(20));
    private static final CellFormat CELL = new CellFormat(10, 20, ParagraphAlignment.LEFT);
    private static FontGroup fonts;

    @BeforeAll static void fonts() throws IOException {
        try (var input = SemanticTableFlowTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    @Test void repeatedHeadersResolvePerPhysicalPageAcrossColumnsContentsAndRestartedNumbers() {
        var builder = Table.builder("flow").cellFormat(CELL).row(TableCell.mixed(List.of(
                text("P="), field(InlineField.Kind.PAGE, ""), text(" T="), field(InlineField.Kind.PAGES, ""),
                text(" R="), field(InlineField.Kind.REFERENCE_PAGE, "target")), CELL));
        for (int row = 0; row < 32; row++) { builder.row("R" + row); }
        var seed = builder.build();
        var table = new Table(seed.id(), seed.columns(), seed.rows(), 1, seed.format());
        var columns = ContinuousColumns.builder(FlowColumn.builder().padding(3).semanticTable(table).build()).gap(12).build();
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(
                new Section("first", PAGE, List.of(new ComponentBlock("host", columns, 0, 0)), 0),
                new Section("second", PAGE, List.of(new Paragraph("target", "TARGET", ParagraphFormat.of(10))), 7)));
        var nav = Navigation.builder().bookmark("Table", "flow", 1).bookmark("Target", "target", 1)
                .contents(new TableOfContents(true, "Contents", ParagraphStyle.of(10))).build();
        var result = DocumentEngine.builder(fonts).navigation(nav).build().layout(source);
        assertEquals(1, result.bodyPageOffset());
        int count = result.display().pages().size();
        int next = 0;
        var rows = new java.util.ArrayList<String>();
        for (var page : result.body().pages()) {
            for (var fragment : page.elements()) {
                if (!(fragment instanceof ComponentFragment component)) { continue; }
                for (var located : ComponentSemantics.locate(component.layout(), component.origin())) {
                    var placement = located.placement();
                    assertEquals(next++, placement.fragmentIndex());
                    var header = placement.fragment().rows().getFirst();
                    assertEquals("P=" + (page.pageIndex() + 2) + " T=" + count + " R=7", logical(header.cells().getFirst().layout()));
                    assertEquals(placement.fragmentIndex() > 0, header.repeatedHeader());
                    assertTrue(placement.fragment().rows().stream().flatMap(row -> row.cells().stream())
                            .flatMap(cell -> cell.layout().lines().stream()).allMatch(line -> line.fields().isEmpty()));
                    placement.fragment().rows().stream().filter(row -> !row.header())
                            .forEach(row -> rows.add(logical(row.cells().getFirst().layout())));
                }
            }
        }
        assertTrue(next > 2);
        assertEquals(java.util.stream.IntStream.range(0, 32).mapToObj(row -> "R" + row).toList(), rows);
        assertEquals(next + 2, result.display().extras().links().size());
        var first = (ComponentFragment) result.body().pages().getFirst().elements().getFirst();
        assertEquals(2, first.layout().semanticPlacements().size());
        assertEquals(next, first.layout().semanticPlacements().getFirst().fragmentCount());
        assertEquals(1, result.display().extras().bookmarks().getFirst().pageIndex());
    }

    @Test void sourceBudgetsCountOnceAndPresentationBudgetsIncludeRepeatedHeaders() {
        var builder = Table.builder("budget").cellFormat(CELL).header("");
        for (int row = 0; row < 30; row++) { builder.row("ROW"); }
        var table = builder.build();
        var document = Documents.builder().pageSettings(PAGE).component("host", new SemanticTable(table)).build();
        var limits = new DocumentLayoutLimits(2, 91, 100, 20, 31, 1, 31);
        var renderer = new ComponentDocumentRenderer(limits, ComponentLimits.DEFAULT);
        var result = renderer.layout(document, PageTemplate.EMPTY, Navigation.EMPTY, fonts);
        assertTrue(result.body().pages().size() > 1);
        var fewLines = new DocumentLayoutLimits(2, 91, 31, 20, 31, 1, 31);
        var lineFailure = assertThrows(DocumentLayoutException.class, () -> new ComponentDocumentRenderer(fewLines, ComponentLimits.DEFAULT)
                .layout(document, PageTemplate.EMPTY, Navigation.EMPTY, fonts));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, lineFailure.code());
        var fewPlacements = new ComponentLimits(1, ComponentLimits.DEFAULT.maxCommands(), ComponentLimits.DEFAULT.maxInteractions());
        var placementFailure = assertThrows(DocumentLayoutException.class, () -> new ComponentDocumentRenderer(limits, fewPlacements)
                .layout(document, PageTemplate.EMPTY, Navigation.EMPTY, fonts));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, placementFailure.code());
    }

    @Test void crossSectionReuseOfOneCompleteSourceStillFails() {
        var table = Table.builder("same-source").cellFormat(CELL).row("ONE").build();
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(
                new Section("first", PAGE, List.of(new ComponentBlock("host-a", new SemanticTable(table), 0, 0)), 0),
                new Section("second", PAGE, List.of(new ComponentBlock("host-b", new SemanticTable(table), 0, 0)), 0)));
        assertThrows(IllegalArgumentException.class, () -> DocumentEngine.builder(fonts).build().layout(source));
    }

    @Test void fontScopePreservesBothShortFirstColumnsAndMeasuredFontPolicy() {
        var builder = Table.builder("scoped-flow").cellFormat(CELL).header("H");
        for (int row = 0; row < 20; row++) { builder.row("R" + row); }
        var table = new SemanticTable(builder.build());
        var local = FontGroup.placeholder(fonts.fonts(), '?');
        var context = new ComponentContext(180, 200, fonts, 0, 0, 1);
        var regions = new FlowRegions(List.of(96.0, 96.0), 200);
        var expected = table.layoutRegions(context.withFonts(local), regions);
        var actual = new FontScope(table, local).layoutRegions(context, regions);
        assertEquals(List.of(0, 1), actual.subList(0, 2).stream().map(part -> part.regionIndex()).toList());
        assertEquals(expected, actual);
        assertTrue(actual.stream().flatMap(part -> part.layout().semanticPlacements().stream())
                .allMatch(placed -> placed.fonts().equals(local)));
        var column = FlowColumn.builder().add(new FontScope(table, local)).build().layoutRegions(context, regions);
        assertEquals(expected.stream().map(part -> part.regionIndex()).toList(), column.stream().map(part -> part.regionIndex()).toList());
        assertEquals(expected.stream().map(part -> part.layout().semanticPlacements().getFirst().fragment().rows().size()).toList(),
                column.stream().map(part -> part.layout().semanticPlacements().getFirst().fragment().rows().size()).toList());
    }

    @Test void splitCellsRetainUtf16WarningsAndActualContinuationLineIndices() {
        String content = java.util.stream.IntStream.range(0, 28).mapToObj(index -> index == 20 ? "😀" : "ROW" + index)
                .collect(Collectors.joining("\n"));
        var table = Table.builder("split").cellFormat(CELL).format(format -> format.allowRowSplit(true))
                .header("H").row(content).build();
        var document = Documents.builder().pageSettings(PAGE).column(column -> column.padding(4).semanticTable(table)).build();
        var result = DocumentEngine.builder(FontGroup.placeholder(fonts.fonts(), '?')).build().layout(document);
        var warning = result.contextualDiagnostics().stream().filter(item -> item.diagnostic().code() == TextDiagnostic.Code.MISSING_GLYPH_REPLACED)
                .findFirst().orElseThrow();
        assertEquals("split", warning.blockId()); assertEquals(1, warning.rowIndex()); assertEquals(0, warning.columnIndex());
        assertEquals(content.indexOf("😀"), warning.diagnostic().sourceStart());
        assertEquals(content.indexOf("😀") + 2, warning.diagnostic().sourceEnd());
        assertTrue(warning.pageIndex() > 0);
        var starts = result.body().pages().stream().flatMap(page -> page.elements().stream()).filter(ComponentFragment.class::isInstance)
                .map(ComponentFragment.class::cast).flatMap(component -> component.layout().semanticPlacements().stream())
                .flatMap(placed -> placed.fragment().rows().stream()).filter(row -> !row.header())
                .map(row -> row.cells().getFirst().firstLineIndex()).toList();
        assertEquals(0, starts.getFirst());
        assertTrue(starts.size() > 1);
        for (int i = 1; i < starts.size(); i++) { assertTrue(starts.get(i) > starts.get(i - 1)); }
    }

    private static InlineText text(String value) { return new InlineText(value, InlineStyle.of(10)); }
    private static InlineField field(InlineField.Kind kind, String target) { return new InlineField(kind, target, 18, InlineStyle.of(10)); }
    private static String logical(ParagraphLayout layout) {
        return layout.lines().stream().map(line -> line.outlines().isEmpty()
                ? line.runs().stream().map(run -> run.text()).collect(Collectors.joining())
                : line.outlines().stream().map(TextOutline::logicalText).collect(Collectors.joining())).collect(Collectors.joining());
    }
}
