// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.math.BigDecimal;
import java.util.Objects;

/** Immutable template cell rule. Conditions read a lexical data path, never executable code.
 * Null style components mean no override; dimensions are points. Rules run in declaration order.
 * The resolved document contains ordinary cell formats and inline styles, without live rules. */
public record CellStyleRule(String path, Operator operator, String value, Overrides style) {
    public enum Operator { EQ, NE, GT, GE, LT, LE, CONTAINS, TRUE, FALSE }

    public CellStyleRule {
        Objects.requireNonNull(path, "path"); Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(value, "value"); Objects.requireNonNull(style, "style");
        if (path.length() > 256 || !path.matches("[A-Za-z_][A-Za-z_0-9]*(\\.[A-Za-z_][A-Za-z_0-9]*)*")) {
            throw new IllegalArgumentException("invalid cell rule data path");
        }
        if (value.length() > 256) { throw new IllegalArgumentException("cell rule comparison value exceeds budget"); }
        if (operator == Operator.GT || operator == Operator.GE || operator == Operator.LT || operator == Operator.LE) {
            decimal(value);
        }
        if ((operator == Operator.TRUE || operator == Operator.FALSE) && !value.isEmpty()) {
            throw new IllegalArgumentException("boolean cell rules do not accept a comparison value");
        }
    }

    /** Nullable values override only the specified properties, including existing rich runs.
     * Setting a size without a line height uses 1.5 times that size. */
    public record Overrides(Double fontSize, Double lineHeight, ParagraphAlignment alignment,
            LineBreakPolicy lineBreakPolicy, String fontRole, Integer rgb, Boolean underline, Boolean strike) {
        public Overrides {
            if (fontSize != null && (!Double.isFinite(fontSize) || fontSize <= 0)
                    || lineHeight != null && (!Double.isFinite(lineHeight) || lineHeight <= 0)) {
                throw new IllegalArgumentException("cell rule text dimensions must be finite and positive");
            }
            if (fontSize != null && lineHeight == null && !Double.isFinite(fontSize * 1.5)) {
                throw new IllegalArgumentException("cell rule default line height overflows");
            }
            if (rgb != null && (rgb < 0 || rgb > 0xFFFFFF)) { throw new IllegalArgumentException("invalid cell rule RGB"); }
            if (fontRole != null && !fontRole.isEmpty() && !fontRole.matches("[A-Za-z_][A-Za-z_0-9-]{0,127}")) {
                throw new IllegalArgumentException("invalid cell rule font role");
            }
            if (fontSize == null && lineHeight == null && alignment == null && lineBreakPolicy == null
                    && fontRole == null && rgb == null && underline == null && strike == null) {
                throw new IllegalArgumentException("cell rule must override at least one style property");
            }
        }
        CellFormat apply(CellFormat format) {
            return new CellFormat(fontSize == null ? format.fontSize() : fontSize,
                    lineHeight != null ? lineHeight : fontSize != null ? fontSize * 1.5 : format.lineHeight(),
                    alignment == null ? format.alignment() : alignment,
                    lineBreakPolicy == null ? format.lineBreakPolicy() : lineBreakPolicy);
        }
        InlineStyle apply(InlineStyle current) {
            return new InlineStyle(fontRole == null ? current.fontRole() : fontRole,
                    fontSize == null ? current.fontSize() : fontSize, rgb == null ? current.rgb() : rgb,
                    current.baselineShift(), underline == null ? current.underline() : underline,
                    strike == null ? current.strikeThrough() : strike);
        }
    }

    boolean matches(Object actual) {
        return switch (operator) {
            case TRUE, FALSE -> {
                if (!(actual instanceof Boolean flag)) { throw new IllegalArgumentException("cell rule requires boolean data"); }
                yield flag == (operator == Operator.TRUE);
            }
            case CONTAINS -> {
                if (!(actual instanceof String text)) { throw new IllegalArgumentException("cell rule requires text data"); }
                yield text.contains(value);
            }
            case EQ, NE -> {
                boolean equal;
                if (actual instanceof Number number) { equal = decimal(number.toString()).compareTo(decimal(value)) == 0; }
                else if (actual instanceof Boolean flag) {
                    if (!value.equals("true") && !value.equals("false")) { throw new IllegalArgumentException("cell rule requires a boolean comparison value"); }
                    equal = flag == Boolean.parseBoolean(value);
                } else if (actual instanceof String text) { equal = text.equals(value); }
                else { throw new IllegalArgumentException("cell rule requires scalar data"); }
                yield operator == Operator.EQ ? equal : !equal;
            }
            case GT, GE, LT, LE -> {
                if (!(actual instanceof Number number)) { throw new IllegalArgumentException("cell rule requires numeric data"); }
                int comparison = decimal(number.toString()).compareTo(decimal(value));
                yield switch (operator) { case GT -> comparison > 0; case GE -> comparison >= 0;
                    case LT -> comparison < 0; case LE -> comparison <= 0; default -> throw new AssertionError(); };
            }
        };
    }

    private static BigDecimal decimal(String text) {
        if (text.length() > 256 || !text.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]{1,4})?")) {
            throw new IllegalArgumentException("invalid bounded cell rule number");
        }
        return new BigDecimal(text);
    }
}
