// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.adapter.template.MarkdownTemplateException.Code;

/** Immutable offline compiler for the documented strict Markdown template subset, not general CommonMark.
 * Supports paragraphs, ATX headings, single-line unordered items, nonnested star emphasis and explicit images.
 * Variables are ASCII dot paths only. Unsupported or ambiguous markup rejects rather than losing semantics.
 * Each compile owns its state; built compilers may be shared. Lengths are points and resources remain immutable.
 * Compilation throws {@link MarkdownTemplateException}; binding uses {@link DocumentTemplate.BindingException}.
 * Both operations observe the caller's synchronous ExecutionContext and perform no implicit I/O.
 */
public final class MarkdownTemplate {
    private static final String ESCAPABLE = "\\*_[]!<>`~|#-+.()&";
    private final PageSettings pageSettings;
    private final ParagraphFormat bodyFormat;
    private final Map<String, InlineImage> images;
    private final Limits limits;
    private final DocumentTemplate.Limits bindingLimits;

    private MarkdownTemplate(Builder builder) {
        pageSettings = builder.pageSettings;
        bodyFormat = builder.bodyFormat;
        images = Map.copyOf(builder.images);
        limits = builder.limits;
        bindingLimits = builder.bindingLimits;
    }

    /** Source work budgets. Input, including line terminators, counts UTF-16 units.
     * Lines include blank physical lines but not an empty line after a terminal newline; empty input has zero lines.
     * Inlines count emitted text runs, images and spaces inserted at paragraph soft breaks across all blocks.
     */
    public record Limits(int maxInputCharacters, int maxLines, int maxBlocks, int maxInlineContents) {
        public static final Limits DEFAULT = new Limits(1_000_000, 20_000, 20_000, 100_000);
        public Limits {
            if (maxInputCharacters < 1 || maxLines < 1 || maxBlocks < 1 || maxInlineContents < 1) {
                throw new IllegalArgumentException("invalid Markdown template limits");
            }
        }
    }

    /** Compiles a complete source snapshot; no partial template is returned on failure.
     * CRLF, LF and CR line endings are accepted. Diagnostic positions refer to the unnormalized source.
     */
    public DocumentTemplate compile(String source) {
        Objects.requireNonNull(source, "source");
        ExecutionContext.checkpoint();
        if (source.length() > limits.maxInputCharacters()) {
            throw failure(source, Code.BUDGET_EXCEEDED, limits.maxInputCharacters());
        }
        return new Compiler(source).compile();
    }

    /** Reads at most maxInputCharacters + 1 UTF-16 units and never closes the borrowed reader.
     * Reader IOException propagates unchanged. Custom readers are trusted code and must cooperate with cancellation.
     */
    public DocumentTemplate compile(Reader source) throws IOException {
        Objects.requireNonNull(source, "source");
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[4096];
        while (true) {
            ExecutionContext.checkpoint();
            int wanted = (int) Math.min(buffer.length, (long) limits.maxInputCharacters() - text.length() + 1);
            int count = source.read(buffer, 0, wanted);
            if (count < 0) { break; }
            if (count == 0) {
                int value = source.read();
                if (value < 0) { break; }
                if (text.length() == limits.maxInputCharacters()) {
                    throw failure(text.toString(), Code.BUDGET_EXCEEDED, text.length());
                }
                text.append((char) value);
            } else {
                if (count > limits.maxInputCharacters() - text.length()) {
                    text.append(buffer, 0, limits.maxInputCharacters() - text.length());
                    throw failure(text.toString(), Code.BUDGET_EXCEEDED, text.length());
                }
                text.append(buffer, 0, count);
            }
        }
        return compile(text.toString());
    }

    private enum Kind { PARAGRAPH, HEADING, BULLET, BLANK }
    private record Line(int start, int end) { }
    private record BlockLine(Kind kind, int start, int end, int level) { }

    private final class Compiler {
        private final String source;
        private final List<DocumentTemplate.Node> nodes = new ArrayList<>();
        private int inlineCount;
        private int paragraphImageCount;

        private Compiler(String source) { this.source = source; }

