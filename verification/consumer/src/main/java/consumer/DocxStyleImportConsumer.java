// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class DocxStyleImportConsumer {
    private DocxStyleImportConsumer() { }
    static void verify(DocumentEngine engine, Path output) throws IOException {
        var adapter = new DocxAdapter(); var base = new ByteArrayOutputStream(); adapter.write(Document.builder().paragraph("Placeholder").build(), base);
        var parts = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(base.toByteArray()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) { parts.put(entry.getName(), zip.readAllBytes()); }
        }
        String xml = new String(parts.get("word/document.xml"), StandardCharsets.UTF_8);
        xml = xml.replaceFirst("<w:p [^>]*>.*?</w:p>", "<w:p><w:pPr><w:pStyle w:val=\"Custom\"/></w:pPr><w:r><w:t>CALLER-STYLE-OLD</w:t></w:r></w:p>");
        parts.put("word/document.xml", xml.getBytes(StandardCharsets.UTF_8));
        parts.put("word/styles.xml", ("<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:style w:type=\"paragraph\" w:styleId=\"Custom\"><w:rPr><w:sz w:val=\"26\"/><w:color w:val=\"235711\"/></w:rPr></w:style></w:styles>").getBytes(StandardCharsets.UTF_8));
        var source = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(source)) { for (var entry : parts.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); } }
        Files.write(output.resolve("consumer-style-source.docx"), source.toByteArray());
        var policy = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
        for (var target : List.of(engine.conversionTarget("PDF", new PdfBackend()), adapter.conversionTarget(), new HtmlAdapter().conversionTarget())) {
            var prepared = adapter.conversion(source.toByteArray()).policy(policy).edit(editor -> editor.replaceText("CALLER-STYLE-OLD", "CALLER-STYLE-READY")).prepare(target);
            var result = prepared.to(output.resolve("consumer-style." + target.format().toLowerCase(Locale.ROOT)));
            if (!prepared.preview().issues().equals(result.report().issues()) || result.report().issues().stream().filter(issue -> issue.stage().equals("IMPORT")).count() != 1) { throw new IllegalStateException("isolated style graph loss missing"); }
        }
    }
}
