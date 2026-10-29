// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocxStylesTest {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    @Test void defaultsParentStylesCharacterStylesAndDirectFormattingResolveInOrder() throws Exception {
        String styles = "<w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val='20'/><w:color w:val='112233'/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:before='40' w:after='60'/></w:pPr></w:pPrDefault></w:docDefaults>"
                + style("Base", "paragraph", "", "<w:pPr><w:ind w:left='120'/><w:spacing w:after='100'/></w:pPr><w:rPr><w:b/><w:sz w:val='28'/></w:rPr>")
                + style("Body", "paragraph", "Base", "<w:pPr><w:jc w:val='right'/></w:pPr><w:rPr><w:color w:val='224466'/></w:rPr>")
                + style("Accent", "character", "", "<w:rPr><w:i/><w:u w:val='single'/></w:rPr>");
        var result = read(styles, "<w:p><w:pPr><w:pStyle w:val='Body'/><w:spacing w:before='80'/></w:pPr><w:r><w:rPr><w:rStyle w:val='Accent'/><w:color w:val='345678'/></w:rPr><w:t>Text</w:t></w:r></w:p>");
        var p = (Paragraph) result.document().sections().getFirst().blocks().getFirst(); var run = p.inlines().getFirst();
        assertEquals(14, p.format().fontSize()); assertEquals(4, p.format().spaceBefore()); assertEquals(5, p.format().spaceAfter()); assertEquals(6, p.format().subsequentLineIndent());
        assertEquals(ParagraphAlignment.RIGHT, p.format().alignment()); assertEquals("boldItalic", run.style().fontRole()); assertEquals(14, run.style().fontSize()); assertEquals(0x345678, run.style().rgb()); assertTrue(run.style().underline());
        assertEquals(1, result.diagnostics().stream().filter(issue -> issue.element().equals("styleGraph")).count());
    }
    @Test void styleToggleParityAndDirectOffAreAppliedInsteadOfSynthesizingBold() throws Exception {
        String styles = "<w:docDefaults><w:rPrDefault><w:rPr><w:b/></w:rPr></w:rPrDefault></w:docDefaults>"
                + style("Base", "paragraph", "", "<w:rPr><w:b/></w:rPr>")
                + style("Body", "paragraph", "Base", "<w:rPr><w:b/><w:i/></w:rPr>")
                + style("CharBase", "character", "", "<w:rPr><w:i/></w:rPr>")
                + style("Char", "character", "CharBase", "<w:rPr><w:i/></w:rPr>");
        var result = read(styles, "<w:p><w:pPr><w:pStyle w:val='Body'/></w:pPr><w:r><w:rPr><w:rStyle w:val='Char'/><w:b w:val='0'/></w:rPr><w:t>Text</w:t></w:r></w:p>");
        var p = (Paragraph) result.document().sections().getFirst().blocks().getFirst(); assertEquals("italic", p.inlines().getFirst().style().fontRole());
    }
    @Test void defaultParagraphStyleAndInheritedOutlineMapToSemanticHeading() throws Exception {
        String styles = "<w:style w:type='paragraph' w:default='1' w:styleId='Default'><w:rPr><w:sz w:val='30'/></w:rPr></w:style>"
                + style("HeadingBase", "paragraph", "", "<w:pPr><w:outlineLvl w:val='1'/></w:pPr>") + style("CustomTitle", "paragraph", "HeadingBase", "");
        var result = read(styles, "<w:p><w:r><w:t>Default text</w:t></w:r></w:p><w:p><w:pPr><w:pStyle w:val='CustomTitle'/></w:pPr><w:r><w:t>Title</w:t></w:r></w:p>");
        var blocks = result.document().sections().getFirst().blocks(); assertEquals(15, ((Paragraph) blocks.getFirst()).format().fontSize()); assertEquals(2, ((Heading) blocks.get(1)).level());
    }
    @Test void explicitFontFamilyMapsToRealRoleKeysAndUnregisteredFamiliesAreDiagnosed() throws Exception {
        String styles = style("Body", "paragraph", "", "<w:rPr><w:rFonts w:ascii='Registered' w:hAnsi='Registered'/><w:b/><w:i/></w:rPr>");
        byte[] source = fixture(styles, "<w:p><w:pPr><w:pStyle w:val='Body'/></w:pPr><w:r><w:t>Text</w:t></w:r></w:p>");
        var adapter = DocxAdapter.builder().fontFamily("Registered", new DocxAdapter.FontRoles("", "real-bold", "real-italic", "real-both")).build();
        var result = adapter.read(new ByteArrayInputStream(source), DocxAdapter.Policy.ALLOW_LOSS);
        assertEquals("real-both", ((Paragraph) result.document().sections().getFirst().blocks().getFirst()).inlines().getFirst().style().fontRole());
        assertTrue(result.diagnostics().stream().noneMatch(issue -> issue.element().equals("rFonts")));
        assertTrue(new DocxAdapter().read(new ByteArrayInputStream(source), DocxAdapter.Policy.ALLOW_LOSS).diagnostics().stream().anyMatch(issue -> issue.element().equals("rFonts")));
    }
    @Test void styleGraphLossIsNeverHiddenByStrictMode() throws Exception {
        byte[] source = fixture(style("Body", "paragraph", "", "<w:rPr><w:color w:val='123456'/></w:rPr>"), "<w:p><w:pPr><w:pStyle w:val='Body'/></w:pPr><w:r><w:t>Text</w:t></w:r></w:p>");
        assertThrows(IOException.class, () -> new DocxAdapter().read(new ByteArrayInputStream(source), DocxAdapter.Policy.STRICT));
    }
    @Test void nativePipelineMaterializesStylesOnceAndReportsTheActualGraphLossOnce() throws Exception {
        byte[] source = fixture(style("Body", "paragraph", "", "<w:rPr><w:color w:val='123456'/></w:rPr>"), "<w:p><w:pPr><w:pStyle w:val='Body'/></w:pPr><w:r><w:t>Text</w:t></w:r></w:p>");
        var adapter = new DocxAdapter(); var policy = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
        var nativeRead = adapter.readSemantic(new ByteArrayInputStream(source), DocxAdapter.Policy.ALLOW_LOSS);
        assertEquals(1, nativeRead.diagnostics().size()); assertEquals("styleGraph", nativeRead.diagnostics().getFirst().element());
        var prepared = adapter.conversion(source).policy(policy).prepare(adapter.conversionTarget()); var actual = prepared.to(new ByteArrayOutputStream());
        assertEquals(1, actual.report().issues().size()); assertEquals("STYLE_GRAPH", actual.report().issues().getFirst().feature()); assertEquals(prepared.preview().issues(), actual.report().issues());
        assertEquals(0x123456, ((Paragraph)actual.document().sections().getFirst().blocks().getFirst()).inlines().getFirst().style().rgb());
    }
    @Test void cyclesMissingBasesWrongTypesAndUnusedInvalidFormattingAlwaysFail() {
        for (String styles : new String[]{style("A", "paragraph", "B", "") + style("B", "paragraph", "A", ""),
                style("A", "paragraph", "Missing", ""), style("A", "paragraph", "B", "") + style("B", "character", "", ""),
                style("Unused", "paragraph", "", "<w:rPr><w:sz w:val='NaN'/></w:rPr>"),
                style("Unused", "paragraph", "", "<w:rPr><w:b w:val='sometimes'/></w:rPr>")}) {
            assertThrows(IOException.class, () -> read(styles, "<w:p><w:r><w:t>Text</w:t></w:r></w:p>"));
        }
    }
    @Test void duplicateDefinitionsDefaultsAndPropertiesCannotBeIgnored() {
        for (String styles : new String[]{style("A", "paragraph", "", "<w:rPr><w:b/></w:rPr>") + style("A", "paragraph", "", ""),
                "<w:docDefaults/><w:docDefaults/>", style("A", "paragraph", "", "<w:rPr><w:sz w:val='24'/><w:sz w:val='30'/></w:rPr>")}) {
            assertThrows(IOException.class, () -> read(styles, "<w:p/>"));
        }
    }

    @Test void cumulativeFormattingCopiesAreBoundedEvenWhenEncodedInputFits() throws Exception {
        String styles = style("Body", "paragraph", "", "<w:rPr><w:sz w:val='24'/><w:color w:val='224466'/></w:rPr>");
        byte[] source = fixture(styles, "<w:p><w:pPr><w:pStyle w:val='Body'/></w:pPr>" + "<w:r><w:t>X</w:t></w:r>".repeat(120) + "</w:p>");
        var adapter = new DocxAdapter(DocxLimits.DEFAULT.toBuilder().maxExpandedBytes(12_000).build());
        // The ZIP expands to less than 4 KiB; applying the same inherited formatting to 120 runs
        // exceeds the separate cumulative XML-copy work budget before it can grow unbounded.
        var failure = assertThrows(IOException.class, () -> adapter.read(new ByteArrayInputStream(source), DocxAdapter.Policy.ALLOW_LOSS));
        assertTrue(failure.getMessage().contains("materialization budget"));
        assertEquals(120, ((Paragraph) new DocxAdapter().read(new ByteArrayInputStream(source), DocxAdapter.Policy.ALLOW_LOSS)
                .document().sections().getFirst().blocks().getFirst()).text().length());
    }

    private static DocxAdapter.ReadResult read(String styles, String body) throws IOException { return new DocxAdapter().read(new ByteArrayInputStream(fixture(styles, body)), DocxAdapter.Policy.ALLOW_LOSS); }
    private static String style(String id, String type, String base, String props) { return "<w:style w:styleId='" + id + "' w:type='" + type + "'>" + (base.isEmpty() ? "" : "<w:basedOn w:val='" + base + "'/>") + props + "</w:style>"; }
    private static byte[] fixture(String styles, String body) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var parts = Map.of("[Content_Types].xml", "<Types/>", "word/styles.xml", "<w:styles xmlns:w='" + W + "'>" + styles + "</w:styles>",
                "word/document.xml", "<w:document xmlns:w='" + W + "'><w:body>" + body + "</w:body></w:document>");
        try (var zip = new ZipOutputStream(bytes)) { for (var entry : parts.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); } }
        return bytes.toByteArray();
    }
}
