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
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
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
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineContent;
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

/** Self-authored semantic column-span fixtures with compact source anchors, fields and standard DOCX.
 * All resources are explicit and generated outputs remain in the project build directory.
 */
public final class ColumnSpanExample {
    private static final Path OUTPUT = Path.of("examples/build/column-spans");
    private static final PageSettings PAGE = PageSettings.builder().size(420, 260).margins(20).build();
    private static final CellFormat CELL = new CellFormat(9, 18, ParagraphAlignment.LEFT);
    private static final InlineStyle STYLE = InlineStyle.of(9);
    private static final TableFormat FORMAT = TableFormat.builder().padding(3).spaceAfter(0).build();
    private static final List<ColumnWidth> WIDTHS = List.of(new ColumnWidth.Fixed(100),
            new ColumnWidth.Fixed(140), new ColumnWidth.Fixed(140));

    private ColumnSpanExample() { }

    /** Produces whole-row, complete-line and short-column PDFs, plus native gridSpan DOCX. */
    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf")))).build();
        var image = icon();
        var prototype = wholeRows(image);
        var bound = DocumentTemplate.builder().metadata(new DocumentMetadata("Semantic column spans", "mirakyux"))
                .pageSettings(PAGE).block(prototype).build().render(Map.of("prefix", "W"));
        var whole = (Table) bound.blocks().getFirst();
        if (whole.rows().getLast().cells().size() != 2 || whole.rows().getLast().cellAtColumn(2) != whole.rows().getLast().cells().getLast()) {
            throw new IOException("template changed compact source anchors");
        }
        export("whole-rows", engine, bound, whole);
        var split = splitRows(image);
        export("split-rows", engine, Document.builder().pageSettings(PAGE).add(split).build(), split);

