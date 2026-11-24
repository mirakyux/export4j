// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math.web;

import com.mirakyux.export4j.math.api.MathException;
import com.mirakyux.export4j.math.api.MathOptions;
import com.mirakyux.export4j.math.core.MathTextFonts;
import com.mirakyux.export4j.math.core.TexVectorMath;

import com.mirakyux.export4j.adapter.web.ContentImporter;
import com.mirakyux.export4j.adapter.web.FormulaProcessor;
import com.mirakyux.export4j.model.InlineVector;
import java.util.Objects;

/** Vector formula processing for semantic source imports. Configure the returned processor on a
 * {@link ContentImporter.Builder}, including when using the importer to parse template source.
 */
public final class VisualMathContent {
    private VisualMathContent() { }

    /** Creates a strict processor with default per-formula limits. The importer supplies each
     * occurrence's point size, display mode, color and remaining segment budget.
     */
    public static FormulaProcessor processor() { return processor(MathOptions.DEFAULT); }

    /** Creates a strict processor with the supplied per-formula limits. */
    public static FormulaProcessor processor(MathOptions options) {
        return processor(options, MathTextFonts.EMPTY);
    }

    /** Creates a strict processor with explicit fonts for literal TeX text groups. The processor
     * retains its immutable configuration and is reusable across import sessions. The caller's
     * execution context governs cancellation and deadlines.
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
            var rendered = TexVectorMath.vector(tex, occurrence, explicitFonts);
            return new InlineVector(rendered.resource(), rendered.logicalText(), rgb, "");
        };
    }
}
