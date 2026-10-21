// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.BoxComponent;
import com.mirakyux.export4j.components.ComponentDocumentLayout;
import com.mirakyux.export4j.components.ContinuousColumns;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.FlowColumn;
import com.mirakyux.export4j.components.GridTable;
import com.mirakyux.export4j.components.TextComponent;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.core.ComponentSemantics;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextLine;
import com.mirakyux.export4j.text.TextOutline;

/** Fictional rich content in a long merged cell; output is confined to the project build directory.
 * Explicit fonts, generated artwork and local layout do not fetch external resources.
 */
public final class GridSemanticFlowExample {
    private static final Path OUTPUT = Path.of("examples/build/grid-semantic-flow");
    private static final PageSettings PAGE = new PageSettings(new PageSize(420, 360), PageMargins.uniform(24));
    private static final PageSettings COLUMN_PAGE = new PageSettings(new PageSize(440, 420), PageMargins.uniform(24));
    private static final CellFormat CELL = new CellFormat(9, 18, ParagraphAlignment.LEFT);
    private static final TableFormat TABLE = TableFormat.builder().padding(3).spaceAfter(0).build();
    private static final ParagraphStyle TEXT = ParagraphStyle.builder().fontSize(9).lineHeight(18).build();
    private static final InlineStyle STYLE = InlineStyle.of(9);
    private GridSemanticFlowExample() { }

    /** Produces two complete PDFs, static SVG previews and independently cross-checkable source manifests. */
    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var fonts = FontGroup.builder().font(AuthoringExample.face("LiberationSans-Regular.ttf")).build();
        var bold = FontGroup.builder().font(AuthoringExample.face("LiberationSans-Bold.ttf")).build();
        var engine = DocumentEngine.builder(fonts).fontRole("emphasis", bold).build();
        var icon = icon();
        var regions = tables("REG", icon);
        var document = Documents.builder().title("Fictional merged-cell review").pageSettings(PAGE)
                .component("reg-target", prefix(372, 72, "REG-PREFIX / fictional review"))
                .component("reg-grid", grid("REG", regions))
                .paragraph("REG-END").build();
        export("regions", "REG", engine, document, regions);

