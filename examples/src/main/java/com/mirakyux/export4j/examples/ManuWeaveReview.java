// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.components.ComponentDocumentLayout;
import com.mirakyux.export4j.graphics.AccessibilityTree;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.graphics.core.TextLayoutPainter;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Example-only final physical decoration; body IR coordinates remain local and unchanged. */
final class ManuWeaveReview {
    private static final int MAX_COMMANDS = 1_000_000;
    private static final int MAX_LINES = 100_000;
    private static final double NUMBER_SIZE = 7;
    private static final double NUMBER_GAP = 6;
    private static final double EPSILON = 1e-6;

    private ManuWeaveReview() { }

    static DisplayDocument decorate(ManuWeaveProfile profile, ManuWeaveFonts fonts,
            ComponentDocumentLayout layout, SectionedDocument source) {
        var display = layout.display();
        if (!display.extras().equals(DocumentExtras.EMPTY) || !display.accessibility().equals(AccessibilityTree.EMPTY)) {
            throw new IllegalArgumentException("ManuWeave physical decoration requires empty interactions and accessibility bindings");
        }
        if (layout.bodyPageOffset() != 0 || layout.body().pages().size() != display.pages().size()) {
            throw new IllegalArgumentException("ManuWeave physical decoration requires body-only final pages");
        }
        boolean mirrored = Boolean.parseBoolean(profile.facts.getProperty("page.mirror_margins", "false"));
        boolean numbered = profile.id.equals("comp-jasa-word") || profile.id.equals("comp-jslhr-word");
        if (numbered && (profile.frontColumns != 1 || profile.bodyColumns != 1)) {
            throw new IllegalArgumentException("ManuWeave review line numbers require the source single-column profiles");
        }
        var sectionByBlock = new HashMap<String, Integer>();
        for (int index = 0; index < source.sections().size(); index++) {
            for (var block : source.sections().get(index).blocks()) { sectionByBlock.put(block.id(), index); }
        }
        var pages = new ArrayList<DisplayPage>();
        int previousSection = -1;
        int displayed = 0;
        int number = 0;
        var budget = new Budget();
        for (var page : layout.body().pages()) {
            ExecutionContext.checkpoint();
            int section = section(page, sectionByBlock, previousSection);
            if (section != previousSection && source.sections().get(section).firstPageNumber() > 0) {
                displayed = source.sections().get(section).firstPageNumber();
            } else { displayed = Math.addExact(displayed, 1); }
            previousSection = section;
            double left = page.contentBounds().x();
            double right = page.size().width() - left - page.contentBounds().width();
            double shift = mirrored && displayed % 2 == 0 ? right - left : 0;
            var commands = new ArrayList<DrawCommand>(display.pages().get(page.pageIndex()).commands());
            var baselines = numbered ? baselines(page, budget) : List.<Point>of();
            for (var baseline : baselines) {
                if (++number > MAX_LINES) { throw new IllegalArgumentException("ManuWeave review line budget exceeded"); }
                var labels = new DisplayListBuilder(page.size());
                double gutter = left - NUMBER_GAP;
                if (gutter <= 0) { throw new IllegalArgumentException("ManuWeave review line-number gutter is too narrow"); }
                var text = new ParagraphLayouter().layout(Integer.toString(number), fonts.group(false, false, false), gutter,
                        ParagraphStyle.of(NUMBER_SIZE).toBuilder().lineHeight(10).alignment(TextAlignment.RIGHT).build());
                if (text.lines().size() != 1 || text.lines().getFirst().width() > gutter + EPSILON) {
                    throw new IllegalArgumentException("ManuWeave review line number does not fit its gutter");
                }
                TextLayoutPainter.draw(labels, text, new Point(0, baseline.y() - text.lines().getFirst().baselineY()),
                        new RgbColor(0.4, 0.4, 0.4));
                commands.addAll(labels.build().commands());
                String key = "effective.line-number." + number + ".";
                profile.report.setProperty(key + "page", Integer.toString(page.pageIndex() + 1));
                profile.report.setProperty(key + "x", Double.toString(text.lines().getFirst().offsetX() + shift));
                profile.report.setProperty(key + "baseline", Double.toString(baseline.y()));
            }
            if (shift != 0) {
                var shifted = new ArrayList<DrawCommand>(commands.size() + 3);
                shifted.add(new DrawCommand.Save());
                shifted.add(new DrawCommand.Transform(AffineTransform.translation(shift, 0)));
                shifted.addAll(commands);
                shifted.add(new DrawCommand.Restore());
                commands = shifted;
            }
            pages.add(new DisplayPage(page.size(), commands));
            String key = "effective.physical.page." + (page.pageIndex() + 1) + ".";
            profile.report.setProperty(key + "displayed-number", Integer.toString(displayed));
            profile.report.setProperty(key + "margin-left", Double.toString(left + shift));
            profile.report.setProperty(key + "margin-right", Double.toString(right - shift));
            profile.report.setProperty(key + "margin-top", Double.toString(page.contentBounds().y()));
            profile.report.setProperty(key + "margin-bottom", Double.toString(page.size().height()
                    - page.contentBounds().y() - page.contentBounds().height()));
            profile.report.setProperty(key + "translation-x", Double.toString(shift));
            profile.report.setProperty(key + "line-number-count", Integer.toString(baselines.size()));
        }
        profile.report.setProperty("effective.physical.page-count", Integer.toString(pages.size()));
        profile.report.setProperty("effective.mirror-margins", Boolean.toString(mirrored));
        profile.report.setProperty("effective.line-numbers", Boolean.toString(numbered));
        profile.report.setProperty("effective.line-number-count", Integer.toString(number));
        if (mirrored) {
            profile.limitations.add("Mirrored margins translate the final Display List on even displayed pages; body IR retains its original local geometry.");
        }
        if (numbered) {
            profile.limitations.add("Review line numbers follow actual body text baselines continuously in source order; semantic table cells and mathematical outlines are excluded.");
        }
        return new DisplayDocument(display.metadata(), pages, display.extras(), display.accessibility());
    }

