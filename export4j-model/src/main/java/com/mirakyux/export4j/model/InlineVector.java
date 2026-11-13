// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;

/** Atomic vector with explicit reading text, RGB fill and optional external link.
 * Occupies one U+FFFC source unit. Dynamic expressions must replace the entire value;
 * template binding preserves logicalText and geometry together.
 */
public record InlineVector(VectorResource resource, String logicalText, int rgb, String hyperlink) implements InlineContent {
    public InlineVector {
        Objects.requireNonNull(resource, "resource"); Objects.requireNonNull(logicalText, "logicalText");
        Objects.requireNonNull(hyperlink, "hyperlink");
        if (rgb < 0 || rgb > 0xffffff || logicalText.length() > 1024
                || logicalText.codePoints().anyMatch(cp -> Character.isISOControl(cp)
                || cp >= 0xd800 && cp <= 0xdfff || cp == 0xfffe || cp == 0xffff
                || Character.getType(cp) == Character.LINE_SEPARATOR || Character.getType(cp) == Character.PARAGRAPH_SEPARATOR)) {
            throw new IllegalArgumentException("invalid inline vector reading text or color");
        }
        if (!hyperlink.isEmpty()) { new ExternalDestination(hyperlink); }
    }
    public InlineVector(VectorResource resource, String logicalText) { this(resource, logicalText, 0, ""); }
}
