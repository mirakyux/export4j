// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.List;
import com.mirakyux.export4j.text.InlineGraphic;
import com.mirakyux.export4j.text.TextOutline;

/** Project-owned, filled vector marks; no font glyphs or backend types. */
final class FormMarks {
    private FormMarks() { }

    static boolean isMark(char value) { return value == '□' || value == '☑' || value == '○' || value == '●'; }

    static InlineGraphic graphic(char value, double advance, double size, int rgb) {
        var paths = new ArrayList<TextOutline.Segment>();
        double left = (advance - size) / 2;
        double thickness = size * .08;
        if (value == '□' || value == '☑') {
            polygon(paths, left, size, 0, 0, 1, 0, 1, 1, 0, 1);
            polygon(paths, left, size, .08, .08, .08, .92, .92, .92, .92, .08);
            if (value == '☑') { polygon(paths, left, size, .16, .48, .38, .70, .84, .22, .93, .31, .38, .88, .07, .57); }
        } else {
            circle(paths, left + size / 2, -size / 2, size / 2, false);
            circle(paths, left + size / 2, -size / 2, size / 2 - thickness, true);
            if (value == '●') { circle(paths, left + size / 2, -size / 2, size * .26, false); }
        }
        return new InlineGraphic(advance, size, 0, new TextOutline(String.valueOf(value), paths, rgb));
    }

    private static void polygon(List<TextOutline.Segment> paths, double left, double size, double... points) {
        for (int i = 0; i < points.length; i += 2) {
            paths.add(new TextOutline.Segment(i == 0 ? TextOutline.Operation.MOVE : TextOutline.Operation.LINE,
                    List.of(left + points[i] * size, (points[i + 1] - 1) * size)));
        }
        paths.add(new TextOutline.Segment(TextOutline.Operation.CLOSE, List.of()));
    }

    private static void circle(List<TextOutline.Segment> paths, double x, double y, double r, boolean reverse) {
        double k = .5522847498307936;
        double d = reverse ? -r : r;
        paths.add(new TextOutline.Segment(TextOutline.Operation.MOVE, List.of(x + r, y)));
        paths.add(new TextOutline.Segment(TextOutline.Operation.CURVE, List.of(x + r, y + k * d, x + k * r, y + d, x, y + d)));
        paths.add(new TextOutline.Segment(TextOutline.Operation.CURVE, List.of(x - k * r, y + d, x - r, y + k * d, x - r, y)));
        paths.add(new TextOutline.Segment(TextOutline.Operation.CURVE, List.of(x - r, y - k * d, x - k * r, y - d, x, y - d)));
        paths.add(new TextOutline.Segment(TextOutline.Operation.CURVE, List.of(x + k * r, y - d, x + r, y - k * d, x + r, y)));
        paths.add(new TextOutline.Segment(TextOutline.Operation.CLOSE, List.of()));
    }
}
