// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable native Word mathematics. Nodes represent OMML semantics, never TeX or XML fragments.
 * Lists are snapshots. Complete trees are checked against finite budgets by DocxMathDocument. */
public sealed interface DocxMath permits DocxMath.Run, DocxMath.Sequence, DocxMath.Fraction,
        DocxMath.Subscript, DocxMath.Superscript, DocxMath.SubSuperscript, DocxMath.Radical,
        DocxMath.Nary, DocxMath.Matrix, DocxMath.Delimiter, DocxMath.Function,
        DocxMath.LowerLimit, DocxMath.UpperLimit {
    enum Style { ITALIC, PLAIN, BOLD, BOLD_ITALIC, NORMAL_TEXT }
    enum LimitLocation { SUB_SUP, UNDER_OVER }
    enum Operator { INTEGRAL, DOUBLE_INTEGRAL, CONTOUR_INTEGRAL, SUM, PRODUCT }
    enum ColumnAlignment { LEFT, CENTER, RIGHT }
    enum BaseAlignment { TOP, CENTER, BOTTOM }
    enum DelimiterShape { CENTERED, MATCH }

    /** fontSize=0 inherits its paragraph; otherwise positive half-point units, at most 144 pt. */
    record Run(String text, Style style, double fontSize, int rgb) implements DocxMath {
        public Run {
            Objects.requireNonNull(text, "text"); Objects.requireNonNull(style, "style");
            if (!Double.isFinite(fontSize) || fontSize < 0 || fontSize > 144 || fontSize * 2 != Math.rint(fontSize * 2)
                    || rgb < 0 || rgb > 0xffffff || text.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("invalid mathematical run");
            }
            for (int index = 0; index < text.length(); index++) {
                char value = text.charAt(index);
                if (Character.isHighSurrogate(value)) {
                    if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) { throw new IllegalArgumentException("invalid mathematical Unicode"); }
                } else if (Character.isLowSurrogate(value) || value == 0xfffe || value == 0xffff) { throw new IllegalArgumentException("invalid mathematical Unicode"); }
            }
        }
        public Run(String text) { this(text, Style.ITALIC, 0, 0); }
        public Run(String text, Style style) { this(text, style, 0, 0); }
    }
    record Sequence(List<DocxMath> elements) implements DocxMath {
        public Sequence { elements = List.copyOf(elements); if (elements.size() > 100_000) { throw new IllegalArgumentException("mathematical sequence budget exceeded"); } }
    }
    record Fraction(DocxMath numerator, DocxMath denominator) implements DocxMath {
        public Fraction { Objects.requireNonNull(numerator, "numerator"); Objects.requireNonNull(denominator, "denominator"); }
    }
    record Subscript(DocxMath base, DocxMath subscript) implements DocxMath {
        public Subscript { Objects.requireNonNull(base, "base"); Objects.requireNonNull(subscript, "subscript"); }
    }
    record Superscript(DocxMath base, DocxMath superscript) implements DocxMath {
        public Superscript { Objects.requireNonNull(base, "base"); Objects.requireNonNull(superscript, "superscript"); }
    }
    record SubSuperscript(DocxMath base, DocxMath subscript, DocxMath superscript) implements DocxMath {
        public SubSuperscript { Objects.requireNonNull(base, "base"); Objects.requireNonNull(subscript, "subscript"); Objects.requireNonNull(superscript, "superscript"); }
    }
    /** An absent degree is a square root, represented by standard degHide in OMML. */
    record Radical(DocxMath radicand, Optional<DocxMath> degree) implements DocxMath {
        public Radical { Objects.requireNonNull(radicand, "radicand"); Objects.requireNonNull(degree, "degree"); }
        public Radical(DocxMath radicand) { this(radicand, Optional.empty()); }
    }
    record Nary(Operator operator, Optional<DocxMath> lower, Optional<DocxMath> upper,
            DocxMath operand, LimitLocation limits, boolean grow) implements DocxMath {
        public Nary { Objects.requireNonNull(operator, "operator"); Objects.requireNonNull(lower, "lower"); Objects.requireNonNull(upper, "upper"); Objects.requireNonNull(operand, "operand"); Objects.requireNonNull(limits, "limits"); }
    }
    record Matrix(List<List<DocxMath>> rows, List<ColumnAlignment> columns, BaseAlignment baseline) implements DocxMath {
        public Matrix {
            rows = rows.stream().map(List::copyOf).toList(); columns = List.copyOf(columns); Objects.requireNonNull(baseline, "baseline");
            int width = columns.size();
            if (rows.isEmpty() || columns.isEmpty() || rows.size() > 1024 || columns.size() > 1024
                    || (long) rows.size() * columns.size() > 100_000 || rows.stream().anyMatch(row -> row.size() != width)) {
                throw new IllegalArgumentException("mathematical matrix must be finite and rectangular");
            }
        }
    }
    record Delimiter(String opening, String closing, String separator, List<DocxMath> items,
            boolean grow, DelimiterShape shape) implements DocxMath {
        public Delimiter {
            character(opening); character(closing); character(separator); items = List.copyOf(items); Objects.requireNonNull(shape, "shape");
            if (items.isEmpty() || items.size() > 1024) { throw new IllegalArgumentException("invalid mathematical delimiter arity"); }
        }
        public Delimiter(String opening, String closing, DocxMath content) { this(opening, closing, "|", List.of(content), true, DelimiterShape.CENTERED); }
    }
    record Function(DocxMath name, DocxMath argument) implements DocxMath {
        public Function { Objects.requireNonNull(name, "name"); Objects.requireNonNull(argument, "argument"); }
    }
    record LowerLimit(DocxMath base, DocxMath limit) implements DocxMath {
        public LowerLimit { Objects.requireNonNull(base, "base"); Objects.requireNonNull(limit, "limit"); }
    }
    record UpperLimit(DocxMath base, DocxMath limit) implements DocxMath {
        public UpperLimit { Objects.requireNonNull(base, "base"); Objects.requireNonNull(limit, "limit"); }
    }
    static DocxMath run(String text) { return new Run(text); }
    static DocxMath sequence(DocxMath... elements) { return new Sequence(List.of(elements)); }
    private static void character(String value) {
        Objects.requireNonNull(value, "delimiter");
        if (value.codePointCount(0, value.length()) > 1 || value.codePoints().anyMatch(Character::isISOControl)
                || value.codePoints().anyMatch(cp -> cp >= 0xd800 && cp <= 0xdfff || cp == 0xfffe || cp == 0xffff)) {
            throw new IllegalArgumentException("invalid mathematical delimiter character");
        }
    }
}
