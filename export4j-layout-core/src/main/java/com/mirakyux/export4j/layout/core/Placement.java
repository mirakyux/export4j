// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.Objects;

/** Immutable positioned component. List order determines paint order within a layer. */
public record Placement(Component component, Position position) {
    public Placement {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(position, "position");
    }
}
