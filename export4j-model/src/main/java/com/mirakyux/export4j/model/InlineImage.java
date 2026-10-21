// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;

/** Atomic inline image. Dimensions and baseline shift are points; positive shift moves below the baseline.
 * The encoded resource is explicit and immutable. Images are not split, stretched or fetched implicitly.
 */
public record InlineImage(ImageResource resource, double width, double height, double baselineShift,
        String altText, String hyperlink) implements InlineContent {
    public InlineImage {
        Objects.requireNonNull(resource, "resource"); Objects.requireNonNull(altText, "altText");
        Objects.requireNonNull(hyperlink, "hyperlink");
        if (!Double.isFinite(width) || width <= 0 || width > 14_400 || !Double.isFinite(height) || height <= 0 || height > 14_400
                || !Double.isFinite(baselineShift) || Math.abs(baselineShift) > 14_400 || altText.length() > 4096
                || altText.codePoints().anyMatch(cp -> Character.isISOControl(cp) || cp >= 0xd800 && cp <= 0xdfff || cp == 0xfffe || cp == 0xffff)) {
            throw new IllegalArgumentException("invalid inline image geometry or alternative text");
        }
        if (!hyperlink.isEmpty()) { new ExternalDestination(hyperlink); }
    }

    public InlineImage(ImageResource resource, double width, double height, double baselineShift, String altText) {
        this(resource, width, height, baselineShift, altText, "");
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().resource(resource()).width(width()).height(height()).baselineShift(baselineShift()).altText(altText()).hyperlink(hyperlink()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private ImageResource resource = null;
        private double width = 0;
        private double height = 0;
        private double baselineShift = 0;
        private String altText = "";
        private String hyperlink = "";

        private Builder() { }
        public Builder resource(ImageResource value) { resource = java.util.Objects.requireNonNull(value, "resource"); return this; }
        public Builder width(double value) { width = value; return this; }
        public Builder height(double value) { height = value; return this; }
        public Builder baselineShift(double value) { baselineShift = value; return this; }
        public Builder altText(String value) { altText = java.util.Objects.requireNonNull(value, "altText"); return this; }
        public Builder hyperlink(String value) { hyperlink = java.util.Objects.requireNonNull(value, "hyperlink"); return this; }

        public InlineImage build() { return new InlineImage(resource, width, height, baselineShift, altText, hyperlink); }
    }
}
