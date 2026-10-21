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
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.FlowColumn;
import com.mirakyux.export4j.components.FlowText;
import com.mirakyux.export4j.components.StyleScope;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.text.ParagraphStyle;

/** Self-authored inherited-style report. All labels and values are synthetic; dimensions are points. */
public final class StyleDefaultsExample {
    private static final Path OUTPUT = Path.of("examples/build/style-defaults");
    private static final RgbColor RED = new RgbColor(.75, .1, .1);
    private static final RgbColor GREEN = new RgbColor(.1, .5, .2);
    private static final RgbColor BLUE = new RgbColor(.1, .2, .75);

    private StyleDefaultsExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var fonts = FontGroup.builder().font(AuthoringExample.face("LiberationSans-Regular.ttf")).build();
        var engine = DocumentEngine.builder(fonts).build();
        String source = IntStream.rangeClosed(1, 26)
                .mapToObj(i -> String.format(Locale.ROOT, "STYLE-LINE-%02d", i)).collect(Collectors.joining("\n"));

        var document = Documents.builder().title("Inherited component styles")
                .pageSettings(page -> page.size(400, 360).margins(20))
                .bodyFormat(format -> format.fontSize(10).lineHeight(20).spaceAfter(0))
                .paragraph("STYLE-REPORT")
                .column(column -> column.gap(2)
                        .text("DEFAULT-BODY")
                        .column(nested -> nested.padding(2).text("NESTED-BODY"))
                        .fixedText("FIXED-BODY")
                        .formText("FORM-BODY ☑ Yes □ No")
                        .choices(group -> group.option("CHOICE-YES", true).option("CHOICE-NO", false))
                        .labelValue("LABEL", "VALUE", field -> field.labelWidth(42).gap(6))
                        .add(StyleScope.builder(FlowColumn.builder()
                                .text("COLOR-INHERIT")
                                .text("PIN12", text -> text.style(ParagraphStyle.of(12)))
                                .text("BLUE-EXPLICIT", text -> text.color(BLUE)).build()).color(RED).build())
                        .add(StyleScope.builder(FlowText.builder("LOCAL14").build())
                                .style(style -> style.fontSize(14).lineHeight(24)).color(GREEN).build()))
                .grid(grid -> grid.columns(1, 1).defaults(cell -> cell.padding(3))
                        .row(row -> row.cell("GRID-BODY").cell(FlowText.builder("GRID-CUSTOM").build()))
                        .defaults(cell -> cell.textStyle(ParagraphStyle.builder().fontSize(9).lineHeight(18).build()).textColor(GREEN))
                        .row(row -> row.cell("GRID9").cell(FlowText.builder("GRID-CUSTOM9").build())))
                .bodyFormat(format -> format.fontSize(11).lineHeight(22))
                .column(column -> column.text("SNAPSHOT11"))
                .pageBreak()
                .bodyFormat(format -> format.fontSize(10).lineHeight(20))
                .grid(grid -> grid.columns(1, 3).headerRows(1).allowRowSplit(true)
                        .defaults(cell -> cell.padding(3))
                        .row(row -> row.cells("MERGED", "STYLE-HEADER"))
                        .row(row -> row.cell("LABEL-ONCE", cell -> cell.rowSpan(2))
                                .cell(FlowColumn.builder().text(source).build(), cell -> cell.rowSpan(2)))
                        .row(row -> { }))
                .paragraph("STYLE-TAIL").build();
        var result = engine.export(document).backend(new PdfBackend()).to(OUTPUT.resolve("style-defaults.pdf"));
        try (var output = Files.newOutputStream(OUTPUT.resolve("style-defaults.svg"))) {
            new SvgBackend().write(result.display(), output);
        }
        if (!result.diagnostics().isEmpty()) { throw new IllegalStateException("style fixture has unexpected text diagnostics"); }
        System.out.println("Inherited component styles: " + result.display().pages().size() + " pages");
    }
}
