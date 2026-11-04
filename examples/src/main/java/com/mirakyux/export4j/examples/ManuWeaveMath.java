// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.model.ExecutionContext;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.scilab.forge.jlatexmath.TeXConstants;
import org.scilab.forge.jlatexmath.TeXFormula;
import org.scilab.forge.jlatexmath.TeXIcon;

/**
 * Example-only, offline display mathematics. A small ASCII TeX subset is converted once into
 * immutable outlines using the dependency's explicit Computer Modern fonts. Original TeX is
 * retained as OutlineText logical text; this is neither MathML nor editable Word mathematics.
 * Parsing and library font caches are confined to a synchronized scope. The subset and budgets
 * are intended for these self-authored examples, not as a general hostile-TeX sandbox.
 */
final class ManuWeaveMath {
    private static final int MAX_SOURCE = 2048;
    private static final int MAX_COMMANDS = 256;
    private static final int MAX_DEPTH = 16;
    private static final int MAX_CELLS = 64;
    private static final double MAX_EXTENT = 1024;
    private static final GeometryBudget GEOMETRY = new GeometryBudget(2048, 40_000);
    private static final Set<String> COMMANDS = Set.of(
            "frac", "sqrt", "sum", "prod", "int", "iint", "oint", "infty", "pm", "mp",
            "quad", "qquad", "left", "right", "sin", "cos", "tan", "log", "ln", "exp",
            "lim", "to", "cdot", "times", "div", "leq", "geq", "neq", "approx", "equiv",
            "alpha", "beta", "gamma", "delta", "epsilon", "theta", "lambda", "mu", "pi",
            "rho", "sigma", "tau", "phi", "omega", "Gamma", "Delta", "Theta", "Lambda",
            "Pi", "Sigma", "Phi", "Omega");
    private static final Set<String> ENVIRONMENTS = Set.of("matrix", "pmatrix", "bmatrix", "cases");
    private static final Set<Character> ESCAPES = Set.of('\\', ',', ';', '!', ' ', '{', '}', '|');
    private static final String LITERALS = " +-*/=.,:;|<>!()[]{}_^&'";
    private static final List<String> FONT_RESOURCES = List.of(
            "base/jlm_cmmi10.ttf", "base/jlm_cmex10.ttf",
            "latin/jlm_cmr10.ttf", "maths/jlm_cmsy10.ttf");

    private ManuWeaveMath() { }

    /** Atomic, natural-size display formula in points; it never shrinks or wraps to fit. */
    static Component equation(String tex, double size) {
        return prepare(tex, size, GEOMETRY);
    }

    static Formula prepare(String tex, double size) {
        return prepare(tex, size, GEOMETRY);
    }

    static Formula prepare(String tex, double size, GeometryBudget budget) {
        validate(tex);
        Objects.requireNonNull(budget, "budget");
        if (!Double.isFinite(size) || size < 6 || size > 72) {
            throw new IllegalArgumentException("formula size must be in [6,72] pt");
        }
        ExecutionContext.checkpoint();
        synchronized (ManuWeaveMath.class) {
            ExecutionContext.checkpoint();
            if (TeXIcon.defaultSize != -1 || TeXIcon.magFactor != 0) {
                throw new IllegalStateException("global TeX icon size overrides are unsupported");
            }
            var icon = new TeXFormula(tex).createTeXIcon(TeXConstants.STYLE_DISPLAY, (float) size);
            ExecutionContext.checkpoint();
            icon.setForeground(Color.BLACK);
            int width = icon.getIconWidth();
            int height = icon.getIconHeight();
            if (width <= 0 || height <= 0 || width > MAX_EXTENT || height > MAX_EXTENT) {
                throw new IllegalArgumentException("formula dimensions exceed 1024 pt budget");
            }
            var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            var capture = new Capture(image.createGraphics(), EmbeddedFonts.FONTS, new Geometry(budget));
            try {
                icon.paintIcon(null, capture, 0, 0);
                var bounds = capture.geometry.bounds;
                double x = Math.min(0, bounds.getMinX());
                double y = Math.min(0, bounds.getMinY());
                double fullWidth = Math.max(width, bounds.getMaxX()) - x;
                double fullHeight = Math.max(height, bounds.getMaxY()) - y;
                if (fullWidth > MAX_EXTENT || fullHeight > MAX_EXTENT) {
                    throw new IllegalArgumentException("formula dimensions exceed 1024 pt budget");
                }
                double baseline = icon.getInsets().top + icon.getTrueIconHeight() - icon.getTrueIconDepth() - y;
                return new Formula(tex, fullWidth, fullHeight, baseline, -x, -y, capture.geometry.paths);
            } finally {
                capture.dispose();
                image.flush();
            }
        }
    }

