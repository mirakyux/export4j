// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import org.apache.pdfbox.pdmodel.font.PDFont;

/** Document-confined encoding/width reuse: at most 16 fonts and the first 256 scalars per font.
 * New fonts/scalars beyond those bounds use the uncached path; entries never cross PDF documents.
 */
final class PdfGlyphMappingCache {
    private static final int MAX_FONTS = 16;
    private static final int MAX_SCALARS = 256;
    private static final int SLOTS = 512;
    private final Map<PDFont, FontMappings> fonts = new IdentityHashMap<>();

    FontMappings forFont(PDFont font) {
        var found = fonts.get(font);
        if (found != null) { return found; }
        if (fonts.size() == MAX_FONTS) { return null; }
        var created = new FontMappings();
        fonts.put(font, created);
        return created;
    }

    void clear() { fonts.clear(); }

    static final class FontMappings {
        private final int[] keys = new int[SLOTS];
        private final Entry[] entries = new Entry[SLOTS];
        private int size;

        Entry get(int codePoint) { return entries[slot(codePoint + 1)]; }

        Entry add(int codePoint, int glyphId) {
            if (size == MAX_SCALARS) { return null; }
            int key = codePoint + 1;
            int at = slot(key);
            if (entries[at] != null) { return entries[at]; }
            var created = new Entry(glyphId);
            keys[at] = key;
            entries[at] = created;
            size++;
            return created;
        }

        private int slot(int key) {
            int at = (key * 0x9e3779b9) >>> 23;
            while (keys[at] != 0 && keys[at] != key) { at = (at + 1) & (SLOTS - 1); }
            return at;
        }
    }

    static final class Entry {
        private final int glyphId;
        private boolean widthKnown;
        private float width;

        private Entry(int glyphId) { this.glyphId = glyphId; }

        int glyphId() { return glyphId; }

        float width(PdfUnicodeFont font) throws IOException {
            if (!widthKnown) {
                width = font.widthForGlyph(glyphId);
                widthKnown = true;
            }
            return width;
        }
    }
}
