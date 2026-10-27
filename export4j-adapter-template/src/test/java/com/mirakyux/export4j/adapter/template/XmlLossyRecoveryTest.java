// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Note;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlLossyRecoveryTest {
    private static final byte[] STANDARD = "<p>PUBLIC</p>".getBytes(StandardCharsets.UTF_8);
    private static final ProcessingReport EMPTY = new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of());

    @Test void reimportsExactBytesOnceAndKeepsOnlyCurrentModelNotesAndLossHistory() throws IOException {
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var policy = policy(action, ProcessingPolicy.Treatment.DEGRADE);
            var calls = new AtomicInteger();
            var current = input().document();
            var notes = List.of(new Note("note", "current", "CURRENT-NOTE", Note.Kind.FOOTNOTE));
            var importLoss = issue("IMPORT", action);
            var assets = XmlTemplateBundle.builder("<document version='1'><p>PRIVATE-SOURCE</p></document>")
                    .font("Face.ttf", new byte[]{1, 2}).license("Face.txt", "Explicit notice").build();
            var saved = XmlModelRecovery.capture(lossy(action)).resources(assets).policy(policy)
                    .reimportOnLoss((bytes, shared) -> {
                        calls.incrementAndGet(); assertArrayEquals(STANDARD, bytes); assertSame(policy, shared);
                        bytes[0] = 0; // A trusted reader cannot mutate the captured standard file.
                        return new DocumentConversion.Input(current, notes, new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of(importLoss)));
                    }).prepare();
            assertEquals(1, calls.get()); assertArrayEquals(STANDARD, saved.documentBytes());
            assertEquals(3, saved.preview().losses().size());
            assertEquals(action == ProcessingPolicy.Action.WARN ? 3 : 0, saved.preview().warnings().size());
            assertEquals("CURRENT_FILE_REIMPORT", saved.preview().issues().get(1).effects().getFirst());
            assertEquals(importLoss, saved.preview().issues().get(2));
            var history = saved.preview().issues();
            for (int round = 0; round < 3; round++) {
                var opened = open(saved).prepare();
                assertEquals(current, opened.document()); assertEquals(notes, opened.notes());
                assertEquals(history, opened.report().issues());
                var resources = opened.resources().orElseThrow();
                assertFalse(resources.source().contains("PRIVATE")); assertTrue(resources.images().isEmpty());
                assertEquals(assets.licenses(), resources.licenses());
                assertArrayEquals(assets.fonts().get("Face.ttf"), resources.fonts().get("Face.ttf"));
                saved = XmlModelRecovery.capture(open(saved).conversion().prepare(XmlTemplateWriter.conversionTarget()))
                        .resources(resources).prepare();
                assertEquals(history, saved.preview().issues());
            }
        }
    }

    @Test void discardStillProducesPlainBytesAndNeverCallsEvenAConfiguredReader() throws IOException {
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var saved = XmlModelRecovery.capture(lossy(action)).policy(policy(action, ProcessingPolicy.Treatment.DISCARD))
                    .reimportOnLoss((bytes, shared) -> { throw new AssertionError("discard called reader"); }).prepare();
            assertTrue(saved.companionBytes().isEmpty()); assertArrayEquals(STANDARD, saved.documentBytes());
            assertArrayEquals(STANDARD, saved.embeddedBytes());
        }
    }

    @Test void errorAndUnsupportedPreservationFailBeforeReadingAndDegradeRequiresAReader() throws IOException {
        var converted = lossy(ProcessingPolicy.Action.WARN); var calls = new AtomicInteger();
        XmlModelRecovery.FallbackReader reader = (bytes, policy) -> { calls.incrementAndGet(); return input(); };
        assertThrows(ProcessingException.class, () -> XmlModelRecovery.capture(converted).reimportOnLoss(reader).prepare());
        assertThrows(ProcessingException.class, () -> XmlModelRecovery.capture(converted)
                .policy(policy(ProcessingPolicy.Action.ERROR, ProcessingPolicy.Treatment.DEGRADE)).reimportOnLoss(reader).prepare());
        assertThrows(ProcessingException.class, () -> XmlModelRecovery.capture(converted)
                .policy(policy(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.PRESERVE)).reimportOnLoss(reader).prepare());
        assertThrows(IllegalArgumentException.class, () -> XmlModelRecovery.capture(converted)
                .policy(policy(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE)).prepare());
        assertEquals(0, calls.get());
    }

    @Test void historicalLossDoesNotReimportACurrentLosslessTarget() throws IOException {
        var history = new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of(issue("EXPORT", ProcessingPolicy.Action.WARN)));
        var converted = DocumentConversion.from("HTML", ignored -> new DocumentConversion.Input(input().document(), List.of(), history))
                .prepare(XmlTemplateWriter.conversionTarget());
        var saved = XmlModelRecovery.capture(converted)
                .reimportOnLoss((bytes, policy) -> { throw new AssertionError("history caused reimport"); }).prepare();
        assertEquals(history.issues(), open(saved).prepare().report().issues());
    }

    @Test void readerFailuresAndInvalidResultsCannotPublishOrBeIgnored(@TempDir Path directory) throws IOException {
        var converted = lossy(ProcessingPolicy.Action.IGNORE); Path old = directory.resolve("old.html"); Files.writeString(old, "OLD");
        var failure = new IOException("reader failed");
        var request = XmlModelRecovery.capture(converted).policy(policy(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE))
                .reimportOnLoss((bytes, policy) -> { throw failure; });
        assertSame(failure, assertThrows(IOException.class, () -> request.prepare().writeEmbedded(old)));
        assertEquals("OLD", Files.readString(old));
        assertThrows(NullPointerException.class, () -> request.reimportOnLoss((bytes, policy) -> null).prepare());
        assertThrows(IllegalArgumentException.class, () -> request.reimportOnLoss((bytes, policy) -> new DocumentConversion.Input(
                input().document(), List.of(), new ProcessingReport(ProcessingReport.Phase.PREVIEW, List.of()))).prepare());
        assertThrows(NullPointerException.class, () -> request.reimportOnLoss(null));
    }

    @Test void reimportObeysAggregateIssueAndDocumentBudgets() throws IOException {
        var converted = lossy(ProcessingPolicy.Action.WARN); var called = new AtomicBoolean();
        var request = XmlModelRecovery.capture(converted).policy(policy(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE))
                .reimportOnLoss((bytes, policy) -> { called.set(true); return new DocumentConversion.Input(input().document(), List.of(),
                        new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of(issue("IMPORT", ProcessingPolicy.Action.WARN)))); });
        assertThrows(IOException.class, () -> request.limits(new XmlModelRecovery.Limits(1, 4096, 10, XmlTemplateBundle.Limits.DEFAULT)).prepare());
        assertFalse(called.get());
        assertThrows(IllegalArgumentException.class, () -> request.limits(new XmlModelRecovery.Limits(4096, 4096, 1, XmlTemplateBundle.Limits.DEFAULT)).prepare());
        assertFalse(called.get());
        assertThrows(IllegalArgumentException.class, () -> request.limits(new XmlModelRecovery.Limits(4096, 4096, 2, XmlTemplateBundle.Limits.DEFAULT)).prepare());
        assertTrue(called.get());
    }

    @Test void cancellationInTheReaderPreservesOldTargetAndRestoresScope(@TempDir Path directory) throws IOException {
        var cancelled = new AtomicBoolean(); var control = ExecutionOptions.builder().cancelled(cancelled::get).build();
        Path old = directory.resolve("old.html"); Files.writeString(old, "OLD");
        var request = XmlModelRecovery.capture(lossy(ProcessingPolicy.Action.IGNORE)).execution(control)
                .policy(policy(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE))
                .reimportOnLoss((bytes, policy) -> { cancelled.set(true); return input(); });
        assertThrows(ExecutionException.class, () -> request.prepare().writeEmbedded(old));
        assertEquals("OLD", Files.readString(old)); ExecutionContext.checkpoint();
    }

    @Test void unrepresentableReimportedModelStillCannotHideXmlSavingLoss() throws IOException {
        Block runtime = () -> "runtime";
        var request = XmlModelRecovery.capture(lossy(ProcessingPolicy.Action.WARN))
                .policy(ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
                        .feature(XmlModelRecovery.FEATURE, ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build())
                .reimportOnLoss((bytes, policy) -> new DocumentConversion.Input(document(runtime), List.of(), EMPTY));
        assertThrows(ProcessingException.class, request::prepare); // XML loss cannot use target reimport's DEGRADE rule.
    }

    private static DocumentConversion.Prepared lossy(ProcessingPolicy.Action action) throws IOException {
        return DocumentConversion.from(document(new Paragraph("private", "PRIVATE", ParagraphFormat.of(12))), List.of())
                .prepare(new DocumentConversion.Target("HTML", (source, notes, policy, output) -> {
                    output.write(STANDARD); return new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of(issue("EXPORT", action)));
                }));
    }
    private static DocumentConversion.Input input() { return new DocumentConversion.Input(document(Paragraph.mixed("current",
            List.of(new InlineText("PUBLIC", InlineStyle.of(12))), ParagraphFormat.of(12))), List.of(), EMPTY); }
    private static SectionedDocument document(Block block) { return new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("section", PageSettings.A4, List.of(block), 0))); }
    private static XmlModelRecovery.OpenRequest open(XmlModelRecovery.Saved saved) { return XmlModelRecovery.open(saved.format(), saved.documentBytes()).companion(saved.companionBytes().orElseThrow()); }
    private static ProcessingPolicy policy(ProcessingPolicy.Action action, ProcessingPolicy.Treatment treatment) { return ProcessingPolicy.builder().feature(XmlModelRecovery.FEATURE, action, treatment).build(); }
    private static ProcessingIssue issue(String stage, ProcessingPolicy.Action action) {
        return new ProcessingIssue(stage, "HTML", "MODEL", "STYLE", stage,
                new ProcessingIssue.Location("", "BODY", "", List.of(), -1, -1), "NOT_REPRESENTABLE", action,
                ProcessingPolicy.Treatment.DISCARD, List.of("STYLE_REMOVED"));
    }
}
