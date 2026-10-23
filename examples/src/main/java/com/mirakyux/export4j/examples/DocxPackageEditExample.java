// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
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

/** Generates original OOXML preservation fixtures, never edits a user's document. */
public final class DocxPackageEditExample {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private DocxPackageEditExample() { }
    public static void main(String[] args) throws IOException {
        Path output = Path.of("examples/build/docx-package-edit"); Files.createDirectories(output);
        var base = Document.builder().metadata(new DocumentMetadata("Package editing fixture", "Export4j"))
                .heading("title", "Package preservation", 1).paragraph("edited", "BODY-OLD")
                .paragraph("custom", "STYLE-KEEP").build();
        var header = new PageRegion(24, 4, List.of(new Paragraph("header", "HEADER-OLD", ParagraphFormat.of(10))));
        var footer = new PageRegion(24, 4, List.of(new Paragraph("footer", "FOOTER-KEEP", ParagraphFormat.of(10))));
        var bytes = new ByteArrayOutputStream(); new DocxAdapter().write(new PageDocument(base, new PageRegions(header, footer)), bytes);
        var parts = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) { parts.put(entry.getName(), zip.readAllBytes()); }
        }
        String document = string(parts.get("word/document.xml"));
        document = document.replace("<w:p od:id=\"custom\"><w:pPr>", "<w:p od:id=\"custom\"><w:pPr><w:pStyle w:val=\"CustomBody\"/>");
        String preserved = """
                <w:p><w:commentRangeStart w:id="0"/><w:r><w:t>COMMENT-ANCHOR</w:t></w:r><w:commentRangeEnd w:id="0"/><w:r><w:commentReference w:id="0"/></w:r></w:p>
                <w:p><w:ins w:id="1" w:author="Export4j" w:date="2026-01-01T00:00:00Z"><w:r><w:t>REVISION-INSERT</w:t></w:r></w:ins><w:del w:id="2" w:author="Export4j" w:date="2026-01-01T00:00:00Z"><w:r><w:delText>REVISION-DELETE</w:delText></w:r></w:del></w:p>
                <w:sdt><w:sdtPr><w:alias w:val="Preserved control"/><w:id w:val="1234"/><w:text/></w:sdtPr><w:sdtContent><w:p><w:r><w:t>CONTROL-KEEP</w:t></w:r></w:p></w:sdtContent></w:sdt>
                <w:p><w:fldSimple w:instr="TOC" w:dirty="false"><w:r><w:t>TOC-CACHED</w:t></w:r></w:fldSimple></w:p>
                <w:p><w:r><w:fldChar w:fldCharType="begin"><w:ffData><w:name w:val="LegacyText"/><w:enabled/><w:textInput><w:default w:val="FORM-KEEP"/></w:textInput></w:ffData></w:fldChar></w:r><w:r><w:instrText> FORMTEXT </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>FORM-KEEP</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r></w:p>
                """;
        // Add passive structures before the final section; they are deliberately outside semantic import.
        int section = document.lastIndexOf("<w:sectPr");
        document = document.substring(0, section) + preserved + document.substring(section);
        document = document.replace("</w:sectPr>", "<w:titlePg/><w:headerReference w:type=\"first\" r:id=\"firstHeader\"/><w:headerReference w:type=\"even\" r:id=\"evenHeader\"/></w:sectPr>");
        parts.put("word/document.xml", document.getBytes(StandardCharsets.UTF_8));
        String styles = string(parts.get("word/styles.xml"));
        styles = styles.replace("</w:styles>", "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>"
                + "<w:style w:type=\"paragraph\" w:styleId=\"CustomBody\"><w:name w:val=\"Custom body\"/><w:basedOn w:val=\"Normal\"/><w:rPr><w:color w:val=\"123456\"/></w:rPr></w:style></w:styles>");
        parts.put("word/styles.xml", styles.getBytes(StandardCharsets.UTF_8));
        parts.put("word/header2.xml", ("<w:hdr xmlns:w=\"" + W + "\"><w:p><w:r><w:t>FIRST-HEADER-KEEP</w:t></w:r></w:p></w:hdr>").getBytes(StandardCharsets.UTF_8));
        parts.put("word/header3.xml", ("<w:hdr xmlns:w=\"" + W + "\"><w:p><w:r><w:t>EVEN-HEADER-KEEP</w:t></w:r></w:p></w:hdr>").getBytes(StandardCharsets.UTF_8));
        parts.put("word/comments.xml", ("<w:comments xmlns:w=\"" + W + "\"><w:comment w:id=\"0\" w:author=\"Export4j\"><w:p><w:r><w:t>COMMENT-KEEP</w:t></w:r></w:p></w:comment></w:comments>").getBytes(StandardCharsets.UTF_8));
        parts.put("word/settings.xml", ("<w:settings xmlns:w=\"" + W + "\"><w:evenAndOddHeaders/><w:updateFields w:val=\"false\"/></w:settings>").getBytes(StandardCharsets.UTF_8));
        String relationships = string(parts.get("word/_rels/document.xml.rels"));
        relationships = relationships.replace("</Relationships>", "<Relationship Id=\"firstHeader\" Type=\"" + R + "/header\" Target=\"header2.xml\"/>"
                + "<Relationship Id=\"evenHeader\" Type=\"" + R + "/header\" Target=\"header3.xml\"/>"
                + "<Relationship Id=\"comments\" Type=\"" + R + "/comments\" Target=\"comments.xml\"/>"
                + "<Relationship Id=\"settings\" Type=\"" + R + "/settings\" Target=\"settings.xml\"/></Relationships>");
        parts.put("word/_rels/document.xml.rels", relationships.getBytes(StandardCharsets.UTF_8));
        String types = string(parts.get("[Content_Types].xml"));
        var overrides = new StringBuilder();
        for (String part : List.of("header2", "header3", "comments", "settings")) {
            String type = part.startsWith("header") ? "header" : part;
            overrides.append("<Override PartName=\"/word/").append(part).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.").append(type).append("+xml\"/>");
        }
        parts.put("[Content_Types].xml", types.replace("</Types>", overrides + "</Types>").getBytes(StandardCharsets.UTF_8));
        var original = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(original)) { for (var part : parts.entrySet()) { zip.putNextEntry(new ZipEntry(part.getKey())); zip.write(part.getValue()); zip.closeEntry(); } }
        Files.write(output.resolve("source.docx"), original.toByteArray());
        var policy = ProcessingPolicy.builder().feature("OOXML_TEXT_STRUCTURE", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.PRESERVE).build();
        var prepared = new DocxAdapter().editPackage(original.toByteArray()).policy(policy).part("word/header1.xml")
                .replaceText("BODY-OLD", "BODY-READY").replaceText("HEADER-OLD", "HEADER-READY").replaceText("TOC-CACHED", "must not replace cached field").prepare();
        var result = prepared.to(output.resolve("edited.docx"));
        if (!prepared.preview().issues().equals(result.report().issues()) || result.changes().size() != 2 || result.report().issues().size() != 1 || !result.report().losses().isEmpty()) {
            throw new IllegalStateException("package edit reconciliation failed");
        }
        Files.writeString(output.resolve("result.tsv"), "changes\tchangedParts\tpreservedParts\tissues\tlosses\n" + result.changes().size() + "\t" + String.join(",", result.changedParts())
                + "\t" + result.preservedParts() + "\t" + result.report().issues().size() + "\t" + result.report().losses().size() + "\n");
    }
    private static String string(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
}
