// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math;

import com.mirakyux.export4j.model.ExecutionContext;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.util.List;
import org.scilab.forge.jlatexmath.AccentedAtom;
import org.scilab.forge.jlatexmath.ArrayOfAtoms;
import org.scilab.forge.jlatexmath.Atom;
import org.scilab.forge.jlatexmath.BigOperatorAtom;
import org.scilab.forge.jlatexmath.Box;
import org.scilab.forge.jlatexmath.DefaultTeXFont;
import org.scilab.forge.jlatexmath.FencedAtom;
import org.scilab.forge.jlatexmath.FractionAtom;
import org.scilab.forge.jlatexmath.ItAtom;
import org.scilab.forge.jlatexmath.MatrixAtom;
import org.scilab.forge.jlatexmath.NthRoot;
import org.scilab.forge.jlatexmath.OverlinedAtom;
import org.scilab.forge.jlatexmath.RomanAtom;
import org.scilab.forge.jlatexmath.RowAtom;
import org.scilab.forge.jlatexmath.ScriptsAtom;
import org.scilab.forge.jlatexmath.StyleAtom;
import org.scilab.forge.jlatexmath.SymbolAtom;
import org.scilab.forge.jlatexmath.TeXConstants;
import org.scilab.forge.jlatexmath.TeXEnvironment;
import org.scilab.forge.jlatexmath.TeXFormula;

/** Builds only ancestors of explicit literal-text nodes through public local AST APIs.
 * Unaffected source subtrees still use the upstream parser. The grammar/budgets have already
 * been checked by TexVectorSyntax; there are no new macro names or global registrations here.
 */
final class TexExplicitText {
    private static final FontRenderContext CONTEXT = new FontRenderContext(null, true, true);
    private final String source;
    private final List<Font> fonts;
    private final MathOptions options;
    private int index;

    private TexExplicitText(String source, List<Font> fonts, MathOptions options) {
        this.source = source; this.fonts = fonts; this.options = options;
    }

    static TeXFormula formula(String source, MathTextFonts fonts, MathOptions options) {
        if (fonts.isEmpty() || !source.contains("\\text")) { return new TeXFormula(source); }
        var physicalFonts = fonts.parse();
        if (physicalFonts.isEmpty()) { return new TeXFormula(source); }
        var parser = new TexExplicitText(source, physicalFonts, options);
        Part result = parser.sequence(Stop.END);
        var formula = new TeXFormula();
        formula.root = parser.atom(result);
        return formula;
    }

    private enum Stop { END, GROUP, DEGREE, RIGHT, CELL }
    private record Part(int start, int end, Atom explicit) { }

    private Part sequence(Stop stop) {
        int start = index, normalStart = index;
        RowAtom row = null;
        while (true) {
            ExecutionContext.checkpoint();
            space();
            if (end(stop)) { break; }
            Part next = scripted();
            if (next.explicit() != null) {
                if (row == null) { row = new RowAtom(null); }
                appendNormal(row, normalStart, next.start());
                row.add(next.explicit());
                normalStart = index;
            }
        }
        if (row != null) { appendNormal(row, normalStart, index); }
        return new Part(start, index, row);
    }

    private boolean end(Stop stop) {
        if (index == source.length()) { return true; }
        return switch (stop) {
            case END -> false;
            case GROUP -> source.charAt(index) == '}';
            case DEGREE -> source.charAt(index) == ']';
            case RIGHT -> commandAt("right");
            case CELL -> source.charAt(index) == '&' || commandAt("end") || source.startsWith("\\\\", index);
        };
    }

    private Part scripted() {
        Part base = primary();
        Part sub = null, sup = null;
        space();
        while (index < source.length() && (source.charAt(index) == '^' || source.charAt(index) == '_')) {
            char kind = source.charAt(index++);
            space();
            Part script = primary();
            if (kind == '^') { sup = script; } else { sub = script; }
            space();
        }
        boolean explicit = base.explicit() != null || sub != null && sub.explicit() != null
                || sup != null && sup.explicit() != null;
        if (!explicit || sub == null && sup == null) { return new Part(base.start(), index, base.explicit()); }
        Atom value = atom(base), lower = sub == null ? null : atom(sub), upper = sup == null ? null : atom(sup);
        Atom result = value.getRightType() == TeXConstants.TYPE_BIG_OPERATOR
                ? new BigOperatorAtom(value, lower, upper) : new ScriptsAtom(value, lower, upper);
        return new Part(base.start(), index, result);
    }

