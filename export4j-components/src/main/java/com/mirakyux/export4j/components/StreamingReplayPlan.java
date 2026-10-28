// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.model.ColumnWidth;

/** Explicit bounded future-information declarations for a replayed row source. Rows are zero-based
 * body rows, excluding repeating headers. The factory must reopen a stable immutable snapshot;
 * the exporter verifies its complete semantic digest, including formats, spans and image identities.
 * No database query, file or network resource is opened implicitly by this value.
 */
public record StreamingReplayPlan(List<ColumnWidth> columns, int maxProbeRows, long maxProbeBytes,
        List<Target> targets, List<Bookmark> bookmarks, List<Footnote> footnotes, String contentsTitle) {
    public static final StreamingReplayPlan EMPTY = builder().build();
    public record Target(String id, long bodyRow, String blockId, String number) {
        public Target {
            Objects.requireNonNull(id, "id"); Objects.requireNonNull(blockId, "blockId"); Objects.requireNonNull(number, "number");
            if (id.isBlank() || id.length() > 1024 || number.length() > 1024 || bodyRow < -1
                    || (bodyRow >= 0) == !blockId.isEmpty()) { throw new IllegalArgumentException("invalid replay target"); }
        }
    }
    public record Bookmark(String title, String targetId, int level) {
        public Bookmark {
            if (Objects.requireNonNull(title, "title").isBlank() || title.length() > 10_000
                    || Objects.requireNonNull(targetId, "targetId").isBlank() || level < 1 || level > 10_000) {
                throw new IllegalArgumentException("invalid replay bookmark");
            }
        }
    }
    /** Footnotes attach to the first row of a connected vertical-span group and appear once, on its
     * first page. A REFERENCE_NUMBER field targeting id renders its generated [n] label. The source
     * marker remains an explicit reserved field; its UTF-16 range is never rewritten.
     */
    public record Footnote(String id, long bodyRow, String text) {
        public Footnote {
            if (Objects.requireNonNull(id, "id").isBlank() || id.length() > 1024 || bodyRow < 0
                    || Objects.requireNonNull(text, "text").isBlank()) { throw new IllegalArgumentException("invalid streaming footnote"); }
        }
    }
    public StreamingReplayPlan {
        columns = List.copyOf(columns); targets = List.copyOf(targets); bookmarks = List.copyOf(bookmarks); footnotes = List.copyOf(footnotes);
        Objects.requireNonNull(contentsTitle, "contentsTitle");
        if (maxProbeRows <= 0 || maxProbeRows > 10_000 || maxProbeBytes <= 0 || targets.size() > 10_000
                || bookmarks.size() > 10_000 || footnotes.size() > 10_000 || contentsTitle.length() > 10_000) {
            throw new IllegalArgumentException("replay declaration budget exceeded");
        }
        var ids = new java.util.HashSet<String>();
        for (var target : targets) { if (!ids.add(target.id())) { throw new IllegalArgumentException("duplicate replay target"); } }
        for (var note : footnotes) { if (!ids.add(note.id())) { throw new IllegalArgumentException("duplicate replay note target"); } }
        int previous = 0;
        for (var bookmark : bookmarks) {
            if (!ids.contains(bookmark.targetId()) || bookmark.level() > previous + 1) { throw new IllegalArgumentException("invalid replay bookmark target or hierarchy"); }
            previous = bookmark.level();
        }
        if (!contentsTitle.isEmpty() && bookmarks.isEmpty()) { throw new IllegalArgumentException("streaming contents require bookmarks"); }
    }
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private List<ColumnWidth> columns = List.of();
        private int probeRows = 64;
        private long probeBytes = 1_000_000;
        private final List<Target> targets = new ArrayList<>();
        private final List<Bookmark> bookmarks = new ArrayList<>();
        private final List<Footnote> notes = new ArrayList<>();
        private String contents = "";
        private Builder() { }
        /** Replaces fixed widths with a bounded prefix probe. Later rows use the resolved fixed
         * widths and wrap normally; they never silently resize previously emitted pages. */
        public Builder columns(List<ColumnWidth> value, int maxRows, long maxBytes) {
            columns = List.copyOf(value); probeRows = maxRows; probeBytes = maxBytes; return this;
        }
        public Builder rowTarget(String id, long row, String number) { targets.add(new Target(id, row, "", number)); return this; }
        public Builder blockTarget(String id, String block, String number) { targets.add(new Target(id, -1, block, number)); return this; }
        public Builder bookmark(String title, String target, int level) { bookmarks.add(new Bookmark(title, target, level)); return this; }
        public Builder footnote(String id, long row, String text) { notes.add(new Footnote(id, row, text)); return this; }
        public Builder contents(String title) { contents = Objects.requireNonNull(title, "title"); return this; }
        public StreamingReplayPlan build() { return new StreamingReplayPlan(columns, probeRows, probeBytes, targets, bookmarks, notes, contents); }
    }
}