        var columnCell = new CellFormat(8, 16, ParagraphAlignment.LEFT);
        var columnStyle = InlineStyle.of(8);
        var left = textLines("C", 16, columnStyle);
        var right = textLines("D", 15, columnStyle);
        right.add(text("\nD16 P=", columnStyle));
        right.add(new InlineField(InlineField.Kind.PAGE, "", 10, columnStyle));
        right.add(new InlineText(" L ", new InlineStyle("", 8, 0x224488, 0, true, false), "https://example.com/columns-text"));
        right.add(new InlineImage(image, 10, 10, 0, "Generated column marker", "https://example.com/columns-image"));
        var columns = Table.builder("column-span-table")
                .columns(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1))
                .format(FORMAT.toBuilder().allowRowSplit(true).build())
                .headerCells(TableCell.builder().format(columnCell).columnSpan(3)
                        .content(List.of(text("COL P=", columnStyle), new InlineField(InlineField.Kind.PAGE, "", 10, columnStyle),
                                text(" N=", columnStyle), new InlineField(InlineField.Kind.PAGES, "", 10, columnStyle))).build())
                .row(TableCell.builder().format(columnCell).content(left).build(),
                        TableCell.builder().format(columnCell).columnSpan(2).content(right).build()).build();
        var grid = GridTable.builder().columns(1).allowRowSplit(true).defaults(cell -> cell.padding(4))
                .row(row -> row.semanticTable(columns)).build();
        var columnDocument = Documents.builder().title("Short columns with semantic spans")
                .pageSettings(PageSettings.builder().size(420, 300).margins(20).build())
                .component(new BoxComponent(380, 100, new TextComponent("COLUMN-PREFIX / generated fixture",
                        ParagraphStyle.builder().fontSize(9).lineHeight(18).build(), RgbColor.BLACK)))
                .component(ContinuousColumns.builder(grid).columns(2).gap(16).build()).build();
        export("columns", engine, columnDocument, columns);
        nativeDocx(whole);
        Files.writeString(OUTPUT.resolve("bindings.json"), "{\"javaValueBlock\":true,\"xmlDynamicRows\":true,\"compactAnchors\":true}\n");
    }

    private static Table wholeRows(ImageResource image) {
        var builder = Table.builder("whole-spans").columns(WIDTHS.toArray(ColumnWidth[]::new)).format(FORMAT)
                .headerCells(TableCell.builder().columnSpan(3).format(CELL).content(List.of(text("WHOLE P="),
                        field(InlineField.Kind.PAGE), text(" N="), field(InlineField.Kind.PAGES))).build());
        for (int number = 1; number <= 15; number++) {
            String label = String.format(Locale.ROOT, "${prefix}%02d", number);
            var a = TableCell.builder().format(CELL).text(label + "-A").columnSpan(number % 2 == 1 ? 2 : 1).build();
            var b = TableCell.builder().format(CELL).columnSpan(number % 2 == 1 ? 1 : 2);
            if (number == 15) {
                b.content(List.of(text(label + "-B P="), field(InlineField.Kind.PAGE), text(" N="), field(InlineField.Kind.PAGES),
                        new InlineText(" L ", new InlineStyle("", 9, 0x224488, 0, true, false), "https://example.com/whole-text"),
                        new InlineImage(image, 12, 12, 0, "Generated final anchor", "https://example.com/whole-image")));
            } else { b.text(label + "-B"); }
            builder.row(a, b.build());
        }
        return builder.build();
    }

    private static Table splitRows(ImageResource image) throws IOException {
        String xml = """
                <document version="1" title="XML semantic spans" author="mirakyux">
                  <rows id="split-spans" items="records" as="record" row-split="true">
                    <columns><column width="100"/><column width="140"/><column width="140"/></columns>
                    <header><cell column-span="3">SPLIT P=<page-number width="10"/> N=<page-count width="10"/></cell></header>
                    <row><cell column-span="2">${record.left}</cell><cell>${record.right}<link href="https://example.com/split-text"><span color="#224488" underline="true"> L </span></link><link href="https://example.com/split-image"><image ref="mark" width="12" height="12" alt="Generated XML marker"/></link> P=<page-number width="10"/></cell></row>
                  </rows>
                </document>
                """;
        Files.writeString(OUTPUT.resolve("split-template.xml"), xml, StandardCharsets.UTF_8);
        var template = XmlTemplate.builder().pageSettings(PAGE).cellFormat(CELL).image("mark", image).build()
                .compile(new StringReader(xml));
        var data = Map.of("records", List.of(Map.of("left", labels("S", 22), "right", labels("R", 22))));
        var original = (Table) template.render(data).blocks().getFirst();
        // XML owns semantic content and spans; the Java application chooses the existing padding contract.
        return new Table(original.id(), original.columns(), original.rows(), original.headerRows(),
                FORMAT.toBuilder().allowRowSplit(true).build());
    }

    private static void nativeDocx(Table whole) throws IOException {
        var rejected = new ByteArrayOutputStream();
        var adapter = new DocxAdapter();
        try {
            adapter.write(Document.builder().pageSettings(PAGE).add(whole).build(), rejected);
            throw new IllegalStateException("repeated header PAGE must be rejected");
        } catch (IOException expected) {
            if (!"DOCX repeated table headers cannot preserve per-occurrence PAGE fields".equals(expected.getMessage()) || rejected.size() != 0) {
                throw new IOException("unexpected DOCX repeated-PAGE boundary", expected);
            }
        }
        var rows = new ArrayList<>(whole.rows());
        rows.set(0, new TableRow(List.of(TableCell.builder().text("WORD HEADER").format(CELL).columnSpan(3).build())));
        var wordTable = new Table(whole.id(), whole.columns(), rows, whole.headerRows(), whole.format());
        var wordDocument = Document.builder().pageSettings(PAGE).add(wordTable).build();
        try (var output = Files.newOutputStream(OUTPUT.resolve("spans.docx"))) { adapter.write(wordDocument, output); }
        try (var input = Files.newInputStream(OUTPUT.resolve("spans.docx"))) {
            var imported = adapter.read(input, DocxAdapter.Policy.STRICT);
            var importedTable = (Table) imported.document().sections().getFirst().blocks().getFirst();
            if (!imported.diagnostics().isEmpty() || !wordTable.equals(importedTable)) {
                throw new IOException("standard DOCX semantic span roundtrip differs");
            }
        }
        Files.writeString(OUTPUT.resolve("docx-rejected.txt"), "REPEATED_HEADER_PAGE_REJECTED\n", StandardCharsets.UTF_8);
    }

    private static String labels(String prefix, int count) {
        return IntStream.rangeClosed(1, count).mapToObj(number -> String.format(Locale.ROOT, "%s%02d", prefix, number))
                .collect(Collectors.joining("\n"));
    }

    private static ArrayList<InlineContent> textLines(String prefix, int count, InlineStyle style) {
        return new ArrayList<>(List.of(text(labels(prefix, count), style)));
    }
    private static InlineText text(String value) { return text(value, STYLE); }
    private static InlineText text(String value, InlineStyle style) { return new InlineText(value, style); }
    private static InlineField field(InlineField.Kind kind) { return new InlineField(kind, "", 10, STYLE); }

    private static ImageResource icon() throws IOException {
        var pixels = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) { pixels.setRGB(x, y, x < 4 ? 0x285080 : 0x64a0c8); }
        }
        var bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(pixels, "png", bytes)) { throw new IOException("PNG encoder is unavailable"); }
        return ImageResource.load(bytes.toByteArray());
    }

    private static void export(String name, DocumentEngine engine, Document document, Table source) throws IOException {
        var result = engine.export(document).backend(new PdfBackend()).to(OUTPUT.resolve(name + ".pdf"));
        if (!result.diagnostics().isEmpty()) { throw new IOException("unexpected fixture text diagnostics: " + name); }
        // This separately named preview deliberately contains no PDF interactions.
        try (var output = Files.newOutputStream(OUTPUT.resolve(name + "-preview.svg"))) {
            new SvgBackend().write(new DisplayDocument(result.display().metadata(), result.display().pages()), output);
        }
        Files.writeString(OUTPUT.resolve(name + "-source.json"), sources(source));
        Files.writeString(OUTPUT.resolve(name + "-results.json"), manifest(result, source));
        System.out.println("Semantic spans " + name + ": " + result.display().pages().size() + " pages");
    }

    private static String sources(Table table) {
        var values = new ArrayList<String>();
        for (int row = 0; row < table.rows().size(); row++) {
            int column = 0;
            for (var cell : table.rows().get(row).cells()) {
                values.add("{\"row\":" + row + ",\"column\":" + column + ",\"columnSpan\":" + cell.columnSpan()
                        + ",\"source\":" + quoted(cell.text()) + ",\"sourceUtf16Length\":" + cell.text().length()
                        + ",\"fieldIndices\":" + cell.fields().stream().map(value -> value.sourceIndex()).toList()
                        + ",\"imageIndices\":" + cell.images().stream().map(value -> value.sourceIndex()).toList() + "}");
                column += cell.columnSpan();
            }
        }
        return "[" + String.join(",", values) + "]\n";
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
        for (var row : table.rows()) {
            var cells = new ArrayList<String>();
            for (var cell : row.cells()) {
                var original = source.rows().get(row.rowIndex()).cellAtColumn(cell.columnIndex());
                var lines = new ArrayList<String>();
                for (var line : cell.layout().lines()) {
                    lines.add("{\"start\":" + line.sourceStart() + ",\"end\":" + line.sourceEnd() + ",\"logical\":" + quoted(logical(line))
                            + geometry(x + cell.textOrigin().x() + line.offsetX(), y + cell.textOrigin().y() + line.baselineY()
                                    - cell.layout().lines().getFirst().baselineY(), line.width(), original.format().lineHeight()) + "}");
                }
                cells.add("{\"column\":" + cell.columnIndex() + ",\"columnSpan\":" + original.columnSpan()
                        + ",\"firstLineIndex\":" + cell.firstLineIndex()
                        + geometry(x + cell.bounds().x(), y + cell.bounds().y(), cell.bounds().width(), cell.bounds().height())
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
