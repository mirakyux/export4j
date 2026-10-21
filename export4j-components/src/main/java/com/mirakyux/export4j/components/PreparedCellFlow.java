// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.TextDiagnostic;

/** Layout-local cursors over measured content. No backend clipping and no reshaping on continuation. */
abstract class PreparedCellFlow {
    abstract double remainingHeight();
    abstract boolean done();
    abstract Optional<ComponentLayout> take(double height);

    static PreparedCellFlow prepare(FlowText content, ComponentContext context) {
        return prepare(content, context, new GridSemanticStreams(), false);
    }

    static PreparedCellFlow prepare(Component content, ComponentContext context, GridSemanticStreams streams, boolean header) {
        return prepare(content, context, streams, header, 0);
    }

    private static PreparedCellFlow prepare(Component content, ComponentContext context, GridSemanticStreams streams,
            boolean header, int depth) {
        ExecutionContext.checkpoint();
        if (depth > 64) { throw new IllegalArgumentException("cell flow nesting budget exceeded"); }
        if (content instanceof FontScope scope) {
            return prepare(scope.content(), context.withFonts(scope.fonts()), streams, header, depth + 1);
        }
        if (content instanceof StyleScope scope) {
            return prepare(scope.content(), scope.scoped(context), streams, header, depth + 1);
        }
        if (content instanceof SemanticTable semantic) {
            var cursor = new DocumentLayouter().prepareTable(semantic.table(), context);
            var source = streams.register(semantic.table(), context, header);
            return new PreparedCellFlow() {
                @Override double remainingHeight() { return cursor.remainingHeight(); }
                @Override boolean done() { return cursor.done(); }
                @Override Optional<ComponentLayout> take(double height) {
                    return cursor.take(height).map(fragment -> {
                        int lines = Math.toIntExact(fragment.rows().stream().flatMap(row -> row.cells().stream())
                                .mapToLong(com.mirakyux.export4j.layout.CellFragment::lineCount).sum());
                        return new ComponentLayout(fragment.width(), fragment.height(), lines, List.of(), List.of(),
                                List.of(source.append(fragment)));
                    });
                }
            };
        }
        if (content instanceof FlowText source) {
            var text = source.resolved(context);
            var measured = new ParagraphLayouter().layout(text.text(), context.fontGroup(), context.width(), text.style());
            return new Lines(measured.lines().size(), text.style().lineHeight(), text.pagination()) {
                @Override ComponentLayout paint(int first, int count) {
                    var lines = measured.lines().subList(first, first + count).stream()
                            .map(line -> line.atBaseline(line.baselineY() - first * step)).toList();
                    var diagnostics = diagnostics(measured.diagnostics(), lines.getFirst().sourceStart(), lines.getLast().sourceEnd());
                    var paragraph = new ParagraphLayout(context.width(), count * step, lines, diagnostics);
                    var builder = new DisplayListBuilder(new PageSize(context.width(), paragraph.height()));
                    TextLayoutPainter.draw(builder, paragraph, new Point(0, 0), text.color());
                    return new ComponentLayout(context.width(), paragraph.height(), count, builder.build().commands(), diagnostics);
                }
            };
        }
        if (content instanceof ChoiceGroup choices) { return prepare(choices.resolved(context).text(), context, streams, header, depth + 1); }
        if (content instanceof FormText source) {
            var text = source.resolved(context);
            var measured = text.measure(context);
            return new Lines(measured.lines().size(), text.style().lineHeight(), PaginationPolicy.DEFAULT) {
                @Override ComponentLayout paint(int first, int count) { return text.paint(measured, first, count); }
            };
        }
        if (content instanceof OpenTypeText source) {
            var text = source.resolved(context);
            var measured = text.measure(context, line -> context.width(), line -> 0);
            return new Lines(measured.lines().size(), text.style().lineHeight(), PaginationPolicy.DEFAULT) {
                @Override ComponentLayout paint(int first, int count) {
                    var commands = new ArrayList<DrawCommand>();
                    for (int offset = 0; offset < count; offset++) {
                        commands.add(new DrawCommand.Save());
                        commands.add(new DrawCommand.Transform(AffineTransform.translation(0, offset * step)));
                        commands.add(measured.lines().get(first + offset).command());
                        commands.add(new DrawCommand.Restore());
                    }
                    var diagnostics = diagnostics(measured.diagnostics(), measured.lines().get(first).sourceStart(),
                            measured.lines().get(first + count - 1).sourceEnd());
                    return new ComponentLayout(context.width(), count * step, count, commands, diagnostics);
                }
            };
        }
        if (content instanceof FlowColumn column) {
            double width = context.width() - column.padding().left() - column.padding().right();
            double height = context.height() - column.padding().top() - column.padding().bottom();
            var childContext = context.viewport(width, height);
            var children = column.children().stream().map(child -> prepare(child, childContext, streams, header, depth + 1)).toList();
            return new Column(column, context.width(), children);
        }
        var measured = content.layout(context);
        ComponentSupport.fits(measured.width(), measured.height(), context);
        streams.registerFixed(measured, header);
        return new PreparedCellFlow() {
            private boolean consumed;
            @Override double remainingHeight() { return consumed ? 0 : measured.height(); }
            @Override boolean done() { return consumed; }
            @Override Optional<ComponentLayout> take(double height) {
                if (consumed || measured.height() > Math.nextUp(height)) { return Optional.empty(); }
                consumed = true;
                return Optional.of(measured);
            }
        };
    }

