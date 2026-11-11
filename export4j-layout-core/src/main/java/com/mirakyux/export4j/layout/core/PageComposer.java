// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.graphics.core.GraphicsResources;
import com.mirakyux.export4j.model.PageSettings;

/** Composes repeating page regions after final pagination. Core depends only on its component SPI. */
public final class PageComposer {
    /** Resolved per-page composition and displayed section number. */
    public record PageSpec(PageSettings settings, PageTemplate template, int pageNumber) {
        public PageSpec {
            Objects.requireNonNull(settings, "settings");
            Objects.requireNonNull(template, "template");
            if (pageNumber <= 0) {
                throw new IllegalArgumentException("page number must be positive");
            }
        }
    }

    /** One composed page and its immutable placement diagnostics. Declared placements include
     * unselected page-specific decorations, matching the whole-document composition budget.
     */
    public record ComposedPage(DisplayPage page, List<PageComposition.Placed> placements, long declaredPlacements) {
        public ComposedPage {
            Objects.requireNonNull(page, "page");
            placements = List.copyOf(placements);
            if (declaredPlacements < 0) { throw new IllegalArgumentException("negative declared placement count"); }
        }
    }

    private record ComposedCommands(DisplayPage page, long commands) { }
    private final ComponentLimits limits;

    public PageComposer() {
        this(ComponentLimits.DEFAULT);
    }

    public PageComposer(ComponentLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * Body must already reserve template bands through bodySettings(). Preserves resolved interactions.
     * Out-of-region placements fail; page fields receive final page count. No I/O or backend objects.
     */
    public DisplayDocument compose(DisplayDocument body, PageSettings settings, PageTemplate template, List<FontFace> fonts) {
        return composeDetailed(body, settings, template, fonts).display();
    }

    /** Like compose(), also retains region/placement indices and all component diagnostics. */
    public PageComposition composeDetailed(DisplayDocument body, PageSettings settings, PageTemplate template, List<FontFace> fonts) {
        List<PageSpec> specs = new ArrayList<>();
        for (int index = 0; index < body.pages().size(); index++) {
            specs.add(new PageSpec(settings, template, index + 1));
        }
        return composeDetailed(body, specs, fonts);
    }

    /** Composes mixed page sizes and section variants using final numbering. */
    public PageComposition composeDetailed(DisplayDocument body, List<PageSpec> specs, List<FontFace> fonts) {
        return composeDetailed(body, specs, FontGroup.strict(fonts));
    }

    /** Composes final page regions without named font roles, preserving compatibility defaults. */
    public PageComposition composeDetailed(DisplayDocument body, List<PageSpec> specs, FontGroup fonts) {
        return composeDetailed(body, specs, fonts, Map.of());
    }

    /** Composes one final body page without retaining preceding pages. Dimensions use points.
     * The zero-based physical index selects positioned decorations; the displayed number comes from
     * the specification, and fields receive the positive final physical page count. Inputs remain borrowed,
     * roles are snapshotted, and no I/O occurs. The body must already reserve template bands.
     * This call enforces per-page budgets. A streaming caller must additionally accumulate declared
     * placements and emitted commands, reject inaccessible decoration bindings, and verify that every
     * explicitly selected placement occurs in its declaring section or page variant across the final plan.
     */
    public ComposedPage composePageDetailed(DisplayPage source, PageSpec spec, FontGroup fonts,
            Map<String, FontGroup> roles, int physicalPageIndex, int finalPages) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(fonts, "fonts");
        roles = Map.copyOf(roles);
        if (finalPages <= 0 || physicalPageIndex < 0 || physicalPageIndex >= finalPages) {
            throw new IllegalArgumentException("physical page index must be within the final page count");
        }
        spec.template().bodySettings(spec.settings());
        long declared = declaredPlacements(spec.template());
        if (declared > limits.maxPlacements()) {
            throw new IllegalArgumentException("component placement budget exceeded");
        }
        var traces = new ArrayList<PageComposition.Placed>();
        var result = composeSingle(source, spec, fonts, roles, physicalPageIndex, finalPages, 0, traces);
        return new ComposedPage(result.page(), traces, declared);
    }

    /** Composes final page regions with explicit, named font groups and their missing-glyph policies.
     * Specifications and roles are snapshotted before any component is invoked. All dimensions use points;
     * fonts remain borrowed and are never closed. Custom components own their thread-safety contract.
     * Null arguments, role keys or role values are rejected. Deferred semantic content remains unsupported.
     */
    public PageComposition composeDetailed(DisplayDocument body, List<PageSpec> specs, FontGroup fonts,
            Map<String, FontGroup> roles) {
        Objects.requireNonNull(body, "body");
        specs = List.copyOf(specs);
        Objects.requireNonNull(fonts, "fonts");
        roles = Map.copyOf(roles);
        if (specs.size() != body.pages().size()) {
            throw new IllegalArgumentException("one composition specification is required per page");
        }
        long placements = 0;
        var selected = new java.util.HashSet<Placement>();
        var rendered = new java.util.HashSet<Placement>();
        int physicalPage = 0;
        for (var spec : specs) {
            physicalPage++;
            for (var placement : java.util.stream.Stream.concat(spec.template().background().stream(), spec.template().foreground().stream()).toList()) {
                if (placement.page() > 0) { selected.add(placement); if (placement.page() == physicalPage) { rendered.add(placement); } }
            }
            spec.template().bodySettings(spec.settings());
            placements += declaredPlacements(spec.template());
        }
        if (!rendered.containsAll(selected)) { throw new IllegalArgumentException("positioned page is outside its declaring section or selected page variant"); }
        if (placements > limits.maxPlacements()) {
            throw new IllegalArgumentException("component placement budget exceeded");
        }
        if (placements > 0 && !body.accessibility().children().isEmpty()) {
            throw new IllegalArgumentException("accessible page decoration requires explicit final command bindings");
        }
        long commands = 0;
        List<DisplayPage> pages = new ArrayList<>();
        List<PageComposition.Placed> traces = new ArrayList<>();
        for (int index = 0; index < body.pages().size(); index++) {
            var result = composeSingle(body.pages().get(index), specs.get(index), fonts, roles, index,
                    body.pages().size(), commands, traces);
            commands = result.commands();
            pages.add(result.page());
        }
        return new PageComposition(new DisplayDocument(body.metadata(), pages, body.extras(), body.accessibility()), traces);
    }

