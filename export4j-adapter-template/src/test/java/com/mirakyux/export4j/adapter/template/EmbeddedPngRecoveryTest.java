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
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedPngRecoveryTest {
    private static final ProcessingReport EMPTY = new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of());
    private static final DocumentConversion.Target PNG = new DocumentConversion.Target("PNG", (document, notes, policy, output) -> {
        output.write(image(text(document).contains("EDITED") ? 0x00ff00 : 0x0000ff)); return EMPTY;
    });

    @Test void threeRoundsRetainExactPngModelAndResourcesAndSaveOnlyCurrentEdits() throws IOException {
        var source = document("ORIGINAL😀");
        var resources = XmlTemplateBundle.builder("<document version='1'><p>UNUSED-SOURCE</p></document>").font("Face.ttf", new byte[]{1, 2, 3}).license("Face.txt", "Notice\r\n").build();
        var saved = XmlModelRecovery.capture(DocumentConversion.from(source, List.of()).prepare(PNG)).resources(resources).prepare();
        byte[] first = saved.embeddedBytes();
        for (int round = 0; round < 3; round++) {
            byte[] file = saved.embeddedBytes(); var request = XmlModelRecovery.openEmbedded("PNG", file); Arrays.fill(file, (byte) 0);
            var opened = request.prepare(); assertTrue(opened.recovered()); assertEquals(source, opened.document());
            assertArrayEquals(new byte[]{1, 2, 3}, opened.resources().orElseThrow().fonts().get("Face.ttf")); assertEquals(resources.licenses(), opened.resources().orElseThrow().licenses());
            saved = XmlModelRecovery.capture(request.conversion().prepare(PNG)).resources(opened.resources().orElseThrow()).prepare();
        }
        assertArrayEquals(first, saved.embeddedBytes());
        var edited = XmlModelRecovery.capture(XmlModelRecovery.openEmbedded("PNG", first).conversion().edit(editor -> editor.replaceText("ORIGINAL", "EDITED")).prepare(PNG)).resources(resources).prepare();
        var opened = XmlModelRecovery.openEmbedded("PNG", edited.embeddedBytes()).prepare();
        assertEquals("EDITED😀", text(opened.document())); assertFalse(opened.resources().orElseThrow().source().contains("ORIGINAL"));
        assertFalse(opened.resources().orElseThrow().source().contains("UNUSED-SOURCE"));
        assertFalse(Arrays.equals(saved.documentBytes(), edited.documentBytes()));
    }

    @Test void externalPixelsNeedAnExplicitCurrentFileReaderForNonblockingActions() throws IOException {
        var saved = save(); byte[] current = image(0xff0000);
        byte[] external = attach(current, saved.companionBytes().orElseThrow());
        var failure = assertThrows(ProcessingException.class, () -> XmlModelRecovery.openEmbedded("PNG", external).prepare());
        assertEquals("STALE_OR_CONFLICTING_COMPANION", failure.issue().reason());
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var policy = rule(action, ProcessingPolicy.Treatment.DEGRADE);
            assertThrows(IllegalArgumentException.class, () -> XmlModelRecovery.openEmbedded("PNG", external).policy(policy).prepare());
            var opened = XmlModelRecovery.openEmbedded("PNG", external).policy(policy).fallback((bytes, shared) -> {
                assertArrayEquals(current, bytes); return new DocumentConversion.Input(document("CURRENT"), List.of(), EMPTY);
            }).prepare();
            assertFalse(opened.recovered()); assertTrue(opened.resources().isEmpty()); assertEquals("CURRENT", text(opened.document()));
            assertEquals(1, opened.report().losses().size()); assertEquals(action == ProcessingPolicy.Action.WARN ? 1 : 0, opened.report().warnings().size());
        }
        assertThrows(ProcessingException.class, () -> XmlModelRecovery.openEmbedded("PNG", current).prepare());
        assertThrows(IllegalStateException.class, () -> XmlModelRecovery.openEmbedded("PNG", external).companion(saved.companionBytes().orElseThrow()));
        assertThrows(IOException.class, () -> XmlModelRecovery.openEmbedded("PNG_ZIP", external).prepare());
    }

    @Test void malformedCrcLengthOrderDuplicateAndCorruptZipNeverReachFallback() throws IOException {
        var saved = save(); byte[] good = saved.embeddedBytes(), plain = saved.documentBytes();
        byte[] badCrc = good.clone(); badCrc[badCrc.length - 1] ^= 1;
        byte[] badLength = good.clone(); ByteBuffer.wrap(badLength).putInt(8, Integer.MAX_VALUE);
        byte[] duplicate = attach(good, saved.companionBytes().orElseThrow());
        byte[] wrongOrder = join(Arrays.copyOf(plain, 8), chunk("exJM", saved.companionBytes().orElseThrow()), Arrays.copyOfRange(plain, 8, plain.length));
        var entries = TemplateBundleIO.unzip(saved.companionBytes().orElseThrow(), XmlTemplateBundle.Limits.DEFAULT, true);
        entries.put("template.xml", utf8("<document version='1'><p>HIDDEN</p></document>"));
        byte[] corrupt = attach(plain, TemplateBundleIO.zip(entries, XmlTemplateBundle.Limits.DEFAULT));
        var called = new AtomicBoolean();
        for (byte[] bytes : List.of(badCrc, badLength, duplicate, wrongOrder, corrupt, join(good, new byte[]{0}), Arrays.copyOf(good, good.length - 12), attach(plain, new byte[]{1, 2}))) {
            assertThrows(IOException.class, () -> XmlModelRecovery.openEmbedded("PNG", bytes).policy(rule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE))
                    .fallback((current, policy) -> { called.set(true); return new DocumentConversion.Input(document("BAD"), List.of(), EMPTY); }).prepare());
        }
        assertFalse(called.get());
    }

    @Test void unknownVersionAndFormatMismatchAreExplicitAndAnimationIsRejected() throws IOException {
        var saved = save(); var entries = TemplateBundleIO.unzip(saved.companionBytes().orElseThrow(), XmlTemplateBundle.Limits.DEFAULT, true);
        entries.put("recovery.xml", utf8(new String(entries.get("recovery.xml"), StandardCharsets.UTF_8).replace("version=\"1\"", "version=\"2\"")));
        var version = assertThrows(ProcessingException.class, () -> XmlModelRecovery.openEmbedded("PNG", attach(saved.documentBytes(), TemplateBundleIO.zip(entries, XmlTemplateBundle.Limits.DEFAULT))).prepare());
        assertEquals("UNSUPPORTED_RECOVERY_VERSION", version.issue().reason());
        entries.put("recovery.xml", utf8(new String(entries.get("recovery.xml"), StandardCharsets.UTF_8).replace("version=\"2\"", "version=\"1\"").replace("format=\"PNG\"", "format=\"SVG\"")));
        var mismatch = assertThrows(ProcessingException.class, () -> XmlModelRecovery.openEmbedded("PNG", attach(saved.documentBytes(), TemplateBundleIO.zip(entries, XmlTemplateBundle.Limits.DEFAULT))).prepare());
        assertEquals("FORMAT_MISMATCH", mismatch.issue().reason());
        byte[] plain = saved.documentBytes();
        assertThrows(IOException.class, () -> XmlModelRecovery.openEmbedded("PNG", join(Arrays.copyOf(plain, 33), chunk("acTL", new byte[8]), Arrays.copyOfRange(plain, 33, plain.length))).prepare());
    }

    @Test void lossyCaptureNeverStoresRicherSource() throws IOException {
        var issue = new ProcessingIssue("EXPORT", "MODEL", "PNG", "CONTENT", "EXPORT", new ProcessingIssue.Location("", "DOCUMENT", "", List.of(), -1, -1), "REMOVED", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD, List.of("REMOVED"));
        byte[] plain = image(0xffffff);
        var target = new DocumentConversion.Target("PNG", (document, notes, policy, output) -> { output.write(plain); return new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of(issue)); });
        var conversion = DocumentConversion.from(document("PRIVATE"), List.of()).prepare(target);
        assertThrows(ProcessingException.class, () -> XmlModelRecovery.capture(conversion).prepare());
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var saved = XmlModelRecovery.capture(conversion).policy(rule(action, ProcessingPolicy.Treatment.DISCARD)).prepare();
            assertArrayEquals(plain, saved.embeddedBytes()); assertTrue(saved.companionBytes().isEmpty());
        }
    }

    @Test void encodedPixelChunkAndZipBudgetsAreEnforcedBeforeFallbackOrPublication(@TempDir Path directory) throws IOException {
        var saved = save(); byte[] file = saved.embeddedBytes(); var bundle = XmlTemplateBundle.Limits.DEFAULT;
        var small = new XmlModelRecovery.Limits(file.length - 1, 1024 * 1024, 100, bundle);
        assertThrows(IOException.class, () -> XmlModelRecovery.openEmbedded("PNG", file).limits(small).prepare());
        for (var limited : List.of(new XmlTemplateBundle.Limits(1, bundle.maxExpandedBytes(), bundle.maxEntries(), bundle.maxSourceUtf16(), bundle.maxImagePixels()),
                new XmlTemplateBundle.Limits(bundle.maxEncodedBytes(), bundle.maxExpandedBytes(), 1, bundle.maxSourceUtf16(), bundle.maxImagePixels()),
                new XmlTemplateBundle.Limits(bundle.maxEncodedBytes(), bundle.maxExpandedBytes(), bundle.maxEntries(), bundle.maxSourceUtf16(), 1))) {
            assertThrows(IOException.class, () -> XmlModelRecovery.openEmbedded("PNG", file).limits(new XmlModelRecovery.Limits(file.length, 1024 * 1024, 100, limited)).prepare());
        }
        Path old = directory.resolve("old.png"); Files.writeString(old, "OLD");
        var threeChunks = new XmlTemplateBundle.Limits(bundle.maxEncodedBytes(), bundle.maxExpandedBytes(), 3, bundle.maxSourceUtf16(), bundle.maxImagePixels());
        var chunkBounded = XmlModelRecovery.capture(DocumentConversion.from(document("ORIGINAL"), List.of()).prepare(PNG))
                .limits(new XmlModelRecovery.Limits(file.length * 2, 1024 * 1024, 100, threeChunks)).prepare();
        assertThrows(IOException.class, () -> chunkBounded.writeEmbedded(old)); assertEquals("OLD", Files.readString(old));
        var bounded = XmlModelRecovery.capture(DocumentConversion.from(document("ORIGINAL"), List.of()).prepare(PNG)).limits(small).prepare();
        assertThrows(IOException.class, () -> bounded.writeEmbedded(old)); assertEquals("OLD", Files.readString(old));
    }

    @Test void cancellationAndBorrowedStreamsPreserveOldTargets(@TempDir Path directory) throws IOException {
        var stop = new AtomicBoolean(); var execution = ExecutionOptions.builder().cancelled(stop::get).build();
        var saved = XmlModelRecovery.capture(DocumentConversion.from(document("ORIGINAL"), List.of()).prepare(PNG)).execution(execution).prepare();
        var touched = new AtomicBoolean(); var output = new ByteArrayOutputStream() {
            @Override public void close() { touched.set(true); }
            @Override public void flush() { touched.set(true); }
        };
        assertEquals(saved.preview().issues(), saved.writeEmbedded(output).issues()); assertFalse(touched.get());
        byte[] file = saved.embeddedBytes(); assertArrayEquals(file, output.toByteArray());
        Path old = directory.resolve("old.png"); Files.writeString(old, "OLD"); stop.set(true);
        assertThrows(ExecutionException.class, () -> saved.writeEmbedded(old)); assertEquals("OLD", Files.readString(old));
        assertThrows(ExecutionException.class, () -> XmlModelRecovery.openEmbedded("PNG", file).execution(execution).prepare());
        stop.set(false); saved.writeEmbedded(old); assertArrayEquals(file, Files.readAllBytes(old));
        try (var files = Files.list(directory)) { assertEquals(List.of(old), files.toList()); }
    }

    private static XmlModelRecovery.Saved save() throws IOException { return XmlModelRecovery.capture(DocumentConversion.from(document("ORIGINAL"), List.of()).prepare(PNG)).prepare(); }
    private static byte[] image(int color) throws IOException {
        var image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB); image.setRGB(0, 0, color); image.setRGB(1, 0, 0xffffff);
        var bytes = new ByteArrayOutputStream(); try { ImageIO.write(image, "png", bytes); } finally { image.flush(); } return bytes.toByteArray();
    }
    private static byte[] attach(byte[] png, byte[] zip) throws IOException { return join(Arrays.copyOf(png, png.length - 12), chunk("exJM", zip), Arrays.copyOfRange(png, png.length - 12, png.length)); }
    private static byte[] chunk(String type, byte[] value) throws IOException {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII); var crc = new CRC32(); crc.update(name); crc.update(value);
        return join(ByteBuffer.allocate(4).putInt(value.length).array(), name, value, ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
    private static byte[] join(byte[]... pieces) throws IOException { var bytes = new ByteArrayOutputStream(); for (var piece : pieces) { bytes.write(piece); } return bytes.toByteArray(); }
    private static SectionedDocument document(String text) { return new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", PageSettings.A4, List.of(Paragraph.mixed("p", List.of(new InlineText(text, InlineStyle.of(12))), ParagraphFormat.of(12))), 0))); }
    private static String text(SectionedDocument document) { return ((Paragraph) document.sections().getFirst().blocks().getFirst()).text(); }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static ProcessingPolicy rule(ProcessingPolicy.Action action, ProcessingPolicy.Treatment treatment) { return ProcessingPolicy.builder().feature(XmlModelRecovery.FEATURE, action, treatment).build(); }
}
