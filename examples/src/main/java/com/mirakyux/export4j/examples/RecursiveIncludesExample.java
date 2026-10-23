// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.ParagraphFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Nested offline fragments with scoped static references and data-bound destinations. */
public final class RecursiveIncludesExample {
    private RecursiveIncludesExample() { }

    public static void main(String[] args) throws IOException {
        var output = Path.of("examples/build/recursive-includes");
        Files.createDirectories(output);
        var compiler = XmlTemplate.builder()
                .pageSettings(PageSettings.builder().size(420, 320).margins(24).build())
                .bodyFormat(ParagraphFormat.builder().fontSize(11).lineHeight(22).build())
                .image("mark", AuthoringExample.image().resource())
                .fragment("panel", """
                        <fragment version="1">
                          <page-break id="start"/>
                          <p id="parent">PARENT-${record.code} child=<page-reference target="leaf-target" width="16"/></p>
                          <include ref="leaf" prefix="leaf"/>
                        </fragment>
                        """)
                .fragment("leaf", """
                        <fragment version="1">
                          <numbered id="target" list-id="steps">TARGET-${record.code}</numbered>
                          <p id="local">LOCAL-${record.code} page=<page-reference target="target" width="16"/> number=<number-reference target="target" width="16"/></p>
                          <p id="dynamic">DYNAMIC-${record.code} page=<page-reference target="${record.target}" width="16"/></p>
                          <p id="global">GLOBAL-${record.code} page=<page-reference target="global-target" width="16"/></p>
                          <p id="fields">FINAL-${record.code} page=<page-number width="16"/> total=<page-count width="16"/></p>
                          <p id="links"><link href="${record.url}">TEXT-${record.code}<image ref="mark" width="8" height="8" alt="MARK-${record.code}"/></link></p>
                        </fragment>
                        """).build();
        var template = compiler.compile("""
                <document version="1" title="Recursive offline includes">
                  <p id="global-target">GLOBAL-TARGET</p>
                  <each items="records" as="record"><include ref="panel" prefix="batch"/></each>
                </document>
                """);
        var document = template.render(Map.of("records", List.of(
                Map.of("code", "A", "target", "batch-leaf-target@0", "url", "https://example.com/records/A?view=detail"),
                Map.of("code", "B", "target", "batch-leaf-target@1", "url", "mailto:docs@example.com"))));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf")))).build();
        var result = engine.export(document).backend(new PdfBackend()).to(output.resolve("includes.pdf"));
        if (result.display().pages().size() != 3 || !result.diagnostics().isEmpty()) {
            throw new IllegalStateException("recursive include fixture did not retain its page contract");
        }
        try (var stream = Files.newOutputStream(output.resolve("includes.docx"))) { new DocxAdapter().write(document, stream); }
    }
}
