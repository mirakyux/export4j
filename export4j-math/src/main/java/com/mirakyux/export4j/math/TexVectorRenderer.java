// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.InlineVector;
import com.mirakyux.export4j.model.VectorResource;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.Paint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ImageObserver;
import java.awt.image.RenderedImage;
import java.awt.image.renderable.RenderableImage;
import java.io.IOException;
import java.text.AttributedCharacterIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.scilab.forge.jlatexmath.TeXConstants;
import org.scilab.forge.jlatexmath.TeXFormula;
import org.scilab.forge.jlatexmath.TeXIcon;

/** The optional Java2D boundary. Only fixed bundled fonts and black filled paths leave it. */
final class TexVectorRenderer {
    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final List<String> FONT_RESOURCES = List.of(
            "base/jlm_cmmi10.ttf", "base/jlm_cmex10.ttf",
            "latin/jlm_cmr10.ttf", "latin/optional/jlm_cmti10.ttf", "maths/jlm_cmsy10.ttf");
    private static Map<String, Font> embeddedFonts;

    private TexVectorRenderer() { }

    static InlineVector prepare(String tex, MathOptions options) {
        return prepare(tex, options, MathTextFonts.EMPTY);
    }

    static InlineVector prepare(String tex, MathOptions options, MathTextFonts textFonts) {
        java.util.Objects.requireNonNull(textFonts, "textFonts");
        TexVectorSyntax.validate(tex, options, !textFonts.isEmpty());
        acquire();
        try {
            ExecutionContext.checkpoint();
            // Applications sharing this dependency must not mutate JLaTeXMath's global settings.
            // Reject known overrides; this lock cannot serialize unrelated direct library callers.
            if (TeXIcon.defaultSize != -1 || TeXIcon.magFactor != 0 || TeXFormula.PIXELS_PER_POINT != 1
                    || TeXFormula.externalFontMap.containsKey(Character.UnicodeBlock.BASIC_LATIN)) {
                throw new MathException(MathException.Reason.RENDERING_FAILED);
            }
            var icon = TexExplicitText.formula(tex, textFonts, options).createTeXIcon(options.display()
                    ? TeXConstants.STYLE_DISPLAY : TeXConstants.STYLE_TEXT, (float) options.fontSize());
            ExecutionContext.checkpoint();
            icon.setForeground(Color.BLACK);
            int width = icon.getIconWidth(), height = icon.getIconHeight();
            if (width <= 0 || height <= 0) { throw new MathException(MathException.Reason.INVALID_SYNTAX); }
            if (width > options.maxExtent() || height > options.maxExtent()) { throw limit(); }
            var fonts = fonts();
            var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            var geometry = new Geometry(options);
            var capture = new Capture(image.createGraphics(), fonts, geometry);
            try {
                icon.paintIcon(null, capture, 0, 0);
                ExecutionContext.checkpoint();
                var bounds = controlBounds(geometry.ink);
                double x = Math.min(0, bounds.getMinX()), y = Math.min(0, bounds.getMinY());
                double fullWidth = Math.max(width, bounds.getMaxX()) - x;
                double fullHeight = Math.max(height, bounds.getMaxY()) - y;
                if (fullWidth > options.maxExtent() || fullHeight > options.maxExtent()) { throw limit(); }
                double baseline = icon.getInsets().top + icon.getTrueIconHeight() - icon.getTrueIconDepth() - y;
                if (!Double.isFinite(baseline) || baseline < 0 || baseline > fullHeight || geometry.ink.isEmpty()) {
                    throw new MathException(MathException.Reason.INVALID_SYNTAX);
                }
                geometry.ink.transform(AffineTransform.getTranslateInstance(-x, -y - baseline));
                var vector = VectorResource.of(fullWidth, baseline, fullHeight - baseline,
                        segments(geometry.ink, options.maxSegments()));
                String logicalText = tex.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
                return new InlineVector(vector, logicalText);
            } finally {
                capture.dispose();
                image.flush();
            }
        } catch (MathException | ExecutionException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new MathException(MathException.Reason.RENDERING_FAILED, failure);
        } finally {
            LOCK.unlock();
        }
    }

    private static void acquire() {
        while (true) {
            ExecutionContext.checkpoint();
            try {
                if (LOCK.tryLock(10, TimeUnit.MILLISECONDS)) { return; }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                ExecutionContext.checkpoint();
                throw new MathException(MathException.Reason.RENDERING_FAILED, failure);
            }
        }
    }

