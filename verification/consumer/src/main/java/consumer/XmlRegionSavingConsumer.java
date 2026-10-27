// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplateBundle;
import com.mirakyux.export4j.adapter.template.XmlTemplateWriter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Maven-only shared source editing and loss-aware conversion. */
public final class XmlRegionSavingConsumer {
    private XmlRegionSavingConsumer() { }
    public static void main(String[] args) throws IOException {
        Path output = Path.of(System.getProperty("consumer.outputDirectory")); Files.createDirectories(output);
        var original = XmlTemplateBundle.read(Files.readAllBytes(Path.of("../../examples/build/xml-region-saving/original.zip")));
        var prepared = original.conversion().edit(editor -> editor.replaceText("odd-header", "ODD", "CALL"))
                .prepare(XmlTemplateWriter.conversionTarget());
        var result = prepared.to(output.resolve("consumer-regions.zip"));
        if (result.changes().size() != 1 || !result.report().issues().isEmpty()
                || !result.report().issues().equals(prepared.preview().issues())) { throw new IOException("XML edit reports changed"); }
        var saved = XmlTemplateBundle.read(Files.readAllBytes(output.resolve("consumer-regions.zip")));
        if (!saved.compile().renderSections(Map.of()).equals(result.document())) { throw new IOException("page region model changed"); }
        var resources = saved.toBuilder(); original.fonts().forEach(resources::font); original.licenses().forEach(resources::license);
        var complete = resources.build(); complete.write(output.resolve("consumer-regions.zip"));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(complete.fonts().get("Regular.ttf")))))
                .fontRole("bold", FontGroup.strict(List.of(TrueTypeFontLoader.load(complete.fonts().get("Bold.ttf"))))).build();
        complete.conversion().prepare(engine.conversionTarget("PDF", new PdfBackend())).to(output.resolve("consumer-regions.pdf"));
        var policy = ProcessingPolicy.builder().feature("PAGE_OVERLAYS", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD).build();
        var docx = complete.conversion().policy(policy).prepare(new DocxAdapter().conversionTarget());
        var actual = docx.to(output.resolve("consumer-regions.docx"));
        if (actual.report().losses().isEmpty() || !actual.report().issues().equals(docx.preview().issues())) { throw new IOException("DOCX overlay losses changed"); }
        System.out.println("Maven XML page source save/reopen/edit and PDF/DOCX verified");
    }
}
