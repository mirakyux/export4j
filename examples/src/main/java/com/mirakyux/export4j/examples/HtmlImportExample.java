// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.web.ExportPolicy;
import com.mirakyux.export4j.adapter.web.FontStyle;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.HtmlImporter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
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

/** Offline HTML/CSS import, exact loss preview, semantic edit and four independent targets. */
public final class HtmlImportExample {
    private HtmlImportExample() { }
    public static void main(String[] args) throws IOException {
        Path output = Path.of("examples/build/html-import"); Files.createDirectories(output);
        String source = """
                <!doctype html><html><head><meta charset="utf-8"><style>
                p { color: #123456; } .picked {color: #2463a1;} #intro {color: #005522 !important;}
                .layout {float:left;} td {line-height:18pt;} .right {text-align:right;}
                </style></head><body>
                <h1 id="title">HTML imported report</h1>
                <p id="intro" class="picked layout" style="color:red">TARGET-OLD &amp; ${literal}</p>
                <p><strong>STRONG-HTML</strong> <em>ITALIC-HTML</em> <strong><em>BOTH-HTML</em></strong>
                <u>UNDER-HTML</u> <s>STRIKE-HTML</s> <a href="https://example.org/html">LINK-HTML</a><br>HARD-HTML</p>
                <ol start="3"><li>STEP-HTML<ol><li>NESTED-HTML</li></ol></li><li>NEXT-HTML</li></ol>
                <table id="merged"><thead><tr><th colspan="2">MERGED-HEAD</th></tr></thead>
                <tbody><tr><td rowspan="2">TALL-HTML</td><td class="right">RIGHT-HTML</td></tr><tr><td>LAST-HTML</td></tr></tbody></table>
                <table id="outer"><tr><td><p id="before">BEFORE-INNER</p><table id="inner"><tr><td>INNER-HTML</td></tr></table><p id="after">AFTER-INNER</p></td></tr></table>
                <blockquote><p>QUOTE-HTML</p></blockquote>
                <p><img src="logo.png" alt="HTML-ALT" width="16"></p>
                </body></html>
                """;
        Files.writeString(output.resolve("source.html"), source);
        var pixels = new BufferedImage(12, 12, BufferedImage.TYPE_INT_RGB); var graphics = pixels.createGraphics();
        try { graphics.setColor(new Color(0x2463a1)); graphics.fillRect(0, 0, 12, 12); } finally { graphics.dispose(); }
        var bytes = new ByteArrayOutputStream();
        try { if (!ImageIO.write(pixels, "png", bytes)) { throw new IOException("PNG encoder unavailable"); } } finally { pixels.flush(); }
        Files.write(output.resolve("logo.png"), bytes.toByteArray());
        var policy = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
        var prepared = HtmlImporter.builder().policy(policy)
                .image("logo.png", new InlineImage(ImageResource.load(bytes.toByteArray()), 12, 12, 0, "")).build().read(source).prepare();
        var imported = prepared.execute();
        if (imported.report().losses().size() != 2 || !prepared.preview().issues().equals(imported.report().issues())) { throw new IllegalStateException("HTML loss reconciliation failed"); }
        var edited = DocumentEditor.edit(imported.document()).replaceText("TARGET-OLD", "TARGET-READY").execute().document();
        var regular = FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf")));
        var engine = DocumentEngine.builder(regular)
                .fontRole("bold", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Bold.ttf"))))
                .fontRole("italic", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Italic.ttf"))))
                .fontRole("boldItalic", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-BoldItalic.ttf")))).build();
        engine.export(edited).backend(new PdfBackend()).to(output.resolve("report.pdf"));
        try (var stream = Files.newOutputStream(output.resolve("report.docx"))) { new DocxAdapter().write(edited, stream); }
        var exportPolicy = ExportPolicy.builder().defaultAction(UnsupportedAction.WARN).build();
        HtmlAdapter.builder().policy(exportPolicy).fontRole("bold", new FontStyle("sans-serif", 700, false))
                .fontRole("italic", new FontStyle("sans-serif", 400, true)).fontRole("boldItalic", new FontStyle("sans-serif", 700, true))
                .build().export(edited).to(output.resolve("report.html"));
        MarkdownAdapter.builder().policy(exportPolicy).fontRole("bold", new FontStyle("sans-serif", 700, false))
                .fontRole("italic", new FontStyle("sans-serif", 400, true)).fontRole("boldItalic", new FontStyle("sans-serif", 700, true))
                .build().export(edited).to(output.resolve("report.md"));
        var report = new StringBuilder("feature\taction\ttreatment\tblock\tstart\tend\n");
        for (var issue : imported.report().issues()) {
            report.append(issue.feature()).append('\t').append(issue.action()).append('\t').append(issue.treatment()).append('\t')
                    .append(issue.location().blockId()).append('\t').append(issue.location().startUtf16()).append('\t').append(issue.location().endUtf16()).append('\n');
        }
        Files.writeString(output.resolve("losses.tsv"), report);
    }
}
