// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxExport;
import com.mirakyux.export4j.adapter.docx.DocxFontFamilies;
import com.mirakyux.export4j.model.Document;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DocxStarterConsumer {
    private DocxStarterConsumer() { }

    public static void main(String[] args) throws Exception {
        var output = Files.createDirectories(Path.of(System.getProperty("consumer.outputDirectory")));
        var document = Document.builder().paragraph("DOCX starter output 中文").build();
        DocxExport.withFonts(new DocxFontFamilies("Arial", "Microsoft YaHei"))
                .write(document, output.resolve("report.docx"));
    }
}
