// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import com.mirakyux.export4j.layout.ComponentLayout;

/**
 * Component extension point owned by core. Implementations measure once and return immutable local drawing.
 * No output I/O or backend types. Flow components must be independent of page index/count and fit their viewport.
 * Implementations must declare thread safety; immutable standard components are reusable.
 */
@FunctionalInterface
public interface Component {
    ComponentLayout layout(ComponentContext context);
}
