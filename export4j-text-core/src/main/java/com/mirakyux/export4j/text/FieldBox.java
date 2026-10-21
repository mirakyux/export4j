// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import java.util.Objects;
import com.mirakyux.export4j.model.InlineField;

/** Unresolved atomic field at a line-local x offset in points; outlineIndex identifies its logical ink slot. */
public record FieldBox(int sourceIndex, InlineField field, double x, int outlineIndex) {
    public FieldBox {
        Objects.requireNonNull(field, "field");
        if (sourceIndex < 0 || !Double.isFinite(x) || x < 0 || outlineIndex < 0) {
            throw new IllegalArgumentException("invalid inline field box");
        }
    }
}
