// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.Objects;
import java.util.function.Consumer;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;

/** Optional front matter derived from bookmarks. Fixed 48 pt page-label column keeps pagination independent of numbers. */
public record TableOfContents(boolean enabled, String title, ParagraphStyle style, ContentsStyle appearance) {
    public static final TableOfContents NONE = new TableOfContents(false, "", ParagraphStyle.of(12));

    public TableOfContents {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(appearance, "appearance");
        if (enabled && title.isBlank()) {
            throw new IllegalArgumentException("contents title must not be blank");
        }
    }

    /** Compatibility constructor: title and page-label styles retain their legacy derivation; all colors are black. */
    public TableOfContents(boolean enabled, String title, ParagraphStyle style) {
        this(enabled, title, style, ContentsStyle.DEFAULT);
    }

    /** Complete title style, derived from the current entry base unless explicitly declared.
     * The legacy derivation scales size/line height by 1.5, with zero indents, LEFT and STANDARD breaks. */
    public ParagraphStyle titleStyle() {
        return appearance.titleStyle().orElseGet(() -> new ParagraphStyle(style.fontSize() * 1.5,
                style.lineHeight() * 1.5, 0, 0, TextAlignment.LEFT));
    }

    /** Complete single-line label style; legacy derivation uses base size/line height, zero indents, RIGHT and STANDARD. */
    public ParagraphStyle pageLabelStyle() {
        return appearance.pageLabelStyle().orElseGet(() -> new ParagraphStyle(style.fontSize(), style.lineHeight(),
                0, 0, TextAlignment.RIGHT));
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().enabled(enabled()).title(title()).style(style()).appearance(appearance()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private boolean enabled = true;
        private String title = "目录 / Contents";
        private ParagraphStyle style = ParagraphStyle.of(12);
        private ContentsStyle appearance = ContentsStyle.DEFAULT;

        private Builder() { }
        public Builder enabled(boolean value) { enabled = value; return this; }
        public Builder title(String value) { title = java.util.Objects.requireNonNull(value, "title"); return this; }
        public Builder style(ParagraphStyle value) { style = java.util.Objects.requireNonNull(value, "style"); return this; }
        /** Replaces appearance declarations; later entry-style changes recompute only undeclared typography. */
        public Builder appearance(ContentsStyle value) { appearance = Objects.requireNonNull(value, "appearance"); return this; }
        /** Edits an independent declaration copy synchronously and snapshots before returning; callbacks are not retained. */
        public Builder appearance(Consumer<ContentsStyle.Builder> configure) {
            var value = appearance.toBuilder();
            Objects.requireNonNull(configure, "configure").accept(value);
            return appearance(value.build());
        }

        public TableOfContents build() { return new TableOfContents(enabled, title, style, appearance); }
    }
}