        private DocumentTemplate compile() {
            var lines = lines();
            List<InlineContent> paragraph = new ArrayList<>();
            int paragraphStart = 0;
            Kind previous = Kind.BLANK;
            for (var line : lines) {
                ExecutionContext.checkpoint();
                var block = classify(line);
                if (block.kind() != Kind.PARAGRAPH) {
                    if (!paragraph.isEmpty()) {
                        addParagraph(paragraph, paragraphStart);
                        paragraph = new ArrayList<>();
                    }
                    if (block.kind() != Kind.BLANK) { addBlock(block); }
                } else {
                    if (previous == Kind.BULLET) { throw error(Code.UNSUPPORTED_SYNTAX, line.start()); }
                    if (paragraph.isEmpty()) { paragraphStart = block.start(); }
                    else { append(paragraph, new InlineText(" ", InlineStyle.of(bodyFormat.fontSize())), block.start()); }
                    inline(block.start(), block.end(), bodyFormat.fontSize(), true, paragraph);
                }
                previous = block.kind();
            }
            if (!paragraph.isEmpty()) { addParagraph(paragraph, paragraphStart); }
            return new DocumentTemplate(DocumentMetadata.EMPTY, pageSettings, nodes, bindingLimits);
        }

        private List<Line> lines() {
            List<Line> result = new ArrayList<>();
            for (int start = 0; start < source.length();) {
                ExecutionContext.checkpoint();
                if (result.size() == limits.maxLines()) { throw error(Code.BUDGET_EXCEEDED, start); }
                int end = start;
                int scanned = 0;
                while (end < source.length() && source.charAt(end) != '\n' && source.charAt(end) != '\r') {
                    if ((scanned++ & 1023) == 0) { ExecutionContext.checkpoint(); }
                    char value = source.charAt(end);
                    if (Character.isHighSurrogate(value)) {
                        if (end + 1 >= source.length() || !Character.isLowSurrogate(source.charAt(end + 1))) {
                            throw error(Code.INVALID_UNICODE, end);
                        }
                        end += 2;
                        continue;
                    }
                    if (Character.isLowSurrogate(value) || value == '\ufffe' || value == '\uffff') {
                        throw error(Code.INVALID_UNICODE, end);
                    }
                    if (Character.isISOControl(value) || value == '\u2028' || value == '\u2029' || value == '\ufffc') {
                        throw error(Code.UNSUPPORTED_SYNTAX, end);
                    }
                    end++;
                }
                result.add(new Line(start, end));
                start = end;
                if (start < source.length() && source.charAt(start++) == '\r'
                        && start < source.length() && source.charAt(start) == '\n') { start++; }
            }
            return result;
        }

        private BlockLine classify(Line line) {
            int start = line.start(), end = line.end();
            while (end > start && source.charAt(end - 1) == ' ') {
                if ((end & 1023) == 0) { ExecutionContext.checkpoint(); }
                end--;
            }
            if (end == start) { return new BlockLine(Kind.BLANK, start, end, 0); }
            if (source.charAt(start) == ' ') { throw error(Code.UNSUPPORTED_SYNTAX, start); }
            if (line.end() - end >= 2) { throw error(Code.UNSUPPORTED_SYNTAX, end); }
            char first = source.charAt(start);
            if (first == '>' || first == '`' || first == '~' || thematic(start, end) || ordered(start, end)) {
                throw error(Code.UNSUPPORTED_SYNTAX, start);
            }
            if (first == '#') {
                int content = start;
                while (content < end && source.charAt(content) == '#') {
                    if ((content & 1023) == 0) { ExecutionContext.checkpoint(); }
                    content++;
                }
                int level = content - start;
                if (level > 6 || content == end || source.charAt(content) != ' ') {
                    throw error(Code.MALFORMED_MARKUP, start);
                }
                while (content < end && source.charAt(content) == ' ') {
                    if ((content & 1023) == 0) { ExecutionContext.checkpoint(); }
                    content++;
                }
                int closing = end;
                while (closing > content && source.charAt(closing - 1) == '#') {
                    if ((closing & 1023) == 0) { ExecutionContext.checkpoint(); }
                    closing--;
                }
                if (closing < end && closing > content && source.charAt(closing - 1) == ' ') {
                    end = closing - 1;
                    while (end > content && source.charAt(end - 1) == ' ') {
                        if ((end & 1023) == 0) { ExecutionContext.checkpoint(); }
                        end--;
                    }
                }
                if (content == end || closing == content) {
                    throw error(Code.MALFORMED_MARKUP, start);
                }
                return new BlockLine(Kind.HEADING, content, end, level);
            }
            if ((first == '+' || first == '*') && start + 1 == end) { throw error(Code.MALFORMED_MARKUP, start); }
            if ((first == '-' || first == '+' || first == '*') && start + 1 < end && source.charAt(start + 1) == ' ') {
                int content = start + 2;
                while (content < end && source.charAt(content) == ' ') {
                    if ((content & 1023) == 0) { ExecutionContext.checkpoint(); }
                    content++;
                }
                if (content == end) { throw error(Code.MALFORMED_MARKUP, start); }
                return new BlockLine(Kind.BULLET, content, end, 1);
            }
            return new BlockLine(Kind.PARAGRAPH, start, end, 0);
        }

