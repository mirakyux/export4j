// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

/** Positive bounds for ordinary disk-backed PDF output. Per-page PDFBox work is additionally
 * governed by PdfLimits. Temporary bytes conservatively count the current page's serialized
 * equivalent and disk indices, although page serialization is discarded without a file;
 * output bytes count the complete destination. The per-page object bound counts the source
 * graph even when a font resource reuses an earlier output object. Neither byte bound measures process memory.
 */
public record PdfSpoolLimits(int maxPages, long maxCommands, int maxObjectsPerPage, long maxObjects,
        long maxPageBytes, long maxTemporaryBytes, long maxOutputBytes) {
    public static final PdfSpoolLimits DEFAULT = new PdfSpoolLimits(100_000, 10_000_000,
            100_000, 10_000_000, 64L * 1024 * 1024, 512L * 1024 * 1024, 8L * 1024 * 1024 * 1024);

    public PdfSpoolLimits {
        if (maxPages <= 0 || maxCommands <= 0 || maxObjectsPerPage <= 0 || maxObjects <= 0
                || maxPageBytes <= 0 || maxTemporaryBytes <= 0 || maxOutputBytes <= 0
                || maxOutputBytes >= 10_000_000_000L) {
            throw new IllegalArgumentException("PDF spool limits must be positive and output offsets fit classic xref");
        }
    }
}
