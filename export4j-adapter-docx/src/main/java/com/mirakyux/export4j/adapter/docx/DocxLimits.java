// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

/** Byte, entry and cumulative semantic-tree limits. Source text counts Unicode code points;
 * table slots count rows times logical columns, including covered merge slots, across every table.
 * maxBlocks includes nested paragraphs and tables, but excludes structural OOXML paragraphs.
 */
public record DocxLimits(long maxInputBytes, int maxEntryBytes, long maxExpandedBytes, int maxEntries,
        int maxBlocks, long maxTableSlots, long maxSourceCodePoints) {
    public static final DocxLimits DEFAULT = new DocxLimits(32L * 1024 * 1024, 32 * 1024 * 1024,
            128L * 1024 * 1024, 4096, 100_000, 1_000_000, 4_000_000);
    /** Compatibility constructor retaining the default cumulative slot and source-text limits. */
    public DocxLimits(long maxInputBytes, int maxEntryBytes, long maxExpandedBytes, int maxEntries, int maxBlocks) {
        this(maxInputBytes, maxEntryBytes, maxExpandedBytes, maxEntries, maxBlocks, 1_000_000, 4_000_000);
    }
    public DocxLimits {
        if (maxInputBytes < 1 || maxEntryBytes < 1 || maxExpandedBytes < 1 || maxEntries < 1 || maxBlocks < 1
                || maxTableSlots < 1 || maxSourceCodePoints < 1) { throw new IllegalArgumentException("invalid DOCX limits"); }
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().maxInputBytes(maxInputBytes()).maxEntryBytes(maxEntryBytes()).maxExpandedBytes(maxExpandedBytes()).maxEntries(maxEntries()).maxBlocks(maxBlocks()).maxTableSlots(maxTableSlots()).maxSourceCodePoints(maxSourceCodePoints()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private long maxInputBytes = DEFAULT.maxInputBytes();
        private int maxEntryBytes = DEFAULT.maxEntryBytes();
        private long maxExpandedBytes = DEFAULT.maxExpandedBytes();
        private int maxEntries = DEFAULT.maxEntries();
        private int maxBlocks = DEFAULT.maxBlocks();
        private long maxTableSlots = DEFAULT.maxTableSlots();
        private long maxSourceCodePoints = DEFAULT.maxSourceCodePoints();

        private Builder() { }
        public Builder maxInputBytes(long value) { maxInputBytes = value; return this; }
        public Builder maxEntryBytes(int value) { maxEntryBytes = value; return this; }
        public Builder maxExpandedBytes(long value) { maxExpandedBytes = value; return this; }
        public Builder maxEntries(int value) { maxEntries = value; return this; }
        public Builder maxBlocks(int value) { maxBlocks = value; return this; }
        public Builder maxTableSlots(long value) { maxTableSlots = value; return this; }
        public Builder maxSourceCodePoints(long value) { maxSourceCodePoints = value; return this; }

        public DocxLimits build() { return new DocxLimits(maxInputBytes, maxEntryBytes, maxExpandedBytes, maxEntries, maxBlocks, maxTableSlots, maxSourceCodePoints); }
    }
}
