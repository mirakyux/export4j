// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.SemanticPlacement;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.RegionFlow;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.TextDiagnostic;

/** Explicit row splitting. Geometry advances independently of complete-line cursors within merged cells. */
final class GridTableContinuation {
    private GridTableContinuation() { }

    static List<RegionFlow.Fragment> layout(GridTable table, ComponentContext context, FlowRegions regions) {
        return layout(table, context, regions, null);
    }

    static List<RegionFlow.Fragment> layout(GridTable table, ComponentContext context, FlowRegions regions, List<Double> widths) {
        boolean variable = widths != null;
        var budget = new Budget();
        var streams = new GridSemanticStreams();
        var cells = table.cells();
        double max = table.columnWeights().stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        double sum = table.columnWeights().stream().mapToDouble(w -> w / max).sum();
        double[] xs = new double[table.columnWeights().size() + 1];
        for (int column = 0; column < table.columnWeights().size(); column++) {
            xs[column + 1] = xs[column] + context.width() * (table.columnWeights().get(column) / max) / sum;
        }
        List<PreparedCellFlow> flows = new ArrayList<>();
        for (var cell : cells) {
            budget.tick();
            double width = xs[cell.column() + cell.columnSpan()] - xs[cell.column()] - cell.padding().left() - cell.padding().right();
            double height = regions.continuationHeight() - cell.padding().top() - cell.padding().bottom();
            flows.add(variable ? VariableCellFlow.prepare(cell.content(), context.viewport(width, height), streams, cell.row() < table.headerRows())
                    : PreparedCellFlow.prepare(cell.content(), context.viewport(width, height), streams, cell.row() < table.headerRows()));
            if (variable) { flows.getLast().reflow(context.viewport(width, height)); }
        }
        var constraints = IntStream.range(0, cells.size()).boxed().sorted(Comparator
                .<Integer>comparingInt(i -> cells.get(i).rowSpan()).thenComparingInt(i -> cells.get(i).row())
                .thenComparingInt(i -> cells.get(i).column())).toList();
        double[] heights = table.minimumRowHeights().stream().mapToDouble(Double::doubleValue).toArray();
        for (int index : constraints) {
            var cell = cells.get(index);
            double required = flows.get(index).remainingHeight() + cell.padding().top() + cell.padding().bottom();
            double actual = sum(heights, cell.row(), cell.row() + cell.rowSpan(), budget);
            if (required > actual) {
                double extra = (required - actual) / cell.rowSpan();
                for (int row = cell.row(); row < cell.row() + cell.rowSpan(); row++) { budget.tick(); heights[row] += extra; }
            }
        }
        double headerHeight = sum(heights, 0, table.headerRows(), budget);
        if (headerHeight >= regions.continuationHeight()) {
            throw new IllegalArgumentException("headers leave no body space in continuation region");
        }
        List<Optional<ComponentLayout>> headers = new ArrayList<>();
        for (int index = 0; index < cells.size(); index++) {
            budget.tick();
            var cell = cells.get(index);
            if (cell.row() < table.headerRows() && !variable) {
                double height = sum(heights, cell.row(), cell.row() + cell.rowSpan(), budget)
                        - cell.padding().top() - cell.padding().bottom();
                var content = flows.get(index).take(height);
                if (content.isEmpty() || !flows.get(index).done()) { throw new IllegalArgumentException("header cell must fit as one fragment"); }
                headers.add(content);
            } else { headers.add(Optional.empty()); }
        }

        List<RegionFlow.Fragment> result = new ArrayList<>();
        int first = table.headerRows();
        double[] remainingMinima = table.minimumRowHeights().stream().mapToDouble(Double::doubleValue).toArray();
        boolean[] joined = new boolean[table.rowCount()];
        for (var cell : cells) {
            for (int row = cell.row() + 1; row < cell.row() + cell.rowSpan(); row++) { joined[row] = true; }
        }
        for (int region = 0; first < table.rowCount(); region++) {
            budget.tick();
            if (region >= 100_016) { throw new IllegalArgumentException("grid logical region budget exceeded"); }
            double actualWidth = variable ? widths.get(region % widths.size()) : context.width();
            if (variable) {
                for (int column = 0; column < table.columnWeights().size(); column++) {
                    xs[column + 1] = xs[column] + actualWidth * (table.columnWeights().get(column) / max) / sum;
                }
                heights = remainingMinima.clone();
                for (int index = 0; index < cells.size(); index++) {
                    budget.tick();
                    var cell = cells.get(index);
                    if (cell.row() + cell.rowSpan() <= first && cell.row() >= table.headerRows()) { continue; }
                    double width = xs[cell.column() + cell.columnSpan()] - xs[cell.column()] - cell.padding().left() - cell.padding().right();
                    flows.get(index).reflow(context.viewport(width, regions.continuationHeight() - cell.padding().top() - cell.padding().bottom()));
                }
                for (int index : constraints) {
                    budget.tick(); var cell = cells.get(index);
                    if (cell.row() + cell.rowSpan() <= first && cell.row() >= table.headerRows()) { continue; }
                    int start = cell.row() < table.headerRows() ? cell.row() : Math.max(first, cell.row());
                    double required = flows.get(index).remainingHeight() + cell.padding().top() + cell.padding().bottom();
                    double actual = sum(heights, start, cell.row() + cell.rowSpan(), budget);
                    if (required > actual) {
                        double extra = (required - actual) / (cell.row() + cell.rowSpan() - start);
                        for (int row = start; row < cell.row() + cell.rowSpan(); row++) { heights[row] += extra; }
                    }
                }
                headerHeight = sum(heights, 0, table.headerRows(), budget);
            }
            // Continuation padding and unused line-box space must never consume the remaining source.
            // Increase the ending logical row before allocating the next region, in canonical order.
            for (int index : constraints) {
                budget.tick();
                var cell = cells.get(index);
                int end = cell.row() + cell.rowSpan();
                if (end <= first || flows.get(index).done()) { continue; }
                double required = flows.get(index).remainingHeight() + cell.padding().top() + cell.padding().bottom();
                double actual = sum(heights, Math.max(first, cell.row()), end, budget);
                if (required > actual) { heights[end - 1] += required - actual; }
            }
            double capacity = regions.height(region) - headerHeight;
            if (capacity <= 0) {
                if (region < regions.initialHeights().size()) { continue; }
                throw new IllegalArgumentException("table cannot advance in continuation region");
            }
            double[] assigned = new double[table.rowCount()];
            double used = 0;
            int last = first;
            while (last < table.rowCount() && used < capacity) {
                budget.tick();
                double available = capacity - used;
                if (!table.allowRowSplit()) {
                    int end = last + 1;
                    while (end < table.rowCount() && joined[end]) { end++; }
                    double group = sum(heights, last, end, budget);
                    if (group > Math.nextUp(available)) { break; }
                    while (last < end) { assigned[last] = heights[last]; used += heights[last]; last++; }
                    continue;
                }
                double take = heights[last] <= Math.nextUp(available) ? heights[last] : available;
                assigned[last] = take;
                used += take;
                last++;
                if (take < heights[last - 1]) { break; }
            }
            if (last == first) {
                if (region < regions.initialHeights().size()) { continue; }
                throw new IllegalArgumentException("grid atomic group cannot fit the actual region");
            }
            List<Optional<ComponentLayout>> contents = new ArrayList<>(headers);
            boolean progress = false;
            boolean pending = false;
            for (int index = 0; index < cells.size(); index++) {
                budget.tick();
                var cell = cells.get(index);
                if (cell.row() < table.headerRows() || cell.row() >= last || cell.row() + cell.rowSpan() <= first) { continue; }
                var flow = flows.get(index);
                double height = sum(assigned, Math.max(first, cell.row()), Math.min(last, cell.row() + cell.rowSpan()), budget);
                var part = flow.take(Math.max(0, height - cell.padding().top() - cell.padding().bottom()));
                contents.set(index, part);
                progress |= part.isPresent();
                pending |= !flow.done();
                if (cell.row() + cell.rowSpan() <= last && assigned[cell.row() + cell.rowSpan() - 1] == heights[cell.row() + cell.rowSpan() - 1]
                        && !flow.done()) {
                    throw new IllegalArgumentException("cell content cannot advance within its final row");
                }
            }
            boolean completedRow = assigned[first] == heights[first];
            if (!progress && pending && region < regions.initialHeights().size()) { continue; }
            if (!progress && pending && !completedRow) {
                throw new IllegalArgumentException("cell atomic content or line constraints exceed continuation region");
            }
            if (result.size() >= 100_000) { throw new IllegalArgumentException("table region fragment budget exceeded"); }
            if (variable) {
                for (int index = 0; index < cells.size(); index++) {
                    var cell = cells.get(index);
                    if (cell.row() >= table.headerRows()) { continue; }
                    double height = sum(heights, cell.row(), cell.row() + cell.rowSpan(), budget)
                            - cell.padding().top() - cell.padding().bottom();
                    var part = flows.get(index).take(height);
                    if (part.isEmpty() || !flows.get(index).done()) { throw new IllegalArgumentException("header cell must fit as one fragment"); }
                    contents.set(index, part);
                }
            }
            result.add(new RegionFlow.Fragment(region, paint(table, actualWidth, headerHeight + used, first, last, xs, heights, assigned, contents, budget)));
            for (int row = first; row < last; row++) {
                budget.tick(); heights[row] = Math.max(0, heights[row] - assigned[row]);
                remainingMinima[row] = Math.max(0, remainingMinima[row] - assigned[row]);
            }
            while (first < table.rowCount() && heights[first] == 0) { budget.tick(); first++; }
        }
        if (flows.stream().anyMatch(flow -> !flow.done())) { throw new IllegalArgumentException("table ended before all cell content was consumed"); }
        return ComponentSupport.regionFragments(streams.finish(result), context, regions);
    }

