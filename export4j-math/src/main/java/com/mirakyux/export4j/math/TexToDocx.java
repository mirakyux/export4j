// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math;

import com.mirakyux.export4j.adapter.docx.DocxMath;
import com.mirakyux.export4j.model.ExecutionContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Offline parser for the explicitly supported semantic TeX subset. No macro expansion occurs. */
final class TexToDocx {
    private static final Map<String, String> LETTERS = Map.ofEntries(
            Map.entry("alpha", "α"), Map.entry("beta", "β"), Map.entry("gamma", "γ"),
            Map.entry("delta", "δ"), Map.entry("epsilon", "ϵ"), Map.entry("varepsilon", "ε"),
            Map.entry("zeta", "ζ"), Map.entry("eta", "η"), Map.entry("theta", "θ"),
            Map.entry("vartheta", "ϑ"), Map.entry("iota", "ι"), Map.entry("kappa", "κ"),
            Map.entry("lambda", "λ"), Map.entry("mu", "μ"), Map.entry("nu", "ν"),
            Map.entry("xi", "ξ"), Map.entry("pi", "π"), Map.entry("varpi", "ϖ"),
            Map.entry("rho", "ρ"), Map.entry("varrho", "ϱ"), Map.entry("sigma", "σ"),
            Map.entry("varsigma", "ς"), Map.entry("tau", "τ"), Map.entry("upsilon", "υ"),
            Map.entry("phi", "ϕ"), Map.entry("varphi", "φ"), Map.entry("chi", "χ"),
            Map.entry("psi", "ψ"), Map.entry("omega", "ω"));
    private static final Map<String, String> SYMBOLS = Map.ofEntries(
            Map.entry("Gamma", "Γ"), Map.entry("Delta", "Δ"), Map.entry("Theta", "Θ"),
            Map.entry("Lambda", "Λ"), Map.entry("Xi", "Ξ"), Map.entry("Pi", "Π"),
            Map.entry("Sigma", "Σ"), Map.entry("Upsilon", "Υ"), Map.entry("Phi", "Φ"),
            Map.entry("Psi", "Ψ"), Map.entry("Omega", "Ω"), Map.entry("pm", "±"),
            Map.entry("mp", "∓"), Map.entry("times", "×"), Map.entry("div", "÷"),
            Map.entry("cdot", "⋅"), Map.entry("le", "≤"), Map.entry("leq", "≤"),
            Map.entry("ge", "≥"), Map.entry("geq", "≥"), Map.entry("ne", "≠"),
            Map.entry("neq", "≠"), Map.entry("approx", "≈"), Map.entry("equiv", "≡"),
            Map.entry("infty", "∞"), Map.entry("partial", "∂"), Map.entry("nabla", "∇"),
            Map.entry("to", "→"), Map.entry("rightarrow", "→"), Map.entry("leftarrow", "←"),
            Map.entry("leftrightarrow", "↔"), Map.entry("in", "∈"), Map.entry("notin", "∉"),
            Map.entry("subset", "⊂"), Map.entry("supset", "⊃"), Map.entry("subseteq", "⊆"),
            Map.entry("supseteq", "⊇"), Map.entry("cup", "∪"), Map.entry("cap", "∩"),
            Map.entry("cdots", "⋯"), Map.entry("ldots", "…"), Map.entry("dots", "…"),
            Map.entry("quad", "\u2003"), Map.entry("qquad", "\u2003\u2003"));
    private static final Set<String> FUNCTIONS = Set.of("sin", "cos", "tan", "log", "ln", "exp");
    private final String source;
    private final MathOptions options;
    private int index;
    private int commands;

    private TexToDocx(String source, MathOptions options) {
        this.source = source;
        this.options = options;
    }

