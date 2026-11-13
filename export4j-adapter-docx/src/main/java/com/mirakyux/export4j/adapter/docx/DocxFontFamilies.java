// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import java.util.Objects;

/** Explicit Word font-family names for the four script slots. Names declare document presentation;
 * they do not load fonts, discover installed fonts or embed physical font files. Immutable and reusable.
 */
public record DocxFontFamilies(String ascii, String highAnsi, String eastAsia, String complexScript) {
    public DocxFontFamilies {
        ascii = family(ascii); highAnsi = family(highAnsi); eastAsia = family(eastAsia); complexScript = family(complexScript);
    }

    /** Uses the Latin family for ASCII, other Western text and complex-script text. */
    public DocxFontFamilies(String latin, String eastAsia) { this(latin, latin, eastAsia, latin); }

    /** Declares the same family in all four script slots. */
    public static DocxFontFamilies uniform(String family) { return new DocxFontFamilies(family, family, family, family); }

    private static String family(String value) {
        Objects.requireNonNull(value, "font family");
        if (value.isBlank() || value.length() > 256 || value.codePoints().anyMatch(cp -> Character.isISOControl(cp)
                || cp >= 0xd800 && cp <= 0xdfff || cp == 0xfffe || cp == 0xffff)) {
            throw new IllegalArgumentException("invalid DOCX font family");
        }
        return value;
    }
}
