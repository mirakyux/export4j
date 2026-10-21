// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

/** Immutable operation budgets. Decoded byte budgets count distinct streams, including
 * cross-reference and object streams, before PDFBox decodes them. They are not a process RSS bound.
 * Page, object, resource, text and operator budgets are cumulative across an operation.
 */
public record PdfLimits(int maxInputBytes, long maxTotalInputBytes, int maxInputs, int maxPages,
                        int maxObjects, int maxDepth, int maxResources,
                        long maxDecodedStreamBytes, long maxDecodedBytes, int maxTextCharacters,
                        long maxContentOperators, long maxImagePixels, long maxOutputBytes) {
    public static final PdfLimits DEFAULT = builder().build();

    public PdfLimits {
        if (maxInputBytes <= 0 || maxInputBytes > Integer.MAX_VALUE - 8 || maxTotalInputBytes <= 0
                || maxInputs <= 0 || maxPages <= 0 || maxObjects <= 0 || maxDepth <= 0 || maxDepth > 256
                || maxResources <= 0 || maxDecodedStreamBytes <= 0 || maxDecodedBytes <= 0
                || maxTextCharacters <= 0 || maxContentOperators <= 0 || maxImagePixels <= 0
                || maxOutputBytes <= 0) {
            throw new IllegalArgumentException("PDF budgets must be positive and depth must not exceed 256");
        }
    }

    /** Task-confined builder. Byte budgets use bytes; text uses UTF-16 code units; indices are zero based. */
    public static Builder builder() { return new Builder(); }

    public Builder toBuilder() {
        return builder().maxInputBytes(maxInputBytes).maxTotalInputBytes(maxTotalInputBytes)
                .maxInputs(maxInputs).maxPages(maxPages).maxObjects(maxObjects).maxDepth(maxDepth)
                .maxResources(maxResources).maxDecodedStreamBytes(maxDecodedStreamBytes)
                .maxDecodedBytes(maxDecodedBytes).maxTextCharacters(maxTextCharacters)
                .maxContentOperators(maxContentOperators).maxImagePixels(maxImagePixels).maxOutputBytes(maxOutputBytes);
    }

    public static final class Builder {
        private int maxInputBytes = 16 * 1024 * 1024;
        private long maxTotalInputBytes = 64L * 1024 * 1024;
        private int maxInputs = 32;
        private int maxPages = 1_000;
        private int maxObjects = 100_000;
        private int maxDepth = 64;
        private int maxResources = 10_000;
        private long maxDecodedStreamBytes = 16L * 1024 * 1024;
        private long maxDecodedBytes = 64L * 1024 * 1024;
        private int maxTextCharacters = 1_000_000;
        private long maxContentOperators = 1_000_000;
        private long maxImagePixels = 16_000_000;
        private long maxOutputBytes = 64L * 1024 * 1024;

        private Builder() { }
        public Builder maxInputBytes(int value) { maxInputBytes = value; return this; }
        public Builder maxTotalInputBytes(long value) { maxTotalInputBytes = value; return this; }
        public Builder maxInputs(int value) { maxInputs = value; return this; }
        public Builder maxPages(int value) { maxPages = value; return this; }
        public Builder maxObjects(int value) { maxObjects = value; return this; }
        public Builder maxDepth(int value) { maxDepth = value; return this; }
        public Builder maxResources(int value) { maxResources = value; return this; }
        public Builder maxDecodedStreamBytes(long value) { maxDecodedStreamBytes = value; return this; }
        public Builder maxDecodedBytes(long value) { maxDecodedBytes = value; return this; }
        public Builder maxTextCharacters(int value) { maxTextCharacters = value; return this; }
        public Builder maxContentOperators(long value) { maxContentOperators = value; return this; }
        public Builder maxImagePixels(long value) { maxImagePixels = value; return this; }
        public Builder maxOutputBytes(long value) { maxOutputBytes = value; return this; }
        public PdfLimits build() {
            return new PdfLimits(maxInputBytes, maxTotalInputBytes, maxInputs, maxPages, maxObjects,
                    maxDepth, maxResources, maxDecodedStreamBytes, maxDecodedBytes, maxTextCharacters,
                    maxContentOperators, maxImagePixels, maxOutputBytes);
        }
    }
}
