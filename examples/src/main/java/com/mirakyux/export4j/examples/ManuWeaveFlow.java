// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.components.FlowColumn;
import com.mirakyux.export4j.layout.core.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Example-local adjacency policy, using the engine's ordinary atomic component contract. */
final class ManuWeaveFlow {
    private final FlowColumn.Builder flow = FlowColumn.builder();
    private final List<Component> pending = new ArrayList<>();

    void add(Component component) { add(component, false); }

    void add(Component component, boolean keepWithNext) {
        pending.add(Objects.requireNonNull(component, "component"));
        if (!keepWithNext) {
            flow.add(pending.size() == 1 ? pending.getFirst() : atomic(pending, "heading and following content"));
            pending.clear();
        }
    }

    void addAtomic(Component component, String description) {
        add(atomic(List.of(component), description));
    }

    FlowColumn build() {
        if (!pending.isEmpty()) { throw new IllegalStateException("ManuWeave heading has no following content"); }
        return flow.build();
    }

    static Component atomic(List<Component> components, String description) {
        var group = FlowColumn.builder().children(List.copyOf(components)).build();
        return context -> {
            var parts = group.layoutParts(context);
            if (parts.size() != 1) {
                throw new IllegalArgumentException("ManuWeave kept group exceeds a fresh column: " + description);
            }
            return parts.getFirst();
        };
    }
}
