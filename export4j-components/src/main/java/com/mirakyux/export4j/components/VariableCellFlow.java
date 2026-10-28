// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntToDoubleFunction;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.graphics.core.TextLayoutPainter;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.SemanticPlacement;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.ComponentSemantics;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.layout.core.VariableTableCursor;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.StyledSpan;
import com.mirakyux.export4j.text.TextDiagnostic;

/** Changing-width cell cursors. All reflow retains the full original shaping source. */
final class VariableCellFlow {
    private VariableCellFlow() { }

    static PreparedCellFlow prepare(Component content, ComponentContext maximum, GridSemanticStreams streams, boolean header) {
        return prepare(content, maximum, streams, header, 0);
    }

    private static PreparedCellFlow prepare(Component content, ComponentContext maximum, GridSemanticStreams streams,
            boolean header, int depth) {
        ExecutionContext.checkpoint();
        if (depth > 64) { throw new IllegalArgumentException("variable cell nesting budget exceeded"); }
        if (content instanceof FontScope scope) { return prepare(scope.content(), maximum.withFonts(scope.fonts()), streams, header, depth + 1); }
        if (content instanceof StyleScope scope) { return prepare(scope.content(), scope.scoped(maximum), streams, header, depth + 1); }
        if (content instanceof ChoiceGroup choices) { return prepare(choices.resolved(maximum).text(), maximum, streams, header, depth + 1); }
        if (content instanceof SemanticTable semantic) {
            var source = streams.register(semantic.table(), maximum, header);
            return new PreparedCellFlow() {
                private VariableTableCursor cursor;
                private long headerWork;
                @Override void reflow(ComponentContext context) {
                    if (header) {
                        com.mirakyux.export4j.model.SemanticTree.walk(List.of(semantic.table()), visit -> {
                            ExecutionContext.checkpoint();
                            if (visit.block() instanceof com.mirakyux.export4j.model.Paragraph paragraph) { headerWork += paragraph.text().length(); }
                            else if (visit.block() instanceof com.mirakyux.export4j.model.Table table) {
                                for (var row : table.rows()) {
                                    for (var cell : row.cells()) { if (!cell.hasBlocks()) { headerWork += cell.text().length(); } }
                                }
                            }
                            if (headerWork > 4_000_000) { throw new IllegalArgumentException("variable semantic header work budget exceeded"); }
                        });
                    }
                    if (cursor == null || header) { cursor = new DocumentLayouter().prepareVariableTable(semantic.table(), maximum); }
                    cursor.width(context.width());
                }
                @Override double remainingHeight() { return cursor.remainingHeight(); }
                @Override boolean done() { return cursor != null && cursor.done(); }
                @Override Optional<ComponentLayout> take(double height) {
                    return cursor.take(height).map(fragment -> new ComponentLayout(fragment.width(), fragment.height(),
                            Math.toIntExact(fragment.rows().stream().flatMap(row -> row.cells().stream())
                                    .mapToLong(com.mirakyux.export4j.layout.CellFragment::lineCount).sum()),
                            List.of(), List.of(), List.of(source.append(fragment))));
                }
            };
        }
        if (content instanceof FlowText value) {
            var text = value.resolved(maximum);
            return new TextLines(text.text().length(), text.style().lineHeight(), text.pagination(), header) {
                private ParagraphLayout measured;
                @Override void measure(ComponentContext context, IntToDoubleFunction widths) {
                    measured = new ParagraphLayouter().layout(List.of(new StyledSpan(text.text(), maximum.fontGroup(),
                            text.style().fontSize(), -1, 0, false, false)), maximum.width(), text.style(), widths);
                    size = measured.lines().size();
                }
                @Override ComponentLayout paint(int first, int count) {
                    var lines = measured.lines().subList(first, first + count).stream()
                            .map(line -> line.atBaseline(line.baselineY() - first * step)).toList();
                    var diagnostics = PreparedCellFlow.diagnostics(measured.diagnostics(), lines.getFirst().sourceStart(), lines.getLast().sourceEnd());
                    var layout = new ParagraphLayout(width, count * step, lines, diagnostics);
                    var builder = new DisplayListBuilder(new PageSize(width, layout.height()));
                    TextLayoutPainter.draw(builder, layout, new Point(0, 0), text.color());
                    return new ComponentLayout(width, layout.height(), count, builder.build().commands(), diagnostics);
                }
            };
        }
        if (content instanceof FormText value) {
            var text = value.resolved(maximum);
            return new TextLines(text.text().length(), text.style().lineHeight(), PaginationPolicy.DEFAULT, header) {
                private ParagraphLayout measured;
                @Override void measure(ComponentContext context, IntToDoubleFunction widths) {
                    var all = text.measure(maximum, widths);
                    measured = new ParagraphLayout(context.width(), all.height(), all.lines(), all.diagnostics()); size = all.lines().size();
                }
                @Override ComponentLayout paint(int first, int count) { return text.paint(measured, first, count); }
            };
        }
        if (content instanceof OpenTypeText value) {
            var text = value.resolved(maximum);
            return new TextLines(text.text().length(), text.style().lineHeight(), PaginationPolicy.DEFAULT, header) {
                private OpenTypeText.Measured measured;
                @Override void measure(ComponentContext context, IntToDoubleFunction widths) {
                    measured = text.measure(maximum, widths, line -> 0); size = measured.lines().size();
                }
                @Override ComponentLayout paint(int first, int count) {
                    var commands = new ArrayList<DrawCommand>();
                    for (int index = 0; index < count; index++) {
                        commands.add(new DrawCommand.Save());
                        commands.add(new DrawCommand.Transform(AffineTransform.translation(0, index * step)));
                        commands.add(measured.lines().get(first + index).command()); commands.add(new DrawCommand.Restore());
                    }
                    var diagnostics = PreparedCellFlow.diagnostics(measured.diagnostics(), measured.lines().get(first).sourceStart(),
                            measured.lines().get(first + count - 1).sourceEnd());
                    return new ComponentLayout(width, count * step, count, commands, diagnostics);
                }
            };
        }
        if (content instanceof FlowColumn column) {
            var children = column.children().stream().map(child -> prepare(child, maximum.viewport(
                    maximum.width() - column.padding().left() - column.padding().right(),
                    maximum.height() - column.padding().top() - column.padding().bottom()), streams, header, depth + 1)).toList();
            return new PreparedCellFlow() {
                private int first;
                private double width;
                @Override void reflow(ComponentContext context) {
                    width = context.width(); if (header) { first = 0; }
                    var childContext = maximum.viewport(width - column.padding().left() - column.padding().right(),
                            maximum.height() - column.padding().top() - column.padding().bottom());
                    for (int at = first; at < children.size(); at++) { children.get(at).reflow(childContext); }
                }
                @Override boolean done() { return first == children.size(); }
                @Override double remainingHeight() {
                    if (done()) { return 0; }
                    double value = column.padding().top() + column.padding().bottom();
                    for (int at = first; at < children.size(); at++) { value += children.get(at).remainingHeight() + (at == first ? 0 : column.gap()); }
                    return value;
                }
                @Override Optional<ComponentLayout> take(double height) {
                    double used = column.padding().top();
                    double limit = height - column.padding().bottom();
                    var parts = new ArrayList<ComponentLayout>();
                    while (first < children.size()) {
                        ExecutionContext.checkpoint();
                        var child = children.get(first);
                        double gap = parts.isEmpty() ? 0 : column.gap();
                        var part = child.take(Math.max(0, limit - used - gap));
                        if (part.isEmpty()) { break; }
                        parts.add(part.orElseThrow()); used += gap + part.orElseThrow().height();
                        if (!child.done()) { break; } first++;
                    }
                    if (parts.isEmpty()) { return Optional.empty(); }
                    var builder = new DisplayListBuilder(new PageSize(width, used + column.padding().bottom()));
                    var diagnostics = new ArrayList<TextDiagnostic>(); var placements = new ArrayList<SemanticPlacement>();
                    double y = column.padding().top(); int lines = 0;
                    for (var part : parts) {
                        double free = width - column.padding().left() - column.padding().right() - part.width();
                        double x = column.padding().left() + switch (column.alignment()) { case START -> 0; case CENTER -> free / 2; case END -> free; };
                        builder.save().transform(AffineTransform.translation(x, y));
                        ComponentSemantics.append(builder, placements, part); builder.restore(); y += part.height() + column.gap();
                        lines = Math.addExact(lines, part.textLines()); diagnostics.addAll(part.diagnostics());
                    }
                    return Optional.of(new ComponentLayout(width, used + column.padding().bottom(), lines, builder.build().commands(), diagnostics, placements));
                }
            };
        }
        return new PreparedCellFlow() {
            private ComponentLayout measured;
            private boolean consumed;
            private java.util.Map<String, GridSemanticStreams.Source> sources;
            @Override void reflow(ComponentContext context) {
                if (consumed && !header) { return; }
                if (header) { consumed = false; }
                // Atomic children may adapt their geometry; they are always measured at the actual width.
                measured = content.layout(new ComponentContext(context.width(), maximum.height(), maximum.fontGroup(),
                        maximum.pageIndex(), maximum.pageCount(), maximum.displayPageNumber(), maximum.fontRoles(),
                        maximum.defaultTextStyle(), maximum.defaultTextColor()));
                ComponentSupport.fits(measured.width(), measured.height(), context.viewport(context.width(), maximum.height()));
            }
            @Override double remainingHeight() { return consumed ? 0 : measured.height(); }
            @Override boolean done() { return consumed; }
            @Override Optional<ComponentLayout> take(double height) {
                if (consumed || measured.height() > Math.nextUp(height)) { return Optional.empty(); }
                sources = streams.recordVariableFixed(measured, header, sources); consumed = true;
                return Optional.of(measured);
            }
        };
    }

