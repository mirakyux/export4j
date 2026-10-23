// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model.edit;

/** Positive per-request editing limits. Work counts visited nodes, UTF-16 units and emitted replacements,
 * including repeated operation passes. Limits are cooperative, not an RSS limit or a hostile-code sandbox.
 */
public record EditLimits(int maxOperations, int maxNodes, int maxTextUtf16, int maxReplacements,
        int maxIssues, long maxWork) {
    public static final EditLimits DEFAULT = new EditLimits(1_000, 100_000, 4_000_000, 100_000, 10_000, 40_000_000);
    public EditLimits {
        if (maxOperations <= 0 || maxNodes <= 0 || maxTextUtf16 <= 0 || maxReplacements <= 0
                || maxIssues <= 0 || maxWork <= 0) { throw new IllegalArgumentException("editing limits must be positive"); }
    }
}
