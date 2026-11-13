// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.ExecutionContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.Node;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/** Bounded delimiter recognition without TeX rendering. Inline dollars use non-space inner edges;
 * display double dollars occupy their own lines. Offsets refer to the original UTF-16 source.
 * Markdown code, links, images, reference definitions and raw HTML are protected.
 */
public final class FormulaSyntax {
    private FormulaSyntax() { }

    /** One original delimited formula; tex excludes delimiters and surrounding whitespace. */
    public record Span(int start, int end, String tex, boolean display) {
        public Span {
            if (start < 0 || end <= start || Objects.requireNonNull(tex, "tex").isBlank()) {
                throw new IllegalArgumentException("invalid formula span");
            }
        }
    }

    /** A source, formula-count or TeX-length limit was exceeded; never contains source text. */
    public static final class LimitExceededException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public LimitExceededException() { super("formula source budget exceeded"); }
    }

    public static List<Span> markdown(String source, int maxSource, int maxCount, int maxTex) {
        Objects.requireNonNull(source, "source");
        if (maxSource < 0 || maxCount < 0 || maxTex < 0) { throw new IllegalArgumentException("negative formula limit"); }
        if (source.length() > maxSource) { throw new LimitExceededException(); }
        ExecutionContext.checkpoint();
        return markdown(source, parser().parse(source), maxCount, maxTex, false);
    }

    /** Recognizes only explicit delimiters; plain text has no protected Markdown contexts. */
    public static List<Span> text(String source, int maxCount, int maxTex) {
        Objects.requireNonNull(source, "source");
        if (maxCount < 0 || maxTex < 0) { throw new IllegalArgumentException("negative formula limit"); }
        return scan(source, null, maxCount, maxTex);
    }

    /** Delimited formulas in HTML text, excluding tag attributes, comments and literal containers.
     * Offsets remain in encoded HTML; TeX character references are decoded once. */
    static List<Span> html(String source, int maxCount, int maxTex, boolean protectTables) {
        var protectedSource = new boolean[source.length()]; var tags = new boolean[source.length()];
        protectHtml(source, protectedSource, tags, protectTables);
        return scan(source, protectedSource, tags, maxCount, maxTex).stream()
                .map(span -> new Span(span.start(), span.end(), org.jsoup.parser.Parser.unescapeEntities(span.tex(), false), span.display())).toList();
    }

    static Parser parser() {
        return Parser.builder().extensions(List.of(TablesExtension.create(), StrikethroughExtension.create()))
                .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build();
    }

    static List<Span> markdown(String source, Node tree, int maxCount, int maxTex, boolean protectTables) {
        return markdown(source, tree, maxCount, maxTex, protectTables, false);
    }

    static List<Span> markdown(String source, Node tree, int maxCount, int maxTex, boolean protectTables, boolean rawHtml) {
        var protectedSource = new boolean[source.length()];
        var tags = rawHtml ? new boolean[source.length()] : null;
        Node node = tree.getFirstChild();
        while (node != null) {
            ExecutionContext.checkpoint();
            boolean protect = node instanceof Code || node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock
                    || !rawHtml && (node instanceof HtmlInline || node instanceof HtmlBlock) || node instanceof Link
                    || node instanceof Image || node instanceof LinkReferenceDefinition || protectTables && node instanceof TableBlock;
            if (protect) {
                for (var span : node.getSourceSpans()) {
                    int start = span.getInputIndex();
                    Arrays.fill(protectedSource, start, start + span.getLength(), true);
                }
            }
            if (!protect && node.getFirstChild() != null) { node = node.getFirstChild(); continue; }
            while (node != tree && node.getNext() == null) { node = node.getParent(); }
            node = node == tree ? null : node.getNext();
        }
        if (rawHtml) { protectHtml(source, protectedSource, tags, protectTables); }
        return scan(source, protectedSource, tags, maxCount, maxTex);
    }

    private static List<Span> scan(String source, boolean[] protectedSource, int maxCount, int maxTex) {
        return scan(source, protectedSource, null, maxCount, maxTex);
    }

    /** Protects real tag/attribute syntax while leaving HTML body text available to explicit math.
     * This is lexical masking only; all HTML safety and structural validation happens in the mapper. */
    private static void protectHtml(String source, boolean[] protectedSource, boolean[] tags, boolean protectTables) {
        var containers = new java.util.ArrayDeque<String>();
        for (int i = 0; i < source.length(); i++) {
            ExecutionContext.checkpoint();
            if (protectedSource[i] && containers.isEmpty()) { continue; }
            if (!containers.isEmpty()) { protectedSource[i] = true; }
            if (source.charAt(i) != '<') { continue; }
            if (source.startsWith("<!--", i)) {
                int end = source.indexOf("-->", i + 4); end = end < 0 ? source.length() : end + 3;
                Arrays.fill(protectedSource, i, end, true); i = end - 1; continue;
            }
            int nameStart = i + 1;
            boolean closing = nameStart < source.length() && source.charAt(nameStart) == '/';
            if (closing) { nameStart++; }
            if (nameStart >= source.length() || !asciiLetter(source.charAt(nameStart))) { continue; }
            int nameEnd = nameStart + 1;
            while (nameEnd < source.length() && (asciiLetter(source.charAt(nameEnd))
                    || Character.isDigit(source.charAt(nameEnd)) || source.charAt(nameEnd) == ':' || source.charAt(nameEnd) == '-')) { nameEnd++; }
            if (nameEnd == source.length() || !(Character.isWhitespace(source.charAt(nameEnd))
                    || source.charAt(nameEnd) == '/' || source.charAt(nameEnd) == '>')) { continue; }
            char quote = 0; int end = nameEnd;
            for (; end < source.length(); end++) {
                char value = source.charAt(end);
                if (quote != 0) { if (value == quote) { quote = 0; } }
                else if (value == '\'' || value == '"') { quote = value; }
                else if (value == '>') { break; }
                else if (value == '<') { break; }
            }
            if (end == source.length() || source.charAt(end) != '>') { continue; }
            String name = source.substring(nameStart, nameEnd).toLowerCase(java.util.Locale.ROOT);
            Arrays.fill(tags, i, end + 1, true);
            if (!containers.isEmpty()) { Arrays.fill(protectedSource, i, end + 1, true); }
            if (List.of("code", "pre", "a", "style", "script").contains(name) || protectTables && name.equals("table")) {
                if (closing && !containers.isEmpty() && containers.peek().equals(name)) { containers.pop(); }
                else if (!closing && source.charAt(end - 1) != '/') { containers.push(name); }
            }
            i = end;
        }
    }
    private static boolean asciiLetter(char value) { return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'; }

    private static List<Span> scan(String source, boolean[] protectedSource, boolean[] tags, int maxCount, int maxTex) {
        var result = new ArrayList<Span>();
        for (int i = 0; i < source.length(); i++) {
            ExecutionContext.checkpoint();
            if (source.charAt(i) != '$' || protectedSource != null && protectedSource[i] || tags != null && tags[i] || escaped(source, i)) { continue; }
            boolean display = i + 1 < source.length() && source.charAt(i + 1) == '$';
            int delimiter = display ? 2 : 1;
            if (display && !lineStart(source, i)) { i++; continue; }
            int start = i + delimiter;
            if (start == source.length() || !display && Character.isWhitespace(source.charAt(start))) { continue; }
            int closing = -1, scanned = start;
            boolean restart = false;
            for (int j = start; j < source.length(); j++) {
                scanned = j; ExecutionContext.checkpoint();
                char c = source.charAt(j);
                if (protectedSource != null && protectedSource[j] || !display && (c == '\n' || c == '\r')) { break; }
                if (tags != null && tags[j]) { continue; }
                if (c != '$' || escaped(source, j)) { continue; }
                if (display) {
                    if (j + 1 < source.length() && source.charAt(j + 1) == '$' && lineEnd(source, j + 2)) { closing = j; break; }
                    if (j + 1 < source.length() && source.charAt(j + 1) == '$' && lineStart(source, j)) { restart = true; break; }
                } else if (j > start && !Character.isWhitespace(source.charAt(j - 1))
                        && (j + 1 == source.length() || !Character.isDigit(source.charAt(j + 1)))
                        && (j + 1 == source.length() || source.charAt(j + 1) != '$')) { closing = j; break; }
                else if (j + 1 < source.length() && !Character.isWhitespace(source.charAt(j + 1))) { restart = true; break; }
            }
            if (closing < 0) { i = restart ? scanned - 1 : scanned; continue; }
            if (closing - start > maxTex || result.size() >= maxCount) { throw new LimitExceededException(); }
            String tex = source.substring(start, closing).strip();
            if (!tex.isEmpty()) { result.add(new Span(i, closing + delimiter, tex, display)); }
            i = closing + delimiter - 1;
        }
        return List.copyOf(result);
    }

    private static boolean escaped(String source, int at) {
        int slashes = 0;
        while (at > 0 && source.charAt(--at) == '\\') { slashes++; }
        return slashes % 2 != 0;
    }
    private static boolean lineStart(String source, int at) {
        while (at > 0) { char c = source.charAt(--at); if (c == '\n' || c == '\r') { break; } if (c != ' ' && c != '\t') { return false; } }
        return true;
    }
    private static boolean lineEnd(String source, int at) {
        for (; at < source.length(); at++) { char c = source.charAt(at); if (c == '\n' || c == '\r') { break; } if (c != ' ' && c != '\t') { return false; } }
        return true;
    }
}