    private static long declaredPlacements(PageTemplate template) {
        return template.background().size() + (long) template.foreground().size()
                + template.header().placements().size() + template.footer().placements().size();
    }

    private ComposedCommands composeSingle(DisplayPage source, PageSpec spec, FontGroup fonts,
            Map<String, FontGroup> roles, int index, int finalPages, long commands,
            List<PageComposition.Placed> traces) {
        var settings = spec.settings();
        var template = spec.template();
        Rect pageRegion = new Rect(0, 0, settings.size().width(), settings.size().height());
        if (!source.size().equals(settings.size())) {
            throw new IllegalArgumentException("page template requires uniform page sizes");
        }
        DisplayListBuilder builder = new DisplayListBuilder(source.size());
        commands = countCommands(source.commands(), commands);
        commands = place(builder, template.background(), pageRegion, fonts, roles, index, finalPages,
                spec.pageNumber(), commands, traces, PageComposition.Region.BACKGROUND);
        builder.appendCommands(source.commands());
        if (template.header().height() > 0) {
            commands = place(builder, template.header().placements(), bandViewport(template.header(), settings, true),
                    fonts, roles, index, finalPages, spec.pageNumber(), commands, traces, PageComposition.Region.HEADER);
        }
        if (template.footer().height() > 0) {
            commands = place(builder, template.footer().placements(), bandViewport(template.footer(), settings, false),
                    fonts, roles, index, finalPages, spec.pageNumber(), commands, traces, PageComposition.Region.FOOTER);
        }
        commands = place(builder, template.foreground(), pageRegion, fonts, roles, index, finalPages,
                spec.pageNumber(), commands, traces, PageComposition.Region.FOREGROUND);
        if (commands > limits.maxCommands()) {
            throw new IllegalArgumentException("component command budget exceeded");
        }
        return new ComposedCommands(builder.build(), commands);
    }

    private static Rect bandViewport(PageBand band, com.mirakyux.export4j.model.PageSettings settings, boolean header) {
        var margins = settings.margins();
        double left = band.leftInset() < 0 ? margins.left() : band.leftInset();
        double right = band.rightInset() < 0 ? margins.right() : band.rightInset();
        double edge = band.edgeOffset() < 0 ? (header ? margins.top() : margins.bottom()) : band.edgeOffset();
        double width = settings.size().width() - left - right;
        double y = header ? edge : settings.size().height() - edge - band.height();
        if (width <= 0) { throw new IllegalArgumentException("page band insets exhaust page width"); }
        var viewport = new Rect(left, y, width, band.height());
        requireContained(viewport, new Rect(0, 0, settings.size().width(), settings.size().height()));
        return viewport;
    }

    private long place(DisplayListBuilder builder, List<Placement> placements, Rect parent, FontGroup fonts, Map<String, FontGroup> roles,
            int page, int pageCount, int pageNumber, long commands, List<PageComposition.Placed> traces, PageComposition.Region region) {
        for (int index = 0; index < placements.size(); index++) {
            Placement placement = placements.get(index);
            if (placement.page() > pageCount) { throw new IllegalArgumentException("placement page exceeds final page count"); }
            if (placement.page() != 0 && placement.page() != page + 1) { continue; }
            Rect viewport = placement.position().resolve(parent);
            requireContained(viewport, parent);
            var layout = placement.component().layout(new ComponentContext(viewport.width(), viewport.height(), fonts, page, pageCount, pageNumber, roles));
            if (layout.width() > Math.nextUp(viewport.width()) || layout.height() > Math.nextUp(viewport.height())) {
                throw new IllegalArgumentException("component exceeds its viewport");
            }
            commands = countCommands(layout.commands(), commands + 3L);
            if (commands > limits.maxCommands()) {
                throw new IllegalArgumentException("component command budget exceeded");
            }
            builder.save().transform(AffineTransform.translation(viewport.x(), viewport.y()))
                    .appendCommands(layout.commands()).restore();
            traces.add(new PageComposition.Placed(page, region, index, viewport, layout));
        }
        return commands;
    }

    private long countCommands(List<com.mirakyux.export4j.graphics.DrawCommand> source, long previous) {
        long[] count = {previous};
        GraphicsResources.walk(source, command -> {
            if (++count[0] > limits.maxCommands()) {
                throw new IllegalArgumentException("component command budget exceeded");
            }
        });
        return count[0];
    }

    private static void requireContained(Rect child, Rect parent) {
        if (child.x() < parent.x() || child.y() < parent.y()
                || child.x() + child.width() > Math.nextUp(parent.x() + parent.width())
                || child.y() + child.height() > Math.nextUp(parent.y() + parent.height())) {
            throw new IllegalArgumentException("positioned component must stay within its region");
        }
    }
}