        var columns = tables("COL", icon);
        var columnDocument = Documents.builder().title("Fictional merged-cell columns").pageSettings(COLUMN_PAGE)
                .component("col-target", prefix(392, 100, "COL-PREFIX / short first columns"))
                .component("col-grid", ContinuousColumns.builder(grid("COL", columns)).columns(2).gap(16).build())
                .paragraph("COL-END").build();
        export("columns", "COL", engine, columnDocument, columns);
    }

    private static GridTable grid(String prefix, List<Table> tables) {
        var content = FlowColumn.builder().padding(3).gap(3)
                .fixedText("MERGED " + prefix, text -> text.style(TEXT))
                .semanticTable(tables.get(1))
                .fixedText("CELL END " + prefix, text -> text.style(TEXT)).build();
        return GridTable.builder().columns(3, 1).headerRows(1).allowRowSplit(true)
                .defaults(cell -> cell.textStyle(TEXT).padding(4))
                .row(row -> row.semanticTable(tables.getFirst(), cell -> cell.columnSpan(2)))
                .row(row -> row.cell(content, cell -> cell.rowSpan(2)).cell("S-A"))
                .row(row -> row.cell("S-B")).build();
    }

    private static List<Table> tables(String prefix, ImageResource image) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        var outer = new Table(lower + "-grid-header", List.of(new ColumnWidth.Fraction(1)), List.of(
                new TableRow(List.of(TableCell.mixed(List.of(text("GRID " + prefix + " P="), field(InlineField.Kind.PAGE, ""),
                        text(" N="), field(InlineField.Kind.PAGES, "")), CELL)))), 0, TABLE);
        var content = new ArrayList<InlineContent>();
        for (int number = 1; number <= 28; number++) {
            if (number > 1) { content.add(text("\n")); }
            var label = String.format(Locale.ROOT, "%s-%02d", prefix, number);
            content.add(new InlineText(label, number == 7 ? new InlineStyle("emphasis", 9, 0x662244, 0, false, false) : STYLE));
            if (number == 4) { content.add(text(" P=")); content.add(field(InlineField.Kind.PAGE, "")); }
            if (number == 14) { content.add(text(" N=")); content.add(field(InlineField.Kind.PAGES, "")); }
            if (number == 22) { content.add(text(" R=")); content.add(field(InlineField.Kind.REFERENCE_PAGE, lower + "-target")); }
            if (number == 12 || number == 26) {
                content.add(new InlineText(" OPEN" + number + " ", new InlineStyle("", 9, 0x224488, 0, true, false),
                        "https://example.com/grid-" + lower + "-text-" + number));
                content.add(new InlineImage(image, 12, 12, 0, "Fictional blue icon " + number,
                        "https://example.com/grid-" + lower + "-image-" + number));
            }
            if (number == 18) { content.add(text(" a\u0301")); }
        }
        var header = TableCell.mixed(List.of(text("H" + prefix + " P="), field(InlineField.Kind.PAGE, ""),
                text(" N="), field(InlineField.Kind.PAGES, "")), CELL);
        var body = new Table(lower + "-merged-content", List.of(new ColumnWidth.Fraction(1)), List.of(
                new TableRow(List.of(header)), new TableRow(List.of(TableCell.mixed(content, CELL)))), 1,
                TABLE.toBuilder().allowRowSplit(true).build());
        return List.of(outer, body);
    }

    private static BoxComponent prefix(double width, double height, String label) {
        return new BoxComponent(width, height, new TextComponent(label, TEXT, RgbColor.BLACK));
    }
    private static InlineText text(String value) { return new InlineText(value, STYLE); }
    private static InlineField field(InlineField.Kind kind, String target) { return new InlineField(kind, target, 14, STYLE); }

    private static ImageResource icon() throws IOException {
        var pixels = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) { pixels.setRGB(x, y, x < 4 ? 0x285080 : 0x64a0c8); }
        }
        var encoded = new ByteArrayOutputStream();
        if (!ImageIO.write(pixels, "png", encoded)) { throw new IOException("PNG encoder is unavailable"); }
        return ImageResource.load(encoded.toByteArray());
    }

    private static void export(String name, String prefix, DocumentEngine engine, Document document, List<Table> tables) throws IOException {
        var result = engine.export(document).backend(new PdfBackend()).to(OUTPUT.resolve(name + ".pdf"));
        if (result.diagnostics().stream().anyMatch(value -> value.severity() == TextDiagnostic.Severity.WARNING)) {
            throw new IllegalStateException("unexpected fixture warning: " + name);
        }
        // The separately named static preview intentionally excludes PDF interactions.
        try (var output = Files.newOutputStream(OUTPUT.resolve(name + "-preview.svg"))) {
            new SvgBackend().write(new DisplayDocument(result.display().metadata(), result.display().pages()), output);
        }
        Files.writeString(OUTPUT.resolve(name + "-source.json"), sources(tables));
        Files.writeString(OUTPUT.resolve(name + "-results.json"), manifest(prefix, result));
        System.out.println("Merged semantic " + name + ": " + result.display().pages().size() + " pages");
    }

    private static String sources(List<Table> tables) {
        var cells = new ArrayList<String>();
        for (var table : tables) {
            for (int row = 0; row < table.rows().size(); row++) {
                int column = 0;
                for (var cell : table.rows().get(row).cells()) {
                    cells.add("{\"tableId\":" + quoted(table.id()) + ",\"row\":" + row + ",\"column\":" + column
                            + ",\"source\":" + quoted(cell.text()) + ",\"sourceUtf16Length\":" + cell.text().length()
                            + ",\"fieldIndices\":" + cell.fields().stream().map(value -> value.sourceIndex()).toList()
                            + ",\"imageIndices\":" + cell.images().stream().map(value -> value.sourceIndex()).toList() + "}");
                    column += cell.columnSpan();
                }
            }
        }
        return "[" + String.join(",", cells) + "]\n";
    }

    private static String manifest(String prefix, ComponentDocumentLayout result) {
        var placements = new ArrayList<String>();
        for (var page : result.body().pages()) {
            for (var element : page.elements()) {
                if (!(element instanceof ComponentFragment component)) { continue; }
                for (var located : ComponentSemantics.locate(component.layout(), component.origin())) {
                    var placement = located.placement(); var table = placement.fragment();
                    double x = located.translation().x() + table.origin().x();
                    double y = located.translation().y() + table.origin().y();
                    var rows = new ArrayList<String>();
                    for (var row : table.rows()) {
                        var cells = new ArrayList<String>();
                        for (var cell : row.cells()) {
                            var lines = new ArrayList<String>();
                            for (var line : cell.layout().lines()) {
                                lines.add("{\"start\":" + line.sourceStart() + ",\"end\":" + line.sourceEnd()
                                        + ",\"logical\":" + quoted(logical(line))
                                        + geometry(x + cell.textOrigin().x() + line.offsetX(),
                                                y + cell.textOrigin().y() + line.baselineY() - cell.layout().lines().getFirst().baselineY(),
                                                line.width(), 18) + "}");
                            }
                            cells.add("{\"column\":" + cell.columnIndex() + ",\"firstLineIndex\":" + cell.firstLineIndex()
                                    + geometry(x + cell.bounds().x(), y + cell.bounds().y(), cell.bounds().width(), cell.bounds().height())
                                    + ",\"lines\":[" + String.join(",", lines) + "]}");
                        }
                        rows.add("{\"row\":" + row.rowIndex() + ",\"header\":" + row.header()
                                + ",\"repeatedHeader\":" + row.repeatedHeader() + ",\"continuedRow\":" + row.continuedRow()
                                + ",\"cells\":[" + String.join(",", cells) + "]}");
                    }
                    placements.add("{\"tableId\":" + quoted(placement.source().id()) + ",\"fragmentIndex\":" + placement.fragmentIndex()
                            + ",\"fragmentCount\":" + placement.fragmentCount() + ",\"pageIndex\":" + page.pageIndex()
                            + geometry(x, y, table.width(), table.height()) + ",\"rows\":[" + String.join(",", rows) + "]}");
                }
            }
        }
        return "{\"prefix\":" + quoted(prefix) + ",\"pages\":" + result.display().pages().size()
                + ",\"bodyPageOffset\":" + result.bodyPageOffset() + ",\"placements\":[" + String.join(",", placements) + "]}\n";
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
