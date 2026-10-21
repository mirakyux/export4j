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
import com.mirakyux.export4j.components.Navigation;
import com.mirakyux.export4j.components.TableOfContents;
import com.mirakyux.export4j.components.TextComponent;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.core.ComponentSemantics;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
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
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextLine;
import com.mirakyux.export4j.text.TextOutline;

/** Synthetic, bounded fixtures for semantic tables in short regions, columns and split cells.
 * Writes only project build outputs. Explicit fonts and generated icons have no system fallback.
 */
public final class SemanticTableFlowExample {
    private static final Path OUTPUT = Path.of("examples/build/semantic-table-flow");
    private static final PageSettings PAGE = new PageSettings(new PageSize(420, 360), PageMargins.uniform(24));
    private static final PageSettings COLUMN_PAGE = new PageSettings(new PageSize(440, 420), PageMargins.uniform(24));
    private static final CellFormat CELL = new CellFormat(9, 18, ParagraphAlignment.LEFT);
    private static final TableFormat FORMAT = TableFormat.builder().padding(3).spaceAfter(0).build();
    private static final InlineStyle STYLE = InlineStyle.of(9);
    private SemanticTableFlowExample() { }

    /** Produces three complete PDF fixtures, named static SVG previews and source cross-checks. */
    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var fonts = FontGroup.builder().font(AuthoringExample.face("LiberationSans-Regular.ttf")).build();
        var engine = DocumentEngine.builder(fonts).build();
        var ordinary = rows("region-table", "REG", 18, "region-target");
        var first = Documents.builder().pageSettings(PAGE)
                .component("region-prefix", prefix(372, 92, "REGION-PREFIX / synthetic data"))
                .column(outer -> outer.padding(7).column(inner -> inner.padding(5).semanticTable(ordinary)))
                .build();
        var target = target(PAGE, "region-target", "REGION-TARGET / page 7");
        export("regions", navigation(fonts, "Region table", ordinary.id(), "region-target"),
                sections(PAGE, first, target, 7), List.of(ordinary));

        var columns = rows("column-table", "COL", 23, "column-target");
        var columnBody = Documents.builder().pageSettings(COLUMN_PAGE)
                .component("column-prefix", prefix(392, 120, "COLUMN-PREFIX / short first columns"))
                .component("column-flow", ContinuousColumns.builder(FlowColumn.builder().padding(3)
                        .semanticTable(columns).build()).columns(2).gap(16).build())
                .paragraph("column-end", "COLUMN-END").build();
        export("columns", navigation(fonts, "Column table", columns.id(), "column-target"),
                sections(COLUMN_PAGE, columnBody, target(COLUMN_PAGE, "column-target", "COLUMN-TARGET / page 9"), 9),
                List.of(columns));

