// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.SemanticPlacement;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.ComponentSemantics;
import com.mirakyux.export4j.layout.core.PageableComponent;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.TextDiagnostic;

/** Sequential top-to-bottom, left-to-right columns. Starts on a fresh page when the remaining page is shorter.
 * Child flow decides complete-line fragmentation; columns are not balanced. */
public record MultiColumn(PageableComponent content, int columns, double gap, boolean balanced) implements PageableComponent {
    public MultiColumn {
        Objects.requireNonNull(content, "content");
        if (columns < 2 || columns > 16 || !Double.isFinite(gap) || gap < 0) { throw new IllegalArgumentException("invalid columns or gap"); }
    }

    public MultiColumn(PageableComponent content, int columns, double gap) { this(content, columns, gap, false); }

    @Override
    public List<ComponentLayout> layoutParts(ComponentContext context) {
        double width = (context.width() - gap * (columns - 1)) / columns;
        var fragments = content.layoutParts(context.viewport(width, context.height()));
        if (fragments.isEmpty() || fragments.size() > 100_000) { throw new IllegalArgumentException("invalid column fragment count"); }
        if (balanced) {
            int pages = (fragments.size() + columns - 1) / columns;
            double low = 0;
            double high = context.height();
            for (int attempt = 0; attempt < 24; attempt++) {
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                double candidate = (low + high) / 2;
                List<ComponentLayout> trial;
                try { trial = content.layoutParts(context.viewport(width, candidate)); }
                catch (IllegalArgumentException tooShort) { low = candidate; continue; }
                if (!trial.isEmpty() && trial.size() <= pages * columns) { high = candidate; fragments = trial; }
                else { low = candidate; }
            }
        }
        var result = new ArrayList<ComponentLayout>();
        for (int first = 0; first < fragments.size(); first += columns) {
            var builder = new DisplayListBuilder(new PageSize(context.width(), context.height()));
            int lines = 0;
            double height = 0;
            List<TextDiagnostic> diagnostics = new ArrayList<>();
            List<SemanticPlacement> placements = new ArrayList<>();
            for (int column = 0; column < columns && first + column < fragments.size(); column++) {
                var fragment = fragments.get(first + column);
                ComponentSupport.fits(fragment.width(), fragment.height(), context.viewport(width, context.height()));
                builder.save().transform(AffineTransform.translation(column * (width + gap), 0));
                ComponentSemantics.append(builder, placements, fragment);
                builder.restore();
                lines = Math.addExact(lines, fragment.textLines());
                diagnostics.addAll(fragment.diagnostics());
                height = Math.max(height, fragment.height());
            }
            // Full viewport reserves the same column height even on the last page.
            result.add(new ComponentLayout(context.width(), balanced && first + columns >= fragments.size() ? height : context.height(), lines, builder.build().commands(), diagnostics, placements));
        }
        return List.copyOf(result);
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    public static Builder builder(PageableComponent content) { return new Builder().content(content); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().content(content()).columns(columns()).gap(gap()).balanced(balanced()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private PageableComponent content = null;
        private int columns = 2;
        private double gap = 12;
        private boolean balanced = false;

        private Builder() { }
        public Builder content(PageableComponent value) { content = java.util.Objects.requireNonNull(value, "content"); return this; }
        public Builder columns(int value) { columns = value; return this; }
        public Builder gap(double value) { gap = value; return this; }
        public Builder balanced(boolean value) { balanced = value; return this; }

        public MultiColumn build() { return new MultiColumn(content, columns, gap, balanced); }
    }
}
