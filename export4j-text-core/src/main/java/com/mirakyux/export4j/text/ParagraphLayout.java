// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import java.util.List;

/** Immutable horizontal line-layout result. Width and height are the available box and line boxes. */
public record ParagraphLayout(double width, double height, List<TextLine> lines,
        List<TextDiagnostic> diagnostics) {
    public ParagraphLayout {
        lines = List.copyOf(lines);
        diagnostics = List.copyOf(diagnostics);
        if (!Double.isFinite(width) || width <= 0 || !Double.isFinite(height) || height <= 0
                || lines.isEmpty()) {
            throw new IllegalArgumentException("layout must contain finite dimensions and at least one line");
        }
    }
}
