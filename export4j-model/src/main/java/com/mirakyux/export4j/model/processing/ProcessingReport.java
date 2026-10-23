// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model.processing;

import java.util.List;
import java.util.Objects;

/** Immutable stage report. IGNORE entries remain in issues/losses, but are absent from warnings.
 * PREVIEW describes a prepared candidate, not successful output I/O.
 */
public record ProcessingReport(Phase phase, List<ProcessingIssue> issues) {
    public enum Phase { PREVIEW, ACTUAL }
    public ProcessingReport { Objects.requireNonNull(phase, "phase"); issues = List.copyOf(issues); }
    public List<ProcessingIssue> warnings() { return issues.stream().filter(ProcessingIssue::warning).toList(); }
    public List<ProcessingIssue> losses() { return issues.stream().filter(ProcessingIssue::lossy).toList(); }
    public List<ProcessingIssue> skipped() { return issues.stream().filter(issue -> switch (issue.treatment()) {
        case SKIP_MATCH, SKIP_BLOCK, SKIP_REQUEST -> true;
        default -> false;
    }).toList(); }
}
