// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immutable, thread-safe default action and per-feature overrides. Default is ERROR. */
public record ExportPolicy(UnsupportedAction defaultAction, Map<ExportFeature, UnsupportedAction> overrides) {
    public static final ExportPolicy STRICT = new ExportPolicy(UnsupportedAction.ERROR, Map.of());
    public ExportPolicy {
        Objects.requireNonNull(defaultAction, "defaultAction");
        overrides = Map.copyOf(overrides);
    }
    public UnsupportedAction action(ExportFeature feature) {
        return overrides.getOrDefault(Objects.requireNonNull(feature, "feature"), defaultAction);
    }
    public static Builder builder() { return new Builder(); }
    /** Task-confined builder; build snapshots every override. */
    public static final class Builder {
        private UnsupportedAction defaultAction = UnsupportedAction.ERROR;
        private final Map<ExportFeature, UnsupportedAction> overrides = new EnumMap<>(ExportFeature.class);
        private Builder() { }
        public Builder defaultAction(UnsupportedAction value) { defaultAction = Objects.requireNonNull(value, "defaultAction"); return this; }
        public Builder on(ExportFeature feature, UnsupportedAction action) {
            overrides.put(Objects.requireNonNull(feature, "feature"), Objects.requireNonNull(action, "action")); return this;
        }
        public ExportPolicy build() { return new ExportPolicy(defaultAction, overrides); }
    }
}
