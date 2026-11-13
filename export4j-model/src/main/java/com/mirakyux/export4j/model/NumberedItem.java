// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Immutable decimal list item with a complete, independent marker style. Dimensions use points.
 * Shared listId continues across sections; restartAt=0 continues, positive restarts.
 * The marker has no independent ID or hyperlink. Its style does not change the body's runs or fixed line height.
 * Fonts are supplied during layout; an unknown role, missing glyph or insufficient line height fails there.
 * Required values are non-null and inline runs are copied. Instances may be shared between threads.
 */
public record NumberedItem(String id, String listId, String text, int restartAt, ParagraphFormat format,
        int level, List<InlineText> inlines, InlineStyle markerStyle,
        List<Paragraph.ImageSpan> images, List<Paragraph.FieldSpan> fields, List<Paragraph.VectorSpan> vectors) implements Block {
    public NumberedItem {
        inlines = List.copyOf(inlines);
        images = List.copyOf(images); fields = List.copyOf(fields); vectors = List.copyOf(vectors);
        new Paragraph(id, text, format, inlines, images, fields, vectors);
        Objects.requireNonNull(listId, "listId");
        Objects.requireNonNull(markerStyle, "markerStyle");
        if (listId.isBlank() || restartAt < 0 || level < 1 || level > 9) {
            throw new IllegalArgumentException("invalid list ID or restart");
        }
    }

    /** 保留原有仅文字及独立标记样式的构造器。 */
    public NumberedItem(String id, String listId, String text, int restartAt, ParagraphFormat format,
            int level, List<InlineText> inlines, InlineStyle markerStyle) {
        this(id, listId, text, restartAt, format, level, inlines, markerStyle, List.of(), List.of(), List.of());
    }

    /** Compatibility constructor: the marker uses the paragraph's default font size and default styling. */
    public NumberedItem(String id, String listId, String text, int restartAt, ParagraphFormat format,
            int level, List<InlineText> inlines) {
        this(id, listId, text, restartAt, format, level, inlines, defaultMarkerStyle(format));
    }

    public NumberedItem(String id, String listId, String text, int restartAt, ParagraphFormat format, int level) {
        this(id, listId, text, restartAt, format, level, List.of());
    }

    /** Styled text and external hyperlinks; the numeric prefix retains the legacy paragraph default style. */
    public static NumberedItem rich(String id, String listId, List<InlineText> inlines, int restartAt, ParagraphFormat format, int level) {
        return rich(id, listId, inlines, restartAt, format, level, defaultMarkerStyle(format));
    }

    /** Styled body and a complete independent prefix style; the prefix does not inherit from the first run. */
    public static NumberedItem rich(String id, String listId, List<InlineText> inlines, int restartAt,
            ParagraphFormat format, int level, InlineStyle markerStyle) {
        var paragraph = new Paragraph(id, inlines, format);
        return new NumberedItem(id, listId, paragraph.text(), restartAt, format, level, paragraph.inlines(), markerStyle);
    }

    /** 混合正文与原子对象；数字前缀独立计数并使用独立样式。 */
    public static NumberedItem mixed(String id, String listId, List<? extends InlineContent> content, int restartAt,
            ParagraphFormat format, int level, InlineStyle markerStyle) {
        var p = Paragraph.mixed(id, content, format);
        return new NumberedItem(id, listId, p.text(), restartAt, format, level, p.inlines(), markerStyle, p.images(), p.fields(), p.vectors());
    }

    public static NumberedItem mixed(String id, String listId, List<? extends InlineContent> content, int restartAt,
            ParagraphFormat format, int level) {
        return mixed(id, listId, content, restartAt, format, level, defaultMarkerStyle(format));
    }

    /** Returns only the body; numbering is resolved by semantic layout or an output adapter. */
    public Paragraph paragraph() { return new Paragraph(id, text, format, inlines, images, fields, vectors); }

    public NumberedItem(String id, String listId, String text, int restartAt, ParagraphFormat format) {
        this(id, listId, text, restartAt, format, 1);
    }

    private static InlineStyle defaultMarkerStyle(ParagraphFormat format) {
        return InlineStyle.of(Objects.requireNonNull(format, "format").fontSize());
    }

    /** Creates task-confined construction state. ID and listId must be supplied before build. */
    public static Builder builder() { return new Builder(); }

    /** Copies every resolved value, including the complete marker style, into independent construction state.
     * Changing the format does not rewrite the copied style; defaultMarkerStyle() explicitly restores derivation.
     */
    public Builder toBuilder() {
        var builder = new Builder().id(id).listId(listId).text(text).restartAt(restartAt)
                .format(format).level(level).markerStyle(markerStyle);
        builder.inlines = inlines; builder.images = images; builder.fields = fields; builder.vectors = vectors;
        if (!images.isEmpty() || !fields.isEmpty() || !vectors.isEmpty()) {
            builder.mixedContent = paragraph().content(); builder.mixedFontSize = format.fontSize();
        }
        return builder;
    }

    /** Mutable, task-confined builder. Format and style callbacks are synchronous and retained only as values.
     * Without markerStyle, build derives the legacy marker from the final format; a declared style is a snapshot.
     * build validates required values and list ranges and copies collections; it does not load fonts or perform I/O.
     */
    public static final class Builder {
        private String id = "";
        private String listId = "";
        private String text = "";
        private int restartAt;
        private ParagraphFormat format = ParagraphFormat.of(12);
        private int level = 1;
        private List<InlineText> inlines = List.of();
        private List<Paragraph.ImageSpan> images = List.of();
        private List<Paragraph.FieldSpan> fields = List.of();
        private List<Paragraph.VectorSpan> vectors = List.of();
        private List<InlineContent> mixedContent;
        private double mixedFontSize;
        private InlineStyle markerStyle = InlineStyle.of(12);
        private boolean markerStyleSet;

        private Builder() { }
        public Builder id(String value) { id = Objects.requireNonNull(value, "id"); return this; }
        public Builder listId(String value) { listId = Objects.requireNonNull(value, "listId"); return this; }
        /** Replaces any prior styled body with plain text. */
        public Builder text(String value) {
            text = Objects.requireNonNull(value, "text"); inlines = List.of(); images = List.of(); fields = List.of(); vectors = List.of(); mixedContent = null; return this;
        }
        /** Replaces any prior body and immediately snapshots styled runs and their external links. */
        public Builder inlines(List<InlineText> value) {
            var copy = List.copyOf(value);
            text = copy.stream().map(InlineText::text).collect(java.util.stream.Collectors.joining());
            inlines = copy; images = List.of(); fields = List.of(); vectors = List.of(); mixedContent = null; return this;
        }
        /** 立即快照混合内容；build 按最终段落格式生成对象占位样式，保留显式运行及对象值。 */
        public Builder content(List<? extends InlineContent> value) {
            var snapshot = List.<InlineContent>copyOf(value);
            var p = Paragraph.mixed("inline", snapshot, format);
            text = p.text(); inlines = p.inlines(); images = p.images(); fields = p.fields(); vectors = p.vectors();
            mixedContent = snapshot;
            mixedFontSize = format.fontSize();
            return this;
        }
        public Builder restartAt(int value) { restartAt = value; return this; }
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
            var value = (markerStyleSet ? markerStyle : NumberedItem.defaultMarkerStyle(format)).toBuilder();
            configure.accept(value); return markerStyle(value.build());
        }
        /** Clears the marker declaration; subsequent build uses the final paragraph format's default style. */
        public Builder defaultMarkerStyle() { markerStyleSet = false; return this; }
        public NumberedItem build() {
            if (mixedContent != null && Double.compare(mixedFontSize, format.fontSize()) != 0) {
                return NumberedItem.mixed(id, listId, mixedContent, restartAt, format, level,
                        markerStyleSet ? markerStyle : NumberedItem.defaultMarkerStyle(format));
            }
            return new NumberedItem(id, listId, text, restartAt, format, level, inlines,
                    markerStyleSet ? markerStyle : NumberedItem.defaultMarkerStyle(format), images, fields, vectors);
        }
    }
}
