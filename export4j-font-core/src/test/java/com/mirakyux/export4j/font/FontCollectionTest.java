// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FontCollectionTest {
    @TempDir Path directory;
    private static byte[] bytes(String name) throws IOException {
        try (var input = FontCollectionTest.class.getResourceAsStream("/fonts/" + name)) { return input.readAllBytes(); }
    }
    @Test void staticCffHasUnicodeMetricsAndExplicitOutlineFormat() throws IOException {
        var face = TrueTypeFontLoader.load(bytes("SourceSans3-Regular.otf"));
        assertTrue(face.data().cffOutlines());
        assertTrue(face.glyph('A').orElseThrow().advance() > 0);
    }
    @Test void selectsAndNormalizesCollectionMembersAndRejectsMalformedOffsets() throws IOException {
        var members = List.of(bytes("LiberationSans-Regular.ttf"), bytes("SourceSans3-Regular.otf"));
        var collection = collection(members);
        int total = collection.capacity();
        var latin = FontCollectionLoader.load(collection.array(), 0);
        var cff = FontCollectionLoader.load(collection.array(), 1);
        assertTrue(latin.glyph('A').isPresent()); assertTrue(cff.data().cffOutlines());
        assertNotEquals(latin.data().id(), cff.data().id());
        assertEquals(TrueTypeFontLoader.load(members.getFirst()).glyph('A'), latin.glyph('A'));
        var file = directory.resolve("collection sample.ttc"); Files.write(file, collection.array());
        assertEquals(cff.data().id(), FontCollectionLoader.load(file, 1).data().id());
        assertEquals(latin.data().id(), FontCollectionLoader.load(file.toFile(), 0).data().id());
        assertEquals(cff.data().id(), FontCollectionLoader.load(file.toString(), 1).data().id());
        assertEquals(latin.data().id(), FontCollectionLoader.load(file, 0, total).data().id());
        assertEquals(cff.data().id(), FontCollectionLoader.load(file.toFile(), 1, total).data().id());
        assertEquals(latin.data().id(), FontCollectionLoader.load(file.toString(), 0, total).data().id());
        var borrowed = new ByteArrayInputStream(collection.array()) {
            @Override public void close() { throw new AssertionError("borrowed collection stream closed"); }
        };
        assertEquals(cff.data().id(), FontCollectionLoader.load(borrowed, 1).data().id());
        assertEquals(FontLoadException.Code.BUDGET_EXCEEDED,
                assertThrows(FontLoadException.class, () -> FontCollectionLoader.load(file, 0, 32)).code());
        assertThrows(FontLoadException.class, () -> FontCollectionLoader.load(file.toString(), 0, 32));
        assertThrows(FontLoadException.class, () -> FontCollectionLoader.load(file.toFile(), 0, 32));
        assertThrows(FontLoadException.class, () -> FontCollectionLoader.load(file, 2));
        Files.move(file, directory.resolve("moved.ttc"));
        assertThrows(FontLoadException.class, () -> FontCollectionLoader.load(collection.array(), 2));
        collection.putInt(12, -1);
        assertThrows(FontLoadException.class, () -> FontCollectionLoader.load(collection.array(), 0));
        assertThrows(FontLoadException.class, () -> FontCollectionLoader.load(new byte[12], 0));
    }

    @Test void explicitSourcesSelectTheSameMemberAndUseTheBorrowedCache() throws IOException {
        byte[] latin = bytes("LiberationSans-Regular.ttf");
        byte[] cff = bytes("SourceSans3-Regular.otf");
        Path file = directory.resolve("named.ttc");
        Files.write(file, collection(List.of(latin, cff)).array());
        try (var cache = FontFaceCache.builder().build()) {
            var named = FontSource.collection(file, "SourceSans3-Regular");
            var indexed = FontSource.collection(file, 1);
            FontFace face = named.load(cache);
            assertTrue(face.data().cffOutlines());
            assertSame(face, indexed.load(cache));
            assertEquals(1, cache.stats().loads());
            assertEquals(1, cache.stats().hits());
            var cancellation = new CancellationException();
            assertSame(cancellation, assertThrows(CancellationException.class, () -> named.load(cache, () -> {
                throw cancellation;
            })));
            assertEquals(1, cache.stats().hits());
            var missing = FontSource.collection(file, "sourcesans3-regular");
            assertEquals(FontLoadException.Code.INVALID_FONT,
                    assertThrows(FontLoadException.class, () -> missing.load(cache)).code());
            Files.write(file, collection(List.of(cff, cff)).array());
            assertEquals(FontLoadException.Code.INVALID_FONT,
                    assertThrows(FontLoadException.class, () -> named.load(cache)).code());
        }
    }

    private static ByteBuffer collection(List<byte[]> members) {
        int offset = 12 + 4 * members.size();
        int total = offset + members.stream().mapToInt(bytes -> bytes.length).sum();
        var result = ByteBuffer.allocate(total);
        result.putInt(0x74746366).putInt(0x10000).putInt(members.size());
        for (int index = 0; index < members.size(); index++) {
            var member = ByteBuffer.wrap(members.get(index).clone());
            result.putInt(12 + index * 4, offset);
            int tables = Short.toUnsignedInt(member.getShort(4));
            for (int table = 0; table < tables; table++) {
                int field = 12 + table * 16 + 8;
                member.putInt(field, member.getInt(field) + offset);
            }
            System.arraycopy(member.array(), 0, result.array(), offset, member.capacity());
            offset += member.capacity();
        }
        return result;
    }
}
