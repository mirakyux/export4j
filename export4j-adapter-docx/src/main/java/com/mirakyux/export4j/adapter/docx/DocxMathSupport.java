// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import com.mirakyux.export4j.model.ExecutionContext;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

final class DocxMathSupport {
    private DocxMathSupport() { }
    private record Pending(DocxMath node, int depth) { }

    static void validate(List<DocxMathDocument.Equation> equations, DocxMathLimits limits) {
        if (equations.size() > limits.maxEquations()) { throw new IllegalArgumentException("equation count budget exceeded"); }
        int nodes = 0; long cells = 0; long text = 0;
        var pending = new ArrayDeque<Pending>();
        for (var equation : equations) { pending.addLast(new Pending(equation.expression(), 1)); }
        while (!pending.isEmpty()) {
            ExecutionContext.checkpoint(); var current = pending.removeLast();
            if (++nodes > limits.maxNodes() || current.depth() > limits.maxDepth()) { throw new IllegalArgumentException("mathematical node/depth budget exceeded"); }
            if (current.node() instanceof DocxMath.Run run) {
                text += run.text().codePointCount(0, run.text().length());
                if (text > limits.maxTextCodePoints()) { throw new IllegalArgumentException("mathematical text budget exceeded"); }
            }
            if (current.node() instanceof DocxMath.Matrix matrix) {
                cells += (long) matrix.rows().size() * matrix.columns().size();
                if (cells > limits.maxMatrixCells()) { throw new IllegalArgumentException("mathematical matrix cell budget exceeded"); }
            }
            for (var child : children(current.node())) { pending.addLast(new Pending(child, current.depth() + 1)); }
        }
    }
    static void charge(List<DocxMath> expressions, DocxCells.Budget budget) throws IOException {
        var pending = new ArrayDeque<>(expressions);
        while (!pending.isEmpty()) {
            var node = pending.removeLast(); budget.node();
            if (node instanceof DocxMath.Run run) { budget.text(run.text()); }
            if (node instanceof DocxMath.Matrix matrix) { budget.slots(matrix.rows().size(), matrix.columns().size()); }
            pending.addAll(children(node));
        }
    }
    static List<DocxMath> children(DocxMath node) {
        return switch (node) {
            case DocxMath.Run ignored -> List.of();
            case DocxMath.Sequence sequence -> sequence.elements();
            case DocxMath.Fraction fraction -> List.of(fraction.numerator(), fraction.denominator());
            case DocxMath.Subscript script -> List.of(script.base(), script.subscript());
            case DocxMath.Superscript script -> List.of(script.base(), script.superscript());
            case DocxMath.SubSuperscript script -> List.of(script.base(), script.subscript(), script.superscript());
            case DocxMath.Radical radical -> {
                var result = new ArrayList<DocxMath>(); result.add(radical.radicand()); radical.degree().ifPresent(result::add); yield result;
            }
            case DocxMath.Nary nary -> {
                var result = new ArrayList<DocxMath>(); nary.lower().ifPresent(result::add); nary.upper().ifPresent(result::add); result.add(nary.operand()); yield result;
            }
            case DocxMath.Matrix matrix -> matrix.rows().stream().flatMap(List::stream).toList();
            case DocxMath.Delimiter delimiter -> delimiter.items();
            case DocxMath.Function function -> List.of(function.name(), function.argument());
            case DocxMath.LowerLimit limit -> List.of(limit.base(), limit.limit());
            case DocxMath.UpperLimit limit -> List.of(limit.base(), limit.limit());
        };
    }
    static com.mirakyux.export4j.model.InlineStyle markerStyle(com.mirakyux.export4j.model.Paragraph paragraph, int at) {
        int offset = 0;
        for (var run : paragraph.inlines()) {
            if (at >= offset && at < offset + run.text().length()) { return run.style(); }
            offset += run.text().length();
        }
        return com.mirakyux.export4j.model.InlineStyle.of(paragraph.format().fontSize());
    }
    static DocxMath inherit(DocxMath node, com.mirakyux.export4j.model.InlineStyle appearance, double paragraphSize) {
        return switch (node) {
            case DocxMath.Run run -> {
                // An OMML run does not reliably inherit the paragraph mark's w:rPr in
                // independent readers. Resolve every inherited size, including when the
                // marker and paragraph agree; an explicit mathematical run still wins.
                double size = run.fontSize() == 0 ? appearance.fontSize() : run.fontSize();
                int color = run.rgb() == 0 ? appearance.rgb() : run.rgb();
                var style = run.style();
                boolean bold = java.util.Set.of("bold", "boldItalic").contains(appearance.fontRole());
                boolean italic = java.util.Set.of("italic", "boldItalic").contains(appearance.fontRole());
                // NORMAL_TEXT explicitly requests Word's m:nor mode. A host font role must not
                // silently turn prose into a mathematical run and change spacing/font semantics.
                if (bold && style != DocxMath.Style.NORMAL_TEXT) { style = style == DocxMath.Style.ITALIC || style == DocxMath.Style.BOLD_ITALIC ? DocxMath.Style.BOLD_ITALIC : DocxMath.Style.BOLD; }
                if (italic && style != DocxMath.Style.NORMAL_TEXT) { style = style == DocxMath.Style.BOLD || style == DocxMath.Style.BOLD_ITALIC ? DocxMath.Style.BOLD_ITALIC : DocxMath.Style.ITALIC; }
                yield new DocxMath.Run(run.text(), style, size, color);
            }
            case DocxMath.Sequence sequence -> {
                var values = new ArrayList<DocxMath>();
                for (var value : sequence.elements()) {
                    var current = inherit(value, appearance, paragraphSize);
                    if (current instanceof DocxMath.Sequence nested) { values.addAll(nested.elements()); }
                    else { values.add(current); }
                }
                yield values.size() == 1 ? values.getFirst() : new DocxMath.Sequence(values);
            }
            case DocxMath.Fraction fraction -> new DocxMath.Fraction(inherit(fraction.numerator(), appearance, paragraphSize), inherit(fraction.denominator(), appearance, paragraphSize));
            case DocxMath.Subscript script -> new DocxMath.Subscript(inherit(script.base(), appearance, paragraphSize), inherit(script.subscript(), appearance, paragraphSize));
            case DocxMath.Superscript script -> new DocxMath.Superscript(inherit(script.base(), appearance, paragraphSize), inherit(script.superscript(), appearance, paragraphSize));
            case DocxMath.SubSuperscript script -> new DocxMath.SubSuperscript(inherit(script.base(), appearance, paragraphSize), inherit(script.subscript(), appearance, paragraphSize), inherit(script.superscript(), appearance, paragraphSize));
            case DocxMath.Radical radical -> new DocxMath.Radical(inherit(radical.radicand(), appearance, paragraphSize), radical.degree().map(value -> inherit(value, appearance, paragraphSize)));
            case DocxMath.Nary nary -> new DocxMath.Nary(nary.operator(), nary.lower().map(value -> inherit(value, appearance, paragraphSize)), nary.upper().map(value -> inherit(value, appearance, paragraphSize)), inherit(nary.operand(), appearance, paragraphSize), nary.limits(), nary.grow());
            case DocxMath.Matrix matrix -> new DocxMath.Matrix(matrix.rows().stream().map(row -> row.stream().map(value -> inherit(value, appearance, paragraphSize)).toList()).toList(), matrix.columns(), matrix.baseline());
            case DocxMath.Delimiter delimiter -> new DocxMath.Delimiter(delimiter.opening(), delimiter.closing(), delimiter.separator(), delimiter.items().stream().map(value -> inherit(value, appearance, paragraphSize)).toList(), delimiter.grow(), delimiter.shape());
            case DocxMath.Function function -> new DocxMath.Function(inherit(function.name(), appearance, paragraphSize), inherit(function.argument(), appearance, paragraphSize));
            case DocxMath.LowerLimit limit -> new DocxMath.LowerLimit(inherit(limit.base(), appearance, paragraphSize), inherit(limit.limit(), appearance, paragraphSize));
            case DocxMath.UpperLimit limit -> new DocxMath.UpperLimit(inherit(limit.base(), appearance, paragraphSize), inherit(limit.limit(), appearance, paragraphSize));
        };
    }
    static DocxMath canonical(DocxMath node) {
        return switch (node) {
            case DocxMath.Run run -> run;
            case DocxMath.Sequence sequence -> {
                var values = new ArrayList<DocxMath>();
                for (var value : sequence.elements()) {
                    var current = canonical(value);
                    if (current instanceof DocxMath.Sequence nested) { values.addAll(nested.elements()); }
                    else { values.add(current); }
                }
                yield values.size() == 1 ? values.getFirst() : new DocxMath.Sequence(values);
            }
            case DocxMath.Fraction fraction -> new DocxMath.Fraction(canonical(fraction.numerator()), canonical(fraction.denominator()));
            case DocxMath.Subscript script -> new DocxMath.Subscript(canonical(script.base()), canonical(script.subscript()));
            case DocxMath.Superscript script -> new DocxMath.Superscript(canonical(script.base()), canonical(script.superscript()));
            case DocxMath.SubSuperscript script -> new DocxMath.SubSuperscript(canonical(script.base()), canonical(script.subscript()), canonical(script.superscript()));
            case DocxMath.Radical radical -> new DocxMath.Radical(canonical(radical.radicand()), radical.degree().map(DocxMathSupport::canonical));
            case DocxMath.Nary nary -> new DocxMath.Nary(nary.operator(), nary.lower().map(DocxMathSupport::canonical), nary.upper().map(DocxMathSupport::canonical), canonical(nary.operand()), nary.limits(), nary.grow());
            case DocxMath.Matrix matrix -> new DocxMath.Matrix(matrix.rows().stream().map(row -> row.stream().map(DocxMathSupport::canonical).toList()).toList(), matrix.columns(), matrix.baseline());
            case DocxMath.Delimiter delimiter -> new DocxMath.Delimiter(delimiter.opening(), delimiter.closing(), delimiter.separator(), delimiter.items().stream().map(DocxMathSupport::canonical).toList(), delimiter.grow(), delimiter.shape());
            case DocxMath.Function function -> new DocxMath.Function(canonical(function.name()), canonical(function.argument()));
            case DocxMath.LowerLimit limit -> new DocxMath.LowerLimit(canonical(limit.base()), canonical(limit.limit()));
            case DocxMath.UpperLimit limit -> new DocxMath.UpperLimit(canonical(limit.base()), canonical(limit.limit()));
        };
    }
}
