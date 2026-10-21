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
}
