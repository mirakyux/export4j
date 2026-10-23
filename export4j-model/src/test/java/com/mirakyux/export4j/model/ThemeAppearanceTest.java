// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ThemeAppearanceTest {
    private static DocumentTheme brand() {
        return DocumentTheme.builder().fontRole("brand").rgb(0x224488).bodyRgb(0x446622)
                .headingFontRole(1, "title").cellRgb(0x884422).footerRgb(0x666666).build();
    }

    @Test void stringAuthoringResolvesDomainsAndListMarkersAtAdditionTime() {
        var theme = brand();
        var builder = Document.builder().theme(theme).paragraph("Body").heading("Title", 1).heading("Second", 2)
                .bullet("Bullet").numbered("list", "Number")
                .table(List.of("Header"), List.of(List.of("Cell")))
                .table(table -> table.header("Builder header").row("Builder cell"));
        var snapshot = builder.build();
        var changed = builder.theme(theme.toBuilder().rgb(0x112233).inheritBodyRgb().build()).paragraph("Later").build();
        assertEquals(theme.bodyText().style(12), ((Paragraph) snapshot.blocks().get(0)).inlines().getFirst().style());
        assertEquals(theme.headingText(1).style(theme.headingFormat(1).fontSize()), ((Heading) snapshot.blocks().get(1)).inlines().getFirst().style());
        assertEquals("brand", ((Heading) snapshot.blocks().get(2)).inlines().getFirst().style().fontRole());
        var bullet = (BulletItem) snapshot.blocks().get(3);
        var number = (NumberedItem) snapshot.blocks().get(4);
        assertEquals(theme.bodyText().style(12), bullet.markerStyle());
        assertEquals(bullet.markerStyle(), number.markerStyle());
        assertEquals(bullet.markerStyle(), bullet.inlines().getFirst().style());
        for (int index : List.of(5, 6)) {
            for (var row : ((Table) snapshot.blocks().get(index)).rows()) {
                assertEquals(theme.cellText().style(12), row.cells().getFirst().inlines().getFirst().style());
            }
        }
        assertEquals(7, snapshot.blocks().size());
        assertEquals(0x112233, ((Paragraph) changed.blocks().getLast()).inlines().getFirst().style().rgb());
    }

    @Test void completeRunsCellsAndRawBlocksKeepTheirExplicitValues() {
        var runs = List.of(new InlineText("Explicit", InlineStyle.of(9)));
        var raw = new Paragraph("raw", "Raw", ParagraphFormat.of(7));
        var cell = new TableCell("Cell", CellFormat.of(8));
        var doc = Document.builder().theme(brand()).add(raw).paragraph(runs).heading(runs, 1).bullet(runs)
                .numbered("list", runs).table(table -> table.row(cell)).build();
        assertSame(raw, doc.blocks().getFirst());
        assertEquals(runs, ((Paragraph) doc.blocks().get(1)).inlines());
        assertEquals(runs, ((Heading) doc.blocks().get(2)).inlines());
        assertEquals(runs, ((BulletItem) doc.blocks().get(3)).inlines());
        assertEquals(runs, ((NumberedItem) doc.blocks().get(4)).inlines());
        assertSame(cell, ((Table) doc.blocks().get(5)).rows().getFirst().cells().getFirst());
        assertEquals(brand().bodyText().style(12), ((BulletItem) doc.blocks().get(3)).markerStyle());
    }

    @Test void declarationIntentSurvivesCopiesAndSinglePropertyRestoration() {
        var inherited = DocumentTheme.builder().build();
        var declared = inherited.toBuilder().bodyFontRole("").bodyRgb(0).headingRgb(2, 0)
                .cellFontRole("").headerRgb(0).footerFontRole("").build();
        assertNotEquals(inherited, declared);
        assertEquals(declared, declared.toBuilder().build());
        var changed = declared.toBuilder().fontRole("brand").rgb(0x112233).build();
        assertEquals(TextAppearance.DEFAULT, changed.bodyText());
        assertEquals(new TextAppearance("brand", 0), changed.headingText(2));
        assertEquals(new TextAppearance("", 0x112233), changed.cellText());
        assertEquals(0, changed.headerStyle().rgb());
        assertEquals("", changed.footerStyle().fontRole());
        var restored = changed.toBuilder().inheritBodyFontRole().inheritBodyRgb().inheritHeadingRgb(2)
                .inheritCellFontRole().inheritHeaderRgb().inheritFooterFontRole().build();
        assertEquals(restored.text(), restored.bodyText());
        assertEquals(restored.text(), restored.headingText(2));
        assertEquals(restored.text(), restored.cellText());
        assertEquals(restored.text().rgb(), restored.headerStyle().rgb());
        assertEquals(restored.text().fontRole(), restored.footerStyle().fontRole());
    }

    @Test void completePageBandStyleSnapshotsResolvedValuesWhileIndividualResetFollowsGlobal() {
        var builder = DocumentTheme.builder().fontRole("brand").rgb(0x224488);
        var plain = builder.build();
        assertEquals("brand", plain.headerStyle().fontRole());
        assertEquals(0x224488, plain.footerStyle().rgb());
        var fixed = builder.headerStyle(style -> style.format(format -> format.fontSize(9)))
                .footerStyle(PageRegionStyle.DEFAULT).fontRole("later").rgb(0x112233).build();
        assertEquals("brand", fixed.headerStyle().fontRole());
        assertEquals(0x224488, fixed.headerStyle().rgb());
        assertEquals(PageRegionStyle.DEFAULT, fixed.footerStyle());
        var reset = fixed.toBuilder().inheritHeaderFontRole().inheritFooterRgb().build();
        assertEquals("later", reset.headerStyle().fontRole());
        assertEquals(0x224488, reset.headerStyle().rgb());
        assertEquals("", reset.footerStyle().fontRole());
        assertEquals(0x112233, reset.footerStyle().rgb());
    }

    @Test void namedCompleteStylesRemainIndependentAndDefaultThemeKeepsLegacyModel() {
        var theme = brand().toBuilder().inlineStyle("ink", InlineStyle.of(8)).build();
        assertEquals(InlineStyle.of(8), theme.inlineStyle("ink"));
        var empty = DocumentTheme.builder().build();
        var actual = Document.builder().theme(empty).paragraph("Body").heading("Title", 1).bullet("Bullet")
                .numbered("list", "Number").table(List.of("H"), List.of(List.of("V"))).build();
        var legacy = Document.builder().paragraph("Body").heading("Title", 1).bullet("Bullet")
                .numbered("list", "Number").table(List.of("H"), List.of(List.of("V"))).build();
        assertEquals(legacy, actual);
        assertTrue(actual.paragraphs().getFirst().inlines().isEmpty());
    }

    @Test void invalidAppearanceFailsBeforeItCanEnterAuthoringState() {
        assertThrows(NullPointerException.class, () -> new TextAppearance(null, 0));
        assertThrows(IllegalArgumentException.class, () -> DocumentTheme.builder().fontRole("bad role"));
        assertThrows(IllegalArgumentException.class, () -> DocumentTheme.builder().cellRgb(-1));
        assertThrows(IllegalArgumentException.class, () -> DocumentTheme.builder().headingRgb(0, 0));
        assertThrows(IllegalArgumentException.class, () -> DocumentTheme.builder().headingFontRole(7, "valid"));
        assertThrows(IllegalArgumentException.class, () -> DocumentTheme.builder().headerRgb(0x1000000));
    }
}
