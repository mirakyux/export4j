// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.util.ArrayList;
import java.util.List;

final class ImportSupport {
    private ImportSupport() { }
    static final class UnavailableTreatment extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        UnavailableTreatment(String feature) { super("import treatment unavailable: " + feature); }
    }
    static final class State {
        final ImportLimits limits;
        final ProcessingPolicy policy;
        final String format;
        final List<ProcessingIssue> issues = new ArrayList<>();
        long work;
        long text;
        long tableSlots;
        int blocks;
        State(ImportLimits limits, ProcessingPolicy policy, String format) { this.limits = limits; this.policy = policy; this.format = format; }
        void work(long count) {
            ExecutionContext.checkpoint();
            if (count > limits.maxWork() - work) { throw new IllegalArgumentException("import work budget exceeded"); }
            work += count;
        }
        void text(int count) {
            work(count); text += count;
            if (text > limits.maxTextUtf16()) { throw new IllegalArgumentException("import text budget exceeded"); }
        }
        String id() {
            work(1);
            if (++blocks > limits.maxNodes()) { throw new IllegalArgumentException("import block budget exceeded"); }
            return format.toLowerCase(java.util.Locale.ROOT) + "-" + blocks;
        }
        ProcessingPolicy.Treatment unsupported(String feature, ProcessingIssue.Location location, String reason,
                List<String> effects, ProcessingPolicy.Treatment... allowed) {
            var rule = policy.resolve(format, feature, "IMPORT");
            var issue = new ProcessingIssue("IMPORT", format, "MODEL", feature, "IMPORT", location,
                    reason, rule.action(), rule.treatment(), effects);
            if (rule.action() == ProcessingPolicy.Action.ERROR) { throw new ProcessingException(issue); }
            if (!List.of(allowed).contains(rule.treatment())) { throw new UnavailableTreatment(feature); }
            if (issues.size() >= limits.maxIssues()) { throw new IllegalArgumentException("import issue budget exceeded"); }
            issues.add(issue); return rule.treatment();
        }
    }
    static void input(String source, State state) {
        if (source.length() > state.limits.maxInputUtf16()) { throw new IllegalArgumentException("import input budget exceeded"); }
        state.work(source.length()); int line = 0;
        for (int index = 0; index < source.length(); index++) {
            if ((index & 1023) == 0) { ExecutionContext.checkpoint(); }
            char value = source.charAt(index);
            if (value == '\r' || value == '\n') { line = 0; }
            else if (++line > state.limits.maxLineUtf16()) { throw new IllegalArgumentException("import line budget exceeded"); }
            if (value == 0) { throw new IllegalArgumentException("NUL is not accepted in import input"); }
            if (Character.isHighSurrogate(value)) {
                if (++index >= source.length() || !Character.isLowSurrogate(source.charAt(index))) { throw new IllegalArgumentException("invalid Unicode import input"); }
                if (++line > state.limits.maxLineUtf16()) { throw new IllegalArgumentException("import line budget exceeded"); }
            } else if (Character.isLowSurrogate(value)) { throw new IllegalArgumentException("invalid Unicode import input"); }
        }
    }
}
