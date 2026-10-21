// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class AuthoringTest {
    @Test void automaticIdsSkipExplicitNamesAndDefaultsApplyOnlyToSubsequentBlocks() {
        var builder = Document.builder().paragraph("auto-1", "explicit").paragraph("automatic")
                .bodyFormat(ParagraphFormat.of(10)).paragraph("smaller")
                .heading("Heading", 1).bullet("Bullet").numbered("steps", "First")
                .table(java.util.List.of("Name", "Value"), java.util.List.of(java.util.List.of("A", "1"))).pageBreak();
        var document = builder.build();
        assertEquals(java.util.List.of("auto-1", "auto-2", "auto-3", "auto-4", "auto-5", "auto-6", "auto-7", "auto-8"),
                document.blocks().stream().map(Block::id).toList());
        assertEquals(PageSettings.A4, document.pageSettings());
        assertEquals(12, ((Paragraph) document.blocks().get(1)).format().fontSize());
        assertEquals(10, ((Paragraph) document.blocks().get(2)).format().fontSize());
        var table = (Table) document.blocks().get(6);
        assertEquals(1, table.headerRows()); assertEquals(2, table.columns().size());
        assertThrows(IllegalArgumentException.class, () -> Table.text("bad", java.util.List.of("A"), java.util.List.of(java.util.List.of("1", "2"))));
        assertThrows(IllegalArgumentException.class, () -> builder.paragraph("auto-2", "collision").build());
    }

    @Test void richHeadingAndListsSnapshotRunsAndValidateTheirSource() {
        var runs = new java.util.ArrayList<>(java.util.List.of(new InlineText("Styled", new InlineStyle("bold", 12, 0x224488, 0, true, false))));
        var heading = Heading.rich("h", runs, 1, ParagraphFormat.of(12));
        var item = NumberedItem.rich("n", "steps", runs, 0, ParagraphFormat.of(12), 1);
        var bullet = BulletItem.rich("b", runs, "•", 1, ParagraphFormat.of(12));
        runs.clear();
        assertEquals(heading.paragraph().inlines(), item.paragraph().inlines());
        assertEquals(heading.paragraph().inlines(), bullet.paragraph().inlines());
        assertEquals("Styled", heading.text());
        assertThrows(UnsupportedOperationException.class, () -> heading.inlines().clear());
        assertThrows(IllegalArgumentException.class, () -> new Heading("h", "mismatch", 1, heading.format(), heading.inlines()));
    }

    @Test void builderSnapshotsAndValidatesIds() {
        var builder = Document.builder(DocumentMetadata.EMPTY, PageSettings.A4).paragraph("p", "text", ParagraphFormat.of(12));
        var first = builder.build();
        builder.pageBreak("break").heading("h", "Heading", 1, ParagraphFormat.of(18));
        assertEquals(1, first.blocks().size());
        assertEquals(3, builder.build().blocks().size());
        builder.paragraph("p", "duplicate", ParagraphFormat.of(12));
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test void inheritanceResolvesForwardParentsAndRejectsUnknownOrCycles() {
        var sheet = StyleSheet.builder().inline("emphasis", "body", parent -> new InlineStyle("bold", parent.fontSize(), parent.rgb(), 0, false, false))
                .inline("body", InlineStyle.of(12)).paragraph("small", "body", parent -> ParagraphFormat.of(parent.fontSize() - 2))
                .paragraph("body", ParagraphFormat.of(12)).build();
        assertEquals(10, sheet.paragraph("small").fontSize());
        assertEquals("bold", sheet.inline("emphasis").fontRole());
        assertThrows(IllegalArgumentException.class, () -> StyleSheet.builder().inline("a", "b", value -> value).inline("b", "a", value -> value).build());
        assertThrows(IllegalArgumentException.class, () -> StyleSheet.builder().paragraph("a", "unknown", value -> value).build());
    }
}
