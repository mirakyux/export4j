// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import java.util.List;

/** Immutable line with point-based local offsets. Source range includes consumed wrap spaces.
 * Nonempty glyphRunBoxes select directly mapped ink at explicit positions; outlines then
 * retain logical source and fallback geometry only and must not also be painted.
 */
public record TextLine(int sourceStart, int sourceEnd, double offsetX, double baselineY,
        double width, List<GlyphRun> runs, List<TextOutline> outlines, List<ImageBox> images, List<FieldBox> fields,
        List<GlyphRunBox> glyphRunBoxes) {
    public TextLine {
        runs = List.copyOf(runs);
        outlines = List.copyOf(outlines);
        images = List.copyOf(images);
        fields = List.copyOf(fields);
        glyphRunBoxes = List.copyOf(glyphRunBoxes);
        for (var field : fields) {
            if (field.sourceIndex() < sourceStart || field.sourceIndex() >= sourceEnd || field.outlineIndex() >= outlines.size()) {
                throw new IllegalArgumentException("field box does not belong to this line");
            }
        }
        if (!runs.isEmpty() && !outlines.isEmpty()) { throw new IllegalArgumentException("line has two ink representations"); }
        if (!glyphRunBoxes.isEmpty() && (!runs.isEmpty() || !fields.isEmpty())) {
            throw new IllegalArgumentException("positioned text cannot share ordinary runs or unresolved fields");
        }
        int previousEnd = sourceStart;
        for (var box : glyphRunBoxes) {
            if (box.sourceStart() < previousEnd || box.sourceEnd() > sourceEnd) {
                throw new IllegalArgumentException("positioned text does not belong to this line");
            }
            previousEnd = box.sourceEnd();
        }
        if (sourceStart < 0 || sourceEnd < sourceStart || !Double.isFinite(offsetX) || offsetX < 0
                || !Double.isFinite(baselineY) || baselineY < 0 || !Double.isFinite(width) || width < 0) {
            throw new IllegalArgumentException("invalid line source range or geometry");
        }
    }

    /** Retains the original constructor for callers that supply ordinary runs or outlines. */
    public TextLine(int sourceStart, int sourceEnd, double offsetX, double baselineY, double width,
            List<GlyphRun> runs, List<TextOutline> outlines, List<ImageBox> images, List<FieldBox> fields) {
        this(sourceStart, sourceEnd, offsetX, baselineY, width, runs, outlines, images, fields, List.of());
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
        return new TextLine(sourceStart, sourceEnd, offsetX, baseline, width, runs, outlines, images, fields, glyphRunBoxes);
    }
}