        private boolean thematic(int start, int end) {
            char marker = source.charAt(start);
            if (marker != '-' && marker != '*' && marker != '_' && marker != '=') { return false; }
            int count = 0;
            for (int i = start; i < end; i++) {
                if ((i & 1023) == 0) { ExecutionContext.checkpoint(); }
                char value = source.charAt(i);
                if (value == marker) { count++; }
                else if (value != ' ') { return false; }
            }
            return marker == '-' || marker == '=' || count >= 3;
        }

        private boolean ordered(int start, int end) {
            int cursor = start;
            while (cursor < end && source.charAt(cursor) >= '0' && source.charAt(cursor) <= '9') {
                if ((cursor & 1023) == 0) { ExecutionContext.checkpoint(); }
                cursor++;
            }
            return cursor > start && cursor + 1 < end
                    && (source.charAt(cursor) == '.' || source.charAt(cursor) == ')') && source.charAt(cursor + 1) == ' ';
        }

        private void addBlock(BlockLine block) {
            reserveBlock(block.start());
            var format = block.kind() == Kind.HEADING ? Heading.defaultFormat(block.level()) : bodyFormat;
            List<InlineContent> content = new ArrayList<>();
            inline(block.start(), block.end(), format.fontSize(), false, content);
            var runs = content.stream().map(item -> (InlineText) item).toList();
            try {
                Block value = block.kind() == Kind.HEADING ? Heading.rich(id(), runs, block.level(), format)
                        : BulletItem.rich(id(), runs, "•", 1, format);
                nodes.add(new DocumentTemplate.ValueBlock(value));
            } catch (IllegalArgumentException invalid) { throw error(Code.INVALID_VALUE, block.start()); }
        }

        private void addParagraph(List<InlineContent> content, int start) {
            reserveBlock(start);
            try { nodes.add(new DocumentTemplate.ValueBlock(Paragraph.mixed(id(), content, bodyFormat))); }
            catch (IllegalArgumentException invalid) { throw error(Code.INVALID_VALUE, start); }
            paragraphImageCount = 0;
        }

        private String id() { return "markdown-" + (nodes.size() + 1); }
        private void reserveBlock(int offset) {
            if (nodes.size() == limits.maxBlocks()) { throw error(Code.BUDGET_EXCEEDED, offset); }
        }

