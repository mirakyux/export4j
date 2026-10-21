// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import com.mirakyux.export4j.components.FlowText;
import com.mirakyux.export4j.components.StyleScope;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.text.TextAlignment;

/** Self-authored property inheritance fixtures; all labels are synthetic and lengths use points. */
public final class PropertyStylesExample {
    private static final Path OUTPUT = Path.of("examples/build/property-styles");
    private static final RgbColor BLUE = new RgbColor(.1, .2, .75);

    private PropertyStylesExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var fonts = FontGroup.builder().font(AuthoringExample.face("LiberationSans-Regular.ttf")).build();
        var engine = DocumentEngine.builder(fonts).build();
        var reset = FlowText.builder("RESET-A\nRESET-B").fontSize(14).build().toBuilder()
                .inheritFontSize().lineHeight(28).build();
        var nested = StyleScope.builder(FlowColumn.builder()
                .add(StyleScope.builder(FlowText.builder("NEST-A\nNEST-B").build())
                        .lineHeight(30).color(BLUE).build())
                .build()).fontSize(14).build();
        var summary = Documents.builder().title("Per-property text styles")
                .pageSettings(page -> page.size(480, 600).margins(20))
                .bodyFormat(format -> format.fontSize(10).lineHeight(24)
                        .firstLineIndent(18).subsequentLineIndent(6).spaceAfter(0))
                .column(column -> column
                        .text("SIZE14-A\nSIZE14-B", text -> text.fontSize(14))
                        .text("ZERO-A\nZERO-B", text -> text.firstLineIndent(0).subsequentLineIndent(0))
                        .text("RIGHT-A\nRIGHT-B", text -> text.alignment(TextAlignment.RIGHT))
                        .add(reset).add(nested).text("SIBLING10")
                        .text("FULL12", text -> text.style(style -> style.alignment(TextAlignment.RIGHT)))
                        .grid(grid -> grid.columns(1, 1)
                                .defaults(cell -> cell.padding(3).fontSize(9).firstLineIndent(0).subsequentLineIndent(0))
                                .row(row -> row.cell("GRID9").cell("FACTORY11", cell -> cell
                                        .textFactory(value -> FlowText.builder(value).fontSize(11).build())))
                                .row(row -> row.cell("LOCAL9", cell -> cell.lineHeight(28))
                                        .cell(FlowText.builder("CHILD12").fontSize(12).build()))
                                .defaults(cell -> cell.fontSize(13))
                                .row(row -> row.cell("GRID13").cell("FACTORY-SNAPSHOT", cell -> cell
                                        .textFactory(value -> FlowText.builder(value).fontSize(11).build()))))
                        .fixedText("FIXED11", text -> text.fontSize(11))
                        .formText("FORM11 ☑ Yes □ No", text -> text.fontSize(11)))
                .build();
        write(engine, "properties", summary);

        var continuation = Documents.builder().title("Partial styles in merged continuation")
                .pageSettings(page -> page.size(400, 320).margins(20))
                .bodyFormat(format -> format.fontSize(10).lineHeight(20)
                        .firstLineIndent(4).subsequentLineIndent(2).spaceAfter(0))
                .grid(grid -> grid.columns(1, 3).headerRows(1).allowRowSplit(true)
                        .defaults(cell -> cell.padding(3).fontSize(9))
                        .row(row -> row.cell("MERGED", cell -> cell.fontSize(11)).cell("PROPERTY-HEAD"))
                        .row(row -> row.cell("LABEL-ONCE", cell -> cell.rowSpan(2))
                                .cell(FlowColumn.builder().text(source("PROP-LINE", 22), text -> text.fontSize(11))
                                        .build(), cell -> cell.rowSpan(2)))
                        .row(row -> { }))
                .paragraph("PROPERTY-TAIL").build();
        write(engine, "continuation", continuation);

        var regionalText = StyleScope.builder(FlowText.builder(source("REGION", 12)).fontSize(11).build())
                .lineHeight(24).build();
        var regions = Documents.builder().title("Partial styles in short first columns")
                .pageSettings(page -> page.size(400, 360).margins(20))
                .bodyFormat(format -> format.fontSize(10).lineHeight(20).firstLineIndent(4)
                        .subsequentLineIndent(2).spaceAfter(0))
                .component(new BoxComponent(360, 200, FlowText.builder("REGION-PREFIX").build()))
                .component(new ContinuousColumns(regionalText, 2, 12))
                .paragraph("REGION-TAIL").build();
        write(engine, "regions", regions);
    }

    private static String source(String prefix, int lines) {
        return IntStream.rangeClosed(1, lines)
                .mapToObj(line -> String.format(Locale.ROOT, "%s-%02d", prefix, line))
                .collect(Collectors.joining("\n"));
    }

    private static void write(DocumentEngine engine, String name, Document document) throws IOException {
        var result = engine.export(document).backend(new PdfBackend()).to(OUTPUT.resolve(name + ".pdf"));
        try (var output = Files.newOutputStream(OUTPUT.resolve(name + ".svg"))) {
            new SvgBackend().write(result.display(), output);
        }
        if (!result.diagnostics().isEmpty()) {
            throw new IllegalStateException("property style fixture has unexpected diagnostics: " + name);
        }
        System.out.println(name + ": " + result.display().pages().size() + " pages");
    }
}
