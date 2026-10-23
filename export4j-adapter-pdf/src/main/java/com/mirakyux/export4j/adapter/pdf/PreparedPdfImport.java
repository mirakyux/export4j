// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Immutable, editable semantic recovery candidate. No input document remains open. */
public final class PreparedPdfImport {
    /** Source boxes are unrotated page coordinates, physical pt, with a top-left origin.
     * Text boxes approximate font metrics; they are not exact glyph outlines or redaction regions.
     */
    public record Source(String blockId, int page, double x, double y, double width, double height) { }
    public record Result(SectionedDocument document, List<Source> sources, ProcessingReport report) {
        public Result {
            Objects.requireNonNull(document, "document"); sources = List.copyOf(sources); Objects.requireNonNull(report, "report");
            if (report.phase() != ProcessingReport.Phase.ACTUAL) { throw new IllegalArgumentException("PDF import result requires an actual report"); }
        }
    }
    private final Result result;
    private final ExecutionOptions execution;
    PreparedPdfImport(Result result, ExecutionOptions execution) { this.result = result; this.execution = execution; }
    public SectionedDocument document() { return result.document(); }
    public List<Source> sources() { return result.sources(); }
    public ProcessingReport preview() { return new ProcessingReport(ProcessingReport.Phase.PREVIEW, result.report().issues()); }
    public Result execute() throws IOException { return ExecutionContext.execute(execution, () -> { ExecutionContext.checkpoint(); return result; }); }
}
