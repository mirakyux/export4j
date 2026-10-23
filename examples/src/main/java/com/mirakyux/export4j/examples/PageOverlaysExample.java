// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.raster.RasterBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Fixed semantic overlays, final fields, image occlusion and explicit conversion losses. */
public final class PageOverlaysExample {
    private PageOverlaysExample() { }

    private static ImageResource fill(int rgb) throws IOException {
        var image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        try {
            for (int y = 0; y < 4; y++) { for (int x = 0; x < 4; x++) { image.setRGB(x, y, rgb); } }
            var bytes = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", bytes)) { throw new IOException("PNG encoder missing"); }
            return ImageResource.load(bytes.toByteArray());
        } finally { image.flush(); }
    }

    public static void main(String[] args) throws IOException {
        var output = Path.of("examples/build/page-overlays"); Files.createDirectories(output);
        var theme = DocumentTheme.builder().fontRole("bold").rgb(0x224488)
                .bodyFormat(format -> format.fontSize(11).lineHeight(32).spaceAfter(0))
                .backgroundStyle(style -> style.format(format -> format.fontSize(11).lineHeight(32).spaceAfter(0)))
                .foregroundStyle(style -> style.format(format -> format.fontSize(11).lineHeight(32).spaceAfter(0)))
                .cellFormat(format -> format.fontSize(10).lineHeight(15))
                .tableFormat(format -> format.padding(2).spaceAfter(0)).build();
        String body = "<p><image ref='body' width='80' height='24' alt='BODY-FILL'/></p><p role='' color='#000000'>BODY-%s</p>";
        String xml = """
                <document version='1' title='Fixed page overlays'>
                  <page width='360' height='260' margin='20'/>
                  <header variant='even' height='20'><p>EVEN-HEADER</p></header>
                  <background>
                    <box x='20' y='40' width='120' height='32'><p><image ref='back' width='120' height='24' alt='BACKGROUND-FILL'/></p></box>
                    <box x='20' y='110' width='260' height='32'><p>BACKGROUND-${label}</p></box>
                  </background>
                  <foreground>
                    <box x='20' y='40' width='40' height='32'><p><image ref='front' width='40' height='24' alt='FOREGROUND-FILL'/></p></box>
                    <box x='20' y='210' width='300' height='32'><p>FINAL <page-number width='20'/> OF <page-count width='20'/></p></box>
                    <box x='20' y='145' width='200' height='32'><table><columns><column weight='1'/></columns><row><cell>OVERLAY-TABLE</cell></row></table></box>
                  </foreground>
                  <section id='a' first-page-number='8'>%s<page-break/>%s</section>
                  <section id='b'><background/>%s</section>
                  <section id='c'><foreground/>%s</section>
                </document>
                """.formatted(body.formatted("ONE"), body.formatted("TWO"), body.formatted("THREE"), body.formatted("FOUR"));
        var source = XmlTemplate.builder().theme(theme).image("back", fill(0x224488)).image("body", fill(0x448822))
                .image("front", fill(0x882244)).build().compile(xml).renderSections(Map.of("label", "BOUND"));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf"))))
                .fontRole("bold", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Bold.ttf")))).build();
        var result = engine.export(source).backend(new PdfBackend()).to(output.resolve("overlays.pdf"));
        if (result.display().pages().size() != 4 || !result.contextualDiagnostics().isEmpty()) { throw new IllegalStateException("overlay fixture differs"); }
        try (var stream = Files.newOutputStream(output.resolve("overlays.svg"))) { new SvgBackend().write(result.display(), stream); }
        try (var stream = Files.newOutputStream(output.resolve("overlays-png.zip"))) { new RasterBackend(144).write(result.display(), stream); }
        var webPolicy = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE)
                .feature("PAGE_NUMBERING", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DISCARD)
                .feature("METADATA", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DISCARD).build();
        var request = DocumentConversion.from(source, List.of()).policy(webPolicy);
        var html = request.prepare(new HtmlAdapter().conversionTarget());
        var htmlResult = html.to(output.resolve("overlays.html"));
        var markdown = request.prepare(new MarkdownAdapter().conversionTarget());
        var markdownResult = markdown.to(output.resolve("overlays.md"));
        var discard = ProcessingPolicy.builder().feature("PAGE_OVERLAYS", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD).build();
        var docx = DocumentConversion.from(source, List.of()).policy(discard).prepare(new DocxAdapter().conversionTarget());
        var docxResult = docx.to(output.resolve("discarded-overlays.docx"));
        Files.writeString(output.resolve("loss-counts.txt"), "HTML=" + htmlResult.report().losses().size() + "\nMARKDOWN="
                + markdownResult.report().losses().size() + "\nDOCX=" + docxResult.report().losses().size() + "\n");
    }
}
