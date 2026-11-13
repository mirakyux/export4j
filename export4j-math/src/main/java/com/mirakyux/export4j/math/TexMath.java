// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math;

import com.mirakyux.export4j.adapter.docx.DocxMath;
import com.mirakyux.export4j.adapter.web.FormulaProcessor;
import com.mirakyux.export4j.model.InlineVector;
import java.util.Objects;

/** Offline conversion of a bounded TeX subset. All lengths use points. No external resources,
 * macros or implicit system fonts are permitted. Methods own no caller resources and may be
 * called concurrently; vector preparation serializes the third-party parser's shared state.
 * Core models and backends do not depend on this optional module.
 */
public final class TexMath {
    private TexMath() { }

    /** Prepares a black vector with default options. Original TeX is reading text, with ASCII
     * line breaks and tabs changed to spaces to satisfy the inline reading-text contract.
     * @throws MathException for unsupported syntax, invalid input, limits or rendering failure
     */
    public static InlineVector vector(String tex) { return vector(tex, MathOptions.DEFAULT); }

    /** Prepares immutable baseline-relative outlines without scaling or wrapping. Source length,
     * grammar depth, commands and captured/final geometry are checked before a result is returned.
     * Cancellation/deadline from the caller's ExecutionContext propagates unchanged.
     * @throws MathException for unsupported syntax, invalid input, limits or rendering failure
     */
    public static InlineVector vector(String tex, MathOptions options) {
        return TexVectorRenderer.prepare(tex, options);
    }

    /** Prepares vector mathematics with explicit physical fonts for literal text groups,
     * including Chinese. Only {@code text}/{@code textrm} literal groups use these fonts;
     * mathematical glyphs retain the bundled math fonts. Literal text is limited to BMP left-to-right
     * Latin, CJK, Greek and Cyrillic with common digits/punctuation; combining marks, complex scripts,
     * supplementary characters and directional controls fail explicitly, as do missing glyphs.
     * No font registration, system fallback or persistent parsed-font cache is introduced.
     * Supplied font byte snapshots remain owned by {@code textFonts}.
     */
    public static InlineVector vector(String tex, MathOptions options, MathTextFonts textFonts) {
        return TexVectorRenderer.prepare(tex, options, textFonts);
    }

    /** Creates the optional strict formula processor for source-content imports, with default
     * per-formula limits. The importer supplies each occurrence's size, display mode, color and
     * remaining segment budget. This immutable processor is safe to reuse across import sessions.
     * No automatic fallback, resource lookup or caller-resource ownership is introduced.
     */
    public static FormulaProcessor processor() { return processor(MathOptions.DEFAULT); }

    /** Creates a strict source-content processor with a snapshot of the supplied per-formula limits.
     * The content's point size and display mode override those two fields; all other limits remain
     * in force. The remaining segment budget can only tighten {@link MathOptions#maxSegments()} and
     * is passed to the renderer before path capture. An exhausted budget fails before TeX parsing.
     * The returned vector uses the supplied 24-bit RGB, with no external link.
     * Cancellation/deadline failures propagate unchanged from the caller's execution context.
     * @throws NullPointerException when options is null
     */
    public static FormulaProcessor processor(MathOptions options) {
        return processor(options, MathTextFonts.EMPTY);
    }

    /** Creates a strict processor with the same explicit literal-text font contract as
     * {@link #vector(String, MathOptions, MathTextFonts)}. The immutable configuration is retained
     * by the returned processor; invocation-local parsed fonts are released after each render.
     */
    public static FormulaProcessor processor(MathOptions options, MathTextFonts textFonts) {
        var limits = Objects.requireNonNull(options, "options");
        var explicitFonts = Objects.requireNonNull(textFonts, "textFonts");
        return (tex, display, fontSize, rgb, maxSegments) -> {
            if (maxSegments <= 0) { throw new MathException(MathException.Reason.LIMIT_EXCEEDED); }
            if (rgb < 0 || rgb > 0xffffff) { throw new IllegalArgumentException("invalid formula color"); }
            var occurrence = new MathOptions(fontSize, display, limits.maxSourceLength(), limits.maxDepth(),
                    limits.maxCommands(), limits.maxPaths(), Math.min(limits.maxSegments(), maxSegments),
                    limits.maxExtent());
            var rendered = vector(tex, occurrence, explicitFonts);
            return new InlineVector(rendered.resource(), rendered.logicalText(), rgb, "");
        };
    }

    /** Converts the supported semantic subset to editable Word mathematics, using default limits.
     * This conversion does not initialize Java2D or the vector renderer.
     * @throws MathException for unsupported syntax, invalid input or limits
     */
    public static DocxMath docx(String tex) { return docx(tex, MathOptions.DEFAULT); }

    /** Converts to an immutable OMML semantic tree. This subset is deliberately narrower than
     * vector rendering: unsupported constructs fail instead of becoming misleading plain runs.
     * @throws MathException for unsupported syntax, invalid input or limits
     */
    public static DocxMath docx(String tex, MathOptions options) { return TexToDocx.parse(tex, options); }
}
