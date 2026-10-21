// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout;

import java.util.Objects;
import com.mirakyux.export4j.graphics.Point;

/** One indivisible component part with original block identity and page-local origin. A block may have multiple parts. */
public record ComponentFragment(String blockId, int blockIndex, Point origin, ComponentLayout layout)
        implements LayoutFragment {
    public ComponentFragment {
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(layout, "layout");
        if (blockId.isBlank() || blockIndex < 0) {
            throw new IllegalArgumentException("invalid component source");
        }
    }
}
