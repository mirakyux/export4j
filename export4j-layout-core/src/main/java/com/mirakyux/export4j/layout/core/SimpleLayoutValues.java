// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.LinkedHashMap;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.text.ParagraphLayout;

/** Shares completed, immutable glyph-only layout values within one flow. Never skips measurement.
 * Equality includes all geometry, source ranges, font identities, glyph advances and diagnostics.
 * Eviction only drops cache references; emitted IR retains its own immutable references.
 */
final class SimpleLayoutValues {
    private static final int MAX_ENTRIES = 1024;
    private static final int MAX_UNITS = 65_536;
    private final LinkedHashMap<ParagraphLayout, Entry> values = new LinkedHashMap<>(16, 0.75f, true);
    private int retainedUnits;

    private record Entry(ParagraphLayout layout, int units) { }

    ParagraphLayout reuse(ParagraphLayout measured) {
        ExecutionContext.checkpoint();
        // Diagnostics also consume retention units, in addition to lines, runs and glyphs.
        long units = measured.diagnostics().size();
        for (var line : measured.lines()) {
            if (!line.outlines().isEmpty() || !line.images().isEmpty() || !line.fields().isEmpty()) { return measured; }
            units++;
            for (var run : line.runs()) {
                units += 1L + run.glyphs().size();
                if (units > MAX_UNITS) { return measured; }
            }
            if (units > MAX_UNITS) { return measured; }
        }
        var existing = values.get(measured);
        if (existing != null) { return existing.layout(); }
        while (values.size() >= MAX_ENTRIES || retainedUnits > MAX_UNITS - units) {
            retainedUnits -= values.pollFirstEntry().getValue().units();
        }
        int weight = (int) units;
        values.put(measured, new Entry(measured, weight));
        retainedUnits += weight;
        return measured;
    }
}
