// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedCommentRecoveryTest {
    private static final ProcessingReport EMPTY = new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of());

    @ParameterizedTest @ValueSource(strings = {"MARKDOWN", "SVG"})
    void threeRoundsRetainModelResourcesAndCurrentEditsWithoutKeepingOldSource(String format) throws IOException {
        var source = document("ORIGINAL😀");
        var resources = XmlTemplateBundle.builder("<document version='1'><p>UNUSED-SOURCE</p></document>")
                .font("Face.ttf", new byte[]{1, 2, 3}).license("Face.txt", "Notice\r\n").build();
        var saved = XmlModelRecovery.capture(DocumentConversion.from(source, List.of()).prepare(target(format))).resources(resources).prepare();
        byte[] first = saved.embeddedBytes();
        for (int round = 0; round < 3; round++) {
            byte[] file = saved.embeddedBytes(); var request = XmlModelRecovery.openEmbedded(format, file); Arrays.fill(file, (byte) 0);
            var opened = request.prepare(); assertTrue(opened.recovered()); assertEquals(source, opened.document());
            assertArrayEquals(new byte[]{1, 2, 3}, opened.resources().orElseThrow().fonts().get("Face.ttf"));
            assertEquals(resources.licenses(), opened.resources().orElseThrow().licenses());
            saved = XmlModelRecovery.capture(request.conversion().prepare(target(format))).resources(opened.resources().orElseThrow()).prepare();
        }
        assertArrayEquals(first, saved.embeddedBytes());
        var next = XmlModelRecovery.capture(XmlModelRecovery.openEmbedded(format, first).conversion()
                .edit(editor -> editor.replaceText("ORIGINAL", "EDITED")).prepare(target(format))).resources(resources).prepare();
        var opened = XmlModelRecovery.openEmbedded(format, next.embeddedBytes()).prepare();
        assertEquals("EDITED😀", text(opened.document()));
        assertFalse(opened.resources().orElseThrow().source().contains("ORIGINAL"));
        assertFalse(opened.resources().orElseThrow().source().contains("UNUSED-SOURCE"));
    }

    @ParameterizedTest @ValueSource(strings = {"MARKDOWN", "SVG"})
    void externalChangesAndFormatConflictsNeverRestoreOldModel(String format) throws IOException {
        var saved = save(format); byte[] current = utf8(new String(saved.embeddedBytes(), StandardCharsets.UTF_8).replace("ORIGINAL", "CURRENT"));
        var strict = assertThrows(ProcessingException.class, () -> XmlModelRecovery.openEmbedded(format, current).prepare());
        assertEquals("STALE_OR_CONFLICTING_COMPANION", strict.issue().reason());
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var policy = rule(action, ProcessingPolicy.Treatment.DEGRADE);
            var opened = XmlModelRecovery.openEmbedded(format, current).policy(policy).fallback((bytes, shared) -> {
                assertArrayEquals(utf8(new String(saved.documentBytes(), StandardCharsets.UTF_8).replace("ORIGINAL", "CURRENT")), bytes);
                return new DocumentConversion.Input(document("CURRENT"), List.of(), EMPTY);
            }).prepare();
            assertFalse(opened.recovered()); assertEquals("CURRENT", text(opened.document())); assertTrue(opened.resources().isEmpty());
            assertEquals(1, opened.report().losses().size()); assertEquals(action == ProcessingPolicy.Action.WARN ? 1 : 0, opened.report().warnings().size());
            assertThrows(IllegalArgumentException.class, () -> XmlModelRecovery.openEmbedded(format, current).policy(policy).prepare());
        }
        var conflict = assertThrows(ProcessingException.class, () -> XmlModelRecovery.openEmbedded("HTML", saved.embeddedBytes()).prepare());
        assertEquals("FORMAT_MISMATCH", conflict.issue().reason());
        assertThrows(IllegalStateException.class, () -> XmlModelRecovery.openEmbedded(format, current).companion(saved.companionBytes().orElseThrow()));
        assertThrows(ProcessingException.class, () -> XmlModelRecovery.openEmbedded(format, saved.documentBytes()).prepare());
    }

    @ParameterizedTest @ValueSource(strings = {"MARKDOWN", "SVG"})
    void brokenCommentOrCorruptResourcesAreNotIgnored(String format) throws IOException {
        var saved = save(format); String file = new String(saved.embeddedBytes(), StandardCharsets.UTF_8);
        int marker = file.indexOf("\n<!--export4j-model-recovery\n"); var called = new AtomicBoolean();
        var entries = TemplateBundleIO.unzip(saved.companionBytes().orElseThrow(), XmlTemplateBundle.Limits.DEFAULT, true);
        entries.put("template.xml", utf8(new String(entries.get("template.xml"), StandardCharsets.UTF_8).replace("ORIGINAL", "HIDDEN")));
        byte[] corrupt = CommentRecoveryEnvelope.encode(saved.documentBytes(), TemplateBundleIO.zip(entries, XmlTemplateBundle.Limits.DEFAULT), 1_000_000);
        for (byte[] bytes : List.of(utf8(file + file.substring(marker)), utf8(file.substring(0, file.length() - 1)),
                utf8(file.replace("recovery\n", "recovery\n!")), corrupt)) {
            assertThrows(IOException.class, () -> XmlModelRecovery.openEmbedded(format, bytes)
                    .policy(rule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE))
                    .fallback((current, policy) -> { called.set(true); return new DocumentConversion.Input(document("BAD"), List.of(), EMPTY); }).prepare());
        }
        assertFalse(called.get());
    }

    @ParameterizedTest @ValueSource(strings = {"MARKDOWN", "SVG"})
    void totalBudgetCancellationAndBorrowedOutputProtectPublication(String format, @TempDir Path directory) throws IOException {
        var saved = save(format); byte[] file = saved.embeddedBytes();
        var limit = new XmlModelRecovery.Limits(file.length - 1, 1024 * 1024, 100, XmlTemplateBundle.Limits.DEFAULT);
        assertThrows(IOException.class, () -> XmlModelRecovery.openEmbedded(format, file).limits(limit).prepare());
        var bounded = XmlModelRecovery.capture(DocumentConversion.from(document("ORIGINAL"), List.of()).prepare(target(format))).limits(limit).prepare();
        Path old = directory.resolve("target"); Files.writeString(old, "OLD");
        assertThrows(IOException.class, () -> bounded.writeEmbedded(old)); assertEquals("OLD", Files.readString(old));
        var stop = new AtomicBoolean(); var execution = ExecutionOptions.builder().cancelled(stop::get).build();
        var cancelled = XmlModelRecovery.capture(DocumentConversion.from(document("ORIGINAL"), List.of()).prepare(target(format))).execution(execution).prepare();
        stop.set(true); assertThrows(ExecutionException.class, () -> cancelled.writeEmbedded(old)); assertEquals("OLD", Files.readString(old));
        assertThrows(ExecutionException.class, () -> XmlModelRecovery.openEmbedded(format, file).execution(execution).prepare());
        var touched = new AtomicBoolean();
        var output = new ByteArrayOutputStream() {
            @Override public void close() { touched.set(true); }
            @Override public void flush() { touched.set(true); }
        };
        assertEquals(saved.preview().issues(), saved.writeEmbedded(output).issues()); assertArrayEquals(file, output.toByteArray()); assertFalse(touched.get());
        saved.writeEmbedded(old); assertArrayEquals(file, Files.readAllBytes(old));
        try (var files = Files.list(directory)) { assertEquals(List.of(old), files.toList()); }
    }

    @ParameterizedTest @ValueSource(strings = {"MARKDOWN", "SVG"})
    void lossyCaptureNeverAddsRicherHiddenSource(String format) throws IOException {
        var issue = new ProcessingIssue("EXPORT", "MODEL", format, "CONTENT", "EXPORT",
                new ProcessingIssue.Location("", "DOCUMENT", "", List.of(), -1, -1), "REMOVED",
                ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD, List.of("REMOVED"));
        var target = new DocumentConversion.Target(format, (model, notes, policy, output) -> {
            output.write(utf8("PUBLIC")); return new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of(issue));
        });
        var conversion = DocumentConversion.from(document("PRIVATE"), List.of()).prepare(target);
        assertThrows(ProcessingException.class, () -> XmlModelRecovery.capture(conversion).prepare());
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var saved = XmlModelRecovery.capture(conversion).policy(rule(action, ProcessingPolicy.Treatment.DISCARD)).prepare();
            assertArrayEquals(utf8("PUBLIC"), saved.embeddedBytes()); assertTrue(saved.companionBytes().isEmpty());
        }
    }

    @Test void genericHtmlIsByteCompatibleWhileOtherFormatsAndOldHtmlMisuseAreRejected() throws IOException {
        var html = save("HTML"); assertArrayEquals(html.embeddedHtmlBytes(), html.embeddedBytes());
        assertEquals(XmlModelRecovery.openEmbeddedHtml(html.embeddedHtmlBytes()).prepare().document(), XmlModelRecovery.openEmbedded("HTML", html.embeddedBytes()).prepare().document());
        assertThrows(IllegalStateException.class, () -> save("MARKDOWN").embeddedHtmlBytes());
        assertThrows(IllegalArgumentException.class, () -> XmlModelRecovery.openEmbedded("PDF", new byte[0]));
        assertThrows(IllegalStateException.class, () -> save("PDF").embeddedBytes());
    }

    private static XmlModelRecovery.Saved save(String format) throws IOException { return XmlModelRecovery.capture(DocumentConversion.from(document("ORIGINAL"), List.of()).prepare(target(format))).prepare(); }
    private static DocumentConversion.Target target(String format) {
        return new DocumentConversion.Target(format, (document, notes, policy, output) -> {
            String text = text(document);
            output.write(utf8(format.equals("SVG") ? "<svg xmlns='http://www.w3.org/2000/svg'><text>" + text + "</text></svg>" : text + "\r\n\r\n")); return EMPTY;
        });
    }
    private static SectionedDocument document(String value) {
        return new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", PageSettings.A4,
                List.of(Paragraph.mixed("p", List.of(new InlineText(value, InlineStyle.of(12))), ParagraphFormat.of(12))), 0)));
    }
    private static String text(SectionedDocument document) { return ((Paragraph) document.sections().getFirst().blocks().getFirst()).text(); }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static ProcessingPolicy rule(ProcessingPolicy.Action action, ProcessingPolicy.Treatment treatment) { return ProcessingPolicy.builder().feature(XmlModelRecovery.FEATURE, action, treatment).build(); }
}
