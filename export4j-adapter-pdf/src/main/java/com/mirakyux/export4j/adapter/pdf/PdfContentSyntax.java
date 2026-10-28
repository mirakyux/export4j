// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.Set;

/** Small lexical guard, not an operator interpreter. PDFBox content parsing otherwise repairs unterminated arrays. */
final class PdfContentSyntax {
    private static final Set<String> OPERATORS = Set.of(
            "d0", "d1", "q", "Q", "cm", "w", "J", "j", "M", "d", "ri", "i", "gs", "m", "l", "c", "v", "y", "h", "re",
            "S", "s", "f", "F", "f*", "B", "B*", "b", "b*", "n", "W", "W*", "BT", "ET", "Tc", "Tw", "Tz",
            "TL", "Tf", "Tr", "Ts", "Td", "TD", "Tm", "T*", "Tj", "TJ", "'", "\"", "CS", "cs", "SC", "SCN",
            "sc", "scn", "G", "g", "RG", "rg", "K", "k", "sh", "Do", "MP", "DP", "BMC", "BDC", "EMC", "BX", "EX");
    private PdfContentSyntax() { }

    static void validate(InputStream content, PdfPreflight budget) throws IOException {
        var stack = new char[budget.limits.maxDepth()];
        int depth = 0;
        long scanned = 0;
        var token = new Token();
        try (var source = new PushbackInputStream(content, 1)) {
            int value;
            while ((value = source.read()) != -1) {
                if ((++scanned & 1023) == 0) { ExecutionContext.checkpoint(); }
                if (whitespace(value)) { token.end(budget); continue; }
                if (value == '%') {
                    token.end(budget);
                    while ((value = source.read()) != -1 && value != '\n' && value != '\r') {
                        if ((++scanned & 1023) == 0) { ExecutionContext.checkpoint(); }
                    }
                    continue;
                }
                if (value == '(') {
                    token.end(budget);
                    int strings = 1;
                    if (depth + strings > stack.length) { throw budget.failure(PdfOperationException.Code.DEPTH_LIMIT); }
                    while (strings > 0) {
                        value = source.read();
                        if (value == -1) { throw budget.failure(PdfOperationException.Code.INVALID_PDF); }
                        if ((++scanned & 1023) == 0) { ExecutionContext.checkpoint(); }
                        if (value == '\\') {
                            value = source.read();
                            if (value == -1) { throw budget.failure(PdfOperationException.Code.INVALID_PDF); }
                            if (value == '\r') {
                                int next = source.read();
                                if (next != -1 && next != '\n') { source.unread(next); }
                            }
                        } else if (value == '(') {
                            if (depth + ++strings > stack.length) { throw budget.failure(PdfOperationException.Code.DEPTH_LIMIT); }
                        } else if (value == ')') { strings--; }
                    }
                    continue;
                }
                if (value == '<') {
                    token.end(budget);
                    int next = source.read();
                    if (next == '<') {
                        if (depth == stack.length) { throw budget.failure(PdfOperationException.Code.DEPTH_LIMIT); }
                        stack[depth++] = '>';
                    } else {
                        while (next != '>') {
                            if (next == -1 || !whitespace(next) && !hex(next)) { throw budget.failure(PdfOperationException.Code.INVALID_PDF); }
                            if ((++scanned & 1023) == 0) { ExecutionContext.checkpoint(); }
                            next = source.read();
                        }
                    }
                    continue;
                }
                if (value == '[') {
                    token.end(budget);
                    if (depth == stack.length) { throw budget.failure(PdfOperationException.Code.DEPTH_LIMIT); }
                    stack[depth++] = ']';
                } else if (value == ']' || value == '>') {
                    token.end(budget);
                    if (depth == 0 || stack[--depth] != value || value == '>' && source.read() != '>') {
                        throw budget.failure(PdfOperationException.Code.INVALID_PDF);
                    }
                } else if (value == ')' || value == '{' || value == '}') {
                    throw budget.failure(PdfOperationException.Code.INVALID_PDF);
                } else {
                    if (value == '/') { token.end(budget); }
                    token.add(value, budget);
                }
            }
            token.end(budget);
            if (depth != 0) { throw budget.failure(PdfOperationException.Code.INVALID_PDF); }
        }
    }

    private static boolean whitespace(int value) { return value == 0 || value == 9 || value == 10 || value == 12 || value == 13 || value == 32; }
    private static boolean hex(int value) { return value >= '0' && value <= '9' || value >= 'A' && value <= 'F' || value >= 'a' && value <= 'f'; }

    private static final class Token {
        private final char[] characters = new char[256];
        private int length;
        void add(int value, PdfPreflight budget) throws IOException {
            if (length == characters.length) { throw budget.failure(PdfOperationException.Code.UNSUPPORTED_FEATURE); }
            characters[length++] = (char) value;
        }
        void end(PdfPreflight budget) throws IOException {
            if (length == 0) { return; }
            if (characters[0] == '/') {
                for (int index = 1; index < length; index++) {
                    if (characters[index] == '#') {
                        if (index + 2 >= length || !hex(characters[index + 1]) || !hex(characters[index + 2])) {
                            throw budget.failure(PdfOperationException.Code.INVALID_PDF);
                        }
                        index += 2;
                    }
                }
            } else {
                String value = new String(characters, 0, length);
                if (!(OPERATORS.contains(value) || value.equals("true") || value.equals("false") || value.equals("null") || number())) {
                    throw budget.failure(PdfOperationException.Code.UNSUPPORTED_FEATURE);
                }
            }
            length = 0;
        }
        private boolean number() {
            int index = characters[0] == '+' || characters[0] == '-' ? 1 : 0;
            boolean digit = false;
            boolean decimal = false;
            for (; index < length; index++) {
                char value = characters[index];
                if (value >= '0' && value <= '9') { digit = true; }
                else if (value == '.' && !decimal) { decimal = true; }
                else { return false; }
            }
            return digit;
        }
    }
}
