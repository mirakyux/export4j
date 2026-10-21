// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.Objects;
import com.mirakyux.export4j.model.Block;

/** Semantic flow adapter; PageableComponent supplies ordered fragments, other components stay whole. Spacing in points. */
public record ComponentBlock(String id, Component component, double spaceBefore, double spaceAfter) implements Block {
    /** Component with no additional block spacing, in points. */
    public ComponentBlock(String id, Component component) { this(id, component, 0, 0); }
    public ComponentBlock {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(component, "component");
        if (id.isBlank() || !Double.isFinite(spaceBefore) || spaceBefore < 0
                || !Double.isFinite(spaceAfter) || spaceAfter < 0) {
            throw new IllegalArgumentException("invalid component block");
        }
    }
}
