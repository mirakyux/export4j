// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.BoxComponent;
import com.mirakyux.export4j.components.ContinuousColumns;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.FlowColumn;
import com.mirakyux.export4j.components.GridTable;
import com.mirakyux.export4j.components.ImageComponent;
import com.mirakyux.export4j.components.OpenTypeText;
import com.mirakyux.export4j.components.Spacer;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.text.ParagraphStyle;

/** Self-authored synthetic fixtures. No competitor code or real patient data. Dimensions use points. */
public final class GridContinuationExample {
    private static final Path OUTPUT = Path.of("examples/build/grid-continuation");
    private static final ParagraphStyle STYLE = ParagraphStyle.builder().fontSize(11).lineHeight(16).build();
    private GridContinuationExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var fonts = FontGroup.builder().font(AuthoringExample.face("LXGWWenKaiTC-Regular.ttf"))
                .font(AuthoringExample.face("LiberationSans-Regular.ttf")).build();
        var engine = DocumentEngine.builder(fonts).build();
        var shapedEngine = DocumentEngine.builder(fonts.toBuilder().font(AuthoringExample.face("NotoNaskhArabic-Regular.ttf")).build()).build();
        var shapedStyle = STYLE.toBuilder().lineHeight(22).build();
        var small = PageSettings.builder().size(360, 280).margins(20).build();
        var longSource = source("ROW", 120, " 中文长单元格续页验证。");
        var merged = Documents.builder().title("Merged cell continuation").pageSettings(page -> page.margins(36))
                .grid(grid -> merged(grid, longSource)).build();
        var mixedContent = FlowColumn.builder().padding(3).gap(6).text(source("MIX", 17, " 中文"), text -> text.style(STYLE))
                .add(new BoxComponent(72, 38, new ImageComponent(new RgbImage(2, 1, new byte[]{30, 100, (byte) 180, (byte) 240, 100, 40}), ImageComponent.Fit.STRETCH)))
                .text("AFTER-IMAGE", text -> text.style(STYLE)).build();
        var mixed = Documents.builder().title("Mixed cell continuation").pageSettings(small).component(new Spacer(110))
                .grid(grid -> grid.columns(2, 1).headerRows(1).allowRowSplit(true)
                        .defaults(cell -> cell.textStyle(STYLE).padding(4))
                        .row(row -> row.cells("HEAD-A", "HEAD-B"))
                        .row(row -> row.cell(mixedContent, cell -> cell.rowSpan(2)).cell("SIDE-A"))
                        .row(row -> row.cell("SIDE-B")))
                .paragraph("TAIL-MIXED").build();
        var columnGrid = GridTable.builder(); merged(columnGrid, source("COL", 46, ""));
        var columns = Documents.builder().title("Merged continuous columns")
                .pageSettings(page -> page.size(520, 340).margins(24)).component(new Spacer(160))
                .component(ContinuousColumns.builder().content(columnGrid.build()).columns(2).gap(20).build())
                .paragraph("TAIL-COLUMNS").build();
        var shaped = Documents.builder().title("Shaped merged cells").pageSettings(small)
                .grid(grid -> grid.columns(2, 1).headerRows(1).allowRowSplit(true)
                        .defaults(cell -> cell.textStyle(shapedStyle).padding(4))
                        .row(row -> row.cells("HEAD-A", "HEAD-B"))
                        .row(row -> row.cell(OpenTypeText.builder(source("SHP", 26, " 中文 العربية a\u0301")).style(shapedStyle).build(), cell -> cell.rowSpan(2)).cell("SIDE-A"))
                        .row(row -> row.cell("SIDE-B"))).build();
        var nested = Documents.builder().title("Ordinary nested text").pageSettings(small)
                .column(column -> column.padding(4).gap(6).fixedText("FIXED-HEADING", text -> text.style(STYLE).minHeight(24))
                        .column(inner -> inner.padding(2).text(source("TXT", 40, " 中文"), text -> text.style(STYLE)))
                        .fixedText("TAIL-NESTED", text -> text.style(STYLE))).build();
        var fixtures = List.of(new Fixture("merged", merged), new Fixture("mixed", mixed), new Fixture("columns", columns),
                new Fixture("shaped", shaped), new Fixture("nested", nested));
        var report = new StringBuilder("{\"fixtures\":[");
        for (var fixture : fixtures) {
            var result = (fixture.name().equals("shaped") ? shapedEngine : engine).export(fixture.document()).backend(new PdfBackend()).to(OUTPUT.resolve(fixture.name() + ".pdf"));
            try (var output = Files.newOutputStream(OUTPUT.resolve(fixture.name() + ".svg"))) { new SvgBackend().write(result.display(), output); }
            if (result.diagnostics().stream().anyMatch(d -> d.severity() == com.mirakyux.export4j.text.TextDiagnostic.Severity.WARNING)) {
                throw new IllegalStateException("unexpected fixture warning: " + fixture.name());
            }
            if (report.charAt(report.length() - 1) != '[') { report.append(','); }
            report.append(String.format(Locale.ROOT, "{\"name\":\"%s\",\"pages\":%d}", fixture.name(), result.display().pages().size()));
            System.out.println(fixture.name() + ": " + result.display().pages().size() + " pages");
        }
        Files.writeString(OUTPUT.resolve("results.json"), report.append("]}\n").toString());
    }

    private static void merged(GridTable.Builder grid, String text) {
        grid.columns(1, 1).headerRows(1).allowRowSplit(true).defaults(cell -> cell.textStyle(STYLE).padding(4))
                .row(row -> row.cells("HEAD-A", "HEAD-B"))
                .row(row -> row.cell(text, cell -> cell.rowSpan(2)).cell("SIDE-A"))
                .row(row -> row.cell("SIDE-B"));
    }
    private static String source(String prefix, int count, String suffix) {
        return IntStream.rangeClosed(1, count).mapToObj(index -> String.format(Locale.ROOT, "%s%03d%s", prefix, index, suffix)).collect(Collectors.joining("\n"));
    }
    private record Fixture(String name, Document document) { }
}
