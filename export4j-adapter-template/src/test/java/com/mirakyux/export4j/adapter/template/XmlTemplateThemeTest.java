// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableFormat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlTemplateThemeTest {
    private static final String COLUMNS = "<columns><column weight='1'/></columns>";
    private static final ParagraphFormat BODY = ParagraphFormat.builder().fontSize(11).lineHeight(24)
            .firstLineIndent(7).subsequentLineIndent(3).alignment(ParagraphAlignment.RIGHT)
            .spaceBefore(2).spaceAfter(4).lineBreakPolicy(LineBreakPolicy.CJK_STRICT).build();
    private static final ParagraphFormat NOTE = ParagraphFormat.builder().fontSize(13).lineHeight(30)
            .firstLineIndent(5).subsequentLineIndent(2).alignment(ParagraphAlignment.CENTER)
            .spaceBefore(7).spaceAfter(9).keepTogether(true)
            .pagination(new PaginationPolicy(true, false, 2, 3)).lineBreakPolicy(LineBreakPolicy.CJK_STRICT).build();
    private static final ParagraphFormat CELL_NOTE = NOTE.withPagination(new PaginationPolicy(false, false, 2, 3));
    private static final CellFormat CELL = new CellFormat(9, 22, ParagraphAlignment.CENTER, LineBreakPolicy.CJK_STRICT);
    private static final InlineStyle INK = new InlineStyle("report-bold", 10, 0x224488, 2, true, true);
    private static final TableFormat TABLE = TableFormat.builder().padding(4).spaceBefore(5).spaceAfter(6).build();

    private static DocumentTheme theme() {
        return DocumentTheme.builder().bodyFormat(BODY).cellFormat(CELL).tableFormat(TABLE)
                .paragraphStyle("note", NOTE).paragraphStyle("note-cell", CELL_NOTE).inlineStyle("ink", INK).build();
    }

    private static String xml(String content) { return "<document version='1'>" + content + "</document>"; }
    private static String table(String attributes, String content) {
        return "<table" + attributes + ">" + COLUMNS + "<row><cell>" + content + "</cell></row></table>";
    }
    private static Document render(XmlTemplate.Builder compiler, String content) {
        return compiler.build().compile(xml(content)).render(Map.of());
    }
    private static Table lastTable(Document document) { return assertInstanceOf(Table.class, document.blocks().getLast()); }

    @Test void defaultThemePreservesTheCompleteModelIdsWhitespaceAndBoundObjects() throws IOException {
        var resource = image();
        String source = xml("<heading level='2'>${title}</heading><p id='intro'>  A<span> B </span>${value}"
                + "<image ref='mark' width='8' height='9'/><page-reference target='intro' width='20'/></p>"
                + "<bullet>Bullet</bullet><numbered list-id='items'>One</numbered><each items='items' as='item'><p>${item}</p></each>"
                + "<rows items='items' as='item'>" + COLUMNS + "<header><cell>H</cell></header><row><cell>${item}</cell></row></rows>"
                + table("", "<p>Block</p>" + table("", "Inner")));
        var data = Map.of("title", "Title", "value", "A😀", "items", List.of("First", "Second"));
        var plain = XmlTemplate.builder().image("mark", resource).build().compile(source).render(data);
        var themed = XmlTemplate.builder().image("mark", resource).theme(DocumentTheme.builder().build()).build()
                .compile(source).render(data);
        assertEquals(plain, themed);
        assertEquals("  A B A😀\ufffc\ufffc", themed.paragraphs().getFirst().text());
    }

    @Test void legacyBodyAndCellSettersKeepTheirIndependentScopesInBothOrders() {
        String content = "<p>P</p><bullet>B</bullet><numbered list-id='n'>N</numbered><heading level='1'>H</heading>" + table("", "C");
        var bodyOnly = render(XmlTemplate.builder().bodyFormat(BODY), content);
        assertEquals(BODY, ((Paragraph) bodyOnly.blocks().getFirst()).format());
        assertEquals(CellFormat.of(12), lastTable(bodyOnly).rows().getFirst().cells().getFirst().format());
        var cellOnly = render(XmlTemplate.builder().cellFormat(CELL), content);
        assertEquals(ParagraphFormat.of(12), ((Paragraph) cellOnly.blocks().getFirst()).format());
        assertEquals(CELL, lastTable(cellOnly).rows().getFirst().cells().getFirst().format());
        var first = render(XmlTemplate.builder().bodyFormat(BODY).cellFormat(CELL), content);
        var second = render(XmlTemplate.builder().cellFormat(CELL).bodyFormat(BODY), content);
        assertEquals(first, second);
        assertEquals(BODY, ((BulletItem) first.blocks().get(1)).format());
        assertEquals(BODY, ((NumberedItem) first.blocks().get(2)).format());
        assertEquals(Heading.defaultFormat(1), ((Heading) first.blocks().get(3)).format());
    }

    @Test void themeThenBodyOrCellOnlyReplacesTheRequestedCompleteValue() {
        var heading = Heading.defaultFormat(1).toBuilder().fontSize(19).lineHeight(32).build();
        var selected = theme().toBuilder().headingFormat(1, heading).build();
        var body = ParagraphFormat.of(15);
        var cell = CellFormat.of(8);
        String content = "<p>P</p><heading level='1'>H</heading>" + table("", "C");
        var bodyOverride = render(XmlTemplate.builder().theme(selected).bodyFormat(body), content);
        assertEquals(body, ((Paragraph) bodyOverride.blocks().getFirst()).format());
        assertEquals(CELL, lastTable(bodyOverride).rows().getFirst().cells().getFirst().format());
        assertEquals(heading, ((Heading) bodyOverride.blocks().get(1)).format());
        assertEquals(TABLE, lastTable(bodyOverride).format());
        var cellOverride = render(XmlTemplate.builder().theme(selected).cellFormat(cell), content);
        assertEquals(BODY, ((Paragraph) cellOverride.blocks().getFirst()).format());
        assertEquals(cell, lastTable(cellOverride).rows().getFirst().cells().getFirst().format());
        assertEquals(heading, ((Heading) cellOverride.blocks().get(1)).format());
        assertEquals(TABLE, lastTable(cellOverride).format());
        var implicitCell = DocumentTheme.builder().bodyFormat(BODY).build();
        assertEquals(implicitCell.cellFormat(), lastTable(render(XmlTemplate.builder().theme(implicitCell).bodyFormat(body), content))
                .rows().getFirst().cells().getFirst().format());
    }

    @Test void laterThemeReplacesDefaultsAndNamesButBuiltCompilersAndPrototypesStayFrozen() {
        var builder = XmlTemplate.builder().pageSettings(PageSettings.builder().size(220, 180).margins(10).build())
                .theme(theme()).bodyFormat(ParagraphFormat.of(15)).cellFormat(CellFormat.of(8));
        var compiler = builder.build();
        String content = "<p style='note'><span style='ink'>${value}</span></p>" + table("", "Cell");
        var compiled = compiler.compile(xml(content));
        var second = DocumentTheme.builder().bodyFormat(ParagraphFormat.of(16)).cellFormat(CellFormat.of(7))
                .paragraphStyle("other", ParagraphFormat.of(10)).build();
        builder.theme(second).pageSettings(PageSettings.A4);
        var frozen = compiled.render(Map.of("value", "Later"));
        assertEquals(NOTE, frozen.paragraphs().getFirst().format());
        assertEquals(INK, frozen.paragraphs().getFirst().inlines().getFirst().style());
        assertEquals(CellFormat.of(8), lastTable(frozen).rows().getFirst().cells().getFirst().format());
        assertEquals(220, frozen.pageSettings().size().width());
        assertEquals(frozen, compiler.compile(xml(content)).render(Map.of("value", "Later")));
        var current = render(builder, "<p>Current</p>" + table("", "Current"));
        assertEquals(second.bodyFormat(), current.paragraphs().getFirst().format());
        assertEquals(second.cellFormat(), lastTable(current).rows().getFirst().cells().getFirst().format());
        assertEquals(TableFormat.DEFAULT, lastTable(current).format());
        rejected(builder.build(), xml("<p style='note'>SECRET</p>"), TemplateException.Code.INVALID_VALUE, 1);
        assertThrows(NullPointerException.class, () -> XmlTemplate.builder().theme(null));
    }

    @Test void namedParagraphIsACompleteBaseAndExplicitLegacyAttributesWin() {
        var document = render(XmlTemplate.builder().theme(theme()), "<p style='note'>Named</p>"
                + "<p style='note' size='12' align='LEFT' keep-together='false' space-before='0' space-after='0'>Override</p>"
                + "<p style='note' size='12' line-height='26'>Height</p>");
        assertEquals(NOTE, document.paragraphs().getFirst().format());
        var expected = NOTE.toBuilder().fontSize(12).lineHeight(18).alignment(ParagraphAlignment.LEFT)
                .keepTogether(false).spaceBefore(0).spaceAfter(0).build();
        assertEquals(expected, document.paragraphs().get(1).format());
        assertEquals(NOTE.toBuilder().fontSize(12).lineHeight(26).build(), document.paragraphs().getLast().format());
    }

    @Test void blockCellNamesKeepTheirCompleteSpacingAndUnnamedParagraphsUseActualCellDefaults() {
        var document = render(XmlTemplate.builder().theme(theme()), "<table>" + COLUMNS
                + "<row><cell size='8' line-height='20' align='RIGHT'><p>Plain</p><p style='note-cell'>Named</p>"
                + "<p style='note-cell' space-before='0' space-after='0'>Zero</p>"
                + table("", "Inner") + "</cell></row></table>");
        var outer = lastTable(document);
        var blocks = outer.rows().getFirst().cells().getFirst().blocks();
        assertEquals(new ParagraphFormat(8, 20, 0, 0, ParagraphAlignment.RIGHT, 0, 0, false)
                .withLineBreakPolicy(LineBreakPolicy.CJK_STRICT), ((Paragraph) blocks.getFirst()).format());
        assertEquals(CELL_NOTE, ((Paragraph) blocks.get(1)).format());
        assertEquals(CELL_NOTE.toBuilder().spaceBefore(0).spaceAfter(0).build(), ((Paragraph) blocks.get(2)).format());
        assertEquals(CELL, ((Table) blocks.getLast()).rows().getFirst().cells().getFirst().format());
        assertEquals(TABLE, ((Table) blocks.getLast()).format());
    }

    @Test void completeNamedParagraphsCannotHideForbiddenAtomicCellPagination() {
        for (var pagination : List.of(new PaginationPolicy(true, false, 2, 3), new PaginationPolicy(false, true, 2, 3))) {
            var named = NOTE.withPagination(pagination);
            var compiler = XmlTemplate.builder().theme(theme().toBuilder().paragraphStyle("atomic-invalid", named).build()).build();
            assertEquals(named, compiler.compile(xml("<p style='atomic-invalid'>Top</p>")).render(Map.of())
                    .paragraphs().getFirst().format());
            rejected(compiler, xml(table("", "<p style='atomic-invalid'>SECRET</p>")), TemplateException.Code.INVALID_VALUE, 1);
            rejected(compiler, xml(table("", "<p style='atomic-invalid' keep-together='false'>SECRET</p>")),
                    TemplateException.Code.INVALID_VALUE, 1);
        }
    }

    @Test void namedInlineKeepsCompleteValuesAndExplicitBlackEmptyRoleAndFalseDoOverride() {
        var document = render(XmlTemplate.builder().theme(theme()), "<p size='18' line-height='36'>Before"
                + "<span style='ink'>Named</span><span style='ink' role='' color='#000000' size='12' underline='false' strike='false'>Override</span>"
                + "<span>Plain</span>After</p>");
        var runs = document.paragraphs().getFirst().inlines();
        assertEquals(INK, runs.get(1).style());
        assertEquals(new InlineStyle("", 12, 0, 2, false, false), runs.get(2).style());
        assertEquals(InlineStyle.of(18), runs.getFirst().style());
        assertEquals(InlineStyle.of(18), runs.get(3).style());
        assertEquals(InlineStyle.of(18), runs.getLast().style());
        assertEquals("report-bold", runs.get(1).style().fontRole());
        rejected(XmlTemplate.builder().theme(theme()).build(), xml("<p><span role='report-bold'>SECRET</span></p>"),
                TemplateException.Code.INVALID_VALUE, 1);
    }

    @Test void spansCanChooseNamesInEveryExistingLegalPositionWithoutChangingTheirContainerFormat() {
        String span = "<span style='ink'>Named</span>";
        var document = render(XmlTemplate.builder().theme(theme()), "<heading level='1'>" + span + "</heading><bullet>" + span
                + "</bullet><numbered list-id='items'>" + span + "</numbered><p><link href='https://example.com/report'>" + span
                + "</link></p>" + table("", span));
        assertEquals(INK, ((Heading) document.blocks().getFirst()).inlines().getFirst().style());
        assertEquals(INK, ((BulletItem) document.blocks().get(1)).inlines().getFirst().style());
        assertEquals(INK, ((NumberedItem) document.blocks().get(2)).inlines().getFirst().style());
        assertEquals(INK, document.paragraphs().getFirst().inlines().getFirst().style());
        assertEquals("https://example.com/report", document.paragraphs().getFirst().inlines().getFirst().hyperlink());
        assertEquals(INK, lastTable(document).rows().getFirst().cells().getFirst().inlines().getFirst().style());
        assertEquals(Heading.defaultFormat(1), ((Heading) document.blocks().getFirst()).format());
    }

    @Test void sixHeadingDefaultsRetainCompletePaginationAndExplicitSizeStillUsesLegacyLeading() {
        var builder = theme().toBuilder();
        for (int level = 1; level <= 6; level++) {
            int size = 20 - level;
            builder.headingFormat(level, format -> format.fontSize(size).lineHeight(40).firstLineIndent(5));
        }
        var theme = builder.build();
        var content = new StringBuilder();
        for (int level = 1; level <= 6; level++) { content.append("<heading level='").append(level).append("'>H</heading>"); }
        content.append("<heading level='2' size='12'>Override</heading>");
        var document = render(XmlTemplate.builder().theme(theme).bodyFormat(ParagraphFormat.of(8)), content.toString());
        for (int level = 1; level <= 6; level++) {
            var heading = assertInstanceOf(Heading.class, document.blocks().get(level - 1));
            assertEquals(theme.headingFormat(level), heading.format());
            assertTrue(heading.format().keepTogether());
            assertEquals(Heading.defaultFormat(level).pagination(), heading.format().pagination());
        }
        assertEquals(theme.headingFormat(2).toBuilder().fontSize(12).lineHeight(18).build(),
                ((Heading) document.blocks().getLast()).format());
    }

    @Test void rootDynamicAndNestedTablesTakeCompleteThemeFormatsAndExplicitSplitHasFinalPriority() {
        var selected = theme().toBuilder().tableFormat(TABLE.toBuilder().allowRowSplit(true).build()).build();
        var document = XmlTemplate.builder().theme(selected).build().compile(xml(table("", "Root")
                + "<rows items='items' as='item' row-split='false'>" + COLUMNS + "<header><cell>H</cell></header>"
                + "<row><cell>${item}</cell></row></rows>"
                + table(" row-split='false'", table(" row-split='false'", "Inner"))))
                .render(Map.of("items", List.of("First", "Second")));
        var root = (Table) document.blocks().getFirst(); var dynamic = (Table) document.blocks().get(1);
        var outer = (Table) document.blocks().getLast(); var inner = (Table) outer.rows().getFirst().cells().getFirst().blocks().getFirst();
        assertEquals(selected.tableFormat(), root.format());
        for (var table : List.of(dynamic, outer, inner)) { assertEquals(TABLE, table.format()); }
        assertEquals(3, dynamic.rows().size());
        assertEquals(CELL, inner.rows().getFirst().cells().getFirst().format());
    }

    @Test void themedSplitCannotSilentlyDisableExistingBlockAndInnerTableRejections() {
        var compiler = XmlTemplate.builder().theme(theme().toBuilder().tableFormat(format -> format.allowRowSplit(true)).build()).build();
        for (String content : List.of(table("", "<p>Block</p>"), table(" row-split='false'", table("", "Inner")),
                "<rows items='items' as='item'>" + COLUMNS + "<row><cell><p>Block</p></cell></row></rows>")) {
            rejected(compiler, xml(content), TemplateException.Code.INVALID_VALUE, 1);
            rejected(compiler, xml("<if test='flag'><then><p>Safe</p></then><else>" + content + "</else></if>"),
                    TemplateException.Code.INVALID_VALUE, 1);
        }
    }

    @Test void namesUseTheirOwnCaseSensitiveNamespacesAndAllowExact64CharacterBoundaries() {
        String longest = "A" + "_".repeat(63);
        var selected = theme().toBuilder().paragraphStyle("shared", NOTE).inlineStyle("shared", INK)
                .paragraphStyle("hyphen-name", NOTE).inlineStyle(longest, INK).build();
        var compiler = XmlTemplate.builder().theme(selected).build();
        var document = compiler.compile(xml("<p style='shared'><span style='shared'>Shared</span></p>"
                + "<p style='hyphen-name'><span style='" + longest + "'>Boundary</span></p>")).render(Map.of());
        assertTrue(document.paragraphs().stream().allMatch(value -> value.format().equals(NOTE)));
        assertTrue(document.paragraphs().stream().allMatch(value -> value.inlines().getFirst().style().equals(INK)));
        for (String invalid : List.of("", " ", "1bad", "a.b", "中文", "a".repeat(65), "Note", "ink", "unknown")) {
            rejected(compiler, xml("<p style='" + invalid + "'>SECRET</p>"), TemplateException.Code.INVALID_VALUE, 1);
        }
        for (String invalid : List.of("", " ", "1bad", "a.b", "a".repeat(65), "Ink", "note", "unknown")) {
            rejected(compiler, xml("<p><span style='" + invalid + "'>SECRET</span></p>"), TemplateException.Code.INVALID_VALUE, 1);
        }
        rejected(XmlTemplate.builder().build(), xml("<p style='SECRET_VALUE'>SECRET_BODY</p>"), TemplateException.Code.INVALID_VALUE, 1);
    }

    @Test void invalidNamesFailAtTheirOwnElementsInUnselectedBranchesAndZeroIterationPrototypes() {
        var compiler = XmlTemplate.builder().theme(theme()).build();
        String invalid = "<p style='SECRET_STYLE'>SECRET_BODY</p>";
        for (String content : List.of(invalid,
                "<if test='flag'><then><p>Safe</p></then><else>\n" + invalid + "</else></if>",
                "<each items='empty' as='item'>\n" + invalid + "</each>",
                "<rows items='empty' as='item'>" + COLUMNS + "<header><cell><span style='SECRET_STYLE'>SECRET_BODY</span></cell></header>"
                        + "<row><cell>Safe</cell></row></rows>",
                "<rows items='empty' as='item'>" + COLUMNS + "<row><cell>\n" + invalid + "</cell></row></rows>",
                table("", table("", "\n" + invalid)))) {
            String source = "<document version='1'>\n" + content + "\n</document>";
            int start = source.indexOf("style='SECRET_STYLE'");
            int line = 1 + (int) source.substring(0, start).chars().filter(value -> value == '\n').count();
            rejected(compiler, source, TemplateException.Code.INVALID_VALUE, line);
        }
        for (String name : List.of("${choice}", "prefix${choice}", "${unterminated")) {
            rejected(compiler, xml("<p style='" + name + "'>SECRET</p>"), TemplateException.Code.INVALID_EXPRESSION, 1);
            rejected(compiler, xml("<p><span style='" + name + "'>SECRET</span></p>"), TemplateException.Code.INVALID_EXPRESSION, 1);
        }
    }

    @Test void styleDoesNotExpandOtherAttributeWhitelistsOrPermitNestedSpans() {
        var compiler = XmlTemplate.builder().theme(theme()).build();
        for (String content : List.of("<heading style='note' level='1'>H</heading>", "<bullet style='note'>B</bullet>",
                "<numbered style='note' list-id='items'>N</numbered>", "<page-break style='note'/>",
                "<p><link href='https://example.com' style='ink'>Link</link></p>",
                "<p><page-number width='20' style='ink'/></p>", "<p><page-count width='20' style='ink'/></p>",
                "<p><page-reference target='x' width='20' style='ink'/></p>",
                "<p><number-reference target='x' width='20' style='ink'/></p>",
                "<table style='note'>" + COLUMNS + "<row><cell>C</cell></row></table>",
                "<rows style='note' items='items' as='item'>" + COLUMNS + "<row><cell>C</cell></row></rows>",
                "<table>" + COLUMNS + "<row><cell style='note'>C</cell></row></table>")) {
            rejected(compiler, xml(content), TemplateException.Code.UNSUPPORTED_ATTRIBUTE, 1);
        }
        rejected(compiler, "<document version='1' style='note'><p>P</p></document>", TemplateException.Code.UNSUPPORTED_ATTRIBUTE, 1);
        rejected(compiler, xml("<p><span style='ink'><span>SECRET</span></span></p>"), TemplateException.Code.UNSUPPORTED_ELEMENT, 1);
        rejected(compiler, xml("<p style='note' class='SECRET'>P</p>"), TemplateException.Code.UNSUPPORTED_ATTRIBUTE, 1);
    }

    @Test void loopBindingKeepsStylesImageUtf16AndLocalAndExternalFieldTargets() throws IOException {
        var resource = image();
        var compiled = XmlTemplate.builder().theme(theme()).image("mark", resource).build().compile(xml("<p id='external'>External</p>"
                + "<each items='items' as='item'><p id='loop' style='note'>${item}<link href='https://example.com/report'>"
                + "<span style='ink'>!</span><image ref='mark' width='8' height='9' alt='Image ${item}'/></link>"
                + "<page-reference target='loop' width='20'/><page-reference target='external' width='20'/></p></each>"));
        var document = compiled.render(Map.of("items", List.of("A😀", "Longer")));
        for (int index = 0; index < 2; index++) {
            String text = index == 0 ? "A😀" : "Longer";
            var paragraph = document.paragraphs().get(index + 1);
            assertEquals("loop@" + index, paragraph.id());
            assertEquals(NOTE, paragraph.format()); assertEquals(text + "!\ufffc\ufffc\ufffc", paragraph.text());
            assertEquals(INK, paragraph.inlines().get(1).style());
            assertEquals("https://example.com/report", paragraph.inlines().get(1).hyperlink());
            assertEquals(text.length() + 1, paragraph.images().getFirst().sourceIndex());
            assertSame(resource, paragraph.images().getFirst().image().resource());
            assertEquals("Image " + text, paragraph.images().getFirst().image().altText());
            assertEquals("https://example.com/report", paragraph.images().getFirst().image().hyperlink());
            assertEquals(List.of(text.length() + 2, text.length() + 3), paragraph.fields().stream().map(Paragraph.FieldSpan::sourceIndex).toList());
            assertEquals(List.of("loop@" + index, "external"), paragraph.fields().stream().map(value -> value.field().targetId()).toList());
            assertTrue(paragraph.fields().stream().allMatch(value -> value.field().style().equals(InlineStyle.of(13))));
        }
        assertEquals(1, compiled.render(Map.of("items", List.of())).blocks().size());
    }

    @Test void dynamicBlockRowsRetainNamedLeafValuesAndTheirIndependentNamespaces() {
        var document = XmlTemplate.builder().theme(theme()).build().compile(xml("<rows id='records' items='items' as='item'>" + COLUMNS
                + "<row><cell><p id='leaf' style='note-cell'><span style='ink'>${item}</span>"
                + "<page-reference target='leaf' width='20'/></p></cell></row></rows>"))
                .render(Map.of("items", List.of("A😀", "B")));
        var rows = lastTable(document).rows();
        for (int index = 0; index < 2; index++) {
            var leaf = (Paragraph) rows.get(index).cells().getFirst().blocks().getFirst();
            assertEquals("leaf@row:records:" + index, leaf.id()); assertEquals(CELL_NOTE, leaf.format());
            assertEquals(INK, leaf.inlines().getFirst().style());
            assertEquals(leaf.id(), leaf.fields().getFirst().field().targetId());
            assertEquals(index == 0 ? 3 : 1, leaf.fields().getFirst().sourceIndex());
        }
    }

    @Test void styledNodesRetainExactSourceAndExpandedBlockBudgets() {
        String source = xml("<p style='note'><span style='ink'>😀</span></p>");
        assertEquals("😀", XmlTemplate.builder().theme(theme()).limits(new XmlTemplate.Limits(source.length(), 3, 3, 1)).build()
                .compile(source).render(Map.of()).paragraphs().getFirst().text());
        for (var limits : List.of(new XmlTemplate.Limits(source.length() - 1, 3, 3, 1),
                new XmlTemplate.Limits(source.length(), 2, 3, 1), new XmlTemplate.Limits(source.length(), 3, 2, 1))) {
            rejected(XmlTemplate.builder().theme(theme()).limits(limits).build(), source, TemplateException.Code.BUDGET_EXCEEDED, 1);
        }
        String repeated = xml("<each items='items' as='item'><p style='note'><span style='ink'>${item}</span></p></each>");
        var data = Map.of("items", List.of("First", "Second"));
        assertEquals(2, XmlTemplate.builder().theme(theme()).bindingLimits(new DocumentTemplate.Limits(1000, 2, 100, 1000, 32)).build()
                .compile(repeated).render(data).blocks().size());
        var bounded = XmlTemplate.builder().theme(theme()).bindingLimits(new DocumentTemplate.Limits(1000, 1, 100, 1000, 32)).build().compile(repeated);
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentTemplate.BindingException.class, () -> bounded.render(data)).code());
    }

    @Test void themedCompileAndBindingCancellationRestoreTheCallingScopeAndBorrowedReaderOwnership() throws IOException {
        var compiler = XmlTemplate.builder().theme(theme()).build();
        String source = xml("<p style='note'><span style='ink'>${value}</span></p>");
        var reader = new StringReader(source) {
            @Override public void close() { throw new AssertionError("Borrowed reader was closed"); }
        };
        var compiled = compiler.compile(reader);
        var options = ExecutionOptions.builder().cancelled(() -> true).build();
        assertEquals(ExecutionException.Code.CANCELLED, assertThrows(ExecutionException.class,
                () -> ExecutionContext.execute(options, () -> compiler.compile(source))).code());
        assertEquals(ExecutionException.Code.CANCELLED, assertThrows(ExecutionException.class,
                () -> ExecutionContext.execute(options, () -> compiled.render(Map.of("value", "SECRET")))).code());
        ExecutionContext.checkpoint();
        assertEquals("OK", compiled.render(Map.of("value", "OK")).paragraphs().getFirst().text());
        assertEquals(NOTE, compiler.compile(source).render(Map.of("value", "Reuse")).paragraphs().getFirst().format());
        assertEquals(-1, reader.read());
    }

    private static void rejected(XmlTemplate compiler, String source, TemplateException.Code code, int line) {
        var failure = assertThrows(TemplateException.class, () -> compiler.compile(source));
        assertEquals(code, failure.code()); assertEquals(line, failure.line()); assertTrue(failure.column() > 0);
        assertFalse(failure.getMessage().contains("SECRET"));
        assertFalse(failure.getMessage().contains("report-bold"));
    }

    private static ImageResource image() throws IOException {
        var output = new ByteArrayOutputStream();
        if (!ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output)) {
            throw new IOException("PNG encoder unavailable");
        }
        return ImageResource.load(output.toByteArray());
    }
}
