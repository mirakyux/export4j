// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Reusable explicit default fonts and named roles. The structural snapshot is immutable;
 * supplied faces retain their own thread-safety contract. No fonts are discovered or synthesized.
 * The standard variant keys match the HTML/Markdown importers: bold, italic and boldItalic.
 */
public record FontSet(FontGroup regular, Map<String, FontGroup> roles) {
    public FontSet {
        Objects.requireNonNull(regular, "regular");
        roles = Map.copyOf(roles);
        for (var role : roles.keySet()) { requireRole(role); }
    }

    /** Starts task-confined configuration from an explicit default font group. */
    public static Builder builder(FontGroup regular) { return new Builder(regular); }

    /** Mutable task-confined configuration; build takes a structural snapshot. */
    public static final class Builder {
        private final FontGroup regular;
        private final Map<String, FontGroup> roles = new HashMap<>();

        private Builder(FontGroup regular) { this.regular = Objects.requireNonNull(regular, "regular"); }

        /** Registers the actual bold variant; it is never synthesized from regular. */
        public Builder bold(FontGroup value) { return role("bold", value); }
        /** Registers the actual italic variant; it is never synthesized from regular. */
        public Builder italic(FontGroup value) { return role("italic", value); }
        /** Registers the actual combined variant under the standard boldItalic role. */
        public Builder boldItalic(FontGroup value) { return role("boldItalic", value); }

        /** Registers one nonblank named role. Duplicate names fail without replacing prior values. */
        public Builder role(String name, FontGroup value) {
            requireRole(name);
            Objects.requireNonNull(value, "font group");
            if (roles.putIfAbsent(name, value) != null) { throw new IllegalArgumentException("duplicate font role"); }
            return this;
        }

        public FontSet build() { return new FontSet(regular, roles); }
    }

    private static void requireRole(String name) {
        if (Objects.requireNonNull(name, "role").isBlank()) { throw new IllegalArgumentException("font role is blank"); }
    }
}
