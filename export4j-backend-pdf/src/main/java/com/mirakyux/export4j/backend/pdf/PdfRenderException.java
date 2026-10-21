// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.io.IOException;
import java.util.Objects;

/**
 * PDF failure with a stable code and zero-based page/command indexes.
 * An index of -1 indicates a document-level or page-level failure respectively.
 * Messages deliberately omit document text and resource paths.
 */
public final class PdfRenderException extends IOException {
    private static final long serialVersionUID = 1L;
    private final PdfErrorCode code;
    private final int pageIndex;
    private final int commandIndex;

    PdfRenderException(PdfErrorCode code, int pageIndex, int commandIndex, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.pageIndex = pageIndex;
        this.commandIndex = commandIndex;
    }

    PdfRenderException(PdfErrorCode code, int pageIndex, int commandIndex, String message,
            Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.pageIndex = pageIndex;
        this.commandIndex = commandIndex;
    }

    public PdfErrorCode code() {
        return code;
    }

    public int pageIndex() {
        return pageIndex;
    }

    public int commandIndex() {
        return commandIndex;
    }
}