    private Part primary() {
        ExecutionContext.checkpoint();
        space();
        int start = index;
        char value = source.charAt(index++);
        if (value == '{') {
            Part result = sequence(Stop.GROUP); index++;
            return new Part(start, index, result.explicit());
        }
        if (value != '\\') { return new Part(start, index, null); }
        String command = command();
        Atom explicit = switch (command) {
            case "text", "textrm" -> text();
            case "frac", "dfrac", "tfrac" -> fraction(command);
            case "sqrt" -> root();
            case "left" -> fence();
            case "begin" -> matrix();
            case "mathrm", "mathit", "bar", "overline" -> wrapper(command);
            default -> null;
        };
        return new Part(start, index, explicit);
    }

    private Part group() { space(); return primary(); }

    private Atom text() {
        space(); index++; // The gate has verified one literal braced text argument.
        int start = index;
        while (source.charAt(index) != '}') { ExecutionContext.checkpoint(); index++; }
        String value = source.substring(start, index++);
        return new OutlineAtom(value, fonts, options.maxSegments());
    }

    private Atom fraction(String command) {
        Part numerator = group(), denominator = group();
        if (numerator.explicit() == null && denominator.explicit() == null) { return null; }
        Atom result = new FractionAtom(atom(numerator), atom(denominator));
        return switch (command) {
            case "dfrac" -> new StyleAtom(TeXConstants.STYLE_DISPLAY, result);
            case "tfrac" -> new StyleAtom(TeXConstants.STYLE_TEXT, result);
            default -> result;
        };
    }

    private Atom root() {
        space();
        Part degree = null;
        if (index < source.length() && source.charAt(index) == '[') {
            index++; degree = sequence(Stop.DEGREE); index++;
        }
        Part radicand = group();
        if (radicand.explicit() == null && (degree == null || degree.explicit() == null)) { return null; }
        return new NthRoot(atom(radicand), degree == null ? null : atom(degree));
    }

    private Atom wrapper(String command) {
        Part argument = group();
        if (argument.explicit() == null) { return null; }
        return switch (command) {
            case "mathrm" -> new RomanAtom(atom(argument));
            case "mathit" -> new ItAtom(atom(argument));
            case "bar" -> new AccentedAtom(atom(argument), "bar");
            case "overline" -> new OverlinedAtom(atom(argument));
            default -> throw new AssertionError(command);
        };
    }

    private Atom fence() {
        SymbolAtom left = delimiter();
        Part inside = sequence(Stop.RIGHT);
        index++; command();
        SymbolAtom right = delimiter();
        return inside.explicit() == null ? null : new FencedAtom(atom(inside), left, right);
    }

    private SymbolAtom delimiter() {
        space();
        char value = source.charAt(index++);
        String name = value == '\\' ? command() : String.valueOf(value);
        return switch (name) {
            case "." -> null;
            case "(" -> SymbolAtom.get("lbrack");
            case ")" -> SymbolAtom.get("rbrack");
            case "[" -> SymbolAtom.get("lsqbrack");
            case "]" -> SymbolAtom.get("rsqbrack");
            case "{" -> SymbolAtom.get("lbrace");
            case "}" -> SymbolAtom.get("rbrace");
            case "|" -> SymbolAtom.get("vert");
            default -> SymbolAtom.get(name);
        };
    }

    private Atom matrix() {
        space(); index++;
        int start = index;
        while (source.charAt(index) != '}') { index++; }
        String name = source.substring(start, index++);
        var array = new ArrayOfAtoms();
        boolean explicit = false;
        while (true) {
            Part cell = sequence(Stop.CELL);
            explicit |= cell.explicit() != null;
            array.root = atom(cell);
            if (source.charAt(index) == '&') { index++; array.addCol(); continue; }
            if (source.startsWith("\\\\", index)) { index += 2; array.addRow(); continue; }
            array.addRow();
            index++; command(); space(); index++;
            while (source.charAt(index) != '}') { index++; }
            index++;
            break;
        }
        if (!explicit) { return null; }
        array.checkDimensions();
        Atom result = name.equals("cases") ? new MatrixAtom(array, "l@{\\!}l")
                : new MatrixAtom(array, MatrixAtom.MATRIX);
        return switch (name) {
            case "pmatrix" -> new FencedAtom(result, SymbolAtom.get("lbrack"), SymbolAtom.get("rbrack"));
            case "bmatrix" -> new FencedAtom(result, SymbolAtom.get("lsqbrack"), SymbolAtom.get("rsqbrack"));
            case "cases" -> new FencedAtom(result, SymbolAtom.get("lbrace"), null);
            default -> result;
        };
    }

