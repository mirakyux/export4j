// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

/** Strict Markdown compilation failure with a stable code and no document or resource payload.
 * Line and column are one-based; offset and column count original UTF-16 units, including CRLF.
 * Offset is zero-based and may equal the source length for an end-of-input failure.
 */
public final class MarkdownTemplateException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        UNSUPPORTED_SYNTAX, MALFORMED_MARKUP, INVALID_EXPRESSION, INVALID_UNICODE,
        INVALID_RESOURCE_REFERENCE, UNKNOWN_RESOURCE, INVALID_VALUE, BUDGET_EXCEEDED
    }

    private final Code code;
    private final int line;
    private final int column;
    private final int offset;

    MarkdownTemplateException(Code code, int line, int column, int offset) {
        super("Markdown template compilation failed: " + code + " at " + line + ":" + column);
        this.code = code;
        this.line = line;
        this.column = column;
        this.offset = offset;
    }

    public Code code() { return code; }
    public int line() { return line; }
    public int column() { return column; }
    public int offset() { return offset; }
}
