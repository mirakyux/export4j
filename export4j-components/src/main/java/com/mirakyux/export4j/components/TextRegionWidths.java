// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.text.ParagraphLayout;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntToDoubleFunction;

/** Fixed line slots for width-aware text; never slices or reindexes the original source. */
final class TextRegionWidths {
    private final FlowRegions regions;
    private final List<Double> widths;
    private final double step;
    private final int continuationLines;
    private final PaginationPolicy policy;
    private int[] prefix = new int[0];
    private int tailRegion;

    TextRegionWidths(ComponentContext context, FlowRegions regions, List<Double> widths,
            double step, PaginationPolicy policy) {
        this.regions = regions;
        this.widths = ComponentSupport.variableWidths(context, regions, widths);
        this.step = step;
        this.policy = policy;
        continuationLines = capacity(regions.continuationHeight());
        if (continuationLines < 1) {
            throw new IllegalArgumentException("invalid variable-width text regions");
        }
    }

    /** Reflows the complete original source until measured lines agree with their actual region widths.
     * At most 32 measurements and 4,000,000 source UTF-16 units of repeated work. No source slicing,
     * diagnostic merging between trials or unbounded retries. Invalid font/text failures propagate.
     */
    ParagraphLayout shape(int sourceUnits, Function<IntToDoubleFunction, ParagraphLayout> measure) {
        var initial = measure.apply(this::lineWidth);
        if (valid(slices(initial.lines().size()))) { return initial; }
        int seed = initial.lines().size();
        var pending = new ArrayDeque<Integer>(); pending.add(seed);
        var visited = new HashSet<Integer>();
        long work = Math.max(1, sourceUnits);
        for (int attempt = 1; attempt < 32 && !pending.isEmpty(); attempt++) {
            ExecutionContext.checkpoint();
            int guess = pending.removeFirst();
            if (guess < 1 || guess > 100_000 || !visited.add(guess)) { attempt--; continue; }
            pending.addLast(guess - 1); pending.addLast(guess + 1);
            List<LineFlowSlices.Slice> planned;
            try { planned = LineFlowSlices.split(guess, step, regions, policy); }
            catch (IllegalArgumentException impossible) { continue; }
            work += Math.max(1, sourceUnits);
            if (work > 4_000_000) { break; }
            plan(planned);
            var shaped = measure.apply(this::lineWidth);
            int actual = shaped.lines().size();
            if (valid(slices(actual))) { return shaped; }
            pending.addFirst(actual);
        }
        throw new IllegalArgumentException("variable-width line constraints have no verified plan within the reflow budget");
    }

    private void plan(List<LineFlowSlices.Slice> slices) {
        var last = slices.getLast();
        prefix = new int[last.first()];
        for (int index = 0; index < slices.size() - 1; index++) {
            var slice = slices.get(index);
            Arrays.fill(prefix, slice.first(), slice.first() + slice.count(), slice.region());
        }
        tailRegion = last.region();
    }

    /** Uses the exact assignment that was measured, including a shorter final fragment after reflow.
     * Recomputing a greedy split from the new total can move lines to widths they never used.
     */
    List<LineFlowSlices.Slice> slices(int lineCount) {
        var result = new ArrayList<LineFlowSlices.Slice>();
        for (int first = 0; first < lineCount;) {
            ExecutionContext.checkpoint();
            int region = lineRegion(first);
            int end = first + 1;
            while (end < lineCount && lineRegion(end) == region) {
                if ((end & 1023) == 0) { ExecutionContext.checkpoint(); }
                end++;
            }
            result.add(new LineFlowSlices.Slice(region, first, end - first));
            first = end;
        }
        return List.copyOf(result);
    }

    private boolean valid(List<LineFlowSlices.Slice> slices) {
        if (slices.size() == 1) { return true; }
        for (int index = 0; index < slices.size() - 1; index++) {
            if (slices.get(index).count() < policy.minLinesBeforeBreak()) { return false; }
        }
        return slices.getLast().count() >= policy.minLinesAfterBreak();
    }

    double width(int region) { return widths.get(region % widths.size()); }

    double lineWidth(int ordinal) {
        return width(lineRegion(ordinal));
    }

    private int lineRegion(int ordinal) {
        if (ordinal < 0 || ordinal >= 100_000) { throw new IllegalArgumentException("variable line budget exceeded"); }
        if (ordinal < prefix.length) { return prefix[ordinal]; }
        ordinal -= prefix.length;
        for (int region = tailRegion; region < regions.initialHeights().size(); region++) {
            int lines = capacity(regions.height(region));
            if (ordinal < lines) { return region; }
            ordinal -= lines;
        }
        return Math.addExact(Math.max(tailRegion, regions.initialHeights().size()), ordinal / continuationLines);
    }

    private int capacity(double height) { return (int) Math.floor(Math.nextUp(height / step)); }
}