    private static Map<String, Font> fonts() {
        if (embeddedFonts == null) {
            var result = new HashMap<String, Font>();
            for (String resource : FONT_RESOURCES) {
                ExecutionContext.checkpoint();
                try (var input = TeXFormula.class.getResourceAsStream("fonts/" + resource)) {
                    if (input == null) { throw new MathException(MathException.Reason.RENDERING_FAILED); }
                    Font font = Font.createFont(Font.TRUETYPE_FONT, input);
                    result.put(font.getPSName(), font);
                } catch (IOException | FontFormatException failure) {
                    throw new MathException(MathException.Reason.RENDERING_FAILED, failure);
                }
            }
            embeddedFonts = Map.copyOf(result);
        }
        return embeddedFonts;
    }

    /** Converts quadratic controls to cubic controls without flattening or changing quality. */
    private static List<VectorResource.Segment> segments(Shape shape, int maximum) {
        var result = new ArrayList<VectorResource.Segment>();
        var points = new double[6];
        double x = 0, y = 0;
        for (var iterator = shape.getPathIterator(null); !iterator.isDone(); iterator.next()) {
            ExecutionContext.checkpoint();
            if (result.size() == maximum) { throw limit(); }
            switch (iterator.currentSegment(points)) {
                case PathIterator.SEG_MOVETO -> {
                    result.add(segment(VectorResource.Operation.MOVE, points[0], points[1])); x = points[0]; y = points[1];
                }
                case PathIterator.SEG_LINETO -> {
                    result.add(segment(VectorResource.Operation.LINE, points[0], points[1])); x = points[0]; y = points[1];
                }
                case PathIterator.SEG_CUBICTO -> {
                    result.add(segment(VectorResource.Operation.CURVE, points[0], points[1], points[2], points[3], points[4], points[5]));
                    x = points[4]; y = points[5];
                }
                case PathIterator.SEG_QUADTO -> {
                    result.add(segment(VectorResource.Operation.CURVE,
                            x + (points[0] - x) * 2 / 3, y + (points[1] - y) * 2 / 3,
                            points[2] + (points[0] - points[2]) * 2 / 3,
                            points[3] + (points[1] - points[3]) * 2 / 3, points[2], points[3]));
                    x = points[2]; y = points[3];
                }
                case PathIterator.SEG_CLOSE -> result.add(segment(VectorResource.Operation.CLOSE));
                default -> throw new MathException(MathException.Reason.RENDERING_FAILED);
            }
        }
        return result;
    }

    private static VectorResource.Segment segment(VectorResource.Operation operation, double... points) {
        return new VectorResource.Segment(operation, Arrays.stream(points).boxed().toList());
    }

    /** The core reservation contains every Bezier control, not just the curve's extrema. */
    private static Rectangle2D controlBounds(Shape shape) {
        var bounds = new Rectangle2D.Double();
        var points = new double[6];
        for (var iterator = shape.getPathIterator(null); !iterator.isDone(); iterator.next()) {
            ExecutionContext.checkpoint();
            int count = coordinateCount(iterator.currentSegment(points));
            for (int index = 0; index < count; index += 2) { bounds.add(points[index], points[index + 1]); }
        }
        return bounds;
    }

    private static int coordinateCount(int kind) {
        return switch (kind) {
            case PathIterator.SEG_MOVETO, PathIterator.SEG_LINETO -> 2;
            case PathIterator.SEG_QUADTO -> 4;
            case PathIterator.SEG_CUBICTO -> 6;
            case PathIterator.SEG_CLOSE -> 0;
            default -> throw new MathException(MathException.Reason.RENDERING_FAILED);
        };
    }

    private static MathException limit() { return new MathException(MathException.Reason.LIMIT_EXCEEDED); }

    private static final class Geometry {
        private final MathOptions options;
        private final Area ink = new Area();
        private int paths;
        private int capturedSegments;

        private Geometry(MathOptions options) { this.options = options; }

        private void add(Shape shape) {
            ExecutionContext.checkpoint();
            if (++paths > options.maxPaths()) { throw limit(); }
            var points = new double[6];
            for (var iterator = shape.getPathIterator(null); !iterator.isDone(); iterator.next()) {
                ExecutionContext.checkpoint();
                if (++capturedSegments > options.maxSegments()) { throw limit(); }
                int count = coordinateCount(iterator.currentSegment(points));
                for (int index = 0; index < count; index++) {
                    if (!Double.isFinite(points[index]) || Math.abs(points[index]) > options.maxExtent() * 2) { throw limit(); }
                }
            }
            // Each black paint is a union. Simply concatenating oppositely oriented paths can
            // erase overlapping ink under the model's nonzero winding rule.
            ink.add(new Area(shape));
            int unionSegments = 0;
            for (var iterator = ink.getPathIterator(null); !iterator.isDone(); iterator.next()) {
                ExecutionContext.checkpoint();
                if (++unionSegments > options.maxSegments()) { throw limit(); }
            }
        }
    }

