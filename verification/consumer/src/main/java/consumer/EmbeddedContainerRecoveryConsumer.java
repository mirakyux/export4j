// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfRecoveryCodec;
import com.mirakyux.export4j.adapter.template.XmlModelRecovery;
import com.mirakyux.export4j.backend.raster.RasterBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Exercises only local published coordinates for three optional standard containers. */
public final class EmbeddedContainerRecoveryConsumer {
    private EmbeddedContainerRecoveryConsumer() { }
    public static void main(String[] args) throws IOException {
        Path output = Path.of(System.getProperty("consumer.outputDirectory")); Files.createDirectories(output);
        var pdfCodec = new PdfRecoveryCodec();
        var formats = List.of("PDF", "DOCX", "PNG_ZIP");
        for (String format : formats) {
            Path source = format.equals("PDF") ? Path.of("../../examples/build/embedded-pdf-recovery/original.pdf")
                    : Path.of("../../examples/build/embedded-zip-recovery/" + format.toLowerCase()
                            + "/original." + (format.equals("DOCX") ? "docx" : "png.zip"));
            byte[] bytes = Files.readAllBytes(source);
            var request = format.equals("PDF") ? XmlModelRecovery.openEmbedded(format, bytes, pdfCodec)
                    : XmlModelRecovery.openEmbedded(format, bytes);
            var resources = request.prepare().resources().orElseThrow();
            var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(resources.fonts().get("Regular.ttf"))))).build();
            var target = switch (format) {
                case "PDF" -> engine.conversionTarget("PDF", new com.mirakyux.export4j.backend.pdf.PdfBackend());
                case "DOCX" -> new DocxAdapter().conversionTarget();
                default -> engine.conversionTarget("PNG_ZIP", new RasterBackend(96));
            };
            var saved = XmlModelRecovery.capture(request.conversion().edit(editor -> editor.replaceText("ORIGINAL", "CALLER"))
                    .prepare(target)).resources(resources).prepare();
            String extension = format.equals("PDF") ? "pdf" : format.equals("DOCX") ? "docx" : "png.zip";
            Path result = output.resolve("consumer-embedded-" + format.toLowerCase() + "." + extension);
            if (format.equals("PDF")) { saved.writeEmbedded(pdfCodec, result); }
            else { saved.writeEmbedded(result); }
            var reopened = format.equals("PDF") ? XmlModelRecovery.openEmbedded(format, Files.readAllBytes(result), pdfCodec)
                    : XmlModelRecovery.openEmbedded(format, Files.readAllBytes(result));
            if (!reopened.prepare().recovered() || !reopened.prepare().resources().orElseThrow().licenses().equals(resources.licenses())) {
                throw new IOException("consumer recovery model/resources changed: " + format);
            }
        }
        System.out.println("Maven PDF/DOCX/PNG_ZIP: optional recovery, edit and resource reopen verified");
    }
}
