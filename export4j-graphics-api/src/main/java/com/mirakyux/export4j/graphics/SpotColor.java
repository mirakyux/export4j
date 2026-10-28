// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

import java.util.Objects;

/** Named single ink, normalized tint and explicit alternate color at full tint. Zero tint is white.
 * The alternate may be RGB, CMYK or ICC; recursive spot alternates are invalid. */
public record SpotColor(String name, double tint, ColorValue alternate) implements ColorValue {
    public SpotColor {
        Objects.requireNonNull(name, "name"); Objects.requireNonNull(alternate, "alternate");
        if (name.isBlank() || name.length() > 128 || name.codePoints().anyMatch(Character::isISOControl)
                || name.equals("All") || name.equals("None") || alternate instanceof SpotColor
                || !Double.isFinite(tint) || tint < 0 || tint > 1) { throw new IllegalArgumentException("invalid named spot color"); }
    }
}
