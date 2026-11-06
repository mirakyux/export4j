// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        int total = 20 + members.stream().mapToInt(b -> b.length).sum();
        var collection = ByteBuffer.allocate(total);
        collection.putInt(0x74746366).putInt(0x10000).putInt(2);
        int offset = 20;
        for (int index = 0; index < members.size(); index++) {
            byte[] member = members.get(index).clone();
            collection.putInt(12 + index * 4, offset);
            var sfnt = ByteBuffer.wrap(member);
            int tables = Short.toUnsignedInt(sfnt.getShort(4));
            for (int table = 0; table < tables; table++) {
                int field = 12 + table * 16 + 8;
                sfnt.putInt(field, sfnt.getInt(field) + offset);
            }
            System.arraycopy(member, 0, collection.array(), offset, member.length);
            offset += member.length;
        }
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
}
