// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import java.io.IOException;
import java.util.Objects;

/** Stable adapter failure without source text, credentials or paths in its message.
 * Input and output indices are zero based; -1 means the operation has not reached that item.
 * The retained cause is intended for trusted diagnostics and may contain PDFBox details.
 */
public final class PdfOperationException extends IOException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        INPUT_LIMIT, INPUT_IO, OUTPUT_IO, PAGE_LIMIT, OBJECT_LIMIT, DEPTH_LIMIT, RESOURCE_LIMIT,
        DECODED_STREAM_LIMIT, TEXT_LIMIT, OPERATOR_LIMIT, IMAGE_LIMIT, OUTPUT_LIMIT,
        INVALID_PDF, PASSWORD, PERMISSION_DENIED, UNSUPPORTED_FEATURE,
        ENCRYPTED_COPY_REJECTED, INVALID_SELECTION
    }

    private final Code code;
    private final int inputIndex;
    private final int pageIndex;

    PdfOperationException(Code code, int inputIndex, int pageIndex) {
        this(code, inputIndex, pageIndex, null);
    }

    PdfOperationException(Code code, int inputIndex, int pageIndex, Throwable cause) {
        super("PDF operation failed: " + Objects.requireNonNull(code, "code")
                + " at input " + inputIndex + ", page " + pageIndex, cause);
        this.code = code;
        this.inputIndex = inputIndex;
        this.pageIndex = pageIndex;
    }

    public Code code() { return code; }
    public int inputIndex() { return inputIndex; }
    public int pageIndex() { return pageIndex; }
}
