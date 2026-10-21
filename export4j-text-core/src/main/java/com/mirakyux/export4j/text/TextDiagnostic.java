// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import java.util.Objects;

/** Structured fallback/replacement notification; source indexes are UTF-16 offsets, end exclusive.
 * Code points may be -1 when unavailable through the compatibility constructor.
 */
public record TextDiagnostic(Code code, int sourceStart, int sourceEnd, String fontId, int sourceCodePoint, int replacementCodePoint) {
    public enum Code {
        FONT_FALLBACK,
        MISSING_GLYPH_REPLACED
    }

    public enum Severity { INFO, WARNING }

    public Severity severity() {
        return code == Code.MISSING_GLYPH_REPLACED ? Severity.WARNING : Severity.INFO;
    }

    public TextDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(fontId, "fontId");
        if (sourceStart < 0 || sourceEnd <= sourceStart) {
            throw new IllegalArgumentException("invalid diagnostic source range");
        }
        if (!scalarOrUnknown(sourceCodePoint) || !scalarOrUnknown(replacementCodePoint)
                || sourceCodePoint != -1 && sourceEnd - sourceStart != Character.charCount(sourceCodePoint)
                || replacementCodePoint != -1 && code != Code.MISSING_GLYPH_REPLACED) {
            throw new IllegalArgumentException("invalid diagnostic code points");
        }
    }

    public TextDiagnostic(Code code, int sourceStart, int sourceEnd, String fontId) {
        this(code, sourceStart, sourceEnd, fontId, -1, -1);
    }

    private static boolean scalarOrUnknown(int value) {
        return value == -1 || Character.isValidCodePoint(value) && (value < 0xd800 || value > 0xdfff);
    }
}
