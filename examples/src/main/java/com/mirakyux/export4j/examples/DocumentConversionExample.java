// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.web.FontStyle;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.HtmlImporter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownImporter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/** Real import/edit/export preparation; publication uses the exact encoding checked by preview. */
public final class DocumentConversionExample {
    private DocumentConversionExample() { }
    public static void main(String[] args) throws IOException {
        Path output = Path.of("examples/build/document-conversion"); Files.createDirectories(output);
        var pixels = new BufferedImage(12, 12, BufferedImage.TYPE_INT_RGB); var graphics = pixels.createGraphics();
        try { graphics.setColor(new Color(0x2463a1)); graphics.fillRect(0, 0, 12, 12); } finally { graphics.dispose(); }
        var bytes = new ByteArrayOutputStream();
        try { if (!ImageIO.write(pixels, "png", bytes)) { throw new IOException("PNG encoder unavailable"); } } finally { pixels.flush(); }
        Files.write(output.resolve("logo.png"), bytes.toByteArray());
        String source = """
                <h1>Conversion report</h1>
                <blockquote><p>QUOTE-CONVERSION</p></blockquote>
                <p id="edited"><b>TURN</b> OLD</p>
                <p id="untouched" style="color:#123456">UNCHANGED-COLOR</p>
                <p><a href="https://example.org/conversion">LINK-CONVERSION</a></p>
                <table><tr><th>HEAD-CONVERSION</th><th>VALUE</th></tr><tr><td>CELL-CONVERSION</td><td>42</td></tr></table>
                <p><img src="logo.png" alt="CONVERSION-ALT" width="16" height="16"></p>
                """;
        Files.writeString(output.resolve("source.html"), source);
        var importer = HtmlImporter.builder().image("logo.png", new InlineImage(ImageResource.load(bytes.toByteArray()), 12, 12, 0, "")).build();
        var warn = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
        var ignore = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE).build();
        var regular = FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf")));
        var engine = DocumentEngine.builder(regular).fontRole("bold", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Bold.ttf")))).build();
        var html = HtmlAdapter.builder().fontRole("bold", new FontStyle("sans-serif", 700, false)).build().conversionTarget();
        var markdown = MarkdownAdapter.builder().fontRole("bold", new FontStyle("sans-serif", 700, false)).build().conversionTarget();
        var docx = new DocxAdapter();
        var targets = List.of(engine.conversionTarget("PDF", new PdfBackend()), docx.conversionTarget(), html, markdown);
        for (var target : targets) {
            var prepared = importer.conversion(source).policy(warn).edit(editor -> editor.replaceText("TURN OLD", "READY-CONVERSION")).prepare(target);
            publish(prepared, output.resolve("report." + target.format().toLowerCase(java.util.Locale.ROOT).replace("markdown", "md")));
        }
        publish(importer.conversion(source).policy(ignore).edit(editor -> editor.replaceText("TURN OLD", "READY-CONVERSION")).prepare(markdown), output.resolve("ignored.md"));
        byte[] packageBytes = Files.readAllBytes(output.resolve("report.docx"));
        publish(docx.conversion(packageBytes).policy(warn).edit(editor -> editor.replaceText("READY-CONVERSION", "DOCX-READY"))
                .prepare(engine.conversionTarget("PDF", new PdfBackend())), output.resolve("from-docx.pdf"));
        publish(docx.conversion(packageBytes).policy(warn).edit(editor -> editor.replaceText("READY-CONVERSION", "DOCX-READY"))
                .prepare(html), output.resolve("from-docx.html"));
        String commonmark = "# Markdown conversion\n\nOLD **STRONG-CONVERSION**\n\n[LINK-CONVERSION](https://example.org/conversion)\n";
        Files.writeString(output.resolve("source.md"), commonmark);
        publish(new MarkdownImporter().conversion(commonmark).edit(editor -> editor.replaceText("OLD", "MD-READY")).prepare(html), output.resolve("from-markdown.html"));
    }

    private static void publish(DocumentConversion.Prepared prepared, Path target) throws IOException {
        var preview = prepared.preview(); var result = prepared.to(target);
        if (!preview.issues().equals(result.report().issues()) || result.bytesWritten() != Files.size(target)) { throw new IllegalStateException("conversion preview/output reconciliation failed"); }
        var report = new StringBuilder("stage\tfeature\taction\ttreatment\tblock\tstart\tend\teffect\n");
        for (var issue : result.report().issues()) {
            report.append(issue.stage()).append('\t').append(issue.feature()).append('\t').append(issue.action()).append('\t').append(issue.treatment()).append('\t')
                    .append(issue.location().blockId()).append('\t').append(issue.location().startUtf16()).append('\t').append(issue.location().endUtf16()).append('\t')
                    .append(String.join(",", issue.effects())).append('\n');
        }
        Files.writeString(target.resolveSibling(target.getFileName() + ".losses.tsv"), report);
    }
}