    private static ComponentLayout paint(GridTable table, double width, double height, int first, int last, double[] xs,
            double[] heights, double[] assigned, List<Optional<ComponentLayout>> contents, Budget budget) {
        double[] ys = new double[table.rowCount() + 1];
        for (int row = 0; row < table.rowCount(); row++) {
            budget.tick();
            ys[row + 1] = ys[row] + (row < table.headerRows() ? heights[row] : assigned[row]);
        }
        var builder = new DisplayListBuilder(new PageSize(width, height));
        List<TextDiagnostic> diagnostics = new ArrayList<>();
        List<SemanticPlacement> placements = new ArrayList<>();
        int lines = 0;
        for (int index = 0; index < table.cells().size(); index++) {
            budget.tick();
            var cell = table.cells().get(index);
            boolean header = cell.row() < table.headerRows();
            if (!header && (cell.row() >= last || cell.row() + cell.rowSpan() <= first)) { continue; }
            int start = header ? cell.row() : Math.max(first, cell.row());
            int end = header ? cell.row() + cell.rowSpan() : Math.min(last, cell.row() + cell.rowSpan());
            var rect = new Rect(xs[cell.column()], ys[start], xs[cell.column() + cell.columnSpan()] - xs[cell.column()], ys[end] - ys[start]);
            GridTable.paintCell(builder, placements, cell, rect, contents.get(index));
            if (contents.get(index).isPresent()) {
                var part = contents.get(index).orElseThrow();
                diagnostics.addAll(part.diagnostics()); lines = Math.addExact(lines, part.textLines());
            }
        }
        return new ComponentLayout(width, height, lines, builder.build().commands(), diagnostics, placements);
    }

    private static double sum(double[] values, int first, int last, Budget budget) {
        double sum = 0;
        for (int index = first; index < last; index++) { budget.tick(); sum += values[index]; }
        if (!Double.isFinite(sum)) { throw new IllegalArgumentException("table height overflow"); }
        return sum;
    }

    private static final class Budget {
        private int work;
        void tick() {
            ExecutionContext.checkpoint();
            if (++work > 10_000_000) { throw new IllegalArgumentException("table continuation work budget exceeded"); }
        }
    }
}
