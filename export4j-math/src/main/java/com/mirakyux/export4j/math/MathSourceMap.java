// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Maps the internal Markdown import stage back to the caller's original UTF-16 input.
 * Unchanged text keeps its exact range. A range within a substituted formula or escaped
 * fallback points to that complete original occurrence, rather than an internal resource key.
 */
final class MathSourceMap {
    /** Ordered, nonempty half-open ranges. Gaps between replacements are unchanged text. */
    record Replacement(int originalStart, int originalEnd, int rewrittenStart, int rewrittenEnd) {
        Replacement {
            if (originalStart < 0 || originalEnd <= originalStart
                    || rewrittenStart < 0 || rewrittenEnd <= rewrittenStart) {
                throw new IllegalArgumentException("invalid math source replacement");
            }
        }
    }

    private MathSourceMap() { }

    static ProcessingReport map(ProcessingReport report, int originalLength, int rewrittenLength,
            List<Replacement> replacements) {
        Objects.requireNonNull(report, "report");
        var snapshot = List.copyOf(replacements);
        validate(originalLength, rewrittenLength, snapshot);
        var issues = new ArrayList<ProcessingIssue>(report.issues().size());
        for (var issue : report.issues()) {
            ExecutionContext.checkpoint();
            var location = issue.location();
            if (location.startUtf16() < 0) { issues.add(issue); continue; }
            if (location.endUtf16() > rewrittenLength) {
                throw new IllegalArgumentException("diagnostic range exceeds rewritten Markdown");
            }
            boolean point = location.startUtf16() == location.endUtf16();
            int start = position(location.startUtf16(), false, point, snapshot);
            int end = position(location.endUtf16(), true, point, snapshot);
            issues.add(new ProcessingIssue(issue.stage(), issue.sourceFormat(), issue.targetFormat(),
                    issue.feature(), issue.operation(), location.range(start, end), issue.reason(),
                    issue.action(), issue.treatment(), issue.effects()));
        }
        return new ProcessingReport(report.phase(), issues);
    }

    private static int position(int rewritten, boolean end, boolean point, List<Replacement> replacements) {
        int low = 0, high = replacements.size() - 1, preceding = -1;
        while (low <= high) {
            ExecutionContext.checkpoint();
            int middle = (low + high) >>> 1;
            if (replacements.get(middle).rewrittenStart() <= rewritten) { preceding = middle; low = middle + 1; }
            else { high = middle - 1; }
        }
        if (preceding < 0) { return rewritten; }
        var replacement = replacements.get(preceding);
        if (rewritten < replacement.rewrittenEnd()) {
            // A point inside generated text has no meaningful finer original location.
            return end && (rewritten > replacement.rewrittenStart() || point)
                    ? replacement.originalEnd() : replacement.originalStart();
        }
        return rewritten + replacement.originalEnd() - replacement.rewrittenEnd();
    }

    private static void validate(int originalLength, int rewrittenLength, List<Replacement> replacements) {
        if (originalLength < 0 || rewrittenLength < 0) { throw new IllegalArgumentException("negative source length"); }
        int originalEnd = 0, rewrittenEnd = 0;
        for (var replacement : replacements) {
            ExecutionContext.checkpoint();
            if (replacement.originalStart() < originalEnd || replacement.rewrittenStart() < rewrittenEnd
                    || replacement.originalStart() - originalEnd != replacement.rewrittenStart() - rewrittenEnd
                    || replacement.originalEnd() > originalLength || replacement.rewrittenEnd() > rewrittenLength) {
                throw new IllegalArgumentException("inconsistent math source replacements");
            }
            originalEnd = replacement.originalEnd(); rewrittenEnd = replacement.rewrittenEnd();
        }
        if (originalLength - originalEnd != rewrittenLength - rewrittenEnd) {
            throw new IllegalArgumentException("inconsistent unchanged source suffix");
        }
    }
}
