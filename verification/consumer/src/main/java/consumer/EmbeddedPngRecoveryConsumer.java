// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.template.XmlModelRecovery;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.raster.RasterBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Uses published single-PNG and recovery APIs without project dependencies. */
public final class EmbeddedPngRecoveryConsumer {
    private EmbeddedPngRecoveryConsumer() { }
    public static void main(String[] args) throws IOException {
        Path output = Path.of(System.getProperty("consumer.outputDirectory")); Files.createDirectories(output);
        var request = XmlModelRecovery.openEmbedded("PNG", Files.readAllBytes(Path.of("../../examples/build/embedded-png-recovery/original.png")));
        var resources = request.prepare().resources().orElseThrow();
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(resources.fonts().get("Regular.ttf"))))).build();
        var saved = XmlModelRecovery.capture(request.conversion().edit(editor -> editor.replaceText("ORIGINAL", "CALLER"))
                .prepare(engine.conversionTarget("PNG", new RasterBackend(96).singlePage()))).resources(resources).prepare();
        saved.writeEmbedded(output.resolve("consumer-embedded-png.png"));
        var reopened = XmlModelRecovery.openEmbedded("PNG", saved.embeddedBytes());
        if (!reopened.prepare().recovered() || !reopened.prepare().resources().orElseThrow().licenses().equals(resources.licenses())) { throw new IOException("caller model/resources changed"); }
        reopened.conversion().prepare(engine.conversionTarget("PDF", new PdfBackend())).to(output.resolve("consumer-embedded-png.pdf"));
        System.out.println("Maven PNG: single-file model/resources, edit, PNG and PDF re-export verified");
    }
}
