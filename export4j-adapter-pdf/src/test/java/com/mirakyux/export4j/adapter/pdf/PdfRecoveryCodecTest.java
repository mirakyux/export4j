// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.adapter.template.XmlModelRecovery;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfRecoveryCodecTest {
    @Test void attachmentRoundTripPreservesOriginalPdfAndExternalSaveInvalidatesBinding() throws IOException {
        byte[] plain;
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage()); document.save(output); plain = output.toByteArray();
        }
        var codec = new PdfRecoveryCodec(); var limits = XmlModelRecovery.Limits.DEFAULT;
        byte[] zip = "recovery ZIP payload".getBytes(StandardCharsets.US_ASCII);
        byte[] embedded = codec.encode(plain, zip, limits);
        assertArrayEquals(embedded, codec.encode(plain, zip, limits));
        assertFalse(java.util.Arrays.equals(plain, embedded));
        assertArrayEquals(plain, codec.decode(embedded, limits).standard());
        assertArrayEquals(zip, codec.decode(embedded, limits).companion());
        try (var document = Loader.loadPDF(embedded); var output = new ByteArrayOutputStream()) {
            assertTrue(PdfObjects.files(document, PdfLimits.DEFAULT).containsKey("export4j-recovery.zip"));
            document.getDocumentInformation().setTitle("changed by PDF editor");
            document.save(output);
            var stale = codec.decode(output.toByteArray(), limits);
            assertFalse(java.util.Arrays.equals(plain, stale.standard()));
            assertArrayEquals(zip, stale.companion());
            try (var stripped = Loader.loadPDF(stale.standard())) {
                assertFalse(PdfObjects.files(stripped, PdfLimits.DEFAULT).containsKey("export4j-recovery.zip"));
            }
        }
        assertThrows(IOException.class, () -> codec.encode(plain, zip, new XmlModelRecovery.Limits(plain.length + 1, 1024, 100, limits.bundle())));
    }
}
