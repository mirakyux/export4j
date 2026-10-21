// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import java.io.IOException;
import java.util.Objects;

/** Structured export failure; UNSUPPORTED retains its feature and source location in diagnostic(). */
public final class ExportException extends IOException {
    private static final long serialVersionUID = 1L;
    public enum Code { UNSUPPORTED, LIMIT_EXCEEDED, INVALID_TEXT, INVALID_NUMBERING }
    private final Code code;
    private final ExportDiagnostic diagnostic;
    ExportException(Code code, ExportDiagnostic diagnostic) {
        super(code + ": " + diagnostic.message());
        this.code = Objects.requireNonNull(code, "code"); this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }
    public Code code() { return code; }
    public ExportDiagnostic diagnostic() { return diagnostic; }
}
