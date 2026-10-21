// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class DocumentTemplateTest {
    @Test void bindsRichSemanticBlocksWithoutDroppingStylesOrHyperlinks() {
        var runs = List.of(new InlineText("${name}", new InlineStyle("bold", 12, 0x224488, 0, true, false), "https://example.com"));
        var template = new DocumentTemplate(DocumentMetadata.EMPTY, PAGE, List.of(
                new DocumentTemplate.ValueBlock(Heading.rich("h", runs, 1, FORMAT)),
                new DocumentTemplate.ValueBlock(BulletItem.rich("b", runs, "•", 1, FORMAT)),
                new DocumentTemplate.ValueBlock(NumberedItem.rich("n", "list", runs, 0, FORMAT, 1))), DocumentTemplate.Limits.DEFAULT);
        var blocks = template.render(Map.of("name", "Bound")).blocks();
        assertEquals(List.of("Bound", "Bound", "Bound"), blocks.stream().map(block -> switch (block) {
            case Heading h -> h.text(); case BulletItem b -> b.text(); case NumberedItem n -> n.text();
            default -> throw new AssertionError();
        }).toList());
        assertEquals(runs.getFirst().style(), ((Heading) blocks.getFirst()).inlines().getFirst().style());
        assertEquals("https://example.com", ((NumberedItem) blocks.getLast()).inlines().getFirst().hyperlink());
    }

    private static final PageSettings PAGE = new PageSettings(PageSize.A4, new PageMargins(40, 40, 40, 40));
    private static final ParagraphFormat FORMAT = ParagraphFormat.of(12);
    @Test void bindsVariablesConditionsAndNestedCollectionScopeWithStableIds() {
        var template = new DocumentTemplate(new DocumentMetadata("${customer.name}", ""), PAGE,
                List.of(new DocumentTemplate.When("visible", List.of(new DocumentTemplate.Each("items", "item", List.of(
                        new DocumentTemplate.ValueBlock(new Paragraph("line", "${customer.name}: ${item.name}", FORMAT))))), List.of())), DocumentTemplate.Limits.DEFAULT);
        var data = Map.of("customer", Map.of("name", "客户"), "visible", true, "items", List.of(Map.of("name", "A"), Map.of("name", "B")));
        var result = template.render(data);
        assertEquals("客户", result.metadata().title());
        assertEquals(List.of("line@0", "line@1"), result.blocks().stream().map(Block::id).toList());
        assertEquals(List.of("客户: A", "客户: B"), result.paragraphs().stream().map(Paragraph::text).toList());
        assertEquals(result, template.render(data));
    }
    @Test void generatesTableRowsAndRejectsMissingValuesWrongTypesAndExpansionBudgets() {
        var row = new TableRow(List.of(new TableCell("${item.name}", CellFormat.of(12))));
        var template = new DocumentTemplate(DocumentMetadata.EMPTY, PAGE, List.of(new DocumentTemplate.Rows("table", "items", "item",
                List.of(new ColumnWidth.Fraction(1)), List.of(new TableRow(List.of(new TableCell("Name", CellFormat.of(12))))), row, TableFormat.DEFAULT)), DocumentTemplate.Limits.DEFAULT);
        var result = (Table) template.render(Map.of("items", List.of(Map.of("name", "A"), Map.of("name", "B")))).blocks().getFirst();
        assertEquals(1, result.headerRows()); assertEquals(3, result.rows().size());
        assertEquals(DocumentTemplate.BindingException.Code.MISSING_VALUE, assertThrows(DocumentTemplate.BindingException.class,
                () -> template.render(Map.of())).code());
        assertEquals(DocumentTemplate.BindingException.Code.TYPE_MISMATCH, assertThrows(DocumentTemplate.BindingException.class,
                () -> template.render(Map.of("items", "invalid"))).code());
        assertEquals(DocumentTemplate.BindingException.Code.EMPTY_TABLE, assertThrows(DocumentTemplate.BindingException.class,
                () -> template.render(Map.of("items", List.of()))).code());
        var bounded = new DocumentTemplate(DocumentMetadata.EMPTY, PAGE, template.nodes(), new DocumentTemplate.Limits(100, 1, 1, 100, 5));
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED, assertThrows(DocumentTemplate.BindingException.class,
                () -> bounded.render(Map.of("items", List.of(Map.of("name", "A"))))).code());
    }

    @Test void valueTableBindingPreservesRectangularSpansRichImagesFieldsAndBoundUtf16Positions() throws IOException {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        var resource = ImageResource.load(bytes.toByteArray());
        var style = new InlineStyle("bold", 12, 0x224488, 0, true, false);
        var image = new InlineImage(resource, 12, 14, 0, "Logo ${name}", "https://example.com/image");
        var cell = TableCell.builder().columnSpan(2).rowSpan(2).format(CellFormat.of(12)).content(List.of(
                new InlineText("${name}", style, "https://example.com/text"), image,
                new InlineField(InlineField.Kind.REFERENCE_PAGE, "${target}", 24, style))).build();
        var header = new TableRow(List.of(TableCell.builder().text("${caption}").columnSpan(3).build()));
        var source = new Table("table", List.of(new ColumnWidth.Fraction(1), new ColumnWidth.Fixed(80), new ColumnWidth.Fraction(2)),
                List.of(header, new TableRow(List.of(cell, new TableCell("${tail}", CellFormat.of(12)))),
                        new TableRow(List.of(new TableCell("Final", CellFormat.of(12))))), 1, TableFormat.DEFAULT);
        var template = DocumentTemplate.builder().block(source).build();
        var bound = (Table) template.render(Map.of("name", "A😀", "target", "chapter", "caption", "Heading", "tail", "Tail"))
                .blocks().getFirst();
        assertEquals(3, bound.columns().size());
        assertEquals(3, bound.rows().getFirst().cells().getFirst().columnSpan());
        assertEquals("Heading", bound.rows().getFirst().cells().getFirst().text());
        var row = bound.rows().get(1);
        assertEquals(2, row.cells().size());
        assertEquals(3, row.columnCount());
        var actual = row.cellAtColumn(1);
        assertSame(row.cells().getFirst(), actual);
        assertEquals(2, actual.columnSpan());
        assertEquals(2, actual.rowSpan());
        assertSame(actual, bound.grid().ownerAt(2, 1).cell());
        assertEquals("Final", bound.grid().ownerAt(2, 2).cell().text());
        assertEquals("A😀\ufffc\ufffc", actual.text());
        assertEquals(style, actual.inlines().getFirst().style());
        assertEquals("https://example.com/text", actual.inlines().getFirst().hyperlink());
        assertEquals(3, actual.images().getFirst().sourceIndex());
        assertSame(resource, actual.images().getFirst().image().resource());
        assertEquals("Logo A😀", actual.images().getFirst().image().altText());
        assertEquals("https://example.com/image", actual.images().getFirst().image().hyperlink());
        assertEquals(4, actual.fields().getFirst().sourceIndex());
        assertEquals("chapter", actual.fields().getFirst().field().targetId());
        assertEquals("Tail", row.cellAtColumn(2).text());
        assertEquals(1, row.cellAtColumn(2).columnSpan());
        assertEquals("${name}\ufffc\ufffc", source.rows().get(1).cells().getFirst().text());
    }

    @Test void dynamicTableRowsValidateCoverageAndRetainDifferentAnchorCountsAfterExpansion() {
        var columns = List.<ColumnWidth>of(new ColumnWidth.Fixed(60), new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1));
        var header = new TableRow(List.of(TableCell.builder().text("${caption}").columnSpan(3).build()));
        var cell = TableCell.builder().columnSpan(2).content(List.of(new InlineText("${item.name}", InlineStyle.of(12)),
                new InlineField(InlineField.Kind.REFERENCE_PAGE, "${item.target}", 24, InlineStyle.of(12)))).build();
        var prototype = new TableRow(List.of(cell, new TableCell("${item.tail}", CellFormat.of(12))));
        var rows = DocumentTemplate.Rows.builder().id("rows").path("items").variable("item")
                .columns(columns).headers(List.of(header)).row(prototype).build();
        var template = DocumentTemplate.builder().node(rows).build();
        var table = (Table) template.render(Map.of("caption", "Full width", "items", List.of(
                Map.of("name", "A😀", "target", "first", "tail", "Tail-A"),
                Map.of("name", "Longer", "target", "second", "tail", "Tail-B")))).blocks().getFirst();
        assertEquals(1, table.headerRows());
        assertEquals(List.of(1, 2, 2), table.rows().stream().map(row -> row.cells().size()).toList());
        assertTrue(table.rows().stream().allMatch(row -> row.columnCount() == 3));
        assertEquals(List.of("A😀\ufffc", "Longer\ufffc"), table.rows().subList(1, 3).stream()
                .map(row -> row.cells().getFirst().text()).toList());
        assertEquals(List.of(3, 6), table.rows().subList(1, 3).stream()
                .map(row -> row.cells().getFirst().fields().getFirst().sourceIndex()).toList());
        assertEquals(List.of("first", "second"), table.rows().subList(1, 3).stream()
                .map(row -> row.cells().getFirst().fields().getFirst().field().targetId()).toList());
        assertEquals(List.of("Tail-A", "Tail-B"), table.rows().subList(1, 3).stream().map(row -> row.cellAtColumn(2).text()).toList());
        assertEquals(3, table.rows().getFirst().cells().getFirst().columnSpan());
        assertTrue(table.rows().subList(1, 3).stream().allMatch(row -> row.cells().getFirst().columnSpan() == 2));
    }

    @Test void dynamicTablePrototypesAndEveryHeaderMustCoverAllPhysicalColumns() {
        var columns = List.<ColumnWidth>of(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1));
        var one = new TableCell("one", CellFormat.of(12));
        var two = TableCell.builder().text("two").columnSpan(2).build();
        var valid = new TableRow(List.of(two, one));
        for (var invalid : List.of(new TableRow(List.of(two)), new TableRow(List.of(two, two)), new TableRow(List.of(two, one, one)))) {
            assertThrows(IllegalArgumentException.class, () -> new DocumentTemplate.Rows("rows", "items", "item",
                    columns, List.of(valid), invalid, TableFormat.DEFAULT));
            assertThrows(IllegalArgumentException.class, () -> new DocumentTemplate.Rows("rows", "items", "item",
                    columns, List.of(valid, invalid), valid, TableFormat.DEFAULT));
        }
        assertThrows(IllegalArgumentException.class, () -> new DocumentTemplate.Rows("rows", "items", "item", columns,
                List.of(valid), new TableRow(List.of(TableCell.builder().columnSpan(Integer.MAX_VALUE).build(), one)), TableFormat.DEFAULT));
    }

    @Test void rejectsNumericSubclassesBeforeAnyOverridableConversionRuns() {
        var calls = new AtomicInteger();
        var integer = new BigInteger("123456789012345678901234567890") {
            private static final long serialVersionUID = 1L;
            @Override public String toString() { calls.incrementAndGet(); return "changed"; }
        };
        var decimal = new BigDecimal("1.25") {
            private static final long serialVersionUID = 1L;
            @Override public String toString() { calls.incrementAndGet(); return "changed"; }
        };
        var template = textTemplate("${n}/${n}", DocumentTemplate.Limits.DEFAULT);
        for (var value : List.of(integer, decimal)) {
            var failure = assertThrows(DocumentTemplate.BindingException.class, () -> template.render(Map.of("n", value)));
            assertEquals(DocumentTemplate.BindingException.Code.TYPE_MISMATCH, failure.code());
        }
        // JDK constructors normalize a supplied BigInteger subclass to an ordinary immutable value.
        assertEquals("123456789012345678901234567890/123456789012345678901234567890",
                template.render(Map.of("n", new BigDecimal(integer))).paragraphs().getFirst().text());
        assertEquals(0, calls.get());
    }

    @Test void acceptsExactNumbersAtTheirCanonicalTextBudgetIncludingExtremeDecimalScales() {
        for (var value : List.of(BigInteger.ZERO, new BigInteger("8"), new BigInteger("999"), new BigInteger("-999"),
                new BigInteger("123456789012345678901234567890"), new BigDecimal("1.25"), new BigDecimal("-0.000001"),
                new BigDecimal("0.0000001"), new BigDecimal("1.0E+2"), new BigDecimal(BigInteger.ONE, Integer.MAX_VALUE),
                new BigDecimal(BigInteger.ZERO, Integer.MIN_VALUE))) {
            String expected = value.toString();
            var limits = new DocumentTemplate.Limits(100, 1, 1, expected.length() + 1, 8);
            var template = textTemplate("${n}", limits);
            assertEquals(expected, template.render(Map.of("n", value)).paragraphs().getFirst().text());
        }
    }

    @Test void rejectsHugeNumbersAndLiteralTextBeforeBuildingAnOversizedOutput() {
        var limits = new DocumentTemplate.Limits(100, 1, 1, 16, 8);
        var numeric = textTemplate("${n}", limits);
        for (var value : List.of(BigInteger.ONE.shiftLeft(2_000_000), new BigDecimal(BigInteger.ONE.shiftLeft(2_000_000)))) {
            assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                    assertThrows(DocumentTemplate.BindingException.class, () -> numeric.render(Map.of("n", value))).code());
        }
        var literal = textTemplate("X".repeat(1_000_000), limits);
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentTemplate.BindingException.class, () -> literal.render(Map.of())).code());
    }

    @Test void accountsForMapKeysDataAndGeneratedTextAndKeepsSurrogatePairsAcrossScanChunks() {
        var exact = textTemplate("${customer}", new DocumentTemplate.Limits(100, 1, 1, 10, 8));
        assertEquals("A", exact.render(Map.of("customer", "A")).paragraphs().getFirst().text());
        var shortBudget = textTemplate("${customer}", new DocumentTemplate.Limits(100, 1, 1, 9, 8));
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentTemplate.BindingException.class, () -> shortBudget.render(Map.of("customer", "A"))).code());
        String pairAtBoundary = "X".repeat(1023) + "😀";
        assertEquals(pairAtBoundary, textTemplate(pairAtBoundary, new DocumentTemplate.Limits(100, 1, 1, 1024, 8))
                .render(Map.of()).paragraphs().getFirst().text());
    }

    @Test void observesCancellationDuringLongLiteralScanningBeforeReturningADocument() {
        var checkpoints = new AtomicInteger();
        var returned = new AtomicBoolean();
        var options = ExecutionOptions.builder().cancelled(() -> checkpoints.incrementAndGet() >= 20).build();
        var template = textTemplate("X".repeat(100_000), DocumentTemplate.Limits.DEFAULT);
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options, () -> {
            var document = template.render(Map.of());
            returned.set(true);
            return document;
        }));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        assertFalse(returned.get());
        ExecutionContext.checkpoint();
        assertEquals(100_000, template.render(Map.of()).paragraphs().getFirst().text().length());
    }

    @Test void emptyNestedLoopsStillConsumeBoundedIterationWork() {
        var template = DocumentTemplate.builder().limits(new DocumentTemplate.Limits(16, 1, 1, 100, 8))
                .each("outer", "o", nodes -> nodes.each("inner", "i", children -> { })).build();
        var items = List.of(true, true, true, true, true);
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentTemplate.BindingException.class, () -> template.render(Map.of("outer", items, "inner", items))).code());
    }

    @Test void lexicalScopeShadowsAndRestoresParentBindingsWithoutCopyingTheirData() {
        var template = DocumentTemplate.builder()
                .each("items", "item", nodes -> nodes.block(new Paragraph("outer", "${item.name}", FORMAT))
                        .each("item.children", "item", nested -> nested.block(new Paragraph("inner", "${item.name}", FORMAT)))
                        .block(new Paragraph("restored", "${item.name}", FORMAT)))
                .block(new Paragraph("root", "${item}", FORMAT)).build();
        var data = Map.of("item", "root", "items", List.of(Map.of("name", "outer", "children", List.of(Map.of("name", "inner")))));
        assertEquals(List.of("outer", "inner", "outer", "root"), template.render(data).paragraphs().stream().map(Paragraph::text).toList());
    }

    @Test void validatesVeryDeepDotPathsWithoutRegexRecursionAndRedactsInvalidSourceExpressions() {
        String path = "a.".repeat(20_000) + "a";
        var deep = DocumentTemplate.builder().when(path, yes -> { }, no -> { }).build();
        var failure = assertThrows(DocumentTemplate.BindingException.class, () -> deep.render(Map.of("a", 1)));
        assertEquals(DocumentTemplate.BindingException.Code.TYPE_MISMATCH, failure.code());
        assertEquals("expression", failure.path());
        String secret = "doSomething('private-document-text')";
        var invalid = textTemplate("${" + secret + "}", DocumentTemplate.Limits.DEFAULT);
        var invalidFailure = assertThrows(DocumentTemplate.BindingException.class, () -> invalid.render(Map.of()));
        assertEquals(DocumentTemplate.BindingException.Code.INVALID_EXPRESSION, invalidFailure.code());
        assertEquals("expression", invalidFailure.path());
        assertFalse(invalidFailure.getMessage().contains(secret));
        assertTrue(failure.getMessage().length() < 128);
    }

    private static DocumentTemplate textTemplate(String text, DocumentTemplate.Limits limits) {
        return DocumentTemplate.builder().limits(limits).block(new Paragraph("text", text, FORMAT)).build();
    }
}
