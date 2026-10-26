// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfFont;
import com.mirakyux.export4j.adapter.pdf.PdfPageEditor;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.model.Document;
import java.io.IOException;
import java.nio.file.Path;

final class PdfPageEditingConsumer {
    private PdfPageEditingConsumer() { }
    static void verify(DocumentEngine engine, FontFace font, Path output) throws IOException {
        var source = Document.builder().pageSettings(page -> page.size(260, 200).margins(20)).paragraph("CALLER-SOURCE-KEEP").build();
        Path original = output.resolve("consumer-page-source.pdf"); engine.export(source).backend(new PdfBackend()).to(original);
        var adapter = new PdfAdapter();
        var prepared = adapter.editPages(adapter.input(original, "")).text(0, "CALLER-STAMP", 20, 90,
                new PdfPageEditor.TextStyle(PdfFont.of(font.data().bytes()), 12, 0x224466, 1), PdfPageEditor.Layer.OVER_CONTENT).rotate(0, 90).prepare();
        var result = prepared.to(output.resolve("consumer-page-edited.pdf"));
        if (result.pageCount() != 1 || result.changes() != 2 || !result.report().issues().isEmpty()) { throw new IllegalStateException("isolated PDF page edits failed"); }
        var rasterPolicy = com.mirakyux.export4j.model.processing.ProcessingPolicy.builder()
                .feature("DOCUMENT_METADATA", com.mirakyux.export4j.model.processing.ProcessingPolicy.Action.WARN,
                        com.mirakyux.export4j.model.processing.ProcessingPolicy.Treatment.DISCARD).build();
        var images = adapter.render(adapter.input(output.resolve("consumer-page-edited.pdf"), "").allPages()).dpi(144).policy(rasterPolicy).prepare();
        images.toZip(output.resolve("consumer-raster.zip")); images.images().getFirst().to(output.resolve("consumer-raster.png"));
        var optimization = adapter.optimize(adapter.input(output.resolve("consumer-page-edited.pdf"), "")).prepare();
        var optimized = optimization.to(output.resolve("consumer-optimized.pdf"));
        if (!optimized.report().losses().isEmpty() || optimization.statistics().resampledImages() != 0) { throw new IllegalStateException("isolated lossless optimization changed semantics"); }
        var textPolicy = com.mirakyux.export4j.model.processing.ProcessingPolicy.builder()
                .feature("TEXT_FONT", com.mirakyux.export4j.model.processing.ProcessingPolicy.Action.WARN,
                        com.mirakyux.export4j.model.processing.ProcessingPolicy.Treatment.DEGRADE).build();
        adapter.editText(adapter.input(original, "")).replace(0, "SOURCE", "PUBLIC", PdfFont.of(font.data().bytes())).policy(textPolicy)
                .prepare().to(output.resolve("consumer-text-replaced.pdf"));
        var redactPolicy = com.mirakyux.export4j.model.processing.ProcessingPolicy.builder()
                .defaultRule(com.mirakyux.export4j.model.processing.ProcessingPolicy.Action.WARN, com.mirakyux.export4j.model.processing.ProcessingPolicy.Treatment.DISCARD)
                .feature("RASTERIZATION", com.mirakyux.export4j.model.processing.ProcessingPolicy.Action.WARN,
                        com.mirakyux.export4j.model.processing.ProcessingPolicy.Treatment.DEGRADE).build();
        adapter.redact(adapter.input(original, "")).area(0, 18, 18, 170, 20).policy(redactPolicy).prepare().to(output.resolve("consumer-redacted.pdf"));
        var recoveryPolicy = com.mirakyux.export4j.model.processing.ProcessingPolicy.builder()
                .defaultRule(com.mirakyux.export4j.model.processing.ProcessingPolicy.Action.WARN, com.mirakyux.export4j.model.processing.ProcessingPolicy.Treatment.DEGRADE)
                .feature("DOCUMENT_METADATA", com.mirakyux.export4j.model.processing.ProcessingPolicy.Action.WARN, com.mirakyux.export4j.model.processing.ProcessingPolicy.Treatment.DISCARD).build();
        for (var target : java.util.List.of(new com.mirakyux.export4j.adapter.docx.DocxAdapter().conversionTarget(),
                new com.mirakyux.export4j.adapter.web.HtmlAdapter().conversionTarget(), new com.mirakyux.export4j.adapter.web.MarkdownAdapter().conversionTarget())) {
            var recovered = adapter.importDocument(adapter.input(original, "")).conversion().policy(recoveryPolicy)
                    .edit(editor -> editor.replaceText("CALLER-SOURCE-KEEP", "CALLER-RECOVERED")).prepare(target);
            recovered.to(output.resolve("consumer-recovered." + target.format().toLowerCase(java.util.Locale.ROOT).replace("markdown", "md")));
        }
        verifyInlineAnchors(engine, adapter, output);
    }
    private static void verifyInlineAnchors(DocumentEngine engine, PdfAdapter adapter, Path output) throws IOException {
        var raster = new java.awt.image.BufferedImage(8, 6, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 6; y++) { for (int x = 0; x < 8; x++) { raster.setRGB(x, y, 0x2266AA); } }
        var imageBytes = new java.io.ByteArrayOutputStream();
        try { javax.imageio.ImageIO.write(raster, "png", imageBytes); } finally { raster.flush(); }
        var image = new com.mirakyux.export4j.model.InlineImage(
                com.mirakyux.export4j.model.ImageResource.load(imageBytes.toByteArray()), 40, 30, 0, "");
        var policy = com.mirakyux.export4j.model.processing.ProcessingPolicy.builder()
                .defaultRule(com.mirakyux.export4j.model.processing.ProcessingPolicy.Action.WARN,
                        com.mirakyux.export4j.model.processing.ProcessingPolicy.Treatment.DEGRADE);
        for (String feature : java.util.List.of("DOCUMENT_METADATA", "PDF_LOGICAL_TEXT", "PDF_HIDDEN_TEXT", "PDF_VECTOR_GRAPHICS")) {
            policy.feature(feature, com.mirakyux.export4j.model.processing.ProcessingPolicy.Action.WARN,
                    com.mirakyux.export4j.model.processing.ProcessingPolicy.Treatment.DISCARD);
        }
        for (boolean text : java.util.List.of(false, true)) {
            String name = "consumer-inline-anchor-" + (text ? "body" : "image-only");
            var document = Document.builder().pageSettings(page -> page.size(260, 200).margins(20));
            if (text) { document.paragraph("BEFORE-ANCHOR"); }
            document.add(com.mirakyux.export4j.model.Paragraph.mixed("inline-image", java.util.List.of(image),
                    com.mirakyux.export4j.model.ParagraphFormat.builder().fontSize(12).lineHeight(48).build()));
            if (text) { document.paragraph("AFTER-ANCHOR"); }
            Path source = output.resolve(name + "-source.pdf"); engine.export(document.build()).backend(new PdfBackend()).to(source);
            for (var target : java.util.List.of(new com.mirakyux.export4j.adapter.docx.DocxAdapter().conversionTarget(),
                    new com.mirakyux.export4j.adapter.web.HtmlAdapter().conversionTarget(),
                    new com.mirakyux.export4j.adapter.web.MarkdownAdapter().conversionTarget(), engine.conversionTarget("PDF", new PdfBackend()))) {
                var prepared = adapter.importDocument(adapter.input(source, "")).conversion().policy(policy.build()).prepare(target);
                var result = prepared.to(output.resolve(name + "." + target.format().toLowerCase(java.util.Locale.ROOT).replace("markdown", "md")));
                if (!prepared.preview().issues().equals(result.report().issues())
                        || result.report().issues().stream().anyMatch(issue -> issue.feature().equals("PDF_STRUCTURE"))
                        || result.report().issues().stream().noneMatch(issue -> issue.feature().equals("PDF_HIDDEN_TEXT"))) {
                    throw new IllegalStateException("isolated inline-image PDF recovery failed");
                }
            }
        }
    }
}
