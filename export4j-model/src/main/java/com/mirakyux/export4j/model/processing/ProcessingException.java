// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model.processing;

import java.io.Serial;
import java.util.Objects;

/** Unsupported operation rejected before publishing a result; carries a content-free source location. */
public final class ProcessingException extends IllegalArgumentException {
    @Serial private static final long serialVersionUID = 1L;
    private final ProcessingIssue issue;
    public ProcessingException(ProcessingIssue issue) {
        super("Unsupported feature: " + Objects.requireNonNull(issue, "issue").feature());
        this.issue = issue;
    }
    public ProcessingIssue issue() { return issue; }
}
