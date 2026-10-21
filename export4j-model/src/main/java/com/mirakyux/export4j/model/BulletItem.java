// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Immutable bulleted item with an explicit marker, nesting level (1 through 9), and complete marker style.
 * Dimensions use points. The marker has no independent ID or hyperlink, and does not inherit from body runs.
 * Its style does not enlarge the fixed line height; fonts and line-height fit are checked during layout.
 * Required values are non-null and inline runs are copied. Instances may be shared between threads.
 */
public record BulletItem(String id, String text, String marker, int level, ParagraphFormat format,
        List<InlineText> inlines, InlineStyle markerStyle) implements Block {
    public BulletItem {
        inlines = List.copyOf(inlines);
        new Paragraph(id, text, format, inlines);
        Objects.requireNonNull(marker, "marker");
        Objects.requireNonNull(markerStyle, "markerStyle");
        if (marker.isBlank() || marker.codePointCount(0, marker.length()) > 8
                || marker.codePoints().anyMatch(Character::isISOControl) || level < 1 || level > 9) {
            throw new IllegalArgumentException("invalid bullet marker or level");
        }
    }

    /** Compatibility constructor: the marker uses the paragraph's default font size and default styling. */
    public BulletItem(String id, String text, String marker, int level, ParagraphFormat format, List<InlineText> inlines) {
        this(id, text, marker, level, format, inlines, defaultMarkerStyle(format));
    }

    public BulletItem(String id, String text, String marker, int level, ParagraphFormat format) {
        this(id, text, marker, level, format, List.of());
    }

    /** Styled text and external hyperlinks; the bullet retains the legacy paragraph default style. */
    public static BulletItem rich(String id, List<InlineText> inlines, String marker, int level, ParagraphFormat format) {
        return rich(id, inlines, marker, level, format, defaultMarkerStyle(format));
    }

    /** Styled body and a complete independent bullet style; the marker does not inherit from the first run. */
    public static BulletItem rich(String id, List<InlineText> inlines, String marker, int level,
            ParagraphFormat format, InlineStyle markerStyle) {
        var paragraph = new Paragraph(id, inlines, format);
        return new BulletItem(id, paragraph.text(), marker, level, format, paragraph.inlines(), markerStyle);
    }

    /** Returns only the body; the bullet is added by semantic layout or an output adapter. */
    public Paragraph paragraph() { return new Paragraph(id, text, format, inlines); }

    private static InlineStyle defaultMarkerStyle(ParagraphFormat format) {
        return InlineStyle.of(Objects.requireNonNull(format, "format").fontSize());
    }

    /** Creates task-confined construction state. ID must be supplied before build. */
    public static Builder builder() { return new Builder(); }

    /** Copies every resolved value, including the complete marker style, into independent construction state.
     * Changing the format does not rewrite the copied style; defaultMarkerStyle() explicitly restores derivation.
     */
    public Builder toBuilder() {
        var builder = new Builder().id(id).text(text).marker(marker).level(level).format(format).markerStyle(markerStyle);
        if (!inlines.isEmpty()) { builder.inlines(inlines); }
        return builder;
    }

    /** Mutable, task-confined builder. Each style callback is synchronous and retained only as a value.
     * Without markerStyle, build derives the legacy marker from the final format; a declared style is a snapshot.
     * build validates required values and list ranges and copies collections; it does not load fonts or perform I/O.
     */
    public static final class Builder {
        private String id = "";
        private String text = "";
        private String marker = "•";
        private int level = 1;
        private ParagraphFormat format = ParagraphFormat.of(12);
        private List<InlineText> inlines = List.of();
        private InlineStyle markerStyle = InlineStyle.of(12);
        private boolean markerStyleSet;

        private Builder() { }
        public Builder id(String value) { id = Objects.requireNonNull(value, "id"); return this; }
        /** Replaces any prior styled body with plain text. */
        public Builder text(String value) {
            text = Objects.requireNonNull(value, "text"); inlines = List.of(); return this;
        }
        /** Replaces any prior body and immediately snapshots styled runs and their external links. */
        public Builder inlines(List<InlineText> value) {
            var copy = List.copyOf(value);
            text = copy.stream().map(InlineText::text).collect(java.util.stream.Collectors.joining());
            inlines = copy; return this;
        }
        public Builder marker(String value) { marker = Objects.requireNonNull(value, "marker"); return this; }
        public Builder level(int value) { level = value; return this; }
        public Builder format(ParagraphFormat value) { format = Objects.requireNonNull(value, "format"); return this; }
        /** Snapshots a synchronous edit of the current complete paragraph format. */
        public Builder format(Consumer<ParagraphFormat.Builder> configure) {
            Objects.requireNonNull(configure, "configure");
            var value = format.toBuilder(); configure.accept(value); return format(value.build());
        }
        /** Declares a complete style; later format changes leave every marker property unchanged. */
        public Builder markerStyle(InlineStyle value) {
            markerStyle = Objects.requireNonNull(value, "markerStyle"); markerStyleSet = true; return this;
        }
        /** Edits the declared style, or a legacy default at the current format's size, and snapshots the result. */
        public Builder markerStyle(Consumer<InlineStyle.Builder> configure) {
            Objects.requireNonNull(configure, "configure");
            var value = (markerStyleSet ? markerStyle : BulletItem.defaultMarkerStyle(format)).toBuilder();
            configure.accept(value); return markerStyle(value.build());
        }
        /** Clears the marker declaration; subsequent build uses the final paragraph format's default style. */
        public Builder defaultMarkerStyle() { markerStyleSet = false; return this; }
        public BulletItem build() {
            return new BulletItem(id, text, marker, level, format, inlines,
                    markerStyleSet ? markerStyle : BulletItem.defaultMarkerStyle(format));
        }
    }
}
