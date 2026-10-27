// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlModelRecoveryTest {
    private static final DocxAdapter DOCX = new DocxAdapter();
    private static final ProcessingReport EMPTY = new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of());

    @Test void threeRoundsRestoreExactModelResourcesAndIndependentSnapshots() throws IOException {
        var source = document("ORIGINAL😀\r\n${literal}");
        var resources = XmlTemplateBundle.builder("<document version='1'><p>UNUSED-OLD-SOURCE</p></document>")
                .font("Regular.ttf", new byte[]{1, 2, 3}).license("Regular.txt", "Notice\r\n").build();
        for (int round = 0; round < 3; round++) {
            var prepared = XmlModelRecovery.capture(DocumentConversion.from(source, List.of()).prepare(DOCX.conversionTarget()))
                    .resources(resources).prepare();
            var reopened = open(prepared).prepare();
            assertTrue(reopened.recovered()); assertEquals(source, reopened.document());
            assertTrue(reopened.report().issues().isEmpty());
            resources = reopened.resources().orElseThrow();
            assertArrayEquals(new byte[]{1, 2, 3}, resources.fonts().get("Regular.ttf"));
            assertEquals("Notice\r\n", resources.licenses().get("Regular.txt"));
            assertFalse(resources.source().contains("UNUSED-OLD-SOURCE"));
            byte[] copy = prepared.companionBytes().orElseThrow(); copy[0] = 0;
            assertEquals(source, open(prepared).prepare().document());
        }
    }

    @Test void editsCaptureCurrentCandidateAndCannotResurrectDeletedSource() throws IOException {
        var original = save(document("KEEP SECRET"));
        var edited = open(original).conversion().edit(editor -> editor.replaceText("SECRET", "REMOVED"))
                .prepare(DOCX.conversionTarget());
        var saved = XmlModelRecovery.capture(edited).prepare();
        var reopened = open(saved).prepare();
        assertEquals("KEEP REMOVED", text(reopened.document()));
        assertFalse(reopened.resources().orElseThrow().source().contains("SECRET"));
        assertThrows(ProcessingException.class, () -> XmlModelRecovery.open("DOCX", saved.documentBytes())
                .companion(original.companionBytes().orElseThrow()).prepare());
    }

    @Test void originalPackageNoOpKeepsBindingAndLocalEditInvalidatesIt() throws IOException {
        var saved = save(document("OLD KEEP"));
        var unchanged = new ByteArrayOutputStream(); DOCX.editPackage(saved.documentBytes()).prepare().to(unchanged);
        assertArrayEquals(saved.documentBytes(), unchanged.toByteArray());
        assertTrue(XmlModelRecovery.open("DOCX", unchanged.toByteArray()).companion(saved.companionBytes().orElseThrow()).prepare().recovered());
        var bytes = new ByteArrayOutputStream(); DOCX.editPackage(saved.documentBytes()).replaceText("OLD", "NEW").prepare().to(bytes);
        assertNotEquals(java.util.Arrays.toString(saved.documentBytes()), java.util.Arrays.toString(bytes.toByteArray()));
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var current = XmlModelRecovery.open("DOCX", bytes.toByteArray()).companion(saved.companionBytes().orElseThrow())
                    .policy(rule(action, ProcessingPolicy.Treatment.DEGRADE)).fallback(XmlModelRecoveryTest::docx).prepare();
            assertFalse(current.recovered()); assertTrue(current.resources().isEmpty()); assertEquals("NEW KEEP", text(current.document()));
            assertEquals("STALE_OR_CONFLICTING_COMPANION", current.report().issues().getFirst().reason());
            assertEquals(1, current.report().losses().size());
            assertEquals(action == ProcessingPolicy.Action.WARN ? 1 : 0, current.report().warnings().size());
        }
    }

    @Test void missingWrongFormatAndUnknownVersionUseOnlyExplicitCurrentFileFallback() throws IOException {
        var saved = save(document("VISIBLE"));
        assertThrows(ProcessingException.class, () -> XmlModelRecovery.open("DOCX", saved.documentBytes()).prepare());
        assertThrows(ProcessingException.class, () -> XmlModelRecovery.open("PDF", saved.documentBytes()).companion(saved.companionBytes().orElseThrow()).prepare());
        var entries = entries(saved); String manifest = new String(entries.get("recovery.xml"), StandardCharsets.UTF_8);
        entries.put("recovery.xml", manifest.replace("version=\"1\"", "version=\"2\"").getBytes(StandardCharsets.UTF_8));
        byte[] version = TemplateBundleIO.zip(entries, XmlTemplateBundle.Limits.DEFAULT);
        assertThrows(ProcessingException.class, () -> XmlModelRecovery.open("DOCX", saved.documentBytes()).companion(version).prepare());
        var opened = XmlModelRecovery.open("DOCX", saved.documentBytes()).companion(version)
                .policy(rule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)).fallback(XmlModelRecoveryTest::docx).prepare();
        assertFalse(opened.recovered()); assertEquals("UNSUPPORTED_RECOVERY_VERSION", opened.report().issues().getFirst().reason());
        assertEquals("VISIBLE", text(opened.document()));
        assertThrows(IllegalArgumentException.class, () -> XmlModelRecovery.open("DOCX", saved.documentBytes())
                .policy(rule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE)).prepare());
        assertThrows(ProcessingException.class, () -> XmlModelRecovery.open("DOCX", saved.documentBytes())
                .policy(rule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.PRESERVE)).fallback(XmlModelRecoveryTest::docx).prepare());
    }

    @Test void corruptedSourcesResourcesAndManifestCannotBeIgnoredOrReachFallback() throws IOException {
        var saved = save(document("VISIBLE")); var called = new AtomicBoolean();
        for (byte[] bad : List.of(
                altered(saved, "template.xml", value -> value.replace("VISIBLE", "HIDDEN")),
                altered(saved, "recovery.xml", value -> value.replace("sha256=", "unknown=")),
                altered(saved, "recovery.xml", value -> "<!DOCTYPE recovery [<!ENTITY x SYSTEM 'file:///not-a-resource'>]>" + value.substring(value.indexOf("<recovery"))),
                new byte[]{1, 2, 3})) {
            assertThrows(IOException.class, () -> XmlModelRecovery.open("DOCX", saved.documentBytes()).companion(bad)
                    .policy(rule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE))
                    .fallback((current, policy) -> { called.set(true); return docx(current, policy); }).prepare());
        }
        assertFalse(called.get());
    }

    @Test void lossyExportNeverHidesTheRicherModelAndDoesNotOverwriteAnOldCompanion(@TempDir Path directory) throws IOException {
        var loss = issue("EXPORT", "SOURCE_CONTENT_REMOVED", ProcessingPolicy.Action.WARN);
        var target = new DocumentConversion.Target("HTML", (document, notes, policy, output) -> {
            output.write("<p>PUBLIC</p>".getBytes(StandardCharsets.UTF_8)); return new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of(loss));
        });
        var converted = DocumentConversion.from(document("PRIVATE"), List.of()).prepare(target);
        assertThrows(ProcessingException.class, () -> XmlModelRecovery.capture(converted).prepare());
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var saved = XmlModelRecovery.capture(converted).policy(rule(action, ProcessingPolicy.Treatment.DISCARD)).prepare();
            assertTrue(saved.companionBytes().isEmpty()); assertEquals("<p>PUBLIC</p>", new String(saved.documentBytes(), StandardCharsets.UTF_8));
            assertEquals(2, saved.preview().losses().size());
            Path old = directory.resolve("old.zip"); Files.writeString(old, "old");
            assertThrows(IllegalStateException.class, () -> saved.writeCompanion(old)); assertEquals("old", Files.readString(old));
        }
    }

    @Test void inheritedExportLossIsHistoryAndDoesNotMisclassifyCurrentLosslessEncoding() throws IOException {
        var history = new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of(issue("EXPORT", "PRIOR_LOSS", ProcessingPolicy.Action.IGNORE)));
        var prepared = DocumentConversion.from("IMPORTED", ignored -> new DocumentConversion.Input(document("CURRENT"), List.of(), history))
                .prepare(DOCX.conversionTarget());
        assertTrue(prepared.exportReport().issues().isEmpty()); assertEquals(history.issues(), prepared.preview().issues());
        var saved = XmlModelRecovery.capture(prepared).prepare();
        for (int round = 0; round < 3; round++) {
            var current = open(saved).prepare(); assertEquals(history.issues(), current.report().issues());
            var next = open(saved).conversion().prepare(DOCX.conversionTarget()); saved = XmlModelRecovery.capture(next).prepare();
            assertEquals(history.issues(), saved.preview().issues());
        }
    }

    @Test void manifestRoundTripsCompleteLocationsActionsAndEffects() throws IOException {
        var at = new ProcessingIssue.Location("s", "BODY", "p", List.of(new com.mirakyux.export4j.model.CellLocation("t", 2, 1, -1)), 1, 4);
        var issue = new ProcessingIssue("IMPORT", "HTML", "MODEL", "STYLE", "IMPORT", at, "escaped <&\"\r\n😀", ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DEGRADE, List.of("ONE", "TWO"));
        var manifest = new RecoveryManifest(1, "DOCX", 42, "a".repeat(64), "b".repeat(64), List.of(issue));
        assertEquals(manifest, RecoveryManifest.read(manifest.encode(4096), 4096, 1));
        assertThrows(IOException.class, () -> RecoveryManifest.read(manifest.encode(4096), 8, 1));
    }

    @Test void budgetsCancellationAndBorrowedStreamsAreEnforced(@TempDir Path directory) throws IOException {
        var saved = save(document("VISIBLE")); var small = new XmlModelRecovery.Limits(1, 1024, 1, XmlTemplateBundle.Limits.DEFAULT);
        assertThrows(IOException.class, () -> open(saved).limits(small).prepare());
        assertThrows(IOException.class, () -> XmlModelRecovery.capture(DocumentConversion.from(document("VISIBLE"), List.of()).prepare(DOCX.conversionTarget())).limits(small).prepare());
        var cancelled = ExecutionOptions.builder().cancelled(() -> true).build();
        assertThrows(ExecutionException.class, () -> open(saved).execution(cancelled).prepare());
        var closed = new AtomicBoolean(); var flushed = new AtomicBoolean();
        var output = new ByteArrayOutputStream() {
            @Override public void close() { closed.set(true); }
            @Override public void flush() { flushed.set(true); }
        };
        assertEquals(saved.preview().issues(), saved.writeDocument(output).issues()); saved.writeCompanion(output);
        assertFalse(closed.get()); assertFalse(flushed.get());
        Path old = directory.resolve("old.docx"); Files.writeString(old, "old");
        var conversion = DocumentConversion.from(document("VISIBLE"), List.of()).prepare(DOCX.conversionTarget());
        var cancelLater = new AtomicBoolean();
        var control = ExecutionOptions.builder().cancelled(cancelLater::get).build();
        var prepared = XmlModelRecovery.capture(conversion).execution(control).prepare(); cancelLater.set(true);
        assertThrows(ExecutionException.class, () -> prepared.writeDocument(old)); assertEquals("old", Files.readString(old));
        assertThrows(IOException.class, () -> saved.writeDocument(new OutputStream() { @Override public void write(int value) throws IOException { throw new IOException("failure"); } }));
    }

    @Test void fontLicenseCarryoverWorksThroughTheSharedConversionTarget() throws IOException {
        var source = XmlTemplateWriter.from(document("OLD")).font("Face.ttf", new byte[]{4, 5}).license("Face.txt", "License").prepare().bundle();
        var bytes = new ByteArrayOutputStream();
        source.conversion().edit(editor -> editor.replaceText("OLD", "NEW")).prepare(XmlTemplateWriter.conversionTarget(source)).to(bytes);
        var reopened = XmlTemplateBundle.read(bytes.toByteArray());
        assertArrayEquals(new byte[]{4, 5}, reopened.fonts().get("Face.ttf")); assertEquals(source.licenses(), reopened.licenses());
        assertEquals("NEW", text(reopened.compile().renderComplete(Map.of()).document()));
    }

    @Test void removingAPictureDoesNotCopyOldImagesFragmentsOrSourceIntoTheNewCompanion() throws IOException {
        var pixels = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var encoded = new ByteArrayOutputStream();
        try { assertTrue(javax.imageio.ImageIO.write(pixels, "png", encoded)); } finally { pixels.flush(); }
        var image = com.mirakyux.export4j.model.ImageResource.load(encoded.toByteArray());
        var picture = Paragraph.mixed("picture", List.of(new com.mirakyux.export4j.model.InlineImage(image, 10, 10, 0, "OLD-PICTURE")), ParagraphFormat.of(12));
        var source = document("KEEP"); var section = source.sections().getFirst();
        var withPicture = new SectionedDocument(source.metadata(), List.of(new Section(section.id(), section.pageSettings(),
                List.of(section.blocks().getFirst(), picture), 0)));
        var original = XmlTemplateWriter.from(withPicture).font("Face.ttf", new byte[]{1}).prepare().bundle();
        var prepared = original.conversion().edit(editor -> editor.remove("picture")).prepare(DOCX.conversionTarget());
        var current = XmlModelRecovery.capture(prepared).resources(original).prepare();
        var recovered = open(current).prepare().resources().orElseThrow();
        assertTrue(recovered.images().isEmpty()); assertTrue(recovered.fragments().isEmpty());
        assertFalse(recovered.source().contains("OLD-PICTURE")); assertEquals(1, recovered.fonts().size());
    }

    private static XmlModelRecovery.Saved save(SectionedDocument document) throws IOException {
        return XmlModelRecovery.capture(DocumentConversion.from(document, List.of()).prepare(DOCX.conversionTarget())).prepare();
    }
    private static XmlModelRecovery.OpenRequest open(XmlModelRecovery.Saved saved) {
        return XmlModelRecovery.open(saved.format(), saved.documentBytes()).companion(saved.companionBytes().orElseThrow());
    }
    private static Map<String, byte[]> entries(XmlModelRecovery.Saved saved) throws IOException {
        return TemplateBundleIO.unzip(saved.companionBytes().orElseThrow(), XmlTemplateBundle.Limits.DEFAULT, true);
    }
    private static byte[] altered(XmlModelRecovery.Saved saved, String name, java.util.function.UnaryOperator<String> change) throws IOException {
        var entries = entries(saved); entries.put(name, change.apply(new String(entries.get(name), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        return TemplateBundleIO.zip(entries, XmlTemplateBundle.Limits.DEFAULT);
    }
    private static SectionedDocument document(String text) {
        return new SectionedDocument(new DocumentMetadata("Recovery", "Export4j"), List.of(new Section("section", PageSettings.A4,
                List.of(Paragraph.mixed("paragraph", List.of(new com.mirakyux.export4j.model.InlineText(text,
                        com.mirakyux.export4j.model.InlineStyle.of(12))), ParagraphFormat.of(12))), 0)));
    }
    private static String text(SectionedDocument document) { return ((Paragraph) document.sections().getFirst().blocks().getFirst()).text(); }
    private static DocumentConversion.Input docx(byte[] current, ProcessingPolicy policy) throws IOException {
        var result = DOCX.read(new ByteArrayInputStream(current), DocxAdapter.Policy.STRICT);
        assertTrue(result.diagnostics().isEmpty()); return new DocumentConversion.Input(result.document(), List.of(), EMPTY);
    }
    private static ProcessingIssue issue(String stage, String effect, ProcessingPolicy.Action action) {
        return new ProcessingIssue(stage, "HTML", "MODEL", "CSS", stage, new ProcessingIssue.Location("", "BODY", "", List.of(), -1, -1),
                "unsupported style", action, ProcessingPolicy.Treatment.DISCARD, List.of(effect));
    }
    private static ProcessingPolicy rule(ProcessingPolicy.Action action, ProcessingPolicy.Treatment treatment) {
        return ProcessingPolicy.builder().defaultRule(action, treatment).build();
    }
}
