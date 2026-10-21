// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FluentModelTest {
    @Test
    void fontSizeDerivesLineHeightUntilExplicitlyConfiguredAndCopiesDoNotMutateOriginal() {
        var defaults = ParagraphFormat.builder().fontSize(20).build();
        assertEquals(30, defaults.lineHeight());
        assertEquals(22, ParagraphFormat.builder().lineHeight(22).fontSize(10).build().lineHeight());
        var changed = defaults.toBuilder().fontSize(10).spaceAfter(12).build();
        assertEquals(30, changed.lineHeight());
        assertEquals(6, defaults.spaceAfter());
        assertEquals(12, changed.spaceAfter());
        assertEquals(18, CellFormat.builder().fontSize(12).build().lineHeight());
        assertThrows(IllegalArgumentException.class, () -> ParagraphFormat.builder().fontSize(Double.NaN).build());
        assertThrows(IllegalArgumentException.class, () -> PageSettings.builder().margins(1000).build());
    }

    @Test
    void semanticTablesInferColumnsSnapshotCellsAndPreserveMixedSources() {
        var inlines = new ArrayList<InlineContent>();
        inlines.add(new InlineText("A", InlineStyle.of(12)));
        inlines.add(new InlineField(InlineField.Kind.PAGE, "", 30, InlineStyle.of(12)));
        var cellBuilder = TableCell.builder().content(inlines);
        inlines.clear();
        var mixed = cellBuilder.build();
        assertEquals("A\uFFFC", mixed.text());
        assertEquals(1, mixed.fields().size());
        var builder = Table.builder("t").header("Name", "Value").row(new TableCell("A", CellFormat.of(12)), mixed)
                .format(value -> value.padding(3).allowRowSplit(true));
        var first = builder.build();
        builder.row("B", "2");
        assertEquals(2, first.rows().size());
        assertEquals(2, first.columns().size());
        assertEquals(1, first.headerRows());
        assertEquals(PageMargins.uniform(3), first.format().padding());
        assertThrows(IllegalStateException.class, () -> builder.header("late", "header"));
        assertThrows(IllegalArgumentException.class, () -> Table.builder("t").header("A", "B").row("bad").build());
    }

    @Test
    void automaticCustomBlocksAndTablesShareIdNamespaceAndSectionsSnapshot() {
        var builder = Document.builder().paragraph("auto-1", "explicit")
                .block(id -> new Paragraph(id, "custom", ParagraphFormat.of(12)))
                .table(table -> table.header("A").row("B"));
        assertEquals(List.of("auto-1", "auto-2", "auto-3"), builder.build().blocks().stream().map(Block::id).toList());
        assertThrows(IllegalArgumentException.class, () -> builder.block(id -> new Paragraph("other", "", ParagraphFormat.of(12))));
        var section = Section.builder("one").blocks(builder.build().blocks()).firstPageNumber(5).build();
        var sections = SectionedDocument.builder().section(section);
        var first = sections.build();
        sections.section("two", value -> value.add(new Paragraph("unique", "", ParagraphFormat.of(12))));
        assertEquals(1, first.sections().size());
        assertEquals(2, sections.build().sections().size());
        assertThrows(IllegalArgumentException.class, () -> sections.section(section).build());
    }

    @Test
    void nestedTemplateBranchesSnapshotAndRetainStrictBinding() {
        var held = new AtomicReference<DocumentTemplate.Nodes>();
        var template = DocumentTemplate.builder()
                .when("show", yes -> {
                    held.set(yes);
                    yes.each("names", "name", each -> each.block(new Paragraph("name", "${name}", ParagraphFormat.of(12))));
                }, no -> no.block(new Paragraph("empty", "Empty", ParagraphFormat.of(12))))
                .build();
        held.get().block(new Paragraph("late", "Must not appear", ParagraphFormat.of(12)));
        var document = template.render(Map.of("show", true, "names", List.of("A", "B")));
        assertEquals(List.of("A", "B"), document.paragraphs().stream().map(Paragraph::text).toList());
        assertEquals("Empty", template.render(Map.of("show", false)).paragraphs().getFirst().text());
        assertThrows(DocumentTemplate.BindingException.class, () -> template.render(Map.of()));
    }
}
