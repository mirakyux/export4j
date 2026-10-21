// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import java.util.List;

/** Immutable line with point-based local offsets. Source range includes consumed wrap spaces. */
public record TextLine(int sourceStart, int sourceEnd, double offsetX, double baselineY,
        double width, List<GlyphRun> runs, List<TextOutline> outlines, List<ImageBox> images, List<FieldBox> fields) {
    public TextLine {
        runs = List.copyOf(runs);
        outlines = List.copyOf(outlines);
        images = List.copyOf(images);
        fields = List.copyOf(fields);
        for (var field : fields) {
            if (field.sourceIndex() < sourceStart || field.sourceIndex() >= sourceEnd || field.outlineIndex() >= outlines.size()) {
                throw new IllegalArgumentException("field box does not belong to this line");
            }
        }
        if (!runs.isEmpty() && !outlines.isEmpty()) { throw new IllegalArgumentException("line has two ink representations"); }
        if (sourceStart < 0 || sourceEnd < sourceStart || !Double.isFinite(offsetX) || offsetX < 0
                || !Double.isFinite(baselineY) || baselineY < 0 || !Double.isFinite(width) || width < 0) {
            throw new IllegalArgumentException("invalid line source range or geometry");
        }
    }

    public TextLine(int sourceStart, int sourceEnd, double offsetX, double baselineY, double width, List<GlyphRun> runs) {
        this(sourceStart, sourceEnd, offsetX, baselineY, width, runs, List.of());
    }

    public TextLine(int sourceStart, int sourceEnd, double offsetX, double baselineY, double width, List<GlyphRun> runs, List<TextOutline> outlines) {
        this(sourceStart, sourceEnd, offsetX, baselineY, width, runs, outlines, List.of());
    }

    public TextLine(int sourceStart, int sourceEnd, double offsetX, double baselineY, double width,
            List<GlyphRun> runs, List<TextOutline> outlines, List<ImageBox> images) {
        this(sourceStart, sourceEnd, offsetX, baselineY, width, runs, outlines, images, List.of());
    }

    /** Preserves precomputed ink when pagination moves a complete line. */
    public TextLine atBaseline(double baseline) {
        return new TextLine(sourceStart, sourceEnd, offsetX, baseline, width, runs, outlines, images, fields);
    }
}
