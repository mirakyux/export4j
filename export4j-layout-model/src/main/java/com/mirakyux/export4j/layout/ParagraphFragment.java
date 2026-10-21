// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout;

import java.util.Objects;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.text.ParagraphLayout;

/**
 * Immutable contiguous paragraph lines on one page. Origin is a page-local top-left point.
 * paragraphIndex is the zero-based document block index; firstLineIndex is paragraph-local.
 * Text ranges remain relative to the original paragraph.
 * Line baselines are relative to this fragment; diagnostics belong only to its source range.
 */
public record ParagraphFragment(String paragraphId, int paragraphIndex, int firstLineIndex,
        Point origin, ParagraphLayout layout) implements LayoutFragment {
    public ParagraphFragment {
        Objects.requireNonNull(paragraphId, "paragraphId");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(layout, "layout");
        if (paragraphId.isBlank() || paragraphIndex < 0 || firstLineIndex < 0) {
            throw new IllegalArgumentException("invalid paragraph fragment source");
        }
    }

    @Override
    public String blockId() {
        return paragraphId;
    }

    @Override
    public int blockIndex() {
        return paragraphIndex;
    }
}
