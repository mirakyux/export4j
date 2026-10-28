// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import java.util.Objects;
import java.io.IOException;
import org.apache.fontbox.ttf.OS2WindowsMetricsTable;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.pdfbox.io.RandomAccessReadBuffer;

/** Explicit static TrueType bytes for PDF stamps, replacements and source-font mappings;
 * no font discovery or implicit fallback.
 * Immutable snapshot; parsing and glyph support checks occur during preparation.
 */
public final class PdfFont {
    private final byte[] bytes;
    private final boolean subsetAllowed;
    private PdfFont(byte[] bytes, boolean subsetAllowed) { this.bytes = bytes; this.subsetAllowed = subsetAllowed; }
    public static PdfFont of(byte[] encoded) throws IOException {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length == 0 || encoded.length > 16 * 1024 * 1024) { throw new IllegalArgumentException("PDF stamp font byte budget exceeded"); }
        byte[] snapshot = encoded.clone();
        try (var buffer = new RandomAccessReadBuffer(snapshot); var font = new TTFParser().parse(buffer)) {
            if (font.getTableMap().containsKey("fvar") || font.getTableMap().containsKey("CFF2")) { throw new IOException("static TrueType stamp font required"); }
            var os2 = font.getOS2Windows(); int flags = os2 == null ? 0 : os2.getFsType();
            if ((flags & (OS2WindowsMetricsTable.FSTYPE_RESTRICTED | OS2WindowsMetricsTable.FSTYPE_BITMAP_ONLY)) != 0) { throw new IOException("stamp font embedding permission denied"); }
            return new PdfFont(snapshot, (flags & OS2WindowsMetricsTable.FSTYPE_NO_SUBSETTING) == 0);
        }
    }
    byte[] bytes() { return bytes; }
    boolean subsetAllowed() { return subsetAllowed; }
}
