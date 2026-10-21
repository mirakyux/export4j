// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Per-call cooperative execution controls. Callbacks run on the caller thread; credentials/text are never reported. */
public record ExecutionOptions(Duration timeout, BooleanSupplier cancelled, Consumer<Progress> progress, long maxOutputBytes) {
    public static final ExecutionOptions DEFAULT = new ExecutionOptions(Duration.ZERO, () -> false, p -> { }, Long.MAX_VALUE);

    public ExecutionOptions {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(progress, "progress");
        if (timeout.isNegative() || timeout.compareTo(Duration.ofDays(365)) > 0 || maxOutputBytes <= 0) {
            throw new IllegalArgumentException("invalid execution duration or byte budget");
        }
    }

    /** Stage-local counts, not an overall percentage. total=-1 explicitly denotes an unknown total;
     * completed remains nonnegative. A known total must be at least completed.
     */
    public record Progress(String stage, String blockId, int pageIndex, long completed, long total) implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public Progress {
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(blockId, "blockId");
            if (pageIndex < -1 || completed < 0 || total < -1 || total != -1 && total < completed) {
                throw new IllegalArgumentException("invalid progress");
            }
        }
        public boolean hasKnownTotal() { return total >= 0; }
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().timeout(timeout()).cancelled(cancelled()).progress(progress()).maxOutputBytes(maxOutputBytes()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private Duration timeout = Duration.ZERO;
        private BooleanSupplier cancelled = () -> false;
        private Consumer<Progress> progress = p -> { };
        private long maxOutputBytes = Long.MAX_VALUE;

        private Builder() { }
        public Builder timeout(Duration value) { timeout = java.util.Objects.requireNonNull(value, "timeout"); return this; }
        public Builder cancelled(BooleanSupplier value) { cancelled = java.util.Objects.requireNonNull(value, "cancelled"); return this; }
        public Builder progress(Consumer<Progress> value) { progress = java.util.Objects.requireNonNull(value, "progress"); return this; }
        public Builder maxOutputBytes(long value) { maxOutputBytes = value; return this; }

        public ExecutionOptions build() { return new ExecutionOptions(timeout, cancelled, progress, maxOutputBytes); }
    }
}
