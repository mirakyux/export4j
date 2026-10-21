// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.SemanticPlacement;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.RegionFlow;
import com.mirakyux.export4j.layout.core.SemanticSourceRegistry;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.Table;

/** Finalizes only streams prepared by this grid; cached header presentations may repeat their exact stream. */
final class GridSemanticStreams {
    private static final int LIMIT = 100_000;
    private final Map<String, Source> sources = new LinkedHashMap<>();
    private int prototypeCount;

    final class Source {
        private final Table table;
        private final FontGroup fonts;
        private final Map<String, FontGroup> roles;
        private final boolean header;
        private final List<SemanticPlacement> prototypes = new ArrayList<>();
        private int presentations;
        private int emitted;

        private Source(Table table, FontGroup fonts, Map<String, FontGroup> roles, boolean header) {
            this.table = table; this.fonts = fonts; this.roles = roles; this.header = header;
        }

        SemanticPlacement append(TableFragment fragment) {
            var placement = new SemanticPlacement(0, table, fragment, fonts, roles, prototypes.size(), LIMIT);
            record(placement);
            return placement;
        }

        private void record(SemanticPlacement placement) {
            if (++prototypeCount > LIMIT) { throw new IllegalArgumentException("grid semantic preparation budget exceeded"); }
            prototypes.add(placement);
        }

        private void accept(SemanticPlacement placement) {
            if (prototypes.isEmpty() || presentations >= LIMIT) {
                throw new IllegalArgumentException("invalid or excessive grid semantic presentations");
            }
            var expected = prototypes.get(presentations % prototypes.size());
            if ((!header && presentations >= prototypes.size()) || placement.source() != table
                    || placement.fragment() != expected.fragment() || !placement.fonts().equals(fonts)
                    || !placement.roles().equals(roles) || placement.fragmentIndex() != expected.fragmentIndex()
                    || placement.fragmentCount() != expected.fragmentCount()) {
                throw new IllegalArgumentException("grid semantic source must preserve its prepared continuation order");
            }
            presentations++;
        }

        private void complete() {
            if (presentations == 0 || presentations % prototypes.size() != 0
                    || !header && presentations != prototypes.size()) {
                throw new IllegalArgumentException("grid semantic source stream is incomplete");
            }
        }

        private SemanticPlacement finalizePlacement(SemanticPlacement placement) {
            ExecutionContext.checkpoint();
            return new SemanticPlacement(placement.commandIndex(), table, placement.fragment(), fonts, roles,
                    emitted++, presentations);
        }
    }

    Source register(Table table, ComponentContext context, boolean header) {
        return register(table, context.fontGroup(), context.fontRoles(), header);
    }

    private Source register(Table table, FontGroup fonts, Map<String, FontGroup> roles, boolean header) {
        if (header && table.hasBlockCells()) {
            throw new IllegalArgumentException("cached grid headers do not support semantic block-cell trees");
        }
        if (sources.size() >= LIMIT || sources.containsKey(table.id())) {
            throw new IllegalArgumentException("grid semantic source IDs must be unique across independently prepared content");
        }
        var source = new Source(table, fonts, roles, header);
        sources.put(table.id(), source);
        return source;
    }

    void registerFixed(ComponentLayout layout, boolean header) {
        var registry = new SemanticSourceRegistry(List.of());
        for (var placement : layout.semanticPlacements()) {
            ExecutionContext.checkpoint();
            if (registry.accept("grid-cell", placement)) {
                register(placement.source(), placement.fonts(), placement.roles(), header);
            }
            sources.get(placement.source().id()).record(placement);
        }
        registry.sources();
    }

    List<RegionFlow.Fragment> finish(List<RegionFlow.Fragment> fragments) {
        if (sources.isEmpty()) { return List.copyOf(fragments); }
        int count = 0;
        for (var fragment : fragments) {
            for (var placement : fragment.layout().semanticPlacements()) {
                ExecutionContext.checkpoint();
                if (++count > LIMIT) { throw new IllegalArgumentException("grid semantic presentation budget exceeded"); }
                var source = sources.get(placement.source().id());
                if (source == null) { throw new IllegalArgumentException("grid contains an unprepared semantic source"); }
                source.accept(placement);
            }
        }
        sources.values().forEach(Source::complete);
        List<RegionFlow.Fragment> result = new ArrayList<>();
        for (var fragment : fragments) {
            ExecutionContext.checkpoint();
            var layout = fragment.layout();
            var placements = layout.semanticPlacements().stream()
                    .map(value -> sources.get(value.source().id()).finalizePlacement(value)).toList();
            result.add(new RegionFlow.Fragment(fragment.regionIndex(), new ComponentLayout(layout.width(), layout.height(),
                    layout.textLines(), layout.staticCommands(), layout.diagnostics(), placements)));
        }
        return List.copyOf(result);
    }
}
