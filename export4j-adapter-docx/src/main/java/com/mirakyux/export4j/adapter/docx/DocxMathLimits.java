// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

/** Cumulative mathematical limits per import/export. Package and shared source budgets also apply. */
public record DocxMathLimits(int maxEquations, int maxNodes, int maxDepth, long maxMatrixCells, long maxTextCodePoints) {
    public static final DocxMathLimits DEFAULT = new DocxMathLimits(10_000, 100_000, 32, 100_000, 4_000_000);
    public DocxMathLimits {
        if (maxEquations < 1 || maxEquations > 100_000 || maxNodes < 1 || maxNodes > 100_000
                || maxDepth < 1 || maxDepth > 64 || maxMatrixCells < 1 || maxMatrixCells > 1_000_000
                || maxTextCodePoints < 1 || maxTextCodePoints > 4_000_000) {
            throw new IllegalArgumentException("invalid mathematical limits");
        }
    }
}
