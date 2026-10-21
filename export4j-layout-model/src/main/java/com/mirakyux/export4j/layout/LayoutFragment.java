// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout;

import com.mirakyux.export4j.graphics.Point;

/** Ordered immutable page content with a page-local origin and zero-based semantic block index. */
public sealed interface LayoutFragment permits ParagraphFragment, TableFragment, ComponentFragment {
    String blockId();
    int blockIndex();
    Point origin();
}