    static DocxMath parse(String tex, MathOptions options) {
        Objects.requireNonNull(tex, "tex");
        Objects.requireNonNull(options, "options");
        ExecutionContext.checkpoint();
        if (tex.length() > options.maxSourceLength()) { throw limit(); }
        if (tex.isBlank()) { throw invalid(); }
        validateSource(tex, options);
        var parser = new TexToDocx(tex, options);
        return parser.sequence('\0', 0, null);
    }

    /** The depth counts every recursive argument, including unbraced command arguments. */
    private DocxMath sequence(char terminator, int depth, DocxMath.Style style) {
        var elements = new ArrayList<DocxMath>();
        while (true) {
            space();
            if (index == source.length()) {
                if (terminator != '\0') { throw invalid(); }
                return pack(elements);
            }
            char value = source.charAt(index);
            if (terminator != '\0' && value == terminator) {
                index++;
                return pack(elements);
            }
            if (value == '}') { throw invalid(); }
            var base = atom(depth + 1, style, terminator);
            DocxMath lower = null;
            DocxMath upper = null;
            space();
            while (index < source.length() && (source.charAt(index) == '^' || source.charAt(index) == '_' || source.charAt(index) == '\'')) {
                char script = source.charAt(index++);
                if (script == '\'') {
                    var prime = new DocxMath.Run("′", DocxMath.Style.PLAIN);
                    upper = upper == null ? prime : new DocxMath.Sequence(List.of(upper, prime));
                } else if (script == '^') {
                    if (upper != null) { throw invalid(); }
                    upper = atom(depth + 1, style, terminator);
                } else {
                    if (lower != null) { throw invalid(); }
                    lower = atom(depth + 1, style, terminator);
                }
                space();
            }
            if (lower != null && upper != null) { base = new DocxMath.SubSuperscript(base, lower, upper); }
            else if (lower != null) { base = new DocxMath.Subscript(base, lower); }
            else if (upper != null) { base = new DocxMath.Superscript(base, upper); }
            elements.add(base);
        }
    }

    private DocxMath atom(int depth, DocxMath.Style style, char terminator) {
        ExecutionContext.checkpoint();
        if (depth > options.maxDepth()) { throw limit(); }
        space();
        if (index == source.length()) { throw invalid(); }
        int value = source.codePointAt(index);
        index += Character.charCount(value);
        if (value == '{') { return sequence('}', depth, style); }
        if (value == '}' || value == '^' || value == '_' || value == ']' && terminator == ']') { throw invalid(); }
        if (value == '&' || value == '$' || value == '%' || value == '#' || value == '~'
                || value == '\'' || value == '`' || value == '"' || value == '\ufffc') {
            throw unsupported();
        }
        if (value != '\\') { return run(new String(Character.toChars(value)), style, Character.isLetter(value)); }
        if (++commands > options.maxCommands()) { throw limit(); }
        if (index == source.length()) { throw invalid(); }
        int start = index;
        while (index < source.length() && asciiLetter(source.charAt(index))) { index++; }
        if (index == start) {
            char escaped = source.charAt(index++);
            return switch (escaped) {
                case '{', '}', '%', '_', '#', '$', '&' -> run(String.valueOf(escaped), style, false);
                case ',' -> run("\u2009", style, false);
                case ':' -> run("\u2005", style, false);
                case ';' -> run("\u2004", style, false);
                case ' ' -> run(" ", style, false);
                default -> throw unsupported();
            };
        }
        String command = source.substring(start, index);
        return switch (command) {
            case "frac" -> new DocxMath.Fraction(atom(depth + 1, style, terminator), atom(depth + 1, style, terminator));
            case "sqrt" -> {
                space();
                Optional<DocxMath> degree = Optional.empty();
                if (index < source.length() && source.charAt(index) == '[') {
                    index++;
                    degree = Optional.of(sequence(']', depth, style));
                }
                yield new DocxMath.Radical(atom(depth + 1, style, terminator), degree);
            }
            case "mathrm" -> atom(depth + 1, DocxMath.Style.PLAIN, terminator);
            case "mathit" -> atom(depth + 1, DocxMath.Style.ITALIC, terminator);
            case "mathbf" -> atom(depth + 1, DocxMath.Style.BOLD, terminator);
            case "text", "textrm", "textit", "textbf" -> text(command, depth);
            default -> {
                String letter = LETTERS.get(command);
                if (letter != null) { yield run(letter, style, true); }
                String symbol = SYMBOLS.get(command);
                if (symbol != null) { yield run(symbol, style, false); }
                if (FUNCTIONS.contains(command)) { yield run(command, DocxMath.Style.PLAIN, false); }
                throw unsupported();
            }
        };
    }

