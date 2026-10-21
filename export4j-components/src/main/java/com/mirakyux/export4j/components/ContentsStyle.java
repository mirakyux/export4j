// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;

/**
 * Immutable declarations for contents title/page-label typography and three independent colors.
 * Unspecified typography is derived from the owning TableOfContents entry style, including after
 * toBuilder edits. Styles are complete values in points; colors are opaque sRGB. This value owns
 * no fonts or resources and is thread-safe. It does not select a document theme or font role.
 */
public final class ContentsStyle {
    /** Independent complete styles and colors; explicit intent is retained even for default values. */
    public enum Property { TITLE_STYLE, PAGE_LABEL_STYLE, TITLE_COLOR, ENTRY_COLOR, PAGE_LABEL_COLOR }

    /** No declarations: preserves the owning contents value's legacy derived styles and black colors. */
    public static final ContentsStyle DEFAULT = builder().build();

    private final ParagraphStyle titleStyle;
    private final ParagraphStyle pageLabelStyle;
    private final RgbColor titleColor;
    private final RgbColor entryColor;
    private final RgbColor pageLabelColor;
    private final Set<Property> explicitProperties;

    private ContentsStyle(Builder source) {
        titleStyle = source.titleStyle;
        pageLabelStyle = source.pageLabelStyle;
        titleColor = source.titleColor;
        entryColor = source.entryColor;
        pageLabelColor = source.pageLabelColor;
        explicitProperties = Set.copyOf(source.explicitProperties);
    }

    /** Starts with no declarations. */
    public static Builder builder() { return new Builder(); }
    /** Copies complete values and declaration intent into independent task-confined state. */
    public Builder toBuilder() { return new Builder(this); }

    /** Explicit title style, or empty when the owning entry style supplies the legacy derivation. */
    public Optional<ParagraphStyle> titleStyle() {
        return isExplicit(Property.TITLE_STYLE) ? Optional.of(titleStyle) : Optional.empty();
    }
    /** Explicit single-line page-label style, or empty for the owning entry style's derivation. */
    public Optional<ParagraphStyle> pageLabelStyle() {
        return isExplicit(Property.PAGE_LABEL_STYLE) ? Optional.of(pageLabelStyle) : Optional.empty();
    }
    public RgbColor titleColor() { return titleColor; }
    public RgbColor entryColor() { return entryColor; }
    public RgbColor pageLabelColor() { return pageLabelColor; }
    /** Immutable declaration set. */
    public Set<Property> explicitProperties() { return explicitProperties; }
    public boolean isExplicit(Property property) {
        return explicitProperties.contains(Objects.requireNonNull(property, "property"));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) { return true; }
        if (!(other instanceof ContentsStyle value)) { return false; }
        return titleStyle.equals(value.titleStyle) && pageLabelStyle.equals(value.pageLabelStyle)
                && titleColor.equals(value.titleColor) && entryColor.equals(value.entryColor)
                && pageLabelColor.equals(value.pageLabelColor) && explicitProperties.equals(value.explicitProperties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(titleStyle, pageLabelStyle, titleColor, entryColor, pageLabelColor, explicitProperties);
    }

    /** Mutable, task-confined configuration; build snapshots values and declaration intent. Nulls are rejected. */
    public static final class Builder {
        private ParagraphStyle titleStyle = ParagraphStyle.of(18);
        private ParagraphStyle pageLabelStyle = ParagraphStyle.of(12).withAlignment(TextAlignment.RIGHT);
        private RgbColor titleColor = RgbColor.BLACK;
        private RgbColor entryColor = RgbColor.BLACK;
        private RgbColor pageLabelColor = RgbColor.BLACK;
        private final EnumSet<Property> explicitProperties = EnumSet.noneOf(Property.class);

        private Builder() { }
        private Builder(ContentsStyle source) {
            titleStyle = source.titleStyle;
            pageLabelStyle = source.pageLabelStyle;
            titleColor = source.titleColor;
            entryColor = source.entryColor;
            pageLabelColor = source.pageLabelColor;
            explicitProperties.addAll(source.explicitProperties);
        }

        /** Selects the complete title typography; fixed line-height validation occurs with the actual fonts. */
        public Builder titleStyle(ParagraphStyle value) {
            titleStyle = Objects.requireNonNull(value, "titleStyle");
            explicitProperties.add(Property.TITLE_STYLE); return this;
        }
        /** Selects complete page-label typography; labels must fit one line in the fixed 48-point column. */
        public Builder pageLabelStyle(ParagraphStyle value) {
            pageLabelStyle = Objects.requireNonNull(value, "pageLabelStyle");
            explicitProperties.add(Property.PAGE_LABEL_STYLE); return this;
        }
        public Builder titleColor(RgbColor value) {
            titleColor = Objects.requireNonNull(value, "titleColor");
            explicitProperties.add(Property.TITLE_COLOR); return this;
        }
        public Builder entryColor(RgbColor value) {
            entryColor = Objects.requireNonNull(value, "entryColor");
            explicitProperties.add(Property.ENTRY_COLOR); return this;
        }
        public Builder pageLabelColor(RgbColor value) {
            pageLabelColor = Objects.requireNonNull(value, "pageLabelColor");
            explicitProperties.add(Property.PAGE_LABEL_COLOR); return this;
        }

        /** Restores derivation from the current owning entry style, preserving other declarations. */
        public Builder defaultTitleStyle() {
            titleStyle = ParagraphStyle.of(18);
            explicitProperties.remove(Property.TITLE_STYLE); return this;
        }
        /** Restores the legacy right-aligned derivation from the current owning entry style. */
        public Builder defaultPageLabelStyle() {
            pageLabelStyle = ParagraphStyle.of(12).withAlignment(TextAlignment.RIGHT);
            explicitProperties.remove(Property.PAGE_LABEL_STYLE); return this;
        }
        public Builder defaultTitleColor() {
            titleColor = RgbColor.BLACK;
            explicitProperties.remove(Property.TITLE_COLOR); return this;
        }
        public Builder defaultEntryColor() {
            entryColor = RgbColor.BLACK;
            explicitProperties.remove(Property.ENTRY_COLOR); return this;
        }
        public Builder defaultPageLabelColor() {
            pageLabelColor = RgbColor.BLACK;
            explicitProperties.remove(Property.PAGE_LABEL_COLOR); return this;
        }

        public ContentsStyle build() { return new ContentsStyle(this); }
    }
}
