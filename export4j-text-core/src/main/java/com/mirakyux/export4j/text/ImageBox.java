// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import java.util.Objects;
import com.mirakyux.export4j.model.InlineImage;

/** Premeasured image at an original UTF-16 marker. x is relative to the aligned line origin, in points. */
public record ImageBox(int sourceIndex, InlineImage image, double x) {
    public ImageBox {
        Objects.requireNonNull(image, "image");
        if (sourceIndex < 0 || !Double.isFinite(x) || x < 0) { throw new IllegalArgumentException("invalid inline image placement"); }
    }
}
