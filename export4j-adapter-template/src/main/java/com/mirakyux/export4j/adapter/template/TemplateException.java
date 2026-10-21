// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import java.util.Optional;

/** Strict compilation failure. Locations are one-based XML source lines/columns, or 1 when unavailable.
 * Messages contain only a stable code and location, never template text, resource paths or data values.
 */
public final class TemplateException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        MALFORMED_XML, FORBIDDEN_XML, UNSUPPORTED_ELEMENT, UNSUPPORTED_ATTRIBUTE,
        INVALID_STRUCTURE, INVALID_VALUE, INVALID_EXPRESSION, UNKNOWN_RESOURCE, BUDGET_EXCEEDED
    }

    private final Code code;
    private final int line;
    private final int column;
    private final String fragmentName;

    TemplateException(Code code, int line, int column) {
        this(code, line, column, "");
    }

    TemplateException(Code code, int line, int column, String fragmentName) {
        super("template compilation failed: " + code + " at " + Math.max(1, line) + ":" + Math.max(1, column));
        this.code = code;
        this.line = Math.max(1, line);
        this.column = Math.max(1, column);
        this.fragmentName = fragmentName;
    }

    public Code code() { return code; }
    public int line() { return line; }
    public int column() { return column; }
    /** Registered fragment name for a fragment-source failure, or empty for the main source.
     * Locations refer to this source; names never enter the exception message. */
    public Optional<String> fragmentName() {
        return fragmentName.isEmpty() ? Optional.empty() : Optional.of(fragmentName);
    }
}
