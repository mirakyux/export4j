// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.math.BigDecimal;
import java.util.Objects;

/** Immutable scalar comparison used by block template conditions, without executing expressions.
 * Comparison values contain at most 256 UTF-16 units. Equality follows the actual data type:
 * strings compare exactly, booleans accept only {@code true}/{@code false}, and numbers compare
 * as exact bounded decimals. Ordered comparisons require numbers; contains requires text.
 * Boolean operators require an empty comparison value. Instances are safe to share. */
public record TemplateCondition(Operator operator, String value) {
    /** Supported scalar predicates. No operator coerces arrays or objects to scalar values. */
    public enum Operator { EQ, NE, GT, GE, LT, LE, CONTAINS, TRUE, FALSE }

    public TemplateCondition {
        Objects.requireNonNull(operator, "operator"); Objects.requireNonNull(value, "value");
        if (value.length() > 256) { throw new IllegalArgumentException("condition comparison value exceeds budget"); }
        if (operator == Operator.TRUE || operator == Operator.FALSE) {
            if (!value.isEmpty()) { throw new IllegalArgumentException("boolean conditions do not accept a comparison value"); }
        } else if (operator == Operator.GT || operator == Operator.GE || operator == Operator.LT || operator == Operator.LE) {
            decimal(value);
        }
    }

    boolean matches(Object actual) {
        return switch (operator) {
            case TRUE, FALSE -> {
                if (!(actual instanceof Boolean flag)) { throw new IllegalArgumentException("condition requires boolean data"); }
                yield flag == (operator == Operator.TRUE);
            }
            case CONTAINS -> {
                if (!(actual instanceof String text)) { throw new IllegalArgumentException("condition requires text data"); }
                yield text.contains(value);
            }
            case EQ, NE -> {
                boolean equal;
                if (actual instanceof String text) { equal = text.equals(value); }
                else if (actual instanceof Boolean flag) {
                    if (!value.equals("true") && !value.equals("false")) {
                        throw new IllegalArgumentException("condition requires a boolean comparison value");
                    }
                    equal = flag == value.equals("true");
                } else if (actual instanceof Number number) { equal = decimal(number.toString()).compareTo(decimal(value)) == 0; }
                else { throw new IllegalArgumentException("condition requires scalar data"); }
                yield operator == Operator.EQ ? equal : !equal;
            }
            case GT, GE, LT, LE -> {
                if (!(actual instanceof Number number)) { throw new IllegalArgumentException("condition requires numeric data"); }
                int comparison = decimal(number.toString()).compareTo(decimal(value));
                yield switch (operator) {
                    case GT -> comparison > 0;
                    case GE -> comparison >= 0;
                    case LT -> comparison < 0;
                    case LE -> comparison <= 0;
                    default -> throw new AssertionError();
                };
            }
        };
    }

    private static BigDecimal decimal(String text) {
        if (text.length() > 256 || !text.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]{1,4})?")) {
            throw new IllegalArgumentException("invalid bounded condition number");
        }
        return new BigDecimal(text);
    }
}
