// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math;

import java.util.Objects;

/** Redacted formula failure. Limits and cancellation must never be converted into a fallback. */
public final class MathException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    /** Stable failure categories; no source text is included in the diagnostic. */
    public enum Reason { INVALID_SYNTAX, UNSUPPORTED_SYNTAX, LIMIT_EXCEEDED, RENDERING_FAILED }

    private final Reason reason;

    public MathException(Reason reason) {
        super("Formula processing failed: " + Objects.requireNonNull(reason, "reason"));
        this.reason = reason;
    }

    public MathException(Reason reason, Throwable cause) {
        super("Formula processing failed: " + Objects.requireNonNull(reason, "reason"), cause);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
