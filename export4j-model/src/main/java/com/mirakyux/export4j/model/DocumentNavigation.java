// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Portable navigation declarations. Dimensions are points and colors are opaque sRGB integers.
 * Heading bookmarks are derived from current body headings after editing. Explicit bookmark titles
 * are literal values. Fonts, callbacks and resolved page positions are never retained. Immutable and
 * thread-safe; target validation occurs against the final edited/bound document.
 */
public record DocumentNavigation(boolean headings, int maxHeadingLevel, List<Bookmark> bookmarks, Contents contents) {
    public static final DocumentNavigation NONE = builder().build();
    public DocumentNavigation {
        bookmarks = List.copyOf(bookmarks); Objects.requireNonNull(contents, "contents");
        if (maxHeadingLevel < 1 || maxHeadingLevel > 6 || bookmarks.size() > 10_000) {
            throw new IllegalArgumentException("invalid navigation limits");
        }
        int previous = 0;
        for (var bookmark : bookmarks) {
            if (bookmark.level() > previous + 1) { throw new IllegalArgumentException("bookmark hierarchy skips a level"); }
            previous = bookmark.level();
        }
        if (headings && !bookmarks.isEmpty()) { throw new IllegalArgumentException("select heading or explicit bookmarks"); }
    }
    public enum Part { TITLE, ENTRY, PAGE_LABEL }
    public record Bookmark(String title, String blockId, int level) {
        public Bookmark {
            if (Objects.requireNonNull(title, "title").isBlank() || Objects.requireNonNull(blockId, "blockId").isBlank()
                    || title.length() > 10_000 || blockId.length() > 1024 || level < 1 || level > 6) {
                throw new IllegalArgumentException("invalid navigation bookmark");
            }
        }
    }
    /** Complete text typography; line height is a fixed baseline step. */
    public record TextStyle(double size, double lineHeight, double firstIndent, double subsequentIndent,
            ParagraphAlignment alignment, LineBreakPolicy lineBreak) {
        public TextStyle {
            Objects.requireNonNull(alignment, "alignment"); Objects.requireNonNull(lineBreak, "lineBreak");
            if (!Double.isFinite(size) || size <= 0 || !Double.isFinite(lineHeight) || lineHeight <= 0
                    || !Double.isFinite(firstIndent) || firstIndent < 0 || !Double.isFinite(subsequentIndent) || subsequentIndent < 0) {
                throw new IllegalArgumentException("invalid navigation typography");
            }
        }
        public static TextStyle of(double size) { return new TextStyle(size, size * 1.5, 0, 0, ParagraphAlignment.LEFT, LineBreakPolicy.STANDARD); }
    }
    /** Title/label style and color maps retain explicit declaration intent, including explicit black.
     * Missing title/label typography is derived from entries by the layout layer. ENTRY is forbidden
     * in styles since entries already supplies that complete value. Empty colors mean implicit black.
     */
    public record Contents(boolean enabled, String title, TextStyle entries, Map<Part, TextStyle> styles, Map<Part, Integer> colors) {
        public static final Contents NONE = new Contents(false, "", TextStyle.of(12), Map.of(), Map.of());
        public Contents {
            Objects.requireNonNull(title, "title"); Objects.requireNonNull(entries, "entries");
            styles = Map.copyOf(styles); colors = Map.copyOf(colors);
            if ((enabled && title.isBlank()) || title.length() > 10_000 || styles.containsKey(Part.ENTRY)
                    || colors.values().stream().anyMatch(value -> value < 0 || value > 0xffffff)) {
                throw new IllegalArgumentException("invalid contents declaration");
            }
        }
        public static Contents of(String title, double size) { return new Contents(true, title, TextStyle.of(size), Map.of(), Map.of()); }
    }
    public static Builder builder() { return new Builder(); }
    public Builder toBuilder() { return new Builder().headings(headings).maxHeadingLevel(maxHeadingLevel).bookmarks(bookmarks).contents(contents); }
    /** Task-confined builder, snapshotted by build(). */
    public static final class Builder {
        private boolean headings;
        private int maxHeadingLevel = 6;
        private List<Bookmark> bookmarks = new ArrayList<>();
        private Contents contents = Contents.NONE;
        private Builder() { }
        public Builder headings(boolean value) { headings = value; return this; }
        public Builder maxHeadingLevel(int value) { maxHeadingLevel = value; return this; }
        public Builder bookmarks(List<Bookmark> value) { bookmarks = new ArrayList<>(List.copyOf(value)); return this; }
        public Builder bookmark(String title, String blockId, int level) { bookmarks.add(new Bookmark(title, blockId, level)); return this; }
        public Builder contents(Contents value) { contents = Objects.requireNonNull(value, "contents"); return this; }
        public DocumentNavigation build() { return new DocumentNavigation(headings, maxHeadingLevel, bookmarks, contents); }
    }
    /** Rejects missing/static targets and invalid current heading hierarchy; never silently deletes a bookmark. */
    public void validate(SectionedDocument document) {
        Objects.requireNonNull(document, "document");
        if (!headings && bookmarks.isEmpty()) { return; }
        var body = document.sections().stream().flatMap(section -> section.blocks().stream()).toList();
        var ids = new java.util.HashSet<String>();
        SemanticTree.walk(body, visit -> ids.add(visit.block().id()));
        for (var bookmark : bookmarks) {
            ExecutionContext.checkpoint();
            if (!ids.contains(bookmark.blockId())) { throw new IllegalArgumentException("navigation target missing"); }
        }
        int previous = 0;
        if (headings) {
            for (var block : body) {
                ExecutionContext.checkpoint();
                if (block instanceof Heading heading && heading.level() <= maxHeadingLevel) {
                    if (heading.level() > previous + 1) { throw new IllegalArgumentException("heading bookmark hierarchy skips a level"); }
                    previous = heading.level();
                }
            }
        }
    }
}
