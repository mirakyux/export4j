// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PaginationPolicy;
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
    private static final Set<String> PROPERTIES = Set.of("font-size", "font-family", "font-weight", "font-style", "color", "line-height",
            "text-align", "text-decoration", "white-space", "margin-top", "margin-bottom", "margin-left", "text-indent", "display",
            "break-before", "break-after", "break-inside", "orphans", "widows", "border");
    private record Declaration(String name, String value, boolean important, int specificity, int order) { }
    private record Rule(CssSelector selector, List<Declaration> declarations) { }
    private final HtmlImporter importer;
    private final ImportSupport.State state;
    private final List<Rule> rules = new ArrayList<>();
    private final Map<Element, Computed> computed = new IdentityHashMap<>();
    private final Map<String, Declaration> pageDeclarations = new HashMap<>();
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
        boolean breakBefore;
        boolean breakAfter;
        boolean keepTogether;
        int orphans = 1;
        int widows = 1;
        boolean borderless;
        final Map<String, String> box = new HashMap<>();
        Computed(ParagraphFormat base) { size = base.fontSize(); line = base.lineHeight() / size; relativeLine = true; after = base.spaceAfter(); }
        Computed(Computed parent) {
            size = parent.size; explicitSize = parent.explicitSize; line = parent.line; relativeLine = parent.relativeLine;
            bold = parent.bold; italic = parent.italic; underline = parent.underline; strike = parent.strike;
            pre = parent.pre; hidden = parent.hidden; roles = parent.roles; color = parent.color; alignment = parent.alignment;
            orphans = parent.orphans; widows = parent.widows;
        }
        double lineHeight() { return relativeLine ? size * line : line; }
        InlineStyle inline() {
            return new InlineStyle(roles.get((bold ? 1 : 0) + (italic ? 2 : 0)), size, color, 0, underline, strike);
        }
        ParagraphFormat paragraph(ParagraphFormat base, double extraIndent) {
            return base.toBuilder().fontSize(size).lineHeight(lineHeight()).alignment(alignment)
                    .firstLineIndent(base.firstLineIndent() + indent + first + extraIndent)
                    .subsequentLineIndent(base.subsequentLineIndent() + indent + extraIndent).spaceBefore(before).spaceAfter(after)
                    .keepTogether(keepTogether).pagination(new PaginationPolicy(false, false, orphans, widows)).build();
        }
    }
    HtmlCss(HtmlImporter importer, ImportSupport.State state) { this.importer = importer; this.state = state; }

    void stylesheet(Element owner) {
        String source = owner.data(); state.work(source.length());
        source = comments(source);
        safety(source);
        stylesheet(source, owner, true, 0);
    }
    private void stylesheet(String source, Element owner, boolean active, int depth) {
        if (depth > 8) { throw new IllegalArgumentException("CSS conditional rule depth exceeded"); }
        int index = 0;
        while (index < source.length()) {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) { index++; }
            if (index == source.length()) { break; }
            int open = source.indexOf('{', index), close = open < 0 ? -1 : closingBrace(source, open);
            if (open < 0 || close < 0) {
                unsupported(owner, "CSS_RULE", "CSS rule is not a balanced supported stylesheet rule"); return;
            }
            String selectors = source.substring(index, open).trim();
            String body = source.substring(open + 1, close);
            if (selectors.toLowerCase(Locale.ROOT).startsWith("@media ")) {
                boolean matches;
                try { matches = media(selectors.substring(7).trim()); }
                catch (IllegalArgumentException invalid) { unsupported(owner, "CSS_RULE", "CSS media query is outside the explicit offline environment"); index = close + 1; continue; }
                stylesheet(body, owner, active && matches, depth + 1);
            } else if (selectors.equalsIgnoreCase("@page")) {
                if (active && importer.media == HtmlImporter.Media.PRINT) { page(body, owner); }
                else { pageSyntax(body, owner); }
            } else if (selectors.startsWith("@") || body.contains("{")) { unsupported(owner, "CSS_RULE", "CSS at-rule or nested selector has no supported mapping"); }
            else {
                var declarations = declarations(body, owner, 0);
                List<String> selectorValues;
                try { selectorValues = CssSelector.split(selectors); }
                catch (IllegalArgumentException invalid) { unsupported(owner, "CSS_SELECTOR", "CSS selector list is unbalanced"); index = close + 1; continue; }
                for (var selectorValue : selectorValues) {
                    CssSelector selector;
                    try { selector = CssSelector.compile(selectorValue); }
                    catch (IllegalArgumentException invalid) {
                        unsupported(owner, "CSS_SELECTOR", "CSS selector is outside the bounded static selector grammar"); continue;
                    }
                    int specificity = selector.specificity();
                    if (rules.size() >= state.limits.maxNodes()) { throw new IllegalArgumentException("CSS rule budget exceeded"); }
                    if (active) { rules.add(new Rule(selector, declarations.stream().map(d -> new Declaration(d.name(), d.value(), d.important(), specificity, d.order())).toList())); }
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
            if (rule.selector().matches(element, state)) { for (var declaration : rule.declarations()) { select(winners, declaration); } }
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
            if (name.equals("page-break-before") || name.equals("page-break-after") || name.equals("page-break-inside")) { name = name.replace("page-break-", "break-"); if (value.equalsIgnoreCase("always")) { value = "page"; } }
            if (Set.of("margin", "padding", "gap", "flex", "grid-column", "grid-row").contains(name)) {
                List<Map.Entry<String, String>> expanded;
                try { expanded = expand(name, value); }
                catch (IllegalArgumentException invalid) { unsupported(owner, "CSS_VALUE", "CSS shorthand has no supported finite expansion"); continue; }
                for (var entry : expanded) { add(result, entry.getKey(), entry.getValue(), important, specificity); }
                continue;
            }
            if (!PROPERTIES.contains(name) && !CssBoxStyle.PROPERTIES.contains(name)) { unsupported(owner, "CSS_PROPERTY", "CSS property has no supported mapping"); continue; }
            add(result, name, value, important, specificity);
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
            if (CssBoxStyle.PROPERTIES.contains(declaration.name())) {
                CssBoxStyle.validate(declaration.name(), text);
                if (owner.normalName().equals("html")) { unsupported(owner, "CSS_CONTEXT", "root HTML box geometry has no document model mapping; style the body or an explicit container"); return; }
                boolean inline = Set.of("span", "a", "strong", "b", "em", "i", "u", "s", "del", "strike", "img", "br", "code").contains(owner.normalName());
                if (inline && !(owner.normalName().equals("img") && Set.of("width", "height").contains(declaration.name()) && !CssBoxStyle.dimension(text, true).percent())
                        || Set.of("td", "th", "tr", "thead", "tbody", "tfoot", "col", "colgroup").contains(owner.normalName())) {
                    unsupported(owner, "CSS_CONTEXT", "CSS box declaration has no layout mapping on this inline/table structural element"); return;
                }
                if (!importer.layoutMode && !owner.normalName().equals("img")) { unsupported(owner, "CSS_CONTEXT", "CSS box geometry requires the explicit layout import mode"); return; }
                value.box.put(declaration.name(), text); return;
            }
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
                case "display" -> {
                    if (text.equals("none")) { value.hidden = true; }
                    else if (Set.of("block", "inline").contains(text)) {
                        boolean inline = Set.of("span", "a", "strong", "b", "em", "i", "u", "s", "del", "strike", "img", "br", "code").contains(owner.normalName());
                        if (inline != text.equals("inline") || Set.of("table", "td", "th", "tr", "thead", "tbody", "tfoot", "col", "colgroup").contains(owner.normalName())) { unsupported(owner, "CSS_CONTEXT", "changing inline, block or table formatting has no supported mapping"); }
                    }
                    else if (importer.layoutMode && Set.of("flex", "grid").contains(text)) {
                        if (!Set.of("body", "div", "section", "article", "main", "header", "footer", "nav", "aside").contains(owner.normalName())) { unsupported(owner, "CSS_CONTEXT", "flex/grid formatting requires an explicit block container"); }
                        else { value.box.put("display", text); }
                    }
                    else { throw new IllegalArgumentException(); }
                }
                case "break-before" -> { paginationContext(owner); if (!Set.of("auto", "page").contains(text)) { throw new IllegalArgumentException(); } value.breakBefore = text.equals("page"); }
                case "break-after" -> { paginationContext(owner); if (!Set.of("auto", "page").contains(text)) { throw new IllegalArgumentException(); } value.breakAfter = text.equals("page"); }
                case "break-inside" -> { paginationContext(owner); if (!Set.of("auto", "avoid", "avoid-page").contains(text)) { throw new IllegalArgumentException(); } value.keepTogether = !text.equals("auto"); }
                case "orphans", "widows" -> { if (!text.matches("[1-9][0-9]{0,2}")) { throw new IllegalArgumentException(); } if (declaration.name().equals("orphans")) { value.orphans = Integer.parseInt(text); } else { value.widows = Integer.parseInt(text); } }
                case "border" -> { if (!Set.of("td", "th").contains(owner.normalName()) || !Set.of("none", "0", "0pt", "0px").contains(text)) { throw new IllegalArgumentException(); } value.borderless = true; }
                default -> throw new IllegalStateException("unrecognized supported CSS property");
            }
            if (importer.layoutMode && Set.of("margin-top", "margin-bottom", "margin-left").contains(declaration.name())) { value.box.put(declaration.name(), text); }
        } catch (ProcessingException | ImportSupport.UnavailableTreatment failure) { throw failure; }
        catch (IllegalArgumentException invalid) { unsupported(owner, "CSS_VALUE", "CSS value has no supported semantic mapping"); }
    }
    private void unsupported(Element owner, String feature, String reason) {
        state.unsupported(feature, HtmlImportMapper.location(owner, "", List.of()), reason, List.of("CSS_DECLARATION_OR_RULE_REMOVED"), Treatment.DEGRADE, Treatment.DISCARD);
    }
    private void spacing(Element owner, double margin) {
        if (margin != 0) {
            if (importer.layoutMode && owner.parent() != null && Set.of("flex", "grid").contains(get(owner.parent()).box.getOrDefault("display", "block"))) { return; }
            state.unsupported("CSS_SPACING", HtmlImportMapper.location(owner, "", List.of()),
                    "CSS margin collapse becomes additive model paragraph spacing", List.of("SPACING_SEMANTICS_CHANGED"), Treatment.DEGRADE);
        }
    }
    private void paginationContext(Element owner) {
        if (Set.of("td", "th", "tr", "thead", "tbody", "tfoot", "span", "a", "b", "strong", "em", "i", "img").contains(owner.normalName())) { throw new IllegalArgumentException("page constraint has no block mapping"); }
    }
    private void add(List<Declaration> output, String name, String value, boolean important, int specificity) {
        if (++order > state.limits.maxNodes()) { throw new IllegalArgumentException("CSS declaration budget exceeded"); }
        output.add(new Declaration(name, value, important, specificity, order));
    }
    private static List<Map.Entry<String, String>> expand(String name, String value) {
        var result = new ArrayList<Map.Entry<String, String>>(); var tokens = CssBoxStyle.tokens(value.trim().toLowerCase(Locale.ROOT));
        if (name.equals("margin") || name.equals("padding")) {
            if (tokens.isEmpty() || tokens.size() > 4) { throw new IllegalArgumentException(); }
            String top = tokens.getFirst(), right = tokens.size() > 1 ? tokens.get(1) : top;
            String bottom = tokens.size() > 2 ? tokens.get(2) : top, left = tokens.size() > 3 ? tokens.get(3) : right;
            result.add(Map.entry(name + "-top", top)); result.add(Map.entry(name + "-right", right)); result.add(Map.entry(name + "-bottom", bottom)); result.add(Map.entry(name + "-left", left));
        } else if (name.equals("gap")) {
            if (tokens.isEmpty() || tokens.size() > 2) { throw new IllegalArgumentException(); }
            result.add(Map.entry("row-gap", tokens.getFirst())); result.add(Map.entry("column-gap", tokens.getLast()));
        } else if (name.equals("flex")) {
            String grow, shrink, basis;
            if (value.equalsIgnoreCase("none")) { grow = "0"; shrink = "0"; basis = "auto"; }
            else if (value.equalsIgnoreCase("auto")) { grow = "1"; shrink = "1"; basis = "auto"; }
            else if (tokens.size() == 1 && tokens.getFirst().matches("[0-9]+(?:\\.[0-9]+)?")) { grow = tokens.getFirst(); shrink = "1"; basis = "0pt"; }
            else if (tokens.size() == 2) { grow = tokens.getFirst(); if (tokens.getLast().matches("[0-9]+(?:\\.[0-9]+)?")) { shrink = tokens.getLast(); basis = "0pt"; } else { shrink = "1"; basis = tokens.getLast(); } }
            else if (tokens.size() == 3) { grow = tokens.getFirst(); shrink = tokens.get(1); basis = tokens.getLast(); }
            else { throw new IllegalArgumentException(); }
            CssBoxStyle.validate("flex-grow", grow); CssBoxStyle.validate("flex-shrink", shrink); CssBoxStyle.validate("flex-basis", basis);
            result.add(Map.entry("flex-grow", grow)); result.add(Map.entry("flex-shrink", shrink)); result.add(Map.entry("flex-basis", basis));
        } else {
            var pair = CssSelector.split(value, '/'); if (pair.isEmpty() || pair.size() > 2) { throw new IllegalArgumentException(); }
            String start = pair.getFirst().trim(), end = pair.size() == 2 ? pair.get(1).trim() : "auto";
            CssBoxStyle.validate(name + "-start", start); CssBoxStyle.validate(name + "-end", end);
            result.add(Map.entry(name + "-start", start)); result.add(Map.entry(name + "-end", end));
        }
        return result;
    }
    private static int closingBrace(String source, int open) {
        int depth = 1; char quote = 0;
        for (int i = open + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != 0) { if (c == quote) { quote = 0; } }
            else if (c == '\'' || c == '"') { quote = c; }
            else if (c == '{') { depth++; } else if (c == '}' && --depth == 0) { return i; }
            if (depth > 10) { throw new IllegalArgumentException("CSS rule depth exceeded"); }
        }
        return -1;
    }
    private boolean media(String query) {
        if (query.length() > 512) { throw new IllegalArgumentException("CSS media query length exceeded"); }
        boolean result = false;
        for (var alternative : CssSelector.split(query.toLowerCase(Locale.ROOT))) {
            String text = alternative.trim(); boolean invert = text.startsWith("not ");
            if (invert || text.startsWith("only ")) { text = text.substring(text.indexOf(' ') + 1).trim(); }
            boolean match = true; String[] clauses = text.split("\\s+and\\s+");
            if (clauses.length > 8) { throw new IllegalArgumentException("CSS media condition budget exceeded"); }
            int start = 0;
            if (!clauses[0].startsWith("(")) {
                if (!Set.of("all", "print", "screen").contains(clauses[0])) { throw new IllegalArgumentException("unknown offline media type"); }
                match = clauses[0].equals("all") || clauses[0].equals(importer.media.name().toLowerCase(Locale.ROOT)); start = 1;
            }
            for (int i = start; i < clauses.length; i++) {
                var condition = Pattern.compile("\\(\\s*(min-width|max-width|min-height|max-height|orientation)\\s*:\\s*([^()]+)\\s*\\)").matcher(clauses[i]);
                if (!condition.matches()) { throw new IllegalArgumentException("unsupported CSS media feature"); }
                String name = condition.group(1), value = condition.group(2).trim(); boolean accepted;
                if (name.equals("orientation")) {
                    if (!Set.of("portrait", "landscape").contains(value)) { throw new IllegalArgumentException(); }
                    accepted = value.equals(importer.viewport.width() > importer.viewport.height() ? "landscape" : "portrait");
                } else { double threshold = nonnegative(length(value)); double actual = name.endsWith("width") ? importer.viewport.width() : importer.viewport.height(); accepted = name.startsWith("min-") ? actual >= threshold : actual <= threshold; }
                match &= accepted;
            }
            result |= invert != match;
        }
        return result;
    }
    private void pageSyntax(String source, Element owner) { page(source, owner, false); }
    private void page(String source, Element owner) { page(source, owner, true); }
    private void page(String source, Element owner, boolean active) {
        if (source.contains("{")) { unsupported(owner, "CSS_RULE", "page margin boxes have no supported mapping"); return; }
        for (var item : source.split(";", -1)) {
            if (item.isBlank()) { continue; } int colon = item.indexOf(':');
            if (colon < 1) { unsupported(owner, "CSS_DECLARATION", "malformed page declaration"); continue; }
            String name = item.substring(0, colon).trim().toLowerCase(Locale.ROOT), value = item.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
            boolean important = value.matches("(?is).*\\s*!important\\s*$"); value = value.replaceFirst("(?is)\\s*!important\\s*$", "").trim();
            List<Map.Entry<String, String>> values;
            try {
                if (name.equals("margin")) { values = expand(name, value); }
                else if (Set.of("margin-top", "margin-right", "margin-bottom", "margin-left", "size").contains(name)) { values = List.of(Map.entry(name, value)); }
                else { unsupported(owner, "CSS_PROPERTY", "page declaration has no model mapping"); continue; }
                for (var entry : values) {
                    if (entry.getKey().equals("size")) { pageSize(entry.getValue()); } else { nonnegative(length(entry.getValue())); }
                }
            } catch (IllegalArgumentException invalid) { unsupported(owner, "CSS_VALUE", "page size/margin has no supported point mapping"); continue; }
            for (var entry : values) { var declaration = new ArrayList<Declaration>(); add(declaration, entry.getKey(), entry.getValue(), important, 0); if (active) { select(pageDeclarations, declaration.getFirst()); } }
        }
    }
    PageSettings pageSettings() {
        var initial = importer.pageSettings; var size = pageDeclarations.get("size"); PageSize dimensions = size == null ? initial.size() : pageSize(size.value());
        var margins = initial.margins();
        return new PageSettings(dimensions, new PageMargins(pageValue("margin-top", margins.top()), pageValue("margin-right", margins.right()), pageValue("margin-bottom", margins.bottom()), pageValue("margin-left", margins.left())));
    }
    private double pageValue(String name, double fallback) { return pageDeclarations.containsKey(name) ? length(pageDeclarations.get(name).value()) : fallback; }
    private static PageSize pageSize(String value) {
        var words = CssBoxStyle.tokens(value); boolean landscape = words.contains("landscape");
        if (words.size() == 1 && words.getFirst().equals("auto")) { throw new IllegalArgumentException("auto page size uses explicit caller settings, not a CSS size declaration"); }
        PageSize size;
        if (words.getFirst().equals("a4")) { size = PageSize.A4; }
        else if (words.getFirst().equals("letter")) { size = new PageSize(612, 792); }
        else if (words.size() == 2) { return new PageSize(positive(length(words.getFirst())), positive(length(words.getLast()))); }
        else { throw new IllegalArgumentException("unsupported CSS page size"); }
        if (words.size() > 2 || words.size() == 2 && !Set.of("landscape", "portrait").contains(words.getLast())) { throw new IllegalArgumentException("unsupported CSS page orientation"); }
        return landscape ? new PageSize(size.height(), size.width()) : size;
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
    static int color(String value) {
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
