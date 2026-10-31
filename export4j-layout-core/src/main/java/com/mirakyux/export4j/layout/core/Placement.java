// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.Objects;

/** Immutable positioned component. List order determines paint order within a layer.
 * avoidBody opts into full-width vertical exclusion; its measured geometry must be stable when final fields resolve.
 */
public record Placement(Component component, Position position, int page, boolean avoidBody) {
    /** Repeating placement; retained for callers using the original constructor. */
    public Placement(Component component, Position position) { this(component, position, 0, false); }
    /** Explicit physical page without reserving body space. */
    public Placement(Component component, Position position, int page) { this(component, position, page, false); }
    public Placement {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(position, "position");
        if (page < 0) { throw new IllegalArgumentException("negative placement page"); }
    }
}
