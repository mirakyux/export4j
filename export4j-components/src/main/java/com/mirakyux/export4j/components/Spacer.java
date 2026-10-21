// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.List;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;

/** Immutable full-width blank space in points. Owns no resources; safe to reuse concurrently. */
public record Spacer(double height) implements Component {
    public Spacer {
        if (!Double.isFinite(height) || height <= 0) { throw new IllegalArgumentException("spacer height must be positive"); }
    }

    @Override
    public ComponentLayout layout(ComponentContext context) {
        ComponentSupport.fits(context.width(), height, context);
        return new ComponentLayout(context.width(), height, 0, List.of(), List.of());
    }
}
