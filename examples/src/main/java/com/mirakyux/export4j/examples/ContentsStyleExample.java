// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Navigation;
import com.mirakyux.export4j.components.TableOfContents;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Independent contents typography and colors, with final physical-page links. */
public final class ContentsStyleExample {
    private static final Path OUTPUT = Path.of("examples/build/contents-styles");

    private ContentsStyleExample() { }

    /** Writes exactly one contents page and one semantic body page using an explicit font. */
    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var font = AuthoringExample.face("LiberationSans-Regular.ttf");
        var page = PageSettings.builder().size(420, 360).margins(24).build();
        var document = Document.builder(new DocumentMetadata("Independent contents styles", "Export4j synthetic example"), page)
                .heading("first", "REPORT-FIRST", 1, ParagraphFormat.of(12))
                .paragraph("body-one", "REPORT-BODY-ONE", ParagraphFormat.of(10))
                .heading("second", "REPORT-SECOND details alpha beta gamma delta epsilon zeta", 2, ParagraphFormat.of(12))
                .paragraph("body-two", "REPORT-BODY-TWO", ParagraphFormat.of(10)).build();
        var contents = TableOfContents.builder().title("REPORT-CONTENTS")
                .style(new ParagraphStyle(11, 20, 0, 0, TextAlignment.LEFT))
                .appearance(style -> style
                        .titleStyle(new ParagraphStyle(22, 36, 0, 0, TextAlignment.CENTER))
                        .pageLabelStyle(new ParagraphStyle(14, 26, 0, 0, TextAlignment.RIGHT))
                        .titleColor(new RgbColor(34 / 255.0, 68 / 255.0, 136 / 255.0))
                        .entryColor(new RgbColor(0, .4, .2))
                        .pageLabelColor(new RgbColor(.8, .1, .1))).build();
        var navigation = Navigation.fromHeadings(document, contents, List.of(), List.of());
        var result = DocumentEngine.builder(FontGroup.strict(List.of(font))).navigation(navigation).build()
                .export(document).backend(new PdfBackend()).to(OUTPUT.resolve("styled.pdf"));
        if (result.bodyPageOffset() != 1 || result.body().pages().size() != 1
                || result.display().pages().size() != 2 || !result.diagnostics().isEmpty()
                || result.display().extras().links().size() != 2
                || result.display().extras().links().stream().anyMatch(link -> link.pageIndex() != 0 || link.targetPageIndex() != 1)) {
            throw new IllegalStateException("Contents styles must retain one front page and final body navigation");
        }
        System.out.println("Contents-style fixture: " + OUTPUT.toAbsolutePath());
    }
}
