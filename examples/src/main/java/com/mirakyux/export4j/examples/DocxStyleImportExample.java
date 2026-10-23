// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.web.FontStyle;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.PageDocument;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Source fixtures declare ordinary OOXML inheritance; imported values use explicit physical fonts. */
public final class DocxStyleImportExample {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private DocxStyleImportExample() { }
    public static void main(String[] args) throws IOException {
        Path output = Path.of("examples/build/docx-style-import"); Files.createDirectories(output);
        var base = Document.builder().paragraph("Placeholder").build();
        var band = new PageRegion(24, 4, List.of(new Paragraph("band", "Placeholder", ParagraphFormat.of(10))));
        var original = new ByteArrayOutputStream(); new DocxAdapter().write(new PageDocument(base, new PageRegions(band, PageRegion.EMPTY)), original);
        var parts = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(original.toByteArray()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) { parts.put(entry.getName(), zip.readAllBytes()); }
        }
        String document = new String(parts.get("word/document.xml"), StandardCharsets.UTF_8);
        String body = """
                <w:p><w:pPr><w:pStyle w:val="ReportTitle"/></w:pPr><w:r><w:t>Styled import</w:t></w:r></w:p>
                <w:p><w:r><w:t>STYLE-OLD</w:t></w:r><w:r><w:rPr><w:rStyle w:val="Accent"/></w:rPr><w:t> ACCENT-KEEP</w:t></w:r></w:p>
                <w:p><w:pPr><w:jc w:val="right"/></w:pPr><w:r><w:t>STYLE-UNCHANGED</w:t></w:r></w:p>
                """;
        int start = document.indexOf("<w:body>") + "<w:body>".length(); int section = document.lastIndexOf("<w:sectPr");
        parts.put("word/document.xml", (document.substring(0, start) + body + document.substring(section)).getBytes(StandardCharsets.UTF_8));
        String styles = """
                <w:styles xmlns:w="%s">
                <w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Explicit Sans" w:hAnsi="Explicit Sans"/><w:sz w:val="24"/><w:color w:val="224466"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:line="360" w:lineRule="exact"/></w:pPr></w:pPrDefault></w:docDefaults>
                <w:style w:type="paragraph" w:default="1" w:styleId="Body"><w:name w:val="Body"/><w:pPr><w:spacing w:after="120"/></w:pPr></w:style>
                <w:style w:type="paragraph" w:styleId="ReportTitle"><w:name w:val="Report title"/><w:basedOn w:val="Body"/><w:pPr><w:outlineLvl w:val="0"/><w:spacing w:after="180" w:line="480" w:lineRule="exact"/></w:pPr><w:rPr><w:b/><w:sz w:val="36"/></w:rPr></w:style>
                <w:style w:type="character" w:styleId="Accent"><w:name w:val="Accent"/><w:rPr><w:i/><w:u w:val="single"/><w:color w:val="406020"/></w:rPr></w:style>
                <w:style w:type="paragraph" w:styleId="Header"><w:name w:val="Header"/><w:basedOn w:val="Body"/><w:rPr><w:sz w:val="20"/></w:rPr></w:style>
                </w:styles>
                """.formatted(W);
        parts.put("word/styles.xml", styles.getBytes(StandardCharsets.UTF_8));
        String header = new String(parts.get("word/header1.xml"), StandardCharsets.UTF_8);
        int paragraph = header.indexOf("<w:p");
        parts.put("word/header1.xml", (header.substring(0, paragraph) + "<w:p><w:pPr><w:pStyle w:val=\"Header\"/></w:pPr><w:r><w:t>HEADER-STYLE</w:t></w:r></w:p></w:hdr>").getBytes(StandardCharsets.UTF_8));
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) { for (var entry : parts.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); } }
        Files.write(output.resolve("source.docx"), bytes.toByteArray());
        var adapter = DocxAdapter.builder().fontFamily("Explicit Sans", new DocxAdapter.FontRoles("", "bold", "italic", "boldItalic")).build();
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf"))))
                .fontRole("bold", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Bold.ttf"))))
                .fontRole("italic", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Italic.ttf")))).build();
        var policy = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
        var targets = List.of(engine.conversionTarget("PDF", new PdfBackend()), new DocxAdapter().conversionTarget(), HtmlAdapter.builder()
                .fontRole("bold", new FontStyle("sans-serif", 700, false)).fontRole("italic", new FontStyle("sans-serif", 400, true)).build().conversionTarget());
        for (var target : targets) {
            var prepared = adapter.conversion(bytes.toByteArray()).policy(policy).edit(editor -> editor.replaceText("STYLE-OLD", "STYLE-READY")).prepare(target);
            var result = prepared.to(output.resolve("report." + target.format().toLowerCase(java.util.Locale.ROOT)));
            Files.writeString(output.resolve(target.format().toLowerCase(java.util.Locale.ROOT) + "-losses.tsv"), result.report().issues().stream()
                    .map(issue -> issue.stage() + "\t" + issue.feature() + "\t" + issue.reason()).collect(java.util.stream.Collectors.joining("\n")) + "\n");
            var features = result.report().issues().stream().map(issue -> issue.feature()).toList();
            var expected = target.format().equals("HTML") ? List.of("OOXML_MARKUP", "PAGE_GEOMETRY", "PAGE_REGIONS") : List.of("OOXML_MARKUP");
            if (!prepared.preview().issues().equals(result.report().issues()) || !features.equals(expected)) {
                throw new IllegalStateException("style import loss reconciliation failed");
            }
        }
    }
}
