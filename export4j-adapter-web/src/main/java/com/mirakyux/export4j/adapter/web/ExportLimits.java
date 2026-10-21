// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

/** Immutable per-call bounds. Source budgets include all sections and unique fixed region sources;
 * output bytes include repeated images/regions. No hard RSS or malicious-input sandbox is promised.
 */
public record ExportLimits(int maxSections, int maxBlocks, int maxTableSlots, int maxCodePoints,
        int maxInlineItems, int maxImages, long maxImageBytes, long maxOutputBytes, int maxDiagnostics) {
    public static final ExportLimits DEFAULT = new ExportLimits(1_000, 20_000, 200_000, 2_000_000,
            100_000, 1_024, 64L * 1024 * 1024, 64L * 1024 * 1024, 10_000);
    public ExportLimits {
        if (maxSections <= 0 || maxBlocks <= 0 || maxTableSlots <= 0 || maxCodePoints <= 0
                || maxInlineItems <= 0 || maxImages <= 0 || maxImageBytes <= 0
                || maxOutputBytes <= 0 || maxOutputBytes > Integer.MAX_VALUE - 8L || maxDiagnostics <= 0) {
            throw new IllegalArgumentException("export limits must be positive and output must fit a Java byte array");
        }
    }
    public static Builder builder() { return new Builder(); }
    /** Task-confined named limits; omitted values use DEFAULT. */
    public static final class Builder {
        private int sections = DEFAULT.maxSections(), blocks = DEFAULT.maxBlocks(), slots = DEFAULT.maxTableSlots();
        private int points = DEFAULT.maxCodePoints(), inlines = DEFAULT.maxInlineItems(), images = DEFAULT.maxImages();
        private long imageBytes = DEFAULT.maxImageBytes(), outputBytes = DEFAULT.maxOutputBytes();
        private int diagnostics = DEFAULT.maxDiagnostics();
        private Builder() { }
        public Builder maxSections(int value) { sections = value; return this; }
        public Builder maxBlocks(int value) { blocks = value; return this; }
        public Builder maxTableSlots(int value) { slots = value; return this; }
        public Builder maxCodePoints(int value) { points = value; return this; }
        public Builder maxInlineItems(int value) { inlines = value; return this; }
        public Builder maxImages(int value) { images = value; return this; }
        public Builder maxImageBytes(long value) { imageBytes = value; return this; }
        public Builder maxOutputBytes(long value) { outputBytes = value; return this; }
        public Builder maxDiagnostics(int value) { diagnostics = value; return this; }
        public ExportLimits build() { return new ExportLimits(sections, blocks, slots, points, inlines, images, imageBytes, outputBytes, diagnostics); }
    }
}
