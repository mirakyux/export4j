// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import java.util.Objects;

/** Explicit web role mapping; family is one CSS family name, weight is 100..900.
 * Markdown maps weight >=600 to strong and italic to emphasis; it does not preserve family names.
 * No font files are scanned, fetched or embedded by this value.
 */
public record FontStyle(String family, int weight, boolean italic) {
    public FontStyle {
        Objects.requireNonNull(family, "family");
        if (family.isBlank() || family.length() > 256 || family.codePoints().anyMatch(cp -> Character.isISOControl(cp)
                || cp >= 0xd800 && cp <= 0xdfff) || weight < 100 || weight > 900 || weight % 100 != 0) {
            throw new IllegalArgumentException("invalid web font style");
        }
    }
}
