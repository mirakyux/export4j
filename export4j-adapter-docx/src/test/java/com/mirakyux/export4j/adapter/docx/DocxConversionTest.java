// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Caption;
import com.mirakyux.export4j.model.CrossReference;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocxConversionTest {
    @Test void portableNavigationIsExplicitlyDiscardedWithoutHidingOldDeclarations() throws Exception {
        var source = new com.mirakyux.export4j.model.SectionedDocument(com.mirakyux.export4j.model.DocumentMetadata.EMPTY,
                List.of(new com.mirakyux.export4j.model.Section("s", com.mirakyux.export4j.model.PageSettings.A4,
                        List.of(new Paragraph("body", "Visible", ParagraphFormat.of(12))), 0)),
                com.mirakyux.export4j.model.DocumentNavigation.builder().bookmark("PRIVATE-NAVIGATION", "body", 1).build());
        var adapter = new DocxAdapter();
        assertThrows(java.io.IOException.class, () -> adapter.write(source, new ByteArrayOutputStream()));
        assertEquals("NAVIGATION", assertThrows(ProcessingException.class, () -> DocumentConversion.from(source, List.of()).prepare(adapter.conversionTarget())).issue().feature());
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var policy = ProcessingPolicy.builder().feature("NAVIGATION", action, ProcessingPolicy.Treatment.DISCARD).build();
            var prepared = DocumentConversion.from(source, List.of()).policy(policy).prepare(adapter.conversionTarget());
            var bytes = new ByteArrayOutputStream(); var actual = prepared.to(bytes);
            assertEquals(prepared.preview().issues(), actual.report().issues()); assertEquals(1, actual.report().losses().size());
            assertEquals(action == ProcessingPolicy.Action.WARN ? 1 : 0, actual.report().warnings().size());
            var read = adapter.read(new ByteArrayInputStream(bytes.toByteArray()), DocxAdapter.Policy.STRICT).document();
            assertEquals(com.mirakyux.export4j.model.DocumentNavigation.NONE, read.navigation());
            assertEquals("Visible", ((Paragraph) read.sections().getFirst().blocks().getFirst()).text());
        }
        var wrong = ProcessingPolicy.builder().feature("NAVIGATION", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.PRESERVE).build();
        assertThrows(IllegalArgumentException.class, () -> DocumentConversion.from(source, List.of()).policy(wrong).prepare(adapter.conversionTarget()));
    }
    @Test void captionAndIndependentReferenceRequireExplicitTextDegradation() throws Exception {
        var format = ParagraphFormat.of(12);
        var source = Document.builder().add(new Caption("figure", "Figure", "CAPTION", format))
                .add(new CrossReference("reference", "SEE=", "figure", CrossReference.Kind.NUMBER, format)).build();
        var target = new DocxAdapter().conversionTarget();
        var strict = assertThrows(ProcessingException.class, () -> DocumentConversion.from(source).prepare(target));
        assertEquals("CAPTION", strict.issue().feature());
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var policy = ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DEGRADE).build();
            var prepared = DocumentConversion.from(source).policy(policy).prepare(target);
            var output = new ByteArrayOutputStream(); var actual = prepared.to(output);
            assertEquals(List.of("CAPTION", "CROSS_REFERENCE"), actual.report().losses().stream().map(issue -> issue.feature()).toList());
            assertEquals(prepared.preview().issues(), actual.report().issues());
            assertEquals(action == ProcessingPolicy.Action.WARN ? 2 : 0, actual.report().warnings().size());
            var read = new DocxAdapter().read(new ByteArrayInputStream(output.toByteArray()), DocxAdapter.Policy.STRICT).document();
            assertEquals(List.of("CAPTION", "SEE=[unresolved reference]"), read.sections().getFirst().blocks().stream().map(block -> ((Paragraph) block).text()).toList());
            assertTrue(actual.document().sections().getFirst().blocks().getFirst() instanceof Caption);
        }
        var preserve = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.PRESERVE).build();
        assertThrows(IllegalArgumentException.class, () -> DocumentConversion.from(source).policy(preserve).prepare(target));
    }

    @Test void discardedNoteRemovesItsExplicitMarkerAndRetainsSurroundingText() throws Exception {
        var paragraph = Paragraph.mixed("body", List.of(new InlineText("BEFORE", InlineStyle.of(12)),
                new InlineField(InlineField.Kind.NOTE_MARKER, "foot", 16, InlineStyle.of(12)),
                new InlineText("AFTER", InlineStyle.of(12))), ParagraphFormat.of(12));
        var source = Document.builder().add(paragraph).build();
        var notes = List.of(new Note("foot", "body", "REMOVED-NOTE", Note.Kind.FOOTNOTE));
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var policy = ProcessingPolicy.builder().feature("NOTES", action, ProcessingPolicy.Treatment.DISCARD).build();
            var output = new ByteArrayOutputStream();
            var result = DocumentConversion.from(source).edit(editor -> editor.notes(notes)).policy(policy)
                    .prepare(new DocxAdapter().conversionTarget()).to(output);
            assertEquals(2, result.report().losses().size());
            var marker = result.report().losses().get(1);
            assertEquals("NOTES", marker.feature()); assertEquals(List.of("NOTE_MARKER_REMOVED"), marker.effects());
            assertEquals(6, marker.location().startUtf16()); assertEquals(7, marker.location().endUtf16());
            assertEquals("body", marker.location().blockId()); assertEquals(notes, result.notes());
            var reopened = new DocxAdapter().read(new ByteArrayInputStream(output.toByteArray()), DocxAdapter.Policy.STRICT).document();
            var body = (Paragraph) reopened.sections().getFirst().blocks().getFirst();
            assertEquals("BEFOREAFTER", body.text()); assertTrue(body.fields().isEmpty());
        }
        assertThrows(IllegalArgumentException.class, () -> DocumentConversion.from(source).policy(IGNORE).prepare(new DocxAdapter().conversionTarget()));
    }

    @Test void pageOverlayDiscardRequiresPolicyAndReportsContentAndPositionLoss() throws Exception {
        var content = new com.mirakyux.export4j.model.PageRegion(20, 0, List.of(new Paragraph("overlay", "SECRET-OVERLAY", ParagraphFormat.of(10))));
        var regions = com.mirakyux.export4j.model.PageRegions.builder()
                .background(new com.mirakyux.export4j.model.PageOverlay(0, 0, 120, content)).build();
        var source = new com.mirakyux.export4j.model.PageDocument(Document.builder().paragraph("body", "KEEP-BODY").build(), regions);
        var sections = new com.mirakyux.export4j.model.SectionedDocument(source.document().metadata(), List.of(
                new com.mirakyux.export4j.model.Section("s", source.document().pageSettings(), source.document().blocks(), 0, regions)));
        var adapter = new DocxAdapter(); var direct = new ByteArrayOutputStream();
        assertThrows(java.io.IOException.class, () -> adapter.write(source, direct)); assertEquals(0, direct.size());
        assertThrows(ProcessingException.class, () -> DocumentConversion.from(sections, List.of()).prepare(adapter.conversionTarget()));
        var policy = ProcessingPolicy.builder().feature("PAGE_OVERLAYS", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DISCARD).build();
        var prepared = DocumentConversion.from(sections, List.of()).policy(policy).prepare(adapter.conversionTarget());
        var output = new ByteArrayOutputStream(); var actual = prepared.to(output);
        assertEquals(prepared.preview().issues(), actual.report().issues());
        assertTrue(actual.report().warnings().isEmpty());
        var issue = actual.report().losses().getFirst();
        assertEquals("PAGE_OVERLAYS", issue.feature()); assertEquals("BACKGROUND:0", issue.location().region());
        assertEquals(List.of("PAGE_OVERLAY_CONTENT_AND_POSITION_REMOVED"), issue.effects());
        var restored = adapter.read(new ByteArrayInputStream(output.toByteArray()), DocxAdapter.Policy.STRICT).document();
        assertEquals("KEEP-BODY", ((Paragraph) restored.sections().getFirst().blocks().getFirst()).text());
        assertTrue(restored.sections().getFirst().pageRegions().background().isEmpty());
        assertEquals(1, source.pageRegions().background().size());
        var preserve = ProcessingPolicy.builder().feature("PAGE_OVERLAYS", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.PRESERVE).build();
        assertThrows(IllegalArgumentException.class, () -> DocumentConversion.from(sections, List.of()).policy(preserve).prepare(adapter.conversionTarget()));
    }

    private static final ProcessingPolicy IGNORE = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE).build();
    @Test void sourcePackageIsSnapshottedAndReadOnceBeforeEditingAndWriting() throws Exception {
        byte[] bytes = encode(Document.builder().paragraph("p", "OLD").build());
        var conversion = new DocxAdapter().conversion(bytes).edit(editor -> editor.replaceText("OLD", "READY"));
        java.util.Arrays.fill(bytes, (byte) 0);
        var prepared = conversion.prepare(new DocxAdapter().conversionTarget());
        var output = new ByteArrayOutputStream(); var actual = prepared.to(output);
        var imported = new DocxAdapter().read(new ByteArrayInputStream(output.toByteArray()), DocxAdapter.Policy.STRICT);
        assertEquals("READY", ((Paragraph) imported.document().sections().getFirst().blocks().getFirst()).text());
        assertTrue(actual.report().losses().isEmpty());
    }
    @Test void uneditedUnknownMarkupIsReportedUnderIgnoreAndStrictFails() throws Exception {
        var original = encode(Document.builder().paragraph("p", "Keep").build());
        var changed = rewrite(original, "word/document.xml", "<w:p", "<w:commentRangeStart w:id=\"1\"/><w:p");
        assertThrows(ProcessingException.class, () -> new DocxAdapter().conversion(changed).prepare(new DocxAdapter().conversionTarget()));
        var prepared = new DocxAdapter().conversion(changed).policy(IGNORE).prepare(new DocxAdapter().conversionTarget());
        var result = prepared.to(new ByteArrayOutputStream());
        assertTrue(result.report().warnings().isEmpty()); assertEquals(prepared.preview().issues(), result.report().issues());
        assertTrue(result.report().losses().stream().anyMatch(issue -> issue.stage().equals("IMPORT") && issue.feature().equals("OOXML_MARKUP")));
    }
    @Test void customFontRoleLossIsExplicitAndSourceStyleAndLinkStayUnchanged() throws Exception {
        var run = new InlineText("Keep", InlineStyle.of(12).toBuilder().fontRole("private-font").rgb(0x123456).build(), "https://example.org/keep");
        var paragraph = new Paragraph("p", List.of(run), ParagraphFormat.of(12));
        var document = Document.builder().add(paragraph).build();
        assertThrows(ProcessingException.class, () -> DocumentConversion.from(document).prepare(new DocxAdapter().conversionTarget()));
        var prepared = DocumentConversion.from(document).policy(IGNORE).prepare(new DocxAdapter().conversionTarget());
        var output = new ByteArrayOutputStream(); var actual = prepared.to(output);
        assertEquals("private-font", paragraph.inlines().getFirst().style().fontRole());
        assertEquals("FONT_ROLE", actual.report().losses().getFirst().feature()); assertEquals(0, actual.report().losses().getFirst().location().startUtf16());
        var imported = new DocxAdapter().read(new ByteArrayInputStream(output.toByteArray()), DocxAdapter.Policy.STRICT);
        var result = (Paragraph) imported.document().sections().getFirst().blocks().getFirst();
        assertEquals("", result.inlines().getFirst().style().fontRole()); assertEquals(0x123456, result.inlines().getFirst().style().rgb());
        assertEquals("https://example.org/keep", result.inlines().getFirst().hyperlink());
    }
    @Test void mergedCellLossUsesItsPhysicalColumn() throws Exception {
        var styled = TableCell.builder().content(List.of(new InlineText("Styled", InlineStyle.of(12).toBuilder().fontRole("private").build()))).build();
        var table = Table.builder("t").columns(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1))
                .row(TableCell.builder().text("Tall").rowSpan(2).build(), TableCell.builder().text("Top").build()).row(styled).build();
        var prepared = DocumentConversion.from(Document.builder().add(table).build()).policy(IGNORE).prepare(new DocxAdapter().conversionTarget());
        var issue = prepared.preview().losses().getFirst();
        assertEquals(1, issue.location().cellPath().getFirst().rowIndex()); assertEquals(1, issue.location().cellPath().getFirst().columnIndex());
    }
    @Test void noPreservationPathCanBeInventedByPolicy() {
        var document = Document.builder().add(new Paragraph("p", List.of(new InlineText("X", InlineStyle.of(12).toBuilder().fontRole("unknown").build())), ParagraphFormat.of(12))).build();
        var preserve = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.PRESERVE).build();
        assertThrows(IllegalArgumentException.class, () -> DocumentConversion.from(document).policy(preserve).prepare(new DocxAdapter().conversionTarget()));
    }
    private static byte[] encode(Document document) throws Exception {
        var output = new ByteArrayOutputStream(); new DocxAdapter().write(document, output); return output.toByteArray();
    }
    private static byte[] rewrite(byte[] bytes, String part, String old, String value) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var input = new ZipInputStream(new ByteArrayInputStream(bytes)); var zip = new ZipOutputStream(output)) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                byte[] content = input.readAllBytes();
                if (entry.getName().equals(part)) { content = new String(content, StandardCharsets.UTF_8).replaceFirst(java.util.regex.Pattern.quote(old), java.util.regex.Matcher.quoteReplacement(value)).getBytes(StandardCharsets.UTF_8); }
                zip.putNextEntry(new ZipEntry(entry.getName())); zip.write(content); zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
