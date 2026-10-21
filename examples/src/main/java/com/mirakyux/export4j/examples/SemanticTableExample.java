// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.ComponentDocumentLayout;
import com.mirakyux.export4j.components.ContinuousColumns;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.FlowColumn;
import com.mirakyux.export4j.components.Navigation;
import com.mirakyux.export4j.components.SemanticTable;
import com.mirakyux.export4j.components.Spacer;
import com.mirakyux.export4j.components.TableOfContents;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.core.ComponentSemantics;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ImageResource;
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
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextOutline;

/** Synthetic semantic tables retain fields, image/text links and source cells through nested components. */
public final class SemanticTableExample {
    private static final Path OUTPUT = Path.of("examples/build/semantic-tables");
    private static final PageSettings PAGE = new PageSettings(new PageSize(420, 360), PageMargins.uniform(24));
    private static final CellFormat CELL = new CellFormat(10, 24, ParagraphAlignment.LEFT);
    private SemanticTableExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var fonts = FontGroup.builder().font(AuthoringExample.face("LiberationSans-Regular.ttf")).build();
        var bold = FontGroup.builder().font(AuthoringExample.face("LiberationSans-Bold.ttf")).build();
        var image = image();
        var firstTable = table("nested-a", "NESTED-A", "target", image);
        var secondTable = table("nested-b", "NESTED-B", "nested-a", image);
        var rowTable = Table.builder("nested-row").cellFormat(CELL).row(TableCell.mixed(List.of(
                text("ROW PAGE="), field(InlineField.Kind.PAGE, ""), text("\nTO="),
                field(InlineField.Kind.REFERENCE_PAGE, "nested-b")), CELL)).build();
        var first = Documents.builder().pageSettings(PAGE).bodyFormat(format -> format.fontSize(10).lineHeight(18))
                .paragraph("intro", "Nested semantic tables / final fields and links")
                .column(column -> column.padding(7).gap(6)
                        .column(inner -> inner.padding(5).semanticTable(firstTable))
                        .row(row -> row.padding(3).gap(8).text("Atomic row / semantic table")
                                .add(new SemanticTable(rowTable))))
                .paragraph("first-end", "FIRST-END").build();
        var second = Documents.builder().pageSettings(PAGE).bodyFormat(format -> format.fontSize(10).lineHeight(18))
                .paragraph("target", "TARGET-B / displayed page 7")
                .component("second-columns", ContinuousColumns.builder(FlowColumn.builder().padding(3)
                                .add(new Spacer(260)).semanticTable(secondTable).build())
                        .columns(2).gap(12).build())
                .paragraph("second-end", "SECOND-END").build();
        var source = new SectionedDocument(new DocumentMetadata("Nested semantic tables", "mirakyux"), List.of(
                new Section("first", PAGE, first.blocks(), 0), new Section("second", PAGE, second.blocks(), 7)));
        var navigation = Navigation.builder().bookmark("Nested A", "nested-a", 1).bookmark("Nested B", "nested-b", 1)
                .contents(new TableOfContents(true, "Contents", ParagraphStyle.of(10))).build();
        var engine = DocumentEngine.builder(fonts).fontRole("bold", bold).navigation(navigation).build();
        var result = engine.export(source).backend(new PdfBackend()).to(OUTPUT.resolve("semantic-tables.pdf"));
        if (result.display().pages().size() != 3 || result.bodyPageOffset() != 1
                || result.diagnostics().stream().anyMatch(d -> d.severity() == TextDiagnostic.Severity.WARNING)) {
            throw new IllegalStateException("semantic table fixture did not produce three clean pages");
        }
        // This named preview deliberately contains static page drawings; interactive data remains in the PDF.
        try (var output = Files.newOutputStream(OUTPUT.resolve("semantic-tables-preview.svg"))) {
            new SvgBackend().write(new DisplayDocument(result.display().metadata(), result.display().pages()), output);
        }
        Files.writeString(OUTPUT.resolve("results.json"), manifest(result));
        System.out.println("Semantic tables: " + result.display().pages().size() + " pages");
    }

    private static Table table(String id, String label, String target, InlineImage image) {
        return Table.builder(id).cellFormat(CELL).header(label)
                .row(TableCell.mixed(List.of(text("PAGE="), field(InlineField.Kind.PAGE, ""), text(" TOTAL="),
                        field(InlineField.Kind.PAGES, ""), text("\nREF="), field(InlineField.Kind.REFERENCE_PAGE, target),
                        text("\n"), new InlineText("OPEN ", new InlineStyle("bold", 10, 0x224488, 0, true, false),
                                "https://example.com/semantic-text"), image), CELL)).build();
    }
    private static InlineText text(String value) { return new InlineText(value, InlineStyle.of(10)); }
    private static InlineField field(InlineField.Kind kind, String target) { return new InlineField(kind, target, 18, InlineStyle.of(10)); }
    private static InlineImage image() throws IOException {
        var pixels = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) { for (int x = 0; x < 8; x++) { pixels.setRGB(x, y, x < 4 ? 0x285080 : 0x64a0c8); } }
        var bytes = new ByteArrayOutputStream(); ImageIO.write(pixels, "png", bytes);
        return new InlineImage(ImageResource.load(bytes.toByteArray()), 14, 14, 0, "Synthetic blue icon",
                "https://example.com/semantic-image");
    }

    private static String manifest(ComponentDocumentLayout result) {
        var json = new StringBuilder("{\"pages\":").append(result.display().pages().size())
                .append(",\"bodyPageOffset\":").append(result.bodyPageOffset()).append(",\"placements\":[");
        boolean first = true;
        for (var page : result.body().pages()) {
            for (var element : page.elements()) {
                if (!(element instanceof ComponentFragment component)) { continue; }
                for (var located : ComponentSemantics.locate(component.layout(), component.origin())) {
                    if (!first) { json.append(','); } first = false;
                    var placement = located.placement(); var table = placement.fragment();
                    double x = located.translation().x() + table.origin().x();
                    double y = located.translation().y() + table.origin().y();
                    json.append("{\"tableId\":").append(quoted(placement.source().id())).append(",\"pageIndex\":")
                            .append(page.pageIndex() + result.bodyPageOffset()).append(geometry(x, y, table.width(), table.height())).append(",\"cells\":[");
                    boolean firstCell = true;
                    for (var row : table.rows()) {
                        for (var cell : row.cells()) {
                            if (!firstCell) { json.append(','); } firstCell = false;
                            var source = placement.source().rows().get(row.rowIndex()).cellAtColumn(cell.columnIndex());
                            json.append("{\"row\":").append(row.rowIndex()).append(",\"column\":").append(cell.columnIndex())
                                    .append(",\"source\":").append(quoted(source.text())).append(",\"sourceUtf16Length\":").append(source.text().length())
                                    .append(",\"fieldIndices\":").append(source.fields().stream().map(field -> field.sourceIndex()).toList())
                                    .append(",\"imageIndices\":").append(source.images().stream().map(image -> image.sourceIndex()).toList())
                                    .append(",\"logical\":").append(quoted(logical(cell.layout())))
                                    .append(String.format(Locale.ROOT, ",\"textX\":%.6f,\"textY\":%.6f}", x + cell.textOrigin().x(), y + cell.textOrigin().y()));
                        }
                    }
                    json.append("]}");
                }
            }
        }
        json.append("],\"externalLinks\":["); first = true;
        for (var link : result.display().extras().externalLinks()) {
            if (!first) { json.append(','); } first = false;
            json.append("{\"uri\":").append(quoted(link.destination().uri())).append(",\"pageIndex\":").append(link.pageIndex())
                    .append(geometry(link.bounds().x(), link.bounds().y(), link.bounds().width(), link.bounds().height())).append('}');
        }
        json.append("],\"links\":["); first = true;
        for (var link : result.display().extras().links()) {
            if (!first) { json.append(','); } first = false;
            json.append("{\"pageIndex\":").append(link.pageIndex()).append(",\"targetPageIndex\":").append(link.targetPageIndex())
                    .append(geometry(link.bounds().x(), link.bounds().y(), link.bounds().width(), link.bounds().height()))
                    .append(String.format(Locale.ROOT, ",\"destinationX\":%.6f,\"destinationY\":%.6f}", link.destination().x(), link.destination().y()));
        }
        return json.append("]}\n").toString();
    }
    private static String geometry(double x, double y, double width, double height) {
        return String.format(Locale.ROOT, ",\"x\":%.6f,\"y\":%.6f,\"width\":%.6f,\"height\":%.6f", x, y, width, height);
    }
    private static String logical(ParagraphLayout layout) {
        return layout.lines().stream().map(line -> line.outlines().isEmpty()
                ? line.runs().stream().map(run -> run.text()).collect(Collectors.joining())
                : line.outlines().stream().map(TextOutline::logicalText).collect(Collectors.joining())).collect(Collectors.joining("\n"));
    }
    private static String quoted(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + '"';
    }
}
