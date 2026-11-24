// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math.core;

import com.mirakyux.export4j.math.api.MathException;
import com.mirakyux.export4j.math.api.MathOptions;

import com.mirakyux.export4j.model.InlineVector;

/** Bounded, offline TeX outlines for any backend that draws inline vectors. Sizes are points.
 * Methods may be called concurrently. Shared third-party parser state is confined to rendering.
 */
public final class TexVectorMath {
    private TexVectorMath() { }

    /** Renders with default options. The vector preserves TeX as logical text, using spaces in
     * place of tabs and line breaks.
     * @throws MathException for unsupported syntax, invalid input, limits or rendering failure
     */
    public static InlineVector vector(String tex) { return vector(tex, MathOptions.DEFAULT); }

    /** Prepares immutable baseline-relative outlines. Source length, syntax depth, commands and
     * geometry are bounded. Caller cancellation and deadlines propagate unchanged.
     * @throws MathException for unsupported syntax, invalid input, limits or rendering failure
     */
    public static InlineVector vector(String tex, MathOptions options) {
        return TexVectorRenderer.prepare(tex, options);
    }

    /** Renders with explicit fonts for {@code text}/{@code textrm} literal groups. Mathematical
     * glyphs use bundled math fonts. Literal text supports BMP left-to-right Latin, CJK, Greek,
     * and Cyrillic with common digits and punctuation. Missing glyphs, combining marks,
     * supplementary characters, and directional controls fail explicitly. The immutable font
     * configuration may be reused across calls.
     */
    public static InlineVector vector(String tex, MathOptions options, MathTextFonts textFonts) {
        return TexVectorRenderer.prepare(tex, options, textFonts);
    }
}
