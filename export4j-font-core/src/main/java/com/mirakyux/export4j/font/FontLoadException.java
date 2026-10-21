// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font;

import java.io.IOException;

/** Font input failure with a stable code and no input path or text in its message. */
public final class FontLoadException extends IOException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        INVALID_FONT,
        EMBEDDING_RESTRICTED,
        BUDGET_EXCEEDED
    }

    private final Code code;

    FontLoadException(Code code, String message) {
        super(message);
        this.code = code;
    }

    FontLoadException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
