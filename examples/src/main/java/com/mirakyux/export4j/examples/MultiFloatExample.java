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
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.MultiFloatText;
import com.mirakyux.export4j.components.OpenTypeText;
import com.mirakyux.export4j.components.TextComponent;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;

/** Offline, project-owned fixtures for staggered floats, blocked line bands and page transitions. */
public final class MultiFloatExample {
    private static final Path OUTPUT = Path.of("examples/build/multi-floats");
    private static final PageSettings SETTINGS = new PageSettings(new PageSize(440, 480), PageMargins.uniform(28));
    private static final ParagraphStyle STYLE = new ParagraphStyle(12, 22, 0, 0, TextAlignment.LEFT);
    private static final RgbColor BLUE = new RgbColor(.86, .93, .98);
    private static final RgbColor ORANGE = new RgbColor(.99, .91, .8);
    private MultiFloatExample() { }
    private record Fixture(String name, String source, double prefixHeight, List<MultiFloatText.FloatBox> floats) { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var faces = new ArrayList<FontFace>();
        for (var name : List.of("LiberationSans-Regular.ttf", "NotoNaskhArabic-Regular.ttf", "LXGWWenKaiTC-Regular.ttf")) {
            try (var input = MultiFloatExample.class.getResourceAsStream("/fonts/" + name)) {
                faces.add(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES));
            }
        }
        var engine = DocumentEngine.builder(FontGroup.strict(faces)).build();
        String source = IntStream.rangeClosed(1, 24).mapToObj(index -> String.format(Locale.ROOT,
                "BODY-%02d  中文与 English / العربية a\u0301: text avoids every active float and keeps logical source order.", index))
                .collect(Collectors.joining("\n"));
        var fixtures = List.of(
                new Fixture("staggered", source, 36, List.of(
                        box(panel("Image", BLUE, true), 96, 68, 0, MultiFloatText.Side.LEFT),
                        box(panel("Right card", ORANGE, false), 90, 88, 24, MultiFloatText.Side.RIGHT),
                        box(panel("Left card", BLUE, false), 116, 54, 132, MultiFloatText.Side.LEFT))),
                new Fixture("blocked", "BLOCK-01 starts below both boxes.\nBLOCK-02 remains in order.", 0, List.of(
                        box(panel("Left", BLUE, false), 192, 44, 0, MultiFloatText.Side.LEFT),
                        box(panel("Right", ORANGE, false), 192, 44, 0, MultiFloatText.Side.RIGHT))),
                new Fixture("moved", "MOVE-01 The entire float group starts on a fresh page.", 240, List.of(
                        box(panel("Top", BLUE, false), 96, 60, 0, MultiFloatText.Side.LEFT),
                        box(panel("Lower box", ORANGE, false), 110, 80, 250, MultiFloatText.Side.RIGHT))),
                new Fixture("float-only", "FULL-01 Text follows a float-only first page.\nFULL-02 No duplicate float on this page.", 0, List.of(
                        box(panel("Full-page float", BLUE, true), 384, 424, 0, MultiFloatText.Side.LEFT))));
        var json = new StringBuilder("{\"pageWidth\":440,\"pageHeight\":480,\"margin\":28,\"lineHeight\":22,\"fixtures\":[");
        for (int index = 0; index < fixtures.size(); index++) {
            var fixture = fixtures.get(index);
            var builder = Document.builder(new DocumentMetadata("Multiple floats / " + fixture.name(), "mirakyux"), SETTINGS);
            if (fixture.prefixHeight() > 0) {
                builder.add(new ComponentBlock("prefix", new BoxComponent(384, fixture.prefixHeight(),
                        new TextComponent("Multiple floats / " + fixture.name(), STYLE, RgbColor.BLACK)), 0, 0));
            }
            builder.add(new ComponentBlock("wrap", new MultiFloatText(new OpenTypeText(fixture.source(), STYLE, RgbColor.BLACK), fixture.floats()), 0, 0));
            var display = engine.layout(builder.build()).display();
            try (var output = Files.newOutputStream(OUTPUT.resolve(fixture.name() + ".pdf"))) { new PdfBackend().write(display, output); }
            try (var output = Files.newOutputStream(OUTPUT.resolve(fixture.name() + ".svg"))) { new SvgBackend().write(display, output); }
            try (var output = Files.newOutputStream(OUTPUT.resolve(fixture.name() + ".zip"))) { new RasterBackend(144).write(display, output); }
            Files.writeString(OUTPUT.resolve(fixture.name() + ".txt"), fixture.source());
            if (index > 0) { json.append(','); }
            json.append(String.format(Locale.ROOT, "{\"name\":\"%s\",\"pages\":%d,\"prefixHeight\":%.8f,\"floats\":[",
                    fixture.name(), display.pages().size(), fixture.prefixHeight()));
            for (int boxIndex = 0; boxIndex < fixture.floats().size(); boxIndex++) {
                var box = fixture.floats().get(boxIndex);
                if (boxIndex > 0) { json.append(','); }
                json.append(String.format(Locale.ROOT, "{\"side\":\"%s\",\"top\":%.8f,\"width\":%.8f,\"height\":%.8f,\"gap\":%.8f}",
                        box.side(), box.top(), box.width(), box.height(), box.gap()));
            }
            json.append("]}");
            System.out.println(fixture.name() + ": " + display.pages().size() + " pages");
        }
        Files.writeString(OUTPUT.resolve("geometry.json"), json.append("]}").toString());
    }

    private static MultiFloatText.FloatBox box(Component child, double width, double height, double top, MultiFloatText.Side side) {
        return new MultiFloatText.FloatBox(child, width, height, top, 10, side);
    }

    private static Component panel(String label, RgbColor background, boolean picture) {
        return context -> {
            var builder = new DisplayListBuilder(new PageSize(context.width(), context.height()));
            builder.fillRect(new Rect(0, 0, context.width(), context.height()), background);
            var labelLayout = new TextComponent(label, new ParagraphStyle(10, 22, 0, 0, TextAlignment.LEFT), RgbColor.BLACK)
                    .layout(context.viewport(context.width() - 16, context.height() - 16));
            builder.save().transform(AffineTransform.translation(8, 8)).appendCommands(labelLayout.commands()).restore();
            if (picture) { builder.drawImage(image(), new Rect(8, 30, context.width() - 16, context.height() - 38)); }
            return new ComponentLayout(context.width(), context.height(), labelLayout.textLines(), builder.build().commands(), labelLayout.diagnostics());
        };
    }

    private static RgbImage image() {
        byte[] pixels = new byte[120 * 80 * 3];
        for (int y = 0; y < 80; y++) {
            for (int x = 0; x < 120; x++) {
                int offset = (y * 120 + x) * 3;
                pixels[offset] = (byte) (40 + x);
                pixels[offset + 1] = (byte) (85 + y);
                pixels[offset + 2] = (byte) (190 + ((x / 12 + y / 8) % 2) * 40);
            }
        }
        return new RgbImage(120, 80, pixels);
    }
}
