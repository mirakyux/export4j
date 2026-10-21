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
import com.mirakyux.export4j.components.FlowText;
import com.mirakyux.export4j.components.OpenTypeText;
import com.mirakyux.export4j.components.TextComponent;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.RgbColor;
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

/** Offline fixtures for remaining-height columns, full continuations and following semantic flow. */
public final class ContinuousColumnsExample {
    private static final Path OUTPUT = Path.of("examples/build/continuous-columns");
    private static final PageSettings SETTINGS = new PageSettings(new PageSize(440, 480), PageMargins.uniform(28));
    private static final ParagraphStyle STYLE = new ParagraphStyle(12, 22, 0, 0, TextAlignment.LEFT);
    private ContinuousColumnsExample() { }
    private record Fixture(String name, String source, double prefixHeight, int columns, double gap,
            boolean shaped, PaginationPolicy policy) { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var faces = new ArrayList<FontFace>();
        for (var name : List.of("LiberationSans-Regular.ttf", "NotoNaskhArabic-Regular.ttf", "LXGWWenKaiTC-Regular.ttf")) {
            try (var input = ContinuousColumnsExample.class.getResourceAsStream("/fonts/" + name)) {
                faces.add(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES));
            }
        }
        var engine = DocumentEngine.builder(FontGroup.strict(faces)).build();
        var fixtures = List.of(
                new Fixture("partial", source("BODY", "Flow text", 42), 112, 2, 16, false, PaginationPolicy.DEFAULT),
                new Fixture("short", source("SHORT", "Next block", 6), 80, 2, 16, false, PaginationPolicy.DEFAULT),
                new Fixture("moved", source("MOVE", "Two-line minimum", 5), 402, 2, 16, false, PaginationPolicy.BALANCED),
                new Fixture("three", source("TRI", "item", 24), 310, 3, 14, false, PaginationPolicy.DEFAULT),
                new Fixture("shaped", source("SHAPE", "中文 العربية a\u0301", 36), 112, 2, 16, true, PaginationPolicy.DEFAULT));
        var json = new StringBuilder("{\"pageWidth\":440,\"pageHeight\":480,\"margin\":28,\"fontSize\":12,\"lineHeight\":22,\"fixtures\":[");
        for (int index = 0; index < fixtures.size(); index++) {
            var fixture = fixtures.get(index);
            RegionFlow content = fixture.shaped() ? new OpenTypeText(fixture.source(), STYLE, RgbColor.BLACK)
                    : new FlowText(fixture.source(), STYLE, RgbColor.BLACK, fixture.policy());
            var document = Document.builder(new DocumentMetadata("Continuous columns / " + fixture.name(), "mirakyux"), SETTINGS)
                    .add(new ComponentBlock("prefix", new BoxComponent(384, fixture.prefixHeight(),
                            new TextComponent("Continuous columns / " + fixture.name(), STYLE, RgbColor.BLACK)), 0, 0))
                    .add(new ComponentBlock("columns", new ContinuousColumns(content, fixture.columns(), fixture.gap()), 0, 0))
                    .add(new ComponentBlock("tail", new TextComponent("TAIL / " + fixture.name(), STYLE, RgbColor.BLACK), 0, 0)).build();
            var display = engine.layout(document).display();
            try (var output = Files.newOutputStream(OUTPUT.resolve(fixture.name() + ".pdf"))) { new PdfBackend().write(display, output); }
            try (var output = Files.newOutputStream(OUTPUT.resolve(fixture.name() + ".svg"))) { new SvgBackend().write(display, output); }
            try (var output = Files.newOutputStream(OUTPUT.resolve(fixture.name() + ".zip"))) { new RasterBackend(144).write(display, output); }
            Files.writeString(OUTPUT.resolve(fixture.name() + ".txt"), fixture.source());
            if (index > 0) { json.append(','); }
            json.append(String.format(Locale.ROOT,
                    "{\"name\":\"%s\",\"pages\":%d,\"prefixHeight\":%.8f,\"columns\":%d,\"gap\":%.8f,\"shaped\":%s,\"minimumLines\":%d}",
                    fixture.name(), display.pages().size(), fixture.prefixHeight(), fixture.columns(), fixture.gap(),
                    fixture.shaped(), fixture.policy().minLinesBeforeBreak()));
            System.out.println(fixture.name() + ": " + display.pages().size() + " pages");
        }
        Files.writeString(OUTPUT.resolve("geometry.json"), json.append("]}").toString());
    }

    private static String source(String prefix, String text, int count) {
        return IntStream.rangeClosed(1, count).mapToObj(index -> String.format(Locale.ROOT, "%s-%02d %s", prefix, index, text))
                .collect(Collectors.joining("\n"));
    }
}
