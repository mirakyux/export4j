// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;

class PdfFontFingerprintTest {
    @Test
    void dictionaryOrderAndIndirectReferencesDoNotChangeResourceIdentity() throws IOException {
        var first = font();
        first.setInt(COSName.FIRST_CHAR, 32); first.setInt(COSName.LAST_CHAR, 64);
        var second = new COSDictionary();
        second.setItem(COSName.LAST_CHAR, new COSObject(COSInteger.get(64)));
        second.setInt(COSName.FIRST_CHAR, 32);
        second.setName(COSName.BASE_FONT, "SameFont"); second.setItem(COSName.TYPE, COSName.FONT);
        assertNotNull(key(first)); assertEquals(key(first), key(second));
    }

    @Test
    void distinguishesEncodingUnicodeWidthsAndActualFontProgramDespiteMatchingNames() throws IOException {
        var font = font();
        try (var pdf = new PDDocument()) {
            var program = pdf.getDocument().createCOSStream();
            try (var output = program.createOutputStream()) { output.write("program A".getBytes(StandardCharsets.US_ASCII)); }
            var descriptor = new COSDictionary(); descriptor.setItem(COSName.FONT_FILE2, program);
            font.setItem(COSName.FONT_DESC, descriptor);
            font.setItem(COSName.ENCODING, COSName.IDENTITY_H);
            var widths = new COSArray(); widths.add(COSInteger.get(600)); font.setItem(COSName.W, widths);
            var cmap = pdf.getDocument().createCOSStream();
            try (var output = cmap.createOutputStream()) { output.write("<0001> <0041>".getBytes(StandardCharsets.US_ASCII)); }
            font.setItem(COSName.TO_UNICODE, cmap);
            String original = key(font);
            assertNotNull(original);
            font.setItem(COSName.ENCODING, COSName.IDENTITY_V);
            assertNotEquals(original, key(font));
            font.setItem(COSName.ENCODING, COSName.IDENTITY_H);
            widths.set(0, COSInteger.get(900)); assertNotEquals(original, key(font)); widths.set(0, COSInteger.get(600));
            try (var output = cmap.createOutputStream()) { output.write("<0001> <0042>".getBytes(StandardCharsets.US_ASCII)); }
            assertNotEquals(original, key(font));
            try (var output = cmap.createOutputStream()) { output.write("<0001> <0041>".getBytes(StandardCharsets.US_ASCII)); }
            assertEquals(original, key(font));
            try (var output = program.createOutputStream()) { output.write("program B".getBytes(StandardCharsets.US_ASCII)); }
            assertNotEquals(original, key(font));
        }
    }

    @Test
    void declinesCyclesAndDeepGraphsInsteadOfRecursingWithoutBounds() throws IOException {
        var font = font(); font.setItem(COSName.FONT_DESC, font);
        assertNull(key(font));
        font.removeItem(COSName.FONT_DESC);
        var outer = new COSArray(); var cursor = outer;
        for (int i = 0; i < 100; i++) { var child = new COSArray(); cursor.add(child); cursor = child; }
        font.setItem(COSName.W, outer); assertNull(key(font));
        font.removeItem(COSName.W);
        // A dictionary exactly at the traversal boundary must decline cleanly as well.
        outer = new COSArray(); cursor = outer;
        for (int i = 0; i < 62; i++) { var child = new COSArray(); cursor.add(child); cursor = child; }
        var leaf = new COSDictionary(); leaf.setInt(COSName.FIRST_CHAR, 1); cursor.add(leaf);
        font.setItem(COSName.W, outer); assertNull(key(font));
    }

    @Test
    void observesCancellationWhileWalkingFontResources() {
        var cancelled = new AtomicBoolean();
        var fingerprint = new PdfFontFingerprint(value -> cancelled.set(true));
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(
                ExecutionOptions.builder().cancelled(cancelled::get).build(), () -> fingerprint.key(font())));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        ExecutionContext.checkpoint();
    }

    private static COSDictionary font() {
        var font = new COSDictionary(); font.setItem(COSName.TYPE, COSName.FONT); font.setName(COSName.BASE_FONT, "SameFont");
        return font;
    }

    private static String key(COSDictionary font) throws IOException { return new PdfFontFingerprint(value -> { }).key(font); }
}
