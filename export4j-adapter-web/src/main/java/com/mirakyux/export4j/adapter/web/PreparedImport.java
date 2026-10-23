// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.IOException;

/** Exact input/configuration/candidate snapshot. Preview does not imply successful later export I/O.
 * Thread-safe subject to caller execution callbacks; owns no external resources.
 */
public final class PreparedImport {
    private final ImportResult result;
    private final ExecutionOptions execution;
    PreparedImport(ImportResult result, ExecutionOptions execution) { this.result = result; this.execution = execution; }
    public ProcessingReport preview() { return new ProcessingReport(ProcessingReport.Phase.PREVIEW, result.report().issues()); }
    public ImportResult execute() throws IOException {
        return ExecutionContext.execute(execution, () -> { ExecutionContext.checkpoint(); return result; });
    }
}
