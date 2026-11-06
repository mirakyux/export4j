// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;

/** Atomic inline image. Dimensions and baseline shift are points; positive shift moves below the baseline.
 * The encoded resource is explicit and immutable. Images are not split, stretched or fetched implicitly.
 */
public record InlineImage(ImageResource resource, double width, double height, double baselineShift,
        String altText, String hyperlink, double rotation) implements InlineContent {
    public InlineImage {
        Objects.requireNonNull(resource, "resource"); Objects.requireNonNull(altText, "altText");
        Objects.requireNonNull(hyperlink, "hyperlink");
        if (!Double.isFinite(width) || width <= 0 || width > 14_400 || !Double.isFinite(height) || height <= 0 || height > 14_400
                || !Double.isFinite(baselineShift) || Math.abs(baselineShift) > 14_400
                || !Double.isFinite(rotation) || Math.abs(rotation) > 180 || altText.length() > 4096
                || altText.codePoints().anyMatch(cp -> Character.isISOControl(cp) || cp >= 0xd800 && cp <= 0xdfff || cp == 0xfffe || cp == 0xffff)) {
            throw new IllegalArgumentException("invalid inline image geometry or alternative text");
        }
        if (!hyperlink.isEmpty()) { new ExternalDestination(hyperlink); }
    }

    public InlineImage(ImageResource resource, double width, double height, double baselineShift,
            String altText, String hyperlink) {
        this(resource, width, height, baselineShift, altText, hyperlink, 0);
    }

    public InlineImage(ImageResource resource, double width, double height, double baselineShift, String altText) {
        this(resource, width, height, baselineShift, altText, "");
    }

    /** Sizes an explicit resource to a point width while preserving its pixel aspect ratio.
     * This explicit operation may enlarge the image; no pixel-to-point DPI is inferred.
     * Derived dimensions obey the same positive, finite 14,400 pt limit as the constructor.
     */
    public static InlineImage atWidth(ImageResource resource, double width, String altText) {
        Objects.requireNonNull(resource, "resource");
        requireExtent(width);
        double height = width * ((double) resource.pixelHeight() / resource.pixelWidth());
        return new InlineImage(resource, width, height, 0, altText);
    }

    /** Fits an explicit resource proportionally inside a positive finite point box, each extent
     * at most 14,400 pt. Fills the limiting dimension and may explicitly enlarge the image.
     * This does not change existing image constructors or introduce automatic layout scaling.
     */
    public static InlineImage fit(ImageResource resource, double maxWidth, double maxHeight, String altText) {
        Objects.requireNonNull(resource, "resource");
        requireExtent(maxWidth);
        requireExtent(maxHeight);
        double width = maxWidth;
        double height = maxWidth * ((double) resource.pixelHeight() / resource.pixelWidth());
        if (height > maxHeight) {
            height = maxHeight;
            width = Math.min(maxWidth, maxHeight * ((double) resource.pixelWidth() / resource.pixelHeight()));
        }
        return new InlineImage(resource, width, height, 0, altText);
    }

    private static void requireExtent(double value) {
        if (!Double.isFinite(value) || value <= 0 || value > 14_400) {
            throw new IllegalArgumentException("inline image extent must be positive, finite and at most 14400 points");
        }
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().resource(resource()).width(width()).height(height()).baselineShift(baselineShift()).altText(altText()).hyperlink(hyperlink()).rotation(rotation()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private ImageResource resource = null;
        private double width = 0;
        private double height = 0;
        private double baselineShift = 0;
        private String altText = "";
        private String hyperlink = "";
        private double rotation;

        private Builder() { }
        public Builder resource(ImageResource value) { resource = java.util.Objects.requireNonNull(value, "resource"); return this; }
        public Builder width(double value) { width = value; return this; }
        public Builder height(double value) { height = value; return this; }
        public Builder baselineShift(double value) { baselineShift = value; return this; }
        public Builder altText(String value) { altText = java.util.Objects.requireNonNull(value, "altText"); return this; }
        public Builder hyperlink(String value) { hyperlink = java.util.Objects.requireNonNull(value, "hyperlink"); return this; }
        /** Clockwise degrees, fitted inside the declared image box. */
        public Builder rotation(double value) { rotation = value; return this; }

        public InlineImage build() { return new InlineImage(resource, width, height, baselineShift, altText, hyperlink, rotation); }
    }
}
