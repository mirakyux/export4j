// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.raster.RasterBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.BoxComponent;
import com.mirakyux.export4j.components.ContinuousColumns;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.FlowColumn;
import com.mirakyux.export4j.components.FlowText;
import com.mirakyux.export4j.components.GridTable;
import com.mirakyux.export4j.components.ImageComponent;
import com.mirakyux.export4j.components.OpenTypeText;
import com.mirakyux.export4j.components.TextComponent;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.RegionFlow;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;

/** Project-owned fixtures for mixed content, repeated table headers and merged groups in continuous columns. */
public final class MixedColumnsExample {
    private static final Path OUTPUT = Path.of("examples/build/mixed-columns");
    private static final PageSettings SETTINGS = new PageSettings(new PageSize(440, 480), PageMargins.uniform(28));
    private static final ParagraphStyle STYLE = new ParagraphStyle(12, 22, 0, 0, TextAlignment.LEFT);
    private MixedColumnsExample() { }
    private record Fixture(String name, RegionFlow content, double prefixHeight, String source) { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var faces = new ArrayList<FontFace>();
        for (var name : List.of("LiberationSans-Regular.ttf", "NotoNaskhArabic-Regular.ttf", "LXGWWenKaiTC-Regular.ttf")) {
            try (var input = MixedColumnsExample.class.getResourceAsStream("/fonts/" + name)) {
                faces.add(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES));
            }
        }
        var engine = DocumentEngine.builder(FontGroup.strict(faces)).build();
        String mixedSource = source("MIX", "Flow text", 24);
        String nestedSource = source("NEST", "中文 العربية a\u0301", 24);
        var mixed = column(List.of(text("ARTICLE"), picture(176, 66),
                new FlowText(mixedSource, STYLE, RgbColor.BLACK, PaginationPolicy.DEFAULT), text("INSIDE-END"), table(2)), 4, 8);
        var nested = column(List.of(column(List.of(text("INNER"), new OpenTypeText(nestedSource, STYLE, RgbColor.BLACK),
                text("INNER-END")), 2, 6)), 2, 8);
        var fixtures = List.of(new Fixture("mixed", mixed, 256, mixedSource),
                new Fixture("table", table(22), 256, source("ROW", "", 21)),
                new Fixture("merged", mergedTable(), 370, "MERGE-A\nMERGE-B\nMERGE-03-L\nMERGE-03-R\nMERGE-04-L\nMERGE-04-R\nMERGE-05-L\nMERGE-05-R"),
                new Fixture("nested", nested, 280, nestedSource));
        var json = new StringBuilder("{\"pageWidth\":440,\"pageHeight\":480,\"margin\":28,\"fontSize\":12,\"lineHeight\":22,\"columns\":2,\"gap\":16,\"fixtures\":[");
        for (int index = 0; index < fixtures.size(); index++) {
            var fixture = fixtures.get(index);
            var document = Document.builder(new DocumentMetadata("Mixed columns / " + fixture.name(), "mirakyux"), SETTINGS)
                    .add(new ComponentBlock("prefix", new BoxComponent(384, fixture.prefixHeight(), text("Mixed columns / " + fixture.name())), 0, 0))
                    .add(new ComponentBlock("columns", new ContinuousColumns(fixture.content(), 2, 16), 0, 0))
                    .add(new ComponentBlock("tail", text("TAIL / " + fixture.name()), 0, 0)).build();
            var display = engine.layout(document).display();
            try (var output = Files.newOutputStream(OUTPUT.resolve(fixture.name() + ".pdf"))) { new PdfBackend().write(display, output); }
            try (var output = Files.newOutputStream(OUTPUT.resolve(fixture.name() + ".svg"))) { new SvgBackend().write(display, output); }
            try (var output = Files.newOutputStream(OUTPUT.resolve(fixture.name() + ".zip"))) { new RasterBackend(144).write(display, output); }
            Files.writeString(OUTPUT.resolve(fixture.name() + ".txt"), fixture.source());
            if (index > 0) { json.append(','); }
            json.append(String.format(Locale.ROOT, "{\"name\":\"%s\",\"pages\":%d,\"prefixHeight\":%.8f}",
                    fixture.name(), display.pages().size(), fixture.prefixHeight()));
            System.out.println(fixture.name() + ": " + display.pages().size() + " pages");
        }
        Files.writeString(OUTPUT.resolve("geometry.json"), json.append("]}").toString());
    }

    private static TextComponent text(String value) { return new TextComponent(value, STYLE, RgbColor.BLACK); }
    private static FlowColumn column(List<Component> children, double padding, double gap) {
        return new FlowColumn(children, PageMargins.uniform(padding), gap, FlowColumn.Alignment.START);
    }
    private static GridTable.Cell cell(int row, int col, int rowSpan, Component content) {
        return new GridTable.Cell(row, col, rowSpan, 1, content, PageMargins.uniform(2), FlowColumn.Alignment.START,
                row == 0 ? new RgbColor(.88, .94, .98) : RgbColor.WHITE, new RgbColor(.45, .55, .65), .5);
    }
    private static GridTable table(int rows) {
        List<GridTable.Cell> cells = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < 2; col++) {
                String label = row == 0 ? "HEAD" : String.format(Locale.ROOT, "ROW-%02d", row);
                cells.add(cell(row, col, 1, text(label + (col == 0 ? "-L" : "-R"))));
            }
        }
        return new GridTable(rows, List.of(1.0, 1.0), 1, cells);
    }
    private static GridTable mergedTable() {
        List<GridTable.Cell> cells = new ArrayList<>(List.of(cell(0, 0, 1, text("HEAD-L")), cell(0, 1, 1, text("HEAD-R")),
                cell(1, 0, 2, picture(80, 48)), cell(1, 1, 1, text("MERGE-A")), cell(2, 1, 1, text("MERGE-B"))));
        for (int row = 3; row <= 5; row++) {
            for (int col = 0; col < 2; col++) {
                cells.add(cell(row, col, 1, text(String.format(Locale.ROOT, "MERGE-%02d-%s", row, col == 0 ? "L" : "R"))));
            }
        }
        return new GridTable(6, List.of(1.0, 1.0), 1, cells);
    }
    private static BoxComponent picture(double width, double height) {
        byte[] pixels = new byte[120 * 60 * 3];
        for (int y = 0; y < 60; y++) {
            for (int x = 0; x < 120; x++) {
                int offset = (y * 120 + x) * 3;
                pixels[offset] = (byte) (40 + x); pixels[offset + 1] = (byte) (90 + y);
                pixels[offset + 2] = (byte) (180 + ((x / 10 + y / 10) % 2) * 50);
            }
        }
        return new BoxComponent(width, height, new ImageComponent(new RgbImage(120, 60, pixels), ImageComponent.Fit.STRETCH));
    }
    private static String source(String prefix, String text, int count) {
        return IntStream.rangeClosed(1, count).mapToObj(index -> String.format(Locale.ROOT, "%s-%02d%s", prefix, index,
                text.isEmpty() ? "" : " " + text)).collect(Collectors.joining("\n"));
    }
}