    private abstract static class TextLines extends PreparedCellFlow {
        private final int units;
        private final PaginationPolicy policy;
        private final boolean header;
        private final List<Double> accepted = new ArrayList<>();
        private long work;
        private int first;
        int size;
        final double step;
        double width;
        TextLines(int units, double step, PaginationPolicy policy, boolean header) {
            this.units = Math.max(1, units); this.step = step; this.policy = policy; this.header = header;
        }
        @Override void reflow(ComponentContext context) {
            ExecutionContext.checkpoint();
            if (header) { first = 0; accepted.clear(); }
            if (size != 0 && first == size && !header) { return; }
            if ((work += units) > 4_000_000) { throw new IllegalArgumentException("variable cell shaping work budget exceeded"); }
            width = context.width(); measure(context, line -> line < accepted.size() ? accepted.get(line) : width);
            if (size < first || size > 100_000) { throw new IllegalArgumentException("variable cell line budget exceeded"); }
        }
        @Override double remainingHeight() { return (size - first) * step; }
        @Override boolean done() { return size > 0 && first == size; }
        @Override Optional<ComponentLayout> take(double height) {
            int remaining = size - first;
            int count = (int) Math.min(remaining, Math.floor(Math.nextUp(Math.max(0, height) / step)));
            if (count < remaining) {
                count = Math.min(count, remaining - policy.minLinesAfterBreak());
                if (count < policy.minLinesBeforeBreak()) { count = 0; }
            }
            if (count <= 0) { return Optional.empty(); }
            var part = paint(first, count);
            for (int at = 0; at < count; at++) { accepted.add(width); }
            first += count; return Optional.of(part);
        }
        abstract void measure(ComponentContext context, IntToDoubleFunction widths);
        abstract ComponentLayout paint(int first, int count);
    }
}
