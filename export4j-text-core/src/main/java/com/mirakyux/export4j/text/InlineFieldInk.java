// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.font.FontGroup;

/** Shared final field ink, without changing reserved source positions, width or pagination. */
public final class InlineFieldInk {
    private InlineFieldInk() { }

    public record Resolved(TextOutline outline, List<TextDiagnostic> diagnostics) {
        public Resolved { Objects.requireNonNull(outline, "outline"); diagnostics = List.copyOf(diagnostics); }
    }

    /** Resolves one field at its line-local x offset, in points. The caller supplies the actual font
     * group for the field's role and validates value-generation budgets. Diagnostics use value-local
     * UTF-16; the caller must retain the original field source index as a separate context.
     * Value must fit one reserved line; fonts remain borrowed. No I/O or implicit font lookup occurs.
     */
    public static Resolved resolve(FieldBox box, String value, double lineHeight, FontGroup fonts, ParagraphLayouter layouter) {
        Objects.requireNonNull(box, "box"); Objects.requireNonNull(value, "value");
        Objects.requireNonNull(fonts, "fonts"); Objects.requireNonNull(layouter, "layouter");
        var field = box.field();
        var style = field.style();
        var resolved = layouter.layoutOpenType(List.of(new StyledSpan(value, fonts, style.fontSize(), style.rgb(),
                style.baselineShift(), style.underline(), style.strikeThrough())), field.width(),
                new ParagraphStyle(style.fontSize(), lineHeight, 0, 0, TextAlignment.LEFT));
        if (resolved.lines().size() != 1 || resolved.lines().getFirst().width() > Math.nextUp(field.width())) {
            throw new IllegalArgumentException("inline field value exceeds reserved width");
        }
        var paths = new ArrayList<TextOutline.Segment>();
        for (var outline : resolved.lines().getFirst().outlines()) {
            for (var segment : outline.segments()) {
                var coordinates = new ArrayList<>(segment.coordinates());
                for (int index = 0; index < coordinates.size(); index += 2) { coordinates.set(index, coordinates.get(index) + box.x()); }
                paths.add(new TextOutline.Segment(segment.operation(), coordinates));
            }
        }
        return new Resolved(new TextOutline(value, paths, style.rgb()), resolved.diagnostics());
    }
}
