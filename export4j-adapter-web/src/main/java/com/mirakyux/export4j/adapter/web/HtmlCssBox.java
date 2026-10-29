// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.SemanticPlacement;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.ComponentSemantics;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.TextDiagnostic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable compiled boxes. All measurement uses the shared semantic layout and explicit context fonts. */
final class HtmlCssBox implements Component {
    private static final class Budget { int measurements; void step() { ExecutionContext.checkpoint(); if (++measurements > 100_000) { throw new IllegalArgumentException("CSS layout measurement budget exceeded"); } } }
    private record Placed(HtmlCssBox source, ComponentLayout layout, double x, double y) { }
    private record Content(List<Placed> children, double height) { }
    private record GridItem(HtmlCssBox source, int row, int column, int rowSpan, int columnSpan) { }
    private record FlexLine(List<HtmlCssBox> children, double[] sizes, List<ComponentLayout> layouts, double cross) { }
    final CssBoxStyle style;
    final List<HtmlCssBox> children;
    private final Component leaf;
    HtmlCssBox(CssBoxStyle style, List<HtmlCssBox> children, Component leaf) {
        this.style = style; this.children = List.copyOf(children); this.leaf = leaf;
        if (children.size() > 256) { throw new IllegalArgumentException("CSS box child budget exceeded"); }
    }
    boolean absolute() { return style.text("position", "static").equals("absolute"); }
    HtmlCssBox withDeclaration(String name, String value) { var declarations = new HashMap<>(style.values); declarations.put(name, value); return new HtmlCssBox(new CssBoxStyle(declarations), children, leaf); }
    @Override public ComponentLayout layout(ComponentContext context) { return layout(context, null, null, 0, new Budget()); }
    private ComponentLayout layout(ComponentContext context, Double forcedWidth, Double forcedHeight, int depth, Budget budget) {
        budget.step(); if (depth > 32) { throw new IllegalArgumentException("CSS layout depth exceeded"); }
        var p = style.padding(); double mh = horizontalMargin(), mv = verticalMargin();
        double outerWidth = forcedWidth == null ? preferred("width", context.width(), p.left() + p.right(), mh) : forcedWidth;
        outerWidth = clamp(outerWidth, limit("min-width", context.width(), p.left() + p.right(), mh, 0), limit("max-width", context.width(), p.left() + p.right(), mh, Double.POSITIVE_INFINITY));
        double width = outerWidth - mh, innerWidth = width - p.left() - p.right();
        double availableHeight = context.height() - mv;
        boolean fixedHeight = forcedHeight != null || !style.length("height").automatic();
        double height = forcedHeight != null ? forcedHeight - mv : fixedHeight ? preferred("height", context.height(), p.top() + p.bottom(), mv) - mv : availableHeight;
        finitePositive(innerWidth); finitePositive(height - p.top() - p.bottom());
        var childContext = context.viewport(innerWidth, height - p.top() - p.bottom());
        Content content;
        if (leaf != null) {
            var measured = leaf.layout(childContext); content = new Content(List.of(new Placed(null, measured, 0, 0)), measured.height());
        } else {
            var flowing = children.stream().filter(child -> !child.absolute()).toList();
            content = switch (style.text("display", "block")) {
                case "flex" -> flex(flowing, childContext, fixedHeight, depth, budget);
                case "grid" -> grid(flowing, childContext, depth, budget);
                default -> column(flowing, childContext, depth, budget);
            };
        }
        if (!fixedHeight) { height = content.height() + p.top() + p.bottom(); }
        height = clamp(height + mv, limit("min-height", context.height(), p.top() + p.bottom(), mv, 0), limit("max-height", context.height(), p.top() + p.bottom(), mv, Double.POSITIVE_INFINITY)) - mv;
        height = Math.max(height, .001); double innerHeight = height - p.top() - p.bottom();
        if (content.height() > Math.nextUp(innerHeight) || outerWidth > Math.nextUp(context.width()) || height + mv > Math.nextUp(context.height())) { throw new IllegalArgumentException("CSS box content exceeds its explicit dimensions or page region"); }
        var placements = new ArrayList<Placed>();
        for (var item : content.children()) { placements.add(new Placed(item.source(), item.layout(), item.x() + p.left(), item.y() + p.top())); }
        for (var child : children) {
            if (!child.absolute()) { continue; }
            var s = child.style; Double assignedWidth = null, assignedHeight = null;
            if (s.length("width").automatic() && !s.length("left").automatic() && !s.length("right").automatic()) { assignedWidth = width - s.length("left").resolve(width) - s.length("right").resolve(width); }
            if (s.length("height").automatic() && !s.length("top").automatic() && !s.length("bottom").automatic()) { assignedHeight = height - s.length("top").resolve(height) - s.length("bottom").resolve(height); }
            var measured = child.layout(context.viewport(width, height), assignedWidth, assignedHeight, depth + 1, budget);
            double x = !s.length("left").automatic() ? s.length("left").resolve(width) : !s.length("right").automatic() ? width - s.length("right").resolve(width) - measured.width() : 0;
            double y = !s.length("top").automatic() ? s.length("top").resolve(height) : !s.length("bottom").automatic() ? height - s.length("bottom").resolve(height) - measured.height() : 0;
            placements.add(new Placed(child, measured, x, y));
        }
        // Paint in source order, independently of flex/grid visual order and absolute positioning.
        var sourceOrder = new HashMap<HtmlCssBox, Integer>(); for (int i = 0; i < children.size(); i++) { sourceOrder.put(children.get(i), i); }
        placements.sort(Comparator.comparingInt(item -> sourceOrder.getOrDefault(item.source(), 0)));
        var builder = new DisplayListBuilder(new PageSize(outerWidth, height + mv));
        String background = style.text("background-color", "transparent");
        if (!background.equals("transparent")) { builder.fillRect(new Rect(style.pt("margin-left"), style.pt("margin-top"), width, height), rgb(HtmlCss.color(background))); }
        var semantics = new ArrayList<SemanticPlacement>(); var diagnostics = new ArrayList<TextDiagnostic>(); int lines = 0;
        var paintedBounds = new ArrayList<Rect>(); var positionedBounds = new ArrayList<Rect>();
        for (var item : placements) {
            ExecutionContext.checkpoint(); double x = item.x(), y = item.y();
            if (item.source() != null && item.source().style.text("position", "static").equals("relative")) {
                var relative = item.source().style;
                x += !relative.length("left").automatic() ? relative.length("left").resolve(innerWidth) : relative.length("right").automatic() ? 0 : -relative.length("right").resolve(innerWidth);
                y += !relative.length("top").automatic() ? relative.length("top").resolve(innerHeight) : relative.length("bottom").automatic() ? 0 : -relative.length("bottom").resolve(innerHeight);
            }
            if (x < 0 || y < 0 || x + item.layout().width() > Math.nextUp(width) || y + item.layout().height() > Math.nextUp(height)) { throw new IllegalArgumentException("positioned CSS content must remain wholly inside its containing box"); }
            var bounds = new Rect(x, y, item.layout().width(), item.layout().height());
            boolean positioned = item.source() != null && !item.source().style.text("position", "static").equals("static");
            for (var other : positioned ? paintedBounds : positionedBounds) {
                if (bounds.x() < other.x() + other.width() - 1e-8 && other.x() < bounds.x() + bounds.width() - 1e-8 && bounds.y() < other.y() + other.height() - 1e-8 && other.y() < bounds.y() + bounds.height() - 1e-8) { throw new IllegalArgumentException("positioned CSS sibling boxes must not overlap in this source-order layout subset"); }
            }
            paintedBounds.add(bounds); if (positioned) { positionedBounds.add(bounds); }
            builder.save().transform(AffineTransform.translation(x + style.pt("margin-left"), y + style.pt("margin-top")));
            ComponentSemantics.append(builder, semantics, item.layout()); builder.restore();
            lines = Math.addExact(lines, item.layout().textLines()); diagnostics.addAll(item.layout().diagnostics());
        }
        return new ComponentLayout(outerWidth, height + mv, lines, builder.build().commands(), diagnostics, semantics);
    }
    private Content column(List<HtmlCssBox> flowing, ComponentContext context, int depth, Budget budget) {
        var result = new ArrayList<Placed>(); double y = 0;
        for (var child : flowing) { var measured = child.layout(context, null, null, depth + 1, budget); result.add(new Placed(child, measured, 0, y)); y += measured.height(); }
        return new Content(result, y);
    }
    private Content flex(List<HtmlCssBox> flowing, ComponentContext context, boolean fixedHeight, int depth, Budget budget) {
        boolean column = style.text("flex-direction", "row").startsWith("column"), reverse = style.text("flex-direction", "row").endsWith("reverse");
        boolean wrap = !style.text("flex-wrap", "nowrap").equals("nowrap");
        if (column && !fixedHeight) { throw new IllegalArgumentException("column flex requires an explicit container height"); }
        if (column && wrap) { throw new IllegalArgumentException("column flex wrapping is outside the finite row-line subset"); }
        var ordered = new ArrayList<>(flowing); ordered.sort(Comparator.comparingDouble(child -> child.style.number("order", 0)));
        double available = column ? context.height() : context.width(), gap = style.gap(column);
        var bases = new HashMap<HtmlCssBox, Double>();
        for (var child : ordered) {
            String property = column ? "height" : "width"; var basis = child.style.length("flex-basis");
            double padding = column ? child.style.padding().top() + child.style.padding().bottom() : child.style.padding().left() + child.style.padding().right();
            double margin = column ? child.verticalMargin() : child.horizontalMargin();
            if (basis.automatic()) { basis = child.style.length(property); }
            if (basis.automatic()) { throw new IllegalArgumentException("flex items require an explicit basis or main-axis size"); }
            double base = basis.resolve(available) + margin + (child.style.text("box-sizing", "content-box").equals("border-box") ? 0 : padding);
            bases.put(child, Math.max(0, base));
        }
        var groups = new ArrayList<List<HtmlCssBox>>(); var current = new ArrayList<HtmlCssBox>(); double used = 0;
        for (var child : ordered) { double next = bases.get(child) + (current.isEmpty() ? 0 : gap); if (wrap && !current.isEmpty() && used + next > available) { groups.add(List.copyOf(current)); current.clear(); used = 0; next = bases.get(child); } current.add(child); used += next; }
        if (!current.isEmpty()) { groups.add(List.copyOf(current)); }
        var prepared = new ArrayList<FlexLine>();
        for (var group : groups) {
            double[] sizes = group.stream().mapToDouble(bases::get).toArray(); distribute(group, sizes, available - gap * (group.size() - 1), column);
            var measured = new ArrayList<ComponentLayout>(); double cross = 0;
            for (int i = 0; i < group.size(); i++) { var box = group.get(i).layout(context, column ? null : sizes[i], column ? sizes[i] : null, depth + 1, budget); measured.add(box); cross = Math.max(cross, column ? box.width() : box.height()); }
            if (!wrap) { cross = column ? context.width() : fixedHeight ? context.height() : cross; }
            prepared.add(new FlexLine(group, sizes, measured, cross));
        }
        double crossGap = style.gap(!column), natural = prepared.stream().mapToDouble(FlexLine::cross).sum() + Math.max(0, prepared.size() - 1) * crossGap;
        double freeCross = wrap && fixedHeight ? Math.max(0, context.height() - natural) : 0;
        String alignContent = style.text("align-content", "stretch");
        var crossSpacing = spacing(alignContent, freeCross, prepared.size());
        var result = new ArrayList<Placed>(); double crossOffset = crossSpacing[0];
        for (var line : prepared) {
            var group = line.children(); var sizes = line.sizes(); var measured = line.layouts();
            double cross = line.cross() + (alignContent.equals("stretch") && !prepared.isEmpty() ? freeCross / prepared.size() : 0);
            String justification = style.text("justify-content", "flex-start");
            if (reverse && (justification.equals("start") || justification.equals("end"))) { justification = justification.equals("start") ? "end" : "start"; }
            double free = available - Arrays.stream(sizes).sum() - gap * (group.size() - 1); var spacing = spacing(justification, free, group.size()); double cursor = spacing[0];
            for (int i = 0; i < group.size(); i++) {
                var child = group.get(i); var box = measured.get(i); String align = child.style.text("align-self", "auto"); if (align.equals("auto")) { align = style.text("align-items", "stretch"); }
                if (style.text("flex-wrap", "nowrap").equals("wrap-reverse") && (align.equals("start") || align.equals("end"))) { align = align.equals("start") ? "end" : "start"; }
                if (align.equals("stretch") && child.style.length(column ? "width" : "height").automatic()) { box = child.layout(context, column ? cross : sizes[i], column ? sizes[i] : cross, depth + 1, budget); }
                double offset = alignment(align, cross - (column ? box.width() : box.height())); double main = reverse ? available - cursor - sizes[i] : cursor;
                result.add(new Placed(child, box, column ? crossOffset + offset : main, column ? main : crossOffset + offset)); cursor += sizes[i] + gap + spacing[1];
            }
            crossOffset += cross + crossGap + crossSpacing[1];
        }
        if (style.text("flex-wrap", "nowrap").equals("wrap-reverse")) {
            double total = fixedHeight ? context.height() : Math.max(0, crossOffset - crossGap - crossSpacing[1]); result.replaceAll(p -> new Placed(p.source(), p.layout(), p.x(), total - p.y() - p.layout().height()));
        }
        return new Content(result, column ? available : result.stream().mapToDouble(p -> p.y() + p.layout().height()).max().orElse(0));
    }
    private void distribute(List<HtmlCssBox> items, double[] sizes, double available, boolean column) {
        double[] original = sizes.clone(); boolean[] frozen = new boolean[sizes.length]; String axis = column ? "height" : "width";
        for (int iteration = 0; iteration <= sizes.length; iteration++) {
            ExecutionContext.checkpoint(); double remaining = available - Arrays.stream(sizes).sum(); if (Math.abs(remaining) < 1e-8) { return; }
            double factors = 0; for (int i = 0; i < sizes.length; i++) { if (!frozen[i]) { factors += remaining > 0 ? items.get(i).style.number("flex-grow", 0) : shrinkWeight(items.get(i), original[i], column); } }
            if (factors == 0) { break; }
            boolean clamped = false;
            for (int i = 0; i < sizes.length; i++) {
                if (frozen[i]) { continue; } var item = items.get(i); double factor = remaining > 0 ? item.style.number("flex-grow", 0) : shrinkWeight(item, original[i], column);
                double padding = column ? item.style.padding().top() + item.style.padding().bottom() : item.style.padding().left() + item.style.padding().right(), margin = column ? item.verticalMargin() : item.horizontalMargin();
                double candidate = sizes[i] + remaining * factor / factors;
                double value = clamp(candidate, Math.max(.001, item.limit("min-" + axis, available, padding, margin, 0)), item.limit("max-" + axis, available, padding, margin, Double.POSITIVE_INFINITY));
                if (candidate != value) { frozen[i] = true; clamped = true; } sizes[i] = value;
            }
            if (!clamped) { break; }
        }
        if (Arrays.stream(sizes).sum() > Math.nextUp(available)) { throw new IllegalArgumentException("flex minimum sizes exceed the container"); }
    }
    private static double shrinkWeight(HtmlCssBox item, double outerBasis, boolean column) {
        var padding = item.style.padding();
        double decoration = column ? padding.top() + padding.bottom() + item.verticalMargin() : padding.left() + padding.right() + item.horizontalMargin();
        return item.style.number("flex-shrink", 1) * Math.max(0, outerBasis - decoration);
    }
    private Content grid(List<HtmlCssBox> flowing, ComponentContext context, int depth, Budget budget) {
        var tracks = CssBoxStyle.tracks(style.text("grid-template-columns", "1fr"), false); int columns = tracks.size(); double columnGap = style.gap(false), rowGap = style.gap(true);
        double[] widths = new double[columns]; double fixed = columnGap * (columns - 1), fractions = 0;
        for (int i = 0; i < columns; i++) { var t = tracks.get(i); if (t.fraction()) { fractions += t.value(); } else { widths[i] = t.percent() ? context.width() * t.value() / 100 : t.value(); fixed += widths[i]; } }
        if (fixed >= context.width() && fractions > 0 || fixed > Math.nextUp(context.width())) { throw new IllegalArgumentException("grid tracks/gaps exceed the container"); }
        for (int i = 0; i < columns; i++) { if (tracks.get(i).fraction()) { widths[i] = (context.width() - fixed) * tracks.get(i).value() / fractions; } }
        var ordered = new ArrayList<>(flowing); ordered.sort(Comparator.comparingDouble(child -> child.style.number("order", 0)));
        // Explicit cells and fixed-row items reserve their slots before auto-placement.
        ordered.sort(Comparator.comparingInt(child -> {
            int column = lines(child.style, "column")[0], row = lines(child.style, "row")[0];
            return row >= 0 ? column >= 0 ? 0 : 1 : 2;
        }));
        var occupancy = new boolean[10_000][columns]; var items = new ArrayList<GridItem>(); int cursor = 0, rows = 0;
        for (var child : ordered) {
            int[] col = lines(child.style, "column"), row = lines(child.style, "row"); int c = col[0], r = row[0]; int cs = col[1], rs = row[1];
            boolean fixedRow = r >= 0;
            if (cs > columns || c >= 0 && c + cs > columns) { throw new IllegalArgumentException("grid column placement exceeds explicit tracks"); }
            int start = style.text("grid-auto-flow", "row").contains("dense") ? 0 : cursor;
            if (fixedRow) { start = 0; }
            else if (c >= 0 && start > 0) { start = (start / columns + (c < start % columns ? 1 : 0)) * columns; }
            if (r >= 0 && c >= 0) { if (!free(occupancy, r, c, rs, cs, budget)) { throw new IllegalArgumentException("overlapping CSS grid cells are outside the safe subset"); } }
            else {
                boolean found = false;
                for (int n = start; n < 10_000 * columns; n++) { budget.step(); int rr = r >= 0 ? r : n / columns, cc = c >= 0 ? c : n % columns; if (free(occupancy, rr, cc, rs, cs, budget)) { r = rr; c = cc; found = true; if (!fixedRow) { cursor = rr * columns + cc + cs; } break; } if (fixedRow && n % columns == columns - 1) { break; } }
                if (!found) { throw new IllegalArgumentException("CSS grid placement budget exceeded"); }
            }
            for (int y = r; y < r + rs; y++) { Arrays.fill(occupancy[y], c, c + cs, true); }
            items.add(new GridItem(child, r, c, rs, cs)); rows = Math.max(rows, r + rs);
        }
        var rowTracks = CssBoxStyle.tracks(style.text("grid-template-rows", "none"), true); var autoRows = CssBoxStyle.tracks(style.text("grid-auto-rows", "auto"), true);
        rows = Math.max(rows, rowTracks.size()); if (rows == 0) { return new Content(List.of(), 0); }
        double[] heights = new double[rows]; boolean[] automatic = new boolean[rows];
        for (int i = 0; i < rows; i++) { var track = i < rowTracks.size() ? rowTracks.get(i) : autoRows.get((i - rowTracks.size()) % autoRows.size()); heights[i] = track.value(); automatic[i] = track.auto(); }
        var measured = new HashMap<HtmlCssBox, ComponentLayout>();
        var sizingOrder = new ArrayList<>(items); sizingOrder.sort(Comparator.comparingInt(GridItem::rowSpan));
        for (var item : sizingOrder) {
            double width = sum(widths, item.column(), item.columnSpan()) + columnGap * (item.columnSpan() - 1); var box = item.source().layout(context.viewport(width, context.height()), null, null, depth + 1, budget); measured.put(item.source(), box);
            double missing = box.height() - sum(heights, item.row(), item.rowSpan()) - rowGap * (item.rowSpan() - 1); int count = 0; for (int row = item.row(); row < item.row() + item.rowSpan(); row++) { if (automatic[row]) { count++; } }
            if (missing > 0 && count == 0) { throw new IllegalArgumentException("CSS grid content exceeds fixed row tracks"); }
            if (missing > 0) { for (int row = item.row(); row < item.row() + item.rowSpan(); row++) { if (automatic[row]) { heights[row] += missing / count; } } }
        }
        var result = new ArrayList<Placed>();
        for (var item : items) {
            double width = sum(widths, item.column(), item.columnSpan()) + columnGap * (item.columnSpan() - 1), height = sum(heights, item.row(), item.rowSpan()) + rowGap * (item.rowSpan() - 1); var box = measured.get(item.source());
            String horizontal = item.source().style.text("justify-self", "auto"), vertical = item.source().style.text("align-self", "auto");
            if (horizontal.equals("auto")) { horizontal = style.text("justify-items", "stretch"); } if (vertical.equals("auto")) { vertical = style.text("align-items", "stretch"); }
            if (vertical.equals("stretch") && item.source().style.length("height").automatic()) { box = item.source().layout(context.viewport(width, Math.max(height, .001)), null, height, depth + 1, budget); }
            double x = sum(widths, 0, item.column()) + columnGap * item.column() + alignment(horizontal, width - box.width());
            double y = sum(heights, 0, item.row()) + rowGap * item.row() + alignment(vertical, height - box.height());
            result.add(new Placed(item.source(), box, x, y));
        }
        return new Content(result, Arrays.stream(heights).sum() + rowGap * (rows - 1));
    }
    private static int[] lines(CssBoxStyle style, String axis) {
        String start = style.text("grid-" + axis + "-start", "auto"), end = style.text("grid-" + axis + "-end", "auto");
        int index = start.matches("[0-9]+") ? Integer.parseInt(start) - 1 : -1;
        int span = start.startsWith("span ") ? Integer.parseInt(start.substring(5)) : end.startsWith("span ") ? Integer.parseInt(end.substring(5)) : 1;
        if (end.matches("[0-9]+")) { int stop = Integer.parseInt(end) - 1; if (index < 0) { index = stop - span; } else { span = stop - index; } }
        if (index < -1 || span < 1 || span > 256) { throw new IllegalArgumentException("CSS grid span/line is outside bounds"); } return new int[] {index, span};
    }
    private static boolean free(boolean[][] occupied, int row, int column, int rows, int columns, Budget budget) {
        if (row < 0 || column < 0 || row + rows > occupied.length || column + columns > occupied[0].length) { return false; }
        for (int y = row; y < row + rows; y++) { for (int x = column; x < column + columns; x++) { budget.step(); if (occupied[y][x]) { return false; } } } return true;
    }
    private double preferred(String name, double available, double padding, double margin) { var length = style.length(name); return length.automatic() ? available : length.resolve(available) + margin + (style.text("box-sizing", "content-box").equals("border-box") ? 0 : padding); }
    private double limit(String name, double available, double padding, double margin, double fallback) { String value = style.text(name, "auto"); if (value.equals("auto") || value.equals("none")) { return fallback; } return CssBoxStyle.dimension(value, false).resolve(available) + margin + (style.text("box-sizing", "content-box").equals("border-box") ? 0 : padding); }
    private double horizontalMargin() { return style.pt("margin-left") + style.pt("margin-right"); }
    private double verticalMargin() { return style.pt("margin-top") + style.pt("margin-bottom"); }
    private static double clamp(double value, double min, double max) { if (min > max) { throw new IllegalArgumentException("CSS minimum exceeds maximum"); } return Math.max(min, Math.min(max, value)); }
    private static double alignment(String name, double free) { return switch (name) { case "center" -> free / 2; case "end", "flex-end" -> free; default -> 0; }; }
    private static double[] spacing(String name, double free, int count) { free = Math.max(0, free); return switch (name) { case "center" -> new double[] {free / 2, 0}; case "end", "flex-end" -> new double[] {free, 0}; case "space-between" -> new double[] {0, count > 1 ? free / (count - 1) : 0}; case "space-around" -> new double[] {free / (2 * Math.max(1, count)), free / Math.max(1, count)}; case "space-evenly" -> new double[] {free / (count + 1), free / (count + 1)}; default -> new double[] {0, 0}; }; }
    private static RgbColor rgb(int color) { return new RgbColor((color >> 16 & 255) / 255.0, (color >> 8 & 255) / 255.0, (color & 255) / 255.0); }
    private static double sum(double[] values, int start, int count) { double result = 0; for (int i = start; i < start + count; i++) { result += values[i]; } return result; }
    private static void finitePositive(double value) { if (!Double.isFinite(value) || value <= 0) { throw new IllegalArgumentException("CSS content box must have finite positive dimensions"); } }
}
