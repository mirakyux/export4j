// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.DocumentLayout;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.SemanticPlacement;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.CellLocation;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;
import com.mirakyux.export4j.text.ParagraphLayouter;

class BlockCellLayouterTest {
    private static final CellFormat CELL = CellFormat.builder().fontSize(8).lineHeight(10).build();
    private static final ParagraphFormat TEXT = ParagraphFormat.builder().fontSize(8).lineHeight(10).spaceAfter(0).build();
    private static FontGroup fonts;

    @BeforeAll static void fonts() throws IOException {
        try (var input = BlockCellLayouterTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    @Test void orderedBlocksUseParentContentWidthAndIndependentPaddingAndSpacing() {
        var first = new Paragraph("first", "FIRST", TEXT.toBuilder().spaceBefore(99).spaceAfter(4).build());
        var inner = new Table("inner", List.of(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1)),
                List.of(row(inline("LEFT"), inline("RIGHT"))), 0,
                new TableFormat(new PageMargins(1, 2, 3, 4), 2, 5));
        var last = new Paragraph("last", "LAST", TEXT.toBuilder().lineHeight(14).spaceBefore(3).spaceAfter(99).build());
        var source = outer(block(first, inner, last), new PageMargins(3, 5, 7, 11));
        var table = firstTable(layout(List.of(source), 100));
        var cell = table.rows().getFirst().cells().getFirst();
        assertTrue(cell.hasBlocks()); assertEquals(4, cell.lineCount());
        assertEquals(62, cell.bounds().height()); assertEquals(11, cell.textOrigin().x()); assertEquals(3, cell.textOrigin().y());
        assertEquals(List.of("first", "inner", "last"), cell.blocks().stream().map(f -> f.blockId()).toList());
        assertEquals(0, cell.blocks().getFirst().origin().y()); assertEquals(16, cell.blocks().get(1).origin().y());
        assertEquals(38, cell.blocks().getLast().origin().y());
        var nested = (TableFragment) cell.blocks().get(1);
        assertEquals(164, nested.width()); assertEquals(14, nested.height());
        var left = nested.rows().getFirst().cells().getFirst();
        assertEquals(82, left.bounds().width()); assertEquals(76, left.layout().width());
        assertThrows(IllegalStateException.class, cell::layout);
        assertThrows(IllegalStateException.class, cell::firstLineIndex);
        assertThrows(UnsupportedOperationException.class, () -> cell.blocks().clear());
        assertFalse(DocumentPainter.paint(layout(List.of(source), 100)).pages().getFirst().commands().isEmpty());
    }

    @Test void decimalFixedColumnsAtParentBoundaryAbsorbOnlyArithmeticRounding() {
        var nested = new Table("inner", List.of(new ColumnWidth.Fixed(110.7), new ColumnWidth.Fixed(80.65)),
                List.of(row(inline("LEFT"), inline("RIGHT"))), 0, new TableFormat(PageMargins.uniform(6), 0, 0));
        var parent = new Table("outer", List.of(new ColumnWidth.Fixed(203.35), new ColumnWidth.Fixed(83.05)),
                List.of(row(block(nested), inline("OUTSIDE"))), 0, new TableFormat(PageMargins.uniform(6), 0, 0));
        var settings = PageSettings.builder().size(420, 420).margins(24).build();
        var result = new DocumentLayouter().layout(new Document(DocumentMetadata.EMPTY, settings, List.of(parent)), fonts);
        var fragment = (TableFragment) firstTable(result).rows().getFirst().cells().getFirst().blocks().getFirst();
        assertEquals(191.35, fragment.width(), 1e-12);
        assertEquals(110.7, fragment.rows().getFirst().cells().getFirst().bounds().width());
        assertEquals(80.65, fragment.rows().getFirst().cells().getLast().bounds().width());
        var oversized = new Table("overflow", List.of(new ColumnWidth.Fixed(110.71), new ColumnWidth.Fixed(80.65)),
                nested.rows(), 0, nested.format());
        var cursor = new DocumentLayouter();
        assertThrows(DocumentLayoutException.class, () -> cursor.prepareTable(oversized,
                new ComponentContext(191.35, 100, fonts, 0, 0, 1)));
    }

    @Test void atomicCellMovesOnceAndFollowingBodyResumesAfterIt() {
        var source = outer(block(new Paragraph("child", "A\nB\nC", TEXT)), PageMargins.uniform(2));
        var lead = new Paragraph("lead", "LEAD", TEXT.toBuilder().lineHeight(30).build());
        var tail = new Paragraph("tail", "TAIL", TEXT);
        var layout = layout(List.of(lead, source, tail), 60);
        assertEquals(2, layout.pages().size()); assertEquals(1, layout.pages().getFirst().elements().size());
        var table = (TableFragment) layout.pages().getLast().elements().getFirst();
        assertEquals(0, table.origin().y()); assertEquals(34, table.height());
        assertEquals(34, layout.pages().getLast().elements().getLast().origin().y());
        assertEquals(3, table.rows().getFirst().cells().getFirst().lineCount());
    }

    @Test void shortCursorTakeIsPureAndDoesNotReshapeOrConsumeChild() {
        var source = outer(block(new Paragraph("child", "A\nB\nC", TEXT)), PageMargins.uniform(2));
        var cursor = new DocumentLayouter().prepareTable(source, new ComponentContext(180, 60, fonts, 0, 0, 1));
        assertTrue(cursor.take(33).isEmpty()); assertTrue(cursor.take(0).isEmpty());
        assertEquals(0, cursor.rowIndex()); assertEquals(0, cursor.fragmentIndex()); assertFalse(cursor.done());
        var placed = cursor.take(34).orElseThrow(); assertTrue(cursor.done());
        assertEquals("child", placed.rows().getFirst().cells().getFirst().blocks().getFirst().blockId());
        assertEquals(3, placed.rows().getFirst().cells().getFirst().lineCount());
    }

    @Test void allDescendantsShareSourceNodesCellsTextAndOutputLineBudgets() {
        var inner = inner("inner", "BC");
        var source = outer(block(new Paragraph("a", "A", TEXT), inner, new Paragraph("d", "D", TEXT)), PageMargins.uniform(0));
        var exact = new DocumentLayoutLimits(4, 4, 3, 1, 2, 2, 2);
        assertEquals(1, limited(source, exact).pages().size());
        for (var limits : List.of(new DocumentLayoutLimits(3, 4, 3, 1, 2, 2, 2),
                new DocumentLayoutLimits(4, 4, 3, 1, 2, 2, 1), new DocumentLayoutLimits(4, 3, 3, 1, 2, 2, 2),
                new DocumentLayoutLimits(4, 4, 2, 1, 2, 2, 2))) {
            var failure = assertThrows(DocumentLayoutException.class, () -> limited(source, limits));
            assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
            assertFalse(failure.sourcePath().isEmpty()); assertEquals("outer", failure.sourcePath().getFirst().tableId());
        }
    }

    @Test void leafFailureRetainsNestedPhysicalGridAndAncestryAndCanSerialize() throws IOException, ClassNotFoundException {
        var inner = inner("inner", new String(Character.toChars(0x10FFFF)));
        var source = outer(block(new Paragraph("before", "A", TEXT), inner), PageMargins.uniform(0));
        var failure = assertThrows(DocumentLayoutException.class, () -> layout(List.of(source), 100));
        assertEquals("inner", failure.blockId()); assertEquals(0, failure.rowIndex()); assertEquals(0, failure.columnIndex());
        assertEquals(0, failure.sourceIndex());
        assertEquals(List.of(new CellLocation("outer", 0, 0, 1), new CellLocation("inner", 0, 0, -1)), failure.sourcePath());
        assertThrows(UnsupportedOperationException.class, () -> failure.sourcePath().clear());
        var bytes = new ByteArrayOutputStream();
        try (var stream = new ObjectOutputStream(bytes)) { stream.writeObject(failure); }
        try (var stream = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertEquals(failure.sourcePath(), ((DocumentLayoutException) stream.readObject()).sourcePath());
        }
    }

    @Test void componentDescendantsKeepTheirPathWhenEarlierBodyExhaustsTheSharedTextBudget() {
        for (Block leaf : List.of(new Paragraph("leaf", "B", TEXT), inner("inner", "B"))) {
            var source = outer(block(leaf), PageMargins.uniform(0));
            var fragment = firstTable(layout(List.of(source), 100));
            var placement = new SemanticPlacement(0, source, fragment, fonts, Map.of());
            var host = new ComponentBlock("host", context -> new ComponentLayout(180, fragment.height(),
                    Math.toIntExact(fragment.rows().getFirst().cells().getFirst().lineCount()), List.of(), List.of(), List.of(placement)));
            var limits = new DocumentLayoutLimits(100, 1, 100, 10);
            var failure = assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter(new ParagraphLayouter(), limits)
                    .layout(document(List.of(new Paragraph("lead", "A", TEXT), host), 100), fonts));
            assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
            assertEquals(leaf.id(), failure.blockId()); assertEquals(1, failure.blockIndex());
            assertEquals(0, failure.rowIndex()); assertEquals(0, failure.columnIndex()); assertEquals(0, failure.sourceIndex());
            assertEquals(new CellLocation("outer", 0, 0, 0), failure.sourcePath().getFirst());
            assertEquals(leaf instanceof Table ? 2 : 1, failure.sourcePath().size());
        }
    }

