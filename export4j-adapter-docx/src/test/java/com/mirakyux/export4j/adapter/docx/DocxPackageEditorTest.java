// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.edit.EditLimits;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocxPackageEditorTest {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final ProcessingPolicy PRESERVE = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.PRESERVE).build();
    @TempDir Path temporary;

    @Test void noMatchRetainsTheExactOriginalArchiveAndInputIsSnapshotted() throws Exception {
        byte[] original = fixture("<w:p><w:r><w:t>Keep</w:t></w:r></w:p>", Map.of()); byte[] borrowed = original.clone();
        var request = new DocxAdapter().editPackage(borrowed).replaceText("Missing", "Ready"); Arrays.fill(borrowed, (byte) 0);
        var output = new ByteArrayOutputStream(); var result = request.prepare().to(output);
        assertArrayEquals(original, output.toByteArray()); assertTrue(result.changedParts().isEmpty()); assertEquals(0, result.changes().size());
    }
    @Test void plainTextChangesWhileStylesCommentsRevisionsFormsTocAndOtherPartsSurvive() throws Exception {
        String untouched = "<w:p><w:pPr><w:pStyle w:val='Custom'/></w:pPr><w:commentRangeStart w:id='0'/><w:r><w:t>Keep</w:t></w:r><w:commentRangeEnd w:id='0'/></w:p>"
                + "<w:p><w:ins w:id='1'><w:r><w:t>Inserted</w:t></w:r></w:ins><w:del w:id='2'><w:r><w:delText>Deleted</w:delText></w:r></w:del></w:p>"
                + "<w:sdt><w:sdtPr><w:alias w:val='Control'/></w:sdtPr><w:sdtContent><w:p><w:r><w:t>Form value</w:t></w:r></w:p></w:sdtContent></w:sdt>"
                + "<w:p><w:fldSimple w:instr='TOC'><w:r><w:t>Cached contents</w:t></w:r></w:fldSimple></w:p>";
        var extras = Map.of("word/comments.xml", "<w:comments xmlns:w='" + W + "'><w:comment w:id='0'><w:p><w:r><w:t>Comment</w:t></w:r></w:p></w:comment></w:comments>",
                "word/styles.xml", "<w:styles xmlns:w='" + W + "'><w:style w:styleId='Custom'><w:basedOn w:val='Normal'/><w:rPr><w:color w:val='123456'/></w:rPr></w:style></w:styles>");
        byte[] original = fixture("<w:p><w:r><w:t>OLD</w:t></w:r></w:p>" + untouched, extras);
        var before = parts(original); var prepared = new DocxAdapter().editPackage(original).replaceText("OLD", "READY").prepare();
        var output = new ByteArrayOutputStream(); var actual = prepared.to(output); var after = parts(output.toByteArray());
        assertEquals(prepared.preview().issues(), actual.report().issues()); assertTrue(actual.report().losses().isEmpty());
        assertEquals(List.of("word/document.xml"), actual.changedParts());
        for (var entry : before.entrySet()) { if (!entry.getKey().equals("word/document.xml")) { assertArrayEquals(entry.getValue(), after.get(entry.getKey())); } }
        var oldBody = DocxAdapter.child(DocxAdapter.parse(before.get("word/document.xml")), "body");
        var newBody = DocxAdapter.child(DocxAdapter.parse(after.get("word/document.xml")), "body");
        var oldNodes = DocxAdapter.children(oldBody); var newNodes = DocxAdapter.children(newBody);
        assertTrue(newNodes.getFirst().getTextContent().contains("READY"));
        for (int index = 1; index < oldNodes.size(); index++) { assertTrue(oldNodes.get(index).isEqualNode(newNodes.get(index))); }
    }
    @Test void replacementSpansUniformRunsWithoutDeletingRunNodesOrUnmatchedText() throws Exception {
        byte[] original = fixture("<w:p><w:r><w:t>A-OLD</w:t></w:r><w:r><w:t>-TOKEN-Z</w:t></w:r></w:p>", Map.of());
        var output = new ByteArrayOutputStream();
        var result = new DocxAdapter().editPackage(original).replaceText("OLD-TOKEN", "READY").prepare().to(output);
        var root = DocxAdapter.parse(parts(output.toByteArray()).get("word/document.xml"));
        assertEquals("A-READY-Z", root.getTextContent()); assertEquals(2, root.getElementsByTagNameNS(W, "r").getLength()); assertEquals(1, result.changes().size());
    }
    @Test void sourcePackageEditingRetainsLegacyWordCompatibilitySettings() throws Exception {
        var settings = "<w:settings xmlns:w='" + W + "'><w:compat><w:compatSetting w:name='compatibilityMode'"
                + " w:uri='http://schemas.microsoft.com/office/word' w:val='12'/></w:compat></w:settings>";
        byte[] original = fixture("<w:p><w:r><w:t>OLD</w:t></w:r></w:p>", Map.of("word/settings.xml", settings));
        var output = new ByteArrayOutputStream();
        var result = new DocxAdapter().editPackage(original).replaceText("OLD", "READY").prepare().to(output);
        assertEquals(List.of("word/document.xml"), result.changedParts());
        assertArrayEquals(parts(original).get("word/settings.xml"), parts(output.toByteArray()).get("word/settings.xml"));
    }
    @Test void repeatedMatchesAndSequentialOperationsUseTheirOwnSourceOffsets() throws Exception {
        var source = fixture("<w:p><w:r><w:t>OLD OLD</w:t></w:r></w:p>", Map.of());
        var output = new ByteArrayOutputStream();
        var result = new DocxAdapter().editPackage(source).replaceText("OLD", "LONGER").replaceText("LONGER", "NEW").prepare().to(output);
        assertEquals("NEW NEW", DocxAdapter.parse(parts(output.toByteArray()).get("word/document.xml")).getTextContent());
        assertEquals(List.of(0, 4, 0, 7), result.changes().stream().map(DocxPackageEditor.Change::startUtf16).toList());
    }
    @Test void mixedStyleRequiresRealTreatmentAndIgnoreRetainsDegradation() throws Exception {
        byte[] original = fixture("<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>OLD</w:t></w:r><w:r><w:t>-TOKEN</w:t></w:r></w:p>", Map.of());
        assertThrows(ProcessingException.class, () -> new DocxAdapter().editPackage(original).replaceText("OLD-TOKEN", "READY").prepare());
        var ignore = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE).build();
        var prepared = new DocxAdapter().editPackage(original).policy(ignore).replaceText("OLD-TOKEN", "READY").prepare();
        var output = new ByteArrayOutputStream(); var result = prepared.to(output);
        assertEquals(1, result.report().losses().size()); assertTrue(result.report().warnings().isEmpty());
        var root = DocxAdapter.parse(parts(output.toByteArray()).get("word/document.xml"));
        assertEquals("READY", root.getTextContent()); assertEquals(1, root.getElementsByTagNameNS(W, "b").getLength());
        var untouched = new ByteArrayOutputStream(); new DocxAdapter().editPackage(original).policy(PRESERVE).replaceText("OLD-TOKEN", "READY").prepare().to(untouched);
        assertArrayEquals(original, untouched.toByteArray());
    }
    @Test void markersFieldsRevisionsAndControlsArePreservedWhenTheirTextMatches() throws Exception {
        for (String paragraph : List.of("<w:p><w:commentRangeStart w:id='0'/><w:r><w:t>OLD</w:t></w:r><w:commentRangeEnd w:id='0'/></w:p>",
                "<w:p><w:fldSimple w:instr='PAGE'><w:r><w:t>OLD</w:t></w:r></w:fldSimple></w:p>",
                "<w:p><w:ins w:id='0'><w:r><w:t>OLD</w:t></w:r></w:ins></w:p>",
                "<w:sdt><w:sdtContent><w:p><w:r><w:t>OLD</w:t></w:r></w:p></w:sdtContent></w:sdt>")) {
            byte[] original = fixture(paragraph, Map.of()); var output = new ByteArrayOutputStream();
            assertThrows(ProcessingException.class, () -> new DocxAdapter().editPackage(original).replaceText("OLD", "READY").prepare());
            var result = new DocxAdapter().editPackage(original).policy(PRESERVE).replaceText("OLD", "READY").prepare().to(output);
            assertArrayEquals(original, output.toByteArray()); assertEquals("OOXML_TEXT_STRUCTURE", result.report().issues().getFirst().feature()); assertTrue(result.report().losses().isEmpty());
        }
    }
    @Test void crossParagraphFieldResultsAreNeverMistakenForOrdinaryText() throws Exception {
        var original = fixture("<w:p><w:r><w:fldChar w:fldCharType='begin'/><w:instrText> TOC </w:instrText><w:fldChar w:fldCharType='separate'/></w:r></w:p>"
                + "<w:p><w:r><w:t>OLD</w:t></w:r></w:p><w:p><w:r><w:fldChar w:fldCharType='end'/></w:r></w:p>", Map.of());
        var output = new ByteArrayOutputStream(); new DocxAdapter().editPackage(original).policy(PRESERVE).replaceText("OLD", "READY").prepare().to(output);
        assertArrayEquals(original, output.toByteArray());
    }
    @Test void skipRequestRollsBackEarlierChangesAndLosses() throws Exception {
        var original = fixture("<w:p><w:r><w:t>OLD</w:t></w:r></w:p><w:p><w:fldSimple w:instr='PAGE'><w:r><w:t>OLD</w:t></w:r></w:fldSimple></w:p>", Map.of());
        var skip = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.SKIP_REQUEST).build();
        var output = new ByteArrayOutputStream(); var result = new DocxAdapter().editPackage(original).policy(skip).replaceText("OLD", "READY").prepare().to(output);
        assertArrayEquals(original, output.toByteArray()); assertTrue(result.changes().isEmpty()); assertTrue(result.report().losses().isEmpty()); assertEquals(1, result.report().issues().size());
    }
    @Test void explicitHeaderSelectionLeavesFooterAndUnselectedHeaderBytesAlone() throws Exception {
        var extras = Map.of("word/header1.xml", "<w:hdr xmlns:w='" + W + "'><w:p><w:r><w:t>OLD</w:t></w:r></w:p></w:hdr>",
                "word/footer1.xml", "<w:ftr xmlns:w='" + W + "'><w:p><w:r><w:t>OLD</w:t></w:r></w:p></w:ftr>");
        var original = fixture("<w:p><w:r><w:t>Keep</w:t></w:r></w:p>", extras); var output = new ByteArrayOutputStream();
        var result = new DocxAdapter().editPackage(original).part("word/header1.xml").replaceText("OLD", "READY").prepare().to(output);
        var before = parts(original); var after = parts(output.toByteArray()); assertEquals(List.of("word/header1.xml"), result.changedParts());
        assertArrayEquals(before.get("word/footer1.xml"), after.get("word/footer1.xml")); assertArrayEquals(before.get("word/document.xml"), after.get("word/document.xml"));
    }
    @Test void signedProtectedMacroAndActivePackagesCannotBeBypassedByIgnore() throws Exception {
        var ignore = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.PRESERVE).build();
        for (var extra : List.of(Map.of("_xmlsignatures/sig1.xml", "<Signature/>"), Map.of("word/vbaProject.bin", "macro"),
                Map.of("word/settings.xml", "<w:settings xmlns:w='" + W + "'><w:documentProtection/></w:settings>"),
                Map.of("word/activeX/activeX1.xml", "<Control/>"))) {
            var source = fixture("<w:p><w:r><w:t>OLD</w:t></w:r></w:p>", extra);
            assertThrows(IOException.class, () -> new DocxAdapter().editPackage(source).policy(ignore).replaceText("OLD", "READY").prepare());
        }
        var field = fixture("<w:p><w:fldSimple w:instr='DDEAUTO server topic item'><w:r><w:t>OLD</w:t></w:r></w:fldSimple></w:p>", Map.of());
        assertThrows(IOException.class, () -> new DocxAdapter().editPackage(field).policy(ignore).prepare());
    }
    @Test void externalResourcesAndBrokenRelationshipsAlwaysFail() throws Exception {
        for (String relationship : List.of("<Relationship Id='r1' Type='image' Target='https://example.org/x.png' TargetMode='External'/>",
                "<Relationship Id='r1' Type='image' Target='media/missing.png'/>",
                "<Relationship Id='r1' Type='http://x/hyperlink' Target='file:///x' TargetMode='External'/>")) {
            var source = fixture("<w:p/>", Map.of("word/_rels/document.xml.rels", "<Relationships xmlns='" + R + "'>" + relationship + "</Relationships>"));
            if (relationship.contains("file:///")) {
                assertThrows(IllegalArgumentException.class, () -> new DocxAdapter().editPackage(source).policy(PRESERVE).prepare());
            } else {
                assertThrows(IOException.class, () -> new DocxAdapter().editPackage(source).policy(PRESERVE).prepare());
            }
        }
    }
    @Test void ordinaryPackageMetadataRelationshipsRemainValidAndUnchanged() throws Exception {
        var source = fixture("<w:p><w:r><w:t>OLD</w:t></w:r></w:p>", Map.of("docProps/core.xml", "<coreProperties/>",
                "_rels/.rels", "<Relationships xmlns='" + R + "'><Relationship Id='core' Type='http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties' Target='docProps/core.xml'/></Relationships>"));
        var output = new ByteArrayOutputStream(); new DocxAdapter().editPackage(source).replaceText("OLD", "READY").prepare().to(output);
        assertArrayEquals(parts(source).get("_rels/.rels"), parts(output.toByteArray()).get("_rels/.rels"));
    }
    @Test void unicodeAndGraphemeBoundariesCannotBeDegraded() throws Exception {
        var source = fixture("<w:p><w:r><w:t>a\u0301X</w:t></w:r></w:p>", Map.of());
        assertThrows(ProcessingException.class, () -> new DocxAdapter().editPackage(source).replaceText("a", "B").prepare());
        var output = new ByteArrayOutputStream(); new DocxAdapter().editPackage(source).policy(PRESERVE).replaceText("a", "B").prepare().to(output); assertArrayEquals(source, output.toByteArray());
        assertThrows(IllegalArgumentException.class, () -> new DocxAdapter().editPackage(source).replaceText("X", "\uD800"));
        assertThrows(IllegalArgumentException.class, () -> new DocxAdapter().editPackage(source).replaceText("X", "\n"));
        var discard = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD).build();
        assertThrows(IllegalArgumentException.class, () -> new DocxAdapter().editPackage(source).policy(discard).replaceText("a", "B").prepare());
    }
    @Test void byteAndCumulativeTextBudgetsAreEnforced() throws Exception {
        var source = fixture("<w:p><w:r><w:t>OLD</w:t></w:r></w:p><w:p><w:r><w:t>OLD</w:t></w:r></w:p>", Map.of());
        var limits = new EditLimits(10, 100, 10, 10, 10, 10000);
        assertThrows(IllegalArgumentException.class, () -> new DocxAdapter().editPackage(source).limits(limits).replaceText("OLD", "LARGER").prepare());
        assertThrows(IllegalArgumentException.class, () -> new DocxAdapter().editPackage(source).maxOutputBytes(10).replaceText("OLD", "NEW").prepare());
        assertThrows(IllegalArgumentException.class, () -> new DocxAdapter(new DocxLimits(10, 10, 10, 10, 10)).editPackage(source));
    }
    @Test void cancellationAndOutputFailurePreserveDestinationAndBorrowedStreamsStayOpen() throws Exception {
        var source = fixture("<w:p><w:r><w:t>OLD</w:t></w:r></w:p>", Map.of()); var cancelled = new AtomicBoolean();
        var prepared = new DocxAdapter().editPackage(source).execution(ExecutionOptions.builder().cancelled(cancelled::get).build()).replaceText("OLD", "READY").prepare();
        var target = temporary.resolve("report.docx"); Files.writeString(target, "OLD FILE"); cancelled.set(true);
        assertThrows(ExecutionException.class, () -> prepared.to(target)); assertEquals("OLD FILE", Files.readString(target));
        try (var files = Files.list(temporary)) { assertEquals(List.of(target), files.toList()); }
        cancelled.set(false);
        var closed = new AtomicBoolean(); var flushed = new AtomicBoolean();
        var stream = new ByteArrayOutputStream() { @Override public void close() { closed.set(true); } @Override public void flush() { flushed.set(true); } };
        var result = prepared.to(stream); assertFalse(closed.get()); assertFalse(flushed.get()); assertEquals(result.bytesWritten(), stream.size());
        var failed = new java.io.OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("controlled write failure"); }
            @Override public void close() { closed.set(true); }
        };
        assertEquals("controlled write failure", assertThrows(IOException.class, () -> prepared.to(failed)).getMessage());
        assertFalse(closed.get());
    }

    private static byte[] fixture(String body, Map<String, String> extra) throws IOException {
        var parts = new LinkedHashMap<String, String>();
        parts.put("[Content_Types].xml", "<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'><Default Extension='xml' ContentType='application/xml'/></Types>");
        parts.put("word/document.xml", "<w:document xmlns:w='" + W + "'><w:body>" + body + "</w:body></w:document>"); parts.putAll(extra);
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) { for (var part : parts.entrySet()) { zip.putNextEntry(new ZipEntry(part.getKey())); zip.write(part.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); } }
        return bytes.toByteArray();
    }
    private static Map<String, byte[]> parts(byte[] encoded) throws IOException { return new DocxAdapter().unzip(new ByteArrayInputStream(encoded)); }
}