        var split = splitTable();
        var splitBody = Documents.builder().pageSettings(PAGE)
                .component("split-anchor", prefix(372, 72, "SPLIT-PREFIX / reference anchor"))
                .column(column -> column.padding(5).semanticTable(split))
                .paragraph("split-end", "SPLIT-END").build();
        export("split", engine, splitBody, List.of(split));
    }

    private static DocumentEngine navigation(FontGroup fonts, String title, String tableId, String targetId) {
        return DocumentEngine.builder(fonts).navigation(Navigation.builder().bookmark(title, tableId, 1)
                .bookmark("Target", targetId, 1)
                .contents(new TableOfContents(true, "Contents", ParagraphStyle.of(9))).build()).build();
    }

    private static SectionedDocument sections(PageSettings settings, Document first, Document target, int restart) {
        return new SectionedDocument(new DocumentMetadata("Semantic table region flow", "mirakyux"), List.of(
                new Section("body", settings, first.blocks(), 0), new Section("target-section", settings, target.blocks(), restart)));
    }

    private static Document target(PageSettings settings, String id, String label) {
        return Documents.builder().pageSettings(settings).bodyFormat(value -> value.fontSize(9).lineHeight(18))
                .paragraph(id, label).build();
    }

    private static BoxComponent prefix(double width, double height, String label) {
        return new BoxComponent(width, height, new TextComponent(label, ParagraphStyle.of(9), RgbColor.BLACK));
    }

    private static Table rows(String id, String prefix, int count, String target) {
        var values = new ArrayList<TableRow>();
        values.add(new TableRow(List.of(header(prefix, target))));
        for (int index = 1; index <= count; index++) {
            values.add(new TableRow(List.of(new TableCell(String.format(Locale.ROOT, "%s-%02d", prefix, index), CELL))));
        }
        return new Table(id, List.of(new ColumnWidth.Fraction(1)), values, 1, FORMAT);
    }

    private static TableCell header(String prefix, String target) {
        return TableCell.mixed(List.of(text("H-" + prefix + " P="), field(InlineField.Kind.PAGE, ""),
                text(" N="), field(InlineField.Kind.PAGES, ""), text(" TO="), field(InlineField.Kind.REFERENCE_PAGE, target)), CELL);
    }

    private static Table splitTable() throws IOException {
        var content = new ArrayList<InlineContent>();
        var image = icon();
        for (int index = 1; index <= 36; index++) {
            if (index > 1) { content.add(text("\n")); }
            content.add(text(String.format(Locale.ROOT, "SPL-%02d", index)));
            if (index == 4 || index == 14 || index == 29) {
                content.add(text(" P=")); content.add(field(InlineField.Kind.PAGE, ""));
                content.add(text(" TO=")); content.add(field(InlineField.Kind.REFERENCE_PAGE, "split-anchor"));
            }
            if (index == 12 || index == 28) {
                content.add(new InlineText(" OPEN" + index + " ", new InlineStyle("", 9, 0x224488, 0, true, false),
                        "https://example.com/flow-text-" + index));
                content.add(new InlineImage(image, 12, 12, 0, "Synthetic blue icon " + index,
                        "https://example.com/flow-image-" + index));
            }
            if (index == 22) { content.add(text(" a\u0301")); }
        }
        return new Table("split-table", List.of(new ColumnWidth.Fraction(1)), List.of(
                new TableRow(List.of(header("SPL", "split-anchor"))),
                new TableRow(List.of(TableCell.mixed(content, CELL)))), 1,
                FORMAT.toBuilder().allowRowSplit(true).build());
    }

    private static InlineText text(String value) { return new InlineText(value, STYLE); }
    private static InlineField field(InlineField.Kind kind, String target) { return new InlineField(kind, target, 14, STYLE); }

    private static ImageResource icon() throws IOException {
        var pixels = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) { pixels.setRGB(x, y, x < 4 ? 0x285080 : 0x64a0c8); }
        }
        var bytes = new ByteArrayOutputStream(); ImageIO.write(pixels, "png", bytes);
        return ImageResource.load(bytes.toByteArray());
    }

    private static void export(String name, DocumentEngine engine, SectionedDocument source, List<Table> tables) throws IOException {
        var result = engine.export(source).backend(new PdfBackend()).to(OUTPUT.resolve(name + ".pdf"));
        outputs(name, result, tables);
    }

    private static void export(String name, DocumentEngine engine, Document source, List<Table> tables) throws IOException {
        var result = engine.export(source).backend(new PdfBackend()).to(OUTPUT.resolve(name + ".pdf"));
        outputs(name, result, tables);
    }

    private static void outputs(String name, ComponentDocumentLayout result, List<Table> tables) throws IOException {
        if (result.diagnostics().stream().anyMatch(item -> item.severity() == TextDiagnostic.Severity.WARNING)) {
            throw new IllegalStateException("unexpected fixture warning");
        }
        // This explicitly static preview excludes links and bookmarks; the final PDF retains them.
        try (var output = Files.newOutputStream(OUTPUT.resolve(name + "-preview.svg"))) {
            new SvgBackend().write(new DisplayDocument(result.display().metadata(), result.display().pages()), output);
        }
        Files.writeString(OUTPUT.resolve(name + "-source.json"), sources(tables));
        Files.writeString(OUTPUT.resolve(name + "-results.json"), manifest(result));
        System.out.println("Semantic table " + name + ": " + result.display().pages().size() + " pages");
    }

    private static String sources(List<Table> tables) {
        var values = new ArrayList<String>();
        for (var table : tables) {
            for (int row = 0; row < table.rows().size(); row++) {
                int column = 0;
                for (var cell : table.rows().get(row).cells()) {
                    values.add("{\"tableId\":" + quoted(table.id()) + ",\"row\":" + row + ",\"column\":" + column
                            + ",\"source\":" + quoted(cell.text()) + ",\"sourceUtf16Length\":" + cell.text().length()
                            + ",\"fieldIndices\":" + cell.fields().stream().map(span -> span.sourceIndex()).toList()
                            + ",\"imageIndices\":" + cell.images().stream().map(span -> span.sourceIndex()).toList() + "}");
                    column += cell.columnSpan();
                }
            }
        }
        return "[" + String.join(",", values) + "]\n";
    }

    private static String manifest(ComponentDocumentLayout result) {
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
                                                y + cell.textOrigin().y() + line.baselineY() - 18 * .8, line.width(), 18) + "}");
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
                            + ",\"fragmentCount\":" + placement.fragmentCount() + ",\"pageIndex\":" + (page.pageIndex() + result.bodyPageOffset())
                            + geometry(x, y, table.width(), table.height()) + ",\"rows\":[" + String.join(",", rows) + "]}");
                }
            }
        }
        return "{\"pages\":" + result.display().pages().size() + ",\"bodyPageOffset\":" + result.bodyPageOffset()
                + ",\"placements\":[" + String.join(",", placements) + "]}\n";
    }

    private static String logical(TextLine line) {
        return line.outlines().isEmpty() ? line.runs().stream().map(run -> run.text()).collect(Collectors.joining())
                : line.outlines().stream().map(TextOutline::logicalText).collect(Collectors.joining());
    }
    private static String geometry(double x, double y, double width, double height) {
        return String.format(Locale.ROOT, ",\"x\":%.6f,\"y\":%.6f,\"width\":%.6f,\"height\":%.6f", x, y, width, height);
    }
    private static String quoted(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + '"';
    }
}
