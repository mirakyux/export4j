// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.ComponentDocumentLayout;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.text.TextOutline;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Element;

/** Original offline XML report with ordered block cells and lexical clone references. */
public final class XmlBlockCellsExample {
    private static final Path OUTPUT = Path.of("examples/build/xml-block-cells");
    private static final PageSettings PAGE = PageSettings.builder().size(420, 300).margins(20).build();
    private static final String REPORT = """
            <document version="1" title="XML block-cell report" author="Export4j example">
              <p id="prefix" size="10" line-height="140" space-after="0">XML-PREFIX</p>
              <each items="cases" as="case">
                <table id="each-outer">
                  <columns><column weight="1"/><column weight="1"/><column weight="1"/></columns>
                  <header><cell column-span="3">${case.code}-HEADER</cell></header>
                  <row>
                    <cell column-span="2" row-span="2" size="8" line-height="14" align="CENTER">
                      <p id="each-before" space-after="2">${case.code} BEFORE INNER=<page-reference target="each-inner" width="12"/> EXT=<page-reference target="prefix" width="12"/></p>
                      <table id="each-inner">
                        <columns><column weight="1"/><column weight="1"/><column weight="1"/></columns>
                        <row><cell column-span="2" row-span="2">${case.code}-INNER-V</cell><cell>${case.code}-INNER-A</cell></row>
                        <row><cell>${case.code}-INNER-B</cell></row>
                        <row><cell column-span="3">${case.code}-INNER-END</cell></row>
                      </table>
                      <p id="each-after" size="10" line-height="18" align="RIGHT" space-before="4">${case.code} AFTER P=<page-number width="12"/> N=<page-count width="12"/>&#10;SELF=<page-reference target="each-before" width="12"/> OUT=<page-reference target="each-outer" width="12"/>&#10;<link href="https://example.com/xml-each-text"><span color="#224488" underline="true">XML-LINK</span></link> <link href="https://example.com/xml-each-image"><image ref="mark" width="10" height="10" alt="Explicit XML marker"/></link></p>
                    </cell>
                    <cell>${case.code}-SIDE-A</cell>
                  </row>
                  <row><cell>${case.code}-SIDE-B</cell></row>
                </table>
              </each>
              <rows id="row-table" items="records" as="record">
                <columns><column weight="1"/><column weight="1"/></columns>
                <header><cell column-span="2">ROWS-HEADER</cell></header>
                <row>
                  <cell column-span="2" size="8" line-height="14" align="CENTER">
                    <p id="row-before" space-after="2">${record.code} BEFORE INNER=<page-reference target="row-inner" width="12"/> EXT=<page-reference target="prefix" width="12"/></p>
                    <table id="row-inner">
                      <columns><column weight="1"/><column weight="1"/></columns>
                      <row><cell row-span="2">${record.code}-INNER-V</cell><cell>${record.code}-INNER-A</cell></row>
                      <row><cell>${record.code}-INNER-B</cell></row>
                    </table>
                    <p id="row-after" size="10" line-height="18" align="RIGHT" space-before="4">${record.code} AFTER P=<page-number width="12"/> N=<page-count width="12"/>&#10;SELF=<page-reference target="row-before" width="12"/> <link href="https://example.com/xml-row-text">ROW-LINK</link> <link href="https://example.com/xml-row-image"><image ref="mark" width="10" height="10" alt="Explicit row marker"/></link></p>
                  </cell>
                </row>
              </rows>
              <p id="end" size="9" line-height="18" space-after="0">XML-END</p>
            </document>
            """;
    // Explicit cell colors preserve meaningful rich runs. DOCX intentionally canonicalizes a
    // sole default black run to plain cell text; that equivalent representation is not record-equal.
    private static final String WORD = """
            <document version="1" title="Static XML native block cells">
              <table id="word-outer">
                <columns><column weight="1"/><column weight="1"/></columns>
                <header><cell column-span="2"><span color="#224488">WORD-HEADER</span></cell></header>
                <row><cell column-span="2" row-span="2" size="8" line-height="14" align="CENTER">
                  <p id="word-before" space-after="2">WORD-BEFORE REF=<page-reference target="word-inner" width="18"/></p>
                  <table id="word-inner">
                    <columns><column weight="1"/><column weight="1"/><column weight="1"/></columns>
                    <row><cell column-span="2" row-span="2"><span color="#224488">WORD-INNER-V</span></cell><cell><span color="#224488">WORD-INNER-A</span></cell></row>
                    <row><cell><span color="#224488">WORD-INNER-B</span></cell></row>
                  </table>
                  <p id="word-after" size="10" line-height="18" align="RIGHT" space-before="4">WORD-AFTER P=<page-number width="18"/> N=<page-count width="18"/> R=<page-reference target="word-before" width="18"/> <link href="https://example.com/xml-word-text">LINK</link><link href="https://example.com/xml-word-image"><image ref="mark" width="10" height="10" alt="Native XML marker"/></link></p>
                </cell></row>
                <row/>
              </table>
            </document>
            """;

