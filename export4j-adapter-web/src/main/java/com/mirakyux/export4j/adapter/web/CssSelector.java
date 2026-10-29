// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

/** Bounded selector compiler. No regex selectors, DOM callbacks or browser state. */
final class CssSelector {
    private record Part(Predicate<Element> match, char relation, int weight) { }
    private final List<Part> parts;
    private final int specificity;
    private final boolean siblingScan;
    private CssSelector(List<Part> parts, boolean siblingScan) { this.parts = List.copyOf(parts); specificity = parts.stream().mapToInt(Part::weight).sum(); this.siblingScan = siblingScan; }
    int specificity() { return specificity; }
    static List<String> split(String source) { return split(source, ','); }
    static List<String> split(String source, char delimiter) {
        var result = new ArrayList<String>(); int start = 0, square = 0, round = 0; char quote = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != 0) { if (c == quote) { quote = 0; } continue; }
            if (c == '\'' || c == '"') { quote = c; }
            else if (c == '[') { square++; } else if (c == ']') { square--; }
            else if (c == '(') { round++; } else if (c == ')') { round--; }
            else if (c == delimiter && square == 0 && round == 0) { result.add(source.substring(start, i).trim()); start = i + 1; }
            if (square < 0 || round < 0 || round > 2) { throw new IllegalArgumentException("invalid CSS nesting"); }
        }
        if (quote != 0 || square != 0 || round != 0) { throw new IllegalArgumentException("unbalanced CSS selector"); }
        result.add(source.substring(start).trim()); return result;
    }
    static CssSelector compile(String source) {
        if (source.isBlank() || source.length() > 256) { throw new IllegalArgumentException("CSS selector length exceeded"); }
        var result = new ArrayList<Part>(); int index = 0; char relation = ' ';
        while (index < source.length()) {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) { index++; }
            int start = index, square = 0, round = 0; char quote = 0;
            while (index < source.length()) {
                char c = source.charAt(index);
                if (quote != 0) { if (c == quote) { quote = 0; } }
                else if (c == '\'' || c == '"') { quote = c; }
                else if (c == '[') { square++; } else if (c == ']') { square--; }
                else if (c == '(') { round++; } else if (c == ')') { round--; }
                else if (square == 0 && round == 0 && (Character.isWhitespace(c) || c == '>' || c == '+' || c == '~')) { break; }
                index++;
            }
            var parsed = compound(source.substring(start, index), true);
            result.add(new Part(parsed.match(), relation, parsed.weight()));
            if (result.size() > 16) { throw new IllegalArgumentException("CSS selector segment budget exceeded"); }
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) { index++; }
            relation = ' ';
            if (index < source.length() && ">+~".indexOf(source.charAt(index)) >= 0) {
                relation = source.charAt(index++);
                while (index < source.length() && Character.isWhitespace(source.charAt(index))) { index++; }
                if (index == source.length()) { throw new IllegalArgumentException("missing CSS selector operand"); }
            }
        }
        return new CssSelector(result, source.toLowerCase(Locale.ROOT).contains(":nth-"));
    }
    boolean matches(Element element, ImportSupport.State state) { return matches(element, parts.size() - 1, state); }
    private boolean matches(Element element, int index, ImportSupport.State state) {
        state.work(1); if (element == null) { return false; }
        if (siblingScan && element.parent() != null) { state.work(element.parent().childrenSize()); }
        if (!parts.get(index).match().test(element)) { return false; }
        if (index == 0) { return true; }
        return switch (parts.get(index).relation()) {
            case '>' -> matches(element.parent(), index - 1, state);
            case '+' -> matches(element.previousElementSibling(), index - 1, state);
            case '~' -> { boolean found = false; for (var sibling = element.previousElementSibling(); sibling != null; sibling = sibling.previousElementSibling()) { if (matches(sibling, index - 1, state)) { found = true; break; } } yield found; }
            default -> { boolean found = false; for (var parent = element.parent(); parent != null; parent = parent.parent()) { if (matches(parent, index - 1, state)) { found = true; break; } } yield found; }
        };
    }
    private static Part compound(String source, boolean functions) {
        if (source.isEmpty()) { throw new IllegalArgumentException("empty CSS selector compound"); }
        var tests = new ArrayList<Predicate<Element>>(); int i = 0, weight = 0;
        if (source.charAt(0) == '*') { i++; }
        else if (Character.isLetter(source.charAt(0))) { int end = nameEnd(source, i); String tag = source.substring(i, end).toLowerCase(Locale.ROOT); tests.add(e -> e.normalName().equals(tag)); i = end; weight++; }
        while (i < source.length()) {
            char kind = source.charAt(i++);
            if (kind == '.' || kind == '#') {
                int end = nameEnd(source, i); String name = source.substring(i, end); i = end;
                tests.add(kind == '.' ? e -> e.hasClass(name) : e -> e.id().equals(name)); weight += kind == '#' ? 1_000_000 : 1_000;
            } else if (kind == '[') {
                int end = closing(source, i, ']'); String expression = source.substring(i, end).trim(); i = end + 1;
                var matcher = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_-]*)(?:\\s*([~|^$*]?=)\\s*(?:'([^']*)'|\"([^\"]*)\"|([a-zA-Z0-9_-]+)))?").matcher(expression);
                if (!matcher.matches()) { throw new IllegalArgumentException("unsupported CSS attribute selector"); }
                String name = matcher.group(1).toLowerCase(Locale.ROOT), op = matcher.group(2);
                String value = matcher.group(3) != null ? matcher.group(3) : matcher.group(4) != null ? matcher.group(4) : matcher.group(5);
                tests.add(e -> e.hasAttr(name) && (op == null || switch (op) {
                    case "=" -> e.attr(name).equals(value); case "~=" -> !value.isEmpty() && List.of(e.attr(name).split("\\s+")).contains(value);
                    case "|=" -> e.attr(name).equals(value) || e.attr(name).startsWith(value + "-");
                    case "^=" -> !value.isEmpty() && e.attr(name).startsWith(value); case "$=" -> !value.isEmpty() && e.attr(name).endsWith(value);
                    case "*=" -> !value.isEmpty() && e.attr(name).contains(value); default -> false;
                })); weight += 1_000;
            } else if (kind == ':') {
                int end = nameEnd(source, i); String pseudo = source.substring(i, end).toLowerCase(Locale.ROOT); i = end;
                if (i < source.length() && source.charAt(i) == '(') {
                    end = closing(source, ++i, ')'); String argument = source.substring(i, end).trim(); i = end + 1;
                    if (pseudo.equals("nth-child") || pseudo.equals("nth-last-child") || pseudo.equals("nth-of-type") || pseudo.equals("nth-last-of-type")) {
                        var formula = nth(argument); boolean reverse = pseudo.contains("last"), type = pseudo.contains("type");
                        tests.add(e -> { var siblings = e.parent() == null ? List.of(e) : e.parent().children().stream().filter(s -> !type || s.normalName().equals(e.normalName())).toList(); int n = siblings.indexOf(e) + 1; if (reverse) { n = siblings.size() - n + 1; } return formula[0] == 0 ? n == formula[1] : (long) (n - formula[1]) * formula[0] >= 0 && (n - formula[1]) % formula[0] == 0; }); weight += 1_000;
                    } else if (functions && List.of("not", "is", "where").contains(pseudo)) {
                        var alternatives = split(argument).stream().map(v -> compound(v, false)).toList();
                        if (alternatives.size() > 16) { throw new IllegalArgumentException("CSS selector alternatives exceeded"); }
                        tests.add(e -> alternatives.stream().anyMatch(p -> p.match().test(e)) != pseudo.equals("not"));
                        if (!pseudo.equals("where")) { weight += alternatives.stream().mapToInt(Part::weight).max().orElseThrow(); }
                    } else { throw new IllegalArgumentException("unsupported CSS selector function"); }
                } else {
                    tests.add(switch (pseudo) {
                        case "first-child" -> e -> e.previousElementSibling() == null;
                        case "last-child" -> e -> e.nextElementSibling() == null;
                        case "only-child" -> e -> e.previousElementSibling() == null && e.nextElementSibling() == null;
                        case "root" -> e -> e.parent() == null || e.parent().normalName().equals("#root");
                        case "empty" -> e -> e.childrenSize() == 0 && e.childNodes().stream().noneMatch(n -> n instanceof TextNode text && !text.getWholeText().isEmpty());
                        default -> throw new IllegalArgumentException("unsupported CSS pseudo-class");
                    }); weight += 1_000;
                }
            } else { throw new IllegalArgumentException("unsupported CSS selector token"); }
        }
        return new Part(e -> tests.stream().allMatch(t -> t.test(e)), ' ', weight);
    }
    private static int nameEnd(String source, int index) {
        int end = index;
        while (end < source.length() && (Character.isLetterOrDigit(source.charAt(end)) || source.charAt(end) == '_' || source.charAt(end) == '-')) { end++; }
        if (end == index) { throw new IllegalArgumentException("CSS name missing"); } return end;
    }
    private static int closing(String source, int start, char close) {
        char quote = 0; boolean attribute = false;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != 0) { if (c == quote) { quote = 0; } }
            else if (c == '\'' || c == '"') { quote = c; }
            else if (c == close && !attribute) { return i; }
            else if (close == ')' && c == '[' && !attribute) { attribute = true; }
            else if (close == ')' && c == ']' && attribute) { attribute = false; }
            else if (c == '(' || c == '[') { throw new IllegalArgumentException("nested CSS functions are outside this subset"); }
        }
        throw new IllegalArgumentException("unterminated CSS selector");
    }
    private static int[] nth(String source) {
        String text = source.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (text.equals("odd")) { return new int[] {2, 1}; } if (text.equals("even")) { return new int[] {2, 0}; }
        if (text.matches("[+-]?[0-9]{1,5}")) { return new int[] {0, Integer.parseInt(text)}; }
        var match = Pattern.compile("([+-]?[0-9]{0,5})n([+-][0-9]{1,5})?").matcher(text);
        if (!match.matches()) { throw new IllegalArgumentException("invalid CSS child formula"); }
        String a = match.group(1); return new int[] {a.isEmpty() || a.equals("+") ? 1 : a.equals("-") ? -1 : Integer.parseInt(a), match.group(2) == null ? 0 : Integer.parseInt(match.group(2))};
    }
}
