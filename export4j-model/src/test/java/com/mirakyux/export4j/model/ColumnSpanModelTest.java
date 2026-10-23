// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ColumnSpanModelTest {
    private static final CellFormat FORMAT = CellFormat.of(12);

    @Test
    void legacyConstructorsFactoriesAndDefaultBuilderKeepOneColumn() {
        var inline = new InlineText("text", InlineStyle.of(12));
        var cells = List.of(new TableCell("text", FORMAT), new TableCell("text", FORMAT, List.of(inline)),
                new TableCell("text", FORMAT, List.of(inline), List.of()),
                new TableCell("text", FORMAT, List.of(inline), List.of(), List.of()),
                TableCell.rich(List.of(inline), FORMAT), TableCell.mixed(List.of(inline), FORMAT),
                TableCell.builder().text("text").build());
        assertTrue(cells.stream().allMatch(cell -> cell.columnSpan() == 1));
    }

    @Test
    void contentBuilderSnapshotsAndToBuilderPreservesRichImageAndFieldSources() throws IOException {
        var pixels = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", pixels);
        var image = new InlineImage(ImageResource.load(pixels.toByteArray()), 8, 8, 0, "image",
                "https://example.com/image");
        var field = new InlineField(InlineField.Kind.PAGE, "", 24, InlineStyle.of(12));
        var rich = new InlineText("A😀", new InlineStyle("bold", 12, 0x123456, 0, true, false),
                "https://example.com/text");
        List<InlineContent> content = new ArrayList<>(List.of(rich, field, image));
        var builder = TableCell.builder().format(FORMAT).content(content).columnSpan(3).rowSpan(2);
        content.clear();
        var original = builder.build();
        assertEquals("A😀\ufffc\ufffc", original.text());
        assertEquals(3, original.fields().getFirst().sourceIndex());
        assertEquals(4, original.images().getFirst().sourceIndex());
        assertEquals(original, original.toBuilder().build());
        assertEquals(2, original.rowSpan());
        var resized = original.toBuilder().columnSpan(2).build();
        assertEquals(2, resized.columnSpan());
        assertEquals(2, resized.rowSpan());
        assertEquals(original.text(), resized.text());
        assertEquals(original.inlines(), resized.inlines());
        assertEquals(original.images(), resized.images());
        assertEquals(original.fields(), resized.fields());
        builder.text("replacement").columnSpan(1);
        assertEquals(3, original.columnSpan());
        assertEquals(1, builder.build().columnSpan());
    }

    @Test
    void canonicalListsAndCopiesRemainImmutableAndTextReplacementClearsObjects() throws IOException {
        var pixels = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", pixels);
        var mixed = TableCell.mixed(List.of(new InlineText("X", InlineStyle.of(12)),
                new InlineImage(ImageResource.load(pixels.toByteArray()), 6, 6, 0, "image"),
                new InlineField(InlineField.Kind.PAGES, "", 24, InlineStyle.of(12))), FORMAT);
        var inlines = new ArrayList<>(mixed.inlines());
        var images = new ArrayList<>(mixed.images());
        var fields = new ArrayList<>(mixed.fields());
        var cell = new TableCell(mixed.text(), FORMAT, inlines, images, fields, 2);
        inlines.clear(); images.clear(); fields.clear();
        assertEquals(mixed.inlines(), cell.inlines());
        assertEquals(mixed.images(), cell.images());
        assertEquals(mixed.fields(), cell.fields());
        assertThrows(UnsupportedOperationException.class, () -> cell.inlines().clear());
        assertThrows(UnsupportedOperationException.class, () -> cell.images().clear());
        assertThrows(UnsupportedOperationException.class, () -> cell.fields().clear());
        var replaced = cell.toBuilder().text("plain").build();
        assertEquals("plain", replaced.text());
        assertEquals(2, replaced.columnSpan());
        assertTrue(replaced.inlines().isEmpty());
        assertTrue(replaced.images().isEmpty());
        assertTrue(replaced.fields().isEmpty());
        var newContent = cell.toBuilder().content(List.of(new InlineText("new", InlineStyle.of(12)))).build();
        assertEquals("new", newContent.text());
        assertEquals(2, newContent.columnSpan());
        assertTrue(newContent.images().isEmpty());
        assertTrue(newContent.fields().isEmpty());
    }

    @Test
    void spansMustBePositiveAtBothCanonicalAndBuilderBoundaries() {
        for (int span : new int[] {0, -1, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new TableCell("x", FORMAT, List.of(), List.of(), List.of(), span));
            assertThrows(IllegalArgumentException.class, () -> TableCell.builder().columnSpan(span).build());
        }
    }

    @Test
    void physicalLookupReturnsRealAnchorRatherThanPlaceholderOrAnchorIndex() {
        var merged = cell("merged", 2);
        var trailing = cell("trailing", 1);
        var row = new TableRow(List.of(merged, trailing));
        assertEquals(2, row.cells().size());
        assertEquals(3, row.columnCount());
        assertSame(merged, row.cellAtColumn(0));
        assertSame(merged, row.cellAtColumn(1));
        assertSame(trailing, row.cellAtColumn(2));
        for (int column : new int[] {-1, 3, Integer.MAX_VALUE}) {
            assertThrows(IndexOutOfBoundsException.class, () -> row.cellAtColumn(column));
        }
    }

    @Test
    void coverageOverflowIsRejectedBeforeInferenceOrLookup() {
        var largest = cell("huge", Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> new TableRow(List.of(largest, cell("overflow", 1))));
        var row = new TableRow(List.of(largest));
        assertEquals(Integer.MAX_VALUE, row.columnCount());
        assertSame(largest, row.cellAtColumn(Integer.MAX_VALUE - 1));
        assertThrows(IndexOutOfBoundsException.class, () -> row.cellAtColumn(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> Table.builder("huge").row(largest).build());
    }

    @Test
    void everyHeaderAndBodyMustCoverExactlyTheExplicitGrid() {
        var a = cell("A", 2);
        var b = cell("B", 1);
        var valid = Table.builder("table").columns(new ColumnWidth.Fixed(40), new ColumnWidth.Fraction(1),
                new ColumnWidth.Fraction(2)).headerCells(cell("header", 3)).row(a, b).build();
        assertEquals(3, valid.columns().size());
        assertEquals(1, valid.headerRows());
        assertEquals(2, valid.rows().getLast().cells().size());
        assertThrows(IllegalArgumentException.class,
                () -> Table.builder("short").columns(new ColumnWidth.Fixed(40), new ColumnWidth.Fixed(40))
                        .row(b).build());
        assertThrows(IllegalArgumentException.class,
                () -> Table.builder("overrun").columns(new ColumnWidth.Fixed(40), new ColumnWidth.Fixed(40))
                        .row(a, b).build());
        assertThrows(IllegalArgumentException.class,
                () -> Table.builder("header").columns(new ColumnWidth.Fixed(40), new ColumnWidth.Fixed(40))
                        .headerCells(b).row(a).build());
    }

    @Test
    void implicitColumnsUsePhysicalCoverageWithBoundedAllocation() {
        var table = Table.builder("implicit").row(cell("merged", 2), cell("last", 1))
                .row(cell("one", 1), cell("two", 1), cell("three", 1)).build();
        assertEquals(3, table.columns().size());
        assertTrue(table.columns().stream().allMatch(column -> column.equals(new ColumnWidth.Fraction(1))));
        assertEquals(Table.MAX_INFERRED_COLUMNS, Table.builder("limit")
                .row(cell("wide", Table.MAX_INFERRED_COLUMNS)).build().columns().size());
        assertThrows(IllegalArgumentException.class, () -> Table.builder("over-limit")
                .row(cell("wide", Table.MAX_INFERRED_COLUMNS + 1)).build());
        var columns = IntStream.range(0, Table.MAX_INFERRED_COLUMNS + 1)
                .<ColumnWidth>mapToObj(ignored -> new ColumnWidth.Fixed(1)).toList();
        assertEquals(Table.MAX_INFERRED_COLUMNS + 1,
                new Table("explicit", columns, List.of(new TableRow(List.of(cell("wide", columns.size())))),
                        0, TableFormat.DEFAULT).columns().size());
    }

    @Test
    void headerCellsRemainUnambiguousAndHeadersCannotFollowBodyRows() {
        var table = Table.builder("headers").headerCells(cell("merged", 2)).header("A", "B")
                .row("body A", "body B").build();
        assertEquals(2, table.headerRows());
        assertEquals(2, table.rows().getFirst().cells().getFirst().columnSpan());
        assertEquals(List.of(1, 1), table.rows().get(1).cells().stream().map(TableCell::columnSpan).toList());
        assertThrows(IllegalStateException.class, () -> Table.builder("late").row("body")
                .headerCells(cell("header", 1)));
        assertThrows(IllegalArgumentException.class, () -> Table.builder("empty").headerCells());
        assertThrows(IllegalArgumentException.class, () -> Table.builder("header-only")
                .headerCells(cell("header", 2)).build());
    }

    @Test
    void streamingHeadersRequireExactSpanCoverageInsteadOfMatchingAnchorCount() {
        assertThrows(IllegalArgumentException.class, () -> StreamingTable.builder("stream")
                .columns(50, 50).header(new TableRow(List.of(cell("merged", 2), cell("trailing", 1)))).build());
        var spec = StreamingTable.builder("stream")
                .columns(50, 50).header(new TableRow(List.of(cell("merged", 2)))).build();
        assertEquals(2, spec.headerRows().getFirst().cells().getFirst().columnSpan());
        assertThrows(IllegalArgumentException.class, () -> spec.toBuilder()
                .headerRows(List.of(new TableRow(List.of(cell("short", 1))))).build());
        assertThrows(IllegalArgumentException.class, () -> spec.toBuilder()
                .headerRows(List.of(new TableRow(List.of(cell("overflow", Integer.MAX_VALUE))))).build());
    }

    private static TableCell cell(String text, int span) {
        return TableCell.builder().text(text).format(FORMAT).columnSpan(span).build();
    }
}
