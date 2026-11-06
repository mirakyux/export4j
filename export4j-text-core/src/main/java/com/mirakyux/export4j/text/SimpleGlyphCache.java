// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import java.util.concurrent.atomic.AtomicReferenceArray;

/** Per-shaper bounded value reuse; collisions replace one slot without retaining source or font data. */
final class SimpleGlyphCache {
    private static final int CAPACITY = 4096;
    private final AtomicReferenceArray<PositionedGlyph> entries = new AtomicReferenceArray<>(CAPACITY);

    PositionedGlyph get(int codePoint, int glyphId, double advance) {
        long advanceBits = Double.doubleToRawLongBits(advance);
        int hash = 31 * (31 * codePoint + glyphId) + Long.hashCode(advanceBits);
        hash ^= hash >>> 16;
        int slot = hash & (CAPACITY - 1);
        PositionedGlyph cached = entries.get(slot);
        if (matches(cached, codePoint, glyphId, advanceBits)) { return cached; }

        PositionedGlyph created = new PositionedGlyph(codePoint, glyphId, advance);
        if (entries.compareAndSet(slot, cached, created)) { return created; }
        PositionedGlyph concurrent = entries.get(slot);
        return matches(concurrent, codePoint, glyphId, advanceBits) ? concurrent : created;
    }

    private static boolean matches(PositionedGlyph glyph, int codePoint, int glyphId, long advanceBits) {
        return glyph != null && glyph.codePoint() == codePoint && glyph.glyphId() == glyphId
                && Double.doubleToRawLongBits(glyph.advance()) == advanceBits;
    }
}
