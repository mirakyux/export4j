// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.GlyphMetrics;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.MissingGlyphPolicy;

/**
 * Deterministic, unkerned one-scalar/one-glyph shaping for simple horizontal scripts.
 * Complex scripts, combining marks and joiners are rejected rather than rendered incorrectly.
 */
public final class SimpleTextShaper implements TextShaper {
    public ShapedText shape(String text, List<FontFace> fonts, double fontSize) {
        return shape(text, fonts, fontSize, 0);
    }

    @Override
    public ShapedText shape(String text, List<FontFace> fonts, double fontSize, int sourceOffset) {
        return shape(text, FontGroup.strict(fonts), fontSize, sourceOffset);
    }

    @Override
    public ShapedText shape(String text, FontGroup group, double fontSize, int sourceOffset) {
        Objects.requireNonNull(text, "text");
        var fonts = group.fonts();
        if (fonts.isEmpty() || !Double.isFinite(fontSize) || fontSize <= 0 || sourceOffset < 0
                || sourceOffset > Integer.MAX_VALUE - text.length()) {
            throw new IllegalArgumentException("fonts, font size and source offset must be valid");
        }
        List<ShapedGlyph> glyphs = new ArrayList<>();
        List<TextDiagnostic> diagnostics = new ArrayList<>();
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            int sourceCodePoint = codePoint;
            int start = sourceOffset + offset;
            int end = start + Character.charCount(codePoint);
            validateScalar(codePoint, start);
            FontFace selected = null;
            GlyphMetrics metrics = null;
            for (FontFace font : fonts) {
                var candidate = font.glyph(codePoint);
                if (candidate.isPresent()) {
                    selected = font;
                    metrics = candidate.orElseThrow();
                    break;
                }
            }
            if (selected == null) {
                if (group.missingGlyphPolicy() == MissingGlyphPolicy.PLACEHOLDER_WITH_WARNING) {
                    validateScalar(group.placeholderCodePoint(), start);
                    for (FontFace font : fonts) {
                        var candidate = font.glyph(group.placeholderCodePoint());
                        if (candidate.isPresent()) {
                            selected = font;
                            metrics = candidate.orElseThrow();
                            break;
                        }
                    }
                    if (selected != null) {
                        diagnostics.add(new TextDiagnostic(TextDiagnostic.Code.MISSING_GLYPH_REPLACED, start, end,
                                selected.data().id(), codePoint, group.placeholderCodePoint()));
                        codePoint = group.placeholderCodePoint();
                    }
                }
            }
            if (selected == null) {
                throw new TextLayoutException(TextLayoutException.Code.MISSING_GLYPH, start,
                        "no configured font supports the source scalar");
            }
            double advance = Objects.requireNonNull(metrics).advance() * fontSize;
            glyphs.add(new ShapedGlyph(selected.data(),
                    new PositionedGlyph(codePoint, metrics.glyphId(), advance), start, end,
                    selected.metrics().ascent() * fontSize, selected.metrics().descent() * fontSize, sourceCodePoint));
            if (selected != fonts.getFirst()) {
                diagnostics.add(new TextDiagnostic(TextDiagnostic.Code.FONT_FALLBACK,
                        start, end, selected.data().id()));
            }
            offset = end - sourceOffset;
        }
        return new ShapedText(glyphs, diagnostics);
    }

    private static void validateScalar(int codePoint, int index) {
        if ((codePoint >= 0xD800 && codePoint <= 0xDFFF) || Character.isISOControl(codePoint)) {
            throw new TextLayoutException(TextLayoutException.Code.INVALID_TEXT, index,
                    "invalid scalar or unsupported control character");
        }
        int type = Character.getType(codePoint);
        boolean simpleScript = switch (Character.UnicodeScript.of(codePoint)) {
            case LATIN, HAN, GREEK, CYRILLIC, HIRAGANA, KATAKANA, HANGUL, BOPOMOFO, COMMON -> true;
            default -> false;
        };
        if (!simpleScript || type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                || (codePoint >= 0x1100 && codePoint <= 0x11FF)
                || (codePoint >= 0xA960 && codePoint <= 0xA97F)
                || (codePoint >= 0xD7B0 && codePoint <= 0xD7FF)
                || (codePoint >= 0x1F3FB && codePoint <= 0x1F3FF)
                || (codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF)) {
            throw new TextLayoutException(TextLayoutException.Code.UNSUPPORTED_SHAPING, index,
                    "source requires shaping that this implementation does not support");
        }
    }
}
