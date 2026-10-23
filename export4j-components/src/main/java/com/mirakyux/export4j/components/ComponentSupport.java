// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.List;
import java.util.ArrayList;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.RegionFlow;
import com.mirakyux.export4j.layout.core.WidthAwareRegionFlow;
import com.mirakyux.export4j.layout.core.PageableComponent;
import com.mirakyux.export4j.model.ExecutionContext;

final class ComponentSupport {
    private ComponentSupport() {
    }

    static void firstPageHeight(double height, ComponentContext context) {
        if (!Double.isFinite(height) || height < 0 || height > Math.nextUp(context.height())) {
            throw new IllegalArgumentException("first-page height must be within the fresh-page viewport");
        }
    }

    static void fits(double width, double height, ComponentContext context) {
        if (width > Math.nextUp(context.width()) || height > Math.nextUp(context.height())) {
            throw new IllegalArgumentException("component exceeds available viewport");
        }
    }

    /** Assigns legacy atomic fragments without introducing a full-width container. */
    static List<RegionFlow.Fragment> atomicRegions(Component content, ComponentContext context, FlowRegions regions) {
        fits(context.width(), regions.continuationHeight(), context);
        var viewport = context.viewport(context.width(), regions.continuationHeight());
        List<ComponentLayout> layouts = List.copyOf(content instanceof PageableComponent pageable
                ? pageable.layoutParts(viewport, regions.height(0)) : List.of(content.layout(viewport)));
        if (layouts.isEmpty() || layouts.size() > 100_000) { throw new IllegalArgumentException("invalid atomic fragment count"); }
        var result = new ArrayList<RegionFlow.Fragment>();
        int region = 0;
        for (var layout : layouts) {
            ExecutionContext.checkpoint();
            fits(layout.width(), layout.height(), viewport);
            while (layout.height() > Math.nextUp(regions.height(region))) {
                if (region >= regions.initialHeights().size()) {
                    throw new IllegalArgumentException("atomic child exceeds continuation region");
                }
                region++;
            }
            result.add(new RegionFlow.Fragment(region++, layout));
        }
        return regionFragments(result, context, regions);
    }

    static List<RegionFlow.Fragment> regionFragments(List<RegionFlow.Fragment> values, ComponentContext context, FlowRegions regions) {
        var fragments = List.copyOf(values);
        if (fragments.isEmpty() || fragments.size() > 100_000) { throw new IllegalArgumentException("invalid region fragment count"); }
        int previous = -1;
        for (var fragment : fragments) {
            ExecutionContext.checkpoint();
            int region = fragment.regionIndex();
            if (region <= previous || region > previous + 1 && region > regions.initialHeights().size()) {
                throw new IllegalArgumentException("regions must be ordered with no skipped continuation regions");
            }
            fits(fragment.layout().width(), fragment.layout().height(), context.viewport(context.width(), regions.height(region)));
            previous = region;
        }
        return fragments;
    }

    static List<RegionFlow.Fragment> variableRegions(Component content, ComponentContext context, FlowRegions regions, List<Double> widths) {
        widths = variableWidths(context, regions, widths);
        List<RegionFlow.Fragment> values;
        if (content instanceof WidthAwareRegionFlow flow) {
            values = flow.layoutVariableRegions(context, regions, widths);
        } else if (content instanceof PageableComponent) {
            throw new IllegalArgumentException("pageable child does not support variable region widths");
        } else {
            int region = 0;
            while (true) {
                ExecutionContext.checkpoint();
                double width = widths.get(region % widths.size());
                var layout = content.layout(context.viewport(width, regions.continuationHeight()));
                fits(layout.width(), layout.height(), context.viewport(width, regions.continuationHeight()));
                if (layout.height() <= Math.nextUp(regions.height(region))) {
                    values = List.of(new RegionFlow.Fragment(region, layout)); break;
                }
                if (region >= regions.initialHeights().size()) { throw new IllegalArgumentException("atomic child exceeds continuation region"); }
                region++;
            }
        }
        var result = regionFragments(values, context, regions);
        for (var fragment : result) {
            fits(fragment.layout().width(), fragment.layout().height(), context.viewport(
                    widths.get(fragment.regionIndex() % widths.size()), regions.height(fragment.regionIndex())));
        }
        return result;
    }

    static List<Double> variableWidths(ComponentContext context, FlowRegions regions, List<Double> widths) {
        fits(context.width(), regions.continuationHeight(), context);
        var snapshot = List.copyOf(widths);
        if (snapshot.isEmpty() || snapshot.size() > 16) { throw new IllegalArgumentException("invalid variable region width count"); }
        for (double width : snapshot) {
            if (!Double.isFinite(width) || width <= 0 || width > Math.nextUp(context.width())) {
                throw new IllegalArgumentException("variable region width must fit the viewport");
            }
        }
        return snapshot;
    }
}