        private void inline(int start, int end, double size, boolean allowImages, List<InlineContent> result) {
            var plain = new StringBuilder();
            for (int cursor = start; cursor < end;) {
                ExecutionContext.checkpoint();
                char value = source.charAt(cursor);
                if (value == '*') {
                    flush(plain, InlineStyle.of(size), result, cursor);
                    int count = stars(cursor, end);
                    if (count > 3 || cursor + count == end || source.charAt(cursor + count) == ' ') {
                        throw error(Code.MALFORMED_MARKUP, cursor);
                    }
                    int opening = cursor;
                    cursor += count;
                    var styled = new StringBuilder();
                    while (cursor < end && source.charAt(cursor) != '*') { cursor = plain(cursor, end, styled); }
                    if (cursor == end || stars(cursor, end) != count || styled.isEmpty()
                            || styled.charAt(styled.length() - 1) == ' ') { throw error(Code.MALFORMED_MARKUP, opening); }
                    String role = count == 1 ? "italic" : count == 2 ? "bold" : "boldItalic";
                    flush(styled, new InlineStyle(role, size, 0, 0, false, false), result, opening);
                    cursor += count;
                } else if (value == '!' && cursor + 1 < end && source.charAt(cursor + 1) == '[') {
                    if (!allowImages) { throw error(Code.UNSUPPORTED_SYNTAX, cursor); }
                    flush(plain, InlineStyle.of(size), result, cursor);
                    int opening = cursor;
                    cursor += 2;
                    var alt = new StringBuilder();
                    while (cursor < end && source.charAt(cursor) != ']') { cursor = plain(cursor, end, alt); }
                    if (cursor + 1 >= end || source.charAt(cursor + 1) != '(') { throw error(Code.MALFORMED_MARKUP, opening); }
                    int reference = cursor + 2;
                    cursor = reference;
                    while (cursor < end && source.charAt(cursor) != ')') {
                        if ((cursor & 1023) == 0) { ExecutionContext.checkpoint(); }
                        cursor++;
                    }
                    if (cursor == end) { throw error(Code.MALFORMED_MARKUP, opening); }
                    String name = source.substring(reference, cursor);
                    if (!identifier(name)) { throw error(Code.INVALID_RESOURCE_REFERENCE, reference); }
                    var registered = images.get(name);
                    if (registered == null) { throw error(Code.UNKNOWN_RESOURCE, reference); }
                    if (paragraphImageCount == 1024) { throw error(Code.BUDGET_EXCEEDED, opening); }
                    paragraphImageCount++;
                    try { append(result, new InlineImage(registered.resource(), registered.width(), registered.height(), 0, alt.toString()), opening); }
                    catch (MarkdownTemplateException failure) { throw failure; }
                    catch (IllegalArgumentException invalid) { throw error(Code.INVALID_VALUE, opening); }
                    cursor++;
                } else { cursor = plain(cursor, end, plain); }
            }
            flush(plain, InlineStyle.of(size), result, end);
        }

        private int stars(int start, int end) {
            int cursor = start;
            while (cursor < end && source.charAt(cursor) == '*') {
                if ((cursor & 1023) == 0) { ExecutionContext.checkpoint(); }
                cursor++;
            }
            return cursor - start;
        }

        private int plain(int cursor, int end, StringBuilder target) {
            ExecutionContext.checkpoint();
            char value = source.charAt(cursor);
            if (value == '\\') {
                if (cursor + 1 == end || ESCAPABLE.indexOf(source.charAt(cursor + 1)) < 0) {
                    throw error(Code.MALFORMED_MARKUP, cursor);
                }
                target.append(source.charAt(cursor + 1));
                return cursor + 2;
            }
            if (value == '$' && cursor + 1 < end && source.charAt(cursor + 1) == '{') {
                int closing = cursor + 2;
                while (closing < end && source.charAt(closing) != '}' && closing - cursor - 2 <= 256) { closing++; }
                if (closing == end || closing - cursor - 2 > 256 || !path(cursor + 2, closing)) {
                    throw error(Code.INVALID_EXPRESSION, cursor);
                }
                target.append(source, cursor, closing + 1);
                return closing + 1;
            }
            if (value == '*' || value == '_' || value == '~' || value == '`' || value == '[' || value == ']'
                    || value == '<' || value == '|' || value == '!' && cursor + 1 < end && source.charAt(cursor + 1) == '['
                    || value == '{' && cursor + 1 < end && (source.charAt(cursor + 1) == '{' || source.charAt(cursor + 1) == '%')
                    || value == '&' && entity(cursor + 1, end)) { throw error(Code.UNSUPPORTED_SYNTAX, cursor); }
            target.append(value);
            return cursor + 1;
        }

