// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlTemplateTest {
    private static final PageSettings PAGE = new PageSettings(new PageSize(420, 300), PageMargins.uniform(24));

    @Test void compilesSemanticBlocksMetadataExplicitFormatsAndDeterministicUniqueIds() {
        var body = ParagraphFormat.builder().fontSize(11).lineHeight(18).spaceAfter(3).build();
        var compiler = XmlTemplate.builder().pageSettings(PAGE).bodyFormat(body).build();
        String xml = """
                <document version="1" title="Report ${customer.name}" author="${author}">
                  <heading level="2" size="20" line-height="30" align="CENTER" keep-together="true" space-before="4" space-after="8">${customer.name}</heading>
                  <p id="intro" size="13" line-height="22" align="JUSTIFY" keep-together="true" space-before="2" space-after="5">Hello ${customer.name}</p>
                  <bullet size="10" line-height="15" align="RIGHT">First</bullet>
                  <page-break/>
                  <p>Default body</p>
                </document>
                """;
        var data = Map.of("customer", Map.of("name", "Acme"), "author", "Author");
        var rendered = compiler.compile(xml).render(data);
        assertEquals(PAGE, rendered.pageSettings());
        assertEquals("Report Acme", rendered.metadata().title());
        assertEquals("Author", rendered.metadata().author());
        assertEquals(5, rendered.blocks().size());
        var heading = assertInstanceOf(Heading.class, rendered.blocks().getFirst());
        assertEquals(2, heading.level());
        assertEquals("Acme", heading.text());
        assertEquals(20, heading.format().fontSize());
        assertEquals(30, heading.format().lineHeight());
        assertEquals(ParagraphAlignment.CENTER, heading.format().alignment());
        assertTrue(heading.format().keepTogether());
        var paragraph = assertInstanceOf(Paragraph.class, rendered.blocks().get(1));
        assertEquals("intro", paragraph.id());
        assertEquals("Hello Acme", paragraph.text());
        assertEquals(13, paragraph.format().fontSize());
        assertEquals(22, paragraph.format().lineHeight());
        assertEquals(ParagraphAlignment.JUSTIFY, paragraph.format().alignment());
        assertEquals(2, paragraph.format().spaceBefore());
        assertEquals(5, paragraph.format().spaceAfter());
        var bullet = assertInstanceOf(BulletItem.class, rendered.blocks().get(2));
        assertEquals("First", bullet.text());
        assertEquals(ParagraphAlignment.RIGHT, bullet.format().alignment());
        assertInstanceOf(PageBreak.class, rendered.blocks().get(3));
        assertEquals(body, assertInstanceOf(Paragraph.class, rendered.blocks().getLast()).format());
        assertEquals(5, rendered.blocks().stream().map(Block::id).distinct().count());
        assertTrue(rendered.blocks().stream().filter(block -> !block.id().equals("intro"))
                .allMatch(block -> block.id().matches("xml-[0-9]+")));
        assertEquals(rendered, compiler.compile(xml).render(data));
    }

    @Test void conditionsLoopsAndLexicalVariablesBindDataWithoutEvaluatingInjectedMarkupOrExpressions() {
        String xml = """
                <document version="1" title="${title}">
                  <if test="visible"><then>
                    <each items="groups" as="group"><each items="group.items" as="item">
                      <p>${group.name}: ${item.name}</p>
                    </each></each>
                  </then><else><p>Hidden</p></else></if>
                  <p>${item.name}</p>
                </document>
                """;
        var template = compiler().compile(xml);
        String injected = "</p><page-break/><p>${missing}&text";
        var data = Map.of("title", "${notEvaluated}", "visible", true, "item", Map.of("name", "ROOT"),
                "groups", List.of(Map.of("name", "A", "items", List.of(Map.of("name", injected), Map.of("name", "B")))));
        var document = template.render(data);
        assertEquals("${notEvaluated}", document.metadata().title());
        assertEquals(List.of("A: " + injected, "A: B", "ROOT"), document.paragraphs().stream().map(Paragraph::text).toList());
        assertEquals(3, document.blocks().size());
        assertEquals(3, document.blocks().stream().map(Block::id).distinct().count());
        var hidden = template.render(Map.of("title", "T", "visible", false, "item", Map.of("name", "ROOT")));
        assertEquals(List.of("Hidden", "ROOT"), hidden.paragraphs().stream().map(Paragraph::text).toList());
    }

    @Test void dynamicRichImageRowsRetainStylesResourcesAndRecalculateUtf16IndicesAfterBinding() throws IOException {
        var logo = image();
        String xml = """
                <document version="1"><rows id="data" items="items" as="item" row-split="true">
                  <columns><column weight="1"/><column weight="1"/><column width="90"/></columns>
                  <header><cell column-span="2">Name</cell><cell>Count</cell></header>
                  <row><cell column-span="2" size="10" line-height="20" align="CENTER"><span role="bold" color="#224488" size="12" underline="true" strike="false">${item.name}</span><image ref="logo" width="12" height="14" alt="Logo ${item.name}"/>!</cell><cell>${item.count}</cell></row>
                </rows></document>
                """;
        var template = XmlTemplate.builder().image("logo", logo).build().compile(xml);
        var table = assertInstanceOf(Table.class, template.render(Map.of("items", List.of(
                Map.of("name", "A😀", "count", 2), Map.of("name", "Longer", "count", 3)))).blocks().getFirst());
        assertEquals("data", table.id());
        assertEquals(List.of(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1), new ColumnWidth.Fixed(90)), table.columns());
        assertTrue(table.format().allowRowSplit());
        assertEquals(1, table.headerRows());
        assertEquals(3, table.rows().size());
        assertTrue(table.rows().stream().allMatch(row -> row.columnCount() == 3));
        assertEquals(2, table.rows().getFirst().cells().getFirst().columnSpan());
        for (int row = 1; row < table.rows().size(); row++) {
            String name = row == 1 ? "A😀" : "Longer";
            var cell = table.rows().get(row).cells().getFirst();
            assertEquals(2, cell.columnSpan());
            assertEquals(name + "\ufffc!", cell.text());
            assertEquals(10, cell.format().fontSize());
            assertEquals(20, cell.format().lineHeight());
            assertEquals(ParagraphAlignment.CENTER, cell.format().alignment());
            var run = cell.inlines().getFirst();
            assertEquals(name, run.text());
            assertEquals("bold", run.style().fontRole());
            assertEquals(0x224488, run.style().rgb());
            assertEquals(12, run.style().fontSize());
            assertTrue(run.style().underline());
            assertFalse(run.style().strikeThrough());
            assertEquals(name.length(), cell.images().getFirst().sourceIndex());
            var placed = cell.images().getFirst().image();
            assertSame(logo, placed.resource());
            assertEquals("Logo " + name, placed.altText());
            assertEquals(12, placed.width());
            assertEquals(14, placed.height());
            assertEquals("", placed.hyperlink());
            assertTrue(cell.fields().isEmpty());
        }
        assertEquals(List.of("2", "3"), table.rows().subList(1, 3).stream().map(row -> row.cellAtColumn(2).text()).toList());
    }

    @Test void staticTableKeepsHeadersColumnKindsAndConfiguredCellDefaults() {
        var defaults = new CellFormat(9, 17, ParagraphAlignment.RIGHT);
        var template = XmlTemplate.builder().cellFormat(defaults).build().compile("""
                <document version="1"><table row-split="true">
                  <columns><column width="100"/><column weight="3"/></columns>
                  <header><cell>A</cell><cell>B</cell></header>
                  <header><cell>C</cell><cell>D</cell></header>
                  <row><cell>${name}</cell><cell align="LEFT">42</cell></row>
                </table></document>
                """);
        var table = assertInstanceOf(Table.class, template.render(Map.of("name", "Value")).blocks().getFirst());
        assertEquals(2, table.headerRows());
        assertEquals(List.of(new ColumnWidth.Fixed(100), new ColumnWidth.Fraction(3)), table.columns());
        assertEquals(defaults, table.rows().getLast().cells().getFirst().format());
        assertEquals("Value", table.rows().getLast().cells().getFirst().text());
        assertEquals(ParagraphAlignment.LEFT, table.rows().getLast().cells().getLast().format().alignment());
        assertTrue(table.format().allowRowSplit());
    }

    @Test void staticTableBindsFullWidthHeadersAndDifferentColumnSpanAnchorsWithoutPlaceholderCells() {
        var template = compiler().compile("""
                <document version="1"><table>
                  <columns><column width="60"/><column weight="1"/><column weight="2"/></columns>
                  <header><cell column-span="3">${caption}</cell></header>
                  <row><cell column-span="2"><span role="bold" color="#224488">${name}</span></cell><cell>Tail-A</cell></row>
                  <row><cell>First-B</cell><cell column-span="2">${name}</cell></row>
                </table></document>
                """);
        var table = assertInstanceOf(Table.class, template.render(Map.of("caption", "All columns", "name", "A😀")).blocks().getFirst());
        assertEquals(3, table.columns().size());
        assertEquals(1, table.headerRows());
        assertEquals(List.of(1, 2, 2), table.rows().stream().map(row -> row.cells().size()).toList());
        assertTrue(table.rows().stream().allMatch(row -> row.columnCount() == 3));
        assertEquals("All columns", table.rows().getFirst().cellAtColumn(2).text());
        assertEquals(3, table.rows().getFirst().cells().getFirst().columnSpan());
        assertEquals("A😀", table.rows().get(1).cellAtColumn(1).text());
        assertEquals("bold", table.rows().get(1).cells().getFirst().inlines().getFirst().style().fontRole());
        assertEquals(0x224488, table.rows().get(1).cells().getFirst().inlines().getFirst().style().rgb());
        assertEquals("Tail-A", table.rows().get(1).cellAtColumn(2).text());
        assertEquals("First-B", table.rows().getLast().cellAtColumn(0).text());
        assertSame(table.rows().getLast().cellAtColumn(1), table.rows().getLast().cellAtColumn(2));
        assertEquals(2, table.rows().getLast().cells().getLast().columnSpan());
    }

    @Test void columnSpanRequiresAFixedPositiveAsciiDecimalIntegerAndUnknownMergeAttributesStillFail() {
        String columns = "<columns><column weight='1'/><column weight='1'/><column weight='1'/></columns>";
        for (String span : List.of("0", "-1", "+1", "1.0", "1e1", "2147483648", "4294967297", "${span}", "two", "", " 2", "٢")) {
            String invalid = "<cell column-span='" + span + "'>X</cell>";
            for (String element : List.of("table", "rows items='items' as='item'")) {
                String tag = element.startsWith("rows") ? "rows" : "table";
                assertCode(TemplateException.Code.INVALID_VALUE, documentXml("<" + element + ">" + columns
                        + "<row>" + invalid + "</row></" + tag + ">"));
                assertCode(TemplateException.Code.INVALID_VALUE, documentXml("<" + element + ">" + columns
                        + "<header>" + invalid + "</header><row><cell column-span='3'>Valid</cell></row></" + tag + ">"));
            }
        }
        for (String attribute : List.of("rowSpan", "columnSpan", "unknown")) {
            assertCode(TemplateException.Code.UNSUPPORTED_ATTRIBUTE, documentXml("<table>" + columns
                    + "<row><cell " + attribute + "='2'>X</cell></row></table>"));
        }
        assertCode(TemplateException.Code.INVALID_VALUE, documentXml("<if test='flag'><then><p>Safe</p></then><else><table>"
                + columns + "<row><cell column-span='${span}'>X</cell></row></table></else></if>"));
    }

    @Test void everyStaticAndDynamicHeaderAndBodyRowMustExactlyCoverTheDeclaredColumns() {
        String columns = "<columns><column weight='1'/><column weight='1'/><column weight='1'/></columns>";
        String valid = "<cell column-span='2'>A</cell><cell>B</cell>";
        for (String invalid : List.of("", "<cell column-span='2'>Short</cell>",
                "<cell column-span='2'>A</cell><cell column-span='2'>Over</cell>",
                "<cell column-span='2'>A</cell><cell>B</cell><cell>Extra</cell>",
                "<cell column-span='2147483647'>A</cell><cell>Overflow</cell>")) {
            for (String element : List.of("table", "rows items='items' as='item'")) {
                String tag = element.startsWith("rows") ? "rows" : "table";
                assertCode(TemplateException.Code.INVALID_STRUCTURE, documentXml("<" + element + ">" + columns
                        + "<header>" + valid + "</header><row>" + invalid + "</row></" + tag + ">"));
                assertCode(TemplateException.Code.INVALID_STRUCTURE, documentXml("<" + element + ">" + columns
                        + "<header>" + valid + "</header><header>" + invalid + "</header><row>" + valid + "</row></" + tag + ">"));
            }
        }
    }

    @Test void preservesInlineWhitespaceCdataAndOnlyPredefinedOrNumericEntities() {
        var document = compiler().compile("""
                <document version="1"><!-- ignored -->
                <p>  A&#10;<span> B </span><![CDATA[ <C> ]]>&amp;&lt;&gt;&quot;&apos;&#x1F600;  </p>
                <p/><table><columns><column weight="1"/></columns><row><cell/></row></table>
                </document>
                """).render(Map.of());
        assertEquals("  A\n B  <C> &<>\"'😀  ", document.paragraphs().getFirst().text());
        assertEquals("", document.paragraphs().getLast().text());
        assertEquals("", assertInstanceOf(Table.class, document.blocks().getLast()).rows().getFirst().cells().getFirst().text());
    }

    @Test void rejectsExecutablePathsIncludingExpressionsInUnselectedBranchesAtCompileTime() {
        for (String expression : List.of("user.name()", "items[0]", "a + b", "T(java.lang.Runtime)", "a..b", "", "a.")) {
            String xml = "<document version='1'><if test='flag'><then><p>safe</p></then><else><p>${"
                    + expression + "}</p></else></if></document>";
            assertCode(TemplateException.Code.INVALID_EXPRESSION, xml);
        }
        for (String path : List.of("items[0]", "value()", "a..b")) {
            assertCode(TemplateException.Code.INVALID_EXPRESSION,
                    "<document version='1'><each items='" + path + "' as='item'><p>safe</p></each></document>");
        }
        assertCode(TemplateException.Code.INVALID_EXPRESSION, "<document version='1'><p>${unterminated</p></document>");
        assertCode(TemplateException.Code.INVALID_EXPRESSION, "<document version='1' title='${bad()}'><p>safe</p></document>");
        assertCode(TemplateException.Code.INVALID_EXPRESSION,
                "<document version='1'><p>${" + "x".repeat(257) + "}</p></document>");
    }

    @Test void rejectsDuplicateIdsAcrossBranchesAndUnambiguousInvalidExplicitIds() {
        assertCode(TemplateException.Code.INVALID_VALUE,
                "<document version='1'><if test='flag'><then><p id='same'>yes</p></then><else><p id='same'>no</p></else></if></document>");
        for (String id : List.of("x@0", "${name}", "space id", "0number", "a".repeat(129))) {
            assertCode(TemplateException.Code.INVALID_VALUE, "<document version='1'><p id='" + id + "'>X</p></document>");
        }
    }

    @Test void automaticIdsSkipExplicitIdsBeforeAndAfterTheirDeclarationDeterministically() {
        var compiler = compiler();
        for (String content : List.of("<p>Automatic</p><p id='xml-1'>Explicit</p>",
                "<p id='xml-2'>Explicit</p><p>Automatic</p>")) {
            String xml = documentXml(content);
            var document = compiler.compile(xml).render(Map.of());
            assertEquals(2, document.blocks().stream().map(Block::id).distinct().count());
            assertTrue(document.blocks().stream().map(Block::id).allMatch(id -> id.matches("xml-[0-9]+")));
            assertEquals(content.contains("xml-1") ? "xml-1" : "xml-2", document.paragraphs().stream()
                    .filter(paragraph -> paragraph.text().equals("Explicit")).findFirst().orElseThrow().id());
            assertEquals(document, compiler.compile(xml).render(Map.of()));
        }
    }

    @Test void rejectsTableShapeOrderAndDynamicRowAmbiguity() {
        String columns = "<columns><column weight='1'/></columns>";
        for (String table : List.of(
                "<table><row><cell>A</cell></row>" + columns + "</table>",
                "<table>" + columns + columns + "<row><cell>A</cell></row></table>",
                "<table>" + columns + "<row><cell>A</cell></row><header><cell>H</cell></header></table>",
                "<table>" + columns + "<row><cell>A</cell><cell>B</cell></row></table>",
                "<table>" + columns + "<header><cell>H</cell></header></table>",
                "<table><row><cell>A</cell></row></table>",
                "<rows items='items' as='item'>" + columns + "</rows>",
                "<rows items='items' as='item'>" + columns + "<row><cell>A</cell></row><row><cell>B</cell></row></rows>",
                "<rows items='items' as='item'>" + columns + "<row><cell>A</cell></row><header><cell>H</cell></header></rows>")) {
            assertCode(TemplateException.Code.INVALID_STRUCTURE, documentXml(table));
        }
    }

    @Test void dynamicRowsKeepAllConsecutiveHeaderRowsBeforeTheirSingleRowPrototype() {
        String xml = documentXml("<rows items='items' as='item'><columns><column weight='1'/></columns>"
                + "<header><cell>H1</cell></header><header><cell>H2</cell></header><row><cell>${item}</cell></row></rows>");
        var table = assertInstanceOf(Table.class, compiler().compile(xml).render(Map.of("items", List.of("A", "B"))).blocks().getFirst());
        assertEquals(2, table.headerRows());
        assertEquals(List.of("H1", "H2", "A", "B"), table.rows().stream().map(row -> row.cells().getFirst().text()).toList());
    }

    @Test void rejectsConditionalOrderDuplicateBranchesAndTextOutsideInlineContainers() {
        for (String child : List.of(
                "text<p>X</p>",
                "<if test='flag'><else><p>N</p></else><then><p>Y</p></then></if>",
                "<if test='flag'><then><p>Y</p></then><then><p>Y2</p></then></if>",
                "<if test='flag'><then><p>Y</p></then><else><p>N</p></else><else><p>N2</p></else></if>",
                "<if test='flag'><p>Y</p></if>",
                "<page-break>text</page-break>",
                "<p>A<p>B</p></p>",
                "<p><span>A<span>B</span></span></p>")) {
            assertThrows(TemplateException.class, () -> compiler().compile(documentXml(child)));
        }
    }

    @Test void refusesUnsupportedVocabularyAndAttributesInsteadOfSilentlyDroppingContent() {
        for (String element : List.of("html", "markdown", "caption", "link", "field", "unknown")) {
            assertCode(TemplateException.Code.UNSUPPORTED_ELEMENT, documentXml("<" + element + ">text</" + element + ">"));
        }
        for (String attribute : List.of("href", "class", "src", "onclick")) {
            var failure = assertCode(TemplateException.Code.UNSUPPORTED_ATTRIBUTE,
                    documentXml("<p " + attribute + "='SECRET_VALUE'>SECRET_BODY</p>"));
            assertFalse(failure.getMessage().contains("SECRET_VALUE"));
            assertFalse(failure.getMessage().contains("SECRET_BODY"));
        }
        assertCode(TemplateException.Code.UNSUPPORTED_ATTRIBUTE,
                documentXml("<p><span href='https://example.com'>link</span></p>"));
    }

    @Test void validatesNumbersBooleansColorsAndEnumerationsWithoutCoercingInvalidValues() {
        for (String value : List.of("NaN", "Infinity", "-1", "0", "bad")) {
            assertCode(TemplateException.Code.INVALID_VALUE, documentXml("<p size='" + value + "'>X</p>"));
        }
        for (String value : List.of("TRUE", "1", "yes", "")) {
            assertCode(TemplateException.Code.INVALID_VALUE, documentXml("<p keep-together='" + value + "'>X</p>"));
        }
        for (String value : List.of("#abc", "red", "#00112233", "#gg1122")) {
            assertCode(TemplateException.Code.INVALID_VALUE, documentXml("<p><span color='" + value + "'>X</span></p>"));
        }
        assertCode(TemplateException.Code.INVALID_VALUE, documentXml("<p align='middle'>X</p>"));
        assertCode(TemplateException.Code.INVALID_VALUE, documentXml("<heading level='7'>X</heading>"));
        assertCode(TemplateException.Code.INVALID_VALUE, documentXml("<heading level='1'/>"));
        assertCode(TemplateException.Code.INVALID_VALUE, "<document version='2'><p>X</p></document>");
    }

    @Test void namespacesDeclarationsInstructionsDtdAndCustomEntitiesAreForbidden() {
        for (String xml : List.of(
                "<document xmlns='urn:test' version='1'><p>X</p></document>",
                "<document xmlns:unused='urn:test' version='1'><p>X</p></document>",
                "<x:document xmlns:x='urn:test' version='1'><x:p>X</x:p></x:document>",
                "<?target value?><document version='1'><p>X</p></document>",
                "<document version='1'><?target value?><p>X</p></document>",
                "<!DOCTYPE document><document version='1'><p>X</p></document>",
                "<!DOCTYPE document [<!ENTITY custom 'EXPANDED'>]><document version='1'><p>&custom;</p></document>",
                "<!DOCTYPE document [<!ENTITY a '1234567890'><!ENTITY b '&a;&a;&a;&a;'>]><document version='1'><p>&b;</p></document>")) {
            assertCode(TemplateException.Code.FORBIDDEN_XML, xml);
        }
    }

    @Test void externalEntitiesDtdAndXIncludeNeverContactEvenAnAvailableLoopbackEndpoint() throws IOException {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] response = "EXTERNAL_SECRET".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            String uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/external";
            assertCode(TemplateException.Code.FORBIDDEN_XML,
                    "<!DOCTYPE document SYSTEM '" + uri + "'><document version='1'><p>X</p></document>");
            assertCode(TemplateException.Code.FORBIDDEN_XML,
                    "<!DOCTYPE document [<!ENTITY remote SYSTEM '" + uri + "'>]><document version='1'><p>&remote;</p></document>");
            assertCode(TemplateException.Code.FORBIDDEN_XML,
                    "<document version='1' xmlns:xi='http://www.w3.org/2001/XInclude'><xi:include href='" + uri + "'/></document>");
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test void imagesOnlyUseExplicitWhitelistAndBuilderTakesResourceSnapshot() throws IOException {
        var logo = image();
        var builder = XmlTemplate.builder().image("logo", logo);
        var frozen = builder.build();
        builder.image("later", image());
        var document = frozen.compile(documentXml("<p>A<image ref='logo' width='12' height='14' alt='${name}'/>B</p>"))
                .render(Map.of("name", "LOGO"));
        var paragraph = document.paragraphs().getFirst();
        assertEquals("A\ufffcB", paragraph.text());
        assertEquals(1, paragraph.images().getFirst().sourceIndex());
        assertSame(logo, paragraph.images().getFirst().image().resource());
        assertEquals("LOGO", paragraph.images().getFirst().image().altText());
        assertEquals(TemplateException.Code.UNKNOWN_RESOURCE, assertThrows(TemplateException.class,
                () -> frozen.compile(documentXml("<p><image ref='later' width='12' height='14'/></p>"))).code());
        assertEquals(TemplateException.Code.UNKNOWN_RESOURCE, assertThrows(TemplateException.class,
                () -> frozen.compile(documentXml("<p><image ref='missing' width='12' height='14'/></p>"))).code());
        for (String reference : List.of("https://example.com/x.png", "file:///private.png", "../private.png")) {
            assertEquals(TemplateException.Code.INVALID_VALUE, assertThrows(TemplateException.class,
                    () -> frozen.compile(documentXml("<p><image ref='" + reference + "' width='12' height='14'/></p>"))).code());
        }
        for (String container : List.of("heading level='1'", "bullet")) {
            String name = container.split(" ")[0];
            assertEquals(TemplateException.Code.UNSUPPORTED_ELEMENT, assertThrows(TemplateException.class,
                    () -> frozen.compile(documentXml("<" + container + ">X<image ref='logo' width='12' height='14'/></" + name + ">"))).code());
        }
    }

    @Test void inputElementDepthAndUnicodeTextLimitsEnforceTheirExactBoundaries() {
        String xml = documentXml("<p><span>&#x1F600;x</span></p>");
        var exact = new XmlTemplate.Limits(xml.length(), 3, 3, 2);
        assertEquals("😀x", XmlTemplate.builder().limits(exact).build().compile(xml).render(Map.of()).paragraphs().getFirst().text());
        assertEquals("😀x", XmlTemplate.builder().limits(exact).build()
                .compile(documentXml("<p><span>😀x</span></p>")).render(Map.of()).paragraphs().getFirst().text());
        for (var limits : List.of(
                new XmlTemplate.Limits(xml.length() - 1, 3, 3, 2),
                new XmlTemplate.Limits(xml.length(), 2, 3, 2),
                new XmlTemplate.Limits(xml.length(), 3, 2, 2),
                new XmlTemplate.Limits(xml.length(), 3, 3, 1))) {
            assertEquals(TemplateException.Code.BUDGET_EXCEEDED,
                    assertThrows(TemplateException.class, () -> XmlTemplate.builder().limits(limits).build().compile(xml)).code());
        }
    }

    @Test void limitsRejectNonpositiveAndUnboundedRecursionConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new XmlTemplate.Limits(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new XmlTemplate.Limits(1, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new XmlTemplate.Limits(1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new XmlTemplate.Limits(1, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new XmlTemplate.Limits(1, 1, 65, 1));
    }

    @Test void textBudgetIncludesStructuralWhitespaceButExcludesAttributesAndComments() {
        String attributes = "<document version='1' title='" + "T".repeat(100) + "'><!--" + "C".repeat(100) + "--><p>X</p></document>";
        var limits = new XmlTemplate.Limits(1000, 10, 10, 1);
        assertEquals("X", XmlTemplate.builder().limits(limits).build().compile(attributes).render(Map.of()).paragraphs().getFirst().text());
        String whitespace = "<document version='1'> <p>X</p></document>";
        assertEquals(TemplateException.Code.BUDGET_EXCEEDED, assertThrows(TemplateException.class,
                () -> XmlTemplate.builder().limits(limits).build().compile(whitespace)).code());
    }

    @Test void borrowedReadersStayOpenAndOversizedInputStopsReadingAtTheBound() throws IOException {
        var success = new TrackingReader(documentXml("<p>X</p>"));
        assertEquals("X", compiler().compile(success).render(Map.of()).paragraphs().getFirst().text());
        assertFalse(success.closed);
        var malformed = new TrackingReader("<document version='1'><p>broken</document>");
        assertThrows(TemplateException.class, () -> compiler().compile(malformed));
        assertFalse(malformed.closed);
        var oversized = new TrackingReader(documentXml("<p>" + "X".repeat(10_000) + "</p>"));
        var compiler = XmlTemplate.builder().limits(new XmlTemplate.Limits(64, 100, 10, 1000)).build();
        assertEquals(TemplateException.Code.BUDGET_EXCEEDED, assertThrows(TemplateException.class,
                () -> compiler.compile(oversized)).code());
        assertTrue(oversized.charactersRead <= 65);
        assertFalse(oversized.closed);
    }

    @Test void borrowedReaderIoFailuresPropagateWithoutClosingOrMasqueradingAsMalformedXml() {
        var cause = new IOException("Synthetic source failure");
        var closed = new AtomicBoolean();
        Reader failing = new Reader() {
            @Override public int read(char[] buffer, int offset, int length) throws IOException { throw cause; }
            @Override public void close() { closed.set(true); }
        };
        assertSame(cause, assertThrows(IOException.class, () -> compiler().compile(failing)));
        assertFalse(closed.get());
    }

    @Test void compileFailuresHaveUsefulSourceCoordinatesWithoutDocumentPayload() {
        var malformed = assertCode(TemplateException.Code.MALFORMED_XML, "<document version='1'>\n<p>broken</document>");
        assertEquals(2, malformed.line());
        assertTrue(malformed.column() > 0);
        var unsupported = assertCode(TemplateException.Code.UNSUPPORTED_ATTRIBUTE,
                "<document version='1'>\n<p unexpected='SECRET_ATTRIBUTE'>SECRET_BODY</p></document>");
        assertEquals(2, unsupported.line());
        assertTrue(unsupported.column() > 0);
        assertFalse(unsupported.getMessage().contains("SECRET_ATTRIBUTE"));
        assertFalse(unsupported.getMessage().contains("SECRET_BODY"));
    }

    @Test void nestedInvalidDimensionsReportTheirOwnElementLocationWithoutLeakingValues() {
        record Fixture(String xml, int line, String value) { }
        var fixtures = List.of(
                new Fixture("<document version='1'>\n  <table>\n    <columns>\n      <column width='-123.456'/>\n"
                        + "    </columns>\n    <row><cell>SECRET_BODY</cell></row>\n  </table>\n</document>", 4, "-123.456"),
                new Fixture("<document version='1'>\n  <table>\n    <columns><column weight='1'/></columns>\n"
                        + "    <row>\n      <cell size='-234.567'>SECRET_BODY</cell>\n    </row>\n  </table>\n</document>", 5, "-234.567"),
                new Fixture("<document version='1'>\n  <p>\n    <span size='-345.678'>SECRET_BODY</span>\n  </p>\n</document>", 3, "-345.678"));
        for (var fixture : fixtures) {
            var failure = assertCode(TemplateException.Code.INVALID_VALUE, fixture.xml());
            assertEquals(fixture.line(), failure.line());
            assertTrue(failure.column() > 1);
            assertFalse(failure.getMessage().contains(fixture.value()));
            assertFalse(failure.getMessage().contains("SECRET_BODY"));
        }
    }

    @Test void bindingLimitsRemainSeparateFromCompileLimitsAndRejectExpandedBlocksRowsTextAndDepth() {
        String each = documentXml("<each items='items' as='item'><p>${item}</p></each>");
        var data = Map.of("items", List.of("A", "B"));
        var cases = List.of(
                new DocumentTemplate.Limits(1, 100, 100, 100, 10),
                new DocumentTemplate.Limits(100, 1, 100, 100, 10),
                new DocumentTemplate.Limits(100, 100, 100, 1, 10),
                new DocumentTemplate.Limits(100, 100, 100, 100, 1));
        for (var limits : cases) {
            var compiled = XmlTemplate.builder().bindingLimits(limits).build().compile(each);
            assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                    assertThrows(DocumentTemplate.BindingException.class, () -> compiled.render(data)).code());
        }
        String rows = documentXml("<rows items='items' as='item'><columns><column weight='1'/></columns><header><cell>H</cell></header><row><cell>${item}</cell></row></rows>");
        var compiledRows = XmlTemplate.builder().bindingLimits(new DocumentTemplate.Limits(100, 100, 1, 100, 10)).build().compile(rows);
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentTemplate.BindingException.class, () -> compiledRows.render(Map.of("items", List.of("A")))).code());
        assertEquals(DocumentTemplate.BindingException.Code.EMPTY_TABLE,
                assertThrows(DocumentTemplate.BindingException.class, () -> compiler().compile(rows).render(Map.of("items", List.of()))).code());
    }

    @Test void cancellationDuringBorrowedInputRestoresExecutionScopeAndCompilerCanBeReused() {
        var cancelled = new AtomicBoolean();
        var compiler = compiler();
        var source = new StringReader(documentXml("<p>" + "X".repeat(1000) + "</p>"));
        Reader triggering = new Reader() {
            @Override public int read(char[] buffer, int offset, int length) throws IOException {
                int count = source.read(buffer, offset, length);
                cancelled.set(true);
                return count;
            }
            @Override public void close() { throw new AssertionError("Borrowed reader was closed"); }
        };
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(
                ExecutionOptions.builder().cancelled(cancelled::get).build(), () -> compiler.compile(triggering)));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        ExecutionContext.checkpoint();
        assertEquals("OK", compiler.compile(documentXml("<p>OK</p>")).render(Map.of()).paragraphs().getFirst().text());
    }

    private static XmlTemplate compiler() { return XmlTemplate.builder().build(); }
    private static String documentXml(String content) { return "<document version='1'>" + content + "</document>"; }

    private static TemplateException assertCode(TemplateException.Code expected, String xml) {
        var failure = assertThrows(TemplateException.class, () -> compiler().compile(xml));
        assertEquals(expected, failure.code(), () -> "Unexpected code for fixture: " + xml);
        return failure;
    }

    private static ImageResource image() throws IOException {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return ImageResource.load(bytes.toByteArray());
    }

    private static final class TrackingReader extends Reader {
        private final StringReader source;
        private int charactersRead;
        private boolean closed;
        private TrackingReader(String input) { source = new StringReader(input); }
        @Override public int read(char[] buffer, int offset, int length) throws IOException {
            int count = source.read(buffer, offset, length);
            if (count > 0) { charactersRead += count; }
            return count;
        }
        @Override public void close() { closed = true; source.close(); }
    }
}
