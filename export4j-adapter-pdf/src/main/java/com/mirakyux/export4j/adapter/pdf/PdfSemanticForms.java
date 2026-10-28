// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.util.Matrix;

/** Validates actual Form invocation paths without treating a shared stream as a single execution. */
final class PdfSemanticForms {
    enum Visibility { VISIBLE, HIDDEN, PARTIAL }

    private final PdfLimits limits;
    private final PdfSemanticGraphicsState graphicsStates;
    private final Set<COSStream> active = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<COSStream, List<Object>> parsed = new IdentityHashMap<>();
    private long operators;
    private long geometry;
    private long cachedTokens;

    PdfSemanticForms(PdfLimits limits) { this.limits = limits; graphicsStates = new PdfSemanticGraphicsState(limits); }

    boolean supported(PDPage page) throws IOException {
        var frame = new Frame(new Matrix());
        boolean supported = true;
        var streams = page.getContentStreams();
        var operands = new ArrayList<COSBase>();
        while (streams.hasNext()) {
            supported &= scan(streams.next().getCOSObject(), page.getResources(), frame, operands, 0);
        }
        if (!operands.isEmpty() || !frame.saved.isEmpty()) { throw PdfObjects.invalid(); }
        return supported;
    }

    Visibility visibility(PDGraphicsState state, Shape shape) throws IOException {
        var iterator = shape.getPathIterator(null);
        var coordinates = new double[6];
        while (!iterator.isDone()) {
            ExecutionContext.checkpoint();
            if (++geometry > limits.maxContentOperators()) { throw new PdfOperationException(PdfOperationException.Code.OPERATOR_LIMIT, 0, -1); }
            int kind = iterator.currentSegment(coordinates);
            int count = switch (kind) {
                case PathIterator.SEG_MOVETO, PathIterator.SEG_LINETO -> 2;
                case PathIterator.SEG_QUADTO -> 4;
                case PathIterator.SEG_CUBICTO -> 6;
                default -> 0;
            };
            for (int i = 0; i < count; i++) { if (!Double.isFinite(coordinates[i])) { throw PdfObjects.invalid(); } }
            iterator.next();
        }
        var painted = new Area(shape);
        if (painted.isEmpty()) { return Visibility.VISIBLE; }
        var visible = new Area(painted);
        visible.intersect(state.getCurrentClippingPath());
        if (visible.isEmpty()) { return Visibility.HIDDEN; }
        painted.subtract(state.getCurrentClippingPath());
        return painted.isEmpty() ? Visibility.VISIBLE : Visibility.PARTIAL;
    }

    private boolean scan(COSStream stream, PDResources resources, Frame frame, List<COSBase> operands, int depth) throws IOException {
        if (depth > limits.maxDepth()) { throw new PdfOperationException(PdfOperationException.Code.DEPTH_LIMIT, 0, -1); }
        if (!active.add(stream)) { throw PdfObjects.invalid(); }
        boolean supported = true;
        try {
            for (var token : tokens(stream)) {
                ExecutionContext.checkpoint();
                if (token instanceof Operator operator) {
                    if (++operators > limits.maxContentOperators()) { throw new PdfOperationException(PdfOperationException.Code.OPERATOR_LIMIT, 0, -1); }
                    switch (operator.getName()) {
                        case "q" -> {
                            arity(operands, 0);
                            if (depth + frame.saved.size() + 1 > limits.maxDepth()) { throw new PdfOperationException(PdfOperationException.Code.DEPTH_LIMIT, 0, -1); }
                            frame.saved.push(frame.matrix.clone());
                        }
                        case "Q" -> { arity(operands, 0); if (frame.saved.isEmpty()) { throw PdfObjects.invalid(); } frame.matrix = frame.saved.pop(); }
                        case "cm" -> { frame.matrix.concatenate(matrix(operands)); finite(frame.matrix); }
                        case "Do" -> {
                            arity(operands, 1);
                            if (!(operands.getFirst() instanceof COSName name) || resources == null
                                    || !(resources.getCOSObject().getDictionaryObject(COSName.XOBJECT) instanceof COSDictionary objects)
                                    || objects instanceof COSStream
                                    || !(objects.getDictionaryObject(name) instanceof COSStream object)) { throw PdfObjects.invalid(); }
                            String subtype = object.getNameAsString(COSName.SUBTYPE, "");
                            if (subtype.equals("Form")) { supported &= form(object, resources, frame, depth); }
                            else if (!subtype.equals("Image")) { throw PdfObjects.invalid(); }
                        }
                        case "gs" -> { if (!graphicsStates.supported(resources, operands)) { supported = false; } }
                        case "W", "W*" -> { arity(operands, 0); supported = false; }
                        case "Tr" -> {
                            arity(operands, 1);
                            if (!(operands.getFirst() instanceof COSInteger number) || number.longValue() < 0 || number.longValue() > 7) { throw PdfObjects.invalid(); }
                            if (number.intValue() != 0 && number.intValue() != 3) { supported = false; }
                        }
                        case "Tf" -> {
                            arity(operands, 2);
                            if (!(operands.getFirst() instanceof COSName name) || resources == null
                                    || !(resources.getCOSObject().getDictionaryObject(COSName.FONT) instanceof COSDictionary fonts)
                                    || fonts instanceof COSStream || !(fonts.getDictionaryObject(name) instanceof COSDictionary font)
                                    || font instanceof COSStream) { throw PdfObjects.invalid(); }
                            number(operands.get(1));
                            if ("Type3".equals(font.getNameAsString(COSName.SUBTYPE))) { supported = false; }
                        }
                        default -> { }
                    }
                    operands.clear();
                } else if (token instanceof COSBase operand) { operands.add(operand); }
                else { throw PdfObjects.invalid(); }
            }
        } finally { active.remove(stream); }
        return supported;
    }

