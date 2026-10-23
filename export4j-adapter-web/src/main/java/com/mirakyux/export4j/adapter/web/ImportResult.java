// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.util.Objects;

/** Immutable imported semantic document and complete actual loss report, including IGNORE entries. */
public record ImportResult(Document document, ProcessingReport report) {
    public ImportResult { Objects.requireNonNull(document, "document"); Objects.requireNonNull(report, "report"); }
}
