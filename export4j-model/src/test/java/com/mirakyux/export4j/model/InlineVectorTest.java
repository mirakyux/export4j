// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mirakyux.export4j.model.edit.DocumentEditor;
import com.mirakyux.export4j.model.edit.EditLimits;
import com.mirakyux.export4j.model.processing.ProcessingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InlineVectorTest {
    private static final ParagraphFormat FORMAT = ParagraphFormat.of(12);
    private static final InlineStyle STYLE = InlineStyle.of(12);

    private static VectorResource.Segment segment(VectorResource.Operation operation, Double... coordinates) {
        return new VectorResource.Segment(operation, List.of(coordinates));
    }
    private static VectorResource resource() {
        return VectorResource.of(10, 8, 2, List.of(
                segment(VectorResource.Operation.MOVE, 0.0, -8.0),
                segment(VectorResource.Operation.LINE, 10.0, 2.0),
                segment(VectorResource.Operation.CLOSE)));
    }
    private static Document document(Block... blocks) {
        return new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(blocks));
    }

    @Test void resourcesSnapshotPathsAndCanonicalizeSignedZeroWithoutLosingGeometryIdentity() {
        var coordinates = new ArrayList<>(List.of(-0.0, -0.0));
        var segments = new ArrayList<>(List.of(new VectorResource.Segment(VectorResource.Operation.MOVE, coordinates)));
        var value = VectorResource.of(10, -0.0, 2, segments);
        coordinates.set(0, 8.0); segments.clear();
        var equivalent = VectorResource.of(10, 0, 2, List.of(segment(VectorResource.Operation.MOVE, 0.0, 0.0)));
        assertEquals(equivalent, value); assertEquals(equivalent.id(), value.id());
        assertEquals(49, value.byteSize()); assertEquals(1, value.segmentCount());
        assertEquals(List.of(0.0, 0.0), value.segments().getFirst().coordinates());
        assertThrows(UnsupportedOperationException.class, () -> value.segments().clear());
        assertThrows(UnsupportedOperationException.class, () -> value.segments().getFirst().coordinates().set(0, 2.0));
        assertNotEquals(value, VectorResource.of(11, 0, 2, value.segments()));
    }

    @Test void invalidGeometryPathOrderAndExcessiveWorkFailAtTheModelBoundary() {
        assertThrows(IllegalArgumentException.class, () -> VectorResource.of(0, 8, 2, resource().segments()));
        assertThrows(IllegalArgumentException.class, () -> VectorResource.of(10, 0, 0, resource().segments()));
        assertThrows(IllegalArgumentException.class, () -> VectorResource.of(10, 14_400, 1, resource().segments()));
        assertThrows(IllegalArgumentException.class, () -> VectorResource.of(10, 8, 2, List.of()));
        assertThrows(IllegalArgumentException.class, () -> segment(VectorResource.Operation.LINE, Double.NaN, 0.0));
        assertThrows(IllegalArgumentException.class, () -> segment(VectorResource.Operation.CURVE, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> VectorResource.of(10, 8, 2,
                List.of(segment(VectorResource.Operation.LINE, 1.0, 1.0))));
        assertThrows(IllegalArgumentException.class, () -> VectorResource.of(10, 8, 2,
                List.of(segment(VectorResource.Operation.MOVE, 11.0, 0.0))));
        assertThrows(IllegalArgumentException.class, () -> VectorResource.of(10, 8, 2,
                List.of(segment(VectorResource.Operation.MOVE, 0.0, 0.0), segment(VectorResource.Operation.CLOSE),
                        segment(VectorResource.Operation.LINE, 1.0, 1.0))));
        assertThrows(IllegalArgumentException.class, () -> VectorResource.of(10, 8, 2,
                Collections.nCopies(10_001, segment(VectorResource.Operation.MOVE, 0.0, 0.0))));
    }

    @Test void readingTextAndLinkRejectInvalidValuesAndOwnOneUtf16SourcePosition() {
        for (String invalid : List.of("x\n", "\ud800", "\uffff", "x\u2028y", "x\u2029y", "x".repeat(1025))) {
            assertThrows(IllegalArgumentException.class, () -> new InlineVector(resource(), invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> new InlineVector(resource(), "x", -1, ""));
        assertThrows(IllegalArgumentException.class, () -> new InlineVector(resource(), "x", 0, "javascript:alert(1)"));
        var vector = new InlineVector(resource(), "x² + y²", 0x123456, "https://example.com/math");
        var field = new InlineField(InlineField.Kind.PAGE, "", 20, STYLE);
        var before = new InlineText("A😀", STYLE);
        var paragraph = Paragraph.mixed("p", List.of(before, vector, field), FORMAT);
        assertEquals("A😀\ufffc\ufffc", paragraph.text());
        assertEquals(3, paragraph.vectors().getFirst().sourceIndex());
        assertEquals(4, paragraph.fields().getFirst().sourceIndex());
        assertEquals(List.of(before, vector, field), paragraph.content());
        assertThrows(IllegalArgumentException.class, () -> new Paragraph("p", "\ufffc", FORMAT,
                List.of(), List.of(), List.of(), List.of(new Paragraph.VectorSpan(0, vector))));
        assertThrows(IllegalArgumentException.class, () -> new Paragraph("p", "\ufffc", FORMAT,
                List.of(new InlineText("\ufffc", STYLE)), List.of(), List.of(new Paragraph.FieldSpan(0, field)),
                List.of(new Paragraph.VectorSpan(0, vector))));
    }

    @Test void cellsBuildersAndCompactionRetainVectorsWithoutHidingBlockDescendants() {
        var vector = new InlineVector(resource(), "x+y");
        var cell = TableCell.mixed(List.of(vector), CellFormat.of(12));
        var copy = cell.toBuilder().columnSpan(2).rowSpan(3).build();
        assertEquals(cell.vectors(), copy.vectors()); assertEquals(2, copy.columnSpan()); assertEquals(3, copy.rowSpan());
        assertEquals(List.of(), copy.toBuilder().text("text").build().vectors());
        var paragraph = Paragraph.mixed("inside", List.of(vector), FORMAT);
        var blockCell = copy.toBuilder().blocks(List.of(paragraph)).build();
        assertThrows(IllegalStateException.class, blockCell::vectors);
        assertEquals(List.of(paragraph), blockCell.blocks());
        var table = new Table("t", List.of(new ColumnWidth.Fraction(1)),
                List.of(new TableRow(List.of(cell))), 0, TableFormat.DEFAULT);
        var independentlyCreatedStyle = InlineStyle.of(12);
        var withText = Paragraph.mixed("p", List.of(new InlineText("text", independentlyCreatedStyle), vector), FORMAT);
        var compacted = Document.builder().add(new Paragraph("seed", List.of(new InlineText("seed", STYLE)), FORMAT))
                .add(withText).add(table).build();
        var actual = (Paragraph) compacted.blocks().get(1);
        assertSame(STYLE, actual.inlines().getFirst().style());
        assertEquals(withText.content(), actual.content());
        assertEquals(withText.vectors(), actual.vectors());
        assertEquals(cell.vectors(), ((Table) compacted.blocks().getLast()).rows().getFirst().cells().getFirst().vectors());
    }

    @Test void bindingRepositionsVectorsAndPreservesLiteralReadingTextWithTheSameGeometry() {
        var vector = new InlineVector(resource(), "${formula}");
        var content = List.of(new InlineText("${name}", STYLE), vector);
        var source = Paragraph.mixed("p", content, FORMAT);
        var table = new Table("t", List.of(new ColumnWidth.Fraction(1)),
                List.of(new TableRow(List.of(TableCell.mixed(content, CellFormat.of(12))))), 0, TableFormat.DEFAULT);
        var result = DocumentTemplate.builder().block(source).block(table).build().render(Map.of("name", "A😀"));
        var paragraph = (Paragraph) result.blocks().getFirst();
        assertEquals("A😀\ufffc", paragraph.text()); assertEquals(3, paragraph.vectors().getFirst().sourceIndex());
        assertEquals("${formula}", paragraph.vectors().getFirst().vector().logicalText());
        assertSame(vector.resource(), paragraph.vectors().getFirst().vector().resource());
        assertEquals(paragraph.vectors(), ((Table) result.blocks().getLast()).rows().getFirst().cells().getFirst().vectors());
        var bounded = new DocumentTemplate(DocumentMetadata.EMPTY, PageSettings.A4,
                List.of(new DocumentTemplate.ValueBlock(Paragraph.mixed("budget", List.of(vector), FORMAT))),
                new DocumentTemplate.Limits(100, 10, 10, 5, 10));
        assertThrows(DocumentTemplate.BindingException.class, () -> bounded.render(Map.of()));
    }

    @Test void editingTextAndReplacingWholeVectorsPreserveAtomicResourcesAndReadingText() throws Exception {
        var vector = new InlineVector(resource(), "old expression");
        var source = Paragraph.mixed("p", List.of(new InlineText("before", STYLE), vector, new InlineText("after", STYLE)), FORMAT);
        var edited = DocumentEditor.edit(document(source)).replaceText("before", "A😀").execute().document();
        var paragraph = (Paragraph) edited.blocks().getFirst();
        assertEquals(3, paragraph.vectors().getFirst().sourceIndex());
        assertSame(vector.resource(), paragraph.vectors().getFirst().vector().resource());
        assertThrows(ProcessingException.class, () -> DocumentEditor.edit(edited).replaceText("\ufffc", "lost").execute());
        var replacement = new InlineVector(resource(), "replacement", 0x124578, "");
        var replaced = DocumentEditor.edit(edited).vector("p", 3, replacement).execute().document();
        assertEquals(replacement, ((Paragraph) replaced.blocks().getFirst()).vectors().getFirst().vector());
        var resized = DocumentEditor.edit(replaced).paragraphFormat("p", ParagraphFormat.of(14)).execute().document();
        assertEquals(replacement, ((Paragraph) resized.blocks().getFirst()).vectors().getFirst().vector());
        var vectorOnly = document(Paragraph.mixed("budget", List.of(vector), FORMAT));
        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.edit(vectorOnly)
                .limits(new EditLimits(10, 100, 1, 100, 100, 100_000)).prepare());
        var work = new EditLimits(10, 100, 100, 100, 100, 60);
        DocumentEditor.edit(document(new Paragraph("plain", "x", FORMAT))).limits(work).prepare();
        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.edit(vectorOnly).limits(work).prepare());
    }

    @Test void regionsRetainVectorsAndBoundRepeatedInkAndLinks() {
        var vector = new InlineVector(resource(), "x");
        var paragraph = Paragraph.mixed("p", List.of(vector), FORMAT);
        assertEquals(List.of(vector), new PageRegion(20, 0, List.of(paragraph)).paragraphs().getFirst().content());
        var linked = new InlineVector(resource(), "x", 0, "https://example.com");
        assertThrows(IllegalArgumentException.class, () -> new PageRegion(20, 0,
                List.of(Paragraph.mixed("linked", List.of(linked), FORMAT))));
        var heavy = VectorResource.of(10, 8, 2, Collections.nCopies(10_000, segment(VectorResource.Operation.MOVE, 0.0, 0.0)));
        var repeated = Paragraph.mixed("heavy", Collections.nCopies(101, new InlineVector(heavy, "x")), FORMAT);
        assertThrows(IllegalArgumentException.class, () -> new PageRegion(20, 0, List.of(repeated)));
    }
}
