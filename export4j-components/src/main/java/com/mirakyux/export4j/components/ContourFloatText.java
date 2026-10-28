// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.awt.BasicStroke;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.SemanticPlacement;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.ComponentSemantics;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.RegionFlow;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.TextDiagnostic;

/** Text around explicitly masked arbitrary nonzero contours, including cubic curves, in points.
 * Each line uses the widest clear interval across its entire line band (leftmost on ties).
 * Anchors locate floats in a region or after an original UTF-16 text position; text anchors activate
 * below their anchor line. Floats may continue across regions. RegionFlow children continue complete
 * source fragments; atomic graphic children are tiled through disjoint page masks. Atomic text must
 * fit one region, preventing duplicated logical text. Contour masking is an explicit visual operation.
 * Immutable, owns no resources; children and callbacks are trusted cooperative code. */
public record ContourFloatText(OpenTypeText text, List<Floating> floats) implements RegionFlow {
    public sealed interface Anchor permits AtRegion, AfterText { }
    /** Nonnegative local y in a specified zero-based region. */
    public record AtRegion(int region, double y) implements Anchor {
        public AtRegion {
            if (region < 0 || region >= 100_000 || !finite(y) || y < 0) { throw new IllegalArgumentException("invalid region anchor"); }
        }
    }
    /** Original UTF-16 position. The float begins after the containing line, clearance and extra gap. */
    public record AfterText(int sourceOffset, double gap) implements Anchor {
        public AfterText {
            if (sourceOffset < 0 || !finite(gap) || gap < 0) { throw new IllegalArgumentException("invalid text anchor"); }
        }
    }
    /** Local contour lies within the declared width/height. Content is explicitly masked by that contour.
     * x is a fixed left offset in each region. clearance expands only the text exclusion contour. */
    public record Floating(Component content, double x, double width, double height, VectorPath contour,
            double clearance, Anchor anchor) {
        public Floating {
            Objects.requireNonNull(content, "content"); Objects.requireNonNull(contour, "contour"); Objects.requireNonNull(anchor, "anchor");
            if (!finite(x) || x < 0 || !finite(width) || width <= 0 || !finite(height) || height <= 0
                    || !finite(clearance) || clearance < 0 || clearance > 10_000 || contour.segments().size() > 4096) {
                throw new IllegalArgumentException("invalid float contour or geometry budget");
            }
            var bounds = area(contour).getBounds2D();
            if (bounds.isEmpty() || bounds.getMinX() < 0 || bounds.getMinY() < 0
                    || bounds.getMaxX() > Math.nextUp(width) || bounds.getMaxY() > Math.nextUp(height)) {
                throw new IllegalArgumentException("float contour must be nonempty and fit its declared box");
            }
        }
    }
    public ContourFloatText {
        Objects.requireNonNull(text, "text"); floats = List.copyOf(floats);
        if (floats.size() > 64) { throw new IllegalArgumentException("float count budget exceeded"); }
        for (var value : floats) {
            if (value.anchor() instanceof AfterText anchor && (anchor.sourceOffset() > text.text().length()
                    || anchor.sourceOffset() < text.text().length() && Character.isLowSurrogate(text.text().charAt(anchor.sourceOffset())))) {
                throw new IllegalArgumentException("text anchor must be an original Unicode boundary");
            }
        }
    }
    private static boolean finite(double value) { return Double.isFinite(value) && Math.abs(value) <= 10_000_000; }