    /** A rejecting Java2D adapter: only font outlines and solid black paths reach the Display List. */
    private static final class Capture extends Graphics2D {
        private final Graphics2D delegate;
        private final Map<String, Font> fonts;
        private final Geometry geometry;

        private Capture(Graphics2D delegate, Map<String, Font> fonts, Geometry geometry) {
            this.delegate = delegate; this.fonts = fonts; this.geometry = geometry;
        }

        private void capture(Shape shape) {
            if (!Color.BLACK.equals(delegate.getPaint()) || delegate.getClip() != null
                    || !(delegate.getComposite() instanceof AlphaComposite alpha)
                    || alpha.getRule() != AlphaComposite.SRC_OVER || alpha.getAlpha() != 1) {
                throw unsupported();
            }
            Shape transformed = delegate.getTransform().createTransformedShape(shape);
            if (transformed.getPathIterator(null).getWindingRule() != PathIterator.WIND_NON_ZERO) {
                transformed = new Area(transformed);
            }
            geometry.add(transformed);
        }

        private Font explicitFont() {
            Font requested = delegate.getFont();
            Font embedded = fonts.get(requested.getPSName());
            if (embedded == null || requested.getStyle() != Font.PLAIN) {
                throw new MathException(MathException.Reason.UNSUPPORTED_SYNTAX);
            }
            return embedded.deriveFont(requested.getSize2D());
        }

        private static MathException unsupported() {
            return new MathException(MathException.Reason.UNSUPPORTED_SYNTAX);
        }

