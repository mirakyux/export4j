// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.font.FontFaceCache;
import java.awt.Font;
import java.awt.FontFormatException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Explicit physical fonts for literal {@code \text{...}} and {@code \textrm{...}} groups.
 * Encoded bytes are privately snapshotted and retained for this configuration's lifetime.
 * Construction validates static font/outline permissions without cache retention.
 * Each vector invocation parses them once into invocation-local fonts; no registration,
 * system-font lookup or cross-document cache is performed. Release this configuration to
 * release its byte snapshots. Font order is the explicit missing-glyph fallback order.
 */
public final class MathTextFonts {
    public static final MathTextFonts EMPTY = new MathTextFonts(List.of());
    private static final int MAX_FONTS = 8;
    private static final int MAX_FONT_BYTES = 32 * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    private final List<byte[]> encoded;

    private MathTextFonts(List<byte[]> encoded) { this.encoded = encoded; }

    /** Snapshots one to eight explicit standalone static TrueType/OpenType CFF font files, at most 32 MiB each and
     * 64 MiB in total. Empty input returns {@link #EMPTY}. Input streams remain caller-owned;
     * read their bounded contents before invoking this method. Malformed fonts, variable fonts,
     * restricted/bitmap-only embedding, and fonts requiring complete-program embedding are
     * rejected before this configuration is returned.
     */
    public static MathTextFonts of(byte[]... fonts) {
        Objects.requireNonNull(fonts, "fonts");
        if (fonts.length > MAX_FONTS) { throw new IllegalArgumentException("too many formula text fonts"); }
        byte[][] sources = fonts.clone();
        long total = 0;
        for (byte[] font : sources) {
            ExecutionContext.checkpoint();
            Objects.requireNonNull(font, "font");
            total += font.length;
            if (font.length == 0 || font.length > MAX_FONT_BYTES || total > MAX_TOTAL_BYTES) {
                throw new IllegalArgumentException("formula text font byte limit exceeded");
            }
        }
        if (fonts.length == 0) { return EMPTY; }
        var snapshot = new ArrayList<byte[]>(fonts.length);
        for (byte[] font : sources) { ExecutionContext.checkpoint(); snapshot.add(font.clone()); }
        try (var validator = FontFaceCache.builder().enabled(false).build()) {
            for (byte[] font : snapshot) {
                ExecutionContext.checkpoint();
                if (!validator.load(font).data().subsetAllowed()) {
                    throw new MathException(MathException.Reason.RENDERING_FAILED);
                }
            }
        } catch (IOException failure) {
            throw new MathException(MathException.Reason.RENDERING_FAILED, failure);
        }
        return new MathTextFonts(List.copyOf(snapshot));
    }

    public boolean isEmpty() { return encoded.isEmpty(); }

    List<Font> parse() {
        var result = new ArrayList<Font>(encoded.size());
        for (byte[] bytes : encoded) {
            ExecutionContext.checkpoint();
            try (var input = new ByteArrayInputStream(bytes)) {
                result.add(Font.createFont(Font.TRUETYPE_FONT, input).deriveFont(1000f));
            } catch (FontFormatException | IOException failure) {
                throw new MathException(MathException.Reason.RENDERING_FAILED, failure);
            }
        }
        return List.copyOf(result);
    }
}
