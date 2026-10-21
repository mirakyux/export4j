// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InlineFieldTest {
    @Test
    void fieldsRoundtripAndTemplatesRecalculateOriginalMarkerPositions() {
        var field = new InlineField(InlineField.Kind.REFERENCE_PAGE, "${target}", 24, InlineStyle.of(12));
        var paragraph = Paragraph.mixed("p", List.of(new InlineText("${name}😀", InlineStyle.of(12)), field,
                new InlineText("tail", InlineStyle.of(12))), ParagraphFormat.of(12));
        assertEquals(9, paragraph.fields().getFirst().sourceIndex());
        assertEquals(field, paragraph.content().get(1));
        var template = new DocumentTemplate(DocumentMetadata.EMPTY, PageSettings.A4,
                List.of(new DocumentTemplate.ValueBlock(paragraph)), DocumentTemplate.Limits.DEFAULT);
        var bound = (Paragraph) template.render(Map.of("name", "Customer", "target", "heading")).blocks().getFirst();
        assertEquals(10, bound.fields().getFirst().sourceIndex());
        assertEquals("heading", bound.fields().getFirst().field().targetId());
        assertEquals("Customer😀\ufffctail", bound.text());
    }

    @Test
    void invalidSlotsAndUnsupportedTableNoteMarkersFailInsteadOfLosingSemantics() {
        var field = new InlineField(InlineField.Kind.PAGE, "", 24, InlineStyle.of(12));
        assertThrows(IllegalArgumentException.class, () -> new InlineField(InlineField.Kind.REFERENCE_PAGE, "", 24, InlineStyle.of(12)));
        assertThrows(IllegalArgumentException.class, () -> new InlineField(InlineField.Kind.PAGE, "target", 24, InlineStyle.of(12)));
        assertThrows(IllegalArgumentException.class, () -> new InlineField(InlineField.Kind.PAGE, "", Double.NaN, InlineStyle.of(12)));
        assertEquals(field, TableCell.mixed(List.of(field), CellFormat.of(12)).fields().getFirst().field());
        assertThrows(IllegalArgumentException.class, () -> TableCell.mixed(List.of(
                new InlineField(InlineField.Kind.NOTE_MARKER, "note", 24, InlineStyle.of(12))), CellFormat.of(12)));
        assertThrows(IllegalArgumentException.class, () -> new Paragraph("p", "x", ParagraphFormat.of(12),
                List.of(new InlineText("x", InlineStyle.of(12))), List.of(), List.of(new Paragraph.FieldSpan(0, field))));
        assertThrows(IllegalArgumentException.class, () -> new Paragraph("p", "\ufffc", ParagraphFormat.of(12),
                List.of(new InlineText("\ufffc", InlineStyle.of(14))), List.of(), List.of(new Paragraph.FieldSpan(0, field))));
    }

    @Test
    void tableTemplatesPreserveFieldsAndRebindTargetsAfterTextExpansion() {
        var cell = TableCell.mixed(List.of(new InlineText("${name}😀", InlineStyle.of(12)),
                new InlineField(InlineField.Kind.REFERENCE_PAGE, "${target}", 24, InlineStyle.of(12))), CellFormat.of(12));
        var table = new Table("t", List.of(new ColumnWidth.Fraction(1)), List.of(new TableRow(List.of(cell))), 0, TableFormat.DEFAULT);
        var template = new DocumentTemplate(DocumentMetadata.EMPTY, PageSettings.A4,
                List.of(new DocumentTemplate.ValueBlock(table)), DocumentTemplate.Limits.DEFAULT);
        var bound = ((Table) template.render(Map.of("name", "Customer", "target", "heading")).blocks().getFirst()).rows().getFirst().cells().getFirst();
        assertEquals("Customer😀\ufffc", bound.text());
        assertEquals(10, bound.fields().getFirst().sourceIndex());
        assertEquals("heading", bound.fields().getFirst().field().targetId());
    }
}
