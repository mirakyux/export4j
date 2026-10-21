// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.List;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.PaginationPolicy;

/** Complete-line allocation after a single wrap; skipped short regions consume no source. */
final class LineFlowSlices {
    record Slice(int region, int first, int count) { }
    private LineFlowSlices() { }

    static List<Slice> split(int lineCount, double step, FlowRegions regions, PaginationPolicy policy) {
        if (lineCount < 1 || lineCount > 100_000 || capacity(regions.continuationHeight(), step) < 1) {
            throw new IllegalArgumentException("line count or fixed line height exceeds flow budget");
        }
        var slices = new ArrayList<Slice>();
        int first = 0;
        for (int region = 0; first < lineCount; region++) {
            ExecutionContext.checkpoint();
            int remaining = lineCount - first;
            int take = Math.min(capacity(regions.height(region), step), remaining);
            if (take < remaining) {
                take = Math.min(take, remaining - policy.minLinesAfterBreak());
                if (take < policy.minLinesBeforeBreak()) { take = 0; }
            }
            if (take == 0) {
                if (region < regions.initialHeights().size()) { continue; }
                throw new IllegalArgumentException("line pagination constraints exceed continuation height");
            }
            slices.add(new Slice(region, first, take));
            first += take;
        }
        return List.copyOf(slices);
    }

    private static int capacity(double height, double step) {
        return (int) Math.floor(Math.nextUp(height / step));
    }
}
