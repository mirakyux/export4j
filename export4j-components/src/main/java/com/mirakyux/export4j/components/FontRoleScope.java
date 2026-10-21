// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.RegionFlow;
import com.mirakyux.export4j.model.ExecutionContext;
import java.util.List;
import java.util.Objects;

/** Selects an explicitly registered font role as the child's default font group.
 * The selected group's fallback order and missing-glyph policy remain intact. Named roles, viewport,
 * final numbering, text defaults, region flow, deferred semantics and diagnostics remain unchanged.
 * Owns no resources; immutable structure, with thread safety determined by the supplied child and fonts.
 * An unknown role fails before the child is invoked. No system font lookup or output I/O occurs.
 */
public record FontRoleScope(Component content, String fontRole) implements RegionFlow {
    public FontRoleScope {
        Objects.requireNonNull(content, "content");
        requireRole(fontRole);
    }

    @Override
    public ComponentLayout layout(ComponentContext context) {
        return selected(context).layout(context);
    }

    @Override
    public List<ComponentLayout> layoutParts(ComponentContext context) {
        return selected(context).layoutParts(context);
    }

    @Override
    public List<ComponentLayout> layoutParts(ComponentContext context, double firstPageHeight) {
        return selected(context).layoutParts(context, firstPageHeight);
    }

    @Override
    public List<RegionFlow.Fragment> layoutRegions(ComponentContext context, FlowRegions regions) {
        Objects.requireNonNull(regions, "regions");
        return selected(context).layoutRegions(context, regions);
    }

    private FontScope selected(ComponentContext context) {
        Objects.requireNonNull(context, "context");
        ExecutionContext.checkpoint();
        FontGroup fonts = context.fontRoles().get(fontRole);
        if (fonts == null) {
            throw new IllegalArgumentException("unregistered component font role");
        }
        return new FontScope(content, fonts);
    }

    private static void requireRole(String value) {
        if (Objects.requireNonNull(value, "fontRole").isBlank()) {
            throw new IllegalArgumentException("font role is blank");
        }
    }

    /** Starts task-confined construction; the role is resolved only during layout. */
    public static Builder builder() { return new Builder(); }

    /** Starts task-confined construction for a borrowed child component. */
    public static Builder builder(Component content) { return new Builder().content(content); }

    /** Starts an independent builder; immutable names and child references remain borrowed. */
    public Builder toBuilder() { return new Builder().content(content).fontRole(fontRole); }

    /** Mutable, task-confined state; build retains neither this builder nor callbacks. */
    public static final class Builder {
        private Component content;
        private String fontRole;

        private Builder() { }

        public Builder content(Component value) { content = Objects.requireNonNull(value, "content"); return this; }
        public Builder fontRole(String value) { requireRole(value); fontRole = value; return this; }
        public FontRoleScope build() { return new FontRoleScope(content, fontRole); }
    }
}
