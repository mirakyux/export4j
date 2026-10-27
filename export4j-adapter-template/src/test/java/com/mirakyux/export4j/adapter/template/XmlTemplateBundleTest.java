// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.Table;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class XmlTemplateBundleTest {
    private static final String SOURCE = """
            <document version="1" title="Report ${title}">
              <include ref="intro" prefix="intro"/>
              <if test="visible"><then><p>Shown</p></then><else><p>Hidden</p></else></if>
              <each items="items" as="item"><p>${item.name}</p></each>
              <rows items="items" as="item"><columns><column weight="1"/></columns>
                <header><cell>Name</cell></header><row><cell>${item.name}</cell></row>
              </rows>
              <p><image ref="logo" width="12" height="12" alt="Logo"/></p>
            </document>
            """;
    private static final String FRAGMENT = "<fragment version='1'><p>Intro ${title}</p></fragment>\r\n";

    @Test void editsRetainedSourceAndResourcesAndRendersSeveralDatasetsAfterReopening() throws IOException {
        byte[] font = {1, 2, 3}; var image = image();
        var original = XmlTemplateBundle.builder(SOURCE).fragment("intro", FRAGMENT).image("logo", image)
                .font("Regular.ttf", font).license("Font-OFL.txt", "Original notice\r\n").build();
        font[0] = 0;
        var edited = XmlTemplateBundle.read(original.encode()).toBuilder()
                .source(SOURCE.replace("Shown", "Approved")).fragment("intro", FRAGMENT.replace("Intro", "Edited")).build();
        var reopened = XmlTemplateBundle.read(edited.encode());
        var template = reopened.compile();
        var first = template.render(Map.of("title", "A", "visible", true, "items", List.of(Map.of("name", "Alpha"), Map.of("name", "Beta"))));
        assertEquals(List.of("Edited A", "Approved", "Alpha", "Beta", "\ufffc"), first.paragraphs().stream().map(Paragraph::text).toList());
        var table = assertInstanceOf(Table.class, first.blocks().get(4));
        assertEquals(List.of("Name", "Alpha", "Beta"), table.rows().stream().map(row -> row.cells().getFirst().text()).toList());
        var second = template.render(Map.of("title", "B", "visible", false, "items", List.of(Map.of("name", "Gamma"))));
        assertEquals(List.of("Edited B", "Hidden", "Gamma", "\ufffc"), second.paragraphs().stream().map(Paragraph::text).toList());
        assertEquals("Report B", second.metadata().title());
        assertTrue(reopened.source().contains("${item.name}"));
        assertEquals(SOURCE, original.source()); assertEquals(FRAGMENT, original.fragments().get("intro"));
        assertArrayEquals(image.bytes(), reopened.images().get("logo").bytes());
        assertEquals("Original notice\r\n", reopened.licenses().get("Font-OFL.txt"));
        assertArrayEquals(new byte[]{1, 2, 3}, reopened.fonts().get("Regular.ttf"));
        reopened.fonts().get("Regular.ttf")[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, reopened.fonts().get("Regular.ttf"));
        assertArrayEquals(reopened.encode(), XmlTemplateBundle.read(reopened.encode()).encode());
    }

    @Test void ordinaryStoredAndDeflatedZipAcceptsDirectoriesExtrasCommentsAndExactUtf8Source() throws IOException {
        String source = "<!-- 中文 😀 -->\r\n<document version='1'><p>${name}</p></document>\r\n";
        for (int method : List.of(ZipEntry.STORED, ZipEntry.DEFLATED)) {
            var entries = new LinkedHashMap<String, byte[]>(); entries.put("fonts/", new byte[0]);
            entries.put("template.xml", source.getBytes(StandardCharsets.UTF_8)); entries.put("fonts/Regular.otf", new byte[]{42});
            var bundle = XmlTemplateBundle.read(zip(entries, method));
            assertEquals(source, bundle.source());
            assertEquals("中文", bundle.compile().render(Map.of("name", "中文")).paragraphs().getFirst().text());
            assertEquals(source, XmlTemplateBundle.read(bundle.encode()).source());
        }
    }

    @Test void savingDraftDoesNotCompileButExistingCompilerAndBinderKeepTheirDiagnostics() throws IOException {
        var draft = XmlTemplateBundle.builder("<unfinished").build();
        assertThrows(TemplateException.class, XmlTemplateBundle.read(draft.encode())::compile);
        var forbidden = draft.toBuilder().source("<!DOCTYPE document [<!ENTITY x SYSTEM 'file:///unavailable'>]><document version='1'><p>&x;</p></document>").build();
        assertEquals(TemplateException.Code.FORBIDDEN_XML, assertThrows(TemplateException.class, forbidden::compile).code());
        var missing = draft.toBuilder().source("<document version='1'><include ref='missing' prefix='x'/></document>").build();
        assertEquals(TemplateException.Code.UNKNOWN_RESOURCE, assertThrows(TemplateException.class, missing::compile).code());
        var template = XmlTemplateBundle.builder("<document version='1'><p>${name}</p></document>").build().compile();
        assertThrows(DocumentTemplate.BindingException.class, () -> template.render(Map.of()));
        var condition = XmlTemplateBundle.builder("<document version='1'><if test='visible'><then><p>A</p></then></if></document>").build().compile();
        assertThrows(DocumentTemplate.BindingException.class, () -> condition.render(Map.of("visible", "true")));
    }

    @Test void removesSelectedResourceWithoutRewritingUnchangedXml() throws IOException {
        var bundle = XmlTemplateBundle.builder(SOURCE).fragment("intro", FRAGMENT).image("logo", image()).build();
        var edited = bundle.toBuilder().remove("images/logo.png").build();
        assertEquals(SOURCE, edited.source()); assertEquals(FRAGMENT, edited.fragments().get("intro"));
        assertThrows(TemplateException.class, edited::compile);
        assertThrows(IOException.class, () -> edited.toBuilder().remove("template.xml").build());
        assertThrows(IllegalArgumentException.class, () -> edited.toBuilder().remove("../template.xml"));
    }

    @Test void rejectsUnsupportedPathsMissingMainDuplicatesAndAmbiguousImageNames() throws IOException {
        for (String name : List.of("../template.xml", "/template.xml", "images\\logo.png", "other.xml", "Template.xml", "fonts/a.js")) {
            assertThrows(IOException.class, () -> XmlTemplateBundle.read(zip(Map.of(name, new byte[]{1}), ZipEntry.DEFLATED)));
        }
        assertThrows(IOException.class, () -> XmlTemplateBundle.read(zip(Map.of("fragments/a.xml", new byte[]{65}), ZipEntry.STORED)));
        byte[] valid = simple().encode();
        byte[] duplicate = zip(Map.of("template.xml", new byte[]{65}, "templatf.xml", new byte[]{66}), ZipEntry.STORED);
        replaceAscii(duplicate, "templatf.xml", "template.xml");
        assertThrows(IOException.class, () -> XmlTemplateBundle.read(duplicate));
        byte[] png = image().bytes(); var jpeg = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "jpeg", jpeg);
        var ambiguous = Map.of("template.xml", "<document version='1'/>".getBytes(StandardCharsets.UTF_8),
                "images/logo.jpg", jpeg.toByteArray(), "images/logo.png", png);
        assertThrows(IOException.class, () -> XmlTemplateBundle.read(zip(ambiguous, ZipEntry.DEFLATED)));
        assertTrue(valid.length > 22);
    }

    @Test void rejectsTruncationCrcMismatchDirectoryMismatchEncryptionAndTrailingBytes() throws IOException {
        byte[] encoded = zip(Map.of("template.xml", "<document version='1'/>".getBytes(StandardCharsets.UTF_8)), ZipEntry.STORED);
        for (int length : List.of(0, 21, encoded.length - 1, encoded.length - 22)) {
            assertThrows(IOException.class, () -> XmlTemplateBundle.read(Arrays.copyOf(encoded, length)));
        }
        byte[] crc = encoded.clone();
        var local = ByteBuffer.wrap(crc).order(ByteOrder.LITTLE_ENDIAN);
        crc[30 + Short.toUnsignedInt(local.getShort(26)) + Short.toUnsignedInt(local.getShort(28))] ^= 1;
        assertThrows(IOException.class, () -> XmlTemplateBundle.read(crc));
        int central = find(encoded, 0x02014b50);
        byte[] mismatch = encoded.clone(); mismatch[central + 24] ^= 1;
        assertThrows(IOException.class, () -> XmlTemplateBundle.read(mismatch));
        byte[] encrypted = encoded.clone(); encrypted[central + 8] |= 1;
        assertThrows(IOException.class, () -> XmlTemplateBundle.read(encrypted));
        byte[] zip64 = encoded.clone(); zip64[4] = 45; zip64[central + 6] = 45;
        assertThrows(IOException.class, () -> XmlTemplateBundle.read(zip64));
        assertThrows(IOException.class, () -> XmlTemplateBundle.read(Arrays.copyOf(encoded, encoded.length + 1)));
    }

    @Test void rejectsInvalidUtf8InvalidImagesAndDeclaredImageTypeMismatch() throws IOException {
        assertThrows(IOException.class, () -> XmlTemplateBundle.read(zip(Map.of("template.xml", new byte[]{(byte) 0xc3, 0x28}), ZipEntry.STORED)));
        assertThrows(IllegalArgumentException.class, () -> XmlTemplateBundle.builder("\ud800"));
        var entries = new LinkedHashMap<String, byte[]>(); entries.put("template.xml", "draft".getBytes(StandardCharsets.UTF_8));
        entries.put("images/logo.png", new byte[]{1, 2});
        assertThrows(IOException.class, () -> XmlTemplateBundle.read(zip(entries, ZipEntry.STORED)));
        entries.remove("images/logo.png"); entries.put("images/logo.jpg", image().bytes());
        assertThrows(IOException.class, () -> XmlTemplateBundle.read(zip(entries, ZipEntry.STORED)));
    }

    @Test void encodedExpandedEntrySourcePixelAndFontBudgetsAreEnforcedBeforePublication() throws IOException {
        var bundle = simple(); byte[] encoded = bundle.encode();
        assertThrows(IOException.class, () -> XmlTemplateBundle.read(encoded, limits(21, 1000, 10, 1000, 1000), ExecutionOptions.DEFAULT));
        assertThrows(IOException.class, () -> XmlTemplateBundle.read(encoded, limits(1000, 1, 10, 1000, 1000), ExecutionOptions.DEFAULT));
        assertThrows(IOException.class, () -> bundle.toBuilder().limits(limits(1000, 1000, 10, 1, 1000)).build());
        assertThrows(IOException.class, () -> bundle.toBuilder().fragment("f", "draft").limits(limits(1000, 1000, 1, 1000, 1000)).build());
        assertThrows(IOException.class, () -> bundle.toBuilder().image("i", image()).limits(limits(1000, 1000, 10, 1000, 1)).build());
        assertThrows(IOException.class, () -> bundle.toBuilder().font("f.ttf", new byte[0]).build());
        var tinyEncoding = bundle.toBuilder().limits(limits(1, 1000, 10, 1000, 1000)).build();
        var output = new ByteArrayOutputStream(); assertThrows(IOException.class, () -> tinyEncoding.write(output)); assertEquals(0, output.size());
        // A false small central size still cannot bypass expansion checks on the actual local stream.
        byte[] bomb = zip(Map.of("template.xml", new byte[4096]), ZipEntry.DEFLATED);
        ByteBuffer.wrap(bomb).order(ByteOrder.LITTLE_ENDIAN).putInt(find(bomb, 0x02014b50) + 24, 1);
        assertThrows(IOException.class, () -> XmlTemplateBundle.read(bomb, limits(1000, 64, 10, 1000, 1000), ExecutionOptions.DEFAULT));
    }

    @Test void callerStreamsRemainOpenAndFailuresPropagate() throws IOException {
        var input = new ByteArrayInputStream(simple().encode()) { @Override public void close() { fail("borrowed input closed"); } };
        var bundle = XmlTemplateBundle.read(input);
        var output = new ByteArrayOutputStream() {
            @Override public void close() { fail("borrowed output closed"); }
            @Override public void flush() { fail("borrowed output flushed"); }
        };
        bundle.write(output); assertEquals(bundle.source(), XmlTemplateBundle.read(output.toByteArray()).source());
        var broken = new OutputStream() { @Override public void write(int value) throws IOException { throw new IOException("failure"); } };
        assertThrows(IOException.class, () -> bundle.write(broken));
    }

    @Test void cancellationAndOutputBudgetPreserveOldPathAndCleanTemporary(@TempDir Path directory) throws IOException {
        Path target = directory.resolve("template.zip"); Files.writeString(target, "old");
        var cancelled = new AtomicBoolean();
        var options = ExecutionOptions.builder().cancelled(cancelled::get).build();
        var bundle = simple().toBuilder().execution(options).build(); cancelled.set(true);
        assertEquals(ExecutionException.Code.CANCELLED, assertThrows(ExecutionException.class, () -> bundle.write(target)).code());
        cancelled.set(false);
        var small = bundle.toBuilder().execution(ExecutionOptions.builder().maxOutputBytes(1).build()).build();
        assertEquals(ExecutionException.Code.OUTPUT_LIMIT, assertThrows(ExecutionException.class, () -> small.write(target)).code());
        assertEquals("old", Files.readString(target));
        try (var entries = Files.list(directory)) { assertEquals(List.of(target), entries.toList()); }
        bundle.write(target); assertEquals(bundle.source(), XmlTemplateBundle.read(Files.readAllBytes(target)).source());
        Path occupied = directory.resolve("occupied"); Files.createDirectory(occupied); Files.writeString(occupied.resolve("child"), "keep");
        assertThrows(IOException.class, () -> bundle.write(occupied)); assertEquals("keep", Files.readString(occupied.resolve("child")));
        try (var entries = Files.list(directory)) { assertEquals(2, entries.count()); }
    }

    @Test void cancellationRequestedByInputAndOutputIsObservedWithoutClosingCallerStreams() throws IOException {
        var cancelled = new AtomicBoolean(); var options = ExecutionOptions.builder().cancelled(cancelled::get).build();
        var input = new ByteArrayInputStream(simple().encode()) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                int count = super.read(bytes, offset, length); cancelled.set(true); return count;
            }
            @Override public void close() { fail("closed"); }
        };
        assertThrows(ExecutionException.class, () -> XmlTemplateBundle.read(input, XmlTemplateBundle.Limits.DEFAULT, options));
        cancelled.set(false); var bundle = simple().toBuilder().execution(options).build();
        var output = new OutputStream() { @Override public void write(int value) { cancelled.set(true); } };
        assertThrows(ExecutionException.class, () -> bundle.write(output));
    }

    private static XmlTemplateBundle simple() throws IOException { return XmlTemplateBundle.builder("<document version='1'><p>Hello</p></document>").build(); }
    private static XmlTemplateBundle.Limits limits(int encoded, int expanded, int entries, int source, long pixels) {
        return new XmlTemplateBundle.Limits(encoded, expanded, entries, source, pixels);
    }
    private static ImageResource image() throws IOException {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB); var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes); return ImageResource.load(bytes.toByteArray());
    }
    private static byte[] zip(Map<String, byte[]> entries, int method) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.setComment("Ordinary ZIP comment");
            for (var item : entries.entrySet()) {
                var header = new ZipEntry(item.getKey()); header.setMethod(method); header.setComment("Editor comment");
                header.setExtra(new byte[]{(byte) 0xfe, (byte) 0xca, 0, 0});
                if (method == ZipEntry.STORED) { var crc = new CRC32(); crc.update(item.getValue()); header.setCrc(crc.getValue()); header.setSize(item.getValue().length); }
                zip.putNextEntry(header); zip.write(item.getValue()); zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    private static int find(byte[] bytes, int signature) {
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i <= bytes.length - 4; i++) { if (buffer.getInt(i) == signature) { return i; } }
        throw new AssertionError("missing ZIP signature");
    }
    private static void replaceAscii(byte[] bytes, String before, String after) {
        byte[] from = before.getBytes(StandardCharsets.US_ASCII), to = after.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i <= bytes.length - from.length; i++) { if (Arrays.equals(bytes, i, i + from.length, from, 0, from.length)) { System.arraycopy(to, 0, bytes, i, to.length); } }
    }
}