        private boolean path(int start, int end) {
            boolean first = true;
            for (int cursor = start; cursor < end; cursor++) {
                char value = source.charAt(cursor);
                if (value == '.' && !first) { first = true; }
                else if (letter(value) || value == '_' || !first && value >= '0' && value <= '9') { first = false; }
                else { return false; }
            }
            return !first;
        }

        private boolean entity(int start, int end) {
            if (start == end || !letter(source.charAt(start)) && source.charAt(start) != '#') { return false; }
            int cursor = start + 1;
            while (cursor < end && (letter(source.charAt(cursor)) || source.charAt(cursor) >= '0' && source.charAt(cursor) <= '9')) {
                if ((cursor & 1023) == 0) { ExecutionContext.checkpoint(); }
                cursor++;
            }
            return cursor < end && source.charAt(cursor) == ';';
        }

        private void flush(StringBuilder text, InlineStyle style, List<InlineContent> target, int offset) {
            if (!text.isEmpty()) { append(target, new InlineText(text.toString(), style), offset); text.setLength(0); }
        }
        private void append(List<InlineContent> target, InlineContent item, int offset) {
            if (inlineCount == limits.maxInlineContents()) { throw error(Code.BUDGET_EXCEEDED, offset); }
            inlineCount++;
            target.add(item);
        }
        private MarkdownTemplateException error(Code code, int offset) { return failure(source, code, offset); }
    }

    private static boolean letter(char value) { return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'; }
    private static boolean identifier(String value) {
        if (value.isEmpty() || value.length() > 256 || !letter(value.charAt(0)) && value.charAt(0) != '_') { return false; }
        for (int i = 1; i < value.length(); i++) {
            char part = value.charAt(i);
            if (!letter(part) && part != '_' && (part < '0' || part > '9')) { return false; }
        }
        return true;
    }

    private static MarkdownTemplateException failure(String source, Code code, int offset) {
        int line = 1, column = 1;
        for (int cursor = 0; cursor < offset; cursor++) {
            if ((cursor & 1023) == 0) { ExecutionContext.checkpoint(); }
            char value = source.charAt(cursor);
            if (value == '\r') {
                if (cursor + 1 < offset && source.charAt(cursor + 1) == '\n') { cursor++; }
                line++; column = 1;
            } else if (value == '\n') { line++; column = 1; }
            else { column++; }
        }
        return new MarkdownTemplateException(code, line, column, offset);
    }

    /** Starts task-confined configuration: A4, 12/18 pt body, strict budgets and no image resources. */
    public static Builder builder() { return new Builder(); }

    /** Mutable construction state. build snapshots it; resource references cannot select a file or URL. */
    public static final class Builder {
        private PageSettings pageSettings = PageSettings.A4;
        private ParagraphFormat bodyFormat = ParagraphFormat.of(12);
        private final Map<String, InlineImage> images = new HashMap<>();
        private Limits limits = Limits.DEFAULT;
        private DocumentTemplate.Limits bindingLimits = DocumentTemplate.Limits.DEFAULT;
        private Builder() { }
        public Builder pageSettings(PageSettings value) { pageSettings = Objects.requireNonNull(value, "pageSettings"); return this; }
        public Builder bodyFormat(ParagraphFormat value) { bodyFormat = Objects.requireNonNull(value, "bodyFormat"); return this; }
        public Builder limits(Limits value) { limits = Objects.requireNonNull(value, "limits"); return this; }
        public Builder bindingLimits(DocumentTemplate.Limits value) { bindingLimits = Objects.requireNonNull(value, "bindingLimits"); return this; }
        /** Registers explicit immutable image bytes and positive point dimensions, without decoding or I/O.
         * A later registration replaces the same name; built compilers retain their earlier snapshot.
         * Image alt text comes from the template. Fonts and fixed line heights remain caller-controlled.
         */
        public Builder image(String name, ImageResource resource, double width, double height) {
            if (!identifier(Objects.requireNonNull(name, "name"))) { throw new IllegalArgumentException("invalid image resource name"); }
            images.put(name, new InlineImage(resource, width, height, 0, ""));
            return this;
        }
        public MarkdownTemplate build() { return new MarkdownTemplate(this); }
    }
}