    @Test void parentAndChildUnsupportedLayoutPoliciesFailWithSource() {
        var good = outer(block(inner("inner", "A")), PageMargins.uniform(0));
        var splitParent = new Table(good.id(), good.columns(), good.rows(), 0, good.format().toBuilder().allowRowSplit(true).build());
        var autoParent = new Table(good.id(), List.of(new ColumnWidth.Auto(20, 180)), good.rows(), 0, good.format());
        var child = inner("inner", "A");
        var splitChild = new Table(child.id(), child.columns(), child.rows(), 0, child.format().toBuilder().allowRowSplit(true).build());
        var autoChild = new Table(child.id(), List.of(new ColumnWidth.Auto(20, 160)), child.rows(), 0, child.format());
        assertEquals(1, layout(List.of(splitParent), 100).pages().size());
        assertEquals(1, layout(List.of(outer(block(splitChild), PageMargins.uniform(0))), 100).pages().size());
        assertEquals(1, layout(List.of(outer(block(autoChild), PageMargins.uniform(0))), 100).pages().size());
        var failure = assertThrows(DocumentLayoutException.class, () -> layout(List.of(autoParent), 100));
        assertEquals(DocumentLayoutException.Code.INVALID_GEOMETRY, failure.code()); assertFalse(failure.sourcePath().isEmpty());
    }

