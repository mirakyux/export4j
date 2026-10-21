// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
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
import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.BoxComponent;
import com.mirakyux.export4j.components.ComponentDocumentLayout;
import com.mirakyux.export4j.components.ContinuousColumns;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.GridTable;
import com.mirakyux.export4j.components.TextComponent;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.core.ComponentSemantics;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextLine;
import com.mirakyux.export4j.text.TextOutline;

/** Self-authored two-dimensional semantic spans, indivisible row groups and native DOCX vMerge. */
public final class RowSpanExample {
    private static final Path OUTPUT = Path.of("examples/build/row-spans");
    private static final PageSettings PAGE = PageSettings.builder().size(420, 300).margins(20).build();
    private static final CellFormat CELL = new CellFormat(9, 18, ParagraphAlignment.LEFT);
    private static final InlineStyle STYLE = InlineStyle.of(9);
    private static final TableFormat FORMAT = TableFormat.builder().padding(3).spaceAfter(0).build();
    private RowSpanExample() { }

    /** Writes generated-only fixtures with explicit resources and independently checkable source manifests. */
    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf")))).build();
        var image = icon();
        var groups = groups(image);
        export("groups", engine, Document.builder().pageSettings(PAGE).add(groups).build(), groups);
        var columns = columns(image);
        var grid = GridTable.builder().columns(1).allowRowSplit(true).defaults(cell -> cell.padding(4))
                .row(row -> row.semanticTable(columns)).build();
        var columnDocument = Documents.builder().title("Atomic semantic row spans in short columns").pageSettings(PAGE)
                .component(new BoxComponent(380, 100, new TextComponent("ROW-COLUMN-PREFIX / generated fixture",
                        ParagraphStyle.builder().fontSize(9).lineHeight(18).build(), RgbColor.BLACK)))
                .component(ContinuousColumns.builder(grid).columns(2).gap(16).build()).build();
        export("columns", engine, columnDocument, columns);

