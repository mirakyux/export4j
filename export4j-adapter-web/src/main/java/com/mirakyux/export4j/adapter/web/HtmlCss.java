// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy.Treatment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.nodes.Element;

/** Deliberately finite CSS grammar. No browser styles, resource loading or arbitrary selector language. */
final class HtmlCss {
    private static final String COMPOUND = "(?:[a-zA-Z][a-zA-Z0-9-]*|\\*)?(?:[.#][a-zA-Z_][a-zA-Z0-9_-]*)*";
    private static final Pattern SELECTOR = Pattern.compile(COMPOUND + "(?:\\s+(?:>\\s*)?" + COMPOUND + ")*");
    private static final Set<String> PROPERTIES = Set.of("font-size", "font-family", "font-weight", "font-style", "color", "line-height",
            "text-align", "text-decoration", "white-space", "margin-top", "margin-bottom", "margin-left", "text-indent", "display");
    private record Declaration(String name, String value, boolean important, int specificity, int order) { }
    private record Rule(String selector, List<Declaration> declarations) { }
    private final HtmlImporter importer;
    private final ImportSupport.State state;
    private final List<Rule> rules = new ArrayList<>();
    private final Map<Element, Computed> computed = new IdentityHashMap<>();
    private int order;

    static final class Computed {
        double size;
        boolean explicitSize;
        double line;
        boolean relativeLine;
        boolean bold;
        boolean italic;
        boolean underline;
        boolean strike;
        boolean pre;
        boolean hidden;
        List<String> roles = List.of("", "bold", "italic", "boldItalic");
        int color;
        ParagraphAlignment alignment = ParagraphAlignment.LEFT;
        double before;
        double after;
        double indent;
        double first;
        boolean geometryExplicit;
        Computed(ParagraphFormat base) { size = base.fontSize(); line = base.lineHeight() / size; relativeLine = true; after = base.spaceAfter(); }
        Computed(Computed parent) {
            size = parent.size; explicitSize = parent.explicitSize; line = parent.line; relativeLine = parent.relativeLine;
            bold = parent.bold; italic = parent.italic; underline = parent.underline; strike = parent.strike;
            pre = parent.pre; hidden = parent.hidden; roles = parent.roles; color = parent.color; alignment = parent.alignment;
        }
        double lineHeight() { return relativeLine ? size * line : line; }
        InlineStyle inline() {
            return new InlineStyle(roles.get((bold ? 1 : 0) + (italic ? 2 : 0)), size, color, 0, underline, strike);
        }
        ParagraphFormat paragraph(ParagraphFormat base, double extraIndent) {
            return base.toBuilder().fontSize(size).lineHeight(lineHeight()).alignment(alignment)
                    .firstLineIndent(base.firstLineIndent() + indent + first + extraIndent)
                    .subsequentLineIndent(base.subsequentLineIndent() + indent + extraIndent).spaceBefore(before).spaceAfter(after).build();
        }
    }
    HtmlCss(HtmlImporter importer, ImportSupport.State state) { this.importer = importer; this.state = state; }

