// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

/**
 * Positive per-document budgets. Image pixels count every draw, even repeated resources.
 * These are allocation/structure limits, not a wall-clock timeout or a sandbox.
 */
public record PdfLimits(int maxPages, int maxCommands, int maxStateDepth, long maxImagePixels,
        long maxTextCodePoints, long maxPathSegments, long maxFontBytes,
        int maxInteractions, long maxInteractionCodePoints) {
    public static final PdfLimits DEFAULT = new PdfLimits(
            1_000, 1_000_000, 64, 25_000_000, 2_000_000, 1_000_000, 64L * 1024 * 1024,
            10_000, 1_000_000);

    /** Retains the seven-budget constructor with default interaction limits. */
    public PdfLimits(int maxPages, int maxCommands, int maxStateDepth, long maxImagePixels,
            long maxTextCodePoints, long maxPathSegments, long maxFontBytes) {
        this(maxPages, maxCommands, maxStateDepth, maxImagePixels, maxTextCodePoints,
                maxPathSegments, maxFontBytes, 10_000, 1_000_000);
    }

    /** Retains the initial six-budget constructor, using the default 64 MiB font budget. */
    public PdfLimits(int maxPages, int maxCommands, int maxStateDepth, long maxImagePixels,
            long maxTextCodePoints, long maxPathSegments) {
        this(maxPages, maxCommands, maxStateDepth, maxImagePixels,
                maxTextCodePoints, maxPathSegments, 64L * 1024 * 1024);
    }

    public PdfLimits {
        if (maxPages <= 0 || maxCommands <= 0 || maxStateDepth <= 0 || maxImagePixels <= 0
                || maxTextCodePoints <= 0 || maxPathSegments <= 0 || maxFontBytes <= 0
                || maxInteractions <= 0 || maxInteractionCodePoints <= 0) {
            throw new IllegalArgumentException("PDF limits must be positive");
        }
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().maxPages(maxPages()).maxCommands(maxCommands()).maxStateDepth(maxStateDepth()).maxImagePixels(maxImagePixels()).maxTextCodePoints(maxTextCodePoints()).maxPathSegments(maxPathSegments()).maxFontBytes(maxFontBytes()).maxInteractions(maxInteractions()).maxInteractionCodePoints(maxInteractionCodePoints()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private int maxPages = DEFAULT.maxPages();
        private int maxCommands = DEFAULT.maxCommands();
        private int maxStateDepth = DEFAULT.maxStateDepth();
        private long maxImagePixels = DEFAULT.maxImagePixels();
        private long maxTextCodePoints = DEFAULT.maxTextCodePoints();
        private long maxPathSegments = DEFAULT.maxPathSegments();
        private long maxFontBytes = DEFAULT.maxFontBytes();
        private int maxInteractions = DEFAULT.maxInteractions();
        private long maxInteractionCodePoints = DEFAULT.maxInteractionCodePoints();

        private Builder() { }
        public Builder maxPages(int value) { maxPages = value; return this; }
        public Builder maxCommands(int value) { maxCommands = value; return this; }
        public Builder maxStateDepth(int value) { maxStateDepth = value; return this; }
        public Builder maxImagePixels(long value) { maxImagePixels = value; return this; }
        public Builder maxTextCodePoints(long value) { maxTextCodePoints = value; return this; }
        public Builder maxPathSegments(long value) { maxPathSegments = value; return this; }
        public Builder maxFontBytes(long value) { maxFontBytes = value; return this; }
        public Builder maxInteractions(int value) { maxInteractions = value; return this; }
        public Builder maxInteractionCodePoints(long value) { maxInteractionCodePoints = value; return this; }

        public PdfLimits build() { return new PdfLimits(maxPages, maxCommands, maxStateDepth, maxImagePixels, maxTextCodePoints, maxPathSegments, maxFontBytes, maxInteractions, maxInteractionCodePoints); }
    }
}
