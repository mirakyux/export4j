// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** Immutable named styles, resolved explicitly by the caller; unknown names fail. */
public record StyleSheet(Map<String, ParagraphFormat> paragraphs, Map<String, InlineStyle> inlines) {
    /** Inheritance is resolved once at build time; unknown parents and cycles fail. */
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final Definitions<ParagraphFormat> paragraphs = new Definitions<>();
        private final Definitions<InlineStyle> inlines = new Definitions<>();

        public Builder paragraph(String name, ParagraphFormat value) {
            paragraphs.add(name, "", ignored -> value);
            return this;
        }

        public Builder paragraph(String name, String parent, UnaryOperator<ParagraphFormat> overrides) {
            paragraphs.add(name, parent, overrides);
            return this;
        }

        public Builder inline(String name, InlineStyle value) {
            inlines.add(name, "", ignored -> value);
            return this;
        }

        public Builder inline(String name, String parent, UnaryOperator<InlineStyle> overrides) {
            inlines.add(name, parent, overrides);
            return this;
        }

        public StyleSheet build() { return new StyleSheet(paragraphs.resolve(), inlines.resolve()); }
    }

    private record Definition<T>(String parent, UnaryOperator<T> overrides) { }

    private static final class Definitions<T> {
        private final Map<String, Definition<T>> definitions = new HashMap<>();

        void add(String name, String parent, UnaryOperator<T> overrides) {
            if (Objects.requireNonNull(name, "name").isBlank()) {
                throw new IllegalArgumentException("style name is blank");
            }
            var definition = new Definition<>(Objects.requireNonNull(parent, "parent"), Objects.requireNonNull(overrides, "overrides"));
            if (definitions.putIfAbsent(name, definition) != null) {
                throw new IllegalArgumentException("duplicate style: " + name);
            }
        }

        Map<String, T> resolve() {
            Map<String, T> result = new HashMap<>();
            for (String name : definitions.keySet()) { resolve(name, result, new HashSet<>()); }
            return result;
        }

        T resolve(String name, Map<String, T> result, java.util.Set<String> visiting) {
            if (result.containsKey(name)) { return result.get(name); }
            var definition = definitions.get(name);
            if (definition == null || !visiting.add(name)) {
                throw new IllegalArgumentException("unknown parent or cyclic style: " + name);
            }
            T parent = definition.parent().isEmpty() ? null : resolve(definition.parent(), result, visiting);
            T value = Objects.requireNonNull(definition.overrides().apply(parent), "resolved style");
            result.put(name, value);
            visiting.remove(name);
            return value;
        }
    }
    public StyleSheet {
        paragraphs = Map.copyOf(paragraphs);
        inlines = Map.copyOf(inlines);
    }

    public ParagraphFormat paragraph(String name) {
        var value = paragraphs.get(name);
        if (value == null) {
            throw new IllegalArgumentException("unknown paragraph style: " + name);
        }
        return value;
    }

    public InlineStyle inline(String name) {
        var value = inlines.get(name);
        if (value == null) {
            throw new IllegalArgumentException("unknown inline style: " + name);
        }
        return value;
    }
}
