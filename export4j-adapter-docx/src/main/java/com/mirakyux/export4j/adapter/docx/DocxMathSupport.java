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
