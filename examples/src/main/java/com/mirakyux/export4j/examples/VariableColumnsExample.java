// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.ContinuousColumns;
import com.mirakyux.export4j.components.BoxComponent;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.FlowColumn;
import com.mirakyux.export4j.components.FlowText;
import com.mirakyux.export4j.components.ImageComponent;
import com.mirakyux.export4j.components.OpenTypeText;
import com.mirakyux.export4j.components.RichTextComponent;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.layout.core.RegionFlow;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextDiagnostic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Actual 1:3 columns with short first regions, styled runs, complex clusters and mixed content. */
public final class VariableColumnsExample {
    private VariableColumnsExample() { }

    public static void main(String[] args) throws IOException {
        var output = Path.of("examples/build/variable-columns");
        Files.createDirectories(output);
        var fonts = FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf"),
                AuthoringExample.face("NotoNaskhArabic-Regular.ttf")));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf"))))
                .fontRole("bold", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Bold.ttf")))).build();
        var style = ParagraphStyle.builder().fontSize(12).lineHeight(20).build();
        String text = IntStream.rangeClosed(1, 60).mapToObj(index -> String.format(Locale.ROOT, "WORD%02d", index)).collect(Collectors.joining(" "));
        var rich = RichTextComponent.builder().lineHeight(20);
        for (int index = 1; index <= 60; index++) {
            rich.add(String.format(Locale.ROOT, "WORD%02d%s", index, index == 60 ? "" : " "),
                    new InlineStyle(index % 2 == 0 ? "bold" : "", 12, index % 2 == 0 ? 0x224488 : 0x884422, 0, index % 2 == 0, false));
        }
        for (String name : List.of("plain", "rich", "complex", "mixed")) {
            RegionFlow flow = switch (name) {
                case "rich" -> rich.build().flow();
                case "complex" -> new OpenTypeText(IntStream.rangeClosed(1, 36)
                        .mapToObj(index -> String.format(Locale.ROOT, "C%02d العربية لا a\u0301", index)).collect(Collectors.joining(" ")),
                        style.toBuilder().lineHeight(28).build(), RgbColor.BLACK);
                case "mixed" -> FlowColumn.builder().padding(3).gap(4).text("MIXED-BEGIN", value -> value.style(style))
                        .add(FlowText.builder(text).style(style).build())
                        .add(new BoxComponent(16, 16, new ImageComponent(new RgbImage(1, 1, new byte[] {34, 68, (byte) 136}), ImageComponent.Fit.CONTAIN)))
                        .text("MIXED-END", value -> value.style(style)).build();
                default -> FlowText.builder(text).style(style).build();
            };
            var document = Documents.builder().pageSettings(PageSettings.builder().size(408, 240).margins(24).build())
                    .bodyFormat(value -> value.fontSize(12).lineHeight(name.equals("complex") ? 28 : 20))
                    .paragraph("VARIABLE-" + name.toUpperCase(Locale.ROOT))
                    .component(ContinuousColumns.builder(flow).columnWeights(List.of(1.0, 3.0)).gap(20).balanced(true).build())
                    .paragraph("DOCUMENT-END").build();
            var selectedEngine = name.equals("complex") ? DocumentEngine.builder(fonts).build() : engine;
            var result = selectedEngine.export(document).backend(new PdfBackend()).to(output.resolve(name + ".pdf"));
            if (result.diagnostics().stream().anyMatch(value -> value.code() != TextDiagnostic.Code.FONT_FALLBACK)) {
                throw new IllegalStateException("variable columns produced unexpected diagnostics");
            }
            try (var stream = Files.newOutputStream(output.resolve(name + ".svg"))) { new SvgBackend().write(result.display(), stream); }
            System.out.println(name + ": " + result.display().pages().size() + " pages");
        }
    }
}
