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
        for (var spec : specs) {
            spec.template().bodySettings(spec.settings());
            placements += spec.template().background().size() + (long) spec.template().foreground().size()
                    + spec.template().header().placements().size() + spec.template().footer().placements().size();
        }
        if (placements > limits.maxPlacements()) {
            throw new IllegalArgumentException("component placement budget exceeded");
        }
        long commands = 0;
        List<DisplayPage> pages = new ArrayList<>();
        List<PageComposition.Placed> traces = new ArrayList<>();
        for (int index = 0; index < body.pages().size(); index++) {
            var spec = specs.get(index);
            var settings = spec.settings();
            var template = spec.template();
            var margins = settings.margins();
            Rect pageRegion = new Rect(0, 0, settings.size().width(), settings.size().height());
            DisplayPage source = body.pages().get(index);
            if (!source.size().equals(settings.size())) {
                throw new IllegalArgumentException("page template requires uniform page sizes");
            }
            DisplayListBuilder builder = new DisplayListBuilder(source.size());
            commands += source.commands().size();
            commands = place(builder, template.background(), pageRegion, fonts, roles, index, body.pages().size(), spec.pageNumber(), commands, traces, PageComposition.Region.BACKGROUND);
            builder.appendCommands(source.commands());
            if (template.header().height() > 0) {
                commands = place(builder, template.header().placements(), new Rect(margins.left(), margins.top(),
                        settings.contentWidth(), template.header().height()), fonts, roles, index, body.pages().size(), spec.pageNumber(), commands, traces, PageComposition.Region.HEADER);
            }
            if (template.footer().height() > 0) {
                commands = place(builder, template.footer().placements(), new Rect(margins.left(),
                        settings.size().height() - margins.bottom() - template.footer().height(),
                        settings.contentWidth(), template.footer().height()), fonts, roles, index, body.pages().size(), spec.pageNumber(), commands, traces, PageComposition.Region.FOOTER);
            }
            commands = place(builder, template.foreground(), pageRegion, fonts, roles, index, body.pages().size(), spec.pageNumber(), commands, traces, PageComposition.Region.FOREGROUND);
            if (commands > limits.maxCommands()) {
                throw new IllegalArgumentException("component command budget exceeded");
            }
            pages.add(builder.build());
        }
        return new PageComposition(new DisplayDocument(body.metadata(), pages, body.extras()), traces);
    }

    private long place(DisplayListBuilder builder, List<Placement> placements, Rect parent, FontGroup fonts, Map<String, FontGroup> roles,
            int page, int pageCount, int pageNumber, long commands, List<PageComposition.Placed> traces, PageComposition.Region region) {
        for (int index = 0; index < placements.size(); index++) {
            Placement placement = placements.get(index);
            Rect viewport = placement.position().resolve(parent);
            requireContained(viewport, parent);
            var layout = placement.component().layout(new ComponentContext(viewport.width(), viewport.height(), fonts, page, pageCount, pageNumber, roles));
            if (layout.width() > Math.nextUp(viewport.width()) || layout.height() > Math.nextUp(viewport.height())) {
                throw new IllegalArgumentException("component exceeds its viewport");
            }
            commands += layout.commands().size() + 3L;
            if (commands > limits.maxCommands()) {
                throw new IllegalArgumentException("component command budget exceeded");
            }
            builder.save().transform(AffineTransform.translation(viewport.x(), viewport.y()))
                    .appendCommands(layout.commands()).restore();
            traces.add(new PageComposition.Placed(page, region, index, viewport, layout));
        }
        return commands;
    }

    private static void requireContained(Rect child, Rect parent) {
        if (child.x() < parent.x() || child.y() < parent.y()
                || child.x() + child.width() > Math.nextUp(parent.x() + parent.width())
                || child.y() + child.height() > Math.nextUp(parent.y() + parent.height())) {
            throw new IllegalArgumentException("positioned component must stay within its region");
        }
    }
}
