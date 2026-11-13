// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math;

import com.mirakyux.export4j.adapter.web.FormulaSyntax;
import java.util.List;

/** Shares source delimiter rules with ordinary imports; TeX processing remains optional. */
final class MarkdownFormulaScanner {
    private MarkdownFormulaScanner() { }

    static List<MarkdownMath.FormulaSpan> scan(String source, int maxSource, int maxCount, int maxTex) {
        try {
            return FormulaSyntax.markdown(source, maxSource, maxCount, maxTex).stream()
                    .map(span -> new MarkdownMath.FormulaSpan(span.start(), span.end(), span.tex(), span.display()))
                    .toList();
        } catch (FormulaSyntax.LimitExceededException failure) {
            throw new MathException(MathException.Reason.LIMIT_EXCEEDED, failure);
        }
    }
}
