// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.ComponentSemantics;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.TextOutline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents reuse of fixed-width lines, duplicate sources and lost semantic context across unequal columns. */
class VariableTableFlowTest {
    private static final CellFormat CELL = CellFormat.builder().fontSize(10).lineHeight(12).build();
    private static FontGroup fonts;

    @BeforeAll static void fonts() throws IOException {
        try (var input = VariableTableFlowTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    @Test void wrapsAtEachActualWidthAndKeepsEverySourceRowExactlyOnce() {
        var builder = Table.builder("variable").cellFormat(CELL).format(format -> format.padding(2)).header("HEADER");
        for (int index = 1; index <= 18; index++) { builder.row("R" + index + " alpha beta gamma delta"); }
        var table = builder.build();
        var parts = new SemanticTable(table).layoutVariableRegions(context(150, 100),
                new FlowRegions(List.of(8.0, 8.0), 100), List.of(70.0, 150.0));
        assertEquals(2, parts.getFirst().regionIndex());
        var rows = new ArrayList<Integer>();
        boolean narrow = false, wide = false;
        for (var part : parts) {
            var placement = part.layout().semanticPlacements().getFirst();
            assertSame(table, placement.source());
            assertEquals(parts.size(), placement.fragmentCount());
            double width = part.regionIndex() % 2 == 0 ? 70 : 150;
            assertEquals(width, part.layout().width());
            var fragment = placement.fragment();
            assertEquals(placement.fragmentIndex() > 0, fragment.rows().getFirst().repeatedHeader());
            for (var row : fragment.rows()) {
                if (row.header()) { continue; }
                rows.add(row.rowIndex());
                var cell = row.cells().getFirst();
                assertEquals(table.rows().get(row.rowIndex()).cells().getFirst().text().replace(" ", ""),
                        logical(cell.layout()).replace(" ", ""));
                assertEquals(0, cell.firstLineIndex());
                assertFalse(row.continuedRow());
                assertTrue(cell.layout().lines().stream().allMatch(line -> line.width() <= Math.nextUp(width - 4)));
                if (width == 70) { narrow |= cell.lineCount() >= 3; }
                else { wide |= cell.lineCount() == 1; }
            }
        }
        assertTrue(narrow && wide, "the actual width must affect wrapping");
        assertEquals(java.util.stream.IntStream.rangeClosed(1, 18).boxed().toList(), rows);
    }

    @Test void automaticMergedOwnersAndCoveredRowsStayInOneRegion() {
        var builder = Table.builder("merged").cellFormat(CELL).format(format -> format.padding(2))
                .columns(new ColumnWidth.Auto(25, 55), new ColumnWidth.Fraction(1)).header("H", "VALUE");
        for (int group = 0; group < 6; group++) {
            builder.row(TableCell.builder().text("G" + group).format(CELL).rowSpan(2).build(), cell("first alpha beta"));
            builder.row(cell("second alpha beta"));
        }
        var table = builder.build();
        var parts = new SemanticTable(table).layoutVariableRegions(context(170, 110),
                new FlowRegions(List.of(2.0), 110), List.of(90.0, 170.0));
        var seen = new ArrayList<Integer>();
        for (var part : parts) {
            var rows = part.layout().semanticPlacements().getFirst().fragment().rows();
            for (int index = 1; index < rows.size(); index += 2) {
                var first = rows.get(index); var second = rows.get(index + 1);
                assertEquals(first.rowIndex() + 1, second.rowIndex());
                assertEquals(1, second.cells().getFirst().columnIndex());
                assertTrue(first.cells().getFirst().bounds().height() > second.cells().getFirst().bounds().height());
                seen.add(first.rowIndex()); seen.add(second.rowIndex());
            }
        }
        assertEquals(java.util.stream.IntStream.rangeClosed(1, 12).boxed().toList(), seen);
    }

    @Test void atomicNestedContentKeepsItsOriginalLeafAndAvailableWidth() {
        var nested = Table.builder("inner").cellFormat(CELL).format(format -> format.padding(1)).row("INNER words words").build();
        var parent = Table.builder("outer").cellFormat(CELL).format(format -> format.padding(2)).header("HEAD")
                .row(TableCell.builder().blocks(List.of(new Paragraph("leaf", "LEAF alpha beta", ParagraphFormat.of(10)), nested)).build())
                .row("AFTER").build();
        var parts = new SemanticTable(parent).layoutVariableRegions(context(160, 160),
                new FlowRegions(List.of(1.0), 160), List.of(80.0, 160.0));
        assertEquals(1, parts.getFirst().regionIndex());
        var blocks = parts.getFirst().layout().semanticPlacements().getFirst().fragment().rows().get(1).cells().getFirst().blocks();
        assertEquals(List.of("leaf", "inner"), blocks.stream().map(block -> block.blockId()).toList());
        assertEquals(156, ((TableFragment) blocks.get(1)).width());
    }

    @Test void finalFieldsLinksAndFontScopesSurvivePaddedMixedAndBalancedColumns() {
        var headerFormat = CELL.toBuilder().lineHeight(20).build();
        var builder = Table.builder("fields").cellFormat(CELL).format(format -> format.padding(2))
                .headerCells(TableCell.mixed(List.of(new InlineText("P", InlineStyle.of(10)),
                        new InlineField(InlineField.Kind.PAGE, "", 12, InlineStyle.of(10)),
                        new InlineText("/", InlineStyle.of(10)),
                        new InlineField(InlineField.Kind.PAGES, "", 12, InlineStyle.of(10))), headerFormat));
        for (int index = 0; index < 24; index++) {
            builder.row(TableCell.rich(List.of(new InlineText("ROW-" + index + " alpha beta", InlineStyle.of(10),
                    "https://example.com/row/" + index)), CELL));
        }
        var table = builder.build();
        var scoped = new FontScope(new SemanticTable(table), fonts);
        var flow = FlowColumn.builder().padding(2).fixedText("BEGIN").add(scoped).fixedText("END").build();
        var columns = ContinuousColumns.builder(flow).columnWeights(List.of(1.0, 2.0)).gap(12).balanced(true).build();
        var document = Documents.builder().pageSettings(PageSettings.builder().size(350, 170).margins(15).build())
                .component("host", columns).build();
        var result = DocumentEngine.builder(fonts).build().layout(document);
        int count = result.display().pages().size(), fragment = 0;
        assertTrue(count > 1);
        var seen = new ArrayList<Integer>();
        for (var page : result.body().pages()) {
            for (var element : page.elements()) {
                if (!(element instanceof ComponentFragment component)) { continue; }
                for (var located : ComponentSemantics.locate(component.layout(), component.origin())) {
                    var placement = located.placement();
                    assertEquals(fragment++, placement.fragmentIndex());
                    assertEquals(fonts, placement.fonts());
                    assertEquals("P" + (page.pageIndex() + 1) + "/" + count,
                            logical(placement.fragment().rows().getFirst().cells().getFirst().layout()));
                    placement.fragment().rows().stream().filter(row -> !row.header()).forEach(row -> seen.add(row.rowIndex()));
                }
            }
        }
        assertEquals(java.util.stream.IntStream.rangeClosed(1, 24).boxed().toList(), seen);
        assertTrue(result.display().extras().externalLinks().size() >= 24);
        assertEquals(24, result.display().extras().externalLinks().stream().map(link -> link.destination()).distinct().count());
    }

    @Test void sourceAndOutputBudgetsAreIndependentOfUnusedWidthAlternatives() {
        var table = Table.builder("budget").cellFormat(CELL).format(new TableFormat(PageMargins.uniform(0), 0, 0))
                .header("H").row("A").row("B").row("C").row("D").row("E").row("F").build();
        var regions = new FlowRegions(List.of(), 36);
        var exact = new DocumentLayoutLimits(1, 7, 9, 3, 7, 1, 7);
        var layouter = new DocumentLayouter(new ParagraphLayouter(), exact);
        var actual = layouter.layoutTableRegions(table, context(100, 36), regions, List.of(50.0, 100.0));
        assertEquals(3, actual.size());
        assertEquals(9, actual.stream().flatMap(part -> part.fragment().rows().stream()).mapToLong(row -> row.cells().getFirst().lineCount()).sum());
        var fewLines = new DocumentLayoutLimits(1, 7, 8, 3, 7, 1, 7);
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, assertThrows(DocumentLayoutException.class,
                () -> new DocumentLayouter(new ParagraphLayouter(), fewLines).layoutTableRegions(table,
                        context(100, 36), regions, List.of(50.0, 100.0))).code());
        var fewPages = new DocumentLayoutLimits(1, 7, 9, 2, 7, 1, 7);
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, assertThrows(DocumentLayoutException.class,
                () -> new DocumentLayouter(new ParagraphLayouter(), fewPages).layoutTableRegions(table,
                        context(100, 36), regions, List.of(50.0, 100.0))).code());
    }

