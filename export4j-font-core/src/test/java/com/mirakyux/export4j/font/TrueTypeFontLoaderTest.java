// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrueTypeFontLoaderTest {
    @Test
    void loadedFaceOwnsBytesAndRemainsUsableAfterInputCloses() throws IOException {
        byte[] bytes = fontBytes();
        FontFace face = TrueTypeFontLoader.load(bytes);
        byte[] original = bytes.clone();
        bytes[0] = 99;
        byte[] returned = face.data().bytes();
        returned[1] = 99;
        assertArrayEquals(original, face.data().bytes());
        assertTrue(face.glyph('A').isPresent());
        assertTrue(face.glyph('A').orElseThrow().advance() > 0);
        assertTrue(face.metrics().ascent() > 0);
        assertFalse(face.glyph(0x10FFFF).isPresent());
        assertEquals(face.data().id(), TrueTypeFontLoader.load(original).data().id());
    }

    @Test
    void honorsSizeLimitsWithoutClosingBorrowedStream() throws IOException {
        class TrackedInput extends ByteArrayInputStream {
            boolean closed;

            TrackedInput(byte[] data) {
                super(data);
            }

            @Override
            public void close() {
                closed = true;
            }
        }
        TrackedInput input = new TrackedInput(fontBytes());
        FontLoadException failure = assertThrows(FontLoadException.class,
                () -> TrueTypeFontLoader.load(input, 32));
        assertEquals(FontLoadException.Code.BUDGET_EXCEEDED, failure.code());
        assertFalse(input.closed);
        assertEquals(33, fontBytes().length - input.available());
    }

    @Test
    void malformedAndRestrictedFontsHaveExplicitFailureCodes() throws IOException {
        FontLoadException invalid = assertThrows(FontLoadException.class,
                () -> TrueTypeFontLoader.load(new byte[12]));
        assertEquals(FontLoadException.Code.INVALID_FONT, invalid.code());
        byte[] restricted = fontBytes();
        int flags = embeddingFlagsOffset(restricted);
        restricted[flags] = 0;
        restricted[flags + 1] = 2;
        FontLoadException denied = assertThrows(FontLoadException.class,
                () -> TrueTypeFontLoader.load(restricted));
        assertEquals(FontLoadException.Code.EMBEDDING_RESTRICTED, denied.code());
    }

    @Test
    void noSubsettingFlagIsPreserved() throws IOException {
        byte[] bytes = fontBytes();
        int offset = embeddingFlagsOffset(bytes);
        bytes[offset] = 1;
        bytes[offset + 1] = 0;
        FontFace complete = TrueTypeFontLoader.load(bytes);
        assertFalse(complete.data().subsetAllowed());
        assertNotEquals(complete.data().id(), TrueTypeFontLoader.load(fontBytes()).data().id());
    }

    private static int embeddingFlagsOffset(byte[] bytes) {
        ByteBuffer table = ByteBuffer.wrap(bytes);
        int count = Short.toUnsignedInt(table.getShort(4));
        for (int index = 0; index < count; index++) {
            int entry = 12 + index * 16;
            if (new String(bytes, entry, 4, StandardCharsets.US_ASCII).equals("OS/2")) {
                return table.getInt(entry + 8) + 8;
            }
        }
        throw new AssertionError("Test font has no OS/2 table");
    }

    private static byte[] fontBytes() throws IOException {
        try (InputStream input = TrueTypeFontLoaderTest.class.getResourceAsStream(
                "/fonts/LiberationSans-Regular.ttf")) {
            if (input == null) {
                throw new IOException("Missing test font");
            }
            return input.readAllBytes();
        }
    }
}
