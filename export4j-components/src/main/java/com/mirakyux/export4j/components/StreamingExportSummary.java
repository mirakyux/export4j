// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

/** Immutable bounded result of a successful single-pass export; contains no text, resources or page lists.
 * Source cells/code points count headers once; output lines and diagnostics include repeated headers.
 * Commands count top-level display commands handed to the backend; outputBytes counts bytes written to
 * the destination, not backend storage or filesystem allocation. Body rows exclude headers; pages are physical.
 */
public record StreamingExportSummary(long bodyRows, int pages, long sourceCells, long sourceCodePoints,
        long outputLines, long commands, long diagnostics, long outputBytes) {
    public StreamingExportSummary {
        if (bodyRows < 0 || pages < 1 || sourceCells < 0 || sourceCodePoints < 0 || outputLines < 0
                || commands < 0 || diagnostics < 0 || outputBytes < 0) {
            throw new IllegalArgumentException("invalid streaming export summary");
        }
    }
}
