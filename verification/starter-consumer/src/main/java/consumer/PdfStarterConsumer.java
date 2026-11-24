// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.pdf.PdfExport;
import com.mirakyux.export4j.font.FontSource;
import com.mirakyux.export4j.model.Document;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PdfStarterConsumer {
    private PdfStarterConsumer() { }

    public static void main(String[] args) throws Exception {
        var output = Files.createDirectories(Path.of(System.getProperty("consumer.outputDirectory")));
        var font = FontSource.file(Path.of("../../assets/fonts/LiberationSans-Regular.ttf"));
        var document = Document.builder().paragraph("PDF starter output").build();
        try (var exporter = PdfExport.withFonts(font)) {
            exporter.write(document, output.resolve("report.pdf"));
        }
    }
}
