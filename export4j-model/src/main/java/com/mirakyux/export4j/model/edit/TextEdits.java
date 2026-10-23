// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model.edit;

import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy.Treatment;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.regex.Pattern;

final class TextEdits {
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private TextEdits() { }
    record Span(int start, int end, InlineContent content) { }

    static List<DocumentEditor.Match> find(Paragraph source, String needle, ProcessingIssue.Location location, EditSupport.State state) {
        state.work(source.text().length()); var spans = spans(source); var boundaries = boundaries(source.text(), state);
        var matches = new ArrayList<DocumentEditor.Match>();
        for (int start = source.text().indexOf(needle); start >= 0; start = source.text().indexOf(needle, start + needle.length())) {
            state.work((long) needle.length() + spans.size());
            if (++state.replacements > state.limits.maxReplacements()) { throw new IllegalArgumentException("search match budget exceeded"); }
            int end = start + needle.length(); InlineText first = null; boolean object = false; boolean uniform = true;
            for (var span : spans) {
                if (span.start() >= end) { break; }
                if (span.end() <= start) { continue; }
                if (!(span.content() instanceof InlineText run)) { object = true; continue; }
                if (first == null) { first = run; }
                else { uniform &= first.style().equals(run.style()) && first.hyperlink().equals(run.hyperlink()); }
            }
            matches.add(new DocumentEditor.Match(location.range(start, end), object, !object && uniform,
                    boundaries.get(start) && boundaries.get(end)));
        }
        return List.copyOf(matches);
    }
    private static List<Span> spans(Paragraph source) {
        var result = new ArrayList<Span>(); int offset = 0;
        for (var content : source.content()) {
            int length = content instanceof InlineText run ? run.text().length() : 1;
            result.add(new Span(offset, offset + length, content)); offset += length;
        }
        return result;
    }

    static Paragraph style(Paragraph source, int start, int end, InlineStyle style,
            ProcessingIssue.Location location, EditSupport.State state) {
        if (start < 0 || end <= start || end > source.text().length()) { throw new IllegalArgumentException("invalid inline style range"); }
        var matcher = GRAPHEME.matcher(source.text());
        boolean startBoundary = start == 0; boolean endBoundary = false;
        while (matcher.find()) {
            state.work(1);
            startBoundary |= matcher.end() == start; endBoundary |= matcher.end() == end;
        }
        if (!startBoundary || !endBoundary) {
            state.unsupported("GRAPHEME_BOUNDARY", "INLINE_STYLE", location.range(start, end),
                    "style range splits a grapheme cluster", List.of("MATCH_NOT_STYLED"),
                    Treatment.SKIP_MATCH, Treatment.SKIP_BLOCK, Treatment.SKIP_REQUEST);
            return source;
        }
        var result = new ArrayList<InlineContent>(); int offset = 0;
        for (var content : source.content()) {
            state.work(1);
            int length = content instanceof InlineText text ? text.text().length() : 1;
            if (offset < end && offset + length > start) {
                if (!(content instanceof InlineText text)) {
                    state.unsupported("INLINE_OBJECT_BOUNDARY", "INLINE_STYLE", location.range(start, end),
                            "style range overlaps an atomic image or field", List.of("MATCH_NOT_STYLED"),
                            Treatment.SKIP_MATCH, Treatment.SKIP_BLOCK, Treatment.SKIP_REQUEST);
                    return source;
                }
                int left = Math.max(0, start - offset); int right = Math.min(length, end - offset);
                if (left > 0) { result.add(new InlineText(text.text().substring(0, left), text.style(), text.hyperlink())); }
                result.add(new InlineText(text.text().substring(left, right), style, text.hyperlink()));
                if (right < length) { result.add(new InlineText(text.text().substring(right), text.style(), text.hyperlink())); }
            } else { result.add(content); }
            offset += length;
        }
        return Paragraph.mixed(source.id(), result, source.format());
    }

