// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.CellFragment;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.model.*;

import static org.junit.jupiter.api.Assertions.*;

class SemanticStreamingTableTest {
    private static FontGroup fonts;
    private static final CellFormat FORMAT = new CellFormat(10, 14, ParagraphAlignment.LEFT);
    @BeforeAll static void fonts() throws IOException {
        try (var input = SemanticStreamingTableTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }
    private static StreamingTable table() {
        return StreamingTable.builder("stream").columns(100).semanticCells(true).allowRowSpans(true).allowRowSplit(true)
                .pageSettings(new PageSettings(new PageSize(140, 120), PageMargins.uniform(10))).padding(2)
                .header(new TableRow(List.of(new TableCell("HEAD", FORMAT)))).build();
    }
    private static TableRow row(String text) { return new TableRow(List.of(new TableCell(text, FORMAT))); }
    private static final class Source implements TableRowSource {
        private final List<TableRow> rows; private int reads;
        private Source(List<TableRow> rows) { this.rows = rows; }
        @Override public Optional<TableRow> next() { int index = reads++; return index < rows.size() ? Optional.of(rows.get(index)) : Optional.empty(); }
        @Override public void close() { throw new AssertionError("layouter borrows source"); }
    }
    @Test void packsIndependentGroupsOnOnePageRepeatingHeadersOnlyAtPhysicalBoundaries() throws Exception {
        var source = new Source(java.util.stream.IntStream.range(0, 12).mapToObj(index -> row("ROW " + index)).toList());
        var pages = new ArrayList<LayoutPage>();
        var result = new SemanticStreamingTableLayouter().layout(table(), source, fonts, Map.of(), StreamingTableLimits.DEFAULT, Map.of(),
                (page, notes, sources) -> {
                    var table = (TableFragment) page.elements().getFirst();
                    int last = table.rows().getLast().rowIndex();
                    assertTrue(source.reads <= last + 1, "only one next group may be read");
                    assertEquals(1, table.rows().stream().filter(value -> value.header()).count()); pages.add(page);
                });
        assertEquals(12, result.bodyRows()); assertEquals(3, pages.size()); assertEquals(13, source.reads);
        assertEquals(java.util.stream.IntStream.rangeClosed(1, 12).boxed().toList(), pages.stream()
                .flatMap(page -> ((TableFragment) page.elements().getFirst()).rows().stream()).filter(row -> !row.header()).map(row -> row.rowIndex()).toList());
    }

    @Test void nestedParagraphContinuationsKeepCompleteOriginalSourceRangesAndBlockOrdinals() throws Exception {
        var paragraph = new Paragraph("leaf", "alpha beta gamma delta ".repeat(30), ParagraphFormat.of(10));
        var nested = Table.builder("nested").columns(new ColumnWidth.Fraction(1)).format(new TableFormat(PageMargins.uniform(2), 0, 0, true))
                .row(TableCell.builder().blocks(List.of(paragraph)).build()).build();
        var source = new Source(List.of(new TableRow(List.of(TableCell.builder().blocks(List.of(nested)).build()))));
        var fragments = new ArrayList<ParagraphFragment>();
        var result = new SemanticStreamingTableLayouter().layout(table(), source, fonts, Map.of(), StreamingTableLimits.DEFAULT, Map.of(),
                (page, notes, sources) -> collect((TableFragment) page.elements().getFirst(), fragments));
        assertTrue(result.pages() > 2); assertEquals(1, result.bodyRows());
        int last = 0;
        for (var fragment : fragments) {
            assertEquals("leaf", fragment.paragraphId()); assertEquals(last, fragment.layout().lines().getFirst().sourceStart());
            last = fragment.layout().lines().getLast().sourceEnd();
        }
        assertEquals(paragraph.text().length(), last);
    }
    private static void collect(TableFragment table, List<ParagraphFragment> paragraphs) {
        for (var row : table.rows()) { for (var cell : row.cells()) { for (var block : cell.blocks()) {
            if (block instanceof ParagraphFragment paragraph) { paragraphs.add(paragraph); }
            else { collect((TableFragment) block, paragraphs); }
        } } }
    }

    @Test void splitVerticalOwnersKeepAnchorRowsAndReserveGroupFootnoteOnce() throws Exception {
        var specification = table().toBuilder().columns(50, 50).headerRows(List.of(new TableRow(List.of(new TableCell("H1", FORMAT), new TableCell("H2", FORMAT))))).build();
        var owner = TableCell.builder().format(FORMAT).text("owner\n".repeat(20)).rowSpan(2).build();
        var source = new Source(List.of(new TableRow(List.of(owner, new TableCell("first", FORMAT))), new TableRow(List.of(new TableCell("second", FORMAT)))));
        int[] notesSeen = {0}; var anchorRows = new ArrayList<Integer>();
        var result = new SemanticStreamingTableLayouter().layout(specification, source, fonts, Map.of(), StreamingTableLimits.DEFAULT, Map.of(0L, 22.0),
                (page, notes, sources) -> {
                    var fragment = (TableFragment) page.elements().getFirst();
                    if (!notes.isEmpty()) { notesSeen[0]++; assertEquals(List.of(0L), notes); assertTrue(fragment.height() <= 78); }
                    fragment.rows().stream().filter(row -> !row.header()).forEach(row -> anchorRows.add(row.rowIndex()));
                });
        assertTrue(result.pages() > 2); assertEquals(1, notesSeen[0]); assertTrue(anchorRows.contains(1)); assertTrue(anchorRows.contains(2));
    }

    @Test void connectedGroupBudgetsFailBeforeReadingFurtherSource() {
        var specification = table().toBuilder().headerRows(List.of()).build();
        var source = new Source(List.of(new TableRow(List.of(TableCell.builder().text("owner").rowSpan(3).build())), TableRow.covered(), TableRow.covered()));
        var limits = StreamingTableLimits.DEFAULT.toBuilder().maxBufferedRows(2).build();
        var failure = assertThrows(DocumentLayoutException.class, () -> new SemanticStreamingTableLayouter().layout(specification, source, fonts, Map.of(), limits, Map.of(),
                (page, notes, sources) -> fail("no page before budget rejection")));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code()); assertEquals(1, source.reads);
    }

