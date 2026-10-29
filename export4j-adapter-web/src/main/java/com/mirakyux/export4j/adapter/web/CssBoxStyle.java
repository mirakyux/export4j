// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.mirakyux.export4j.model.PageMargins;

/** Resolved finite CSS box declarations. Values are parsed once; no CSS is sent to a backend. */
final class CssBoxStyle {
    static final Set<String> PROPERTIES = Set.of("width", "height", "min-width", "max-width", "min-height", "max-height",
            "padding-top", "padding-right", "padding-bottom", "padding-left", "margin-right", "position", "left", "right", "top", "bottom",
            "box-sizing", "background-color", "flex-direction", "flex-wrap", "flex-grow", "flex-shrink", "flex-basis", "order",
            "justify-content", "align-content", "align-items", "align-self", "justify-items", "justify-self", "row-gap", "column-gap",
            "grid-template-columns", "grid-template-rows", "grid-auto-rows", "grid-auto-flow", "grid-column-start", "grid-column-end", "grid-row-start", "grid-row-end");
    record Length(double value, boolean percent, boolean automatic) {
        double resolve(double available) { return automatic ? available : percent ? available * value / 100 : value; }
    }
    record Track(double value, boolean fraction, boolean percent, boolean auto) { }
    final Map<String, String> values;
    CssBoxStyle(Map<String, String> values) { this.values = Map.copyOf(values); }
    String text(String property, String fallback) { return values.getOrDefault(property, fallback); }
    boolean has(String property) { return values.containsKey(property); }
    Length length(String name) { return dimension(text(name, "auto"), true); }
    double number(String name, double fallback) { return values.containsKey(name) ? Double.parseDouble(values.get(name)) : fallback; }
    double gap(boolean row) { return values.containsKey(row ? "row-gap" : "column-gap") ? HtmlCss.length(values.get(row ? "row-gap" : "column-gap")) : 0; }
    PageMargins padding() { return new PageMargins(pt("padding-top"), pt("padding-right"), pt("padding-bottom"), pt("padding-left")); }
    double pt(String name) { return HtmlCss.length(text(name, "0")); }
    static void validate(String name, String value) {
        switch (name) {
            case "width", "height", "min-width", "max-width", "min-height", "max-height", "flex-basis" -> {
                if ((name.startsWith("max-") && value.equals("none")) || value.equals("auto")) { return; }
                var length = dimension(value, false);
                if (length.value() < 0) { throw new IllegalArgumentException("negative CSS dimension"); }
                if (name.endsWith("height") && length.percent()) { throw new IllegalArgumentException("percentage heights need a browser containing-height algorithm outside this subset"); }
            }
            case "left", "right", "top", "bottom" -> dimension(value, true);
            case "padding-top", "padding-right", "padding-bottom", "padding-left", "margin-right", "row-gap", "column-gap" -> {
                if (HtmlCss.length(value) < 0) { throw new IllegalArgumentException("negative CSS spacing"); }
            }
            case "position" -> one(value, "static", "relative", "absolute");
            case "box-sizing" -> one(value, "content-box", "border-box");
            case "background-color" -> { if (!value.equals("transparent")) { HtmlCss.color(value); } }
            case "flex-direction" -> one(value, "row", "row-reverse", "column", "column-reverse");
            case "flex-wrap" -> one(value, "nowrap", "wrap", "wrap-reverse");
            case "flex-grow", "flex-shrink" -> {
                if (!value.matches("[0-9]+(?:\\.[0-9]+)?") || !Double.isFinite(Double.parseDouble(value)) || Double.parseDouble(value) > 10_000) { throw new IllegalArgumentException("invalid flex factor"); }
            }
            case "order" -> { if (!value.matches("-?[0-9]{1,6}")) { throw new IllegalArgumentException("invalid CSS order"); } }
            case "justify-content" -> one(value, "start", "end", "flex-start", "flex-end", "center", "space-between", "space-around", "space-evenly");
            case "align-content" -> one(value, "flex-start", "flex-end", "center", "stretch", "space-between", "space-around", "space-evenly");
            case "align-items", "justify-items" -> one(value, "start", "end", "flex-start", "flex-end", "center", "stretch");
            case "align-self", "justify-self" -> one(value, "auto", "start", "end", "flex-start", "flex-end", "center", "stretch");
            case "grid-template-columns", "grid-template-rows" -> tracks(value, !name.equals("grid-template-columns"));
            case "grid-auto-rows" -> { if (value.equals("none")) { throw new IllegalArgumentException("auto rows require at least one track"); } tracks(value, true); }
            case "grid-auto-flow" -> one(value, "row", "row dense");
            case "grid-column-start", "grid-column-end", "grid-row-start", "grid-row-end" -> {
                if (!value.equals("auto") && !value.matches("(?:span )?[1-9][0-9]{0,3}")) { throw new IllegalArgumentException("invalid CSS grid line"); }
            }
            default -> throw new IllegalArgumentException("unknown CSS box property");
        }
    }
    static Length dimension(String value, boolean auto) {
        if (auto && value.equals("auto")) { return new Length(0, false, true); }
        if (value.matches("-?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)%")) {
            double number = Double.parseDouble(value.substring(0, value.length() - 1));
            if (Math.abs(number) > 10_000) { throw new IllegalArgumentException("percentage budget exceeded"); }
            return new Length(number, true, false);
        }
        return new Length(HtmlCss.length(value), false, false);
    }
    static List<Track> tracks(String value, boolean auto) {
        if (auto && value.equals("none")) { return List.of(); }
        var output = new ArrayList<Track>();
        for (String token : tokens(value)) {
            if (token.startsWith("repeat(")) {
                var pieces = CssSelector.split(token.substring(7, token.length() - 1));
                if (pieces.size() != 2 || !pieces.getFirst().matches("[1-9][0-9]?") || pieces.get(1).contains("repeat(")) { throw new IllegalArgumentException("invalid bounded CSS repeat"); }
                int count = Integer.parseInt(pieces.getFirst()); var inner = tracks(pieces.get(1), auto);
                if ((long) count * inner.size() > 64) { throw new IllegalArgumentException("CSS track budget exceeded"); }
                for (int i = 0; i < count; i++) { output.addAll(inner); }
            } else if (auto && token.equals("auto")) { output.add(new Track(0, false, false, true)); }
            else if (token.matches("(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)fr")) {
                double n = Double.parseDouble(token.substring(0, token.length() - 2)); if (n <= 0 || n > 10000 || auto) { throw new IllegalArgumentException("invalid CSS fractional track"); } output.add(new Track(n, true, false, false));
            } else {
                var length = dimension(token, false); if (length.value() <= 0 || auto && length.percent()) { throw new IllegalArgumentException("invalid CSS fixed track"); }
                output.add(new Track(length.value(), false, length.percent(), false));
            }
            if (output.size() > 64) { throw new IllegalArgumentException("CSS track budget exceeded"); }
        }
        if (output.isEmpty()) { throw new IllegalArgumentException("empty CSS track list"); } return List.copyOf(output);
    }
    static List<String> tokens(String source) {
        var result = new ArrayList<String>(); int start = 0, round = 0;
        for (int i = 0; i <= source.length(); i++) {
            char c = i == source.length() ? ' ' : source.charAt(i);
            if (c == '(') { round++; } else if (c == ')') { round--; }
            if (round < 0 || round > 1) { throw new IllegalArgumentException("CSS track nesting exceeded"); }
            if (Character.isWhitespace(c) && round == 0) { if (i > start) { result.add(source.substring(start, i)); } start = i + 1; }
        }
        if (round != 0) { throw new IllegalArgumentException("CSS track nesting is unbalanced"); } return result;
    }
    private static void one(String value, String... accepted) { if (!List.of(accepted).contains(value)) { throw new IllegalArgumentException("unsupported CSS box value"); } }
}
