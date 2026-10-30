// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocxPackageSecurityTest {
    @TempDir Path directory;

    private static byte[] document() throws IOException {
        var output = new ByteArrayOutputStream();
        new DocxAdapter().write(Document.builder().add(new Paragraph("p", "SAFE", ParagraphFormat.of(12))).build(), output);
        return output.toByteArray();
    }

    private static ByteBuffer bytes(byte[] value) { return ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN); }
    private static int central(byte[] value) { return bytes(value).getInt(value.length - 6); }

    @Test void incompleteOrDisagreeingDirectoriesFailAcrossPublicEntryPoints() throws IOException {
        byte[] original = document();
        int central = central(original);
        byte[] renamed = original.clone(); renamed[central + 46] ^= 1;
        byte[] crc = original.clone(); crc[central + 16] ^= 1;
        byte[] offset = original.clone(); bytes(offset).putInt(central + 42, 1);
        byte[] count = original.clone(); bytes(count).putShort(count.length - 12, (short) 1);
        for (byte[] invalid : List.of(Arrays.copyOf(original, central), renamed, crc, offset, count)) {
            for (var policy : DocxAdapter.Policy.values()) {
                assertThrows(IOException.class, () -> new DocxAdapter().read(new ByteArrayInputStream(invalid), policy));
                assertThrows(IOException.class, () -> new DocxAdapter().readSemantic(new ByteArrayInputStream(invalid), policy));
            }
            Path target = directory.resolve("existing.docx");
            byte[] sentinel = {1, 2, 3}; Files.write(target, sentinel);
            assertThrows(IOException.class, () -> new DocxAdapter().editPackage(invalid).replaceText("SAFE", "CHANGED").prepare().to(target));
            assertArrayEquals(sentinel, Files.readAllBytes(target));
        }
    }

    @Test void completeEncodedInputIncludingCommentsIsChargedAndBorrowedStreamStaysOpen() throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.setComment("comment".repeat(1000));
            zip.putNextEntry(new ZipEntry("word/document.xml")); zip.write(new byte[] {1}); zip.closeEntry();
        }
        byte[] encoded = output.toByteArray();
        var adapter = new DocxAdapter(DocxLimits.DEFAULT.toBuilder().maxInputBytes(encoded.length - 1).build());
        var input = new ByteArrayInputStream(encoded) {
            @Override public void close() { throw new AssertionError("caller owns stream"); }
        };
        assertThrows(IOException.class, () -> adapter.unzip(input));
        var exact = new DocxAdapter(DocxLimits.DEFAULT.toBuilder().maxInputBytes(encoded.length).build());
        assertArrayEquals(new byte[] {1}, exact.unzip(new ByteArrayInputStream(encoded)).get("word/document.xml"));
    }

    @Test void appendedBytesAreRejectedEvenWhenLocalEntriesFitTheBudget() throws IOException {
        byte[] original = document();
        byte[] trailing = Arrays.copyOf(original, original.length + 8192);
        assertThrows(IOException.class, () -> new DocxAdapter().read(new ByteArrayInputStream(trailing), DocxAdapter.Policy.ALLOW_LOSS));
    }

    @Test void ordinaryStoredDeflatedUnicodeDirectoriesAndCommentsRemainCompatible() throws IOException {
        for (int method : List.of(ZipEntry.STORED, ZipEntry.DEFLATED)) {
            var output = new ByteArrayOutputStream();
            byte[] content = {10, 20, 30}; var crc = new CRC32(); crc.update(content);
            try (var zip = new ZipOutputStream(output)) {
                zip.setComment("ZIP comment");
                zip.putNextEntry(new ZipEntry("word/")); zip.closeEntry();
                var entry = new ZipEntry("word/图片.xml"); entry.setMethod(method);
                if (method == ZipEntry.STORED) { entry.setSize(content.length); entry.setCompressedSize(content.length); entry.setCrc(crc.getValue()); }
                zip.putNextEntry(entry); zip.write(content); zip.closeEntry();
            }
            var imported = new DocxAdapter().unzip(new ByteArrayInputStream(output.toByteArray()));
            assertEquals(2, imported.size());
            assertArrayEquals(content, imported.get("word/图片.xml"));
            assertFalse(imported.isEmpty());
        }
    }
}
