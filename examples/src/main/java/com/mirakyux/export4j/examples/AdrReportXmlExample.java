// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.text.TextDiagnostic;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Binds an editable XML report with merged tables, repeated records and naturally flowing pages. */
public final class AdrReportXmlExample {
    private static final Path OUTPUT = Path.of("examples/build/adr-report/xml.pdf");

    private AdrReportXmlExample() { }

    static SectionedDocument document(Map<String, Object> data) throws IOException {
        try (var input = AdrReportXmlExample.class.getResourceAsStream("/templates/adverse-reaction.xml")) {
            if (input == null) { throw new IOException("adverse-reaction example template is missing"); }
            try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                // Section rendering retains the repeated page-number footer.
                return XmlTemplate.builder().build().compile(reader).renderSections(data);
            }
        }
    }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT.getParent());
        var layout = AdrReportData.engine().export(document(AdrReportData.data()))
                .backend(new PdfBackend()).to(OUTPUT);
        // Selecting the explicitly configured Chinese fallback font is informational.
        layout.contextualDiagnostics().stream()
                .filter(value -> value.diagnostic().severity() == TextDiagnostic.Severity.WARNING)
                .findFirst().ifPresent(value -> { throw new IllegalStateException("ADR XML example warning: " + value); });
        System.out.println("XML adverse-reaction report: " + layout.display().pages().size()
                + " pages, " + OUTPUT.toAbsolutePath());
    }
}
