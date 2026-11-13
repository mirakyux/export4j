// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.InlineVector;
import com.mirakyux.export4j.model.SourceContentParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** One call's immutable defaults, original-source mapping and temporary literal placeholders. */
final class ContentImportContext {
    record Replacement(int start, int end, int replacementStart, int replacementEnd) { }
    final ContentBudget budget;
    final ContentOptions.Resolved options;
    final SourceContentParser.Remaining remaining;
    final String namespace;
    final String source;
    final FormulaProcessor processor;
    final Map<String, String> literals = new HashMap<>();
    final Map<String, FormulaSyntax.Span> formulaMarkers = new HashMap<>();
    boolean markdownFormulasScanned;
    String literalPrefix;
    final List<Replacement> replacements = new ArrayList<>();
    List<MixedMarkdownRenderer.Range> htmlSourceRanges = List.of();
    private int rows, codePoints;
    private int blocks;
    private int vectorSegments;
    private int outputNodes;
    private int pendingObjects;
    private final Map<InlineVector, Integer> preparedVectors = new IdentityHashMap<>();

    ContentImportContext(ContentBudget budget, ContentOptions.Resolved options, SourceContentParser.Remaining remaining,
            String namespace, String source, FormulaProcessor processor) {
        this.budget = budget; this.options = options; this.remaining = remaining;
        this.namespace = namespace; this.source = source; this.processor = processor;
    }
    String id(int index) {
        block();
        budget.block();
        return namespace + ":block-" + index;
    }
    String sourceId(String id) { block(); budget.block(); return namespace + ":source-" + HexFormat.of().formatHex(id.getBytes(StandardCharsets.UTF_8)); }
    private void block() { if (++blocks > remaining.maxBlocks()) { throw new IllegalArgumentException("content block allowance exceeded"); } node(); }
    void row() { if (++rows > remaining.maxRows()) { throw new IllegalArgumentException("content row allowance exceeded"); } node(); }
    void node() {
        if (outputNodes >= remaining.maxVisitedNodes() - vectorSegments) { throw new IllegalArgumentException("content output node allowance exceeded"); }
        outputNodes++;
    }
    void codePoints(String value) {
        codePoints(value.codePointCount(0, value.length()));
    }
    void codePoints(int count) {
        if (count > remaining.maxCodePoints() - codePoints - pendingObjects) { throw new IllegalArgumentException("content code point allowance exceeded"); }
        codePoints += count;
    }
    void object(int count) { pendingObjects -= Math.min(count, pendingObjects); codePoints(count); }
    InlineStyle style(double size) { return new InlineStyle(options.fontRole(), size, options.rgb(), 0, false, false); }
    String raw(int start, int end) {
        if (start < 0 || end < start) { throw new IllegalArgumentException("content original source range unavailable"); }
        var range = originalRange(start, end);
        if (range[0] < 0 || range[1] < range[0]) { throw new IllegalArgumentException("content original source range unavailable"); }
        return source.substring(range[0], range[1]);
    }
    int[] originalRange(int start, int end) {
        if (!htmlSourceRanges.isEmpty()) {
            MixedMarkdownRenderer.Range best = null;
            for (var range : htmlSourceRanges) {
                budget.work(1);
                if (start >= range.start() && end <= range.end()
                        && (best == null || range.end() - range.start() < best.end() - best.start())) { best = range; }
            }
            if (best != null) {
                if (best.direct()) { return new int[] {original(best.sourceStart() + start - best.start(), false), original(best.sourceStart() + end - best.start(), true)}; }
                return new int[] {original(best.sourceStart(), false), original(best.sourceEnd(), true)};
            }
            // Implicit parser wrappers have no source anchor. Never report a generated HTML offset
            // as though it belonged to the original Markdown.
            return new int[] {-1, -1};
        }
        return new int[] {original(start, false), original(end, true)};
    }
    String restoreLiterals(String literal) {
        if (literals.isEmpty()) { return literal; }
        budget.work(literal.length());
        var restored = new StringBuilder(); int cursor = 0;
        while (cursor < literal.length()) {
            int at = literal.indexOf(literalPrefix, cursor);
            if (at < 0) { restored.append(literal, cursor, literal.length()); break; }
            int end = literal.indexOf("END", at + literalPrefix.length());
            if (end < 0) { throw new IllegalArgumentException("literal formula placeholder was not preserved"); }
            String replacement = literals.get(literal.substring(at, end + 3));
            if (replacement == null) { throw new IllegalArgumentException("literal formula placeholder was not preserved"); }
            restored.append(literal, cursor, at).append(replacement); cursor = end + 3;
        }
        return restored.toString();
    }
    int original(int offset, boolean end) {
        if (offset < 0) { return offset; }
        int shift = 0;
        for (var replacement : replacements) {
            budget.work(1);
            if (offset <= replacement.replacementStart()) { break; }
            if (offset < replacement.replacementEnd()) { return end ? replacement.end() : replacement.start(); }
            shift = replacement.end() - replacement.replacementEnd();
        }
        return Math.min(source.length(), offset + shift);
    }
    List<InlineContent> text(String literal, InlineStyle style, String link, ImportSupport.State state, boolean parseFormulas) {
        var restored = new ArrayList<InlineContent>();
        if (!literals.isEmpty()) {
            budget.work(literal.length());
            int cursor = 0;
            while (cursor < literal.length()) {
                int at = literal.indexOf(literalPrefix, cursor);
                if (at < 0) { append(restored, literal.substring(cursor), style, link, state); break; }
                int end = literal.indexOf("END", at + literalPrefix.length());
                if (end < 0) { throw new IllegalArgumentException("literal formula placeholder was not preserved"); }
                String found = literal.substring(at, end + 3), replacement = literals.get(found);
                if (replacement == null) { throw new IllegalArgumentException("literal formula placeholder was not preserved"); }
                append(restored, literal.substring(cursor, at), style, link, state);
                append(restored, replacement, style, link, state); cursor = at + found.length();
            }
            return List.copyOf(restored);
        }
        if (!parseFormulas || !options.formulas()) { append(restored, literal, style, link, state); return List.copyOf(restored); }
        budget.work(literal.length());
        var spans = FormulaSyntax.text(literal, budget.remainingFormulas(), budget.maxFormulaUtf16);
        budget.formulas(spans.size()); int cursor = 0;
        for (var span : spans) {
            append(restored, literal.substring(cursor, span.start()), style, link, state);
            var vector = render(span, style.fontSize(), style.rgb());
            state.text(1); state.work(vector.resource().segmentCount() + vector.logicalText().length());
            restored.add(new InlineVector(vector.resource(), vector.logicalText(), vector.rgb(), link.isEmpty() ? vector.hyperlink() : link));
            cursor = span.end();
        }
        append(restored, literal.substring(cursor), style, link, state);
        return List.copyOf(restored);
    }
    InlineVector render(FormulaSyntax.Span span, double size, int rgb) {
        if (processor == null) { throw new IllegalStateException("formula parsing requires an explicit FormulaProcessor"); }
        int readingPoints = span.tex().codePointCount(0, span.tex().length());
        if ((long) readingPoints + 1 > remaining.maxCodePoints() - codePoints - pendingObjects) { throw new IllegalArgumentException("content code point allowance exceeded"); }
        int futureNodes = (blocks == 0 ? 1 : 0) + 1;
        int futureWork = Math.addExact(span.tex().length(), 3);
        int remainingSegments = Math.min(Math.min(budget.remainingSegments(), budget.remainingWork() - futureWork),
                remaining.maxVisitedNodes() - vectorSegments - outputNodes - futureNodes);
        if (remainingSegments <= 0) { throw new IllegalArgumentException("content vector segment budget exceeded"); }
        var vector = java.util.Objects.requireNonNull(processor.render(span.tex(), span.display(), size, rgb, remainingSegments), "formula result");
        accountVector(vector);
        preparedVectors.merge(vector, 1, Integer::sum);
        return vector;
    }
    void vector(InlineVector vector) {
        Integer prepared = preparedVectors.get(vector);
        if (prepared != null) {
            if (prepared == 1) { preparedVectors.remove(vector); } else { preparedVectors.put(vector, prepared - 1); }
        } else { accountVector(vector); }
    }
    private void accountVector(InlineVector vector) {
        int count = vector.resource().segmentCount();
        if (count > remaining.maxVisitedNodes() - vectorSegments - outputNodes - 1 - (blocks == 0 ? 1 : 0)) { throw new IllegalArgumentException("content vector output allowance exceeded"); }
        budget.segments(count); vectorSegments += count;
        budget.text(vector.logicalText().length()); codePoints(vector.logicalText());
        if (remaining.maxCodePoints() - codePoints - pendingObjects < 1) { throw new IllegalArgumentException("content code point allowance exceeded"); }
        pendingObjects++; node();
    }
    private static void append(List<InlineContent> output, String text, InlineStyle style, String link, ImportSupport.State state) {
        if (text.isEmpty()) { return; }
        if (state.content != null) { state.content.node(); }
        state.text(text); output.add(new InlineText(text, style, link));
    }
}