    private static int section(LayoutPage page, HashMap<String, Integer> sections, int previous) {
        int owner = -1;
        for (var fragment : page.elements()) {
            Integer current = sections.get(fragment.blockId());
            if (current == null || owner >= 0 && current != owner) {
                throw new IllegalArgumentException("ManuWeave page must belong to one declared source section");
            }
            owner = current;
        }
        return owner < 0 ? Math.max(0, previous) : owner;
    }

    private static List<Point> baselines(LayoutPage page, Budget budget) {
        var result = new ArrayList<Point>();
        for (var fragment : page.elements()) {
            switch (fragment) {
                case ComponentFragment component -> collect(component.layout().staticCommands(),
                        AffineTransform.translation(component.origin().x(), component.origin().y()), result, budget, 0);
                case ParagraphFragment paragraph -> {
                    for (var line : paragraph.layout().lines()) {
                        add(result, new Point(paragraph.origin().x() + line.offsetX(), paragraph.origin().y() + line.baselineY()));
                    }
                }
                default -> { /* Semantic table text has its own row/cell geometry, not manuscript line numbers. */ }
            }
        }
        return List.copyOf(result);
    }

    private static void collect(List<DrawCommand> commands, AffineTransform initial, List<Point> lines,
            Budget budget, int depth) {
        if (depth > 64) { throw new IllegalArgumentException("ManuWeave review group depth exceeded"); }
        var stack = new ArrayDeque<AffineTransform>();
        var current = initial;
        for (var command : commands) {
            ExecutionContext.checkpoint();
            if (++budget.commands > MAX_COMMANDS) { throw new IllegalArgumentException("ManuWeave review command budget exceeded"); }
            switch (command) {
                case DrawCommand.Save ignored -> stack.push(current);
                case DrawCommand.Restore ignored -> current = stack.pop();
                case DrawCommand.Transform transform -> current = concatenate(current, transform.matrix());
                case DrawCommand.GlyphText text -> { if (!text.run().text().isBlank()) { add(lines, apply(current, text.baseline())); } }
                case DrawCommand.Text text -> { if (!text.text().isBlank()) { add(lines, apply(current, text.baseline())); } }
                case DrawCommand.Group group -> collect(group.commands(), current, lines, budget, depth + 1);
                default -> { /* Outlined mathematical content has no manuscript text baseline. */ }
            }
        }
    }

    private static void add(List<Point> lines, Point baseline) {
        if (lines.isEmpty() || Math.abs(lines.getLast().y() - baseline.y()) > EPSILON) { lines.add(baseline); }
    }

    private static Point apply(AffineTransform matrix, Point point) {
        return new Point(matrix.a() * point.x() + matrix.c() * point.y() + matrix.e(),
                matrix.b() * point.x() + matrix.d() * point.y() + matrix.f());
    }

    private static AffineTransform concatenate(AffineTransform current, AffineTransform local) {
        return new AffineTransform(current.a() * local.a() + current.c() * local.b(),
                current.b() * local.a() + current.d() * local.b(),
                current.a() * local.c() + current.c() * local.d(),
                current.b() * local.c() + current.d() * local.d(),
                current.a() * local.e() + current.c() * local.f() + current.e(),
                current.b() * local.e() + current.d() * local.f() + current.f());
    }

    private static final class Budget { int commands; }
}
