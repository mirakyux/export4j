// SPDX-License-Identifier: Apache-2.0
package consumer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.ContentsStyle;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Navigation;
import com.mirakyux.export4j.components.TableOfContents;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;

/** Checks independent contents typography and final navigation through published coordinates. */
final class ContentsStyleConsumer {
    private ContentsStyleConsumer() { }

    static void verify(FontFace font, Path output) throws IOException {
        var page = PageSettings.builder().size(300, 260).margins(20).build();
        var source = Document.builder(new DocumentMetadata("Contents styles consumer", ""), page)
                .add(new Heading("one", "CONSUMER-FIRST", 1, ParagraphFormat.of(12)))
                .paragraph("body", "CONSUMER-BODY", ParagraphFormat.of(10))
                .add(new Heading("two", "CONSUMER-SECOND", 1, ParagraphFormat.of(12))).build();
        var visual = ContentsStyle.builder()
                .titleStyle(new ParagraphStyle(18, 30, 0, 0, TextAlignment.CENTER))
                .pageLabelStyle(new ParagraphStyle(12, 22, 0, 0, TextAlignment.RIGHT))
                .titleColor(new RgbColor(34 / 255.0, 68 / 255.0, 136 / 255.0))
                .pageLabelColor(new RgbColor(.8, .1, .1)).build();
        var contents = TableOfContents.builder().title("CONSUMER-CONTENTS")
                .style(new ParagraphStyle(10, 18, 0, 0, TextAlignment.LEFT))
                .appearance(visual).build();
        var navigation = Navigation.fromHeadings(source, contents, List.of(), List.of());
        var result = DocumentEngine.builder(FontGroup.strict(List.of(font))).navigation(navigation).build()
                .export(source).backend(new PdfBackend()).to(output.resolve("consumer-contents-style.pdf"));
        if (result.bodyPageOffset() != 1 || result.display().pages().size() != 2
                || result.display().extras().links().size() != 2 || !result.diagnostics().isEmpty()
                || result.display().extras().links().stream().anyMatch(link -> link.pageIndex() != 0 || link.targetPageIndex() != 1)) {
            throw new IllegalStateException("published contents style or final navigation changed");
        }
    }
}
