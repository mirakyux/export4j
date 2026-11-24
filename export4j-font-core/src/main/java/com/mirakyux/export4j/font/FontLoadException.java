// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font;

import java.io.IOException;

/** Font input failure with a stable code and no input path or text in its message. */
public final class FontLoadException extends IOException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        INVALID_FONT,
        EMBEDDING_RESTRICTED,
        BUDGET_EXCEEDED,
        PARSER_UNAVAILABLE
    }

    private final Code code;

    /** Creates a classified failure with a diagnostic that omits source data and paths. */
    public FontLoadException(Code code, String message) {
        super(message);
        this.code = code;
    }

    /** Creates a classified failure and retains its underlying cause for debugging. */
    public FontLoadException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