    private List<Object> tokens(COSStream stream) throws IOException {
        var cached = parsed.get(stream);
        if (cached != null) { return cached; }
        var parser = new PDFStreamParser(new PdfContentStream(stream));
        var values = new ArrayList<Object>();
        try {
            Object token;
            while ((token = parser.parseNextToken()) != null) {
                ExecutionContext.checkpoint();
                if (!(token instanceof Operator) && !(token instanceof COSBase)) { throw PdfObjects.invalid(); }
                if (++cachedTokens > limits.maxObjects()) { throw new PdfOperationException(PdfOperationException.Code.OBJECT_LIMIT, 0, -1); }
                values.add(token);
            }
        } finally { parser.close(); }
        var result = List.copyOf(values); parsed.put(stream, result); return result;
    }

    private boolean form(COSStream form, PDResources inherited, Frame parent, int depth) throws IOException {
        if (form.containsKey(COSName.FORMTYPE)
                && (!(form.getDictionaryObject(COSName.FORMTYPE) instanceof COSInteger type) || type.longValue() != 1)) { throw PdfObjects.invalid(); }
        if (!(form.getDictionaryObject(COSName.BBOX) instanceof COSArray box) || box.size() != 4) { throw PdfObjects.invalid(); }
        double left = number(box.getObject(0)); double bottom = number(box.getObject(1));
        double right = number(box.getObject(2)); double top = number(box.getObject(3));
        if (right <= left || top <= bottom || !Float.isFinite((float) (right - left)) || !Float.isFinite((float) (top - bottom))) { throw PdfObjects.invalid(); }
        Matrix local = new Matrix();
        if (form.containsKey(COSName.MATRIX)) {
            if (!(form.getDictionaryObject(COSName.MATRIX) instanceof COSArray values)) { throw PdfObjects.invalid(); }
            var operands = new ArrayList<COSBase>();
            for (int i = 0; i < values.size(); i++) { operands.add(values.getObject(i)); }
            local = matrix(operands);
        }
        var frame = new Frame(parent.matrix.clone()); frame.matrix.concatenate(local); finite(frame.matrix);
        for (double x : new double[] {left, right}) {
            for (double y : new double[] {bottom, top}) {
                if (!Float.isFinite((float) (frame.matrix.getScaleX() * x + frame.matrix.getShearX() * y + frame.matrix.getTranslateX()))
                        || !Float.isFinite((float) (frame.matrix.getShearY() * x + frame.matrix.getScaleY() * y + frame.matrix.getTranslateY()))) { throw PdfObjects.invalid(); }
            }
        }
        boolean supported = axisUniform(frame.matrix);
        PDResources resources = inherited;
        if (form.containsKey(COSName.RESOURCES)) {
            if (!(form.getDictionaryObject(COSName.RESOURCES) instanceof COSDictionary dictionary) || dictionary instanceof COSStream) { throw PdfObjects.invalid(); }
            resources = new PDResources(dictionary);
        }
        if (form.containsKey(COSName.GROUP)) {
            if (!(form.getDictionaryObject(COSName.GROUP) instanceof COSDictionary group) || group instanceof COSStream
                    || !(group.getDictionaryObject(COSName.S) instanceof COSName)) { throw PdfObjects.invalid(); }
            supported = false;
        }
        var operands = new ArrayList<COSBase>();
        supported &= scan(form, resources, frame, operands, depth + parent.saved.size() + 1);
        if (!operands.isEmpty() || !frame.saved.isEmpty()) { throw PdfObjects.invalid(); }
        return supported;
    }

    static boolean axisUniform(Matrix matrix) {
        return matrix.getShearX() == 0 && matrix.getShearY() == 0 && matrix.getScaleX() > 0
                && matrix.getScaleY() > 0 && Math.abs(matrix.getScaleX() - matrix.getScaleY()) <= Math.max(matrix.getScaleX(), matrix.getScaleY()) * 0.00001;
    }

    private static Matrix matrix(List<COSBase> operands) throws IOException {
        arity(operands, 6);
        return new Matrix((float) number(operands.get(0)), (float) number(operands.get(1)), (float) number(operands.get(2)),
                (float) number(operands.get(3)), (float) number(operands.get(4)), (float) number(operands.get(5)));
    }

    private static double number(COSBase value) throws IOException {
        if (!(value instanceof COSNumber number)) { throw PdfObjects.invalid(); }
        double result = number instanceof COSInteger ? number.longValue() : number.floatValue();
        if (!Double.isFinite(result) || !Float.isFinite((float) result)) { throw PdfObjects.invalid(); }
        return result;
    }

    private static void finite(Matrix matrix) throws IOException {
        if (!Float.isFinite(matrix.getScaleX()) || !Float.isFinite(matrix.getScaleY())
                || !Float.isFinite(matrix.getShearX()) || !Float.isFinite(matrix.getShearY())
                || !Float.isFinite(matrix.getTranslateX()) || !Float.isFinite(matrix.getTranslateY())) { throw PdfObjects.invalid(); }
    }

    private static void arity(List<COSBase> operands, int count) throws IOException { if (operands.size() != count) { throw PdfObjects.invalid(); } }

    private static final class Frame {
        Matrix matrix;
        final ArrayDeque<Matrix> saved = new ArrayDeque<>();
        Frame(Matrix matrix) { this.matrix = matrix; }
    }
}
