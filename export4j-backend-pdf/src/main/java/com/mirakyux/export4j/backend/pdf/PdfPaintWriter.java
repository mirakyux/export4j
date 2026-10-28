// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import com.mirakyux.export4j.graphics.BlendMode;
import com.mirakyux.export4j.graphics.CmykColor;
import com.mirakyux.export4j.graphics.ColorValue;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.IccColor;
import com.mirakyux.export4j.graphics.Paint;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.SpotColor;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.graphics.core.GraphicsResources;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType2;
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType4;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.color.PDICCBased;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.color.PDSeparation;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType1;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.util.Matrix;

/** PDF-native print colors, functional gradients, patterns and isolated transparency groups. */
final class PdfPaintWriter {
    private PdfPaintWriter() { }
    static Paint prepare(Paint paint, PdfArchival.Profile archival, com.mirakyux.export4j.model.processing.ProcessingPolicy policy,
            List<com.mirakyux.export4j.model.processing.ProcessingIssue> issues) {
        var colors = GraphicsResources.colors(paint);
        boolean mixed = !(paint instanceof Paint.Solid) && !colors.isEmpty() && colors.stream().anyMatch(color -> !compatible(colors.getFirst(), color));
        boolean archivalConversion = archival != null && colors.stream().anyMatch(color -> archiveColor(color, archival));
        if (!mixed && !archivalConversion) { return paint; }
        String feature = mixed ? "gradient-color-space" : "archival-color-space";
        var rule = policy.resolve("PDF", feature, "graphics-color");
        var issue = new com.mirakyux.export4j.model.processing.ProcessingIssue("GRAPHICS", "DISPLAY", "PDF", feature, "graphics-color",
                new com.mirakyux.export4j.model.processing.ProcessingIssue.Location("", "", "", List.of(), -1, -1),
                mixed ? "gradient stops use different color spaces" : "color space conflicts with the configured archival output intent",
                rule.action(), rule.treatment(), List.of("paint is converted to explicit RGB appearance; original print-space interpolation is lost"));
        if (rule.action() == com.mirakyux.export4j.model.processing.ProcessingPolicy.Action.ERROR || rule.treatment() != com.mirakyux.export4j.model.processing.ProcessingPolicy.Treatment.DEGRADE) { throw new com.mirakyux.export4j.model.processing.ProcessingException(issue); }
        issues.add(issue);
        return switch (paint) {
            case Paint.Solid solid -> new Paint.Solid(GraphicsResources.rgb(solid.color()));
            case Paint.Linear linear -> new Paint.Linear(linear.start(), linear.end(), linear.stops().stream().map(stop -> new Paint.Stop(stop.offset(), GraphicsResources.rgb(stop.color()), stop.opacity())).toList(), linear.spread(), linear.transform());
            case Paint.Radial radial -> new Paint.Radial(radial.center(), radial.focus(), radial.radius(), radial.stops().stream().map(stop -> new Paint.Stop(stop.offset(), GraphicsResources.rgb(stop.color()), stop.opacity())).toList(), radial.spread(), radial.transform());
            case Paint.Tile tile -> tile;
        };
    }
    private static boolean archiveColor(ColorValue color, PdfArchival.Profile profile) {
        if (color instanceof SpotColor spot) { return archiveColor(spot.alternate(), profile); }
        return color instanceof CmykColor || profile == PdfArchival.Profile.A1B && color instanceof IccColor icc && java.awt.color.ICC_Profile.getInstance(icc.profile()).getMajorVersion() != 2;
    }
    @FunctionalInterface interface Renderer { void render(PDPageContentStream stream, List<DrawCommand> commands, PDResources resources, java.awt.geom.AffineTransform initialMatrix) throws IOException; }

