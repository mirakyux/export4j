// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocxPageRegionsTest {
    private static SectionedDocument document() {
        var header = PageRegion.builder().height(24).gap(4).paragraph(Paragraph.mixed("brand", List.of(
                new InlineText("HEADER ", InlineStyle.of(10)), new InlineField(InlineField.Kind.PAGE, "", 20, InlineStyle.of(10))), ParagraphFormat.of(10))).build();
        var bands = new PageRegions(header, PageRegion.EMPTY);
        return new SectionedDocument(DocumentMetadata.EMPTY, List.of(
                new Section("one", PageSettings.A4, List.of(new Paragraph("p1", "First", ParagraphFormat.of(10))), 1, bands),
                new Section("two", PageSettings.A4, List.of(new Paragraph("p2", "Second", ParagraphFormat.of(10))), 8, bands),
                new Section("three", PageSettings.A4, List.of(new Paragraph("p3", "Third", ParagraphFormat.of(10))), 0)));
    }
    private static Map<String, byte[]> parts() throws IOException {
        var out = new ByteArrayOutputStream(); new DocxAdapter().write(document(), out);
        var result = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) { result.put(entry.getName(), zip.readAllBytes()); }
        }
        return result;
    }
    private static byte[] zip(Map<String, byte[]> parts) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var entry : parts.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
        }
        return out.toByteArray();
    }
    private static String text(Map<String, byte[]> parts, String name) { return new String(parts.get(name), StandardCharsets.UTF_8); }
    private static void replace(Map<String, byte[]> parts, String name, String from, String to) {
        String source = text(parts, name); assertTrue(source.contains(from), from);
        parts.put(name, source.replace(from, to).getBytes(StandardCharsets.UTF_8));
    }
    private static DocxAdapter.ReadResult read(Map<String, byte[]> parts, DocxAdapter.Policy policy) throws IOException {
        return new DocxAdapter().read(new ByteArrayInputStream(zip(parts)), policy);
    }

    @Test void standardPartsFieldsAndExplicitClearSurviveStrictRoundTrip() throws IOException {
        var parts = parts();
        assertTrue(text(parts, "word/header1.xml").contains("w:instr=\"PAGE\""));
        assertTrue(text(parts, "word/settings.xml").contains("updateFields"));
        assertTrue(text(parts, "[Content_Types].xml").contains("wordprocessingml.header+xml"));
        var result = read(parts, DocxAdapter.Policy.STRICT);
        assertTrue(result.diagnostics().isEmpty());
        assertEquals(document().sections().stream().map(Section::pageRegions).toList(), result.document().sections().stream().map(Section::pageRegions).toList());
        assertSame(result.document().sections().get(0).pageRegions().header(), result.document().sections().get(1).pageRegions().header());
        assertEquals(PageRegions.EMPTY, result.document().sections().getLast().pageRegions());
        assertEquals(PageSettings.A4.margins(), result.document().sections().getFirst().pageSettings().margins());
        assertEquals(595.3, result.document().sections().getFirst().pageSettings().size().width());
    }

    @Test void missingDefaultReferenceInheritsPreviousSectionButExplicitEmptyPartClearsIt() throws IOException {
        var parts = parts();
        String main = text(parts, "word/document.xml");
        String reference = "<w:headerReference w:type=\"default\" r:id=\"header1\"/>";
        int second = main.indexOf(reference, main.indexOf(reference) + 1);
        assertTrue(second > 0);
        parts.put("word/document.xml", (main.substring(0, second) + main.substring(second + reference.length())).getBytes(StandardCharsets.UTF_8));
        var sections = read(parts, DocxAdapter.Policy.STRICT).document().sections();
        assertSame(sections.get(0).pageRegions().header(), sections.get(1).pageRegions().header());
        assertEquals(PageRegions.EMPTY, sections.get(2).pageRegions());
    }

    @Test void metadataFreeStandardBandsNeedCallerGeometryAndFieldWidth() throws IOException {
        var parts = parts();
        for (var name : List.copyOf(parts.keySet())) {
            if (name.matches("word/(header|footer)[0-9]+\\.xml")) {
                String source = text(parts, name).replaceAll(" od:(height|gap)=\"[^\"]*\"", "");
                parts.put(name, source.getBytes(StandardCharsets.UTF_8));
            }
        }
        assertThrows(IOException.class, () -> read(parts, DocxAdapter.Policy.STRICT));
        // Supply zero-height fallbacks only for empty bands by retaining their explicit geometry.
        for (var name : List.of("word/header2.xml", "word/footer1.xml")) {
            parts.put(name, text(parts, name).replace(" mc:Ignorable=\"od\"", " mc:Ignorable=\"od\" od:height=\"0\" od:gap=\"0\"").getBytes(StandardCharsets.UTF_8));
        }
        replace(parts, "word/header1.xml", " od:width=\"20.0\"", "");
        var adapter = DocxAdapter.builder().regionGeometry(new DocxAdapter.RegionGeometry(24, 4, 0, 0)).build();
        assertThrows(IOException.class, () -> adapter.read(new ByteArrayInputStream(zip(parts)), DocxAdapter.Policy.STRICT));
        var read = adapter.read(new ByteArrayInputStream(zip(parts)), DocxAdapter.Policy.STRICT, 20);
        assertEquals(24, read.document().sections().getFirst().pageRegions().header().height());
        assertEquals(PageSettings.A4.margins(), read.document().sections().getFirst().pageSettings().margins());
        assertEquals(841.9, read.document().sections().getFirst().pageSettings().size().height());
    }

    @Test void unsafeMissingExternalAndMismatchedRegionRelationshipsFailInBothPolicies() throws IOException {
        for (String target : List.of("../secret.xml", "https://example.com/header.xml", "missing.xml", "/header.xml", "header1.xml?secret")) {
            var parts = parts(); replace(parts, "word/_rels/document.xml.rels", "Target=\"header1.xml\"", "Target=\"" + target + "\"");
            for (var policy : DocxAdapter.Policy.values()) { assertThrows(IOException.class, () -> read(parts, policy), target); }
        }
        var external = parts(); replace(external, "word/_rels/document.xml.rels", "Target=\"header1.xml\"", "Target=\"header1.xml\" TargetMode=\"External\"");
        assertThrows(IOException.class, () -> read(external, DocxAdapter.Policy.ALLOW_LOSS));
        var mismatch = parts(); replace(mismatch, "word/document.xml", "r:id=\"header1\"", "r:id=\"footer1\"");
        assertThrows(IOException.class, () -> read(mismatch, DocxAdapter.Policy.STRICT));
        var sharedKind = parts(); replace(sharedKind, "word/_rels/document.xml.rels", "Target=\"footer1.xml\"", "Target=\"header1.xml\"");
        assertThrows(IOException.class, () -> read(sharedKind, DocxAdapter.Policy.ALLOW_LOSS));
    }

    @Test void duplicateReferencesAndMalformedGeometryAreNeverTreatedAsSuccessfulImports() throws IOException {
        var duplicate = parts(); String reference = "<w:headerReference w:type=\"default\" r:id=\"header1\"/>";
        replace(duplicate, "word/document.xml", reference, reference + reference);
        assertThrows(IOException.class, () -> read(duplicate, DocxAdapter.Policy.ALLOW_LOSS));
        for (var height : List.of("NaN", "Infinity", "-1", "0")) {
            var parts = parts(); replace(parts, "word/header1.xml", "od:height=\"24.0\"", "od:height=\"" + height + "\"");
            assertThrows(IOException.class, () -> read(parts, DocxAdapter.Policy.ALLOW_LOSS));
        }
        var badDistance = parts(); replace(badDistance, "word/document.xml", "w:header=\"1080\"", "w:header=\"NaN\"");
        assertThrows(IOException.class, () -> read(badDistance, DocxAdapter.Policy.ALLOW_LOSS));
        var marker = parts(); replace(marker, "word/header2.xml", "od:emptyBand=\"true\"/>", "od:emptyBand=\"true\"><w:r><w:t>Hidden</w:t></w:r></w:p>");
        assertThrows(IOException.class, () -> read(marker, DocxAdapter.Policy.ALLOW_LOSS));
    }

    @Test void firstEvenVariantsAndUnknownBandMarkupHaveExplicitLossDiagnostics() throws IOException {
        var first = parts(); replace(first, "word/document.xml", "w:headerReference w:type=\"default\"", "w:headerReference w:type=\"first\"");
        assertThrows(IOException.class, () -> read(first, DocxAdapter.Policy.STRICT));
        assertFalse(read(first, DocxAdapter.Policy.ALLOW_LOSS).diagnostics().isEmpty());
        var unknown = parts(); replace(unknown, "word/header1.xml", "</w:hdr>", "<w:unknown/></w:hdr>");
        assertThrows(IOException.class, () -> read(unknown, DocxAdapter.Policy.STRICT));
        assertTrue(read(unknown, DocxAdapter.Policy.ALLOW_LOSS).diagnostics().stream().anyMatch(value -> value.part().equals("word/header1.xml")));
    }

    @Test void bodyReferencesCannotTargetRepeatingBandIds() {
        var source = document().sections().getFirst();
        var body = Paragraph.mixed("reference", List.of(new InlineField(InlineField.Kind.REFERENCE_PAGE, "brand", 20, InlineStyle.of(10))), ParagraphFormat.of(10));
        var value = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", source.pageSettings(), List.of(body), 0, source.pageRegions())));
        var output = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> new DocxAdapter().write(value, output));
        assertEquals(0, output.size());
    }

    @Test void bodyAndBandIdsCannotCollideAndRegionBudgetsApplyBeforeOutput() throws IOException {
        var parts = parts(); replace(parts, "word/header1.xml", "od:id=\"brand\"", "od:id=\"p1\"");
        assertThrows(IOException.class, () -> read(parts, DocxAdapter.Policy.STRICT));
        var small = DocxLimits.builder().maxBlocks(2).build();
        var out = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> new DocxAdapter(small).write(document(), out));
        assertEquals(0, out.size());
    }
}
