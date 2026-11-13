// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ContentOptionsTest {
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
