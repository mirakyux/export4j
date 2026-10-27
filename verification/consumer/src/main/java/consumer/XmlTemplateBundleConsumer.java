// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplateBundle;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Only published Maven APIs; input is a standard ZIP created independently with Python. */
public final class XmlTemplateBundleConsumer {
    private XmlTemplateBundleConsumer() { }
    public static void main(String[] args) throws IOException {
        Path output = Path.of(System.getProperty("consumer.outputDirectory")); Files.createDirectories(output);
        Path input = Path.of("../../build/verification/xml-template-bundle/independent.zip");
        XmlTemplateBundle original;
        try (var stream = Files.newInputStream(input)) { original = XmlTemplateBundle.read(stream); }
        var edited = original.toBuilder().source(original.source().replace("CALLER-TEMPLATE", "CALLER-EDITED"))
                .fragment("intro", original.fragments().get("intro").replace("ORIGINAL-FRAGMENT", "EDITED-FRAGMENT")).build();
        edited.write(output.resolve("consumer-template.zip"));
        var reopened = XmlTemplateBundle.read(Files.readAllBytes(output.resolve("consumer-template.zip")));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(reopened.fonts().get("Regular.ttf"))))).build();
        render(original, engine, output, "consumer-template-original", true);
        render(reopened, engine, output, "consumer-template-edited", false);
        System.out.println("Independent ZIP template loaded, edited, saved and rendered using Maven artifacts");
    }
    private static void render(XmlTemplateBundle bundle, DocumentEngine engine, Path output, String name, boolean approved) throws IOException {
        var document = bundle.compile().render(Map.of("title", "External", "approved", approved,
                "items", List.of(Map.of("name", "Gamma", "count", 4), Map.of("name", "Delta", "count", 5))));
        engine.write(document, new PdfBackend(), output.resolve(name + ".pdf"));
        try (var stream = Files.newOutputStream(output.resolve(name + ".docx"))) { new DocxAdapter().write(document, stream); }
    }
}