    @Override public List<Fragment> layoutRegions(ComponentContext context, FlowRegions regions) {
        ComponentSupport.fits(context.width(), regions.continuationHeight(), context);
        var resolvedText = text.resolved(context);
        double step = resolvedText.style().lineHeight();
        if (step > regions.continuationHeight()) { throw new IllegalArgumentException("float text line exceeds continuation region"); }
        var geometry = new Geometry(regions);
        var resolved = new ArrayList<Positioned>();
        var deferred = new ArrayList<Floating>();
        for (var value : floats) {
            if (value.x() + value.width() > Math.nextUp(context.width())) { throw new IllegalArgumentException("float exceeds region width"); }
            if (value.anchor() instanceof AtRegion anchor) {
                if (anchor.y() > regions.height(anchor.region())) { throw new IllegalArgumentException("float region anchor exceeds region height"); }
                resolved.add(position(value, geometry.start(anchor.region()) + anchor.y()));
            } else { deferred.add(value); }
        }
        deferred.sort(Comparator.comparingInt(value -> ((AfterText) value.anchor()).sourceOffset()));
        var shaping = new ShapeWork();
        var work = new Work();
        for (var value : deferred) {
            ExecutionContext.checkpoint();
            var slots = new Slots(context.width(), step, geometry, resolved, work);
            var measured = shaping.shape(resolvedText, context, slots);
            int source = ((AfterText) value.anchor()).sourceOffset();
            int index = measured.lines().size() - 1;
            for (int at = 0; at < measured.lines().size(); at++) {
                if (source < measured.lines().get(at).sourceEnd() || source <= measured.lines().get(at).sourceStart()) { index = at; break; }
            }
            var slot = slots.get(index);
            double top = geometry.start(slot.region()) + slot.y() + step + value.clearance() + ((AfterText) value.anchor()).gap();
            resolved.add(position(value, top));
        }
        var slots = new Slots(context.width(), step, geometry, resolved, work);
        var measured = text.text().isEmpty() && !floats.isEmpty() ? new OpenTypeText.Measured(List.of(), List.of())
                : shaping.shape(resolvedText, context, slots);
        var pages = new TreeMap<Integer, Page>();
        for (int index = 0; index < measured.lines().size(); index++) {
            ExecutionContext.checkpoint(); var slot = slots.get(index);
            var page = pages.computeIfAbsent(slot.region(), ignored -> new Page());
            page.commands.add(new DrawCommand.Save());
            page.commands.add(new DrawCommand.Transform(AffineTransform.translation(0, slot.y())));
            page.commands.add(measured.lines().get(index).command()); page.commands.add(new DrawCommand.Restore());
            page.height = Math.max(page.height, slot.y() + step); page.lines++;
            var line = measured.lines().get(index);
            page.diagnostics.addAll(PreparedCellFlow.diagnostics(measured.diagnostics(), line.sourceStart(), line.sourceEnd()));
        }
        for (var value : resolved) { paintFloat(value, context, geometry, pages); }
        if (pages.isEmpty()) { throw new IllegalArgumentException("float flow made no progress"); }
        var result = new ArrayList<Fragment>();
        // Explicit future-region anchors deliberately reserve the intervening page regions.
        int first = Math.min(pages.firstKey(), regions.initialHeights().size());
        for (int region = first; region <= pages.lastKey(); region++) {
            ExecutionContext.checkpoint();
            var page = pages.get(region);
            if (page == null) { result.add(new Fragment(region, new ComponentLayout(context.width(), regions.height(region), 0, List.of(), List.of()))); }
            else {
                result.add(new Fragment(region, new ComponentLayout(context.width(), page.height, page.lines,
                        page.commands, page.diagnostics, page.placements)));
            }
        }
        return ComponentSupport.regionFragments(result, context, regions);
    }

    private static final class Page {
        private final List<DrawCommand> commands = new ArrayList<>();
        private final List<SemanticPlacement> placements = new ArrayList<>();
        private final List<TextDiagnostic> diagnostics = new ArrayList<>();
        private double height;
        private int lines;
    }
    private record Positioned(Floating source, double top, Area exclusion) { }
    private static Positioned position(Floating value, double top) {
        if (!finite(top + value.height())) { throw new IllegalArgumentException("float extent budget exceeded"); }
        var shape = area(value.contour());
        if (value.clearance() > 0) {
            shape.add(new Area(new BasicStroke((float) (2 * value.clearance()), BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND).createStrokedShape(shape)));
        }
        return new Positioned(value, top, shape);
    }

