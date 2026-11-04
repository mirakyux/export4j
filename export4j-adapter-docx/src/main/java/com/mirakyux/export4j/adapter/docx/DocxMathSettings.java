// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import java.util.Objects;

/** Native document-wide mathematical settings. Integer spacing values are twips (1/20 pt).
 * The named font is a native Word font choice; this adapter neither discovers nor renders it. */
public record DocxMathSettings(String fontFamily, BinaryBreak binaryBreak, BinarySubtraction binarySubtraction,
        boolean smallFractions, boolean displayDefault, int leftMargin, int rightMargin,
        Justification defaultJustification, int preSpacing, int postSpacing, int interSpacing, int intraSpacing,
        int wrapIndent, boolean wrapRight, DocxMath.LimitLocation integralLimits, DocxMath.LimitLocation naryLimits) {
    public enum BinaryBreak { BEFORE, AFTER, REPEAT }
    public enum BinarySubtraction { MINUS_MINUS, PLUS_MINUS, MINUS_PLUS }
    public enum Justification { LEFT, CENTER, RIGHT, CENTER_GROUP }
    public static final DocxMathSettings DEFAULT = new DocxMathSettings("Cambria Math", BinaryBreak.BEFORE,
            BinarySubtraction.MINUS_MINUS, false, true, 0, 0, Justification.CENTER_GROUP,
            0, 0, 0, 0, 1440, false, DocxMath.LimitLocation.SUB_SUP, DocxMath.LimitLocation.UNDER_OVER);
    public DocxMathSettings {
        Objects.requireNonNull(fontFamily, "fontFamily"); Objects.requireNonNull(binaryBreak, "binaryBreak");
        Objects.requireNonNull(binarySubtraction, "binarySubtraction"); Objects.requireNonNull(defaultJustification, "defaultJustification");
        Objects.requireNonNull(integralLimits, "integralLimits"); Objects.requireNonNull(naryLimits, "naryLimits");
        if (fontFamily.isBlank() || fontFamily.length() > 128 || fontFamily.codePoints().anyMatch(cp -> Character.isISOControl(cp)
                || cp >= 0xd800 && cp <= 0xdfff || cp == 0xfffe || cp == 0xffff)) { throw new IllegalArgumentException("invalid math font name"); }
        for (int value : new int[] {leftMargin, rightMargin, preSpacing, postSpacing, interSpacing, intraSpacing, wrapIndent}) {
            if (value < 0 || value > 31_680) { throw new IllegalArgumentException("math spacing exceeds 1584 pt"); }
        }
    }
}
