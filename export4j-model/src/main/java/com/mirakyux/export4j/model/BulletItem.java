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
        List<InlineText> inlines, InlineStyle markerStyle,
        List<Paragraph.ImageSpan> images, List<Paragraph.FieldSpan> fields, List<Paragraph.VectorSpan> vectors) implements Block {
    public BulletItem {
        inlines = List.copyOf(inlines);
        images = List.copyOf(images); fields = List.copyOf(fields); vectors = List.copyOf(vectors);
        new Paragraph(id, text, format, inlines, images, fields, vectors);
        Objects.requireNonNull(marker, "marker");
        Objects.requireNonNull(markerStyle, "markerStyle");
        if (marker.isBlank() || marker.codePointCount(0, marker.length()) > 8
                || marker.codePoints().anyMatch(Character::isISOControl) || level < 1 || level > 9) {
            throw new IllegalArgumentException("invalid bullet marker or level");
        }
    }

    /** 保留原有仅文字及独立标记样式的构造器。 */
    public BulletItem(String id, String text, String marker, int level, ParagraphFormat format,
            List<InlineText> inlines, InlineStyle markerStyle) {
        this(id, text, marker, level, format, inlines, markerStyle, List.of(), List.of(), List.of());
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

    /** 混合正文与原子对象；列表标记继续使用独立样式。 */
    public static BulletItem mixed(String id, List<? extends InlineContent> content, String marker, int level,
            ParagraphFormat format, InlineStyle markerStyle) {
        var p = Paragraph.mixed(id, content, format);
        return new BulletItem(id, p.text(), marker, level, format, p.inlines(), markerStyle, p.images(), p.fields(), p.vectors());
    }

    public static BulletItem mixed(String id, List<? extends InlineContent> content, String marker, int level, ParagraphFormat format) {
        return mixed(id, content, marker, level, format, defaultMarkerStyle(format));
    }

    /** Returns only the body; the bullet is added by semantic layout or an output adapter. */
    public Paragraph paragraph() { return new Paragraph(id, text, format, inlines, images, fields, vectors); }

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
        builder.inlines = inlines; builder.images = images; builder.fields = fields; builder.vectors = vectors;
        if (!images.isEmpty() || !fields.isEmpty() || !vectors.isEmpty()) {
            builder.mixedContent = paragraph().content(); builder.mixedFontSize = format.fontSize();
        }
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
        private List<Paragraph.ImageSpan> images = List.of();
        private List<Paragraph.FieldSpan> fields = List.of();
        private List<Paragraph.VectorSpan> vectors = List.of();
        private List<InlineContent> mixedContent;
        private double mixedFontSize;
        private InlineStyle markerStyle = InlineStyle.of(12);
        private boolean markerStyleSet;

        private Builder() { }
        public Builder id(String value) { id = Objects.requireNonNull(value, "id"); return this; }
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
            if (mixedContent != null && Double.compare(mixedFontSize, format.fontSize()) != 0) {
                return BulletItem.mixed(id, mixedContent, marker, level, format,
                        markerStyleSet ? markerStyle : BulletItem.defaultMarkerStyle(format));
            }
            return new BulletItem(id, text, marker, level, format, inlines,
                    markerStyleSet ? markerStyle : BulletItem.defaultMarkerStyle(format), images, fields, vectors);
        }
    }
}
