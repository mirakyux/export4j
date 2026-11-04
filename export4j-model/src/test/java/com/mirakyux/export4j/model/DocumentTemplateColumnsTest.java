// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentTemplateColumnsTest {
    private static final ParagraphFormat FORMAT = ParagraphFormat.of(12);

    @Test void bindsColumnChildrenAndRelocatesReferencesInNestedLexicalCopies() {
        var reference = Paragraph.mixed("reference", List.of(new InlineText("${line}", InlineStyle.of(12)),
                new InlineField(InlineField.Kind.REFERENCE_PAGE, "target", 24, InlineStyle.of(12))), FORMAT);
        var flow = new DocumentTemplate.Columns("article", 3, 16, false, List.of(1.0, 2.0, 1.0), List.of(
                new DocumentTemplate.ValueBlock(new Paragraph("target", "${group.name}", FORMAT)),
                new DocumentTemplate.Each("group.lines", "line", List.of(new DocumentTemplate.ValueBlock(reference)))));
        var template = DocumentTemplate.builder().node(new DocumentTemplate.Each("groups", "group", List.of(flow))).build();
        var data = Map.of("groups", List.of(Map.of("name", "First", "lines", List.of("A", "B")),
                Map.of("name", "Second", "lines", List.of("C"))));
        var result = template.render(data);
        assertEquals(List.of("article@0", "article@1"), result.blocks().stream().map(Block::id).toList());
        var first = (ColumnFlow) result.blocks().getFirst();
        assertEquals(3, first.columns()); assertEquals(16, first.gap()); assertEquals(false, first.balanced());
        assertEquals(List.of(1.0, 2.0, 1.0), first.columnWeights());
        assertEquals(List.of("target@0", "reference@0@0", "reference@0@1"), first.blocks().stream().map(Block::id).toList());
        assertEquals("First", ((Paragraph) first.blocks().getFirst()).text());
        assertEquals("A\ufffc", ((Paragraph) first.blocks().get(1)).text());
        assertEquals("target@0", ((Paragraph) first.blocks().get(1)).fields().getFirst().field().targetId());
        assertEquals("target@1", ((Paragraph) ((ColumnFlow) result.blocks().getLast()).blocks().get(1)).fields().getFirst().field().targetId());
        assertEquals(result, template.render(data));
    }

    @Test void omitsEmptyBoundColumnsButChargesTheWrapperToTheSharedBlockBudget() {
        var flow = new DocumentTemplate.Columns("article", 2, 18, true, List.of(new DocumentTemplate.When("show",
                List.of(new DocumentTemplate.ValueBlock(new Paragraph("inside", "Inside", FORMAT))), List.of())));
        var template = DocumentTemplate.builder().node(flow).block(new Paragraph("after", "After", FORMAT)).build();
        assertEquals(List.of("after"), template.render(Map.of("show", false)).blocks().stream().map(Block::id).toList());
        var bounded = template.toBuilder().limits(new DocumentTemplate.Limits(100, 1, 10, 100, 10)).build();
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentTemplate.BindingException.class, () -> bounded.render(Map.of("show", false))).code());
    }

    @Test void javaColumnPrototypeBindsDescendantsWithoutLosingGeometryOrFields() {
        var source = new ColumnFlow("article", List.of(new Paragraph("target", "${name}", FORMAT),
                Paragraph.mixed("reference", List.of(new InlineField(InlineField.Kind.REFERENCE_PAGE, "target", 24, InlineStyle.of(12))), FORMAT)),
                2, 21, true, List.of(2.0, 3.0));
        var template = DocumentTemplate.builder().each("items", "item", nodes -> nodes.block(source)).build();
        var bound = (ColumnFlow) template.render(Map.of("name", "Bound😀", "items", List.of("copy"))).blocks().getFirst();
        assertEquals("article@0", bound.id()); assertEquals(source.columnWeights(), bound.columnWeights());
        assertEquals("Bound😀", ((Paragraph) bound.blocks().getFirst()).text());
        assertEquals("target@0", ((Paragraph) bound.blocks().getLast()).fields().getFirst().field().targetId());
        assertEquals("${name}", ((Paragraph) source.blocks().getFirst()).text());
    }

    @Test void snapshotsChildrenAndRejectsUnsupportedContentInEveryBranch() {
        var children = new ArrayList<DocumentTemplate.Node>();
        children.add(new DocumentTemplate.ValueBlock(new Paragraph("p", "Text", FORMAT)));
        var weights = new ArrayList<>(List.of(1.0, 2.0));
        var source = new DocumentTemplate.Columns("article", 2, 18, true, weights, children);
        children.clear(); weights.clear();
        assertEquals(1, source.children().size()); assertEquals(List.of(1.0, 2.0), source.weights());
        assertThrows(IllegalArgumentException.class, () -> new DocumentTemplate.Columns("bad", 2, 18, true,
                List.of(new DocumentTemplate.When("show", source.children(), List.of(new DocumentTemplate.ValueBlock(new PageBreak("break")))))));
        assertThrows(IllegalArgumentException.class, () -> new DocumentTemplate.Columns("nested", 2, 18, true, List.of(source)));
        assertThrows(IllegalArgumentException.class, () -> new DocumentTemplate.Columns("empty", 2, 18, true, List.of()));
    }
}
