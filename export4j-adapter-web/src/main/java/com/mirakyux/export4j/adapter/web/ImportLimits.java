// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

/** Positive offline import budgets. Text/line lengths use UTF-16; table slots include covered positions.
 * Parsing and mapping are synchronous and cooperative, not a hard time or memory sandbox.
 */
public record ImportLimits(int maxInputUtf16, int maxLineUtf16, int maxNodes, int maxDepth,
        int maxTextUtf16, int maxTableSlots, int maxIssues, long maxWork) {
    public static final ImportLimits DEFAULT = new ImportLimits(1_000_000, 16_384, 100_000, 64,
            2_000_000, 100_000, 10_000, 20_000_000);
    public ImportLimits {
        if (maxInputUtf16 <= 0 || maxLineUtf16 <= 0 || maxNodes <= 0 || maxDepth <= 0 || maxDepth > 256
                || maxTextUtf16 <= 0 || maxTableSlots <= 0 || maxIssues <= 0 || maxWork <= 0) {
            throw new IllegalArgumentException("invalid import limits");
        }
    }
}