    @Test void nestedAutomaticColumnsUseParentContentWidthAndKeepIndependentPadding() {
        var child = Table.builder("inner").cellFormat(CELL).columns(new ColumnWidth.Auto(20, 120), new ColumnWidth.Fixed(40))
                .format(format -> format.padding(2)).row("WWWWWWWWWWWW", "TAIL").build();
        var parent = outer(block(child), PageMargins.uniform(3));
        var wide = firstTable(layout(List.of(parent), 100));
        var narrow = firstTable(new DocumentLayouter().layout(new Document(DocumentMetadata.EMPTY,
                PageSettings.builder().size(100, 100).margins(0).build(), List.of(parent)), fonts));
        var a = (TableFragment) wide.rows().getFirst().cells().getFirst().blocks().getFirst();
        var b = (TableFragment) narrow.rows().getFirst().cells().getFirst().blocks().getFirst();
        double wideColumn = a.rows().getFirst().cells().getFirst().bounds().width();
        assertTrue(wideColumn > 54 && wideColumn <= 120);
        assertEquals(94, b.width(), 1e-9); assertEquals(54, b.rows().getFirst().cells().getFirst().bounds().width(), 1e-9);
        assertEquals(50, b.rows().getFirst().cells().getFirst().layout().width(), 1e-9);
        assertTrue(b.rows().getFirst().cells().getFirst().lineCount() > a.rows().getFirst().cells().getFirst().lineCount());
        assertEquals(40, b.rows().getFirst().cells().getLast().bounds().width());
    }

