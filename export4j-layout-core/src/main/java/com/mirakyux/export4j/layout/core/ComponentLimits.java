// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

/** Positive composition budgets; source body limits and backend output limits remain independent. */
public record ComponentLimits(int maxPlacements, int maxCommands, int maxInteractions) {
    public static final ComponentLimits DEFAULT = new ComponentLimits(10_000, 1_000_000, 10_000);

    public ComponentLimits {
        if (maxPlacements <= 0 || maxCommands <= 0 || maxInteractions <= 0) {
            throw new IllegalArgumentException("component budgets must be positive");
        }
    }

    /** Returns a mutable builder initialized with {@link #DEFAULT}. */
    public static Builder builder() {
        return new Builder(DEFAULT);
    }

    /** Returns an independent mutable builder initialized with these limits. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Named configuration of positive budgets; {@link #build()} creates an immutable snapshot. */
    public static final class Builder {
        private int maxPlacements;
        private int maxCommands;
        private int maxInteractions;

        private Builder(ComponentLimits limits) {
            maxPlacements = limits.maxPlacements();
            maxCommands = limits.maxCommands();
            maxInteractions = limits.maxInteractions();
        }

        public Builder maxPlacements(int value) {
            maxPlacements = value;
            return this;
        }

        public Builder maxCommands(int value) {
            maxCommands = value;
            return this;
        }

        public Builder maxInteractions(int value) {
            maxInteractions = value;
            return this;
        }

        /** @throws IllegalArgumentException if any budget is not positive */
        public ComponentLimits build() {
            return new ComponentLimits(maxPlacements, maxCommands, maxInteractions);
        }
    }
}