    void stylesheet(Element owner) {
        String source = owner.data(); state.work(source.length());
        source = comments(source);
        safety(source);
        int index = 0;
        while (index < source.length()) {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) { index++; }
            if (index == source.length()) { break; }
            int open = source.indexOf('{', index), close = open < 0 ? -1 : source.indexOf('}', open);
            if (open < 0 || close < 0 || source.substring(open + 1, close).contains("{")) {
                unsupported(owner, "CSS_RULE", "CSS rule is outside the flat stylesheet grammar"); return;
            }
            String selectors = source.substring(index, open).trim();
            if (selectors.startsWith("@")) { unsupported(owner, "CSS_RULE", "CSS at-rules have no semantic mapping"); }
            else {
                var declarations = declarations(source.substring(open + 1, close), owner, 0);
                for (var selectorValue : selectors.split(",", -1)) {
                    var selector = selectorValue.trim();
                    if (selector.isEmpty() || selector.length() > 256 || !SELECTOR.matcher(selector).matches()
                            || selector.split("\\s+").length > 16 || selector.endsWith(">")) {
                        unsupported(owner, "CSS_SELECTOR", "CSS selector is outside the element/class/id descendant/child subset"); continue;
                    }
                    int specificity = specificity(selector);
                    if (rules.size() >= state.limits.maxNodes()) { throw new IllegalArgumentException("CSS rule budget exceeded"); }
                    rules.add(new Rule(selector, declarations.stream().map(d -> new Declaration(d.name(), d.value(), d.important(), specificity, d.order())).toList()));
                }
            }
            index = close + 1;
        }
    }
    Computed get(Element element) {
        var cached = computed.get(element); if (cached != null) { return cached; }
        Computed parent = element.parent() == null ? new Computed(importer.format) : get(element.parent());
        var value = new Computed(parent); String tag = element.normalName();
        if (tag.matches("h[1-6]")) {
            if (!parent.explicitSize) { var format = Heading.defaultFormat(tag.charAt(1) - '0'); value.size = format.fontSize(); value.line = format.lineHeight() / value.size; value.relativeLine = true; }
        }
        value.bold |= Set.of("strong", "b", "th").contains(tag); value.italic |= Set.of("em", "i").contains(tag);
        value.underline |= tag.equals("u"); value.strike |= Set.of("s", "del", "strike").contains(tag);
        value.after = importer.format.spaceAfter();
        if (tag.equals("pre")) { value.pre = true; }
        var winners = new HashMap<String, Declaration>();
        for (var rule : rules) {
            state.work(1);
            if (element.is(rule.selector())) { for (var declaration : rule.declarations()) { select(winners, declaration); } }
        }
        if (element.hasAttr("style")) { for (var declaration : declarations(comments(element.attr("style")), element, 1_000_000_000)) { select(winners, declaration); } }
        // Font size must be applied before relative line height, independently of declaration order.
        var size = winners.remove("font-size"); if (size != null) { apply(value, size, element, parent); }
        for (var declaration : winners.values()) { apply(value, declaration, element, parent); }
        if (value.indent + value.first < 0) { throw new IllegalArgumentException("HTML text indentation is negative"); }
        computed.put(element, value); return value;
    }
    private static void select(Map<String, Declaration> winners, Declaration candidate) {
        var old = winners.get(candidate.name());
        if (old == null || compare(candidate, old) >= 0) { winners.put(candidate.name(), candidate); }
    }
    private static int compare(Declaration first, Declaration second) {
        int compared = Boolean.compare(first.important(), second.important());
        if (compared == 0) { compared = Integer.compare(first.specificity(), second.specificity()); }
        return compared == 0 ? Integer.compare(first.order(), second.order()) : compared;
    }
    private List<Declaration> declarations(String source, Element owner, int specificity) {
        safety(source); state.work(source.length()); var result = new ArrayList<Declaration>();
        for (var item : source.split(";", -1)) {
            if (item.isBlank()) { continue; }
            int colon = item.indexOf(':');
            if (colon < 1) { unsupported(owner, "CSS_DECLARATION", "CSS declaration is malformed"); continue; }
            String name = item.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = item.substring(colon + 1).trim(); boolean important = value.matches("(?is).*\\s*!important\\s*$");
            if (important) { value = value.replaceFirst("(?is)\\s*!important\\s*$", "").trim(); }
            if (!PROPERTIES.contains(name)) { unsupported(owner, "CSS_PROPERTY", "CSS property has no supported semantic mapping"); continue; }
            result.add(new Declaration(name, value, important, specificity, ++order));
            if (order > state.limits.maxNodes()) { throw new IllegalArgumentException("CSS declaration budget exceeded"); }
        }
        return result;
    }
    private void apply(Computed value, Declaration declaration, Element owner, Computed parent) {
        String raw = declaration.value(); String text = raw.toLowerCase(Locale.ROOT);
        if (Set.of("margin-top", "margin-bottom", "margin-left", "text-indent").contains(declaration.name())) { value.geometryExplicit = true; }
        if (Set.of("span", "a", "strong", "b", "em", "i", "u", "s", "del", "strike", "img", "br", "code").contains(owner.normalName())
                && Set.of("line-height", "text-align", "margin-top", "margin-bottom", "margin-left", "text-indent").contains(declaration.name())) {
            unsupported(owner, "CSS_CONTEXT", "CSS paragraph geometry on an inline element has no model mapping"); return;
        }
        try {
            switch (declaration.name()) {
                case "font-size" -> { value.size = positive(length(text)); value.explicitSize = true; }
                case "line-height" -> {
                    if (text.equals("normal")) { value.line = 1.5; value.relativeLine = true; }
                    else if (text.matches("[0-9]+(?:\\.[0-9]+)?")) { value.line = positive(Double.parseDouble(text)); value.relativeLine = true; }
                    else { value.line = positive(length(text)); value.relativeLine = false; }
                }
                case "font-weight" -> { if (!Set.of("normal", "bold", "400", "700").contains(text)) { throw new IllegalArgumentException(); } value.bold = text.equals("bold") || text.equals("700"); }
                case "font-style" -> { if (!Set.of("normal", "italic").contains(text)) { throw new IllegalArgumentException(); } value.italic = text.equals("italic"); }
                case "font-family" -> {
                    String family = text.replaceAll("^['\"]|['\"]$", "");
                    var roles = importer.families.get(family); if (roles == null) { unsupported(owner, "CSS_FONT_FAMILY", "CSS family is not explicitly mapped to a font role"); }
                    else { value.roles = roles; }
                }
                case "color" -> value.color = color(text);
                case "text-align" -> value.alignment = ParagraphAlignment.valueOf(text.toUpperCase(Locale.ROOT));
                case "text-decoration" -> {
                    var parts = List.of(text.split("\\s+"));
                    if (!Set.of("none", "underline", "line-through").containsAll(parts) || parts.contains("none") && parts.size() != 1) { throw new IllegalArgumentException(); }
                    value.underline = parent.underline || parts.contains("underline"); value.strike = parent.strike || parts.contains("line-through");
                }
                case "white-space" -> { if (!Set.of("normal", "pre-wrap").contains(text)) { throw new IllegalArgumentException(); } value.pre = text.equals("pre-wrap"); }
                case "margin-top" -> {
                    double margin = nonnegative(length(text)); spacing(owner, margin); value.before = margin;
                }
                case "margin-bottom" -> {
                    double margin = nonnegative(length(text)); spacing(owner, margin); value.after = margin;
                }
                case "margin-left" -> value.indent = nonnegative(length(text));
                case "text-indent" -> value.first = length(text);
                case "display" -> { if (!text.equals("none")) { throw new IllegalArgumentException(); } value.hidden = true; }
                default -> throw new IllegalStateException("unrecognized supported CSS property");
            }
        } catch (ProcessingException | ImportSupport.UnavailableTreatment failure) { throw failure; }
        catch (IllegalArgumentException invalid) { unsupported(owner, "CSS_VALUE", "CSS value has no supported semantic mapping"); }
    }
    private void unsupported(Element owner, String feature, String reason) {
        state.unsupported(feature, HtmlImportMapper.location(owner, "", List.of()), reason, List.of("CSS_DECLARATION_OR_RULE_REMOVED"), Treatment.DEGRADE, Treatment.DISCARD);
    }
    private void spacing(Element owner, double margin) {
        if (margin != 0) {
            state.unsupported("CSS_SPACING", HtmlImportMapper.location(owner, "", List.of()),
                    "CSS margin collapse becomes additive model paragraph spacing", List.of("SPACING_SEMANTICS_CHANGED"), Treatment.DEGRADE);
        }
    }
    private static int specificity(String selector) {
        int ids = 0, classes = 0, elements = 0;
        for (var part : selector.split("\\s+")) {
            if (!part.isEmpty() && Character.isLetter(part.charAt(0))) { elements++; }
            for (int index = 0; index < part.length(); index++) { if (part.charAt(index) == '#') { ids++; } if (part.charAt(index) == '.') { classes++; } }
        }
        return ids * 1_000_000 + classes * 1_000 + elements;
    }
    static double length(String value) {
        if (value.equals("0")) { return 0; }
        if (!value.matches("-?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:pt|px)")) { throw new IllegalArgumentException("unsupported CSS length"); }
        double number = Double.parseDouble(value.substring(0, value.length() - 2));
        if (!Double.isFinite(number) || Math.abs(number) > 14400) { throw new IllegalArgumentException("invalid CSS length"); }
        return value.endsWith("px") ? number * 0.75 : number;
    }
    private static double positive(double value) { if (value <= 0) { throw new IllegalArgumentException(); } return value; }
    private static double nonnegative(double value) { if (value < 0) { throw new IllegalArgumentException(); } return value; }
    private static int color(String value) {
        if (value.matches("#[a-f0-9]{3}")) { value = "#" + value.charAt(1) + value.charAt(1) + value.charAt(2) + value.charAt(2) + value.charAt(3) + value.charAt(3); }
        if (value.matches("#[a-f0-9]{6}")) { return Integer.parseInt(value.substring(1), 16); }
        return switch (value) {
            case "black" -> 0; case "white" -> 0xffffff; case "red" -> 0xff0000;
            case "green" -> 0x008000; case "blue" -> 0x0000ff; case "gray", "grey" -> 0x808080;
            default -> throw new IllegalArgumentException("unsupported CSS color");
        };
    }
    private static String comments(String source) {
        var result = new StringBuilder(); int start = 0;
        while (true) {
            int comment = source.indexOf("/*", start); if (comment < 0) { return result.append(source, start, source.length()).toString(); }
            result.append(source, start, comment); int end = source.indexOf("*/", comment + 2);
            if (end < 0) { throw new IllegalArgumentException("unterminated CSS comment"); } start = end + 2;
        }
    }
    static void safety(String source) {
        String lower = source.toLowerCase(Locale.ROOT);
        if (source.contains("\\") || lower.matches("(?s).*(?:url\\s*\\(|@import|@font-face|expression\\s*\\(|javascript:|vbscript:).*")) {
            throw new IllegalArgumentException("CSS resources, escapes and executable constructs are not accepted");
        }
    }
}
