// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model.processing;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable unsupported-feature policy. Precedence: operation, feature, format, default.
 * Keys are stable API identifiers, not input text. Invalid input and execution failures never use this policy.
 */
public final class ProcessingPolicy {
    public enum Action { ERROR, WARN, IGNORE }
    /** Treatment is independent of warning visibility. PRESERVE requires a verified preservation path. */
    public enum Treatment { SKIP_MATCH, SKIP_BLOCK, SKIP_REQUEST, DEGRADE, DISCARD, PRESERVE }
    public record Rule(Action action, Treatment treatment) {
        public Rule { Objects.requireNonNull(action, "action"); Objects.requireNonNull(treatment, "treatment"); }
    }
    public static final ProcessingPolicy STRICT = builder().build();
    private final Rule defaultRule;
    private final Map<String, Rule> formats;
    private final Map<String, Rule> features;
    private final Map<String, Rule> operations;

    private ProcessingPolicy(Builder builder) {
        defaultRule = builder.defaultRule;
        formats = Map.copyOf(builder.formats);
        features = Map.copyOf(builder.features);
        operations = Map.copyOf(builder.operations);
    }
    public Rule resolve(String format, String feature, String operation) {
        return operations.getOrDefault(key(operation), features.getOrDefault(key(feature),
                formats.getOrDefault(key(format), defaultRule)));
    }
    private static String key(String value) {
        if (Objects.requireNonNull(value, "key").isBlank()) { throw new IllegalArgumentException("empty policy key"); }
        return value;
    }
    public static Builder builder() { return new Builder(); }
    /** Mutable task-confined construction state; build snapshots all mappings. */
    public static final class Builder {
        private Rule defaultRule = new Rule(Action.ERROR, Treatment.SKIP_MATCH);
        private final Map<String, Rule> formats = new HashMap<>();
        private final Map<String, Rule> features = new HashMap<>();
        private final Map<String, Rule> operations = new HashMap<>();
        private Builder() { }
        public Builder defaultRule(Action action, Treatment treatment) { defaultRule = new Rule(action, treatment); return this; }
        public Builder format(String format, Action action, Treatment treatment) { formats.put(key(format), new Rule(action, treatment)); return this; }
        public Builder feature(String feature, Action action, Treatment treatment) { features.put(key(feature), new Rule(action, treatment)); return this; }
        public Builder operation(String operation, Action action, Treatment treatment) { operations.put(key(operation), new Rule(action, treatment)); return this; }
        public ProcessingPolicy build() { return new ProcessingPolicy(this); }
    }
}
