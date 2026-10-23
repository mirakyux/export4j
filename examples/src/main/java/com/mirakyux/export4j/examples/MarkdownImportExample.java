// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.web.ExportPolicy;
import com.mirakyux.export4j.adapter.web.FontStyle;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownImporter;
import com.mirakyux.export4j.adapter.web.UnsupportedAction;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.edit.DocumentEditor;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/** CommonMark/GFM import, semantic edit, and four real output targets using only explicit local resources. */
public final class MarkdownImportExample {
    private MarkdownImportExample() { }
    public static void main(String[] args) throws IOException {
        Path output = Path.of("examples/build/markdown-import"); Files.createDirectories(output);
        String source = """
                # Imported report

                **STRONG-ONLY** *ITALIC-ONLY* ***BOTH-ONLY*** ~~STRIKE-ONLY~~ [LINK-ONLY](https://example.org/import)

                TARGET-OLD and literal ${untouched}.
                SOFT-ONLY\s\s
                HARD-ONLY

                3. STEP-ONLY
                   1. NESTED-ONLY
                4. NEXT-ONLY

                - BULLET-ONLY

                | Left | Right |
                |:---|---:|
                | CELL-ONLY | VALUE-ONLY |

                > QUOTE-ONLY

                ```java
                CODE-ONLY <tag> & value;
                ```

                ![IMAGE-ALT](logo.png)
                """;
        Files.writeString(output.resolve("source.md"), source);
        var pixels = new BufferedImage(12, 12, BufferedImage.TYPE_INT_RGB); var graphics = pixels.createGraphics();
        try { graphics.setColor(new Color(0x2463a1)); graphics.fillRect(0, 0, 12, 12); }
        finally { graphics.dispose(); }
        var bytes = new ByteArrayOutputStream();
        try { if (!ImageIO.write(pixels, "png", bytes)) { throw new IOException("PNG encoder unavailable"); } }
        finally { pixels.flush(); }
        Files.write(output.resolve("logo.png"), bytes.toByteArray());
        var image = new InlineImage(ImageResource.load(bytes.toByteArray()), 12, 12, 0, "", "");
        var policy = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
        var prepared = MarkdownImporter.builder().policy(policy).image("logo.png", image).build().read(source).prepare();
        var imported = prepared.execute();
        if (imported.report().losses().size() != 2 || !prepared.preview().issues().equals(imported.report().issues())) {
            throw new IllegalStateException("Markdown import loss reconciliation failed");
        }
        var edited = DocumentEditor.edit(imported.document()).replaceText("TARGET-OLD", "TARGET-READY").execute().document();
        var regular = FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf")));
        var engine = DocumentEngine.builder(regular)
                .fontRole("bold", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Bold.ttf"))))
                .fontRole("italic", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Italic.ttf"))))
                .fontRole("boldItalic", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-BoldItalic.ttf"))))
                .fontRole("code", regular).build();
        engine.export(edited).backend(new PdfBackend()).to(output.resolve("report.pdf"));
        try (var stream = Files.newOutputStream(output.resolve("report.docx"))) { new DocxAdapter().write(edited, stream); }
        var exportPolicy = ExportPolicy.builder().defaultAction(UnsupportedAction.WARN).build();
        HtmlAdapter.builder().policy(exportPolicy).fontRole("bold", new FontStyle("sans-serif", 700, false))
                .fontRole("italic", new FontStyle("sans-serif", 400, true)).fontRole("boldItalic", new FontStyle("sans-serif", 700, true))
                .fontRole("code", new FontStyle("sans-serif", 400, false)).build().export(edited).to(output.resolve("report.html"));
        MarkdownAdapter.builder().policy(exportPolicy).fontRole("bold", new FontStyle("sans-serif", 700, false))
                .fontRole("italic", new FontStyle("sans-serif", 400, true)).fontRole("boldItalic", new FontStyle("sans-serif", 700, true))
                .fontRole("code", new FontStyle("sans-serif", 400, false)).build().export(edited).to(output.resolve("report.md"));
        var report = new StringBuilder("feature\taction\ttreatment\tblock\tstart\tend\n");
        for (var issue : imported.report().issues()) {
            report.append(issue.feature()).append('\t').append(issue.action()).append('\t').append(issue.treatment()).append('\t')
                    .append(issue.location().blockId()).append('\t').append(issue.location().startUtf16()).append('\t')
                    .append(issue.location().endUtf16()).append('\n');
        }
        Files.writeString(output.resolve("losses.tsv"), report);
        System.out.println("Markdown import: " + edited.blocks().size() + " blocks, 2 reported semantic degradations");
    }
}