    private static void paintFloat(Positioned positioned, ComponentContext context, Geometry geometry, Map<Integer, Page> pages) {
        var value = positioned.source();
        int first = geometry.region(positioned.top());
        int last = geometry.region(Math.nextDown(positioned.top() + value.height()));
        List<Fragment> children;
        boolean flowing = value.content() instanceof RegionFlow;
        if (flowing) {
            var initial = new ArrayList<Double>();
            initial.add(Math.min(value.height(), geometry.regions.height(first) - (positioned.top() - geometry.start(first))));
            for (int region = first + 1; region < geometry.regions.initialHeights().size(); region++) { initial.add(geometry.regions.height(region)); }
            children = ((RegionFlow) value.content()).layoutRegions(context.viewport(value.width(), geometry.regions.continuationHeight()),
                    new FlowRegions(initial, geometry.regions.continuationHeight()));
            for (var child : children) {
                double y = child.regionIndex() == 0 ? positioned.top() - geometry.start(first) : 0;
                int region = first + child.regionIndex();
                if (region > last || geometry.start(region) + y + child.layout().height() > Math.nextUp(positioned.top() + value.height())) {
                    throw new IllegalArgumentException("floating source exceeds its declared height");
                }
            }
        } else {
            var child = value.content().layout(context.viewport(value.width(), value.height()));
            ComponentSupport.fits(child.width(), child.height(), context.viewport(value.width(), value.height()));
            boolean[] hasText = {false};
            com.mirakyux.export4j.graphics.core.GraphicsResources.walk(child.staticCommands(), command -> {
                if (command instanceof DrawCommand.Text || command instanceof DrawCommand.GlyphText || command instanceof DrawCommand.OutlineText) {
                    hasText[0] = true;
                }
            });
            if (first != last && (child.textLines() != 0 || !child.semanticPlacements().isEmpty()
                    || hasText[0])) {
                throw new IllegalArgumentException("cross-region floating text requires a RegionFlow child");
            }
            children = List.of(new Fragment(0, child));
        }
        for (int region = first; region <= last; region++) {
            ExecutionContext.checkpoint();
            double y = Math.max(0, positioned.top() - geometry.start(region));
            double offset = Math.max(0, geometry.start(region) - positioned.top());
            double height = Math.min(geometry.regions.height(region) - y, value.height() - offset);
            if (height <= 0) { continue; }
            var page = pages.computeIfAbsent(region, ignored -> new Page());
            var builder = new DisplayListBuilder(new PageSize(context.width(), y + height));
            builder.save().transform(AffineTransform.translation(value.x(), y)).clip(new Rect(0, 0, value.width(), height));
            builder.transform(AffineTransform.translation(0, -offset)).clipPath(value.contour(), DrawCommand.FillRule.NONZERO);
            if (flowing) { builder.transform(AffineTransform.translation(0, offset)); }
            int target = region - first;
            var part = flowing ? children.stream().filter(child -> child.regionIndex() == target).findFirst().map(Fragment::layout)
                    : java.util.Optional.of(children.getFirst().layout());
            if (part.isPresent()) {
                var child = part.orElseThrow();
                var placements = new ArrayList<SemanticPlacement>();
                ComponentSemantics.append(builder, placements, child);
                int commandsBefore = page.commands.size();
                for (var placement : placements) {
                    page.placements.add(new SemanticPlacement(commandsBefore + placement.commandIndex(), placement.source(), placement.fragment(),
                            placement.fonts(), placement.roles(), placement.fragmentIndex(), placement.fragmentCount()));
                }
                page.lines = Math.addExact(page.lines, child.textLines()); page.diagnostics.addAll(child.diagnostics());
            }
            builder.restore(); page.commands.addAll(builder.build().commands()); page.height = Math.max(page.height, y + height);
        }
    }

    private static final class ShapeWork {
        private long units;
        private OpenTypeText.Measured shape(OpenTypeText text, ComponentContext context, Slots slots) {
            while (true) {
                ExecutionContext.checkpoint();
                if ((units += Math.max(1, text.text().length())) > 4_000_000) {
                    throw new IllegalArgumentException("float source shaping work budget exceeded");
                }
                try { return text.measure(context, line -> slots.get(line).width(), line -> slots.get(line).x()); }
                catch (OpenTypeText.LineTooNarrow narrow) {
                    if (slots.get(narrow.line).width() >= context.width()) { throw narrow; }
                    slots.skip(narrow.line);
                }
            }
        }
    }

