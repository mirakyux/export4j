// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.apache.fontbox.type1.Type1Font;
import org.apache.pdfbox.pdmodel.font.encoding.StandardEncoding;

/** Bounds Type1 subroutine/composite expansion before the font library builds any outline. */
final class PdfType1ProgramGuard {
    private final PdfLimits limits;
    private long work;
    PdfType1ProgramGuard(PdfLimits limits) { this.limits = limits; }

    void validate(Type1Font font) throws IOException {
        for (var entry : font.getCharStringsDict().entrySet()) {
            var active = Collections.newSetFromMap(new IdentityHashMap<byte[], Boolean>());
            code(font, entry.getValue(), new ArrayList<>(), active, 0);
        }
    }
    private void code(Type1Font font, byte[] bytes, ArrayList<Double> stack, Set<byte[]> active, int depth) throws IOException {
        if (depth > limits.maxDepth()) { throw new PdfOperationException(PdfOperationException.Code.DEPTH_LIMIT, 0, -1); }
        if (bytes == null || !active.add(bytes)) { throw PdfObjects.invalid(); }
        try {
            for (int offset = 0; offset < bytes.length;) {
                ExecutionContext.checkpoint();
                if (++work > limits.maxContentOperators()) { throw new PdfOperationException(PdfOperationException.Code.OPERATOR_LIMIT, 0, -1); }
                int value = bytes[offset++] & 255;
                if (value >= 32) {
                    double number;
                    if (value <= 246) { number = value - 139; }
                    else if (value <= 250) { if (offset >= bytes.length) { throw PdfObjects.invalid(); } number = (value - 247) * 256 + (bytes[offset++] & 255) + 108; }
                    else if (value <= 254) { if (offset >= bytes.length) { throw PdfObjects.invalid(); } number = -(value - 251) * 256 - (bytes[offset++] & 255) - 108; }
                    else {
                        if (bytes.length - offset < 4) { throw PdfObjects.invalid(); }
                        number = (bytes[offset] & 255) << 24 | (bytes[offset + 1] & 255) << 16 | (bytes[offset + 2] & 255) << 8 | bytes[offset + 3] & 255; offset += 4;
                    }
                    stack.add(number);
                    if (stack.size() > 48) { throw PdfObjects.invalid(); }
                } else if (value == 10) {
                    int index = integer(pop(stack));
                    if (index < 0 || index >= font.getSubrsArray().size()) { throw PdfObjects.invalid(); }
                    code(font, font.getSubrsArray().get(index), stack, active, depth + 1);
                } else if (value == 11 || value == 14) { return; }
                else if (value == 12) {
                    if (offset >= bytes.length) { throw PdfObjects.invalid(); }
                    int escape = bytes[offset++] & 255;
                    if (escape == 12) {
                        double divisor = pop(stack); double dividend = pop(stack);
                        if (divisor == 0 || !Double.isFinite(divisor) || !Double.isFinite(dividend)) { throw PdfObjects.invalid(); }
                        stack.add(dividend / divisor);
                    } else if (escape == 6) {
                        if (stack.size() < 5) { throw PdfObjects.invalid(); }
                        int accent = integer(pop(stack)); int base = integer(pop(stack));
                        composite(font, base, active, depth); composite(font, accent, active, depth); stack.clear();
                    } else if (escape == 16) {
                        int subroutine = integer(pop(stack)); int count = integer(pop(stack));
                        if (count < 0 || count > stack.size()) { throw PdfObjects.invalid(); }
                        if (subroutine < 0 || subroutine > 3) { throw PdfObjects.unsupported(); }
                        for (int i = 0; i < count; i++) { pop(stack); }
                    } else if (escape == 17) { stack.add(Double.NaN); }
                    else { stack.clear(); }
                } else { stack.clear(); }
            }
        } finally { active.remove(bytes); }
    }
    private void composite(Type1Font font, int code, Set<byte[]> active, int depth) throws IOException {
        String name = StandardEncoding.INSTANCE.getName(code); byte[] bytes = font.getCharStringsDict().get(name);
        if (name == null || bytes == null) { throw PdfObjects.invalid(); }
        code(font, bytes, new ArrayList<>(), active, depth + 1);
    }
    private static double pop(ArrayList<Double> stack) throws IOException {
        if (stack.isEmpty()) { throw PdfObjects.invalid(); } return stack.removeLast();
    }
    private static int integer(double value) throws IOException {
        if (!Double.isFinite(value) || value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) { throw PdfObjects.unsupported(); }
        return (int) value;
    }
}
