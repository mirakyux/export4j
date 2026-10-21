// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

class FontCollectionTest {
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
        assertThrows(FontLoadException.class, () -> FontCollectionLoader.load(collection.array(), 2));
        collection.putInt(12, -1);
        assertThrows(FontLoadException.class, () -> FontCollectionLoader.load(collection.array(), 0));
        assertThrows(FontLoadException.class, () -> FontCollectionLoader.load(new byte[12], 0));
    }
}
