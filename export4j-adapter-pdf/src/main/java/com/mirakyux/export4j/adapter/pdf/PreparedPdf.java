// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import org.apache.pdfbox.pdmodel.PDDocument;

/** Immutable exact PDF candidate and preview. Encoding completes before any destination is touched.
 * Retains bounded encoded bytes, not an open PDF document. Safe to publish concurrently to distinct targets.
 */
public final class PreparedPdf {
    public record Result(int pageCount, int changes, long outputBytes, ProcessingReport report) { }
    private final byte[] bytes;
    private final Result result;
    private final ExecutionOptions execution;

    private PreparedPdf(byte[] bytes, Result result, ExecutionOptions execution) {
        this.bytes = bytes; this.result = result; this.execution = execution;
    }
    static PreparedPdf encode(PDDocument document, int changes, List<ProcessingIssue> issues, PdfLimits limits,
            ExecutionOptions execution) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var bounded = new OutputStream() {
            private void reserve(int count) throws IOException {
                ExecutionContext.checkpoint();
                if (count > Math.min(limits.maxOutputBytes(), Integer.MAX_VALUE - 8L) - bytes.size()) {
                    throw new PdfOperationException(PdfOperationException.Code.OUTPUT_LIMIT, 0, -1);
                }
            }
            @Override public void write(int value) throws IOException { reserve(1); bytes.write(value); }
            @Override public void write(byte[] value, int start, int count) throws IOException {
                Objects.checkFromIndexSize(start, count, value.length); reserve(count); bytes.write(value, start, count);
            }
        };
        document.save(bounded);
        ExecutionContext.checkpoint();
        return new PreparedPdf(bytes.toByteArray(), new Result(document.getNumberOfPages(), changes, bytes.size(),
                new ProcessingReport(ProcessingReport.Phase.ACTUAL, issues)), execution);
    }
    public ProcessingReport preview() { return new ProcessingReport(ProcessingReport.Phase.PREVIEW, result.report().issues()); }
    public int pageCount() { return result.pageCount(); }
    public long outputBytes() { return result.outputBytes(); }
    /** Caller owns the destination: neither close nor flush is invoked. Failed writes may be partial. */
    public Result to(OutputStream output) throws IOException {
        Objects.requireNonNull(output, "output");
        return ExecutionContext.execute(execution, () -> { ExecutionContext.output(output).write(bytes); return result; });
    }
    /** Same-directory atomic replacement, with the final cancellation check before publication. */
    public Result to(Path output) throws IOException {
        Path target = Objects.requireNonNull(output, "output").toAbsolutePath();
        Path temporary = Files.createTempFile(target.getParent(), ".export4j-pdf-edit-", ".tmp");
        try {
            return ExecutionContext.execute(execution, () -> {
                try (var stream = Files.newOutputStream(temporary)) { to(stream); }
                ExecutionContext.checkpoint();
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return result;
            });
        } catch (IOException | RuntimeException | Error failure) {
            try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
}
