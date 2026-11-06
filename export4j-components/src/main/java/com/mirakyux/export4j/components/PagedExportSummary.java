// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

/** Completed synchronous replay export; page counts include generated contents and note continuations.
 * Output bytes count successful writes to the borrowed destination. No page IR or drawing lists are retained.
 */
public record PagedExportSummary(int pages, int bodyPages, int contentsPages, long diagnostics, long outputBytes) {
    public PagedExportSummary {
        if (pages <= 0 || bodyPages <= 0 || contentsPages < 0 || (long) bodyPages + contentsPages != pages
                || diagnostics < 0 || outputBytes < 0) {
            throw new IllegalArgumentException("invalid completed page export summary");
        }
    }
}
