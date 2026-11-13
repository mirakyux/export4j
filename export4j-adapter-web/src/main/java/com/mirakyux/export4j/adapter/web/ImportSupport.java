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
        final ContentImportContext content;
        final List<ProcessingIssue> issues = new ArrayList<>();
        long work;
        long text;
        long tableSlots;
        int blocks;
        State(ImportLimits limits, ProcessingPolicy policy, String format) { this(limits, policy, format, null); }
        State(ImportLimits limits, ProcessingPolicy policy, String format, ContentImportContext content) {
            this.limits = limits; this.policy = policy; this.format = format; this.content = content;
        }
        void work(long count) {
            ExecutionContext.checkpoint();
            if (count > limits.maxWork() - work) { throw new IllegalArgumentException("import work budget exceeded"); }
            if (content != null) { content.budget.work(count); }
            work += count;
        }
        void text(int count) {
            textUnits(count);
            if (content != null) { content.object(count); }
        }
        private void textUnits(int count) {
            work(count); text += count;
            if (text > limits.maxTextUtf16()) { throw new IllegalArgumentException("import text budget exceeded"); }
            if (content != null) { content.budget.text(count); }
        }
        void text(String value) { textUnits(value.length()); if (content != null) { content.codePoints(value); } }
        void slots(long count) {
            if (count < 0 || count > limits.maxTableSlots() - tableSlots) { throw new IllegalArgumentException("import table slot budget exceeded"); }
            if (content != null) { content.budget.slots(count); content.node(); }
            tableSlots += count;
        }
        void row() { if (content != null) { content.row(); } }
        String id() {
            work(1);
            if (++blocks > limits.maxNodes()) { throw new IllegalArgumentException("import block budget exceeded"); }
            if (content != null) { return content.id(blocks); }
            return format.toLowerCase(java.util.Locale.ROOT) + "-" + blocks;
        }
        ProcessingPolicy.Treatment unsupported(String feature, ProcessingIssue.Location location, String reason,
                List<String> effects, ProcessingPolicy.Treatment... allowed) {
            var rule = policy.resolve(format, feature, "IMPORT");
            if (content != null && location.startUtf16() >= 0) {
                var range = content.originalRange(location.startUtf16(), location.endUtf16());
                location = location.range(range[0], range[1]);
            }
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
