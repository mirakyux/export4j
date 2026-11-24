// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math.docx.web;

import com.mirakyux.export4j.adapter.docx.DocxMathDocument;
import com.mirakyux.export4j.adapter.web.ContentImporter;
import com.mirakyux.export4j.adapter.web.FormulaProcessor;
import com.mirakyux.export4j.adapter.web.FormulaSyntax;
import com.mirakyux.export4j.math.docx.DocxTexMath;
import com.mirakyux.export4j.math.api.MathException;
import com.mirakyux.export4j.math.api.MathOptions;
import com.mirakyux.export4j.math.docx.NativeMathBinding;
import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineVector;
import com.mirakyux.export4j.model.VectorResource;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Imports semantic source and binds each supported TeX occurrence to an editable Word equation.
 * Word determines formula geometry and final page flow.
 */
public final class DocxMathContent {
    private DocxMathContent() { }

    /** Compiles the entire source with native formulas and default import and math limits. */
    public static DocxMathDocument read(String source, ContentOptions.Format format) throws IOException {
        return read(source, format, MathOptions.DEFAULT, importer -> { });
    }

    /** Compiles source text under the supplied TeX and semantic import limits. The synchronous
     * callback configures the importer, including metadata, page settings, styles and resources.
     * The explicit format and native formula processor apply to this import. Callers can use
     * the enclosing {@link ExecutionContext} to control cancellation and deadlines.
     * @throws MathException if the native formula syntax or a formula limit is violated
     */
    public static DocxMathDocument read(String source, ContentOptions.Format format, MathOptions options,
            Consumer<ContentImporter.Builder> configure) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(configure, "configure");
        ExecutionContext.checkpoint();

        var capture = new Capture(options);
        var defaults = ContentOptions.builder().format(format).formulas(true).build();
        var importer = ContentImporter.builder().defaults(defaults).maxFormulaUtf16(options.maxSourceLength());
        configure.accept(importer);
        try {
            var document = importer.formulaProcessor(capture).build().read(source)
                    .options(ContentOptions.builder().format(format).build()).execute().document();
            return NativeMathBinding.bind(document, capture.equations, true);
        } catch (FormulaSyntax.LimitExceededException failure) {
            throw new MathException(MathException.Reason.LIMIT_EXCEEDED, failure);
        }
    }

    private static final class Capture implements FormulaProcessor {
        private final MathOptions options;
        private final Map<String, NativeMathBinding.Equation> equations = new LinkedHashMap<>();

        private Capture(MathOptions options) { this.options = options; }

        @Override
        public InlineVector render(String tex, boolean display, double fontSize, int rgb, int maxSegments) {
            return render(tex, display, new InlineStyle("", fontSize, rgb, 0, false, false), maxSegments);
        }

        @Override
        public InlineVector render(String tex, boolean display, InlineStyle style, int maxSegments) {
            if (maxSegments < 1) { throw new MathException(MathException.Reason.LIMIT_EXCEEDED); }
            var math = DocxTexMath.parse(tex, options.withDisplay(display));
            String key = Integer.toString(equations.size());
            // One non-drawing command distinguishes repeated occurrences through model compaction.
            var identity = new VectorResource.Segment(VectorResource.Operation.MOVE,
                    List.of(Math.scalb((double) equations.size() + 1, -32), 0.0));
            var slot = new InlineVector(VectorResource.of(1, 1, 0, List.of(identity)),
                    tex.replace('\r', ' ').replace('\n', ' ').replace('\t', ' '), style.rgb(), "");
            equations.put(key, new NativeMathBinding.Equation(slot, math, display, style.fontSize(), style.fontRole()));
            return slot;
        }
    }
}