        @Override public void drawChars(char[] value, int offset, int length, int x, int y) {
            String text = new String(value, offset, length);
            Font font = explicitFont();
            if (font.canDisplayUpTo(text) >= 0) { throw new MathException(MathException.Reason.UNSUPPORTED_SYNTAX); }
            capture(font.createGlyphVector(delegate.getFontRenderContext(), text).getOutline(x, y));
        }
        @Override public void drawString(String value, float x, float y) {
            Font font = explicitFont();
            if (font.canDisplayUpTo(value) >= 0) { throw new MathException(MathException.Reason.UNSUPPORTED_SYNTAX); }
            capture(font.createGlyphVector(delegate.getFontRenderContext(), value).getOutline(x, y));
        }
        @Override public void drawString(String value, int x, int y) { drawString(value, (float) x, (float) y); }
        @Override public void fill(Shape shape) { capture(shape); }
        @Override public void draw(Shape shape) { capture(delegate.getStroke().createStrokedShape(shape)); }
        @Override public Graphics create() { return new Capture((Graphics2D) delegate.create(), fonts, geometry); }
        @Override public void dispose() { delegate.dispose(); }
        @Override public void translate(int x, int y) { delegate.translate(x, y); }
        @Override public void translate(double x, double y) { delegate.translate(x, y); }
        @Override public void rotate(double angle) { delegate.rotate(angle); }
        @Override public void rotate(double angle, double x, double y) { delegate.rotate(angle, x, y); }
        @Override public void scale(double x, double y) { delegate.scale(x, y); }
        @Override public void shear(double x, double y) { delegate.shear(x, y); }
        @Override public void transform(AffineTransform transform) { delegate.transform(transform); }
        @Override public void setTransform(AffineTransform transform) { delegate.setTransform(transform); }
        @Override public AffineTransform getTransform() { return delegate.getTransform(); }
        @Override public Paint getPaint() { return delegate.getPaint(); }
        @Override public Composite getComposite() { return delegate.getComposite(); }
        @Override public void setComposite(Composite composite) { delegate.setComposite(composite); }
        @Override public void setPaint(Paint paint) { delegate.setPaint(paint); }
        @Override public void setStroke(Stroke stroke) { delegate.setStroke(stroke); }
        @Override public Stroke getStroke() { return delegate.getStroke(); }
        @Override public void setRenderingHint(RenderingHints.Key key, Object value) { delegate.setRenderingHint(key, value); }
        @Override public Object getRenderingHint(RenderingHints.Key key) { return delegate.getRenderingHint(key); }
        @Override public void setRenderingHints(Map<?, ?> hints) { delegate.setRenderingHints(hints); }
        @Override public void addRenderingHints(Map<?, ?> hints) { delegate.addRenderingHints(hints); }
        @Override public RenderingHints getRenderingHints() { return delegate.getRenderingHints(); }
        @Override public void setBackground(Color color) { delegate.setBackground(color); }
        @Override public Color getBackground() { return delegate.getBackground(); }
        @Override public void clip(Shape shape) { delegate.clip(shape); }
        @Override public FontRenderContext getFontRenderContext() { return delegate.getFontRenderContext(); }
        @Override public GraphicsConfiguration getDeviceConfiguration() { return delegate.getDeviceConfiguration(); }
        @Override public boolean hit(Rectangle rectangle, Shape shape, boolean stroke) { return delegate.hit(rectangle, shape, stroke); }
        @Override public Color getColor() { return delegate.getColor(); }
        @Override public void setColor(Color color) { delegate.setColor(color); }
        @Override public void setPaintMode() { delegate.setPaintMode(); }
        @Override public Font getFont() { return delegate.getFont(); }
        @Override public void setFont(Font font) { delegate.setFont(font); }
        @Override public FontMetrics getFontMetrics(Font font) { return delegate.getFontMetrics(font); }
        @Override public Rectangle getClipBounds() { return delegate.getClipBounds(); }
        @Override public void clipRect(int x, int y, int width, int height) { delegate.clipRect(x, y, width, height); }
        @Override public void setClip(int x, int y, int width, int height) { delegate.setClip(x, y, width, height); }
        @Override public Shape getClip() { return delegate.getClip(); }
        @Override public void setClip(Shape shape) { delegate.setClip(shape); }
        @Override public void drawLine(int x, int y, int endX, int endY) { draw(new java.awt.geom.Line2D.Double(x, y, endX, endY)); }
        @Override public void fillRect(int x, int y, int width, int height) { fill(new Rectangle2D.Double(x, y, width, height)); }
        @Override public void drawString(AttributedCharacterIterator value, int x, int y) { throw unsupported(); }
        @Override public void drawString(AttributedCharacterIterator value, float x, float y) { throw unsupported(); }
        @Override public void drawGlyphVector(GlyphVector vector, float x, float y) { throw unsupported(); }
        @Override public void setXORMode(Color color) { throw unsupported(); }
        @Override public void copyArea(int x, int y, int width, int height, int dx, int dy) { throw unsupported(); }
        @Override public void clearRect(int x, int y, int width, int height) { throw unsupported(); }
        @Override public void drawRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) { throw unsupported(); }
        @Override public void fillRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) { throw unsupported(); }
        @Override public void drawOval(int x, int y, int width, int height) { throw unsupported(); }
        @Override public void fillOval(int x, int y, int width, int height) { throw unsupported(); }
        @Override public void drawArc(int x, int y, int width, int height, int start, int arc) { throw unsupported(); }
        @Override public void fillArc(int x, int y, int width, int height, int start, int arc) { throw unsupported(); }
        @Override public void drawPolyline(int[] x, int[] y, int count) { throw unsupported(); }
        @Override public void drawPolygon(int[] x, int[] y, int count) { throw unsupported(); }
        @Override public void fillPolygon(int[] x, int[] y, int count) { throw unsupported(); }
        @Override public boolean drawImage(Image image, AffineTransform transform, ImageObserver observer) { throw unsupported(); }
        @Override public void drawImage(BufferedImage image, BufferedImageOp operation, int x, int y) { throw unsupported(); }
        @Override public void drawRenderedImage(RenderedImage image, AffineTransform transform) { throw unsupported(); }
        @Override public void drawRenderableImage(RenderableImage image, AffineTransform transform) { throw unsupported(); }
        @Override public boolean drawImage(Image image, int x, int y, ImageObserver observer) { throw unsupported(); }
        @Override public boolean drawImage(Image image, int x, int y, int width, int height, ImageObserver observer) { throw unsupported(); }
        @Override public boolean drawImage(Image image, int x, int y, Color color, ImageObserver observer) { throw unsupported(); }
        @Override public boolean drawImage(Image image, int x, int y, int width, int height, Color color, ImageObserver observer) { throw unsupported(); }
        @Override public boolean drawImage(Image image, int x, int y, int endX, int endY, int sourceX, int sourceY, int sourceEndX, int sourceEndY, ImageObserver observer) { throw unsupported(); }
        @Override public boolean drawImage(Image image, int x, int y, int endX, int endY, int sourceX, int sourceY, int sourceEndX, int sourceEndY, Color color, ImageObserver observer) { throw unsupported(); }
    }
}
