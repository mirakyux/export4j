// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import java.util.Objects;

/** Explicit rich field text. Fonts are immutable caller resources; sizes use native PDF user units. */
public final class PdfFieldText {
    private PdfFieldText() { }
    public record Run(String text, PdfFont font, double size, int rgb, boolean underline) {
        public Run {
            Objects.requireNonNull(text, "text"); Objects.requireNonNull(font, "font");
            if (!Double.isFinite(size) || size <= 0 || size > 10_000 || (rgb & 0xff000000) != 0) {
                throw new IllegalArgumentException("invalid field run appearance");
            }
        }
    }
}
