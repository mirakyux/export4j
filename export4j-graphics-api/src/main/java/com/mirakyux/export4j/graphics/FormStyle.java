// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable declarations for editable field appearances, independent of the output backend.
 * Only explicitly set properties override each control's existing defaults; stored values of
 * unspecified properties do not select an appearance. All dimensions are points and all colors
 * are opaque sRGB. No resources are owned; built values are thread-safe, builders are task-confined.
 * Font size and alignment apply only to text and choice fields and are rejected by mark controls.
 * Color selects text color or the check/radio mark color. This is not a document text theme.
 */
public final class FormStyle {
    /** Independently declared complete properties. Border includes its mode, width and color. */
    public enum Property { FONT_SIZE, COLOR, BACKGROUND, BORDER, ALIGNMENT }

    /** No border, fixed width, or width capped at one tenth of the widget's shortest side. */
    public enum BorderMode { NONE, FIXED, ADAPTIVE }

    /** Horizontal alignment of editable text or the selected choice label. */
    public enum Alignment { LEFT, CENTER, RIGHT }

    /** No overrides: retains the distinct legacy appearance of each field type. */
    public static final FormStyle DEFAULT = builder().build();

    private final double fontSize;
    private final RgbColor color;
    private final boolean backgroundEnabled;
    private final RgbColor background;
    private final BorderMode borderMode;
    private final double borderWidth;
    private final RgbColor borderColor;
    private final Alignment alignment;
    private final Set<Property> explicitProperties;

    private FormStyle(Builder source) {
        fontSize = source.fontSize;
        color = source.color;
        backgroundEnabled = source.backgroundEnabled;
        background = source.background;
        borderMode = source.borderMode;
        borderWidth = source.borderWidth;
        borderColor = source.borderColor;
        alignment = source.alignment;
        explicitProperties = Set.copyOf(source.explicitProperties);
    }

    /** Starts with no explicitly declared properties. */
    public static Builder builder() { return new Builder(); }

    /** Copies stored values and declaration intent; later mutations cannot affect this value. */
    public Builder toBuilder() { return new Builder(this); }