    @Test void errorsInLaterNestedGroupsRetainGlobalOuterSourceRowsAndUtf16() {
        var invalid = new Paragraph("broken", "unsupported 😀", ParagraphFormat.of(10));
        var source = new Source(List.of(row("first"), new TableRow(List.of(TableCell.builder().blocks(List.of(invalid)).build()))));
        var failure = assertThrows(DocumentLayoutException.class, () -> new SemanticStreamingTableLayouter().layout(table(), source, fonts, Map.of(), StreamingTableLimits.DEFAULT, Map.of(),
                (page, notes, sources) -> { }));
        assertEquals("broken", failure.blockId()); assertFalse(failure.sourcePath().isEmpty());
        assertEquals(2, failure.sourcePath().getFirst().rowIndex()); assertTrue(failure.sourceIndex() >= 0);
    }

    @Test void callbackFailureStopsBeforeAnotherReadAndPreservesTheException() {
        var source = new Source(java.util.stream.IntStream.range(0, 100).mapToObj(index -> row("ROW " + index)).toList());
        var failure = new IOException("consumer");
        assertSame(failure, assertThrows(IOException.class, () -> new SemanticStreamingTableLayouter().layout(table(), source, fonts, Map.of(), StreamingTableLimits.DEFAULT, Map.of(),
                (page, notes, sources) -> { throw failure; })));
        assertTrue(source.reads <= 5);
    }

    @Test void semanticIdsAreCheckedAcrossSeparateGroups() {
        var cell = TableCell.builder().paragraph(new Paragraph("same", "text", ParagraphFormat.of(10))).build();
        var source = new Source(List.of(new TableRow(List.of(cell)), new TableRow(List.of(cell))));
        assertThrows(IllegalArgumentException.class, () -> new SemanticStreamingTableLayouter().layout(table(), source, fonts, Map.of(), StreamingTableLimits.DEFAULT, Map.of(),
                (page, notes, sources) -> { }));
    }
}
