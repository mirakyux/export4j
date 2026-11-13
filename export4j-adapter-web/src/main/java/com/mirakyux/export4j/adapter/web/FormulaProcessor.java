// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.InlineVector;

/** Explicit, synchronous formula renderer. Implementations must check the remaining positive path
 * segment budget before allocating paths, propagate cancellation, and fail rather than drop input.
 * Size is in points and color is 24-bit RGB. The returned immutable vector owns no open resources.
 */
@FunctionalInterface
public interface FormulaProcessor {
    InlineVector render(String tex, boolean display, double fontSize, int rgb, int maxSegments);
}