    private XmlBlockCellsExample() { }

    /** Writes only self-authored fixtures under the project build directory. */
    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var compiler = XmlTemplate.builder().pageSettings(PAGE)
                .bodyFormat(ParagraphFormat.builder().fontSize(9).lineHeight(16).spaceAfter(0).build())
                .cellFormat(new CellFormat(9, 16, ParagraphAlignment.LEFT))
                .image("mark", AuthoringExample.image().resource()).build();
        var source = compiler.compile(REPORT).render(Map.of("cases", List.of(Map.of("code", "E1"), Map.of("code", "E2")),
                "records", List.of(Map.of("code", "R1"), Map.of("code", "R2"))));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf")))).build();
        var result = engine.export(source).backend(new PdfBackend()).to(OUTPUT.resolve("report.pdf"));
        if (!result.diagnostics().isEmpty()) { throw new IOException("unexpected XML block-cell diagnostics"); }
        try (var output = Files.newOutputStream(OUTPUT.resolve("report-preview.svg"))) {
            new SvgBackend().write(new DisplayDocument(result.display().metadata(), result.display().pages()), output);
        }
        Files.writeString(OUTPUT.resolve("report.xml"), REPORT, StandardCharsets.UTF_8);
        Files.writeString(OUTPUT.resolve("word.xml"), WORD, StandardCharsets.UTF_8);
        Files.writeString(OUTPUT.resolve("source.json"), sources(source), StandardCharsets.UTF_8);
        Files.writeString(OUTPUT.resolve("results.json"), manifest(result, source), StandardCharsets.UTF_8);
        nativeDocx(compiler.compile(WORD).render(Map.of()));
        System.out.println("XML ordered block-cell report: " + result.display().pages().size() + " pages");
    }

    private static String sources(Document source) {
        var nodes = new ArrayList<String>();
        SemanticTree.walk(source.blocks(), visit -> {
            var node = visit.block();
            String detail = "";
            if (node instanceof Paragraph paragraph) {
                detail = ",\"text\":" + quote(paragraph.text()) + ",\"utf16Length\":" + paragraph.text().length()
                        + ",\"size\":" + paragraph.format().fontSize() + ",\"step\":" + paragraph.format().lineHeight()
                        + ",\"align\":" + quote(paragraph.format().alignment().name())
                        + ",\"fieldIndices\":" + paragraph.fields().stream().map(Paragraph.FieldSpan::sourceIndex).toList()
                        + ",\"targets\":[" + paragraph.fields().stream().map(span -> quote(span.field().targetId()))
                                .collect(Collectors.joining(",")) + "]"
                        + ",\"imageIndices\":" + paragraph.images().stream().map(Paragraph.ImageSpan::sourceIndex).toList();
            } else if (node instanceof Table table) {
                detail = ",\"rows\":" + table.rows().size() + ",\"columns\":" + table.columns().size()
                        + ",\"anchors\":[" + table.grid().anchorRows().stream().flatMap(List::stream)
                                .map(anchor -> "[" + anchor.rowIndex() + "," + anchor.columnIndex() + ","
                                        + anchor.cell().columnSpan() + "," + anchor.cell().rowSpan() + "]")
                                .collect(Collectors.joining(",")) + "]";
            }
            nodes.add("{\"id\":" + quote(node.id()) + ",\"kind\":" + quote(node instanceof Table ? "table" : "paragraph")
                    + detail + ",\"path\":[" + visit.cellPath().stream().map(location -> "{\"tableId\":" + quote(location.tableId())
                            + ",\"row\":" + location.rowIndex() + ",\"column\":" + location.columnIndex()
                            + ",\"block\":" + location.blockIndex() + "}").collect(Collectors.joining(",")) + "]}");
        });
        return "{\"nodes\":[" + String.join(",", nodes) + "]}\n";
    }

    private static String manifest(ComponentDocumentLayout result, Document source) {
        var formats = new java.util.HashMap<String, ParagraphFormat>();
        SemanticTree.walk(source.blocks(), visit -> {
            if (visit.block() instanceof Paragraph paragraph) { formats.put(paragraph.id(), paragraph.format()); }
        });
        var tables = new ArrayList<String>(); var lines = new ArrayList<String>();
        for (var page : result.body().pages()) {
            for (var element : page.elements()) {
                if (element instanceof TableFragment table) { collect(table, page.pageIndex(), 0, 0, formats, tables, lines); }
            }
        }
        return "{\"pages\":" + result.display().pages().size() + ",\"tables\":[" + String.join(",", tables)
                + "],\"lines\":[" + String.join(",", lines) + "]}\n";
    }

    private static void collect(TableFragment table, int page, double offsetX, double offsetY,
            Map<String, ParagraphFormat> formats, List<String> tables, List<String> lines) {
        double x = offsetX + table.origin().x(), y = offsetY + table.origin().y();
        tables.add("{\"id\":" + quote(table.blockId()) + ",\"page\":" + page + geometry(x, y, table.width(), table.height())
                + ",\"rows\":" + table.rows().stream().map(row -> row.rowIndex()).toList() + "}");
        for (var row : table.rows()) {
            for (var cell : row.cells()) {
                if (!cell.hasBlocks()) { continue; }
                double cellX = x + cell.textOrigin().x(), cellY = y + cell.textOrigin().y();
                for (var block : cell.blocks()) {
                    if (block instanceof TableFragment nested) { collect(nested, page, cellX, cellY, formats, tables, lines); }
                    else if (block instanceof ParagraphFragment paragraph) {
                        double step = formats.get(paragraph.paragraphId()).lineHeight();
                        int ordinal = 0;
                        for (var line : paragraph.layout().lines()) {
                            String logical = line.outlines().isEmpty() ? line.runs().stream().map(run -> run.text()).collect(Collectors.joining())
                                    : line.outlines().stream().map(TextOutline::logicalText).collect(Collectors.joining());
                            lines.add("{\"id\":" + quote(paragraph.paragraphId()) + ",\"page\":" + page
                                    + ",\"start\":" + line.sourceStart() + ",\"end\":" + line.sourceEnd()
                                    + ",\"logical\":" + quote(logical) + ",\"step\":" + step
                                    + geometry(cellX + paragraph.origin().x() + line.offsetX(),
                                            cellY + paragraph.origin().y() + ordinal++ * step, line.width(), step) + "}");
                        }
                    }
                }
            }
        }
    }

    private static void nativeDocx(Document document) throws IOException {
        var adapter = new DocxAdapter();
        try (var output = Files.newOutputStream(OUTPUT.resolve("blocks.docx"))) { adapter.write(document, output); }
        try (var input = Files.newInputStream(OUTPUT.resolve("blocks.docx"))) {
            var restored = adapter.read(input, DocxAdapter.Policy.STRICT);
            if (!restored.diagnostics().isEmpty() || !document.blocks().equals(restored.document().sections().getFirst().blocks())) {
                throw new IOException("XML-generated ordered blocks failed strict DOCX roundtrip");
            }
        }
        stripMetadata();
        try (var input = Files.newInputStream(OUTPUT.resolve("blocks-standard.docx"))) {
            var restored = adapter.read(input, DocxAdapter.Policy.STRICT, 18);
            var outer = (Table) restored.document().sections().getFirst().blocks().getFirst();
            var blocks = outer.rows().get(1).cells().getFirst().blocks();
            if (!restored.diagnostics().isEmpty() || blocks.size() != 3 || !(blocks.get(1) instanceof Table)
                    || !((Paragraph) blocks.getFirst()).text().startsWith("WORD-BEFORE")
                    || ((Paragraph) blocks.getLast()).fields().size() != 3) {
                throw new IOException("metadata-free XML-generated DOCX lost ordered native blocks");
            }
        }
        Files.writeString(OUTPUT.resolve("docx-read.txt"),
                "XML_BLOCKS_STRICT_ROUNDTRIP_PASSED\nXML_NATIVE_BLOCKS_STRICT_IMPORT_PASSED\n", StandardCharsets.UTF_8);
    }

    private static void stripMetadata() throws IOException {
        try (var input = new ZipFile(OUTPUT.resolve("blocks.docx").toFile());
                var output = new ZipOutputStream(Files.newOutputStream(OUTPUT.resolve("blocks-standard.docx")))) {
            var entries = input.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement(); output.putNextEntry(new ZipEntry(entry.getName()));
                try (var stream = input.getInputStream(entry)) {
                    if (!entry.getName().equals("word/document.xml")) { stream.transferTo(output); }
                    else {
                        try {
                            var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
                            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                            var document = factory.newDocumentBuilder().parse(stream);
                            var elements = document.getElementsByTagName("*");
                            for (int index = 0; index < elements.getLength(); index++) {
                                var element = (Element) elements.item(index);
                                var attributes = element.getAttributes();
                                for (int attribute = attributes.getLength() - 1; attribute >= 0; attribute--) {
                                    var value = attributes.item(attribute);
                                    if ("urn:com.mirakyux.export4j:semantic:1".equals(value.getNamespaceURI())) {
                                        element.removeAttributeNode((org.w3c.dom.Attr) value);
                                    }
                                }
                            }
                            var transformers = TransformerFactory.newInstance();
                            transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                            transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                            transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
                            var transformer = transformers.newTransformer(); transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
                            transformer.transform(new DOMSource(document), new StreamResult(output));
                        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException
                                | javax.xml.transform.TransformerException failure) {
                            throw new IOException("could not prepare standard DOCX fixture", failure);
                        }
                    }
                }
                output.closeEntry();
            }
        }
    }

    private static String geometry(double x, double y, double width, double height) {
        return String.format(Locale.ROOT, ",\"x\":%.6f,\"y\":%.6f,\"width\":%.6f,\"height\":%.6f", x, y, width, height);
    }

    private static String quote(String source) {
        return "\"" + source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                .replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
