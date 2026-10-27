// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedZipRecoveryTest {
    private static final ProcessingReport EMPTY = new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of());

    @Test void bothZipFormatsKeepOriginalBytesAndRejectChangedVisibleMember() throws IOException {
        for (String format : List.of("DOCX", "PNG_ZIP")) {
            byte[] plain = zip(format);
            var target = new DocumentConversion.Target(format, (document, notes, policy, output) -> { output.write(plain); return EMPTY; });
            var saved = XmlModelRecovery.capture(DocumentConversion.from(document("ORIGINAL"), List.of()).prepare(target)).prepare();
            byte[] embedded = saved.embeddedBytes();
            assertArrayEquals(plain, ZipRecoveryEnvelope.decode(format, embedded, XmlModelRecovery.Limits.DEFAULT).document());
            assertEquals(document("ORIGINAL"), XmlModelRecovery.openEmbedded(format, embedded).prepare().document());
            assertTrue(embedded.length > plain.length);
            byte[] external = rewrite(embedded, format.equals("DOCX") ? "word/document.xml" : "page-00001.png");
            assertEquals("STALE_OR_CONFLICTING_COMPANION", assertThrows(com.mirakyux.export4j.model.processing.ProcessingException.class,
                    () -> XmlModelRecovery.openEmbedded(format, external).prepare()).issue().reason());
            var opened = XmlModelRecovery.openEmbedded(format, external).policy(ProcessingPolicy.builder()
                    .feature(XmlModelRecovery.FEATURE, ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build())
                    .fallback((bytes, shared) -> {
                        assertFalse(contains(bytes, format.equals("DOCX") ? "customXml/export4j-recovery.xml" : "export4j/recovery.zip"));
                        return new DocumentConversion.Input(document("CURRENT"), List.of(), EMPTY);
                    }).prepare();
            assertFalse(opened.recovered()); assertEquals(document("CURRENT"), opened.document());
        }
    }

    @Test void corruptedRecoveryAndOutputEntryBudgetNeverPublish(@TempDir Path directory) throws IOException {
        String format = "DOCX"; byte[] plain = zip(format);
        var target = new DocumentConversion.Target(format, (document, notes, policy, output) -> { output.write(plain); return EMPTY; });
        var saved = XmlModelRecovery.capture(DocumentConversion.from(document("ORIGINAL"), List.of()).prepare(target)).prepare();
        byte[] corrupted = saved.embeddedBytes();
        byte[] marker = "customXml/export4j-recovery.xml".getBytes(StandardCharsets.US_ASCII);
        int offset = indexOf(corrupted, marker); assertTrue(offset > 0);
        corrupted[offset + marker.length] ^= 1;
        var called = new AtomicBoolean();
        assertThrows(IOException.class, () -> XmlModelRecovery.openEmbedded(format, corrupted)
                .policy(ProcessingPolicy.builder().feature(XmlModelRecovery.FEATURE, ProcessingPolicy.Action.IGNORE,
                        ProcessingPolicy.Treatment.DEGRADE).build())
                .fallback((current, shared) -> { called.set(true); return new DocumentConversion.Input(document("BAD"), List.of(), EMPTY); })
                .prepare());
        assertFalse(called.get());
        Path old = directory.resolve("old.docx"); Files.writeString(old, "OLD");
        var bundle = XmlTemplateBundle.Limits.DEFAULT;
        var limited = new XmlTemplateBundle.Limits(bundle.maxEncodedBytes(), bundle.maxExpandedBytes(), 2, bundle.maxSourceUtf16(), bundle.maxImagePixels());
        var bounded = XmlModelRecovery.capture(DocumentConversion.from(document("ORIGINAL"), List.of()).prepare(target))
                .limits(new XmlModelRecovery.Limits(plain.length + 100_000, 1024 * 1024, 100, limited)).prepare();
        assertThrows(IOException.class, () -> bounded.writeEmbedded(old)); assertEquals("OLD", Files.readString(old));
    }

    private static byte[] zip(String format) throws IOException {
        var result = new ByteArrayOutputStream();
        try (var output = new ZipOutputStream(result)) {
            if (format.equals("DOCX")) {
                entry(output, "[Content_Types].xml", "<Types><Default Extension='xml' ContentType='application/xml'/></Types>".getBytes(StandardCharsets.UTF_8));
                entry(output, "word/document.xml", "ORIGINAL".getBytes(StandardCharsets.UTF_8));
            } else { entry(output, "page-00001.png", "ORIGINAL".getBytes(StandardCharsets.UTF_8)); }
        }
        return result.toByteArray();
    }
    private static byte[] rewrite(byte[] source, String changed) throws IOException {
        var result = new ByteArrayOutputStream();
        try (var input = new ZipInputStream(new ByteArrayInputStream(source)); var output = new ZipOutputStream(result)) {
            for (ZipEntry item; (item = input.getNextEntry()) != null;) {
                byte[] bytes = input.readAllBytes();
                entry(output, item.getName(), item.getName().equals(changed) ? "EXTERNAL".getBytes(StandardCharsets.UTF_8) : bytes);
                input.closeEntry();
            }
        }
        return result.toByteArray();
    }
    private static boolean contains(byte[] source, String name) throws IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(source))) {
            for (ZipEntry item; (item = zip.getNextEntry()) != null;) { if (item.getName().equals(name)) { return true; } }
        }
        return false;
    }
    private static void entry(ZipOutputStream output, String name, byte[] bytes) throws IOException {
        var item = new ZipEntry(name); item.setTime(0); output.putNextEntry(item); output.write(bytes); output.closeEntry();
    }
    private static int indexOf(byte[] source, byte[] marker) {
        for (int offset = 0; offset <= source.length - marker.length; offset++) {
            if (Arrays.equals(source, offset, offset + marker.length, marker, 0, marker.length)) { return offset; }
        }
        return -1;
    }
    private static SectionedDocument document(String text) {
        return new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", PageSettings.A4,
                List.of(Paragraph.mixed("p", List.of(new InlineText(text, InlineStyle.of(12))), ParagraphFormat.of(12))), 0)));
    }
}