    @Test void nestedAutomaticMergedOwnersRetainPhysicalColumnsAndCoveredRows() {
        var child = Table.builder("inner").cellFormat(CELL)
                .columns(new ColumnWidth.Auto(20, 60), new ColumnWidth.Auto(20, 60), new ColumnWidth.Fixed(30))
                .format(format -> format.padding(1))
                .row(TableCell.builder().format(CELL).text("MERGED OWNER").columnSpan(2).rowSpan(2).build(), inline("A"))
                .row(inline("B")).build();
        var nested = (TableFragment) firstTable(layout(List.of(outer(block(child), PageMargins.uniform(2))), 100))
                .rows().getFirst().cells().getFirst().blocks().getFirst();
        var owner = nested.rows().getFirst().cells().getFirst();
        assertEquals(0, owner.columnIndex()); assertEquals(2, nested.rows().getLast().cells().getFirst().columnIndex());
        assertTrue(owner.bounds().width() >= 40 && owner.bounds().width() <= 120);
        assertEquals(nested.height(), owner.bounds().height());
        assertEquals(owner.bounds().width(), nested.rows().getLast().cells().getFirst().bounds().x());
        assertEquals(3, nested.rows().stream().mapToInt(row -> row.cells().size()).sum());
    }

    @Test void nestedAutomaticProbesDoNotDoubleChargeSourceLinesOrMissingGlyphWarnings() {
        var child = Table.builder("inner").cellFormat(CELL).columns(new ColumnWidth.Auto(20, 160))
                .format(format -> format.padding(0)).row("A\u4e2d").build();
        var source = outer(block(child), PageMargins.uniform(0));
        var limits = new DocumentLayoutLimits(2, 2, 1, 1, 1, 1, 2);
        var result = new DocumentLayouter(new ParagraphLayouter(), limits).layout(document(List.of(source), 100),
                FontGroup.placeholder(fonts.fonts(), '?'));
        var nested = (TableFragment) firstTable(result).rows().getFirst().cells().getFirst().blocks().getFirst();
        assertEquals(1, nested.rows().getFirst().cells().getFirst().layout().diagnostics().size());
        assertEquals(1, nested.rows().getFirst().cells().getFirst().lineCount());
        var exhausted = new DocumentLayoutLimits(2, 1, 1, 1, 1, 1, 2);
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentLayoutException.class, () -> limited(source, exhausted)).code());
    }

    @Test void nestedAutomaticMinimumOverflowAndProbeFailureKeepTheirSourceAncestry() {
        var tooWide = Table.builder("inner").cellFormat(CELL).columns(new ColumnWidth.Auto(200, 220)).row("A").build();
        var geometry = assertThrows(DocumentLayoutException.class,
                () -> layout(List.of(outer(block(tooWide), PageMargins.uniform(0))), 100));
        assertEquals(DocumentLayoutException.Code.INVALID_GEOMETRY, geometry.code());
        assertEquals(new CellLocation("outer", 0, 0, 0), geometry.sourcePath().getFirst());
        var missing = Table.builder("inner").cellFormat(CELL).columns(new ColumnWidth.Auto(20, 160))
                .row(new String(Character.toChars(0x10FFFF))).build();
        var font = assertThrows(DocumentLayoutException.class,
                () -> layout(List.of(outer(block(missing), PageMargins.uniform(0))), 100));
        assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, font.code()); assertEquals(0, font.sourceIndex());
        assertEquals(List.of(new CellLocation("outer", 0, 0, 0), new CellLocation("inner", 0, 0, -1)), font.sourcePath());
    }

    @Test void recursiveCellsContinueParagraphsAndTablesWithOriginalOrdinalsAndLineRanges() {
        var inner = Table.builder("inner").cellFormat(CELL).format(format -> format.padding(0).allowRowSplit(true))
                .header("INNER").row("A\nB\nC\nD\nE\nF").row("END").build();
        var middle = Table.builder("middle").format(format -> format.padding(0).allowRowSplit(true))
                .row(block(inner)).build();
        var outer = Table.builder("outer").cellFormat(CELL).format(format -> format.padding(0).allowRowSplit(true))
                .header("OUTER").row(block(new Paragraph("before", "BEFORE", TEXT), middle,
                        new Paragraph("after", "AFTER", TEXT))).build();
        var result = layout(List.of(outer), 50);
        assertEquals(4, result.pages().size());
        var children = result.pages().stream().map(page -> (TableFragment) page.elements().getFirst())
                .flatMap(table -> table.rows().stream().filter(row -> !row.header()))
                .flatMap(row -> row.cells().stream()).flatMap(cell -> cell.blocks().stream()).toList();
        assertEquals("before", children.getFirst().blockId()); assertEquals("after", children.getLast().blockId());
        var finalCell = ((TableFragment) result.pages().getLast().elements().getFirst()).rows().getLast().cells().getFirst();
        assertTrue(finalCell.sourceBlockIndices().contains(2));
        assertFalse(DocumentPainter.paint(result).pages().getLast().commands().isEmpty());
    }

    @Test void recursiveShortTakeAndFailedBudgetNeverConsumeNestedContent() {
        var inner = Table.builder("inner").columns(new ColumnWidth.Auto(20, 160)).cellFormat(CELL).format(format -> format.padding(0).allowRowSplit(true))
                .header("H").row("A\nB\nC").build();
        var source = Table.builder("outer").format(format -> format.padding(2).allowRowSplit(true)).row(block(inner)).build();
        var cursor = new DocumentLayouter().prepareTable(source, new ComponentContext(180, 40, fonts, 0, 0, 1));
        assertTrue(cursor.take(23).isEmpty()); assertEquals(44, cursor.remainingHeight());
        assertEquals(24, cursor.take(24).orElseThrow().height());
        assertTrue(cursor.take(23).isEmpty()); assertEquals(0, cursor.rowIndex());
        var second = cursor.take(24).orElseThrow();
        var nested = (TableFragment) second.rows().getFirst().cells().getFirst().blocks().getFirst();
        assertTrue(nested.rows().getFirst().repeatedHeader());
        assertEquals(1, nested.rows().getLast().cells().getFirst().firstLineIndex());
        assertTrue(cursor.take(24).isPresent()); assertTrue(cursor.done());
    }

    @Test void recursiveParagraphKeepTogetherAndWidowConstraintsAreObserved() {
        var paragraph = new Paragraph("child", "A\nB\nC", TEXT.toBuilder().keepTogether(true).build());
        var source = Table.builder("outer").format(format -> format.padding(0).allowRowSplit(true)).row(block(paragraph)).build();
        var cursor = new DocumentLayouter().prepareTable(source, new ComponentContext(180, 40, fonts, 0, 0, 1));
        assertTrue(cursor.take(20).isEmpty()); assertTrue(cursor.take(30).isPresent()); assertTrue(cursor.done());
    }

    @Test void recursiveRepeatedHeaderBudgetAndCancellationLeaveCursorUnchanged() {
        var inner = Table.builder("inner").cellFormat(CELL).format(format -> format.padding(0).allowRowSplit(true))
                .header("H").row("A\nB\nC").build();
        var source = Table.builder("outer").format(format -> format.padding(0).allowRowSplit(true)).row(block(inner)).build();
        var cursor = new DocumentLayouter(new ParagraphLayouter(), new DocumentLayoutLimits(10, 100, 4, 10))
                .prepareTable(source, new ComponentContext(180, 40, fonts, 0, 0, 1));
        assertTrue(cursor.take(20).isPresent());
        double remaining = cursor.remainingHeight();
        assertThrows(ExecutionException.class, () -> ExecutionContext.execute(ExecutionOptions.builder().cancelled(() -> true).build(),
                () -> cursor.take(20)));
        var failure = assertThrows(DocumentLayoutException.class, () -> cursor.take(20));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals(remaining, cursor.remainingHeight()); assertEquals(1, cursor.fragmentIndex());
        assertFalse(cursor.done()); assertEquals(0, cursor.rowIndex());
    }

    @Test void ordinaryOuterHeadersRepeatButBlockParentHeadersAreRejected() {
        var rows = List.of(row(inline("HEAD")), row(block(new Paragraph("a", "A", TEXT))),
                row(block(new Paragraph("b", "B", TEXT))));
        var source = new Table("outer", List.of(new ColumnWidth.Fraction(1)), rows, 1,
                new TableFormat(PageMargins.uniform(0), 0, 0));
        var result = layout(List.of(source), 20);
        assertEquals(2, result.pages().size());
        assertTrue(((TableFragment) result.pages().getLast().elements().getFirst()).rows().getFirst().repeatedHeader());
        var bad = new Table("outer", source.columns(), List.of(row(block(new Paragraph("head", "H", TEXT))), row(inline("A"))),
                1, source.format());
        assertEquals(DocumentLayoutException.Code.INVALID_GEOMETRY,
                assertThrows(DocumentLayoutException.class, () -> layout(List.of(bad), 40)).code());
    }

    @Test void verticalParentAndChildSpansKeepOneSourceTree() {
        var inner = new Table("inner", List.of(new ColumnWidth.Fraction(1)),
                List.of(row(inline("A\nB").toBuilder().rowSpan(2).build()), TableRow.covered()), 0,
                new TableFormat(PageMargins.uniform(1), 0, 0));
        var source = new Table("outer", List.of(new ColumnWidth.Fraction(1)),
                List.of(row(block(inner).toBuilder().rowSpan(2).build()), TableRow.covered()), 0,
                new TableFormat(PageMargins.uniform(2), 0, 0));
        var result = firstTable(layout(List.of(source), 40));
        assertEquals(26, result.height()); assertEquals(1, result.rows().size());
        var cell = result.rows().getFirst().cells().getFirst(); assertEquals(2, cell.lineCount());
        var nested = (TableFragment) cell.blocks().getFirst(); assertEquals(1, nested.rows().size());
        assertEquals(22, nested.rows().getFirst().cells().getFirst().bounds().height());
    }

    @Test void overheightAtomicSubtreeFailsWithoutRetryingPages() {
        var source = outer(block(new Paragraph("too-tall", "A\nB\nC\nD", TEXT)), PageMargins.uniform(1));
        var failure = assertThrows(DocumentLayoutException.class, () -> layout(List.of(source), 30));
        assertEquals(DocumentLayoutException.Code.CONTENT_TOO_TALL, failure.code()); assertEquals("too-tall", failure.blockId());
        assertEquals(0, failure.pageIndex()); assertEquals(0, failure.sourcePath().getFirst().blockIndex());
    }

    @Test void cancelledCursorRestoresScopeAndLeavesAtomicContentAvailable() {
        var source = outer(block(new Paragraph("child", "A", TEXT)), PageMargins.uniform(0));
        var cursor = new DocumentLayouter().prepareTable(source, new ComponentContext(180, 40, fonts, 0, 0, 1));
        assertThrows(ExecutionException.class, () -> ExecutionContext.execute(ExecutionOptions.builder().cancelled(() -> true).build(),
                () -> cursor.take(40)));
        ExecutionContext.checkpoint(); assertFalse(cursor.done()); assertTrue(cursor.take(40).isPresent());
    }

    @Test void streamingBodyRejectsBlockCellBeforeDeliveringAnyPage() throws IOException {
        var source = StreamingTable.builder("stream").columns(180).build();
        try (var rows = new TableRowSource() {
            @Override public Optional<TableRow> next() { return Optional.of(row(block(new Paragraph("child", "A", TEXT)))); }
            @Override public void close() { }
        }) {
            var failure = assertThrows(DocumentLayoutException.class, () -> new StreamingTableLayouter().layout(source, rows, fonts,
                    page -> { throw new AssertionError("page must not be delivered"); }, diagnostic -> { }, StreamingTableLimits.DEFAULT));
            assertEquals(DocumentLayoutException.Code.UNSUPPORTED_COMPONENT, failure.code()); assertEquals(0, failure.columnIndex());
        }
    }

    private static TableCell inline(String text) { return new TableCell(text, CELL); }
    private static TableCell block(Block... blocks) { return TableCell.builder().format(CELL).blocks(List.of(blocks)).build(); }
    private static TableRow row(TableCell... cells) { return new TableRow(List.of(cells)); }
    private static Table inner(String id, String text) {
        return new Table(id, List.of(new ColumnWidth.Fraction(1)), List.of(row(inline(text))), 0,
                new TableFormat(PageMargins.uniform(0), 0, 0));
    }
    private static Table outer(TableCell cell, PageMargins padding) {
        return new Table("outer", List.of(new ColumnWidth.Fraction(1)), List.of(row(cell)), 0,
                new TableFormat(padding, 0, 0));
    }
    private static Document document(List<Block> blocks, double height) {
        return new Document(DocumentMetadata.EMPTY, PageSettings.builder().size(180, height).margins(0).build(), blocks);
    }
    private static DocumentLayout layout(List<Block> blocks, double height) { return new DocumentLayouter().layout(document(blocks, height), fonts); }
    private static DocumentLayout limited(Table table, DocumentLayoutLimits limits) {
        return new DocumentLayouter(new ParagraphLayouter(), limits).layout(document(List.of(table), 100), fonts);
    }
    private static TableFragment firstTable(DocumentLayout layout) { return (TableFragment) layout.pages().getFirst().elements().getFirst(); }
}
