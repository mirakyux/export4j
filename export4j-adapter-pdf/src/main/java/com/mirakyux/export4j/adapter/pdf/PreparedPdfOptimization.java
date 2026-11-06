// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Objects;

/** Immutable optimization candidate. Smaller output is measured, never guaranteed. */
public final class PreparedPdfOptimization {
    public record Statistics(long inputBytes, long outputBytes, int recompressedStreams, int deduplicatedResources,
            int resampledImages, long resampledPixelsBefore, long resampledPixelsAfter) { }
    public record Result(Statistics statistics, ProcessingReport report) { }
    private final PreparedPdf output;
    private final Statistics statistics;
    PreparedPdfOptimization(PreparedPdf output, Statistics statistics) { this.output = output; this.statistics = statistics; }
    public Statistics statistics() { return statistics; }
    public ProcessingReport preview() { return output.preview(); }
    public Result to(Path target) throws IOException { return new Result(statistics, output.to(target).report()); }

    /** Local file output using the same atomic publication contract as the Path overload. */
    public Result to(String target) throws IOException {
        return to(Path.of(Objects.requireNonNull(target, "target")));
    }

    /** Local file output using the same atomic publication contract as the Path overload. */
    public Result to(File target) throws IOException {
        return to(Objects.requireNonNull(target, "target").toPath());
    }
    public Result to(OutputStream target) throws IOException { return new Result(statistics, output.to(target).report()); }
}