    private static void validate(String tex) {
        Objects.requireNonNull(tex, "tex");
        if (tex.isBlank() || tex.length() > MAX_SOURCE) {
            throw new IllegalArgumentException("formula source must contain 1..2048 UTF-16 units");
        }
        int depth = 0;
        int leftDepth = 0;
        int commands = 0;
        int cells = 0;
        var environments = new ArrayDeque<String>();
        for (int index = 0; index < tex.length(); index++) {
            ExecutionContext.checkpoint();
            char current = tex.charAt(index);
            if (current == '\\') {
                if (++index == tex.length()) { throw invalid(); }
                int start = index;
                while (index < tex.length() && asciiLetter(tex.charAt(index))) { index++; }
                if (++commands > MAX_COMMANDS) { throw new IllegalArgumentException("formula command budget exceeded"); }
                if (start == index) {
                    char escape = tex.charAt(index);
                    if (!ESCAPES.contains(escape)) { throw invalid(); }
                    if (escape == '\\' && (environments.isEmpty() || ++cells > MAX_CELLS)) {
                        throw new IllegalArgumentException("formula matrix cell budget exceeded");
                    }
                    continue;
                }
                String command = tex.substring(start, index);
                if (command.equals("begin") || command.equals("end")) {
                    int close = tex.indexOf('}', index);
                    if (index >= tex.length() || tex.charAt(index) != '{' || close < 0) { throw invalid(); }
                    String environment = tex.substring(index + 1, close);
                    if (!ENVIRONMENTS.contains(environment)) { throw invalid(); }
                    if (command.equals("begin")) {
                        if (environments.size() >= 4 || ++cells > MAX_CELLS) { throw invalid(); }
                        environments.push(environment);
                    } else if (environments.isEmpty() || !environments.pop().equals(environment)) {
                        throw invalid();
                    }
                    index = close;
                    continue;
                }
                if (!COMMANDS.contains(command)) { throw invalid(); }
                if (command.equals("left") && ++leftDepth > MAX_DEPTH) { throw invalid(); }
                if (command.equals("right") && --leftDepth < 0) { throw invalid(); }
                if (command.equals("frac") || command.equals("sqrt")) {
                    int argument = index;
                    while (argument < tex.length() && tex.charAt(argument) == ' ') { argument++; }
                    // Braced arguments keep parser recursion bounded by the validated group depth.
                    if (argument == tex.length() || tex.charAt(argument) != '{') { throw invalid(); }
                    if (command.equals("frac")) {
                        int second = groupEnd(tex, argument) + 1;
                        while (second < tex.length() && tex.charAt(second) == ' ') { second++; }
                        if (second == tex.length() || tex.charAt(second) != '{') { throw invalid(); }
                    }
                }
                index--;
                continue;
            }
            if (!asciiLetter(current) && !(current >= '0' && current <= '9') && LITERALS.indexOf(current) < 0) {
                throw invalid();
            }
            if (current == '{' && ++depth > MAX_DEPTH || current == '}' && --depth < 0) { throw invalid(); }
            if (current == '&' && (environments.isEmpty() || ++cells > MAX_CELLS)) { throw invalid(); }
            if (current == '^' || current == '_') {
                if (index + 1 == tex.length()) { throw invalid(); }
                char next = tex.charAt(index + 1);
                if (next != '{' && !asciiLetter(next) && !(next >= '0' && next <= '9')) { throw invalid(); }
            }
        }
        if (depth != 0 || leftDepth != 0 || !environments.isEmpty()) { throw invalid(); }
    }

    private static boolean asciiLetter(char value) {
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z';
    }

    private static int groupEnd(String tex, int opening) {
        int depth = 0;
        for (int index = opening; index < tex.length(); index++) {
            char value = tex.charAt(index);
            if (value == '\\') { index++; }
            else if (value == '{') { depth++; }
            else if (value == '}' && --depth == 0) { return index; }
        }
        throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("formula is outside the bounded offline TeX subset");
    }

    record GeometryBudget(int paths, int segments) {
        GeometryBudget {
            if (paths < 1 || paths > GEOMETRY_MAX_PATHS || segments < 1 || segments > GEOMETRY_MAX_SEGMENTS) {
                throw new IllegalArgumentException("invalid formula geometry budget");
            }
        }
        private static final int GEOMETRY_MAX_PATHS = 2048;
        private static final int GEOMETRY_MAX_SEGMENTS = 40_000;
    }

