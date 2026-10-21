// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

/** Text failure with a stable code and original UTF-16 source offset, never the source text. */
public final class TextLayoutException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        INVALID_TEXT,
        MISSING_GLYPH,
        UNSUPPORTED_SHAPING,
        GLYPH_TOO_WIDE,
        LINE_HEIGHT_TOO_SMALL,
        BUDGET_EXCEEDED
    }

    private final Code code;
    private final int sourceIndex;

    public TextLayoutException(Code code, int sourceIndex, String message) {
        super(message);
        this.code = java.util.Objects.requireNonNull(code, "code");
        this.sourceIndex = sourceIndex;
    }

    public Code code() {
        return code;
    }

    public int sourceIndex() {
        return sourceIndex;
    }
}