    private static List<TextDiagnostic> diagnostics(List<TextDiagnostic> values, int start, int end) {
        return values.stream().filter(d -> d.sourceStart() >= start && d.sourceStart() < end).toList();
    }

    private abstract static class Lines extends PreparedCellFlow {
        private final int size;
        final double step;
        private final PaginationPolicy policy;
        private int first;

        Lines(int size, double step, PaginationPolicy policy) {
            if (size < 1 || size > 100_000) { throw new IllegalArgumentException("cell line budget exceeded"); }
            this.size = size; this.step = step; this.policy = policy;
        }
        @Override double remainingHeight() { return (size - first) * step; }
        @Override boolean done() { return first == size; }
        @Override Optional<ComponentLayout> take(double height) {
            int remaining = size - first;
            int count = (int) Math.min(remaining, Math.floor(Math.nextUp(Math.max(0, height) / step)));
            if (count < remaining) {
                count = Math.min(count, remaining - policy.minLinesAfterBreak());
                if (count < policy.minLinesBeforeBreak()) { count = 0; }
            }
            if (count == 0) { return Optional.empty(); }
            var layout = paint(first, count);
            first += count;
            return Optional.of(layout);
        }
        abstract ComponentLayout paint(int first, int count);
    }

    private static final class Column extends PreparedCellFlow {
        private final FlowColumn column;
        private final double width;
        private final List<PreparedCellFlow> children;
        private int first;

        Column(FlowColumn column, double width, List<PreparedCellFlow> children) {
            this.column = column; this.width = width; this.children = children;
        }
        @Override boolean done() { return first == children.size(); }
        @Override double remainingHeight() {
            if (done()) { return 0; }
            double height = column.padding().top() + column.padding().bottom();
            for (int index = first; index < children.size(); index++) {
                height += children.get(index).remainingHeight() + (index == first ? 0 : column.gap());
            }
            return height;
        }
        @Override Optional<ComponentLayout> take(double height) {
            double used = column.padding().top();
            double limit = height - column.padding().bottom();
            if (used >= limit || done()) { return Optional.empty(); }
            List<ComponentLayout> parts = new ArrayList<>();
            while (first < children.size()) {
                ExecutionContext.checkpoint();
                double gap = parts.isEmpty() ? 0 : column.gap();
                var child = children.get(first);
                var part = child.take(Math.max(0, limit - used - gap));
                if (part.isEmpty()) { break; }
                parts.add(part.orElseThrow());
                used += gap + part.orElseThrow().height();
                if (!child.done()) { break; }
                first++;
            }
            if (parts.isEmpty()) { return Optional.empty(); }
            var builder = new DisplayListBuilder(new PageSize(width, used + column.padding().bottom()));
            List<TextDiagnostic> diagnostics = new ArrayList<>();
            List<SemanticPlacement> placements = new ArrayList<>();
            int lines = 0;
            double y = column.padding().top();
            for (var part : parts) {
                double free = width - column.padding().left() - column.padding().right() - part.width();
                double offset = switch (column.alignment()) { case START -> 0; case CENTER -> free / 2; case END -> free; };
                builder.save().transform(AffineTransform.translation(column.padding().left() + offset, y));
                ComponentSemantics.append(builder, placements, part);
                builder.restore();
                y += part.height() + column.gap();
                lines = Math.addExact(lines, part.textLines());
                diagnostics.addAll(part.diagnostics());
            }
            return Optional.of(new ComponentLayout(width, used + column.padding().bottom(), lines, builder.build().commands(), diagnostics, placements));
        }
    }
}
