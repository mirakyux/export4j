// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.SemanticPlacement;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.RegionFlow;
import com.mirakyux.export4j.layout.core.WidthAwareRegionFlow;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.text.ParagraphLayouter;

/**
 * A semantic table that continues across component regions. All dimensions are points.
 * Retains source cells, explicit font policy, fields and hyperlinks until final document pagination.
 * Use with DocumentEngine or ComponentDocumentRenderer; raw commands cannot represent unresolved semantics.
 * Region flow repeats headers and preserves complete rows, or complete cell lines when row splitting is explicit.
 * Direct {@link #layout(ComponentContext)} still requires one fixed viewport. Outer spacing belongs to the parent.
 * Its ID must be unique among all document blocks and nested semantic tables. Immutable; owns no resources.
 */
public record SemanticTable(Table table) implements WidthAwareRegionFlow {
    public SemanticTable { Objects.requireNonNull(table, "table"); }

    @Override
    public ComponentLayout layout(ComponentContext context) {
        var defaults = DocumentLayoutLimits.DEFAULT;
        var limits = new DocumentLayoutLimits(defaults.maxParagraphs(), defaults.maxCodePoints(), defaults.maxLines(), 1,
                defaults.maxTableRows(), defaults.maxTableColumns(), defaults.maxCells());
        return layoutRegions(context, new FlowRegions(List.of(), context.height()), limits).getFirst().layout();
    }

    /**
     * Measures at the supplied width once and returns immutable, locally positioned semantic fragments.
     * Short initial regions may be skipped; full continuation regions must consume body content.
     * The default document limits apply per table: source cells/code points once, output lines including
     * repeated headers, and at most 1,000 occupied regions. The enclosing document also checks shared limits.
     * Fields remain unresolved until final pagination; caller-owned fonts and resources are never closed.
     */
    @Override
    public List<RegionFlow.Fragment> layoutRegions(ComponentContext context, FlowRegions regions) {
        return layoutRegions(context, regions, DocumentLayoutLimits.DEFAULT);
    }

    private List<RegionFlow.Fragment> layoutRegions(ComponentContext context, FlowRegions regions,
            DocumentLayoutLimits limits) {
        var measured = new DocumentLayouter(new ParagraphLayouter(), limits).layoutTableRegions(table, context, regions);
        return fragments(context, measured);
    }

    /**
     * Reflows complete rows/groups at their actual cyclic region widths. Explicit row splitting
     * also supports inline cells across unequal widths; split vertical spans/block cells remain unsupported.
     * Preparation is bounded to 16 widths and 4,000,000 cumulative source UTF-16 units, including split-cell work.
     * Source identity, fields, links and font policy are retained for final pagination.
     */
    @Override
    public List<RegionFlow.Fragment> layoutVariableRegions(ComponentContext context, FlowRegions regions, List<Double> widths) {
        widths = ComponentSupport.variableWidths(context, regions, widths);
        var measured = new DocumentLayouter().layoutTableRegions(table, context, regions, widths);
        return fragments(context, measured);
    }

    private List<RegionFlow.Fragment> fragments(ComponentContext context, List<DocumentLayouter.TableRegion> measured) {
        List<RegionFlow.Fragment> result = new ArrayList<>();
        for (int index = 0; index < measured.size(); index++) {
            com.mirakyux.export4j.model.ExecutionContext.checkpoint();
            var assigned = measured.get(index);
            TableFragment fragment = assigned.fragment();
            int lines = Math.toIntExact(fragment.rows().stream().flatMap(row -> row.cells().stream())
                    .mapToLong(com.mirakyux.export4j.layout.CellFragment::lineCount).sum());
            var layout = new ComponentLayout(fragment.width(), fragment.height(), lines, List.of(), List.of(),
                    List.of(new SemanticPlacement(0, table, fragment, context.fontGroup(), context.fontRoles(), index, measured.size())));
            result.add(new RegionFlow.Fragment(assigned.regionIndex(), layout));
        }
        return List.copyOf(result);
    }
}
