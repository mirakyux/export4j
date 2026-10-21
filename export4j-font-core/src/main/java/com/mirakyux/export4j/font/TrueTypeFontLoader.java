// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.HorizontalHeaderTable;
import org.apache.fontbox.ttf.OS2WindowsMetricsTable;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;

/** Eager, bounded static TrueType/CFF loader. Parser resources close before an immutable face is returned. */
public final class TrueTypeFontLoader {
    public static final int DEFAULT_MAX_BYTES = 32 * 1024 * 1024;

    private TrueTypeFontLoader() {
    }

    /** Loads caller-owned bytes under the default size limit; never scans system fonts. */
    public static FontFace load(byte[] bytes) throws IOException {
        return load(bytes, DEFAULT_MAX_BYTES);
    }

    /** Reads at most maxBytes+1 bytes. The borrowed input stream is never closed. */
    public static FontFace load(InputStream input, int maxBytes) throws IOException {
        Objects.requireNonNull(input, "input");
        requireLimit(maxBytes);
        return load(input.readNBytes(maxBytes + 1), maxBytes);
    }

    public static FontFace load(byte[] bytes, int maxBytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        requireLimit(maxBytes);
        if (bytes.length > maxBytes) {
            throw new FontLoadException(FontLoadException.Code.BUDGET_EXCEEDED, "font byte budget exceeded");
        }
        byte[] snapshot = bytes.clone();
        boolean cff = snapshot.length >= 4 && snapshot[0] == 'O' && snapshot[1] == 'T' && snapshot[2] == 'T' && snapshot[3] == 'O';
        if (snapshot.length < 12 || !cff && (snapshot[0] != 0 || snapshot[1] != 1
                || snapshot[2] != 0 || snapshot[3] != 0)) {
            throw new FontLoadException(FontLoadException.Code.INVALID_FONT,
                    "a standalone static TrueType or OpenType CFF font is required");
        }
        try (RandomAccessReadBuffer buffer = new RandomAccessReadBuffer(snapshot);
                TrueTypeFont font = (cff ? new OTFParser() : new TTFParser()).parse(buffer)) {
            if (font.getTableMap().containsKey("fvar") || font.getTableMap().containsKey("CFF2")) {
                throw new FontLoadException(FontLoadException.Code.INVALID_FONT,
                        "variable fonts are not supported");
            }
            OS2WindowsMetricsTable os2 = font.getOS2Windows();
            int flags = os2 == null ? 0 : os2.getFsType();
            if ((flags & (OS2WindowsMetricsTable.FSTYPE_RESTRICTED
                    | OS2WindowsMetricsTable.FSTYPE_BITMAP_ONLY)) != 0) {
                throw new FontLoadException(FontLoadException.Code.EMBEDDING_RESTRICTED,
                        "font embedding permissions do not allow outline embedding");
            }
            int units = font.getUnitsPerEm();
            if (units <= 0) {
                throw new FontLoadException(FontLoadException.Code.INVALID_FONT, "invalid font units");
            }
            HorizontalHeaderTable header = font.getHorizontalHeader();
            FontMetrics metrics = new FontMetrics(Math.max(0, header.getAscender()) / (double) units,
                    Math.max(0, -header.getDescender()) / (double) units,
                    Math.max(0, header.getLineGap()) / (double) units);
            CmapLookup cmap = font.getUnicodeCmapLookup(false);
            Map<Integer, GlyphMetrics> mappings = new HashMap<>();
            for (int glyphId = 1; glyphId < font.getNumberOfGlyphs(); glyphId++) {
                List<Integer> codes = cmap.getCharCodes(glyphId);
                if (codes == null) {
                    continue;
                }
                GlyphMetrics glyph = new GlyphMetrics(glyphId,
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
            FontData data = new FontData(snapshot,
                    (flags & OS2WindowsMetricsTable.FSTYPE_NO_SUBSETTING) == 0);
            return new LoadedFace(data, metrics, Map.copyOf(mappings));
        } catch (FontLoadException failure) {
            throw failure;
        } catch (IOException | IllegalArgumentException | NullPointerException | IndexOutOfBoundsException failure) {
            throw new FontLoadException(FontLoadException.Code.INVALID_FONT,
                    "font parsing failed", failure);
        }
    }

    private static void requireLimit(int maxBytes) {
        if (maxBytes <= 0 || maxBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxBytes must be positive and less than Integer.MAX_VALUE");
        }
    }

    private record LoadedFace(FontData data, FontMetrics metrics,
            Map<Integer, GlyphMetrics> mappings) implements FontFace {
        @Override
        public Optional<GlyphMetrics> glyph(int codePoint) {
            return Optional.ofNullable(mappings.get(codePoint));
        }
    }
}
