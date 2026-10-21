// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.BoxComponent;
import com.mirakyux.export4j.components.ComponentDocumentLayout;
import com.mirakyux.export4j.components.ContinuousColumns;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.SemanticTable;
import com.mirakyux.export4j.components.TextComponent;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.core.ComponentSemantics;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextOutline;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import org.w3c.dom.Element;

/** Self-authored atomic Paragraph/Table/Paragraph cells with separate two-dimensional inner/outer grids. */
public final class BlockCellsExample {
    private static final Path OUTPUT = Path.of("examples/build/block-cells");
    private static final PageSettings PAGE = PageSettings.builder().size(420, 300).margins(20).build();
    private static final CellFormat CELL = new CellFormat(9, 16, ParagraphAlignment.LEFT);
    private static final InlineStyle INK = InlineStyle.of(9);
    private static final ParagraphFormat PARAGRAPH = ParagraphFormat.builder().fontSize(9).lineHeight(16).spaceAfter(0).build();
    private static final TableFormat TABLE = TableFormat.builder().padding(3).spaceAfter(0).build();
    private BlockCellsExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf")))).build();
        var image = image();
        for (String name : List.of("atomic", "columns")) {
            var table = table(name, image);
            var document = Documents.builder().pageSettings(PAGE)
                    .component(new BoxComponent(380, 140, new TextComponent(name.toUpperCase(Locale.ROOT) + "-PREFIX",
                            ParagraphStyle.builder().fontSize(9).lineHeight(16).build(), RgbColor.BLACK)));
            if (name.equals("atomic")) { document.add(table); }
            else { document.component(ContinuousColumns.builder(new SemanticTable(table)).columns(2).gap(16).build()); }
            document.add(new Paragraph(name + "-after", name.toUpperCase(Locale.ROOT) + "-AFTER",
                    ParagraphFormat.builder().fontSize(8).lineHeight(10).spaceAfter(0).build()));
            export(name, engine, document.build(), table);
        }
        nativeDocx(table("word", image));
    }

    private static Table table(String name, ImageResource image) {
        String prefix = name.toUpperCase(Locale.ROOT);
        String innerPrefix = prefix.substring(0, 1) + "I";
        var first = Paragraph.mixed(name + "-p1", List.of(text(prefix + "-P1 REF="),
                reference(name + "-inner")), PARAGRAPH);
        var last = Paragraph.mixed(name + "-p2", List.of(text(prefix + "-P2 P="), field(InlineField.Kind.PAGE),
                text(" N="), field(InlineField.Kind.PAGES), text("\nREF="), reference(name + "-p1"),
                text(" ROOT="), reference(name + "-outer"), text("\n"),
                new InlineText("LINK ", new InlineStyle("", 9, 0x224488, 0, true, false), "https://example.com/" + name + "-text"),
                new InlineImage(image, 10, 10, 0, "Generated block-cell marker", "https://example.com/" + name + "-image")), PARAGRAPH);
        var source = Table.builder(name + "-outer")
                .columns(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1)).format(TABLE)
                .headerCells(TableCell.builder().format(CELL).columnSpan(3).text(prefix + "-HEADER").build())
                .row(TableCell.builder().format(CELL).columnSpan(2).rowSpan(2).paragraph(first)
                        .table(name + "-inner", nested -> nested.columns(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1)).format(TABLE)
                                .row(TableCell.builder().format(CELL).columnSpan(2).rowSpan(2).text(innerPrefix + "-V").build(), cell(innerPrefix + "-A"))
                                .row(cell(innerPrefix + "-B"))
                                .row(TableCell.builder().format(CELL).columnSpan(3).text(innerPrefix + "-E").build()))
                        .paragraph(last).build(), cell(prefix + "-SIDE-A"))
                .row(cell(prefix + "-SIDE-B"));
        for (int index = 1; index <= 5; index++) {
            source.row(TableCell.builder().format(CELL).columnSpan(3).text(prefix + "-ROW-" + index).build());
        }
        return source.build();
    }

    private static TableCell cell(String source) { return new TableCell(source, CELL); }
    private static InlineText text(String source) { return new InlineText(source, INK); }
    private static InlineField field(InlineField.Kind kind) { return new InlineField(kind, "", 10, INK); }
    private static InlineField reference(String id) { return new InlineField(InlineField.Kind.REFERENCE_PAGE, id, 10, INK); }
    private static ImageResource image() throws IOException {
        var pixels = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) { for (int x = 0; x < 8; x++) { pixels.setRGB(x, y, x < 4 ? 0x285080 : 0x64a0c8); } }
        var bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(pixels, "png", bytes)) { throw new IOException("PNG encoder is unavailable"); }
        return ImageResource.load(bytes.toByteArray());
    }

    private static void export(String name, DocumentEngine engine, Document document, Table source) throws IOException {
        var result = engine.export(document).backend(new PdfBackend()).to(OUTPUT.resolve(name + ".pdf"));
        if (!result.diagnostics().isEmpty()) { throw new IOException("unexpected block-cell text diagnostics"); }
        try (var output = Files.newOutputStream(OUTPUT.resolve(name + "-preview.svg"))) {
            new SvgBackend().write(new DisplayDocument(result.display().metadata(), result.display().pages()), output);
        }
        Files.writeString(OUTPUT.resolve(name + "-source.json"), sources(source), StandardCharsets.UTF_8);
        Files.writeString(OUTPUT.resolve(name + "-results.json"), manifest(result), StandardCharsets.UTF_8);
        System.out.println("Semantic block cells " + name + ": " + result.display().pages().size() + " pages");
    }

    private static String sources(Table source) {
        var result = new ArrayList<String>();
        SemanticTree.walk(source, visit -> {
            if (visit.block() instanceof Paragraph paragraph) {
                result.add("{\"id\":" + quote(paragraph.id()) + ",\"text\":" + quote(paragraph.text())
                        + ",\"utf16Length\":" + paragraph.text().length() + ",\"fieldIndices\":"
                        + paragraph.fields().stream().map(Paragraph.FieldSpan::sourceIndex).toList()
                        + ",\"imageIndices\":" + paragraph.images().stream().map(Paragraph.ImageSpan::sourceIndex).toList()
                        + ",\"path\":[" + visit.cellPath().stream().map(location -> "{\"tableId\":" + quote(location.tableId())
                                + ",\"row\":" + location.rowIndex() + ",\"column\":" + location.columnIndex()
                                + ",\"block\":" + location.blockIndex() + "}").collect(Collectors.joining(",")) + "]}");
            }
        });
        return "{\"paragraphs\":[" + String.join(",", result) + "]}\n";
    }

    private static String manifest(ComponentDocumentLayout result) {
        var tables = new ArrayList<String>(); var lines = new ArrayList<String>();
        for (var page : result.body().pages()) {
            for (var element : page.elements()) {
                if (element instanceof TableFragment table) { collect(table, page.pageIndex(), 0, 0, tables, lines); }
                else if (element instanceof ComponentFragment component) {
                    for (var located : ComponentSemantics.locate(component.layout(), component.origin())) {
                        collect(located.placement().fragment(), page.pageIndex(), located.translation().x(), located.translation().y(), tables, lines);
                    }
                }
            }
        }
        return "{\"pages\":" + result.display().pages().size() + ",\"tables\":[" + String.join(",", tables)
                + "],\"lines\":[" + String.join(",", lines) + "]}\n";
    }

    private static void collect(TableFragment table, int page, double offsetX, double offsetY,
            List<String> tables, List<String> lines) {
        double x = offsetX + table.origin().x(), y = offsetY + table.origin().y();
        tables.add("{\"id\":" + quote(table.blockId()) + ",\"page\":" + page + geometry(x, y, table.width(), table.height())
                + ",\"rows\":" + table.rows().stream().map(row -> row.rowIndex()).toList() + "}");
        for (var row : table.rows()) {
            for (var cell : row.cells()) {
                if (!cell.hasBlocks()) { continue; }
                double cellX = x + cell.textOrigin().x(), cellY = y + cell.textOrigin().y();
                for (var block : cell.blocks()) {
                    if (block instanceof TableFragment nested) { collect(nested, page, cellX, cellY, tables, lines); }
                    else if (block instanceof ParagraphFragment paragraph) {
                        int lineIndex = 0;
                        for (var line : paragraph.layout().lines()) {
                            String logical = line.outlines().isEmpty() ? line.runs().stream().map(run -> run.text()).collect(Collectors.joining())
                                    : line.outlines().stream().map(TextOutline::logicalText).collect(Collectors.joining());
                            lines.add("{\"id\":" + quote(paragraph.paragraphId()) + ",\"page\":" + page + ",\"start\":" + line.sourceStart()
                                    + ",\"end\":" + line.sourceEnd() + ",\"logical\":" + quote(logical)
                                    + String.format(Locale.ROOT, ",\"baselineY\":%.6f", cellY + paragraph.origin().y() + line.baselineY())
                                    + geometry(cellX + paragraph.origin().x() + line.offsetX(),
                                            cellY + paragraph.origin().y() + lineIndex++ * 16, line.width(), 16) + "}");
                        }
                    }
                }
            }
        }
    }

    private static void nativeDocx(Table source) throws IOException {
        var tailInner = Table.builder("word-tail-inner").columns(new ColumnWidth.Fraction(1)).format(TABLE).row(cell("WORD-TAIL")).build();
        var tail = Table.builder("word-tail").columns(new ColumnWidth.Fraction(1)).format(TABLE)
                .row(TableCell.builder().format(CELL).blocks(List.of(Paragraph.mixed("word-tail-p",
                        List.of(text("WORD-TAIL-P")), PARAGRAPH), tailInner)).build()).build();
        var document = Document.builder().pageSettings(PAGE).add(source).add(tail).build();
        var adapter = new DocxAdapter();
        try (var output = Files.newOutputStream(OUTPUT.resolve("blocks.docx"))) { adapter.write(document, output); }
        try (var input = Files.newInputStream(OUTPUT.resolve("blocks.docx"))) {
            var result = adapter.read(input, DocxAdapter.Policy.STRICT);
            if (!result.diagnostics().isEmpty() || !document.blocks().equals(result.document().sections().getFirst().blocks())) {
                throw new IOException("ordered standard DOCX block-cell roundtrip differs");
            }
        }
        stripMetadata();
        try (var input = Files.newInputStream(OUTPUT.resolve("blocks-standard.docx"))) {
            var result = adapter.read(input, DocxAdapter.Policy.STRICT, 10);
            var back = (Table) result.document().sections().getFirst().blocks().getFirst();
            var content = back.rows().get(1).cells().getFirst().blocks();
            var tailBack = (Table) result.document().sections().getFirst().blocks().getLast();
            var tailContent = tailBack.rows().getFirst().cells().getFirst().blocks();
            if (!result.diagnostics().isEmpty() || content.size() != 3 || !(content.get(1) instanceof Table)
                    || !((Paragraph) content.getFirst()).text().startsWith("WORD-P1") || tailContent.size() != 3
                    || !(tailContent.get(1) instanceof Table) || !((Paragraph) tailContent.getLast()).text().isEmpty()) {
                throw new IOException("metadata-free nested DOCX source order differs");
            }
        }
        Files.writeString(OUTPUT.resolve("docx-read.txt"), "ORDERED_BLOCKS_STRICT_ROUNDTRIP_PASSED\nMETADATA_FREE_NESTING_STRICT_IMPORT_PASSED\n", StandardCharsets.UTF_8);
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
                        var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
                        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                        var document = factory.newDocumentBuilder().parse(stream);
                        var elements = document.getElementsByTagName("*");
                        for (int index = 0; index < elements.getLength(); index++) {
                            var attributes = elements.item(index).getAttributes();
                            for (int a = attributes.getLength() - 1; a >= 0; a--) {
                                var attribute = attributes.item(a);
                                if ("urn:com.mirakyux.export4j:semantic:1".equals(attribute.getNamespaceURI())) {
                                    ((Element) elements.item(index)).removeAttributeNode((org.w3c.dom.Attr) attribute);
                                }
                            }
                        }
                        var transformers = TransformerFactory.newInstance(); transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                        transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
                        var transformer = transformers.newTransformer(); transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
                        transformer.transform(new DOMSource(document), new StreamResult(output));
                    }
                } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException | javax.xml.transform.TransformerException failure) {
                    throw new IOException("cannot produce metadata-free generated DOCX", failure);
                }
                output.closeEntry();
            }
        }
    }

    private static String geometry(double x, double y, double width, double height) {
        return String.format(Locale.ROOT, ",\"x\":%.6f,\"y\":%.6f,\"width\":%.6f,\"height\":%.6f", x, y, width, height);
    }
    private static String quote(String value) { return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"'; }
}
