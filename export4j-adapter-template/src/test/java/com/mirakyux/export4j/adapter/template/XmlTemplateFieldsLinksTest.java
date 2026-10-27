// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.Table;

class XmlTemplateFieldsLinksTest {
    @Test void fieldsKeepTheirKindsWidthsStylesAndBoundUtf16SourcePositions() {
        var document = compiler().compile(xml("<p size='10'>${prefix}<page-number width='21' role='bold' size='11' color='#224488' underline='true'/>"
                + "/<page-count width='22'/>,<page-reference target='chapter-1' width='23' strike='true'/>"
                + ":<number-reference target='chapter-1' width='24'/></p>"
                + "<numbered id='chapter-1' list-id='chapters' restart='3'>Chapter</numbered>"))
                .render(Map.of("prefix", "A😀"));
        var paragraph = document.paragraphs().getFirst();
        assertEquals("A😀\ufffc/\ufffc,\ufffc:\ufffc", paragraph.text());
        assertEquals(List.of(3, 5, 7, 9), paragraph.fields().stream().map(Paragraph.FieldSpan::sourceIndex).toList());
        assertEquals(List.of(InlineField.Kind.PAGE, InlineField.Kind.PAGES, InlineField.Kind.REFERENCE_PAGE, InlineField.Kind.REFERENCE_NUMBER),
                paragraph.fields().stream().map(span -> span.field().kind()).toList());
        assertEquals(List.of(21.0, 22.0, 23.0, 24.0), paragraph.fields().stream().map(span -> span.field().width()).toList());
        assertEquals(new InlineStyle("bold", 11, 0x224488, 0, true, false), paragraph.fields().getFirst().field().style());
        assertEquals(InlineStyle.of(10), paragraph.fields().get(1).field().style());
        assertTrue(paragraph.fields().get(2).field().style().strikeThrough());
        assertEquals(List.of("", "", "chapter-1", "chapter-1"), paragraph.fields().stream().map(span -> span.field().targetId()).toList());
        assertTrue(paragraph.inlines().stream().allMatch(run -> run.hyperlink().isEmpty()));
        assertInstanceOf(NumberedItem.class, document.blocks().getLast());
    }

    @Test void linkedTextStylesAndRegisteredImagesSurviveParagraphAndDynamicCellBinding() throws IOException {
        var resource = image();
        String link = "<link href='https://example.com/report?a=1&amp;b=2'>${item}<span role='bold' color='#224488'>!</span>"
                + "<image ref='logo' width='12' height='13' alt='Logo ${item}'/></link><page-number width='30'/>";
        var compiled = XmlTemplate.builder().image("logo", resource).build().compile(xml(
                "<each items='items' as='item'><p id='linked'>" + link + "</p></each>"
                + "<rows items='items' as='item' row-split='true'><columns><column weight='1'/><column weight='1'/><column weight='1'/></columns>"
                + "<header><cell column-span='3'><page-count width='30'/></cell></header>"
                + "<row><cell column-span='2'>" + link + "</cell><cell>${item}-tail</cell></row></rows>"));
        var document = compiled.render(Map.of("items", List.of("A😀", "Longer")));
        var table = assertInstanceOf(Table.class, document.blocks().getLast());
        assertTrue(table.format().allowRowSplit());
        assertEquals(1, table.headerRows());
        assertEquals(3, table.rows().getFirst().cells().getFirst().columnSpan());
        assertTrue(table.rows().stream().allMatch(row -> row.columnCount() == 3));
        assertEquals(InlineField.Kind.PAGES, table.rows().getFirst().cells().getFirst().fields().getFirst().field().kind());
        for (int index = 0; index < 2; index++) {
            String value = index == 0 ? "A😀" : "Longer";
            var paragraph = assertInstanceOf(Paragraph.class, document.blocks().get(index));
            var cell = table.rows().get(index + 1).cells().getFirst();
            assertEquals(2, cell.columnSpan());
            assertSame(cell, table.rows().get(index + 1).cellAtColumn(1));
            assertEquals(value + "-tail", table.rows().get(index + 1).cellAtColumn(2).text());
            assertEquals(1, table.rows().get(index + 1).cellAtColumn(2).columnSpan());
            assertEquals(value + "!\ufffc\ufffc", paragraph.text());
            assertEquals(paragraph.text(), cell.text());
            assertEquals(paragraph.inlines(), cell.inlines());
            assertEquals(paragraph.images(), cell.images());
            assertEquals(paragraph.fields(), cell.fields());
            assertEquals(value.length() + 1, paragraph.images().getFirst().sourceIndex());
            assertEquals(value.length() + 2, paragraph.fields().getFirst().sourceIndex());
            assertSame(resource, paragraph.images().getFirst().image().resource());
            assertEquals("Logo " + value, paragraph.images().getFirst().image().altText());
            assertEquals("https://example.com/report?a=1&b=2", paragraph.images().getFirst().image().hyperlink());
            assertTrue(paragraph.inlines().subList(0, 3).stream().allMatch(run -> run.hyperlink().equals("https://example.com/report?a=1&b=2")));
            assertEquals("bold", paragraph.inlines().get(1).style().fontRole());
            assertEquals("", paragraph.inlines().getLast().hyperlink());
        }
    }

