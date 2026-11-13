// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math;

import com.mirakyux.export4j.model.ExecutionContext;
import java.util.Objects;
import java.util.Set;

/** Grammar gate before the third-party parser. Argument groups, left/right pairs and matrix
 * cells are consumed recursively under one depth limit; no arbitrary TeX expansion is admitted.
 */
final class TexVectorSyntax {
    private static final Set<String> SYMBOLS = Set.of(
            "sum", "prod", "int", "iint", "oint", "infty", "pm", "mp", "quad", "qquad",
            "sin", "cos", "tan", "log", "ln", "exp", "lim", "min", "max", "to",
            "cdot", "times", "div", "le", "leq", "ge", "geq", "ne", "neq", "approx", "equiv",
            "partial", "nabla", "rightarrow", "leftarrow", "in", "notin", "cdots", "ldots",
            "alpha", "beta", "gamma", "delta", "epsilon", "theta", "lambda", "mu", "pi",
            "rho", "sigma", "tau", "phi", "chi", "psi", "omega", "Gamma", "Delta", "Theta",
            "Lambda", "Pi", "Sigma", "Phi", "Omega");
    private static final Set<String> ENVIRONMENTS = Set.of("matrix", "pmatrix", "bmatrix", "cases");
    private static final String LITERALS = "+-*/=.,:;|<>!()[]'";
    private final String source;
    private final MathOptions options;
    private final boolean explicitText;
    private int index;
    private int commands;
    private int cells;

    private TexVectorSyntax(String source, MathOptions options, boolean explicitText) {
        this.source = source; this.options = options; this.explicitText = explicitText;
    }

    static void validate(String source, MathOptions options) {
        validate(source, options, false);
    }

    static void validate(String source, MathOptions options, boolean explicitText) {
        Objects.requireNonNull(source, "tex"); Objects.requireNonNull(options, "options");
        ExecutionContext.checkpoint();
        if (source.length() > options.maxSourceLength()) { throw limit(); }
        if (source.isBlank()) { throw invalid(); }
        lexicalLimits(source, options);
        var parser = new TexVectorSyntax(source, options, explicitText);
        parser.sequence(0, Stop.END);
        if (parser.index != source.length()) { throw invalid(); }
    }

    /** An unknown command cannot terminate parsing before the rest of the input's budgets
     * have been checked. Literal escaped braces are not structural nesting.
     */
    private static void lexicalLimits(String source, MathOptions options) {
        int commands = 0, braces = 0;
        boolean escaped = false;
        boolean malformed = false;
        for (int index = 0; index < source.length(); index++) {
            ExecutionContext.checkpoint();
            char value = source.charAt(index);
            if (Character.isSurrogate(value) || value == 0xfffe || value == 0xffff
                    || Character.isISOControl(value) && value != '\t' && value != '\r' && value != '\n') {
                malformed = true;
            }
            if (escaped) { escaped = false; }
            else if (value == '\\') {
                if (++commands > options.maxCommands()) { throw limit(); }
                escaped = true;
            } else if (value == '{') {
                if (++braces > options.maxDepth()) { throw limit(); }
            } else if (value == '}') {
                if (braces == 0) { malformed = true; }
                else { braces--; }
            }
        }
        if (malformed || braces != 0 || escaped) { throw invalid(); }
    }

    private enum Stop { END, GROUP, DEGREE, RIGHT, CELL }

    private void sequence(int depth, Stop stop) {
        depth(depth);
        while (true) {
            space();
            if (index == source.length()) {
                if (stop != Stop.END) { throw invalid(); }
                return;
            }
            if (stop == Stop.GROUP && source.charAt(index) == '}') { index++; return; }
            if (stop == Stop.DEGREE && source.charAt(index) == ']') { index++; return; }
            if (stop == Stop.RIGHT && commandAt("right")) { return; }
            if (stop == Stop.CELL && (source.charAt(index) == '&' || commandAt("end") || source.startsWith("\\\\", index))) { return; }
            atom(depth);
            boolean lower = false, upper = false;
            space();
            while (index < source.length() && (source.charAt(index) == '^' || source.charAt(index) == '_')) {
                char script = source.charAt(index++);
                if (script == '^') { if (upper) { throw invalid(); } upper = true; }
                else { if (lower) { throw invalid(); } lower = true; }
                script(depth + 1);
                space();
            }
        }
    }

    private void atom(int depth) {
        depth(depth); space();
        if (index == source.length()) { throw invalid(); }
        char value = source.charAt(index++);
        if (value == '{') { sequence(depth + 1, Stop.GROUP); return; }
        if (asciiLetter(value) || value >= '0' && value <= '9' || LITERALS.indexOf(value) >= 0) { return; }
        if (value != '\\') { throw unsupported(); }
        String command = command();
        if (SYMBOLS.contains(command) || escape(command)) { return; }
        switch (command) {
            case "frac", "dfrac", "tfrac" -> { group(depth + 1); group(depth + 1); }
            case "sqrt" -> {
                space();
                if (index < source.length() && source.charAt(index) == '[') { index++; sequence(depth + 1, Stop.DEGREE); }
                group(depth + 1);
            }
            case "left" -> {
                delimiter(); sequence(depth + 1, Stop.RIGHT);
                index++; // Consume the slash; command() checks its command budget.
                if (!command().equals("right")) { throw invalid(); }
                delimiter();
            }
            case "begin" -> matrix(depth + 1);
            case "mathrm", "mathit", "bar", "overline" -> group(depth + 1);
            case "text", "textrm" -> { depth(depth + 1); text(); }
            default -> throw unsupported();
        }
    }