    static void path(PDPageContentStream stream, VectorPath path) throws IOException {
        for (var segment : path.segments()) {
            switch (segment) {
                case VectorPath.MoveTo p -> stream.moveTo(f(p.point().x()), f(p.point().y()));
                case VectorPath.LineTo p -> stream.lineTo(f(p.point().x()), f(p.point().y()));
                case VectorPath.CurveTo p -> stream.curveTo(f(p.control1().x()), f(p.control1().y()), f(p.control2().x()), f(p.control2().y()), f(p.end().x()), f(p.end().y()));
                case VectorPath.Close ignored -> stream.closePath();
            }
        }
    }
    static void blend(PDPageContentStream stream, BlendMode blend) throws IOException { var state = new PDExtendedGraphicsState(); state.getCOSObject().setItem(COSName.BM, COSName.getPDFName(blendName(blend))); stream.setGraphicsStateParameters(state); }
    private static String blendName(BlendMode mode) { var result = new StringBuilder(); for (String word : mode.name().split("_")) { result.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT)); } return result.toString(); }
    static void group(PDDocument pdf, PDPageContentStream stream, DrawCommand.Group group, double outerAlpha, java.awt.geom.AffineTransform currentMatrix, Renderer renderer) throws IOException {
        var form = fragment(pdf, group.bounds(), group.commands(), renderer, true, currentMatrix);
        stream.saveGraphicsState();
        var state = new PDExtendedGraphicsState(); state.setNonStrokingAlphaConstant(f(outerAlpha * group.opacity())); state.setStrokingAlphaConstant(f(outerAlpha * group.opacity())); state.getCOSObject().setItem(COSName.BM, COSName.getPDFName(blendName(group.blend()))); stream.setGraphicsStateParameters(state);
        stream.drawForm(form); stream.restoreGraphicsState();
    }
    private static PDAppearanceStream fragment(PDDocument pdf, Rect bounds, List<DrawCommand> commands, Renderer renderer, boolean isolated, java.awt.geom.AffineTransform initialMatrix) throws IOException {
        var form = new PDAppearanceStream(pdf); form.setBBox(rect(bounds)); form.setResources(new PDResources());
        if (isolated) { var attributes = new COSDictionary(); attributes.setItem(COSName.S, COSName.TRANSPARENCY); attributes.setBoolean(COSName.I, true); attributes.setItem(COSName.CS, COSName.DEVICERGB); form.getCOSObject().setItem(COSName.GROUP, attributes); }
        try (var content = new PDPageContentStream(pdf, form)) {
            var state = new PDExtendedGraphicsState(); state.setNonStrokingAlphaConstant(1f); state.setStrokingAlphaConstant(1f); state.getCOSObject().setItem(COSName.BM, COSName.NORMAL); state.getCOSObject().setItem(COSName.SMASK, COSName.NONE); content.setGraphicsStateParameters(state);
            renderer.render(content, commands, form.getResources(), initialMatrix);
        }
        return form;
    }
    static void paint(PDDocument pdf, PDPageContentStream stream, VectorPath vector, Paint paint, DrawCommand.FillRule rule, double strokeWidth, double alpha, BlendMode outerBlend, java.awt.geom.AffineTransform currentMatrix, PDResources resources, Renderer renderer) throws IOException {
        stream.saveGraphicsState();
        if (paint instanceof Paint.Solid solid) {
            var color = color(pdf, solid.color());
            if (strokeWidth > 0) { stream.setStrokingColor(color); stream.setLineWidth(f(strokeWidth)); path(stream, vector); stream.stroke(); }
            else { stream.setNonStrokingColor(color); path(stream, vector); fill(stream, rule); }
        } else if (paint instanceof Paint.Tile tile) {
            if (alpha != 1 || outerBlend != BlendMode.NORMAL) {
                var shape = GraphicsResources.path(vector, rule);
                if (strokeWidth > 0) { shape = new java.awt.geom.Path2D.Double(new java.awt.BasicStroke(f(strokeWidth), java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER).createStrokedShape(shape)); }
                var bounds = shape.getBounds2D();
                if (!bounds.isEmpty()) {
                    var form = fragment(pdf, new Rect(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight()),
                            List.of(new DrawCommand.PaintFill(GraphicsResources.vector(shape), tile, strokeWidth > 0 ? DrawCommand.FillRule.NONZERO : rule)), renderer, true, currentMatrix);
                    var state = new PDExtendedGraphicsState(); state.setNonStrokingAlphaConstant(f(alpha)); state.setStrokingAlphaConstant(f(alpha)); state.getCOSObject().setItem(COSName.BM, COSName.getPDFName(blendName(outerBlend))); stream.setGraphicsStateParameters(state); stream.drawForm(form);
                }
                stream.restoreGraphicsState(); return;
            }

            var pattern = new PDTilingPattern(); pattern.setBBox(rect(tile.bounds())); pattern.setXStep(f(tile.xStep())); pattern.setYStep(f(tile.yStep())); pattern.setPaintType(PDTilingPattern.PAINT_COLORED); pattern.setTilingType(PDTilingPattern.TILING_CONSTANT_SPACING); var patternMatrix = new java.awt.geom.AffineTransform(currentMatrix); patternMatrix.concatenate(GraphicsResources.matrix(tile.transform())); pattern.setMatrix(patternMatrix);
            var form = fragment(pdf, tile.bounds(), tile.commands(), renderer, false, patternMatrix); pattern.setResources(form.getResources());
            try (var input = form.getContents(); var output = pattern.getContentStream().createOutputStream(COSName.FLATE_DECODE)) { input.transferTo(output); }
            // A local resource dictionary is obtained by a form wrapper, avoiding access to PDFBox internals.
            var name = resources.add(pattern); var patternSpace = new PDPattern(resources);
            var color = new PDColor(name, patternSpace);
            if (strokeWidth > 0) { stream.setStrokingColor(color); stream.setLineWidth(f(strokeWidth)); path(stream, vector); stream.stroke(); }
            else { stream.setNonStrokingColor(color); path(stream, vector); fill(stream, rule); }
        } else {
            var shape = GraphicsResources.path(vector, rule);
            if (strokeWidth > 0) { shape = new java.awt.geom.Path2D.Double(new java.awt.BasicStroke(f(strokeWidth), java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER).createStrokedShape(shape)); }
            path(stream, GraphicsResources.vector(shape)); if (rule == DrawCommand.FillRule.EVEN_ODD && strokeWidth == 0) { stream.clipEvenOdd(); } else { stream.clip(); }
            var transform = paint instanceof Paint.Linear p ? p.transform() : ((Paint.Radial) paint).transform();
            var matrix = GraphicsResources.matrix(transform); final java.awt.geom.Rectangle2D bounds;
            try { bounds = matrix.createInverse().createTransformedShape(shape).getBounds2D(); } catch (java.awt.geom.NoninvertibleTransformException failure) { throw new IllegalArgumentException("singular gradient matrix", failure); }
            if (!bounds.isEmpty()) {
                stream.transform(new Matrix(matrix));
                var stops = paint instanceof Paint.Linear p ? p.stops() : ((Paint.Radial) paint).stops();
                var state = new PDExtendedGraphicsState(); state.setNonStrokingAlphaConstant(f(alpha));
                if (stops.stream().anyMatch(stop -> stop.opacity() != 1)) {
                    var mask = new PDAppearanceStream(pdf); mask.setResources(new PDResources()); mask.setBBox(new PDRectangle(f(bounds.getX()), f(bounds.getY()), f(bounds.getWidth()), f(bounds.getHeight())));
                    var attributes = new COSDictionary(); attributes.setItem(COSName.S, COSName.TRANSPARENCY); attributes.setBoolean(COSName.I, true); attributes.setItem(COSName.CS, COSName.DEVICEGRAY); mask.getCOSObject().setItem(COSName.GROUP, attributes);
                    try (var content = new PDPageContentStream(pdf, mask)) { var opaque = new PDExtendedGraphicsState(); opaque.setNonStrokingAlphaConstant(1f); opaque.setStrokingAlphaConstant(1f); content.setGraphicsStateParameters(opaque); content.shadingFill(shading(pdf, paint, bounds, true)); }
                    var softMask = new COSDictionary(); softMask.setItem(COSName.S, COSName.LUMINOSITY); softMask.setItem(COSName.G, mask); state.getCOSObject().setItem(COSName.SMASK, softMask);
                }
                stream.setGraphicsStateParameters(state); stream.shadingFill(shading(pdf, paint, bounds, false));
            }
        }
        stream.restoreGraphicsState();
    }
    private static void fill(PDPageContentStream stream, DrawCommand.FillRule rule) throws IOException { if (rule == DrawCommand.FillRule.EVEN_ODD) { stream.fillEvenOdd(); } else { stream.fill(); } }

    static PDColor color(PDDocument pdf, ColorValue value) throws IOException {
        return new PDColor(components(value), space(pdf, value));
    }
    private static PDColorSpace space(PDDocument pdf, ColorValue value) throws IOException {
        return switch (value) {
            case RgbColor ignored -> PDDeviceRGB.INSTANCE;
            case CmykColor ignored -> PDDeviceCMYK.INSTANCE;
            case IccColor icc -> { var space = new PDICCBased(pdf); space.getPDStream().getCOSObject().setInt(COSName.N, icc.components().size()); try (var output = space.getPDStream().createOutputStream(COSName.FLATE_DECODE)) { output.write(icc.profile()); } yield space; }
            case SpotColor spot -> { var separation = new PDSeparation(); separation.setColorantName(spot.name()); separation.setAlternateColorSpace(space(pdf, spot.alternate())); var dictionary = new COSDictionary(); dictionary.setInt(COSName.FUNCTION_TYPE, 2); dictionary.setItem(COSName.DOMAIN, array(0, 1)); float[] white = components(spot.alternate()); if (spot.alternate() instanceof CmykColor || spot.alternate() instanceof IccColor icc && icc.components().size() == 4) { java.util.Arrays.fill(white, 0); } else { java.util.Arrays.fill(white, 1); } dictionary.setItem(COSName.C0, array(white)); dictionary.setItem(COSName.C1, array(components(spot.alternate()))); dictionary.setInt(COSName.N, 1); separation.setTintTransform(new PDFunctionType2(dictionary)); yield separation; }
        };
    }
    private static float[] components(ColorValue value) { return switch (value) {
        case RgbColor c -> new float[] {f(c.red()), f(c.green()), f(c.blue())};
        case CmykColor c -> new float[] {f(c.cyan()), f(c.magenta()), f(c.yellow()), f(c.black())};
        case IccColor c -> { var values = new float[c.components().size()]; for (int i = 0; i < values.length; i++) { values[i] = f(c.components().get(i)); } yield values; }
        case SpotColor c -> new float[] {f(c.tint())};
    }; }
    static boolean compatible(ColorValue left, ColorValue right) {
        if (left.getClass() != right.getClass()) { return false; }
        if (left instanceof IccColor a && right instanceof IccColor b) { return a.id().equals(b.id()); }
        if (left instanceof SpotColor a && right instanceof SpotColor b) { return a.name().equals(b.name()) && a.alternate().equals(b.alternate()); }
        return true;
    }
    private static PDShadingType1 shading(PDDocument pdf, Paint paint, java.awt.geom.Rectangle2D bounds, boolean alpha) throws IOException {
        var stops = paint instanceof Paint.Linear linear ? linear.stops() : ((Paint.Radial) paint).stops();
        var prototype = stops.getFirst().color();
        if (!alpha && stops.stream().anyMatch(stop -> !compatible(prototype, stop.color()))) { throw new IllegalArgumentException("gradient stops must share one explicit color space"); }
        var domain = array(f(bounds.getMinX()), f(bounds.getMaxX()), f(bounds.getMinY()), f(bounds.getMaxY()));
        int channels = alpha ? 1 : components(prototype).length;
        var function = new PDStream(pdf); function.getCOSObject().setInt(COSName.FUNCTION_TYPE, 4); function.getCOSObject().setItem(COSName.DOMAIN, domain); var range = new COSArray(); for (int i = 0; i < channels; i++) { range.add(COSInteger.ZERO); range.add(COSInteger.ONE); } function.getCOSObject().setItem(COSName.RANGE, range);
        String code = "{ " + parameter(paint).code(0) + " exch pop exch pop " + spread(paint) + interpolation(stops, alpha, channels) + " }";
        try (var output = function.createOutputStream(COSName.FLATE_DECODE)) { output.write(code.getBytes(StandardCharsets.US_ASCII)); }
        var shading = new PDShadingType1(new COSDictionary()); shading.setShadingType(1); shading.setColorSpace(alpha ? PDDeviceGray.INSTANCE : space(pdf, prototype)); shading.setDomain(domain); shading.setFunction(new PDFunctionType4(function.getCOSObject())); return shading;
    }

    @FunctionalInterface private interface Expr { String code(int depth); }
    private static Expr x() { return depth -> (depth + 1) + " index "; }
    private static Expr y() { return depth -> depth + " index "; }
    private static Expr n(double value) { return depth -> number(value) + " "; }
    private static Expr op(Expr a, Expr b, String operation) { return depth -> a.code(depth) + b.code(depth + 1) + operation + " "; }
    private static Expr sq(Expr a) { return op(a, a, "mul"); }
    private static Expr parameter(Paint paint) {
        if (paint instanceof Paint.Linear p) { double dx = p.end().x() - p.start().x(), dy = p.end().y() - p.start().y(); return op(op(op(op(x(), n(p.start().x()), "sub"), n(dx), "mul"), op(op(y(), n(p.start().y()), "sub"), n(dy), "mul"), "add"), n(dx * dx + dy * dy), "div"); }
        var p = (Paint.Radial) paint; Expr dx = op(x(), n(p.focus().x()), "sub"), dy = op(y(), n(p.focus().y()), "sub"); double cx = p.center().x() - p.focus().x(), cy = p.center().y() - p.focus().y(), a = p.radius() * p.radius() - cx * cx - cy * cy;
        Expr dot = op(op(dx, n(cx), "mul"), op(dy, n(cy), "mul"), "add"); Expr discriminant = op(sq(dot), op(n(a), op(sq(dx), sq(dy), "add"), "mul"), "add");
        Expr root = depth -> discriminant.code(depth) + "dup 0 lt { pop 0 } if sqrt "; return op(op(root, dot, "sub"), n(a), "div");
    }
    private static String spread(Paint paint) { var spread = paint instanceof Paint.Linear p ? p.spread() : ((Paint.Radial) paint).spread(); return switch (spread) {
        case PAD -> "dup 0 lt { pop 0 } if dup 1 gt { pop 1 } if ";
        case REPEAT -> "dup floor sub ";
        case REFLECT -> "dup 2 div floor 2 mul sub dup 1 gt { 2 exch sub } if ";
    }; }
    private static String interpolation(List<Paint.Stop> stops, boolean alpha, int channels) {
        var result = new StringBuilder();
        for (int i = 0; i + 1 < stops.size(); i++) {
            var a = stops.get(i); var b = stops.get(i + 1); if (b.offset() == a.offset()) { continue; }
            result.append("dup ").append(number(b.offset())).append(" lt { ");
            result.append(number(a.offset())).append(" sub ").append(number(b.offset() - a.offset())).append(" div ");
            float[] ca = alpha ? new float[] {f(a.opacity())} : components(a.color()), cb = alpha ? new float[] {f(b.opacity())} : components(b.color());
            for (int c = 0; c < channels; c++) { result.append(c).append(" index ").append(number(cb[c] - ca[c])).append(" mul ").append(number(ca[c])).append(" add "); }
            result.append(channels + 1).append(" -1 roll pop } { ");
        }
        result.append("pop "); var last = stops.getLast(); for (float component : alpha ? new float[] {f(last.opacity())} : components(last.color())) { result.append(number(component)).append(' '); }
        for (int i = 0; i + 1 < stops.size(); i++) { if (stops.get(i + 1).offset() != stops.get(i).offset()) { result.append("} ifelse "); } }
        return result.toString();
    }
    private static COSArray array(float... values) { var array = new COSArray(); for (float value : values) { array.add(new COSFloat(value)); } return array; }
    private static String number(double value) { if (!Double.isFinite(value)) { throw new IllegalArgumentException("nonfinite PDF function value"); } return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    private static PDRectangle rect(Rect r) { return new PDRectangle(f(r.x()), f(r.y()), f(r.width()), f(r.height())); }
    private static float f(double value) { float f = (float) value; if (!Float.isFinite(f)) { throw new IllegalArgumentException("PDF paint numeric overflow"); } return f; }
}
