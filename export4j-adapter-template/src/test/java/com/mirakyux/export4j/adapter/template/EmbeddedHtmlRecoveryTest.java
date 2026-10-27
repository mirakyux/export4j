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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedHtmlRecoveryTest {
    private static final ProcessingReport EMPTY = new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of());
    private static final DocumentConversion.Target HTML = new DocumentConversion.Target("HTML", (document, notes, policy, output) -> {
        output.write(utf8("<!doctype html><html><body><p>" + text(document) + "</p></body></html>")); return EMPTY;
    });

    @Test void threeSingleFileRoundsRetainExactModelAndExplicitResourcesThenSaveOnlyEditedContent() throws IOException {
        var source = document("ORIGINAL😀");
        var resources = XmlTemplateBundle.builder("<document version='1'><p>UNUSED-SOURCE</p></document>")
                .font("Face.ttf", new byte[]{1, 2, 3}).license("Face.txt", "Notice\r\n").build();
        var saved = XmlModelRecovery.capture(DocumentConversion.from(source, List.of()).prepare(HTML)).resources(resources).prepare();
        for (int round = 0; round < 3; round++) {
            byte[] file = saved.embeddedHtmlBytes(); var request = XmlModelRecovery.openEmbeddedHtml(file); Arrays.fill(file, (byte) 0);
            var opened = request.prepare(); assertTrue(opened.recovered()); assertEquals(source, opened.document());
            assertArrayEquals(new byte[]{1, 2, 3}, opened.resources().orElseThrow().fonts().get("Face.ttf"));
            assertEquals(resources.licenses(), opened.resources().orElseThrow().licenses());
            assertFalse(opened.resources().orElseThrow().source().contains("UNUSED-SOURCE"));
            saved = XmlModelRecovery.capture(request.conversion().prepare(HTML)).resources(opened.resources().orElseThrow()).prepare();
        }
        var opened = XmlModelRecovery.openEmbeddedHtml(saved.embeddedHtmlBytes());
        var edited = XmlModelRecovery.capture(opened.conversion().edit(editor -> editor.replaceText("ORIGINAL", "EDITED")).prepare(HTML))
                .resources(resources).prepare();
        assertEquals("EDITED😀", text(XmlModelRecovery.openEmbeddedHtml(edited.embeddedHtmlBytes()).prepare().document()));
        assertFalse(XmlModelRecovery.openEmbeddedHtml(edited.embeddedHtmlBytes()).prepare().resources().orElseThrow().source().contains("ORIGINAL"));
    }

    @Test void externalEditsUseOnlyCurrentHtmlAndNeverTheStaleEmbeddedModel() throws IOException {
        var saved = save("ORIGINAL"); byte[] current = utf8(new String(saved.embeddedHtmlBytes(), StandardCharsets.UTF_8).replace("<p>ORIGINAL</p>", "<p>CURRENT</p>"));
        var failure = assertThrows(ProcessingException.class, () -> XmlModelRecovery.openEmbeddedHtml(current).prepare());
        assertEquals("STALE_OR_CONFLICTING_COMPANION", failure.issue().reason());
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var called = new AtomicBoolean();
            var opened = XmlModelRecovery.openEmbeddedHtml(current).policy(rule(action, ProcessingPolicy.Treatment.DEGRADE))
                    .fallback((bytes, policy) -> {
                        called.set(true); assertEquals("<!doctype html><html><body><p>CURRENT</p></body></html>", new String(bytes, StandardCharsets.UTF_8));
                        return new DocumentConversion.Input(document("CURRENT"), List.of(), EMPTY);
                    }).prepare();
            assertTrue(called.get()); assertFalse(opened.recovered()); assertTrue(opened.resources().isEmpty());
            assertEquals("CURRENT", text(opened.document())); assertEquals(1, opened.report().losses().size());
            assertEquals(action == ProcessingPolicy.Action.WARN ? 1 : 0, opened.report().warnings().size());
        }
        assertThrows(IllegalArgumentException.class, () -> XmlModelRecovery.openEmbeddedHtml(current)
                .policy(rule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE)).prepare());
    }

    @Test void strippedAndUnknownVersionRecoveryNeedExplicitFallbackAndCannotMixSources() throws IOException {
        var saved = save("CURRENT");
        assertThrows(ProcessingException.class, () -> XmlModelRecovery.openEmbeddedHtml(saved.documentBytes()).prepare());
        var plain = XmlModelRecovery.openEmbeddedHtml(saved.documentBytes()).policy(rule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE))
                .fallback((bytes, policy) -> new DocumentConversion.Input(document("CURRENT"), List.of(), EMPTY)).prepare();
        assertFalse(plain.recovered()); assertEquals("MISSING_COMPANION", plain.report().issues().getFirst().reason());
        var entries = TemplateBundleIO.unzip(saved.companionBytes().orElseThrow(), XmlTemplateBundle.Limits.DEFAULT, true);
        entries.put("recovery.xml", utf8(new String(entries.get("recovery.xml"), StandardCharsets.UTF_8).replace("version=\"1\"", "version=\"2\"")));
        byte[] unknown = CommentRecoveryEnvelope.encode(saved.documentBytes(), TemplateBundleIO.zip(entries, XmlTemplateBundle.Limits.DEFAULT), 1_000_000);
        var failure = assertThrows(ProcessingException.class, () -> XmlModelRecovery.openEmbeddedHtml(unknown).prepare());
        assertEquals("UNSUPPORTED_RECOVERY_VERSION", failure.issue().reason());
        assertThrows(IllegalStateException.class, () -> XmlModelRecovery.openEmbeddedHtml(unknown).companion(saved.companionBytes().orElseThrow()));
    }

    @Test void malformedDuplicateAndCorruptEnvelopesNeverReachFallback() throws IOException {
        var saved = save("CURRENT"); String file = new String(saved.embeddedHtmlBytes(), StandardCharsets.UTF_8);
        int marker = file.indexOf("\n<!--export4j-model-recovery\n");
        String comment = file.substring(marker);
        var called = new AtomicBoolean();
        for (byte[] bad : List.of(utf8(file + comment), utf8(file.substring(0, file.length() - 1)),
                utf8(file.replace("recovery\n", "recovery\n!")), utf8(file.substring(0, marker) + "\n<!--export4j-model-recovery\nAAAA\n-->\n"),
                utf8(comment + file.substring(0, marker)))) {
            assertThrows(IOException.class, () -> XmlModelRecovery.openEmbeddedHtml(bad).policy(rule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE))
                    .fallback((bytes, policy) -> { called.set(true); return new DocumentConversion.Input(document("BAD"), List.of(), EMPTY); }).prepare());
        }
        assertFalse(called.get());
        assertThrows(IOException.class, () -> CommentRecoveryEnvelope.encode(saved.embeddedHtmlBytes(), saved.companionBytes().orElseThrow(), 1_000_000));
        var entries = TemplateBundleIO.unzip(saved.companionBytes().orElseThrow(), XmlTemplateBundle.Limits.DEFAULT, true);
        entries.put("template.xml", utf8(new String(entries.get("template.xml"), StandardCharsets.UTF_8).replace("CURRENT", "HIDDEN")));
        byte[] corrupt = CommentRecoveryEnvelope.encode(saved.documentBytes(), TemplateBundleIO.zip(entries, XmlTemplateBundle.Limits.DEFAULT), 1_000_000);
        assertThrows(IOException.class, () -> XmlModelRecovery.openEmbeddedHtml(corrupt).policy(rule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE))
                .fallback((bytes, policy) -> { called.set(true); return new DocumentConversion.Input(document("BAD"), List.of(), EMPTY); }).prepare());
        assertFalse(called.get());
    }

    @Test void lossyHtmlHasNoEmbeddedSourceForEitherNonBlockingAction() throws IOException {
        var issue = new ProcessingIssue("EXPORT", "MODEL", "HTML", "PRIVATE_DATA", "EXPORT", new ProcessingIssue.Location("", "DOCUMENT", "", List.of(), -1, -1),
                "CONTENT_REMOVED", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD, List.of("REMOVED"));
        var target = new DocumentConversion.Target("HTML", (document, notes, policy, output) -> { output.write(utf8("<p>PUBLIC</p>")); return new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of(issue)); });
        var conversion = DocumentConversion.from(document("PRIVATE"), List.of()).prepare(target);
        assertThrows(ProcessingException.class, () -> XmlModelRecovery.capture(conversion).prepare());
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var saved = XmlModelRecovery.capture(conversion).policy(rule(action, ProcessingPolicy.Treatment.DISCARD)).prepare();
            assertArrayEquals(utf8("<p>PUBLIC</p>"), saved.embeddedHtmlBytes());
            assertTrue(saved.companionBytes().isEmpty()); assertEquals(2, saved.preview().losses().size());
        }
    }

    @Test void totalAndDecodedBudgetsCancellationAndAtomicOutputAreEnforced(@TempDir Path directory) throws IOException {
        var saved = save("CURRENT"); byte[] file = saved.embeddedHtmlBytes();
        var small = new XmlModelRecovery.Limits(file.length - 1, 1024 * 1024, 100, XmlTemplateBundle.Limits.DEFAULT);
        assertThrows(IOException.class, () -> XmlModelRecovery.openEmbeddedHtml(file).limits(small).prepare());
        var bundle = XmlTemplateBundle.Limits.DEFAULT;
        var tinyZip = new XmlTemplateBundle.Limits(1, bundle.maxExpandedBytes(), bundle.maxEntries(), bundle.maxSourceUtf16(), bundle.maxImagePixels());
        assertThrows(IOException.class, () -> XmlModelRecovery.openEmbeddedHtml(file).limits(new XmlModelRecovery.Limits(file.length, 1024 * 1024, 100, tinyZip)).prepare());
        var bounded = XmlModelRecovery.capture(DocumentConversion.from(document("CURRENT"), List.of()).prepare(HTML)).limits(small).prepare();
        Path old = directory.resolve("old.html"); Files.writeString(old, "OLD");
        assertThrows(IOException.class, () -> bounded.writeEmbeddedHtml(old)); assertEquals("OLD", Files.readString(old));
        var stop = new AtomicBoolean(); var execution = ExecutionOptions.builder().cancelled(stop::get).build();
        var cancelled = XmlModelRecovery.capture(DocumentConversion.from(document("CURRENT"), List.of()).prepare(HTML)).execution(execution).prepare();
        stop.set(true); assertThrows(ExecutionException.class, () -> cancelled.writeEmbeddedHtml(old)); assertEquals("OLD", Files.readString(old));
        assertThrows(ExecutionException.class, () -> XmlModelRecovery.openEmbeddedHtml(file).execution(execution).prepare());
        var closed = new AtomicBoolean(); var flushed = new AtomicBoolean();
        var output = new ByteArrayOutputStream() {
            @Override public void close() { closed.set(true); }
            @Override public void flush() { flushed.set(true); }
        };
        assertEquals(saved.preview().issues(), saved.writeEmbeddedHtml(output).issues()); assertArrayEquals(file, output.toByteArray());
        assertFalse(closed.get()); assertFalse(flushed.get());
        assertThrows(IOException.class, () -> saved.writeEmbeddedHtml(new OutputStream() { @Override public void write(int value) throws IOException { throw new IOException("failure"); } }));
        saved.writeEmbeddedHtml(old); assertArrayEquals(file, Files.readAllBytes(old));
        try (var files = Files.list(directory)) { assertEquals(List.of(old), files.toList()); }
    }

    private static XmlModelRecovery.Saved save(String value) throws IOException { return XmlModelRecovery.capture(DocumentConversion.from(document(value), List.of()).prepare(HTML)).prepare(); }
    private static SectionedDocument document(String value) {
        return new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", PageSettings.A4,
                List.of(Paragraph.mixed("p", List.of(new InlineText(value, InlineStyle.of(12))), ParagraphFormat.of(12))), 0)));
    }
    private static String text(SectionedDocument document) { return ((Paragraph) document.sections().getFirst().blocks().getFirst()).text(); }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static ProcessingPolicy rule(ProcessingPolicy.Action action, ProcessingPolicy.Treatment treatment) { return ProcessingPolicy.builder().feature(XmlModelRecovery.FEATURE, action, treatment).build(); }
}
