// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentThemeAuthoringTest {
    private static Document ordinary(Document.Builder builder) {
        builder.paragraph("Body").bullet("Bullet").numbered("steps", "First");
        for (int level = 1; level <= 6; level++) { builder.heading("Heading " + level, level); }
        return builder.table(List.of("Name", "Value"), List.of(List.of("A", "1")))
                .table(table -> table.header("Name").row("B")).pageBreak().build();
    }

    private static ParagraphFormat body(double size, double height) {
        return ParagraphFormat.builder().fontSize(size).lineHeight(height).spaceAfter(0).build();
    }

    private static CellFormat cell(double size, double height) {
        return CellFormat.builder().fontSize(size).lineHeight(height).build();
    }

    @Test void defaultThemeKeepsEveryExistingConvenienceBlockAndDeterministicId() {
        var plain = ordinary(Document.builder());
        var themed = ordinary(Document.builder().theme(DocumentTheme.builder().build()));
        assertEquals(plain, themed);
        assertEquals(plain.blocks().stream().map(Block::id).toList(), themed.blocks().stream().map(Block::id).toList());
        var theme = DocumentTheme.builder().build();
        assertTrue(theme.paragraphStyles().isEmpty()); assertTrue(theme.inlineStyles().isEmpty());
        for (int level = 1; level <= 6; level++) { assertEquals(Heading.defaultFormat(level), theme.headingFormat(level)); }
        assertEquals(TableFormat.DEFAULT, theme.tableFormat());
    }

    @Test void implicitCellsDeriveTheFinalBodyAndToBuilderPreservesThatIntent() {
        var format = body(11, 22).toBuilder().alignment(ParagraphAlignment.RIGHT)
                .lineBreakPolicy(LineBreakPolicy.CJK_STRICT).firstLineIndent(7).spaceBefore(3).build();
        var theme = DocumentTheme.builder().bodyFormat(format).build();
        assertEquals(new CellFormat(11, 22, ParagraphAlignment.RIGHT, LineBreakPolicy.CJK_STRICT), theme.cellFormat());
        var changed = theme.toBuilder().bodyFormat(body(14, 28)).build();
        assertEquals(cell(14, 28), changed.cellFormat());
        assertEquals(format, theme.bodyFormat()); assertEquals(11, theme.cellFormat().fontSize());
        var explicit = DocumentTheme.builder().cellFormat(cell(12, 18)).bodyFormat(format).build();
        assertEquals(cell(12, 18), explicit.toBuilder().bodyFormat(body(16, 32)).build().cellFormat());
    }

    @Test void themeBodyThemeOrderAffectsOnlyLaterParagraphsListsAndTableCells() {
        var heading = Heading.defaultFormat(1).toBuilder().fontSize(19).lineHeight(30).build();
        var tableFormat = TableFormat.builder().padding(4).spaceAfter(9).build();
        var first = DocumentTheme.builder().bodyFormat(body(10, 20)).cellFormat(cell(8, 16))
                .headingFormat(1, heading).tableFormat(tableFormat).build();
        var second = DocumentTheme.builder().bodyFormat(body(14, 28)).cellFormat(cell(9, 18)).build();
        var builder = Document.builder().theme(first).paragraph("A").bullet("A bullet")
                .table(table -> table.header("A").row("A1"));
        var snapshot = builder.build();
        var document = builder.bodyFormat(format -> format.fontSize(12).lineHeight(24))
                .paragraph("B").numbered("items", "B item").heading("B heading", 1)
                .table(table -> table.header("B").row("B1"))
                .theme(second).paragraph("C").table(table -> table.header("C").row("C1")).build();
        assertEquals(3, snapshot.blocks().size());
        assertEquals(body(10, 20), ((Paragraph) snapshot.blocks().getFirst()).format());
        assertEquals(body(10, 20), ((BulletItem) document.blocks().get(1)).format());
        assertEquals(cell(8, 16), ((Table) document.blocks().get(2)).rows().getFirst().cells().getFirst().format());
        assertEquals(12, ((Paragraph) document.blocks().get(3)).format().fontSize());
        assertEquals(24, ((NumberedItem) document.blocks().get(4)).format().lineHeight());
        assertEquals(heading, ((Heading) document.blocks().get(5)).format());
        assertEquals(cell(12, 24), ((Table) document.blocks().get(6)).rows().getFirst().cells().getFirst().format());
        assertEquals(tableFormat, ((Table) document.blocks().get(6)).format());
        assertEquals(body(14, 28), ((Paragraph) document.blocks().get(7)).format());
        assertEquals(cell(9, 18), ((Table) document.blocks().get(8)).rows().getFirst().cells().getFirst().format());
        assertEquals(TableFormat.DEFAULT, ((Table) document.blocks().get(8)).format());
    }

    @Test void allSixHeadingFormatsKeepTheirCompletePaginationPolicyAndRichRuns() {
        var builder = DocumentTheme.builder();
        for (int level = 1; level <= 6; level++) {
            int size = 20 - level;
            builder.headingFormat(level, format -> format.fontSize(size).lineHeight(size * 2));
        }
        var theme = builder.build();
        var document = Document.builder().theme(theme).bodyFormat(body(8, 16));
        var runs = List.of(new InlineText("Explicit rich heading", InlineStyle.of(12)));
        for (int level = 1; level <= 6; level++) { document.heading(runs, level); }
        var result = document.build();
        for (int level = 1; level <= 6; level++) {
            var heading = (Heading) result.blocks().get(level - 1);
            assertEquals(theme.headingFormat(level), heading.format());
            assertTrue(heading.format().keepTogether());
            assertEquals(Heading.defaultFormat(level).pagination(), heading.format().pagination());
            assertEquals(runs, heading.inlines());
        }
        var unlocked = body(10, 20).withPagination(PaginationPolicy.DEFAULT);
        var explicit = DocumentTheme.builder().headingFormat(2, unlocked).build();
        assertFalse(explicit.headingFormat(2).keepTogether());
        assertEquals(PaginationPolicy.DEFAULT, explicit.headingFormat(2).pagination());
        assertThrows(IllegalArgumentException.class, () -> theme.headingFormat(0));
        assertThrows(IllegalArgumentException.class, () -> builder.headingFormat(7, unlocked));
    }

    @Test void themeInitializesBothTableEntrypointsAndLocalCallbackHasFinalPriority() {
        var themedFormat = TableFormat.builder().padding(4).spaceBefore(3).spaceAfter(8).allowRowSplit(true).build();
        var theme = DocumentTheme.builder().bodyFormat(body(11, 22)).cellFormat(cell(9, 18)).tableFormat(themedFormat).build();
        var explicitCell = new TableCell("Explicit", cell(12, 18));
        var document = Document.builder().theme(theme)
                .table(List.of("H"), List.of(List.of("A")))
                .table(table -> table.cellFormat(cell(10, 20)).format(format -> format.padding(2).spaceAfter(1))
                        .header("H").row("B").row(explicitCell)).build();
        var first = (Table) document.blocks().getFirst(); var second = (Table) document.blocks().getLast();
        assertEquals(themedFormat, first.format());
        assertTrue(first.rows().stream().flatMap(row -> row.cells().stream()).allMatch(value -> value.format().equals(cell(9, 18))));
        assertEquals(PageMargins.uniform(2), second.format().padding()); assertEquals(1, second.format().spaceAfter());
        assertTrue(second.format().allowRowSplit());
        assertEquals(cell(10, 20), second.rows().getFirst().cells().getFirst().format());
        assertEquals(explicitCell, second.rows().getLast().cells().getFirst());
    }

    @Test void explicitBlocksCellsInnerTablesAndInlineStylesAreNotRewritten() {
        var theme = DocumentTheme.builder().bodyFormat(body(16, 32)).cellFormat(cell(8, 16)).build();
        var paragraph = new Paragraph("explicit-p", "Original", ParagraphFormat.of(12));
        var heading = new Heading("explicit-h", "Original heading", 3, ParagraphFormat.of(12));
        var inner = Table.builder("inner").row("Original inner").build();
        var blocks = TableCell.builder().format(cell(12, 18)).paragraph(paragraph).table("nested", table -> table.row("Inner")).build();
        var outer = Table.builder("outer").row(blocks).build();
        var ink = new InlineStyle("bold", 12, 0, 0, false, false);
        var rich = new InlineText("Explicit black", ink);
        var document = Document.builder().theme(theme).add(heading).add(inner).add(outer)
                .paragraph(List.of(rich)).paragraph("Local complete", ParagraphFormat.of(12)).build();
        assertEquals(heading, document.blocks().getFirst()); assertEquals(inner, document.blocks().get(1));
        assertEquals(outer, document.blocks().get(2));
        assertEquals(paragraph, ((Table) document.blocks().get(2)).rows().getFirst().cells().getFirst().blocks().getFirst());
        assertEquals(List.of(rich), ((Paragraph) document.blocks().get(3)).inlines());
        assertEquals(ParagraphFormat.of(12), ((Paragraph) document.blocks().getLast()).format());
    }

    @Test void namedCallbacksSnapshotDefinitionTimeDefaultsAndUpdatesUsePreviousNamedValue() {
        var builder = DocumentTheme.builder().bodyFormat(body(11, 22))
                .paragraphStyle("note", format -> format.spaceBefore(2))
                .inlineStyle("emphasis", style -> style.fontRole("bold").rgb(0x224488))
                .bodyFormat(body(16, 32));
        var first = builder.build();
        assertEquals(11, first.paragraphStyle("note").fontSize());
        assertEquals(22, first.paragraphStyle("note").lineHeight());
        assertEquals(11, first.inlineStyle("emphasis").fontSize());
        builder.paragraphStyle("note", format -> format.spaceAfter(3))
                .inlineStyle("emphasis", style -> style.underline(true));
        var changed = builder.build();
        assertEquals(11, changed.paragraphStyle("note").fontSize()); assertEquals(2, changed.paragraphStyle("note").spaceBefore());
        assertEquals(3, changed.paragraphStyle("note").spaceAfter());
        assertEquals("bold", changed.inlineStyle("emphasis").fontRole()); assertEquals(0x224488, changed.inlineStyle("emphasis").rgb());
        assertEquals(11, changed.inlineStyle("emphasis").fontSize()); assertTrue(changed.inlineStyle("emphasis").underline());
        assertFalse(first.inlineStyle("emphasis").underline());
    }

    @Test void namedStylesAreCompleteCaseSensitiveValuesAndUnknownNamesFailImmediately() {
        var theme = DocumentTheme.builder().bodyFormat(body(18, 36))
                .paragraphStyle("Note", ParagraphFormat.of(12)).paragraphStyle("note", body(9, 18))
                .inlineStyle("Ink", new InlineStyle("bold", 12, 0, 0, false, false)).build();
        assertEquals(12, theme.paragraphStyle("Note").fontSize()); assertEquals(9, theme.paragraphStyle("note").fontSize());
        assertEquals(12, theme.inlineStyle("Ink").fontSize()); assertEquals(0, theme.inlineStyle("Ink").rgb());
        assertThrows(IllegalArgumentException.class, () -> theme.paragraphStyle("unknown"));
        assertThrows(IllegalArgumentException.class, () -> theme.inlineStyle("ink"));
        assertThrows(NullPointerException.class, () -> theme.paragraphStyle(null));
    }

    @Test void invalidNamesAndNewNameBeyond128FailBeforeInvokingCallbacks() {
        var calls = new AtomicInteger();
        for (String invalid : List.of("", " ", "1bad", "-bad", "a.b", "a b", "中文", "a".repeat(65))) {
            var builder = DocumentTheme.builder();
            assertThrows(IllegalArgumentException.class, () -> builder.paragraphStyle(invalid, format -> calls.incrementAndGet()));
            assertThrows(IllegalArgumentException.class, () -> builder.inlineStyle(invalid, style -> calls.incrementAndGet()));
        }
        assertEquals(0, calls.get());
        var builder = DocumentTheme.builder();
        for (int index = 0; index < 128; index++) {
            builder.paragraphStyle("p" + index, body(10, 20)).inlineStyle("i" + index, InlineStyle.of(10));
        }
        assertThrows(IllegalArgumentException.class, () -> builder.paragraphStyle("overflow", format -> calls.incrementAndGet()));
        assertThrows(IllegalArgumentException.class, () -> builder.inlineStyle("overflow", style -> calls.incrementAndGet()));
        assertEquals(0, calls.get());
        builder.paragraphStyle("p0", format -> format.fontSize(12)).inlineStyle("i0", style -> style.fontSize(12));
        var theme = builder.build();
        assertEquals(128, theme.paragraphStyles().size()); assertEquals(128, theme.inlineStyles().size());
        assertEquals(12, theme.paragraphStyle("p0").fontSize()); assertEquals(12, theme.inlineStyle("i0").fontSize());
        assertEquals(10, theme.paragraphStyle("p127").fontSize());
        var longest = "A" + "_".repeat(63);
        assertEquals(12, DocumentTheme.builder().inlineStyle(longest, InlineStyle.of(12)).build().inlineStyle(longest).fontSize());
    }

    @Test void namedMapsAndToBuilderAreIndependentImmutableSnapshots() {
        var original = DocumentTheme.builder().bodyFormat(body(11, 22)).paragraphStyle("note", body(9, 18))
                .inlineStyle("emphasis", InlineStyle.of(11)).build();
        assertThrows(UnsupportedOperationException.class, () -> original.paragraphStyles().clear());
        assertThrows(UnsupportedOperationException.class, () -> original.inlineStyles().put("new", InlineStyle.of(12)));
        var builder = original.toBuilder();
        var copy = builder.build();
        builder.paragraphStyle("note", body(10, 20)).inlineStyle("emphasis", InlineStyle.of(13)).bodyFormat(body(16, 32));
        assertEquals(original.bodyFormat(), copy.bodyFormat()); assertEquals(original.cellFormat(), copy.cellFormat());
        assertEquals(original.paragraphStyles(), copy.paragraphStyles()); assertEquals(original.inlineStyles(), copy.inlineStyles());
        assertEquals(9, original.paragraphStyle("note").fontSize()); assertEquals(11, original.inlineStyle("emphasis").fontSize());
        assertEquals(10, builder.build().paragraphStyle("note").fontSize());
    }

    @Test void retainedChildBuildersCannotChangeThemeEvenBeforeItsOuterBuild() {
        var heldBody = new AtomicReference<ParagraphFormat.Builder>();
        var heldHeading = new AtomicReference<ParagraphFormat.Builder>();
        var heldCell = new AtomicReference<CellFormat.Builder>();
        var heldTable = new AtomicReference<TableFormat.Builder>();
        var heldParagraph = new AtomicReference<ParagraphFormat.Builder>();
        var heldInline = new AtomicReference<InlineStyle.Builder>();
        var builder = DocumentTheme.builder()
                .bodyFormat(format -> { heldBody.set(format); format.fontSize(11).lineHeight(22); })
                .headingFormat(1, format -> { heldHeading.set(format); format.fontSize(20).lineHeight(30); })
                .cellFormat(format -> { heldCell.set(format); format.fontSize(10).lineHeight(20); })
                .tableFormat(format -> { heldTable.set(format); format.padding(4); })
                .paragraphStyle("note", format -> { heldParagraph.set(format); format.spaceBefore(2); })
                .inlineStyle("emphasis", style -> { heldInline.set(style); style.fontRole("bold"); });
        heldBody.get().fontSize(99); heldHeading.get().fontSize(99); heldCell.get().fontSize(99);
        heldTable.get().padding(99); heldParagraph.get().fontSize(99); heldInline.get().fontSize(99);
        var theme = builder.build();
        assertEquals(11, theme.bodyFormat().fontSize()); assertEquals(20, theme.headingFormat(1).fontSize());
        assertEquals(10, theme.cellFormat().fontSize()); assertEquals(PageMargins.uniform(4), theme.tableFormat().padding());
        assertEquals(11, theme.paragraphStyle("note").fontSize()); assertEquals(2, theme.paragraphStyle("note").spaceBefore());
        assertEquals(11, theme.inlineStyle("emphasis").fontSize()); assertEquals("bold", theme.inlineStyle("emphasis").fontRole());
    }
}