    /** Immutable and reusable; logical TeX survives PDF ActualText and SVG metadata. */
    record Formula(String tex, double width, double height, double baseline, double offsetX, double offsetY,
            List<VectorPath> paths) implements Component {
        Formula { paths = List.copyOf(paths); }

        @Override public ComponentLayout layout(ComponentContext context) {
            ExecutionContext.checkpoint();
            if (width > context.width() || height > context.height()) {
                throw new IllegalArgumentException("atomic formula exceeds component viewport");
            }
            var outline = new DrawCommand.OutlineText(tex, paths, RgbColor.BLACK,
                    new Rect(-offsetX, -offsetY, width, height));
            List<DrawCommand> commands = offsetX == 0 && offsetY == 0 ? List.of(outline) : List.of(
                    new DrawCommand.Save(), new DrawCommand.Transform(new com.mirakyux.export4j.graphics.AffineTransform(
                            1, 0, 0, 1, offsetX, offsetY)), outline, new DrawCommand.Restore());
            return new ComponentLayout(width, height, 1, commands, List.of());
        }
    }

    private static final class EmbeddedFonts {
        private static final Map<String, Font> FONTS = load();

        private static Map<String, Font> load() {
            var result = new HashMap<String, Font>();
            for (String resource : FONT_RESOURCES) {
                try (var input = TeXFormula.class.getResourceAsStream("fonts/" + resource)) {
                    if (input == null) { throw new IllegalStateException("missing embedded math font"); }
                    Font font = Font.createFont(Font.TRUETYPE_FONT, input);
                    result.put(font.getPSName(), font);
                } catch (IOException | FontFormatException exception) {
                    throw new IllegalStateException("cannot load embedded math font", exception);
                }
            }
            return Map.copyOf(result);
        }
    }

    private static final class Geometry {
        private final GeometryBudget budget;
        private final List<VectorPath> paths = new ArrayList<>();
        private Rectangle2D bounds = new Rectangle2D.Double();
        private int segments;

        private Geometry(GeometryBudget budget) { this.budget = budget; }

        private void add(Shape shape) {
            ExecutionContext.checkpoint();
            var path = new ArrayList<VectorPath.Segment>();
            double[] coordinates = new double[6];
            double x = 0;
            double y = 0;
            for (var iterator = shape.getPathIterator(null); !iterator.isDone(); iterator.next()) {
                ExecutionContext.checkpoint();
                if (++segments > budget.segments()) { throw geometryLimit(); }
                switch (iterator.currentSegment(coordinates)) {
                    case PathIterator.SEG_MOVETO -> {
                        x = coordinates[0]; y = coordinates[1];
                        path.add(new VectorPath.MoveTo(new Point(x, y)));
                    }
                    case PathIterator.SEG_LINETO -> {
                        x = coordinates[0]; y = coordinates[1];
                        path.add(new VectorPath.LineTo(new Point(x, y)));
                    }
                    case PathIterator.SEG_QUADTO -> {
                        path.add(new VectorPath.CurveTo(
                                new Point(x + 2 * (coordinates[0] - x) / 3, y + 2 * (coordinates[1] - y) / 3),
                                new Point(coordinates[2] + 2 * (coordinates[0] - coordinates[2]) / 3,
                                        coordinates[3] + 2 * (coordinates[1] - coordinates[3]) / 3),
                                new Point(coordinates[2], coordinates[3])));
                        x = coordinates[2]; y = coordinates[3];
                    }
                    case PathIterator.SEG_CUBICTO -> {
                        path.add(new VectorPath.CurveTo(new Point(coordinates[0], coordinates[1]),
                                new Point(coordinates[2], coordinates[3]), new Point(coordinates[4], coordinates[5])));
                        x = coordinates[4]; y = coordinates[5];
                    }
                    case PathIterator.SEG_CLOSE -> path.add(new VectorPath.Close());
                    default -> throw new IllegalArgumentException("unsupported math path segment");
                }
            }
            if (!path.isEmpty()) {
                if (paths.size() == budget.paths()) { throw geometryLimit(); }
                paths.add(new VectorPath(path));
                bounds = bounds.createUnion(shape.getBounds2D());
            }
        }

        private static IllegalArgumentException geometryLimit() {
            return new IllegalArgumentException("formula geometry budget exceeded");
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
                throw new IllegalArgumentException("formula requests a non-whitelisted font");
            }
            return embedded.deriveFont(requested.getSize2D());
        }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("unsupported math drawing operation");
        }

        @Override public void drawChars(char[] value, int offset, int length, int x, int y) {
            String text = new String(value, offset, length);
            Font font = explicitFont();
            if (font.canDisplayUpTo(text) >= 0) { throw new IllegalArgumentException("missing embedded math glyph"); }
            capture(font.createGlyphVector(delegate.getFontRenderContext(), text).getOutline(x, y));
        }
        @Override public void drawString(String value, float x, float y) {
            Font font = explicitFont();
            if (font.canDisplayUpTo(value) >= 0) { throw new IllegalArgumentException("missing embedded math glyph"); }
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
