// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContentOptionsTest {
    @Test void themeSemanticDomainsSurviveSparseDeclarationsAndExplicitBodyEqualValues() {
        var theme = DocumentTheme.builder().bodyFormat(p -> p.fontSize(10).lineHeight(24))
                .bodyFontRole("body").bodyRgb(0x223344)
                .headingFormat(1, p -> p.fontSize(18).lineHeight(32).spaceAfter(11)
                        .pagination(new PaginationPolicy(true, false, 1, 1)))
                .headingFontRole(1, "title").headingRgb(1, 0xAABBCC)
                .cellFormat(c -> c.fontSize(9).lineHeight(20))
                .cellFontRole("cell").cellRgb(0x445566).build();
        var base = ContentOptions.fromTheme(theme);
        assertEquals(base, ContentOptions.builder().build().resolve(base));
        for (int level = 1; level <= 6; level++) {
            assertEquals(theme.headingFormat(level), base.styles().heading(level).paragraphFormat());
            assertEquals(theme.headingText(level), base.styles().heading(level).text());
        }
        assertEquals(theme.cellText(), base.styles().cellText());
        var local = ContentOptions.builder().fontSize(10).fontRole("body").rgb(0x223344).spaceAfter(0)
                .keepWithNext(false).build().resolve(base);
        var heading = local.styles().heading(1);
        assertEquals(10, heading.paragraphFormat().fontSize());
        assertEquals(32, heading.paragraphFormat().lineHeight());
        assertEquals(0, heading.paragraphFormat().spaceAfter());
        assertFalse(heading.paragraphFormat().pagination().keepWithNext());
        assertEquals(theme.bodyText(), heading.text());
        assertEquals(theme.bodyText(), local.styles().cellText());
        assertEquals(theme.cellFormat(), local.cellFormat());
        var black = ContentOptions.builder().rgb(0).fontRole("").build().resolve(base);
        assertEquals(new TextAppearance("", 0), black.styles().heading(1).text());
        assertEquals(new TextAppearance("", 0), black.styles().cellText());
    }

    @Test void semanticStylesAreBoundedSnapshotsAndLegacyConstructorsStayUniform() {
        var legacy = parent();
        for (int level = 1; level <= 6; level++) {
            assertEquals(legacy.paragraphFormat(), legacy.styles().heading(level).paragraphFormat());
            assertEquals(legacy.textAppearance(), legacy.styles().heading(level).text());
        }
        assertEquals(legacy.textAppearance(), legacy.styles().cellText());
        assertEquals(ContentOptions.defaults().paragraphFormat(),
                ContentOptions.defaults().styles().heading(1).paragraphFormat());
        var values = new ArrayList<>(legacy.styles().headings());
        var styles = new ContentOptions.SemanticStyles(values, legacy.textAppearance());
        values.clear();
        assertEquals(6, styles.headings().size());
        assertThrows(UnsupportedOperationException.class, () -> styles.headings().clear());
        assertThrows(IllegalArgumentException.class, () -> styles.heading(0));
        assertThrows(IllegalArgumentException.class, () -> styles.heading(7));
        assertThrows(IllegalArgumentException.class, () -> new ContentOptions.SemanticStyles(List.of(), legacy.textAppearance()));
        assertThrows(NullPointerException.class, () -> new ContentOptions.SemanticStyle(null, legacy.paragraphFormat()));
    }

    @Test void declarationOverlayRetainsOnlyTheDeclaredPropertiesUntilFinalResolution() {
        var global = ContentOptions.builder().fontSize(13).formulas(true).build();
        var local = ContentOptions.builder().rgb(0x556677).build();
        var combined = global.overlay(local);
        assertEquals(ContentOptions.builder().fontSize(13).formulas(true).rgb(0x556677).build(), combined);
        var theme = DocumentTheme.builder().bodyFormat(p -> p.fontSize(10).lineHeight(26))
                .headingFormat(1, p -> p.fontSize(18).lineHeight(32))
                .cellFormat(c -> c.fontSize(8).lineHeight(19)).build();
        var body = combined.resolve(ContentOptions.fromTheme(theme));
        assertEquals(13, body.paragraphFormat().fontSize());
        assertEquals(26, body.paragraphFormat().lineHeight());
        assertEquals(theme.cellFormat(), body.cellFormat());
        var heading = new ContentOptions.Resolved("", 0x123456, theme.headingFormat(1), theme.cellFormat(),
                theme.tableFormat(), ContentOptions.Format.TEXT, false, true);
        assertEquals(32, combined.resolve(heading).paragraphFormat().lineHeight());
        assertEquals(theme.cellFormat(), combined.resolve(heading).cellFormat());
        assertEquals(global, global.overlay(ContentOptions.builder().build()));
        assertEquals(local, local.overlay(ContentOptions.builder().build()));
        assertEquals(ContentOptions.builder().build(), combined.toBuilder().inheritFontSize().inheritFormulas().inheritRgb().build());
    }

    @Test void declarationOverlayPreservesExplicitZeroFalseAndEmptyRoleOverrides() {
        var global = ContentOptions.builder().fontRole("brand").rgb(0x112233).firstLineIndent(9)
                .spaceAfter(8).formulas(true).tables(true).rawHtml(true).build();
        var local = ContentOptions.builder().fontRole("").rgb(0).firstLineIndent(0).spaceAfter(0)
                .formulas(false).tables(false).rawHtml(false).build();
        var combined = global.overlay(local);
        assertEquals(local, combined);
        var resolved = combined.resolve(parent());
        assertEquals("", resolved.fontRole());
        assertEquals(0, resolved.rgb());
        assertEquals(0, resolved.paragraphFormat().firstLineIndent());
        assertEquals(0, resolved.paragraphFormat().spaceAfter());
        assertFalse(resolved.formulas());
        assertFalse(resolved.tables());
        assertFalse(resolved.rawHtml());
        assertEquals(17, resolved.paragraphFormat().fontSize());
        assertEquals(ContentOptions.Format.HTML, resolved.format());
    }

    @Test void markdownHtmlToggleInheritsAndExplicitFalseRestoresLiteralSourceBoundary() {
        var parent = ContentOptions.builder().format(ContentOptions.Format.MARKDOWN).rawHtml(true).build().resolve(ContentOptions.defaults());
        assertTrue(ContentOptions.builder().build().resolve(parent).rawHtml());
        var local = ContentOptions.builder().rawHtml(false).build();
        assertFalse(local.resolve(parent).rawHtml());
        assertTrue(local.toBuilder().inheritRawHtml().build().resolve(parent).rawHtml());
        assertNotEquals(local, ContentOptions.builder().build());
        assertFalse(ContentOptions.defaults().rawHtml());
    }
    private static ContentOptions.Resolved parent() {
        return new ContentOptions.Resolved("brand", 0x224488,
                new ParagraphFormat(17, 28, 7, 8, ParagraphAlignment.RIGHT, 9, 10, true,
                        new PaginationPolicy(true, true, 3, 4), LineBreakPolicy.CJK_STRICT, 45),
                CellFormat.of(9), TableFormat.builder().padding(3).drawBorders(false).build(),
                ContentOptions.Format.HTML, true, true);
    }

    @Test void undeclaredPropertiesInheritEveryDomainWithoutDefaultValueGuessing() {
        var inherited = ContentOptions.builder().build();
        assertEquals(parent(), inherited.resolve(parent()));
        assertEquals(inherited, inherited.toBuilder().build());
        var local = ContentOptions.builder().fontRole("").rgb(0).fontSize(12).lineHeight(18)
                .firstLineIndent(0).subsequentLineIndent(0).alignment(ParagraphAlignment.LEFT)
                .spaceBefore(0).spaceAfter(0).keepTogether(false).keepWithNext(false).pageBreakBefore(false)
                .minLinesBeforeBreak(1).minLinesAfterBreak(1).lineBreakPolicy(LineBreakPolicy.STANDARD)
                .rotation(0).format(ContentOptions.Format.TEXT).formulas(false).tables(false).build();
        var result = local.resolve(parent());
        assertEquals("", result.fontRole()); assertEquals(0, result.rgb());
        assertEquals(new ParagraphFormat(12, 18, 0, 0, ParagraphAlignment.LEFT, 0, 0, false), result.paragraphFormat());
        assertEquals(parent().cellFormat(), result.cellFormat());
        assertEquals(parent().tableFormat(), result.tableFormat());
        assertEquals(ContentOptions.Format.TEXT, result.format());
        assertFalse(result.formulas()); assertFalse(result.tables());
        assertNotEquals(inherited, ContentOptions.builder().rgb(0).build());
    }

    @Test void sparseFontSizeDoesNotHideInheritedLineHeightOrOtherParagraphGeometry() {
        var child = ContentOptions.builder().fontSize(12).rgb(0).build().resolve(parent());
        assertEquals(12, child.paragraphFormat().fontSize());
        assertEquals(28, child.paragraphFormat().lineHeight());
        assertEquals(7, child.paragraphFormat().firstLineIndent());
        assertEquals(parent().paragraphFormat().pagination(), child.paragraphFormat().pagination());
        assertEquals(0, child.rgb()); assertEquals("brand", child.fontRole());
    }

    @Test void snapshotsAndSinglePropertyResetsPreserveOtherDeclarations() {
        var builder = ContentOptions.builder().fontRole("").rgb(0).paragraphFormat(ParagraphFormat.of(12))
                .formulas(false).tables(false).format(ContentOptions.Format.TEXT);
        var first = builder.build();
        builder.rgb(0xff0000).fontSize(30).formulas(true);
        assertEquals(0, first.resolve(parent()).rgb());
        assertEquals(12, first.resolve(parent()).paragraphFormat().fontSize());
        assertFalse(first.resolve(parent()).formulas());
        var reset = first.toBuilder().inheritFontRole().inheritRgb().inheritFontSize().inheritKeepTogether()
                .inheritPageBreakBefore().inheritFormulas().inheritFormat().build().resolve(parent());
        assertEquals("brand", reset.fontRole()); assertEquals(0x224488, reset.rgb());
        assertEquals(17, reset.paragraphFormat().fontSize());
        assertEquals(18, reset.paragraphFormat().lineHeight());
        assertTrue(reset.paragraphFormat().keepTogether());
        assertTrue(reset.paragraphFormat().pagination().pageBreakBefore());
        assertFalse(reset.paragraphFormat().pagination().keepWithNext());
        assertTrue(reset.formulas()); assertFalse(reset.tables());
        assertEquals(ContentOptions.Format.HTML, reset.format());
        assertEquals(parent().paragraphFormat(), first.toBuilder().inheritParagraphFormat().build().resolve(parent()).paragraphFormat());
    }

    @Test void themeAndWholeCellTableValuesResolveAsIndependentSnapshots() {
        var theme = DocumentTheme.builder().fontRole("brand").rgb(0x123456)
                .bodyFormat(format -> format.fontSize(11).lineHeight(22))
                .cellFormat(CellFormat.of(8)).tableFormat(format -> format.padding(2)).build();
        var base = ContentOptions.fromTheme(theme);
        assertEquals(theme.bodyFormat(), base.paragraphFormat());
        assertEquals("brand", base.fontRole()); assertEquals(0x123456, base.rgb());
        var local = ContentOptions.builder().cellFormat(CellFormat.of(12)).tableFormat(TableFormat.DEFAULT).build();
        assertEquals(CellFormat.of(12), local.resolve(base).cellFormat());
        assertEquals(TableFormat.DEFAULT, local.resolve(base).tableFormat());
        assertEquals(base, local.toBuilder().inheritCellFormat().inheritTableFormat().build().resolve(base));
    }

    @Test void invalidDeclarationsAndUnresolvedFinalFormatsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ContentOptions.builder().rgb(-1).build());
        assertThrows(IllegalArgumentException.class, () -> ContentOptions.builder().fontRole("bad role").build());
        assertThrows(IllegalArgumentException.class, () -> ContentOptions.builder().fontSize(Double.NaN).build());
        assertThrows(IllegalArgumentException.class, () -> ContentOptions.builder().spaceAfter(-1).build());
        assertThrows(IllegalArgumentException.class, () -> ContentOptions.builder().minLinesAfterBreak(0).build());
        assertThrows(IllegalArgumentException.class, () -> new ContentOptions.Resolved("", 0, ParagraphFormat.of(12),
                CellFormat.of(12), TableFormat.DEFAULT, ContentOptions.Format.INHERIT, false, false));
    }
}