    /** Stored positive point size; ignored unless FONT_SIZE is explicit. Zero auto-sizing is unsupported. */
    public double fontSize() { return fontSize; }
    /** Stored foreground color; ignored unless COLOR is explicit. */
    public RgbColor color() { return color; }
    /** Whether an explicit background paints the whole widget. */
    public boolean backgroundEnabled() { return backgroundEnabled; }
    /** Stored background color; ignored when disabled or BACKGROUND is unspecified. */
    public RgbColor background() { return background; }
    /** Stored border mode; ignored unless BORDER is explicit. */
    public BorderMode borderMode() { return borderMode; }
    /** Stored fixed width or adaptive maximum in points; zero disables a fixed border. */
    public double borderWidth() { return borderWidth; }
    /** Stored border color, independent of the check/radio mark color. */
    public RgbColor borderColor() { return borderColor; }
    /** Stored horizontal alignment; ignored unless ALIGNMENT is explicit. */
    public Alignment alignment() { return alignment; }
    /** Immutable set of explicit properties. */
    public Set<Property> explicitProperties() { return explicitProperties; }
    /** Returns whether this property overrides its control-specific default. */
    public boolean isExplicit(Property property) {
        return explicitProperties.contains(Objects.requireNonNull(property, "property"));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) { return true; }
        if (!(other instanceof FormStyle value)) { return false; }
        return Double.compare(fontSize, value.fontSize) == 0 && color.equals(value.color)
                && backgroundEnabled == value.backgroundEnabled && background.equals(value.background)
                && borderMode == value.borderMode && Double.compare(borderWidth, value.borderWidth) == 0
                && borderColor.equals(value.borderColor) && alignment == value.alignment
                && explicitProperties.equals(value.explicitProperties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fontSize, color, backgroundEnabled, background, borderMode,
                borderWidth, borderColor, alignment, explicitProperties);
    }

    /** Mutable builder; callbacks must finish synchronously and built values snapshot all state. */
    public static final class Builder {
        private double fontSize = 10;
        private RgbColor color = RgbColor.BLACK;
        private boolean backgroundEnabled = true;
        private RgbColor background = RgbColor.WHITE;
        private BorderMode borderMode = BorderMode.NONE;
        private double borderWidth;
        private RgbColor borderColor = RgbColor.BLACK;
        private Alignment alignment = Alignment.LEFT;
        private final EnumSet<Property> explicitProperties = EnumSet.noneOf(Property.class);

        private Builder() { }

        private Builder(FormStyle source) {
            fontSize = source.fontSize;
            color = source.color;
            backgroundEnabled = source.backgroundEnabled;
            background = source.background;
            borderMode = source.borderMode;
            borderWidth = source.borderWidth;
            borderColor = source.borderColor;
            alignment = source.alignment;
            explicitProperties.addAll(source.explicitProperties);
        }

        /** Selects a finite positive point size. Does not scan fonts or request automatic sizing. */
        public Builder fontSize(double value) {
            positive(value, "font size");
            fontSize = value;
            explicitProperties.add(Property.FONT_SIZE);
            return this;
        }

        /** Selects text or mark color; explicit black overrides a control's colored mark default. */
        public Builder color(RgbColor value) {
            color = Objects.requireNonNull(value, "color");
            explicitProperties.add(Property.COLOR);
            return this;
        }

        /** Paints an opaque background. */
        public Builder background(RgbColor value) {
            background = Objects.requireNonNull(value, "background");
            backgroundEnabled = true;
            explicitProperties.add(Property.BACKGROUND);
            return this;
        }

        /** Leaves the widget background transparent; does not change border or mark visibility. */
        public Builder noBackground() {
            backgroundEnabled = false;
            background = RgbColor.WHITE;
            explicitProperties.add(Property.BACKGROUND);
            return this;
        }

        /**
         * Selects a finite nonnegative fixed border width and color. Zero explicitly hides the border.
         * A positive border that cannot fit inside the final widget is rejected by the backend.
         */
        public Builder border(double width, RgbColor value) {
            if (!Double.isFinite(width) || width < 0) {
                throw new IllegalArgumentException("border width must be finite and nonnegative");
            }
            borderColor = Objects.requireNonNull(value, "borderColor");
            borderMode = BorderMode.FIXED;
            borderWidth = width == 0 ? 0 : width;
            explicitProperties.add(Property.BORDER);
            return this;
        }

        /** Selects min(maximum, shortest widget side / 10) points; maximum must be finite and positive. */
        public Builder adaptiveBorder(double maximum, RgbColor value) {
            positive(maximum, "adaptive border maximum");
            borderColor = Objects.requireNonNull(value, "borderColor");
            borderMode = BorderMode.ADAPTIVE;
            borderWidth = maximum;
            explicitProperties.add(Property.BORDER);
            return this;
        }

        /** Hides only the border. Check/radio marks keep their independent adaptive stroke width. */
        public Builder noBorder() {
            borderMode = BorderMode.NONE;
            borderWidth = 0;
            borderColor = RgbColor.BLACK;
            explicitProperties.add(Property.BORDER);
            return this;
        }

        /** Selects text/choice alignment; mark controls reject this explicit property. */
        public Builder alignment(Alignment value) {
            alignment = Objects.requireNonNull(value, "alignment");
            explicitProperties.add(Property.ALIGNMENT);
            return this;
        }

        /** Returns an independent immutable snapshot. */
        public FormStyle build() { return new FormStyle(this); }

        private static void positive(double value, String name) {
            if (!Double.isFinite(value) || value <= 0) {
                throw new IllegalArgumentException(name + " must be finite and positive");
            }
        }
    }
}