        var template = template(image);
        export("templates", engine, Document.builder().pageSettings(PAGE).add(template).build(), template);
        nativeDocx(template);
        try {
            var unsupported = new Table(groups.id(), groups.columns(), groups.rows(), groups.headerRows(),
                    groups.format().toBuilder().allowRowSplit(true).build());
            engine.layout(Document.builder().pageSettings(PAGE).add(unsupported).build());
            throw new IllegalStateException("rowSpan with allowRowSplit must be rejected");
        } catch (DocumentLayoutException expected) {
            if (expected.code() != DocumentLayoutException.Code.INVALID_GEOMETRY || expected.rowIndex() != 0 || expected.columnIndex() != 0) { throw expected; }
            Files.writeString(OUTPUT.resolve("row-split-rejected.txt"), "ROWSPAN_ROW_SPLIT_REJECTED\n", StandardCharsets.UTF_8);
        }
    }

    private static Table groups(ImageResource image) {
        var table = Table.builder("row-groups")
                .columns(new ColumnWidth.Fixed(100), new ColumnWidth.Fixed(140), new ColumnWidth.Fixed(70), new ColumnWidth.Fixed(70))
                .format(FORMAT)
                .headerCells(TableCell.builder().format(CELL).columnSpan(2).rowSpan(2)
                        .content(List.of(text("GROUP P="), field(InlineField.Kind.PAGE), text(" N="), field(InlineField.Kind.PAGES))).build(),
                        cell("HEADER-A", 2, 1))
                .headerCells(cell("HEADER-B", 2, 1))
                .row(cell("G01-A", 2, 2), cell("G01-B", 1, 1), cell("G01-C", 1, 2))
                .row(cell("G01-D", 1, 1))
                .row(cell("G02-A1\nG02-A2", 4, 2)).coveredRow();
        for (int group = 3; group <= 6; group++) {
            String prefix = String.format(Locale.ROOT, "G%02d-", group);
            table.row(cell(prefix + "A1\n" + prefix + "A2\n" + prefix + "A3", 1, 3),
                    cell(prefix + "B1\n" + prefix + "B2", 1, 2), cell(prefix + "C", 2, 1))
                    .row(cell(prefix + "D", 2, 1));
            if (group == 6) {
                table.row(TableCell.builder().format(CELL).columnSpan(3).content(List.of(text(prefix + "E P="),
                        field(InlineField.Kind.PAGE), text(" N="), field(InlineField.Kind.PAGES),
                        linkedText(" L ", "groups", STYLE), linkedImage(image, 12, "groups"))).build());
            } else { table.row(cell(prefix + "E", 3, 1)); }
        }
        return table.build();
    }

    private static Table columns(ImageResource image) {
        var format = new CellFormat(8, 16, ParagraphAlignment.LEFT);
        var style = InlineStyle.of(8);
        var table = Table.builder("row-column-groups")
                .columns(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1))
                .format(FORMAT)
                .headerCells(TableCell.builder().format(format).columnSpan(2).rowSpan(2)
                        .content(List.of(new InlineText("COL P=", style), new InlineField(InlineField.Kind.PAGE, "", 10, style),
                                new InlineText(" N=", style), new InlineField(InlineField.Kind.PAGES, "", 10, style))).build(),
                        TableCell.builder().format(format).text("HA").build())
                .headerCells(TableCell.builder().format(format).text("HB").build());
        for (int group = 1; group <= 5; group++) {
            String prefix = String.format(Locale.ROOT, "K%02d-", group);
            table.row(TableCell.builder().format(format).rowSpan(2).text(prefix + "A").build(),
                    TableCell.builder().format(format).columnSpan(2).text(prefix + "B").build());
            if (group == 5) {
                table.row(TableCell.builder().format(format).columnSpan(2).content(List.of(new InlineText(prefix + "C P=", style),
                        new InlineField(InlineField.Kind.PAGE, "", 10, style), new InlineText(" N=", style),
                        new InlineField(InlineField.Kind.PAGES, "", 10, style), linkedText(" L ", "columns", style),
                        linkedImage(image, 10, "columns"))).build());
            } else { table.row(TableCell.builder().format(format).columnSpan(2).text(prefix + "C").build()); }
        }
        return table.build();
    }

    private static Table template(ImageResource image) throws IOException {
        var prototype = Table.builder("template-spans")
                .columns(new ColumnWidth.Fixed(100), new ColumnWidth.Fixed(140), new ColumnWidth.Fixed(140)).format(FORMAT)
                .headerCells(TableCell.builder().format(CELL).columnSpan(3).rowSpan(2).content(List.of(text("TPL P="),
                        field(InlineField.Kind.PAGE), text("\nN="), field(InlineField.Kind.PAGES))).build()).coveredHeaderRow()
                .row(TableCell.builder().format(CELL).columnSpan(2).rowSpan(2)
                        .content(List.of(underlined("JAVA-${label}\nBOUND"))).build(),
                        TableCell.builder().format(CELL).content(List.of(underlined("XML-A"))).build())
                .row(TableCell.builder().format(CELL).content(List.of(underlined("XML-B"))).build())
                .row(TableCell.builder().format(CELL).columnSpan(3).rowSpan(2)
                        .content(List.of(text("ALL-${label} P="), field(InlineField.Kind.PAGE), text("\nN="), field(InlineField.Kind.PAGES),
                                linkedText(" L ", "templates", STYLE), linkedImage(image, 12, "templates"))).build()).coveredRow().build();
        var data = Map.of("label", "TEMPLATE");
        var javaTable = (Table) DocumentTemplate.builder().pageSettings(PAGE).block(prototype).build().render(data).blocks().getFirst();
        String xml = """
                <document version="1" title="Static two-dimensional XML spans" author="mirakyux">
                  <table id="template-spans">
                    <columns><column width="100"/><column width="140"/><column width="140"/></columns>
                    <header><cell column-span="3" row-span="2">TPL P=<page-number width="10"/>
                N=<page-count width="10"/></cell></header>
                    <header/>
                    <row><cell column-span="2" row-span="2"><span underline="true">JAVA-${label}
                BOUND</span></cell><cell><span underline="true">XML-A</span></cell></row>
                    <row><cell><span underline="true">XML-B</span></cell></row>
                    <row><cell column-span="3" row-span="2">ALL-${label} P=<page-number width="10"/>
                N=<page-count width="10"/><link href="https://example.com/templates-text"><span color="#224488" underline="true"> L </span></link><link href="https://example.com/templates-image"><image ref="mark" width="12" height="12" alt="Generated templates marker"/></link></cell></row>
                    <row/>
                  </table>
                </document>
                """;
        Files.writeString(OUTPUT.resolve("spans-template.xml"), xml, StandardCharsets.UTF_8);
        var compiled = XmlTemplate.builder().pageSettings(PAGE).cellFormat(CELL).image("mark", image).build().compile(new StringReader(xml));
        var xmlTable = (Table) compiled.render(data).blocks().getFirst();
        xmlTable = new Table(xmlTable.id(), xmlTable.columns(), xmlTable.rows(), xmlTable.headerRows(), FORMAT);
        if (!javaTable.equals(xmlTable)) { throw new IOException("Java/XML static two-dimensional span binding differs"); }
        Files.writeString(OUTPUT.resolve("bindings.json"), "{\"javaValueBlock\":true,\"xmlStaticTable\":true,\"coveredRows\":true}\n");
        return xmlTable;
    }

    private static void nativeDocx(Table source) throws IOException {
        var rows = new ArrayList<>(source.rows());
        rows.set(0, new TableRow(List.of(cell("WORD HEADER\nCONTINUED", 3, 2))));
        var wordTable = new Table(source.id(), source.columns(), rows, source.headerRows(), source.format());
        var document = Document.builder().pageSettings(PAGE).add(wordTable).build();
        var adapter = new DocxAdapter();
        try (var output = Files.newOutputStream(OUTPUT.resolve("spans.docx"))) { adapter.write(document, output); }
        try (var input = Files.newInputStream(OUTPUT.resolve("spans.docx"))) {
            var imported = adapter.read(input, DocxAdapter.Policy.STRICT);
            var back = (Table) imported.document().sections().getFirst().blocks().getFirst();
            if (!imported.diagnostics().isEmpty() || !wordTable.equals(back)) { throw new IOException("standard vMerge semantic roundtrip differs"); }
        }
        stripPrivateMetadata();
        try (var input = Files.newInputStream(OUTPUT.resolve("spans-standard.docx"))) {
            var imported = adapter.read(input, DocxAdapter.Policy.STRICT, 10);
            var back = (Table) imported.document().sections().getFirst().blocks().getFirst();
            if (!imported.diagnostics().isEmpty() || back.headerRows() != wordTable.headerRows() || !sourceShape(wordTable).equals(sourceShape(back))) {
                throw new IOException("metadata-free native vMerge import changed source anchors");
            }
        }
        Files.writeString(OUTPUT.resolve("docx-standard-read.txt"), "STANDARD_VMERGE_STRICT_IMPORT_PASSED\n", StandardCharsets.UTF_8);
    }

    private static List<List<String>> sourceShape(Table table) {
        return table.grid().anchorRows().stream().map(row -> row.stream().map(anchor -> {
            var cell = anchor.cell();
            return anchor.rowIndex() + ":" + anchor.columnIndex() + ":" + cell.rowSpan() + ":" + cell.columnSpan() + ":"
                    + cell.text() + ":" + cell.fields().stream().map(value -> value.field().kind()).toList();
        }).toList()).toList();
    }

    private static void stripPrivateMetadata() throws IOException {
        try (var input = new ZipFile(OUTPUT.resolve("spans.docx").toFile());
                var output = new ZipOutputStream(Files.newOutputStream(OUTPUT.resolve("spans-standard.docx")))) {
            var entries = input.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                output.putNextEntry(new ZipEntry(entry.getName()));
                try (var bytes = input.getInputStream(entry)) {
                    if (!entry.getName().equals("word/document.xml")) { bytes.transferTo(output); }
                    else {
                        var factory = DocumentBuilderFactory.newInstance();
                        factory.setNamespaceAware(true);
                        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                        var document = factory.newDocumentBuilder().parse(bytes);
                        var elements = document.getElementsByTagName("*");
                        for (int index = 0; index < elements.getLength(); index++) {
                            var attributes = elements.item(index).getAttributes();
                            for (int attribute = attributes.getLength() - 1; attribute >= 0; attribute--) {
                                var value = attributes.item(attribute);
                                if ("urn:com.mirakyux.export4j:semantic:1".equals(value.getNamespaceURI())) { attributes.removeNamedItemNS(value.getNamespaceURI(), value.getLocalName()); }
                            }
                        }
                        document.getDocumentElement().removeAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "od");
                        document.getDocumentElement().removeAttributeNS("http://schemas.openxmlformats.org/markup-compatibility/2006", "Ignorable");
                        var transformers = TransformerFactory.newInstance();
                        transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                        transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                        transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
                        var transform = transformers.newTransformer(); transform.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
                        transform.transform(new DOMSource(document), new StreamResult(output));
                    }
                }
                output.closeEntry();
            }
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException | javax.xml.transform.TransformerException failure) {
            throw new IOException("cannot prepare generated standard-only DOCX fixture", failure);
        }
    }

    private static TableCell cell(String text, int columns, int rows) {
        return TableCell.builder().format(CELL).text(text).columnSpan(columns).rowSpan(rows).build();
    }
    private static InlineText text(String value) { return new InlineText(value, STYLE); }
    private static InlineText underlined(String value) { return new InlineText(value, STYLE.toBuilder().underline(true).build()); }
    private static InlineField field(InlineField.Kind kind) { return new InlineField(kind, "", 10, STYLE); }
    private static InlineText linkedText(String value, String fixture, InlineStyle style) {
        return new InlineText(value, new InlineStyle("", style.fontSize(), 0x224488, 0, true, false),
                "https://example.com/" + fixture + "-text");
    }
    private static InlineImage linkedImage(ImageResource image, double size, String fixture) {
        return new InlineImage(image, size, size, 0, "Generated " + fixture + " marker", "https://example.com/" + fixture + "-image");
    }
    private static ImageResource icon() throws IOException {
        var pixels = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) { for (int x = 0; x < 8; x++) { pixels.setRGB(x, y, x < 4 ? 0x285080 : 0x64a0c8); } }
        var bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(pixels, "png", bytes)) { throw new IOException("PNG encoder is unavailable"); }
        return ImageResource.load(bytes.toByteArray());
    }

    private static void export(String name, DocumentEngine engine, Document document, Table source) throws IOException {
        var result = engine.export(document).backend(new PdfBackend()).to(OUTPUT.resolve(name + ".pdf"));
        if (!result.diagnostics().isEmpty()) { throw new IOException("unexpected fixture text diagnostics: " + name); }
        try (var output = Files.newOutputStream(OUTPUT.resolve(name + "-preview.svg"))) {
            new SvgBackend().write(new DisplayDocument(result.display().metadata(), result.display().pages()), output);
        }
        Files.writeString(OUTPUT.resolve(name + "-source.json"), sources(source));
        Files.writeString(OUTPUT.resolve(name + "-results.json"), manifest(result, source));
        System.out.println("Semantic row spans " + name + ": " + result.display().pages().size() + " pages");
    }

    private static String sources(Table table) {
        var values = new ArrayList<String>();
        var owners = new ArrayList<String>();
        var grid = table.grid();
        for (int row = 0; row < table.rows().size(); row++) {
            for (var anchor : grid.anchorRows().get(row)) {
                var cell = anchor.cell();
                values.add("{\"row\":" + row + ",\"column\":" + anchor.columnIndex() + ",\"columnSpan\":" + cell.columnSpan()
                        + ",\"rowSpan\":" + cell.rowSpan() + ",\"source\":" + quoted(cell.text()) + ",\"sourceUtf16Length\":" + cell.text().length()
                        + ",\"fieldIndices\":" + cell.fields().stream().map(value -> value.sourceIndex()).toList()
                        + ",\"imageIndices\":" + cell.images().stream().map(value -> value.sourceIndex()).toList() + "}");
            }
            var owned = new ArrayList<String>();
            for (int column = 0; column < table.columns().size(); column++) {
                var anchor = grid.ownerAt(row, column); owned.add(quoted(anchor.rowIndex() + ":" + anchor.columnIndex()));
            }
            owners.add("[" + String.join(",", owned) + "]");
        }
        return "{\"physicalRows\":" + table.rows().size() + ",\"physicalColumns\":" + table.columns().size()
                + ",\"headerRows\":" + table.headerRows() + ",\"anchors\":[" + String.join(",", values)
                + "],\"owners\":[" + String.join(",", owners) + "]}\n";
    }

    private static String manifest(ComponentDocumentLayout result, Table source) {
        var values = new ArrayList<String>();
        for (var page : result.body().pages()) {
            for (var element : page.elements()) {
                if (element instanceof TableFragment table) {
                    values.add(placement(source, table, page.pageIndex(), table.origin().x(), table.origin().y(), -1, -1));
                } else if (element instanceof ComponentFragment component) {
                    for (var located : ComponentSemantics.locate(component.layout(), component.origin())) {
                        var placement = located.placement(); var table = placement.fragment();
                        values.add(placement(source, table, page.pageIndex(), located.translation().x() + table.origin().x(),
                                located.translation().y() + table.origin().y(), placement.fragmentIndex(), placement.fragmentCount()));
                    }
                }
            }
        }
        return "{\"pages\":" + result.display().pages().size() + ",\"bodyPageOffset\":" + result.bodyPageOffset()
                + ",\"placements\":[" + String.join(",", values) + "]}\n";
    }

    private static String placement(Table source, TableFragment table, int page, double x, double y, int fragment, int count) {
        var rows = new ArrayList<String>();
        var grid = source.grid();
        for (var row : table.rows()) {
            var cells = new ArrayList<String>();
            for (var cell : row.cells()) {
                var original = grid.anchorAt(row.rowIndex(), cell.columnIndex()).orElseThrow().cell();
                var lines = new ArrayList<String>();
                for (var line : cell.layout().lines()) {
                    lines.add("{\"start\":" + line.sourceStart() + ",\"end\":" + line.sourceEnd() + ",\"logical\":" + quoted(logical(line))
                            + geometry(x + cell.textOrigin().x() + line.offsetX(), y + cell.textOrigin().y() + line.baselineY()
                                    - cell.layout().lines().getFirst().baselineY(), line.width(), original.format().lineHeight()) + "}");
                }
                cells.add("{\"column\":" + cell.columnIndex() + ",\"columnSpan\":" + original.columnSpan() + ",\"rowSpan\":" + original.rowSpan()
                        + ",\"firstLineIndex\":" + cell.firstLineIndex() + geometry(x + cell.bounds().x(), y + cell.bounds().y(), cell.bounds().width(), cell.bounds().height())
                        + ",\"lines\":[" + String.join(",", lines) + "]}");
            }
            rows.add("{\"row\":" + row.rowIndex() + ",\"header\":" + row.header() + ",\"repeatedHeader\":" + row.repeatedHeader()
                    + ",\"continuedRow\":" + row.continuedRow() + ",\"cells\":[" + String.join(",", cells) + "]}");
        }
        return "{\"pageIndex\":" + page + ",\"fragmentIndex\":" + fragment + ",\"fragmentCount\":" + count
                + geometry(x, y, table.width(), table.height()) + ",\"rows\":[" + String.join(",", rows) + "]}";
    }

    private static String logical(TextLine line) {
        return line.outlines().isEmpty() ? line.runs().stream().map(value -> value.text()).collect(Collectors.joining())
                : line.outlines().stream().map(TextOutline::logicalText).collect(Collectors.joining());
    }
    private static String geometry(double x, double y, double width, double height) {
        return String.format(Locale.ROOT, ",\"x\":%.6f,\"y\":%.6f,\"width\":%.6f,\"height\":%.6f", x, y, width, height);
    }
    private static String quoted(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + '"';
    }
}
