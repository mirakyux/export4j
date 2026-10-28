// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;

/** Exact passive geometry and local, deterministic appearances; never calls a reader's repair path. */
final class PdfPassiveAnnotations {
    private PdfPassiveAnnotations() { }
    static String subtype(PdfAnnotationSpec.Type type) {
        return switch (type) { case HIGHLIGHT -> "Highlight"; case UNDERLINE -> "Underline"; case STRIKEOUT -> "StrikeOut";
            case INK -> "Ink"; case SQUARE -> "Square"; case CIRCLE -> "Circle"; };
    }
    static boolean supported(String subtype) { return List.of("Highlight", "Underline", "StrikeOut", "Ink", "Square", "Circle").contains(subtype); }
    static PdfAnnotationSpec inspect(PDAnnotation annotation, PdfLimits limits) throws IOException {
        var object = annotation.getCOSObject(); String subtype = annotation.getSubtype();
        PdfAnnotationSpec.Type type = switch (subtype) { case "Highlight" -> PdfAnnotationSpec.Type.HIGHLIGHT; case "Underline" -> PdfAnnotationSpec.Type.UNDERLINE;
            case "StrikeOut" -> PdfAnnotationSpec.Type.STRIKEOUT; case "Ink" -> PdfAnnotationSpec.Type.INK; case "Square" -> PdfAnnotationSpec.Type.SQUARE; case "Circle" -> PdfAnnotationSpec.Type.CIRCLE; default -> throw PdfObjects.unsupported(); };
        var rectangle = annotation.getRectangle(); if (rectangle == null) { throw PdfObjects.invalid(); }
        var quads = new ArrayList<PdfAnnotationSpec.Quad>(); var ink = new ArrayList<List<PdfAnnotationSpec.Point>>(); long points = 0;
        var rawQuads = object.getDictionaryObject(key("QuadPoints"));
        if (rawQuads != null) {
            if (!(rawQuads instanceof COSArray values) || values.size() == 0 || values.size() % 8 != 0 || values.size() > limits.maxObjects()) { throw PdfObjects.invalid(); }
            for (int i = 0; i < values.size(); i += 8) {
                ExecutionContext.checkpoint(); quads.add(new PdfAnnotationSpec.Quad(point(values, i), point(values, i + 2), point(values, i + 4), point(values, i + 6))); points += 4;
            }
        }
        var rawInk = object.getDictionaryObject(key("InkList"));
        if (rawInk != null) {
            if (!(rawInk instanceof COSArray paths) || paths.size() == 0 || paths.size() > limits.maxObjects()) { throw PdfObjects.invalid(); }
            for (int i = 0; i < paths.size(); i++) {
                if (!(paths.getObject(i) instanceof COSArray values) || values.size() < 4 || values.size() % 2 != 0) { throw PdfObjects.invalid(); }
                var path = new ArrayList<PdfAnnotationSpec.Point>();
                for (int j = 0; j < values.size(); j += 2) { ExecutionContext.checkpoint(); if (++points > limits.maxObjects()) { throw PdfObjects.invalid(); } path.add(point(values, j)); }
                ink.add(path);
            }
        }
        if (points > limits.maxObjects()) { throw new PdfOperationException(PdfOperationException.Code.OBJECT_LIMIT, 0, -1); }
        double width = 1; var border = object.getDictionaryObject(COSName.BS);
        if (border != null) { if (!(border instanceof COSDictionary dictionary)) { throw PdfObjects.invalid(); } if (dictionary.containsKey(COSName.W)) { width = number(dictionary.getDictionaryObject(COSName.W)); } }
        else if (object.getDictionaryObject(COSName.BORDER) instanceof COSArray values && values.size() >= 3) { width = number(values.getObject(2)); }
        try {
            return new PdfAnnotationSpec(type, new PdfAdapter.Box(rectangle.getLowerLeftX(), rectangle.getLowerLeftY(), rectangle.getWidth(), rectangle.getHeight()),
                    annotation.getContents() == null ? "" : annotation.getContents(), quads, ink, color(object.getDictionaryObject(COSName.C), 0),
                    color(object.getDictionaryObject(COSName.IC), -1), width, object.containsKey(COSName.CA) ? number(object.getDictionaryObject(COSName.CA)) : 1);
        } catch (IllegalArgumentException failure) { throw PdfObjects.invalid(); }
    }
    static PDAnnotation create(PDDocument document, PdfAnnotationSpec spec, PdfLimits limits) throws IOException {
        ExecutionContext.checkpoint(); long points = (long) spec.quads().size() * 4;
        for (var path : spec.ink()) { points += path.size(); }
        if (points > limits.maxObjects() || points * 8 + 128 > limits.maxContentOperators()) { throw new PdfOperationException(PdfOperationException.Code.OBJECT_LIMIT, 0, -1); }
        var object = new COSDictionary(); object.setName(COSName.TYPE, "Annot"); object.setName(COSName.SUBTYPE, subtype(spec.type()));
        object.setItem(COSName.RECT, array(spec.box().x(), spec.box().y(), spec.box().x() + spec.box().width(), spec.box().y() + spec.box().height()));
        object.setString(COSName.CONTENTS, spec.contents()); object.setItem(COSName.C, rgbArray(spec.strokeRgb()));
        object.setFloat(COSName.CA, (float) spec.opacity()); object.setInt(COSName.F, 4);
        var border = new COSDictionary(); border.setFloat(COSName.W, (float) spec.width()); border.setName(COSName.S, "S"); object.setItem(COSName.BS, border);
        if (spec.fillRgb() != -1) { object.setItem(COSName.IC, rgbArray(spec.fillRgb())); }
        if (!spec.quads().isEmpty()) { var values = new COSArray(); for (var quad : spec.quads()) { for (var point : quad.points()) { values.add(new COSFloat((float) point.x())); values.add(new COSFloat((float) point.y())); } } object.setItem(key("QuadPoints"), values); }
        if (!spec.ink().isEmpty()) { var paths = new COSArray(); for (var path : spec.ink()) { var values = new COSArray(); for (var point : path) { values.add(new COSFloat((float) point.x())); values.add(new COSFloat((float) point.y())); } paths.add(values); } object.setItem(key("InkList"), paths); }
        var stream = new PDAppearanceStream(document); stream.setBBox(new PDRectangle((float) spec.box().width(), (float) spec.box().height()));
        var resources = new PDResources(); stream.setResources(resources); var graphics = new PDExtendedGraphicsState();
        graphics.setStrokingAlphaConstant((float) spec.opacity()); graphics.setNonStrokingAlphaConstant((float) spec.opacity());
        if (spec.type() == PdfAnnotationSpec.Type.HIGHLIGHT) { graphics.getCOSObject().setName(COSName.BM, "Multiply"); }
        var state = resources.add(graphics);
        var commands = new StringBuilder("q\n/").append(state.getName()).append(" gs\n").append(rgb(spec.strokeRgb(), false)).append(rgb(spec.strokeRgb(), true)).append(n(spec.width())).append(" w\n1 J 1 j\n");
        for (var quad : spec.quads()) {
            ExecutionContext.checkpoint();
            if (spec.type() == PdfAnnotationSpec.Type.HIGHLIGHT) { point(commands, spec, quad.upperLeft(), "m"); point(commands, spec, quad.upperRight(), "l"); point(commands, spec, quad.lowerRight(), "l"); point(commands, spec, quad.lowerLeft(), "l"); commands.append("h f\n"); }
            else { double fraction = spec.type() == PdfAnnotationSpec.Type.UNDERLINE ? .06 : .5;
                point(commands, spec, interpolate(quad.lowerLeft(), quad.upperLeft(), fraction), "m"); point(commands, spec, interpolate(quad.lowerRight(), quad.upperRight(), fraction), "l"); commands.append("S\n"); }
        }
        for (var path : spec.ink()) { for (int i = 0; i < path.size(); i++) { point(commands, spec, path.get(i), i == 0 ? "m" : "l"); } commands.append("S\n"); }
        if (spec.type() == PdfAnnotationSpec.Type.SQUARE || spec.type() == PdfAnnotationSpec.Type.CIRCLE) {
            double inset = spec.width() / 2, w = spec.box().width() - spec.width(), h = spec.box().height() - spec.width();
            if (w <= 0 || h <= 0) { throw new IllegalArgumentException("annotation stroke exceeds rectangle"); }
            if (spec.fillRgb() != -1) { commands.append(rgb(spec.fillRgb(), false)); }
            if (spec.type() == PdfAnnotationSpec.Type.SQUARE) { commands.append(n(inset)).append(' ').append(n(inset)).append(' ').append(n(w)).append(' ').append(n(h)).append(" re\n"); }
            else { ellipse(commands, inset, inset, w, h); }
            commands.append(spec.fillRgb() == -1 ? "S\n" : "B\n");
        }
        commands.append("Q\n"); byte[] bytes = commands.toString().getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > limits.maxDecodedStreamBytes()) { throw new PdfOperationException(PdfOperationException.Code.DECODED_STREAM_LIMIT, 0, -1); }
        try (var output = stream.getCOSObject().createOutputStream(COSName.FLATE_DECODE)) { output.write(bytes); }
        var appearance = new PDAppearanceDictionary(); appearance.setNormalAppearance(stream); var annotation = PDAnnotation.createAnnotation(object); annotation.setAppearance(appearance); return annotation;
    }
    private static void ellipse(StringBuilder text, double x, double y, double w, double h) {
        double rx = w / 2, ry = h / 2, cx = x + rx, cy = y + ry, k = .5522847498307936;
        text.append(n(cx + rx)).append(' ').append(n(cy)).append(" m\n");
        double[][] curves = {{cx+rx,cy+ry*k,cx+rx*k,cy+ry,cx,cy+ry},{cx-rx*k,cy+ry,cx-rx,cy+ry*k,cx-rx,cy},{cx-rx,cy-ry*k,cx-rx*k,cy-ry,cx,cy-ry},{cx+rx*k,cy-ry,cx+rx,cy-ry*k,cx+rx,cy}};
        for (var curve : curves) { for (double number : curve) { text.append(n(number)).append(' '); } text.append("c\n"); } text.append("h\n");
    }
    private static PdfAnnotationSpec.Point interpolate(PdfAnnotationSpec.Point a, PdfAnnotationSpec.Point b, double value) { return new PdfAnnotationSpec.Point(a.x() + (b.x() - a.x()) * value, a.y() + (b.y() - a.y()) * value); }
    private static void point(StringBuilder text, PdfAnnotationSpec spec, PdfAnnotationSpec.Point point, String command) { text.append(n(point.x() - spec.box().x())).append(' ').append(n(point.y() - spec.box().y())).append(' ').append(command).append('\n'); }
    private static PdfAnnotationSpec.Point point(COSArray values, int offset) throws IOException { try{return new PdfAnnotationSpec.Point(number(values.getObject(offset)), number(values.getObject(offset + 1)));}catch(IllegalArgumentException failure){throw PdfObjects.invalid();} }
    private static COSName key(String name) { return COSName.getPDFName(name); }
    private static COSArray array(double... numbers) { var value = new COSArray(); for (double number : numbers) { value.add(new COSFloat((float) number)); } return value; }
    private static COSArray rgbArray(int value) { return array(((value >> 16) & 255) / 255d, ((value >> 8) & 255) / 255d, (value & 255) / 255d); }
    static String rgb(int value, boolean stroke) { return n(((value >> 16) & 255) / 255d) + " " + n(((value >> 8) & 255) / 255d) + " " + n((value & 255) / 255d) + (stroke ? " RG\n" : " rg\n"); }
    static String n(double number) { return BigDecimal.valueOf(number).stripTrailingZeros().toPlainString(); }
    private static double number(COSBase value) throws IOException { if (!(value instanceof COSNumber number) || !Float.isFinite(number.floatValue()) || Math.abs(number.floatValue()) > 1_000_000) { throw PdfObjects.invalid(); } return number.floatValue(); }
    private static int color(COSBase value, int fallback) throws IOException {
        if (value == null) { return fallback; } if (!(value instanceof COSArray values) || !List.of(0, 1, 3, 4).contains(values.size())) { throw PdfObjects.invalid(); }
        if (values.size() == 0) { return fallback; } double[] parts = new double[values.size()]; for (int i = 0; i < parts.length; i++) { parts[i] = number(values.getObject(i)); if (parts[i] < 0 || parts[i] > 1) { throw PdfObjects.invalid(); } }
        double r, g, b; if (parts.length == 1) { r = g = b = parts[0]; } else if (parts.length == 3) { r=parts[0];g=parts[1];b=parts[2]; } else { r=1-Math.min(1,parts[0]+parts[3]);g=1-Math.min(1,parts[1]+parts[3]);b=1-Math.min(1,parts[2]+parts[3]); }
        return (int) Math.round(r * 255) << 16 | (int) Math.round(g * 255) << 8 | (int) Math.round(b * 255);
    }
}