    private Atom atom(Part part) {
        return part.explicit() != null ? part.explicit() : new TeXFormula(source.substring(part.start(), part.end())).root;
    }

    private void appendNormal(RowAtom row, int start, int end) {
        if (start < end && !source.substring(start, end).isBlank()) {
            row.add(new TeXFormula(source.substring(start, end)).root);
        }
    }

    private String command() {
        int start = index;
        while (index < source.length() && (source.charAt(index) >= 'a' && source.charAt(index) <= 'z'
                || source.charAt(index) >= 'A' && source.charAt(index) <= 'Z')) { index++; }
        if (start == index) { index++; }
        return source.substring(start, index);
    }

    private boolean commandAt(String command) {
        String token = "\\" + command;
        return source.startsWith(token, index) && (index + token.length() == source.length()
                || !Character.isLetter(source.charAt(index + token.length())));
    }

    private void space() { while (index < source.length() && " \t\r\n".indexOf(source.charAt(index)) >= 0) { index++; } }

    private static final class OutlineAtom extends Atom {
        private final Shape outline;
        private final float advance;

        private OutlineAtom(String text, List<Font> fonts, int maxSegments) {
            var path = new Path2D.Double();
            float x = 0;
            int segments = 0;
            char[] characters = text.toCharArray();
            for (int start = 0; start < text.length();) {
                ExecutionContext.checkpoint();
                Font selected = select(fonts, text.charAt(start));
                int end = start + 1;
                while (end < text.length() && select(fonts, text.charAt(end)) == selected) { end++; }
                var glyphs = selected.layoutGlyphVector(CONTEXT, characters, start, end, Font.LAYOUT_LEFT_TO_RIGHT);
                var placement = AffineTransform.getTranslateInstance(x, 0);
                placement.scale(0.001, 0.001);
                for (int glyph = 0; glyph < glyphs.getNumGlyphs(); glyph++) {
                    ExecutionContext.checkpoint();
                    Shape shape = placement.createTransformedShape(glyphs.getGlyphOutline(glyph));
                    for (var iterator = shape.getPathIterator(null); !iterator.isDone(); iterator.next()) {
                        ExecutionContext.checkpoint();
                        if (++segments > maxSegments) { throw new MathException(MathException.Reason.LIMIT_EXCEEDED); }
                    }
                    path.append(shape, false);
                }
                x += (float) glyphs.getGlyphPosition(glyphs.getNumGlyphs()).getX() * 0.001f;
                start = end;
            }
            outline = path; advance = x;
        }

        private static Font select(List<Font> fonts, char value) {
            for (Font font : fonts) { if (font.canDisplay(value)) { return font; } }
            throw new MathException(MathException.Reason.UNSUPPORTED_SYNTAX);
        }

        @Override public Box createBox(TeXEnvironment environment) {
            ExecutionContext.checkpoint();
            double scale = DefaultTeXFont.getSizeFactor(environment.getStyle()) * environment.getTeXFont().getScaleFactor();
            return new OutlineBox(AffineTransform.getScaleInstance(scale, scale).createTransformedShape(outline),
                    (float) (advance * scale));
        }
    }

    private static final class OutlineBox extends Box {
        private final Shape outline;

        private OutlineBox(Shape outline, float advance) {
            this.outline = outline;
            var bounds = outline.getBounds2D();
            width = Math.max(advance, (float) bounds.getMaxX());
            height = Math.max(0, (float) -bounds.getMinY());
            depth = Math.max(0, (float) bounds.getMaxY());
        }

        @Override public void draw(Graphics2D graphics, float x, float y) {
            ExecutionContext.checkpoint();
            graphics.fill(AffineTransform.getTranslateInstance(x, y).createTransformedShape(outline));
        }

        @Override public int getLastFontId() { return 0; }
    }
}
