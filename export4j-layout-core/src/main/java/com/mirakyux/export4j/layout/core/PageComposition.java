// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.layout.ComponentLayout;

/** Immutable display and positioned component traces, retaining text fallback diagnostics. */
public record PageComposition(DisplayDocument display, List<Placed> placements) {
    public PageComposition {
        Objects.requireNonNull(display, "display");
        placements = List.copyOf(placements);
    }

    public enum Region { BACKGROUND, HEADER, FOOTER, FOREGROUND }

    public record Placed(int pageIndex, Region region, int placementIndex, Rect viewport, ComponentLayout layout) {
        public Placed {
            Objects.requireNonNull(region, "region");
            Objects.requireNonNull(viewport, "viewport");
            Objects.requireNonNull(layout, "layout");
            if (pageIndex < 0 || placementIndex < 0) {
                throw new IllegalArgumentException("invalid placement source");
            }
        }
    }
}