    @Test void partialReflowAndImpossibleRegionsDoNotPoisonLaterCalls() {
        var split = Table.builder("split").cellFormat(CELL).format(format -> format.padding(0).allowRowSplit(true))
                .row("ONE\nTWO\nTHREE\nFOUR").build();
        var flow = new SemanticTable(split);
        assertEquals(2, flow.layoutVariableRegions(context(100, 24), new FlowRegions(List.of(), 24), List.of(50.0, 100.0)).size());
        assertEquals(2, flow.layoutVariableRegions(context(100, 24), new FlowRegions(List.of(), 24), List.of(50.0, 50.0)).size());
        var atomic = new SemanticTable(new Table(split.id(), split.columns(), split.rows(), 0,
                split.format().toBuilder().allowRowSplit(false).build()));
        assertEquals(DocumentLayoutException.Code.CONTENT_TOO_TALL, assertThrows(DocumentLayoutException.class,
                () -> atomic.layoutVariableRegions(context(100, 24), new FlowRegions(List.of(), 24), List.of(50.0, 100.0))).code());
        assertEquals(1, atomic.layoutVariableRegions(context(100, 100), new FlowRegions(List.of(), 100), List.of(50.0, 100.0)).size());
    }

    @Test void unusedWidthWarningsAreNotDuplicatedAndUtf16BelongsToOriginalCell() {
        var builder = Table.builder("warnings").cellFormat(CELL).format(format -> format.padding(0)).header("H");
        for (int row = 0; row < 7; row++) { builder.row(row == 4 ? "A😀B" : "ROW"); }
        var policy = FontGroup.placeholder(fonts.fonts(), '?');
        var parts = new SemanticTable(builder.build()).layoutVariableRegions(context(100, 36).withFonts(policy),
                new FlowRegions(List.of(), 36), List.of(50.0, 100.0));
        var warned = parts.stream().flatMap(part -> part.layout().semanticPlacements().stream())
                .flatMap(part -> part.fragment().rows().stream()).flatMap(row -> row.cells().stream())
                .flatMap(cell -> cell.diagnostics().stream()).toList();
        assertEquals(1, warned.size());
        assertEquals(1, warned.getFirst().sourceStart());
        assertEquals(3, warned.getFirst().sourceEnd());
        assertTrue(parts.stream().flatMap(part -> part.layout().semanticPlacements().stream())
                .allMatch(part -> part.fonts().equals(policy)));
    }

