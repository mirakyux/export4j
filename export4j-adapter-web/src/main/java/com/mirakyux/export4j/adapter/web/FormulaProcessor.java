// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.InlineVector;
import com.mirakyux.export4j.model.InlineStyle;
import java.util.Objects;

/** Explicit, synchronous formula renderer. Implementations must check the remaining positive path
 * segment budget before allocating paths, propagate cancellation, and fail rather than drop input.
 * Size is in points and color is 24-bit RGB. The returned immutable vector owns no open resources.
 */
@FunctionalInterface
public interface FormulaProcessor {
    InlineVector render(String tex, boolean display, double fontSize, int rgb, int maxSegments);

    /** Renders with the complete, resolved host text appearance. A processor that also creates text
     * representations may retain this immutable style for that occurrence. The default delegates to
     * the original size/color contract, preserving existing implementations and five-argument lambdas.
     * The style is non-null; point units, segment budgets and synchronous cancellation are unchanged.
     */
    default InlineVector render(String tex, boolean display, InlineStyle style, int maxSegments) {
        Objects.requireNonNull(style, "style");
        return render(tex, display, style.fontSize(), style.rgb(), maxSegments);
    }
}