    private record Slot(int region, double y, double x, double width) { }
    private static final class Work {
        private long units;
        private void add(long count) {
            ExecutionContext.checkpoint();
            if ((units += count) > 20_000_000) { throw new IllegalArgumentException("float contour work budget exceeded"); }
        }
    }
    private static final class Slots {
        private final double width;
        private final double step;
        private final Geometry geometry;
        private final List<Positioned> floats;
        private final Work work;
        private final List<Slot> slots = new ArrayList<>();
        private int region;
        private double y;
        private Slots(double width, double step, Geometry geometry, List<Positioned> floats, Work work) {
            this.width = width; this.step = step; this.geometry = geometry; this.floats = List.copyOf(floats); this.work = work;
        }
        private void skip(int index) {
            var rejected = slots.get(index);
            slots.subList(index, slots.size()).clear();
            region = rejected.region(); y = rejected.y() + step;
        }
        private Slot get(int index) {
            if (index < 0 || index >= 100_000) { throw new IllegalArgumentException("float line budget exceeded"); }
            while (slots.size() <= index) {
                work.add(1);
                if (y + step > Math.nextUp(geometry.regions.height(region))) { region++; y = 0; }
                if (region >= 100_000) { throw new IllegalArgumentException("float region budget exceeded"); }
                if (y + step > Math.nextUp(geometry.regions.height(region))) { continue; }
                double global = geometry.start(region) + y;
                var blocked = new ArrayList<double[]>();
                for (var value : floats) {
                    var bounds = value.exclusion().getBounds2D();
                    if (global + step <= value.top() + bounds.getMinY() || global >= value.top() + bounds.getMaxY()) { continue; }
                    work.add(value.source().contour().segments().size());
                    var band = new Area(value.exclusion());
                    band.intersect(new Area(new Rectangle2D.Double(bounds.getMinX(), global - value.top(), bounds.getWidth(), step)));
                    if (!band.isEmpty()) {
                        var cut = band.getBounds2D();
                        blocked.add(new double[] {Math.max(0, value.source().x() + cut.getMinX()), Math.min(width, value.source().x() + cut.getMaxX())});
                    }
                }
                blocked.sort(Comparator.comparingDouble(value -> value[0]));
                double left = 0, bestX = 0, best = 0;
                for (var cut : blocked) {
                    if (cut[0] - left > best) { best = cut[0] - left; bestX = left; }
                    left = Math.max(left, cut[1]);
                }
                if (width - left > best) { best = width - left; bestX = left; }
                if (best > 0) { slots.add(new Slot(region, y, bestX, best)); }
                double next = y + step;
                if (next <= y) { throw new IllegalArgumentException("float line grid cannot advance"); }
                y = next;
            }
            return slots.get(index);
        }
    }
    private static final class Geometry {
        private final FlowRegions regions;
        private final double prefix;
        private Geometry(FlowRegions regions) { this.regions = regions; prefix = regions.initialHeights().stream().mapToDouble(Double::doubleValue).sum(); }
        private double start(int region) {
            if (region < 0 || region >= 100_000) { throw new IllegalArgumentException("float region budget exceeded"); }
            if (region >= regions.initialHeights().size()) { return prefix + (region - regions.initialHeights().size()) * regions.continuationHeight(); }
            return regions.initialHeights().subList(0, region).stream().mapToDouble(Double::doubleValue).sum();
        }
        private int region(double y) {
            if (y < 0 || !Double.isFinite(y)) { throw new IllegalArgumentException("invalid global float position"); }
            double before = 0;
            for (int at = 0; at < regions.initialHeights().size(); at++) {
                before += regions.height(at); if (y < before) { return at; }
            }
            long result = regions.initialHeights().size() + (long) Math.floor((y - prefix) / regions.continuationHeight());
            if (result >= 100_000) { throw new IllegalArgumentException("float region budget exceeded"); }
            return (int) result;
        }
    }
    private static Area area(VectorPath path) {
        var shape = new Path2D.Double(Path2D.WIND_NON_ZERO);
        for (var segment : path.segments()) {
            ExecutionContext.checkpoint();
            switch (segment) {
                case VectorPath.MoveTo move -> shape.moveTo(move.point().x(), move.point().y());
                case VectorPath.LineTo line -> shape.lineTo(line.point().x(), line.point().y());
                case VectorPath.CurveTo curve -> shape.curveTo(curve.control1().x(), curve.control1().y(), curve.control2().x(), curve.control2().y(), curve.end().x(), curve.end().y());
                case VectorPath.Close ignored -> shape.closePath();
            }
        }
        return new Area(shape);
    }
}
