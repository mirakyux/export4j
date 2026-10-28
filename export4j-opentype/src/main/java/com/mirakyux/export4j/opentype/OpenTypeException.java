// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.opentype;

/** Stable native-font failure, without document text or resource paths. */
public final class OpenTypeException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    public enum Code { NATIVE_UNAVAILABLE, INVALID_FONT, EMBEDDING_RESTRICTED, INVALID_AXIS, MISSING_GLYPH,
        INVALID_TEXT, BUDGET_EXCEEDED, UNSUPPORTED_PAINT, DOES_NOT_FIT }
    private final Code code;
    private final int sourceIndex;
    public OpenTypeException(Code code, int sourceIndex, String message) {
        super(message);
        this.code = java.util.Objects.requireNonNull(code, "code");
        this.sourceIndex = sourceIndex;
    }
    OpenTypeException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        sourceIndex = -1;
    }
    public Code code() { return code; }
    /** Original UTF-16 offset, or -1 for a font/runtime failure. */
    public int sourceIndex() { return sourceIndex; }
}
