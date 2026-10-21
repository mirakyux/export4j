// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import java.util.List;

/** Completed UTF-8 output byte count and immutable warnings. No library logging is performed. */
public record ExportResult(long bytesWritten, List<ExportDiagnostic> diagnostics) {
    public ExportResult {
        diagnostics = List.copyOf(diagnostics);
        if (bytesWritten < 0) { throw new IllegalArgumentException("negative output count"); }
    }
}
