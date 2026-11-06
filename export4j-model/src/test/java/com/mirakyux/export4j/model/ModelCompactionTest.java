// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ModelCompactionTest {
    private static final CellFormat FORMAT = CellFormat.of(12);

    @Test
    void equalCellsAndStylesShareWithinBuilderWithoutChangingDocumentValuesOrSnapshots() {
        var first = table("first", List.of(cell("repeat"), cell("first-only")));
        var second = table("second", List.of(cell("repeat"), cell("second-only")));
        var builder = Document.builder().add(first).add(second);
        var document = builder.build();
        var storedFirst = (Table) document.blocks().get(0);
        var storedSecond = (Table) document.blocks().get(1);

        assertEquals(List.of(first, second), document.blocks());
        assertSame(at(storedFirst, 0), at(storedSecond, 0));
        assertNotSame(at(storedFirst, 1), at(storedSecond, 1));
        assertSame(at(storedFirst, 0).inlines().getFirst().style(), at(storedFirst, 1).inlines().getFirst().style());
        assertSame(at(storedFirst, 0).inlines().getFirst().style(), at(storedSecond, 1).inlines().getFirst().style());

        builder.paragraph("later", "later");
        var later = builder.build();
        assertEquals(2, document.blocks().size());
        assertEquals(3, later.blocks().size());
        assertEquals(document.blocks(), later.blocks().subList(0, 2));
        assertThrows(UnsupportedOperationException.class, () -> document.blocks().clear());
    }

    @Test
    void sharingDoesNotIntroduceCrossDocumentCaches() {
        var first = Document.builder().add(table("first", List.of(cell("repeat"), cell("repeat")))).build();
        var second = Document.builder().add(table("second", List.of(cell("repeat"), cell("repeat")))).build();
        var firstTable = (Table) first.blocks().getFirst();
        var secondTable = (Table) second.blocks().getFirst();
        var firstCell = at(firstTable, 0);
        var secondCell = at(secondTable, 0);

        assertSame(firstCell, at(firstTable, 1));
        assertSame(secondCell, at(secondTable, 1));
        assertEquals(firstCell, secondCell);
        assertNotSame(firstCell, secondCell);
        assertNotSame(firstCell.inlines().getFirst().style(), secondCell.inlines().getFirst().style());
    }

    @Test
    void compactionPreservesTableGeometryAndDistinctRunOrHyperlinkValues() {
        var first = spannedTable("first");
        var second = spannedTable("second");
        var document = Document.builder().add(first).add(second).build();
        var storedFirst = (Table) document.blocks().get(0);
        var storedSecond = (Table) document.blocks().get(1);

        assertEquals(List.of(first, second), document.blocks());
        assertSame(at(storedFirst, 1), at(storedSecond, 1));
        assertEquals(2, at(storedSecond, 1).columnSpan());
        assertEquals(2, at(storedSecond, 1).rowSpan());
        assertEquals(List.of(), storedSecond.rows().get(2).cells());
        assertEquals(1, storedSecond.headerRows());
        assertEquals(first.columns(), storedSecond.columns());
        assertEquals(first.format(), storedSecond.format());

        var split = TableCell.rich(List.of(new InlineText("a", style()), new InlineText("", style()),
                new InlineText("b", style())), FORMAT);
        var splitCopy = TableCell.rich(List.of(new InlineText("a", style()), new InlineText("", style()),
                new InlineText("b", style())), FORMAT);
        var combined = cell("ab");
        var linked = TableCell.rich(List.of(new InlineText("a", style(), "https://example.com/a"),
                new InlineText("", style()), new InlineText("b", style())), FORMAT);
        var runsTable = table("runs", List.of(split, splitCopy, combined, linked));
        var storedRuns = (Table) Document.builder().add(runsTable).build().blocks().getFirst();

        assertEquals(runsTable, storedRuns);
        assertSame(at(storedRuns, 0), at(storedRuns, 1));
        assertNotSame(at(storedRuns, 0), at(storedRuns, 2));
        assertNotSame(at(storedRuns, 0), at(storedRuns, 3));
        assertEquals(List.of("a", "", "b"), at(storedRuns, 0).inlines().stream().map(InlineText::text).toList());
        assertEquals("https://example.com/a", at(storedRuns, 3).inlines().getFirst().hyperlink());
    }

    @Test
    void largeOrResourceBearingCellsBypassSharingWithoutLosingContent() {
        var longSource = "😀long content ".repeat(1000);
        var longFirst = cell(longSource);
        var longSecond = cell(longSource);
        var manyRuns = new ArrayList<InlineText>();
        for (int i = 0; i < 40; i++) { manyRuns.add(new InlineText("x", style())); }
        var runsFirst = TableCell.rich(manyRuns, FORMAT);
        var runsSecond = TableCell.rich(manyRuns, FORMAT);
        var longRole = new InlineStyle("role".repeat(100), 12, 0, 0, false, false);
        var roleFirst = TableCell.rich(List.of(new InlineText("role text", longRole)), FORMAT);
        var roleSecond = TableCell.rich(List.of(new InlineText("role text", longRole)), FORMAT);
        var field = new InlineField(InlineField.Kind.PAGE, "", 24, style());
        var fieldFirst = TableCell.mixed(List.of(field), FORMAT);
        var fieldSecond = TableCell.mixed(List.of(field), FORMAT);
        var longLink = "https://example.com/" + "x".repeat(6000);
        var linkedFirst = TableCell.rich(List.of(new InlineText("linked", style(), longLink)), FORMAT);
        var linkedSecond = TableCell.rich(List.of(new InlineText("linked", style(), longLink)), FORMAT);
        var sourceCells = List.of(longFirst, longSecond, runsFirst, runsSecond,
                roleFirst, roleSecond, fieldFirst, fieldSecond, linkedFirst, linkedSecond);
        var source = table("large", sourceCells);

        var stored = (Table) Document.builder().add(source).build().blocks().getFirst();

        assertEquals(source, stored);
        for (int i = 0; i < sourceCells.size(); i++) { assertSame(sourceCells.get(i), at(stored, i)); }
        assertEquals(longSource, at(stored, 1).text());
        assertEquals(40, at(stored, 3).inlines().size());
        assertEquals(longRole, at(stored, 5).inlines().getFirst().style());
        assertEquals(field, at(stored, 7).fields().getFirst().field());
        assertEquals(longLink, at(stored, 9).inlines().getFirst().hyperlink());
    }

    @Test
    void finiteCellCacheEvictsOldValuesWithoutDroppingSourceRows() {
        var cells = new ArrayList<TableCell>();
        cells.add(cell("first-value"));
        for (int i = 0; i < 6000; i++) { cells.add(cell("unique-value-" + i)); }
        cells.add(cell("first-value"));
        var source = table("many-values", cells);

        var stored = (Table) Document.builder().add(source).build().blocks().getFirst();

        assertEquals(source, stored);
        assertEquals(cells.size(), stored.rows().size());
        assertEquals(at(stored, 0), at(stored, stored.rows().size() - 1));
        assertNotSame(at(stored, 0), at(stored, stored.rows().size() - 1));
        assertEquals("unique-value-5999", at(stored, stored.rows().size() - 2).text());
    }

    @Test
    void automaticIdsAvoidExplicitAndNestedIdsWhenBuilderIsReusedAfterBuild() {
        var builder = Document.builder().paragraph("auto-1", "first explicit");
        var first = builder.build();
        var nested = table("nested", List.of(TableCell.builder()
                .paragraph(new Paragraph("auto-3", "nested explicit", ParagraphFormat.of(12))).build()));
        builder.paragraph("auto-2", "second explicit").add(nested).paragraph("first automatic");
        var second = builder.build();

        var inner = table("auto-6", List.of(TableCell.builder()
                .paragraph(new Paragraph("auto-7", "deep explicit", ParagraphFormat.of(12))).build()));
        var outer = table("outer", List.of(TableCell.builder().blocks(List.of(inner)).build()));
        builder.paragraph("auto-5", "third explicit").add(outer)
                .block(id -> new Paragraph(id, "second automatic", ParagraphFormat.of(12)));
        var third = builder.build();

        assertEquals(List.of("auto-1"), first.blocks().stream().map(Block::id).toList());
        assertEquals(List.of("auto-1", "auto-2", "nested", "auto-4"),
                second.blocks().stream().map(Block::id).toList());
        assertEquals(List.of("auto-1", "auto-2", "nested", "auto-4", "auto-5", "outer", "auto-8"),
                third.blocks().stream().map(Block::id).toList());
        assertEquals(first.blocks(), third.blocks().subList(0, 1));
        assertEquals(second.blocks(), third.blocks().subList(0, 4));
        var nestedLeaf = (Paragraph) ((Table) third.blocks().get(2)).rows().getFirst().cells().getFirst().blocks().getFirst();
        var innerTable = (Table) ((Table) third.blocks().get(5)).rows().getFirst().cells().getFirst().blocks().getFirst();
        assertEquals("auto-3", nestedLeaf.id());
        assertEquals("auto-6", innerTable.id());
        assertEquals("auto-7", innerTable.rows().getFirst().cells().getFirst().blocks().getFirst().id());
    }

    @Test
    void paragraphStyleSharingPreservesObjectsBoundariesAndFormattingAndBypassesLargeValues() throws IOException {
        var format = new ParagraphFormat(12, 22, 3, 2, ParagraphAlignment.RIGHT, 4, 7, true)
                .toBuilder().rotation(15).build();
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB), "png", bytes);
        var image = new InlineImage(ImageResource.load(bytes.toByteArray()), 8, 12, 0,
                "Icon", "https://example.com/icon");
        var first = objectParagraph("first-paragraph", image, format);
        var second = objectParagraph("second-paragraph", image, format);
        var longSource = "long source ".repeat(500);
        var longParagraph = new Paragraph("long-paragraph", longSource, format,
                List.of(new InlineText(longSource, style())));
        var manyRuns = new ArrayList<InlineText>();
        for (int i = 0; i < 40; i++) { manyRuns.add(new InlineText("x", style())); }
        var runsParagraph = new Paragraph("runs-paragraph", manyRuns, format);
        var longRole = new InlineStyle("role".repeat(100), 12, 0x123456, 0, false, false);
        var roleParagraph = new Paragraph("role-paragraph", List.of(new InlineText("role", longRole)), format);
        var longLink = "https://example.com/" + "x".repeat(6000);
        var linkedParagraph = new Paragraph("link-paragraph", List.of(new InlineText("link", style(), longLink)), format);
        var expected = List.of(first, second, longParagraph, runsParagraph, roleParagraph, linkedParagraph);
        var builder = Document.builder();
        expected.forEach(builder::add);

        var document = builder.build();
        var storedFirst = (Paragraph) document.blocks().get(0);
        var storedSecond = (Paragraph) document.blocks().get(1);

        assertEquals(expected, document.blocks());
        assertNotSame(storedFirst, storedSecond);
        assertSame(storedFirst.inlines().getFirst().style(), storedSecond.inlines().getFirst().style());
        assertSame(storedFirst.inlines().getFirst().style(), storedFirst.inlines().get(1).style());
        assertSame(storedFirst.inlines().getFirst().style(), storedFirst.inlines().get(3).style());
        assertEquals("A😀\ufffc\ufffctail", storedFirst.text());
        assertEquals(List.of("", "A😀", "\ufffc", "\ufffc", "", "tail"),
                storedFirst.inlines().stream().map(InlineText::text).toList());
        assertEquals("https://example.com/text", storedFirst.inlines().get(1).hyperlink());
        assertEquals(image.hyperlink(), storedFirst.inlines().get(2).hyperlink());
        assertEquals("", storedFirst.inlines().get(3).hyperlink());
        assertEquals(3, storedFirst.images().getFirst().sourceIndex());
        assertEquals(4, storedFirst.fields().getFirst().sourceIndex());
        assertSame(image, storedFirst.images().getFirst().image());
        assertEquals(first.fields(), storedFirst.fields());
        assertEquals(format, storedFirst.format());
        for (int i = 2; i < expected.size(); i++) { assertSame(expected.get(i), document.blocks().get(i)); }

        var other = (Paragraph) Document.builder().add(objectParagraph("other-paragraph", image, format))
                .build().blocks().getFirst();
        assertEquals(storedFirst.inlines(), other.inlines());
        assertNotSame(storedFirst.inlines().getFirst().style(), other.inlines().getFirst().style());
    }

    private static InlineStyle style() { return new InlineStyle("body", 12, 0x123456, 0, false, false); }

    private static TableCell cell(String source) { return TableCell.rich(List.of(new InlineText(source, style())), FORMAT); }

    private static Table table(String id, List<TableCell> cells) {
        return new Table(id, List.of(new ColumnWidth.Fraction(1)),
                cells.stream().map(value -> new TableRow(List.of(value))).toList(), 0, TableFormat.DEFAULT);
    }

    private static TableCell at(Table table, int row) { return table.rows().get(row).cells().getFirst(); }

    private static Paragraph objectParagraph(String id, InlineImage image, ParagraphFormat format) {
        return Paragraph.mixed(id, List.of(new InlineText("", style()),
                new InlineText("A😀", style(), "https://example.com/text"), image,
                new InlineField(InlineField.Kind.PAGE, "", 24, style()),
                new InlineText("", style()), new InlineText("tail", style())), format);
    }

    private static Table spannedTable(String id) {
        var header = cell("header").toBuilder().columnSpan(2).build();
        var body = cell("body").toBuilder().columnSpan(2).rowSpan(2).build();
        return new Table(id, List.of(new ColumnWidth.Fixed(40), new ColumnWidth.Fraction(2)),
                List.of(new TableRow(List.of(header)), new TableRow(List.of(body)), TableRow.covered()), 1,
                new TableFormat(PageMargins.uniform(3), 2, 7, false, false, 15));
    }
}
