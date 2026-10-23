// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExternalDestination;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.text.ParagraphLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** Resolves passive external links from final ink without measuring or retaining source content.
 * Coordinates use top-left points; source positions passed to the synchronous sink are UTF-16.
 * The caller owns interaction budgets and may coalesce adjacent rectangles. Inputs are borrowed.
 */
public final class InlineLinkGeometry {
    private record Range(int start, int end, ExternalDestination target) { }
    private InlineLinkGeometry() { }

    /** Emits actual text/image link rectangles, including shaped and bidirectional line ink.
     * The sink must not silently discard interactions. Its exceptions propagate unchanged.
     */
    public static void collect(String text, List<InlineText> inlines, double lineHeight, Point origin,
            ParagraphLayout layout, int pageIndex, double pageWidth,
            BiConsumer<DocumentExtras.ExternalLink, Integer> sink) {
        var targets = new ArrayList<Range>();
        int base = 0;
        for (var inline : inlines) {
            ExecutionContext.checkpoint();
            if (!inline.hyperlink().isEmpty() && !inline.text().isEmpty()) {
                targets.add(new Range(base, base + inline.text().length(), new ExternalDestination(inline.hyperlink())));
            }
            base += inline.text().length();
        }
        for (var line : layout.lines()) {
            ExecutionContext.checkpoint();
            double x = origin.x() + line.offsetX();
            double y = origin.y() + line.baselineY() - layout.lines().getFirst().baselineY();
            for (var image : line.images()) {
                var item = image.image();
                if (!item.hyperlink().isEmpty()) {
                    sink.accept(new DocumentExtras.ExternalLink(pageIndex,
                            new Rect(x + image.x(), origin.y() + line.baselineY() + item.baselineShift() - item.height(),
                                    item.width(), item.height()), new ExternalDestination(item.hyperlink())), image.sourceIndex());
                }
            }
            int source = line.sourceStart();
            for (var run : line.runs()) {
                for (var glyph : run.glyphs()) {
                    ExecutionContext.checkpoint();
                    int end = source + Character.charCount(text.codePointAt(source));
                    for (var range : targets) {
                        if (source < range.end() && end > range.start() && glyph.advance() > 0) {
                            sink.accept(new DocumentExtras.ExternalLink(pageIndex,
                                    new Rect(x, y, glyph.advance(), lineHeight), range.target()), source);
                        }
                    }
                    x += glyph.advance(); source = end;
                }
            }
            for (var outline : line.outlines()) {
                ExecutionContext.checkpoint();
                int end = source + outline.logicalText().length();
                double left = Double.POSITIVE_INFINITY;
                double right = Double.NEGATIVE_INFINITY;
                for (var segment : outline.segments()) {
                    for (int index = 0; index < segment.coordinates().size(); index += 2) {
                        left = Math.min(left, segment.coordinates().get(index));
                        right = Math.max(right, segment.coordinates().get(index));
                    }
                }
                for (var range : targets) {
                    if (source < range.end() && end > range.start() && right > left) {
                        double from = Math.max(0, x + left);
                        double to = Math.min(pageWidth, x + right);
                        if (to > from) {
                            sink.accept(new DocumentExtras.ExternalLink(pageIndex,
                                    new Rect(from, y, to - from, lineHeight), range.target()), source);
                        }
                    }
                }
                source = end;
            }
        }
    }
}