    static Paragraph replace(Paragraph source, String needle, String replacement,
            ProcessingIssue.Location location, EditSupport.State state) {
        state.work(source.text().length());
        var spans = spans(source);
        var output = new ArrayList<InlineContent>();
        int cursor = 0;
        boolean changed = false;
        long candidateLength = source.text().length();
        var boundaries = boundaries(source.text(), state);
        for (int start = source.text().indexOf(needle); start >= 0; start = source.text().indexOf(needle, cursor)) {
            state.work((long) needle.length() + spans.size());
            if (++state.replacements > state.limits.maxReplacements()) { throw new IllegalArgumentException("replacement budget exceeded"); }
            int end = start + needle.length();
            if (splitsSurrogate(source.text(), start) || splitsSurrogate(source.text(), end)) {
                throw new IllegalArgumentException("replacement splits a Unicode code point");
            }
            var range = location.range(start, end);
            int matchStart = start;
            var matching = spans.stream().filter(span -> span.start() < end && span.end() > matchStart).toList();
            // Literal text never consumes an image or a field, even under a degrading policy.
            boolean object = matching.stream().anyMatch(span -> !(span.content() instanceof InlineText));
            InlineText first = matching.isEmpty() || !(matching.getFirst().content() instanceof InlineText text) ? null : text;
            boolean mixed = !object && first != null && matching.stream().anyMatch(span -> {
                var run = (InlineText) span.content();
                return !first.style().equals(run.style()) || !first.hyperlink().equals(run.hyperlink());
            });
            Treatment treatment = null;
            if (!boundaries.get(start) || !boundaries.get(end)) {
                treatment = state.unsupported("GRAPHEME_BOUNDARY", "REPLACE_TEXT", range,
                        "literal match splits a grapheme cluster", List.of("MATCH_NOT_REPLACED"),
                        Treatment.SKIP_MATCH, Treatment.SKIP_BLOCK, Treatment.SKIP_REQUEST);
            } else if (object || mixed) {
                treatment = object ? state.unsupported("INLINE_OBJECT_BOUNDARY", "REPLACE_TEXT", range,
                        "literal replacement overlaps an atomic image or field", List.of("MATCH_NOT_REPLACED"),
                        Treatment.SKIP_MATCH, Treatment.SKIP_BLOCK, Treatment.SKIP_REQUEST)
                    : state.unsupported("MIXED_RUN_STYLE", "REPLACE_TEXT", range,
                        "replacement spans different styles or hyperlinks", List.of("REPLACEMENT_USES_FIRST_RUN_STYLE_AND_LINK"),
                        Treatment.SKIP_MATCH, Treatment.SKIP_BLOCK, Treatment.SKIP_REQUEST, Treatment.DEGRADE);
            }
            if (treatment == Treatment.SKIP_BLOCK) { throw new EditSupport.SkipBlock(location.blockId()); }
            append(output, spans, cursor, start);
            if (treatment == Treatment.SKIP_MATCH) { append(output, spans, start, end); }
            else {
                if (first == null) { throw new IllegalStateException("text match has no text run"); }
                candidateLength += (long) replacement.length() - needle.length();
                if (candidateLength > state.limits.maxTextUtf16()) { throw new IllegalArgumentException("replacement output budget exceeded"); }
                state.work(replacement.length());
                if (!replacement.isEmpty()) { output.add(new InlineText(replacement, first.style(), first.hyperlink())); }
                state.change("REPLACE_TEXT", range); changed = true;
            }
            cursor = end;
        }
        if (!changed) { return source; }
        append(output, spans, cursor, source.text().length());
        return Paragraph.mixed(source.id(), output, source.format());
    }
    private static boolean splitsSurrogate(String value, int index) {
        return index > 0 && index < value.length() && Character.isHighSurrogate(value.charAt(index - 1))
                && Character.isLowSurrogate(value.charAt(index));
    }
    private static BitSet boundaries(String text, EditSupport.State state) {
        var result = new BitSet(text.length() + 1); result.set(0);
        var matcher = GRAPHEME.matcher(text);
        while (matcher.find()) { state.work(1); result.set(matcher.end()); }
        return result;
    }
    private static void append(List<InlineContent> output, List<Span> spans, int start, int end) {
        if (start == end) { return; }
        for (var span : spans) {
            if (span.start() >= end) { break; }
            if (span.end() <= start) { continue; }
            if (span.content() instanceof InlineText text) {
                output.add(new InlineText(text.text().substring(Math.max(0, start - span.start()),
                        Math.min(span.end(), end) - span.start()), text.style(), text.hyperlink()));
            } else { output.add(span.content()); }
        }
    }
}
