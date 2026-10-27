// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplateBundle;
import com.mirakyux.export4j.adapter.template.XmlTemplateWriter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Shared conversion and explicit resource reuse, compiled only against published Maven APIs. */
public final class XmlModelSavingConsumer {
    private XmlModelSavingConsumer() { }
    public static void main(String[] args) throws IOException {
        Path output = Path.of(System.getProperty("consumer.outputDirectory")); Files.createDirectories(output);
        var original = XmlTemplateBundle.read(Files.readAllBytes(Path.of("../../examples/build/xml-model-saving/original.zip")));
        var prepared = original.conversion().edit(editor -> editor.replaceText("intro", "ORIGINAL-MODEL", "CALLER-MODEL"))
                .prepare(XmlTemplateWriter.conversionTarget());
        Path saved = output.resolve("consumer-model.zip");
        var result = prepared.to(saved);
        if (result.changes().size() != 1 || !result.report().issues().equals(prepared.preview().issues()) || !result.report().issues().isEmpty()) {
            throw new IOException("shared save/edit reports changed");
        }
        var modelBundle = XmlTemplateBundle.read(Files.readAllBytes(saved));
        if (!modelBundle.compile().renderSections(Map.of()).equals(result.document())) { throw new IOException("saved model changed"); }
        // The semantic model has roles, not font handles. Resource ownership stays explicit.
        var resources = modelBundle.toBuilder(); original.fonts().forEach(resources::font); original.licenses().forEach(resources::license);
        var complete = resources.build(); complete.write(saved);
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(complete.fonts().get("Regular.ttf")))))
                .fontRole("bold", FontGroup.strict(List.of(TrueTypeFontLoader.load(complete.fonts().get("Bold.ttf"))))).build();
        complete.conversion().prepare(engine.conversionTarget("PDF", new PdfBackend())).to(output.resolve("consumer-model.pdf"));
        complete.conversion().prepare(new DocxAdapter().conversionTarget()).to(output.resolve("consumer-model.docx"));
        System.out.println("Maven XML model save/reopen/edit and conversion to PDF/DOCX verified");
    }
}