    @Test void whollyCoveredRowsKeepGroupHeightWithoutSyntheticContentAnchors() {
        var table = Table.builder("covered").cellFormat(CELL).format(format -> format.padding(0)).header("H")
                .row(TableCell.builder().text("ONE\nTWO\nTHREE\nFOUR\nFIVE\nSIX").format(CELL).rowSpan(3).build())
                .coveredRow().coveredRow().row("NEXT").build();
        var parts = new SemanticTable(table).layoutVariableRegions(context(100, 84),
                new FlowRegions(List.of(20.0), 84), List.of(50.0, 100.0));
        assertEquals(2, parts.size());
        var first = parts.getFirst().layout().semanticPlacements().getFirst().fragment();
        assertEquals(List.of(0, 1), first.rows().stream().map(row -> row.rowIndex()).toList());
        assertEquals(72, first.rows().get(1).cells().getFirst().bounds().height());
        var last = parts.getLast().layout().semanticPlacements().getFirst().fragment();
        assertEquals(4, last.rows().getLast().rowIndex());
        assertEquals("NEXT", logical(last.rows().getLast().cells().getFirst().layout()));
    }

    private static ComponentContext context(double width, double height) { return new ComponentContext(width, height, fonts, 0, 0, 1); }
    private static TableCell cell(String text) { return new TableCell(text, CELL); }
    private static String logical(ParagraphLayout layout) {
        return layout.lines().stream().map(line -> line.outlines().isEmpty()
                ? line.runs().stream().map(run -> run.text()).collect(Collectors.joining())
                : line.outlines().stream().map(TextOutline::logicalText).collect(Collectors.joining())).collect(Collectors.joining());
    }
}