    /** A text group preserves whitespace and explicit literal escapes, never expands commands. */
    private DocxMath text(String command, int depth) {
        space();
        if (index == source.length() || source.charAt(index++) != '{') { throw invalid(); }
        if (depth + 1 > options.maxDepth()) { throw limit(); }
        var text = new StringBuilder();
        while (index < source.length()) {
            ExecutionContext.checkpoint();
            int value = source.codePointAt(index);
            index += Character.charCount(value);
            if (value == '}') {
                var style = switch (command) {
                    case "textbf" -> DocxMath.Style.BOLD;
                    case "textit" -> DocxMath.Style.ITALIC;
                    default -> DocxMath.Style.NORMAL_TEXT;
                };
                return new DocxMath.Run(text.toString(), style);
            }
            if (value == '{' || value == '$' || value == '%' || value == '#' || value == '~'
                    || value == '&' || value == '_' || value == '^' || value == '\ufffc') {
                throw unsupported();
            }
            if (value == '\\') {
                if (++commands > options.maxCommands()) { throw limit(); }
                if (index == source.length()) { throw invalid(); }
                char escaped = source.charAt(index++);
                if ("{}%_#$& ".indexOf(escaped) < 0) { throw unsupported(); }
                text.append(escaped);
            } else {
                if (Character.isISOControl(value)) { throw unsupported(); }
                text.appendCodePoint(value);
            }
        }
        throw invalid();
    }

    private void space() {
        while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
            ExecutionContext.checkpoint();
            index++;
        }
    }

    private static DocxMath run(String text, DocxMath.Style style, boolean letter) {
        return new DocxMath.Run(text, style == null ? letter ? DocxMath.Style.ITALIC : DocxMath.Style.PLAIN : style);
    }

    private static DocxMath pack(List<DocxMath> values) {
        return values.size() == 1 ? values.getFirst() : new DocxMath.Sequence(values);
    }

    /** Apply lexical budgets to the complete input before any unsupported construct can stop parsing. */
    private static void validateSource(String tex, MathOptions options) {
        int commands = 0;
        int braces = 0;
        boolean escaped = false;
        boolean malformed = false;
        for (int index = 0; index < tex.length();) {
            ExecutionContext.checkpoint();
            int value = tex.codePointAt(index);
            if (value >= 0xd800 && value <= 0xdfff || value == 0xfffe || value == 0xffff
                    || Character.isISOControl(value) && value != '\t' && value != '\r' && value != '\n') {
                malformed = true;
            }
            if (escaped) {
                escaped = false;
            } else if (value == '\\') {
                if (++commands > options.maxCommands()) { throw limit(); }
                escaped = true;
            } else if (value == '{') {
                if (++braces > options.maxDepth()) { throw limit(); }
            } else if (value == '}') {
                if (braces == 0) { malformed = true; }
                else { braces--; }
            }
            index += Character.charCount(value);
        }
        if (malformed || braces != 0 || escaped) { throw invalid(); }
    }

    private static boolean asciiLetter(char value) {
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z';
    }

    private static MathException invalid() { return new MathException(MathException.Reason.INVALID_SYNTAX); }
    private static MathException unsupported() { return new MathException(MathException.Reason.UNSUPPORTED_SYNTAX); }
    private static MathException limit() { return new MathException(MathException.Reason.LIMIT_EXCEEDED); }
}
