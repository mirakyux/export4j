// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfLimits;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.ParagraphFormat;

/** Static page selection/merge and explicit text extraction limitations; no source document mutation. */
public final class PdfOperationsExample {
    private static final Path OUTPUT = Path.of("examples/build/pdf-operations");
    private PdfOperationsExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var fonts = FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf")));
        var engine = DocumentEngine.builder(fonts).build();
        var sourceA = Document.builder().pageSettings(PageSettings.builder().size(320, 300).margins(24).build())
                .bodyFormat(ParagraphFormat.builder().fontSize(12).lineHeight(26).build())
                .paragraph("SOURCE-A-01")
                .paragraph(List.of(new InlineText("Static image ", InlineStyle.of(12)),
                        new InlineImage(AuthoringExample.image().resource(), 16, 16, 0, "Generated static mark")))
                .pageBreak().paragraph("SOURCE-A-02").paragraph("Cafe\u0301 outline text").build();
        var sourceB = Document.builder().pageSettings(PageSettings.builder().size(420, 300).margins(24).build())
                .paragraph("SOURCE-B-01").build();
        engine.export(sourceA).backend(new PdfBackend()).to(OUTPUT.resolve("source-a.pdf"));
        engine.export(sourceB).backend(new PdfBackend()).to(OUTPUT.resolve("source-b.pdf"));
        var adapter = PdfAdapter.builder().limits(PdfLimits.builder().maxPages(20).build()).build();
        var a = adapter.input(OUTPUT.resolve("source-a.pdf"), "");
        var b = adapter.input(OUTPUT.resolve("source-b.pdf"), "");
        var read = adapter.read(a);
        if (read.pages().size() != 2 || !read.pages().getFirst().text().contains("SOURCE-A-01")) {
            throw new IOException("static PDF text extraction failed");
        }
        adapter.copy(a.pages(1, 0), OUTPUT.resolve("selected.pdf"));
        adapter.copy(a.pages(1), OUTPUT.resolve("split.pdf"));
        var merged = adapter.merge(List.of(a.pages(1), b.allPages(), a.pages(0)), OUTPUT.resolve("merged.pdf"));
        Files.writeString(OUTPUT.resolve("read.txt"), read.pages().getFirst().text() + "\n" + read.pages().getLast().text(), StandardCharsets.UTF_8);
        Files.writeString(OUTPUT.resolve("diagnostics.txt"), "read=" + read.diagnostics() + "\nmerge=" + merged.diagnostics(), StandardCharsets.UTF_8);
        System.out.println("Static PDF operations: 2 sources, reordered selection, split and merge");
    }
}
