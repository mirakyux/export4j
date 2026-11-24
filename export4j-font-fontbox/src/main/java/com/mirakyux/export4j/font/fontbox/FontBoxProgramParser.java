// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font.fontbox;

import com.mirakyux.export4j.font.FontLoadException;
import com.mirakyux.export4j.font.FontMetrics;
import com.mirakyux.export4j.font.GlyphMetrics;
import com.mirakyux.export4j.font.spi.FontProgramParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import org.apache.fontbox.ttf.OS2WindowsMetricsTable;
import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.pdfbox.io.RandomAccessReadBuffer;

/** FontBox provider for static font programs. */
public final class FontBoxProgramParser implements FontProgramParser {
    @Override
    public Metadata parse(ByteBuffer source) throws IOException {
        ByteBuffer snapshot = source.slice();
        if (snapshot.remaining() < 12) {
            throw new FontLoadException(FontLoadException.Code.INVALID_FONT,
                    "a standalone static TrueType or OpenType CFF font is required");
        }
        int headerTag = snapshot.getInt(0);
        boolean cff = headerTag == 0x4F54544F;
        if (!cff && headerTag != 0x00010000) {
            throw new FontLoadException(FontLoadException.Code.INVALID_FONT,
                    "a standalone static TrueType or OpenType CFF font is required");
        }
        try (var buffer = new RandomAccessReadBuffer(snapshot);
                var font = (cff ? new OTFParser() : new TTFParser()).parse(buffer)) {
            if (font.getTableMap().containsKey("fvar") || font.getTableMap().containsKey("CFF2")) {
                throw new FontLoadException(FontLoadException.Code.INVALID_FONT, "variable fonts are not supported");
            }
            var os2 = font.getOS2Windows();
            int flags = os2 == null ? 0 : os2.getFsType();
            if ((flags & (OS2WindowsMetricsTable.FSTYPE_RESTRICTED | OS2WindowsMetricsTable.FSTYPE_BITMAP_ONLY)) != 0) {
                throw new FontLoadException(FontLoadException.Code.EMBEDDING_RESTRICTED,
                        "font embedding permissions do not allow outline embedding");
            }
            int units = font.getUnitsPerEm();
            if (units <= 0) {
                throw new FontLoadException(FontLoadException.Code.INVALID_FONT, "invalid font units");
            }
            var header = font.getHorizontalHeader();
            var metrics = new FontMetrics(Math.max(0, header.getAscender()) / (double) units,
                    Math.max(0, -header.getDescender()) / (double) units,
                    Math.max(0, header.getLineGap()) / (double) units);
            var cmap = font.getUnicodeCmapLookup(false);
            var mappings = new HashMap<Integer, GlyphMetrics>();
            for (int glyphId = 1; glyphId < font.getNumberOfGlyphs(); glyphId++) {
                var codes = cmap.getCharCodes(glyphId);
                if (codes == null) {
                    continue;
                }
                var glyph = new GlyphMetrics(glyphId,
                        Math.round(font.getAdvanceWidth(glyphId) * 1000.0 / units) / 1000.0);
                for (int codePoint : codes) {
                    if (Character.isValidCodePoint(codePoint) && (codePoint < 0xD800 || codePoint > 0xDFFF)
                            && cmap.getGlyphId(codePoint) == glyphId) {
                        mappings.put(codePoint, glyph);
                    }
                }
            }
            if (mappings.isEmpty()) {
                throw new FontLoadException(FontLoadException.Code.INVALID_FONT, "no Unicode glyph mappings");
            }
            return new Metadata(metrics, mappings, (flags & OS2WindowsMetricsTable.FSTYPE_NO_SUBSETTING) == 0);
        } catch (FontLoadException failure) {
            throw failure;
        } catch (IOException | IllegalArgumentException | NullPointerException | IndexOutOfBoundsException failure) {
            throw new FontLoadException(FontLoadException.Code.INVALID_FONT, "font parsing failed", failure);
        }
    }
}
