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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class BlockCellTemplateTest {
    private static final ParagraphFormat FORMAT = ParagraphFormat.of(12);
    private static final InlineStyle STYLE = new InlineStyle("bold", 12, 0x224488, 0, true, false);

    @Test void staticJavaBindingPreservesTheDeepTreeFormatsImagesSpansAndLeafUtf16() throws IOException {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        var resource = ImageResource.load(bytes.toByteArray());
        var before = Paragraph.mixed("before", List.of(new InlineText("${name}", STYLE, "https://example.com/text"),
                new InlineImage(resource, 12, 14, 0, "Logo ${name}", "https://example.com/image")), FORMAT);
        var nested = reference("nested", "${name}", "after");
        var inner = Table.builder("inner").row(TableCell.builder().paragraph(nested).columnSpan(2).rowSpan(2).build())
                .coveredRow().build();
        var sourceCell = TableCell.builder().blocks(List.of(before, inner, new Paragraph("after", "${tail}",
                FORMAT.toBuilder().fontSize(10).lineHeight(17).spaceBefore(3).spaceAfter(7).build())))
                .columnSpan(2).rowSpan(2).build();
        var source = Table.builder("outer").row(sourceCell, new TableCell("Side", CellFormat.of(12)))
                .row(new TableCell("Tail", CellFormat.of(12))).build();
        var result = (Table) DocumentTemplate.builder().block(source).build().render(Map.of("name", "A😀", "tail", "Last"))
                .blocks().getFirst();
        var actual = result.rows().getFirst().cells().getFirst();
        assertTrue(actual.hasBlocks());
        assertEquals(2, actual.columnSpan());
        assertEquals(2, actual.rowSpan());
        assertSame(actual, result.grid().ownerAt(1, 1).cell());
        var actualBefore = (Paragraph) actual.blocks().getFirst();
        assertEquals("A😀\ufffc", actualBefore.text());
        assertEquals(STYLE, actualBefore.inlines().getFirst().style());
        assertEquals("https://example.com/text", actualBefore.inlines().getFirst().hyperlink());
        assertEquals(3, actualBefore.images().getFirst().sourceIndex());
        assertSame(resource, actualBefore.images().getFirst().image().resource());
        assertEquals("Logo A😀", actualBefore.images().getFirst().image().altText());
        var actualInner = (Table) actual.blocks().get(1);
        var leaf = (Paragraph) actualInner.rows().getFirst().cells().getFirst().blocks().getFirst();
        assertEquals("A😀\ufffc", leaf.text());
        assertEquals(3, leaf.fields().getFirst().sourceIndex());
        assertEquals("after", leaf.fields().getFirst().field().targetId());
        assertEquals(2, actualInner.rows().getFirst().cells().getFirst().rowSpan());
        assertEquals(17, ((Paragraph) actual.blocks().getLast()).format().lineHeight());
        assertEquals("${name}\ufffc", before.text());
    }

    @Test void eachCopiesEveryDescendantAndRelocatesOnlyStaticTargetsInItsLexicalCopy() {
        var dynamic = Paragraph.mixed("dynamic", List.of(new InlineField(InlineField.Kind.REFERENCE_PAGE,
                "${item.target}", 24, STYLE)), FORMAT);
        var source = table("outer", TableCell.builder().blocks(List.of(reference("before", "${item.name}", "after"),
                table("inner", TableCell.builder().paragraph(reference("nested", "Nested", "destination")).build()),
                new Paragraph("after", "After", FORMAT), dynamic)).build());
        var template = DocumentTemplate.builder().each("items", "item", nodes -> nodes.block(source)
                .block(reference("sibling", "Sibling", "nested")))
                .block(new Paragraph("destination", "External", FORMAT)).build();
        var result = template.render(Map.of("items", List.of(Map.of("name", "A", "target", "after"),
                Map.of("name", "B", "target", "after"))));
        for (int index = 0; index < 2; index++) {
            var outer = (Table) result.blocks().get(index*2);
            var children = outer.rows().getFirst().cells().getFirst().blocks();
            assertEquals(List.of("before@" + index, "inner@" + index, "after@" + index, "dynamic@" + index),
                    children.stream().map(Block::id).toList());
            assertEquals("after@" + index, ((Paragraph) children.getFirst()).fields().getFirst().field().targetId());
            assertEquals("after", ((Paragraph) children.getLast()).fields().getFirst().field().targetId());
            var inner = (Table) children.get(1);
            assertEquals("destination", ((Paragraph) inner.rows().getFirst().cells().getFirst().blocks().getFirst())
                    .fields().getFirst().field().targetId());
            assertEquals("nested@" + index, ((Paragraph) result.blocks().get(index*2+1)).fields().getFirst().field().targetId());
        }
        assertEquals(ids(result).size(), ids(result).stream().distinct().count());
        assertEquals(result, template.render(Map.of("items", List.of(Map.of("name", "A", "target", "after"),
                Map.of("name", "B", "target", "after")))));
    }

    @Test void dynamicRowsNamespaceDescendantsAcrossRowsAndDifferentRowTemplates() {
        var row = new TableRow(List.of(TableCell.builder().blocks(List.of(reference("before", "${item.name}", "after"),
                table("inner", TableCell.builder().paragraph(reference("nested", "Nested", "before")).build()),
                new Paragraph("after", "After", FORMAT))).columnSpan(2).build(), new TableCell("${item.name}", CellFormat.of(12))));
        var columns = List.<ColumnWidth>of(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1));
        var first = new DocumentTemplate.Rows("first", "items", "item", columns, List.of(), row, TableFormat.DEFAULT);
        var second = new DocumentTemplate.Rows("second", "items", "item", columns, List.of(), row, TableFormat.DEFAULT);
        var result = DocumentTemplate.builder().node(first).node(second).build()
                .render(Map.of("items", List.of(Map.of("name", "A"), Map.of("name", "B"))));
        for (var block : result.blocks()) {
            var table = (Table) block;
            for (int index = 0; index < 2; index++) {
                String suffix = "@row:" + table.id() + ":" + index;
                var children = table.rows().get(index).cells().getFirst().blocks();
                assertEquals(List.of("before" + suffix, "inner" + suffix, "after" + suffix), children.stream().map(Block::id).toList());
                assertEquals("after" + suffix, ((Paragraph) children.getFirst()).fields().getFirst().field().targetId());
                var inner = (Table) children.get(1);
                assertEquals("before" + suffix, ((Paragraph) inner.rows().getFirst().cells().getFirst().blocks().getFirst())
                        .fields().getFirst().field().targetId());
            }
        }
        assertEquals(ids(result).size(), ids(result).stream().distinct().count());
    }

    @Test void nestedIterationsKeepOuterStaticTargetsAndCurrentInnerTargetsSeparate() {
        var template = DocumentTemplate.builder().each("items", "item", outer -> outer
                .block(new Paragraph("outer", "Outer", FORMAT))
                .each("item.children", "child", inner -> inner.block(reference("inner", "Inner", "outer"))
                        .block(reference("self", "Self", "inner")))).build();
        var result = template.render(Map.of("items", List.of(Map.of("children", List.of(true, true)))));
        assertEquals(List.of("outer@0", "inner@0@0", "self@0@0", "inner@0@1", "self@0@1"), ids(result));
        assertEquals("outer@0", ((Paragraph) result.blocks().get(1)).fields().getFirst().field().targetId());
        assertEquals("inner@0@1", ((Paragraph) result.blocks().getLast()).fields().getFirst().field().targetId());
    }

    @Test void aStaticTargetInAnUnselectedBranchRemainsAnUnresolvedFinalTarget() {
        var template = DocumentTemplate.builder().each("items", "item", nodes -> nodes
                .block(reference("reference", "Reference", "absent"))
                .when("visible", yes -> yes.block(new Paragraph("absent", "Absent", FORMAT)), no -> { })).build();
        var result = template.render(Map.of("items", List.of(true), "visible", false));
        assertEquals(List.of("reference@0"), ids(result));
        assertEquals("absent@0", ((Paragraph) result.blocks().getFirst()).fields().getFirst().field().targetId());
        assertFalse(ids(result).contains("absent@0"));
    }

    @Test void descendantBlocksRowsAndDepthConsumeTheSameGlobalTemplateBudgets() {
        var source = table("outer", TableCell.builder().paragraph("before", "Before")
                .table("inner", inner -> inner.row(TableCell.builder().paragraph("nested", "Nested").build()))
                .paragraph("after", "After").build());
        for (var limits : List.of(new DocumentTemplate.Limits(1000, 4, 10, 1000, 16),
                new DocumentTemplate.Limits(1000, 10, 1, 1000, 16),
                new DocumentTemplate.Limits(1000, 10, 10, 1000, 3),
                new DocumentTemplate.Limits(10, 10, 10, 1000, 16),
                new DocumentTemplate.Limits(1000, 10, 10, 3, 16))) {
            var template = DocumentTemplate.builder().limits(limits).block(source).build();
            var failure = assertThrows(DocumentTemplate.BindingException.class, () -> template.render(Map.of()));
            assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED, failure.code());
        }
        assertEquals(List.of("outer", "before", "inner", "nested", "after"), ids(DocumentTemplate.builder().block(source).build().render(Map.of())));
    }

    @Test void descendantBindingCancellationDoesNotLeakIntoTheNextRender() {
        var children = java.util.stream.IntStream.range(0, 80)
                .<Block>mapToObj(index -> new Paragraph("leaf-" + index, "${name}", FORMAT)).toList();
        var template = DocumentTemplate.builder().block(table("outer", TableCell.builder().blocks(children).build())).build();
        var checks = new AtomicInteger();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(
                ExecutionOptions.builder().cancelled(() -> checks.incrementAndGet() >= 80).build(), () -> template.render(Map.of("name", "Bound"))));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        ExecutionContext.checkpoint();
        assertEquals(81, ids(template.render(Map.of("name", "Bound"))).size());
    }

    @Test void relocatedFieldTargetsChargeTheGeneratedSuffixWithoutDoubleChargingExternalTargets() {
        var self = DocumentTemplate.builder().each("items", "item", nodes -> nodes.block(reference("p", "X", "p"))).build();
        var data = Map.of("items", List.of(true));
        assertEquals("p@0", self.toBuilder().limits(new DocumentTemplate.Limits(100, 10, 10, 9, 16)).build()
                .render(data).paragraphs().getFirst().fields().getFirst().field().targetId());
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentTemplate.BindingException.class,
                        () -> self.toBuilder().limits(new DocumentTemplate.Limits(100, 10, 10, 8, 16)).build().render(data)).code());
        var external = DocumentTemplate.builder().each("items", "item", nodes -> nodes.block(reference("p", "X", "q")))
                .block(new Paragraph("q", "", FORMAT)).limits(new DocumentTemplate.Limits(100, 10, 10, 7, 16)).build();
        assertEquals("q", external.render(data).paragraphs().getFirst().fields().getFirst().field().targetId());
    }

    private static Paragraph reference(String id, String text, String target) {
        return Paragraph.mixed(id, List.of(new InlineText(text, STYLE),
                new InlineField(InlineField.Kind.REFERENCE_PAGE, target, 24, STYLE)), FORMAT);
    }

    private static Table table(String id, TableCell cell) { return Table.builder(id).row(cell).build(); }

    private static List<String> ids(Document document) {
        var result = new ArrayList<String>();
        SemanticTree.walk(document.blocks(), visit -> result.add(visit.block().id()));
        return result;
    }
}
