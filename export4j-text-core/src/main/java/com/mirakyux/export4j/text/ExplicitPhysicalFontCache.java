// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import com.mirakyux.export4j.font.FontData;
import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Bounded reuse of explicitly supplied base fonts, without retaining source arrays or derived sizes. */
final class ExplicitPhysicalFontCache {
    private record Entry(Font font, int sourceBytes) { }

    private final int maxEntries;
    private final long maxSourceBytes;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private long sourceBytes;

    ExplicitPhysicalFontCache(int maxEntries, long maxSourceBytes) {
        if (maxEntries < 1 || maxSourceBytes < 1) {
            throw new IllegalArgumentException("physical font cache limits must be positive");
        }
        this.maxEntries = maxEntries;
        this.maxSourceBytes = maxSourceBytes;
    }

    // Loading under the same lock prevents concurrent paragraphs from parsing one program repeatedly.
    synchronized Font get(FontData data) {
        String id = data.id();
        Entry cached = entries.get(id);
        if (cached != null) { return cached.font(); }

        Font loaded = load(data);
        int weight = data.byteSize();
        if (weight > maxSourceBytes) { return loaded; }

        while (entries.size() >= maxEntries || sourceBytes > maxSourceBytes - weight) {
            var oldest = entries.entrySet().iterator();
            sourceBytes -= oldest.next().getValue().sourceBytes();
            oldest.remove();
        }
        entries.put(id, new Entry(loaded, weight));
        sourceBytes += weight;
        return loaded;
    }

    private static Font load(FontData data) {
        // FontData owns the immutable program; the JDK only needs a private read cursor.
        ByteBuffer bytes = data.readOnlyBuffer();
        try (var input = new InputStream() {
            @Override public int read() {
                return bytes.hasRemaining() ? Byte.toUnsignedInt(bytes.get()) : -1;
            }

            @Override public int read(byte[] target, int offset, int length) {
                Objects.checkFromIndexSize(offset, length, target.length);
                if (length == 0) { return 0; }
                if (!bytes.hasRemaining()) { return -1; }
                int count = Math.min(length, bytes.remaining());
                bytes.get(target, offset, count);
                return count;
            }
        }) {
            return Font.createFont(Font.TRUETYPE_FONT, input);
        } catch (IOException | FontFormatException failure) {
            throw new IllegalArgumentException("explicit physical font cannot be loaded", failure);
        }
    }
}