    @Test void loopReferencesRelocateCurrentCopyTargetsAndPreserveExternalStaticTargets() throws IOException {
        var document = compiler().compile(xml("<numbered id='static-target' list-id='steps' restart='7'>Target</numbered>"
                + "<each items='items' as='item'><p id='loop'>${item}<page-reference target='static-target' width='20'/>"
                + "<number-reference target='static-target' width='20'/><page-reference target='loop' width='20'/></p></each>"))
                .render(Map.of("items", List.of("A", "B")));
        assertEquals(List.of("static-target", "loop@0", "loop@1"), document.blocks().stream().map(Block::id).toList());
        for (int index = 0; index < document.paragraphs().size(); index++) {
            var paragraph = document.paragraphs().get(index);
            assertEquals(List.of("static-target", "static-target", "loop@" + index),
                    paragraph.fields().stream().map(span -> span.field().targetId()).toList());
        }
        assertFalse(document.blocks().stream().anyMatch(block -> block.id().equals("loop")));
        FontGroup fonts;
        try (var input = XmlTemplateFieldsLinksTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
        var engine = DocumentEngine.builder(fonts).build();
        var display = engine.layout(document).display();
        assertEquals(6, display.extras().links().size());
        assertTrue(display.extras().links().stream().allMatch(link -> link.targetPageIndex() == 0));
        var missing = compiler().compile(xml("<each items='items' as='item'><p id='reference'><page-reference target='absent' width='20'/></p>"
                + "<if test='visible'><then><p id='absent'>Absent</p></then></if></each>"))
                .render(Map.of("items", List.of(true), "visible", false));
        assertEquals("absent@0", missing.paragraphs().getFirst().fields().getFirst().field().targetId());
        assertThrows(IllegalArgumentException.class, () -> engine.layout(missing));
    }

    @Test void numberedItemsRetainListIdentityRestartStyleAndLoopSuffixes() {
        var document = compiler().compile(xml("<numbered id='first' list-id='steps' level='1' restart='3' size='10' align='CENTER'>"
                + "<span role='bold'>${title}</span></numbered>"
                + "<each items='items' as='item'><numbered id='next' list-id='steps'>${item}</numbered></each>"
                + "<numbered id='reset' list-id='steps' restart='8'>Reset</numbered>"))
                .render(Map.of("title", "Start", "items", List.of("A", "B")));
        assertEquals(List.of("first", "next@0", "next@1", "reset"), document.blocks().stream().map(Block::id).toList());
        var items = document.blocks().stream().map(block -> assertInstanceOf(NumberedItem.class, block)).toList();
        assertEquals(List.of("steps", "steps", "steps", "steps"), items.stream().map(NumberedItem::listId).toList());
        assertEquals(List.of(3, 0, 0, 8), items.stream().map(NumberedItem::restartAt).toList());
        assertTrue(items.stream().allMatch(item -> item.level() == 1));
        assertEquals(List.of("Start", "A", "B", "Reset"), items.stream().map(NumberedItem::text).toList());
        assertEquals(10, items.getFirst().format().fontSize());
        assertEquals(ParagraphAlignment.CENTER, items.getFirst().format().alignment());
        assertEquals("bold", items.getFirst().inlines().getFirst().style().fontRole());
    }

    @Test void numberedTargetsResolveThroughTheExistingEngineAndMissingLoopTargetsFail() throws IOException {
        FontGroup fonts;
        try (var input = XmlTemplateFieldsLinksTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
        var engine = DocumentEngine.builder(fonts).build();
        var document = compiler().compile(xml("<numbered id='target' list-id='steps' restart='3'>Anchor</numbered>"
                + "<each items='items' as='item'><numbered list-id='steps'>${item}</numbered></each>"
                + "<numbered list-id='steps' restart='8'>Reset</numbered>"
                + "<p>number=<number-reference target='target' width='20'/> page=<page-reference target='target' width='20'/></p>"))
                .render(Map.of("items", List.of("Alpha", "Beta")));
        var layout = engine.layout(document);
        var text = new StringBuilder();
        for (var page : layout.display().pages()) {
            for (var command : page.commands()) {
                if (command instanceof DrawCommand.GlyphText glyph) { text.append(glyph.run().text()); }
                else if (command instanceof DrawCommand.OutlineText outline) { text.append(outline.logicalText()); }
            }
        }
        assertTrue(text.toString().contains("3. Anchor4. Alpha5. Beta8. Resetnumber=3 page=1"));
        assertEquals(2, layout.display().extras().links().size());
        assertTrue(layout.display().extras().links().stream().allMatch(link -> link.targetPageIndex() == 0));
        var missing = compiler().compile(xml("<each items='items' as='item'><numbered id='target' list-id='steps'>${item}</numbered></each>"
                + "<p><number-reference target='target' width='20'/></p>"))
                .render(Map.of("items", List.of("Only target@0")));
        assertThrows(IllegalArgumentException.class, () -> engine.layout(missing));
    }

    @Test void fieldsAreCompileCheckedInEveryBranchIncludingWidthsStylesAndTargets() {
        for (String width : List.of("0", "-1", "NaN", "Infinity", "3.5e38", "bad", "${width}")) {
            assertRejected(TemplateException.Code.INVALID_VALUE, unselected("<page-number width='" + width + "'/>"));
        }
        assertRejected(TemplateException.Code.INVALID_VALUE, unselected("<page-count/>"));
        for (String target : List.of("target@0", "space id", "0target", "x".repeat(129))) {
            assertRejected(TemplateException.Code.INVALID_VALUE, unselected("<page-reference target='" + target + "' width='20'/>"));
        }
        assertRejected(TemplateException.Code.INVALID_STRUCTURE, unselected("<number-reference width='20'/>"));
        assertRejected(TemplateException.Code.INVALID_STRUCTURE, unselected("<number-reference target='' width='20'/>"));
        assertRejected(TemplateException.Code.INVALID_VALUE, unselected("<page-number width='20' color='red'/>"));
        assertRejected(TemplateException.Code.INVALID_VALUE, unselected("<page-number width='20' size='0'/>"));
        assertRejected(TemplateException.Code.UNSUPPORTED_ATTRIBUTE, unselected("<page-number width='20' target='target'/>"));
        assertRejected(TemplateException.Code.UNSUPPORTED_ATTRIBUTE, unselected("<page-count width='20' instruction='PAGE'/>"));
        assertRejected(TemplateException.Code.INVALID_STRUCTURE, unselected("<page-count width='20'>cache</page-count>"));
    }

    @Test void fieldsLinksAndAtomicContentHaveAnExplicitContainerBoundary() {
        for (String content : List.of(
                "<heading level='1'><page-number width='20'/></heading>",
                "<bullet><page-count width='20'/></bullet>",
                "<numbered list-id='steps'><page-number width='20'/></numbered>",
                "<numbered list-id='steps'><image ref='logo' width='12' height='12'/></numbered>",
                "<p><span><page-number width='20'/></span></p>",
                "<p><field kind='PAGE' width='20'/></p>",
                "<p><link href='https://example.com'><page-number width='20'/></link></p>",
                "<p><link href='https://example.com'><link href='https://example.org'>nested</link></link></p>")) {
            assertRejected(TemplateException.Code.UNSUPPORTED_ELEMENT, xml(content));
        }
        assertRejected(TemplateException.Code.INVALID_STRUCTURE, xml("<p><note-marker width='20'/></p>"));
        assertRejected(TemplateException.Code.INVALID_STRUCTURE, xml("<p><link href='https://example.com'/></p>"));
        assertRejected(TemplateException.Code.UNSUPPORTED_ATTRIBUTE, xml("<p><link href='https://example.com' onclick='execute'>text</link></p>"));
    }

    @Test void staticDestinationsUseExistingUriRulesAndDynamicExpressionsCompileInAllBranches() {
        for (String destination : List.of("javascript:alert(1)", "file:///secret", "ftp://example.com/file", "/relative", "example.com",
                "https://user:password@example.com/report", "https:///path", "mailto:", "https://example.com/&#x7f;", "https://example.com/" + "x".repeat(8192))) {
            assertRejected(TemplateException.Code.INVALID_VALUE, unselected("<link href='" + destination + "'>text</link>"));
        }
        for (String destination : List.of("${url}", "https://example.com/${path}", "https://example.com/$&#123;path}")) {
            assertEquals("Safe", compiler().compile(unselected("<link href='" + destination + "'>text</link>"))
                    .render(Map.of("flag", true)).paragraphs().getFirst().text());
        }
        assertRejected(TemplateException.Code.INVALID_EXPRESSION, unselected("<link href='${unsafe() }'>text</link>"));
        for (String destination : List.of("https://example.com/report", "HTTP://example.com/report", "mailto:docs@example.com")) {
            var paragraph = compiler().compile(xml("<p><link href='" + destination + "'>${value}</link></p>"))
                    .render(Map.of("value", "Text")).paragraphs().getFirst();
            assertEquals(destination, paragraph.inlines().getFirst().hyperlink());
        }
    }

    @Test void dynamicLinksAndTargetsBindInLexicalScopeAndInvalidValuesNeverLeakIntoTheDocument() throws IOException {
        var template = XmlTemplate.builder().image("logo", image()).build().compile(xml("<each items='records' as='record'>"
                + "<p id='target'>TARGET</p><p id='link'><link href='${record.url}'><span color='#224488'>LINK</span>"
                + "<image ref='logo' width='8' height='8'/></link><page-reference target='${record.target}' width='20'/></p></each>"));
        var result = template.render(Map.of("records", List.of(Map.of("url", "https://example.com/a", "target", "target@0"),
                Map.of("url", "mailto:docs@example.com", "target", "target@1"))));
        var first = (Paragraph) result.blocks().get(1); var second = (Paragraph) result.blocks().get(3);
        assertEquals("https://example.com/a", first.inlines().getFirst().hyperlink());
        assertEquals("https://example.com/a", first.images().getFirst().image().hyperlink());
        assertEquals("target@0", first.fields().getFirst().field().targetId());
        assertEquals("mailto:docs@example.com", second.inlines().getFirst().hyperlink());
        assertEquals("target@1", second.fields().getFirst().field().targetId());
        for (String invalid : List.of("javascript:SECRET", "file:///SECRET", "https://user:SECRET@example.com", "/SECRET", "")) {
            var failure = assertThrows(DocumentTemplate.BindingException.class, () -> template.render(Map.of("records",
                    List.of(Map.of("url", invalid, "target", "target@0")))));
            assertEquals(DocumentTemplate.BindingException.Code.INVALID_VALUE, failure.code());
            assertFalse(failure.getMessage().contains("SECRET")); assertEquals(null, failure.getCause());
        }
        assertEquals(result, template.toBuilder().build().render(Map.of("records", List.of(
                Map.of("url", "https://example.com/a", "target", "target@0"), Map.of("url", "mailto:docs@example.com", "target", "target@1")))));
    }

    @Test void numberingRejectsMissingDynamicInvalidOrUnsupportedConfigurationBeforeBinding() {
        assertRejected(TemplateException.Code.INVALID_STRUCTURE, xml("<numbered>Text</numbered>"));
        for (String list : List.of("${list}", "space id", "steps@0")) {
            assertRejected(TemplateException.Code.INVALID_VALUE, xml("<numbered list-id='" + list + "'>Text</numbered>"));
        }
        for (String level : List.of("0", "10", "2147483648", "${level}")) {
            assertRejected(TemplateException.Code.INVALID_VALUE, xml("<numbered list-id='steps' level='" + level + "'>Text</numbered>"));
        }
        for (String restart : List.of("-1", "2147483648", "bad", "${restart}")) {
            assertRejected(TemplateException.Code.INVALID_VALUE, xml("<numbered list-id='steps' restart='" + restart + "'>Text</numbered>"));
        }
        assertRejected(TemplateException.Code.INVALID_VALUE, xml("<numbered id='same' list-id='steps'>A</numbered><p id='same'>B</p>"));
        var document = compiler().compile(xml("<p>Automatic</p><numbered id='xml-1' list-id='steps'>Explicit</numbered>"))
                .render(Map.of());
        assertEquals(List.of("xml-2", "xml-1"), document.blocks().stream().map(Block::id).toList());
    }

    @Test void fieldAndLinkFailuresKeepTheirOwnSourceCoordinatesAndHidePayloads() {
        for (String element : List.of("<page-number width='-123.456'/>", "<page-reference target='SECRET TARGET' width='20'/>",
                "<link href='file:///SECRET_PATH'>SECRET_BODY</link>")) {
            var failure = assertThrows(TemplateException.class, () -> compiler().compile("<document version='1'>\n<p>\n  " + element + "\n</p>\n</document>"));
            assertEquals(3, failure.line());
            assertTrue(failure.column() > 1);
            assertFalse(failure.getMessage().contains("SECRET"));
            assertFalse(failure.getMessage().contains("123.456"));
        }
    }

    @Test void newInlineNodesRemainSubjectToSourceVisitedWorkAndGeneratedStringBudgets() {
        var sourceLimited = XmlTemplate.builder().limits(new XmlTemplate.Limits(1000, 3, 32, 1000)).build();
        assertEquals(TemplateException.Code.BUDGET_EXCEEDED, assertThrows(TemplateException.class,
                () -> sourceLimited.compile(xml("<p><page-number width='20'/><page-count width='20'/></p>"))).code());
        var workLimited = XmlTemplate.builder().bindingLimits(new DocumentTemplate.Limits(8, 100, 100, 1000, 32)).build()
                .compile(xml("<p>" + "<page-number width='20'/>".repeat(8) + "</p>"));
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentTemplate.BindingException.class, () -> workLimited.render(Map.of())).code());
        var textLimited = XmlTemplate.builder().bindingLimits(new DocumentTemplate.Limits(100, 100, 100, 20, 32)).build()
                .compile(xml("<p><link href='https://example.com/report'>X</link></p>"));
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentTemplate.BindingException.class, () -> textLimited.render(Map.of())).code());
        var fields = "<page-number width='20'/>".repeat(1025);
        assertRejected(TemplateException.Code.INVALID_VALUE, xml("<p>" + fields + "</p>"));
    }

    @Test void evenReachableExternalLinksRemainPassiveDuringCompileAndBinding() throws IOException {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            var resource = image();
            String destination = "http://127.0.0.1:" + server.getAddress().getPort() + "/report";
            var document = XmlTemplate.builder().image("logo", resource).build().compile(xml("<p><link href='${destination}'>"
                    + "${text}<image ref='logo' width='12' height='12' alt='${text}'/></link><page-number width='20'/></p>"))
                    .render(Map.of("text", "Text", "destination", destination));
            var paragraph = document.paragraphs().getFirst();
            assertEquals(destination, paragraph.inlines().getFirst().hyperlink());
            assertEquals(destination, paragraph.images().getFirst().image().hyperlink());
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }

    private static XmlTemplate compiler() { return XmlTemplate.builder().build(); }
    private static String xml(String body) { return "<document version='1'>" + body + "</document>"; }
    private static String unselected(String inline) {
        return xml("<if test='flag'><then><p>Safe</p></then><else><p>" + inline + "</p></else></if>");
    }
    private static void assertRejected(TemplateException.Code expected, String source) {
        var failure = assertThrows(TemplateException.class, () -> compiler().compile(source));
        assertEquals(expected, failure.code());
    }
    private static ImageResource image() throws IOException {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        return ImageResource.load(output.toByteArray());
    }
}
