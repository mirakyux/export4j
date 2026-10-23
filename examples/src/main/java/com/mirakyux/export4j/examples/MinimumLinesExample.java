// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.BoxComponent;
import com.mirakyux.export4j.components.ContinuousColumns;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.FlowText;
import com.mirakyux.export4j.components.RichTextComponent;
import com.mirakyux.export4j.components.TextComponent;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.core.RegionFlow;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.text.ParagraphStyle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Unequal columns with minimum two-line fragments and a deliberately short initial page. */
public final class MinimumLinesExample {
    private MinimumLinesExample() { }
    public static void main(String[] args) throws IOException {
        var output = Path.of("examples/build/minimum-lines"); Files.createDirectories(output);
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf"))))
                .fontRole("bold", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Bold.ttf")))).build();
        var style = ParagraphStyle.builder().fontSize(12).lineHeight(20).firstLineIndent(8).build();
        String explicit = IntStream.rangeClosed(1, 5).mapToObj(index -> String.format(Locale.ROOT, "KEEP%02d", index)).collect(Collectors.joining("\n"));
        String wrapped = IntStream.rangeClosed(1, 31).mapToObj(index -> String.format(Locale.ROOT, "TOKEN%02d", index)).collect(Collectors.joining(" "));
        var rich = RichTextComponent.builder().lineHeight(20).firstLineIndent(8);
        for (int index = 1; index <= 31; index++) {
            rich.add(String.format(Locale.ROOT, "TOKEN%02d%s", index, index == 31 ? "" : " "),
                    new InlineStyle(index % 2 == 0 ? "bold" : "", 12, index % 2 == 0 ? 0x224488 : 0x884422, 0, index % 2 == 0, false));
        }
        for (String name : List.of("explicit", "wrapped", "rich")) {
            RegionFlow flow = name.equals("rich") ? rich.build().flow(PaginationPolicy.BALANCED)
                    : new FlowText(name.equals("explicit") ? explicit : wrapped, style, RgbColor.BLACK, PaginationPolicy.BALANCED);
            var document = Documents.builder().pageSettings(PageSettings.builder().size(408, 128).margins(24).build())
                    .bodyFormat(format -> format.fontSize(12).lineHeight(20).spaceAfter(0))
                    .component(new BoxComponent(360, 60, new TextComponent("PREFACE", style, RgbColor.BLACK)))
                    .component(ContinuousColumns.builder(flow).columnWeights(List.of(1.0, 3.0)).gap(20).balanced(name.equals("rich")).build())
                    .paragraph("DOCUMENT-END").build();
            var result = engine.export(document).backend(new PdfBackend()).to(output.resolve(name + ".pdf"));
            if (!result.contextualDiagnostics().isEmpty()) { throw new IllegalStateException("minimum-line fixture has diagnostics"); }
            try (var stream = Files.newOutputStream(output.resolve(name + ".svg"))) { new SvgBackend().write(result.display(), stream); }
        }
    }
}
