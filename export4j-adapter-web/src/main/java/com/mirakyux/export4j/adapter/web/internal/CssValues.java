// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web.internal;

import java.util.ArrayList;
import java.util.List;

/** Shared finite CSS value parsing for the semantic importer and optional box layout. */
public final class CssValues {
    private CssValues() { }

    public static double length(String value) {
        if (value.equals("0")) { return 0; }
        if (!value.matches("-?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:pt|px)")) { throw new IllegalArgumentException("unsupported CSS length"); }
        double number = Double.parseDouble(value.substring(0, value.length() - 2));
        if (!Double.isFinite(number) || Math.abs(number) > 14400) { throw new IllegalArgumentException("invalid CSS length"); }
        return value.endsWith("px") ? number * 0.75 : number;
    }

    public static int color(String value) {
        if (value.matches("#[a-f0-9]{3}")) { value = "#" + value.charAt(1) + value.charAt(1) + value.charAt(2) + value.charAt(2) + value.charAt(3) + value.charAt(3); }
        if (value.matches("#[a-f0-9]{6}")) { return Integer.parseInt(value.substring(1), 16); }
        return switch (value) {
            case "black" -> 0; case "white" -> 0xffffff; case "red" -> 0xff0000;
            case "green" -> 0x008000; case "blue" -> 0x0000ff; case "gray", "grey" -> 0x808080;
            default -> throw new IllegalArgumentException("unsupported CSS color");
        };
    }

    public static List<String> split(String source, char delimiter) {
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
}