    private void script(int depth) {
        depth(depth); space();
        if (index == source.length()) { throw invalid(); }
        char value = source.charAt(index);
        if (value == '{') { index++; sequence(depth, Stop.GROUP); }
        else if (asciiLetter(value) || value >= '0' && value <= '9' || "+-*/=.,:;|<>!()'".indexOf(value) >= 0) { index++; }
        else if (value == '\\') {
            index++;
            String command = command();
            if (!SYMBOLS.contains(command) && !escape(command)) { throw unsupported(); }
        } else { throw invalid(); }
    }

    private void group(int depth) {
        depth(depth); space();
        if (index == source.length() || source.charAt(index++) != '{') { throw invalid(); }
        sequence(depth, Stop.GROUP);
    }

    private void text() {
        space();
        if (index == source.length() || source.charAt(index++) != '{') { throw invalid(); }
        while (index < source.length()) {
            ExecutionContext.checkpoint();
            char value = source.charAt(index++);
            if (value == '}') { return; }
            if (explicitText && value > 127 && literalText(value)) { continue; }
            if (!asciiLetter(value) && !(value >= '0' && value <= '9') && value != ' '
                    && "+-*/=.,:;|<>!()[]'".indexOf(value) < 0) { throw unsupported(); }
        }
        throw invalid();
    }

    private void matrix(int depth) {
        depth(depth);
        String environment = name();
        if (!ENVIRONMENTS.contains(environment)) { throw unsupported(); }
        int width = -1, current = 0;
        while (true) {
            if (++cells > 64) { throw limit(); }
            sequence(depth, Stop.CELL); current++;
            if (index < source.length() && source.charAt(index) == '&') { index++; continue; }
            if (width < 0) { width = current; }
            if (width != current || environment.equals("cases") && width != 2) { throw invalid(); }
            if (source.startsWith("\\\\", index)) { index += 2; countCommand(); current = 0; continue; }
            if (!commandAt("end")) { throw invalid(); }
            index++;
            if (!command().equals("end") || !name().equals(environment)) { throw invalid(); }
            return;
        }
    }

    private String name() {
        space();
        if (index == source.length() || source.charAt(index++) != '{') { throw invalid(); }
        int start = index;
        while (index < source.length() && asciiLetter(source.charAt(index))) { index++; }
        if (index == source.length() || source.charAt(index++) != '}') { throw invalid(); }
        return source.substring(start, index - 1);
    }

    private void delimiter() {
        space();
        if (index == source.length()) { throw invalid(); }
        char value = source.charAt(index++);
        if ("()[]|.".indexOf(value) >= 0) { return; }
        if (value == '\\') {
            String escaped = command();
            if (Set.of("{", "}", "|", "langle", "rangle").contains(escaped)) { return; }
        }
        throw unsupported();
    }

    private String command() {
        countCommand();
        if (index == source.length()) { throw invalid(); }
        int start = index;
        while (index < source.length() && asciiLetter(source.charAt(index))) { index++; }
        if (index == start) { index++; }
        return source.substring(start, index);
    }

    private boolean commandAt(String name) {
        String token = "\\" + name;
        return source.startsWith(token, index) && (index + token.length() == source.length()
                || !asciiLetter(source.charAt(index + token.length())));
    }

    private void countCommand() { if (++commands > options.maxCommands()) { throw limit(); } }
    private void depth(int depth) { ExecutionContext.checkpoint(); if (depth > options.maxDepth()) { throw limit(); } }
    private void space() {
        while (index < source.length() && " \t\r\n".indexOf(source.charAt(index)) >= 0) { index++; }
    }
    private static boolean asciiLetter(char value) { return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z'; }
    private static boolean literalText(char value) {
        int type = Character.getType(value);
        boolean script = switch (Character.UnicodeScript.of(value)) {
            case LATIN, HAN, HIRAGANA, KATAKANA, HANGUL, GREEK, CYRILLIC, COMMON -> true;
            default -> false;
        };
        return script && (Character.isLetterOrDigit(value) || value == ' '
                || type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION || type == Character.MATH_SYMBOL
                || type == Character.CURRENCY_SYMBOL || type == Character.MODIFIER_SYMBOL
                || type == Character.OTHER_SYMBOL);
    }
    private static boolean escape(String command) { return command.length() == 1 && ",;! {}|".indexOf(command.charAt(0)) >= 0; }
    private static MathException invalid() { return new MathException(MathException.Reason.INVALID_SYNTAX); }
    private static MathException unsupported() { return new MathException(MathException.Reason.UNSUPPORTED_SYNTAX); }
    private static MathException limit() { return new MathException(MathException.Reason.LIMIT_EXCEEDED); }
}
