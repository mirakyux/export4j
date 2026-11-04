// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentTemplateGridTest {
    private static final ParagraphFormat FORMAT = ParagraphFormat.of(12);
    private static DocumentTemplate.ValueBlock paragraph(String id, String text) {
        return new DocumentTemplate.ValueBlock(new Paragraph(id, text, FORMAT));
    }

    @Test void nestedLexicalCopiesRetainGridGeometryAndRelocateSiblingTargets() {
        var reference = new DocumentTemplate.ValueBlock(Paragraph.mixed("reference", List.of(
                new InlineText("${line}", InlineStyle.of(12)),
                new InlineField(InlineField.Kind.REFERENCE_PAGE, "target", 24, InlineStyle.of(12))), FORMAT));
        var grid = new DocumentTemplate.Grid("grid", 12, 13, 17, List.of(
                new DocumentTemplate.GridItem("left", 1, 1, 8, List.of(paragraph("target", "${group.name}"))),
                new DocumentTemplate.GridItem("right", 1, 9, 4, List.of(
                        new DocumentTemplate.Each("group.lines", "line", List.of(reference))))));
        var template = DocumentTemplate.builder().each("groups", "group", nodes -> nodes.node(grid)).build();
        var bound = template.render(Map.of("groups", List.of(Map.of("name", "First", "lines", List.of("A", "B")),
                Map.of("name", "Second", "lines", List.of("C")))));
        assertEquals(List.of("grid@0", "grid@1"), bound.blocks().stream().map(Block::id).toList());
        var first = (GridLayout) bound.blocks().getFirst();
        assertEquals(12, first.tracks()); assertEquals(13, first.columnGap()); assertEquals(17, first.rowGap());
        assertEquals(List.of("left@0", "right@0"), first.items().stream().map(Block::id).toList());
        assertEquals(9, first.items().getLast().columnStart()); assertEquals(4, first.items().getLast().columnSpan());
        assertEquals("First", ((Paragraph) first.items().getFirst().blocks().getFirst()).text());
        assertEquals(List.of("reference@0@0", "reference@0@1"), first.items().getLast().blocks().stream().map(Block::id).toList());
        assertEquals("target@0", ((Paragraph) first.items().getLast().blocks().getFirst()).fields().getFirst().field().targetId());
        var second = (GridLayout) bound.blocks().getLast();
        assertEquals("target@1", ((Paragraph) second.items().getLast().blocks().getFirst()).fields().getFirst().field().targetId());
    }

    @Test void dynamicEmptyItemsAndGridDisappearWhileChargingBothWrappers() {
        var item = new DocumentTemplate.GridItem("item", 1, 1, 12, List.of(
                new DocumentTemplate.When("show", List.of(paragraph("inside", "Inside")), List.of())));
        var template = DocumentTemplate.builder().grid("grid", 12, 12, 12, nodes -> nodes.node(item))
                .block(new Paragraph("after", "After", FORMAT)).build();
        assertEquals(List.of("after"), template.render(Map.of("show", false)).blocks().stream().map(Block::id).toList());
        var bounded = template.toBuilder().limits(new DocumentTemplate.Limits(100, 2, 10, 100, 10)).build();
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentTemplate.BindingException.class, () -> bounded.render(Map.of("show", false))).code());
    }

    @Test void javaGridPrototypeBindsItsEntireSemanticTree() {
        var grid = new GridLayout("grid", 6, 3, 9, List.of(new GridItem("item", 2, 2, 4,
                List.of(new Paragraph("target", "${name}", FORMAT), Paragraph.mixed("reference", List.of(
                        new InlineField(InlineField.Kind.REFERENCE_PAGE, "target", 24, InlineStyle.of(12))), FORMAT)))));
        var template = DocumentTemplate.builder().each("copies", "copy", nodes -> nodes.block(grid)).build();
        var bound = (GridLayout) template.render(Map.of("copies", List.of("one"), "name", "Bound😀")).blocks().getFirst();
        assertEquals("grid@0", bound.id()); assertEquals("item@0", bound.items().getFirst().id());
        assertEquals("Bound😀", ((Paragraph) bound.items().getFirst().blocks().getFirst()).text());
        assertEquals("target@0", ((Paragraph) bound.items().getFirst().blocks().getLast()).fields().getFirst().field().targetId());
        assertEquals("${name}", ((Paragraph) grid.items().getFirst().blocks().getFirst()).text());
    }

    @Test void validationCoversInactiveBranchesRootItemsAndExpandedOverlap() {
        var children = new ArrayList<DocumentTemplate.Node>(List.of(paragraph("p", "Text")));
        var item = new DocumentTemplate.GridItem("item", 1, 1, 6, children); children.clear();
        assertEquals(1, item.children().size());
        assertThrows(IllegalArgumentException.class, () -> DocumentTemplate.builder().when("show", nodes -> {}, nodes -> nodes.node(item)).build());
        assertThrows(IllegalArgumentException.class, () -> new DocumentTemplate.Grid("empty", 12, 12, 12, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new DocumentTemplate.GridItem("empty", 1, 1, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new DocumentTemplate.Grid("bad", 4, 12, 12,
                List.of(new DocumentTemplate.When("show", List.of(), List.of(item)))));
        var nestedColumns = new DocumentTemplate.Columns("nested", 2, 18, true, List.of(paragraph("p", "No")));
        var invalidBranch = new DocumentTemplate.When("show", List.of(paragraph("valid", "Valid")), List.of(nestedColumns));
        assertThrows(IllegalArgumentException.class, () -> new DocumentTemplate.GridItem("bad", 1, 1, 1, List.of(invalidBranch)));
        var repeated = DocumentTemplate.builder().grid("grid", 12, 12, 12,
                nodes -> nodes.each("copies", "copy", nested -> nested.node(item))).build();
        assertThrows(IllegalArgumentException.class, () -> repeated.render(Map.of("copies", List.of("a", "b"))));
    }
}
