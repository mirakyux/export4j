// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.io.TempDir;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;

class PdfProtectionTest {
    @TempDir Path directory;

    @Test void archivalRejectsEncryptionAndUnembeddedStandardTextWithoutWritingOutput() throws IOException {
        com.mirakyux.export4j.font.FontData font;
        try (var input = getClass().getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            font = com.mirakyux.export4j.font.TrueTypeFontLoader.load(input, com.mirakyux.export4j.font.TrueTypeFontLoader.DEFAULT_MAX_BYTES).data();
        }
        var archival = new PdfArchival(java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB).getData(), font);
        var options = PdfOptions.DEFAULT.withArchival(archival);
        assertThrows(IllegalArgumentException.class, () -> options.withEncryption(new PdfEncryption("owner".toCharArray(), "user".toCharArray(), PdfEncryption.Permissions.ALL)));
        var builder = new DisplayListBuilder(PageSize.A4);
        builder.drawText("Standard", new com.mirakyux.export4j.graphics.Point(20, 30), new com.mirakyux.export4j.graphics.TextStyle(
                com.mirakyux.export4j.graphics.FontFamily.SANS_SERIF, 12, com.mirakyux.export4j.graphics.RgbColor.BLACK));
        var output = new ByteArrayOutputStream();
        assertThrows(PdfRenderException.class, () -> new PdfBackend(PdfLimits.DEFAULT, options).write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(builder.build())), output));
        assertEquals(0, output.size());
    }

    @Test void signatureCallbackFailureKeepsSourceAndBorrowedOutputOpen() throws IOException {
        Path source = directory.resolve("source.pdf");
        try (var output = Files.newOutputStream(source)) { new PdfBackend().write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(new DisplayListBuilder(PageSize.A4).build())), output); }
        byte[] original = Files.readAllBytes(source);
        var output = new ByteArrayOutputStream() {
            @Override public void close() { throw new AssertionError("borrowed output closed"); }
        };
        var options = new PdfSigner.Options("Test", "Callback failure", Instant.parse("2026-10-14T00:00:00Z"), 4096);
        assertThrows(IOException.class, () -> PdfSigner.sign(source, output, options, content -> {
            assertTrue(content.readNBytes(10).length > 0); throw new IOException("signer rejected request");
        }));
        org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(source));
        assertThrows(IOException.class, () -> PdfSigner.sign(source, output, options, content -> new byte[4097]));
    }
    @Test void aes256RequiresPasswordAndPreservesRequestedUserPermissions() throws IOException {
        var encryption = new PdfEncryption("owner-secret".toCharArray(), "reader-secret".toCharArray(), PdfEncryption.Permissions.READ_ONLY);
        var backend = new PdfBackend(PdfLimits.DEFAULT, PdfOptions.storage(PdfOptions.Storage.TEMP_FILE).withEncryption(encryption));
        var output = new ByteArrayOutputStream();
        backend.write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(new DisplayListBuilder(PageSize.A4).build())), output);
        byte[] bytes = output.toByteArray();
        assertThrows(InvalidPasswordException.class, () -> Loader.loadPDF(bytes));
        try (var pdf = Loader.loadPDF(bytes, "reader-secret")) {
            assertTrue(pdf.isEncrypted()); assertEquals(256, pdf.getEncryption().getLength());
            var permission = pdf.getCurrentAccessPermission();
            assertFalse(permission.canPrint()); assertFalse(permission.canExtractContent()); assertFalse(permission.canModify());
        }
        try (var pdf = Loader.loadPDF(bytes, "owner-secret")) { assertTrue(pdf.getCurrentAccessPermission().isOwnerPermission()); }
        assertFalse(encryption.toString().contains("owner-secret"));
    }
}
