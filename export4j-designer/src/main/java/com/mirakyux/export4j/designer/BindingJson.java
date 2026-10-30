// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.designer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded JSON data, never executable expressions. Null is not a template binding value. */
final class BindingJson {
    private final String source;
    private int index;
    private int values;
    private BindingJson(String source, int limit) {
        if (source.length() > limit) { throw new IllegalArgumentException("JSON UTF-16 budget exceeded: " + limit); }
        this.source = source;
    }
    static Map<String, Object> parse(String text) {
        return parse(text, 1_000_000);
    }
    static Map<String, Object> parse(String text, int limit) {
        var parser = new BindingJson(text, limit);
        Object value = parser.value(0);
        parser.space();
        if (parser.index != text.length() || !(value instanceof Map<?, ?> map)) { throw parser.failure("expected one JSON object"); }
        var result = new LinkedHashMap<String, Object>();
        map.forEach((key, item) -> result.put((String) key, item));
        return Map.copyOf(result);
    }
    static Map<String, Object> object(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int n = 0; n < pairs.length; n += 2) { result.put((String) pairs[n], pairs[n + 1]); }
        return result;
    }
    static String write(Object value) {
        if (value == null) { return "null"; }
        if (value instanceof Boolean || value instanceof Number) { return value.toString(); }
        if (value instanceof Map<?, ?> map) {
            var items = new ArrayList<String>(); map.forEach((key, item) -> items.add(write(key.toString()) + ":" + write(item)));
            return "{" + String.join(",", items) + "}";
        }
        if (value instanceof List<?> list) { return "[" + String.join(",", list.stream().map(BindingJson::write).toList()) + "]"; }
        String text = value.toString(); var out = new StringBuilder("\"");
        for (int n = 0; n < text.length(); n++) {
            char c = text.charAt(n);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 32) { out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c)); } else { out.append(c); } }
            }
        }
        return out.append('"').toString();
    }
    private Object value(int depth) {
        if (depth > 32 || ++values > 50_000) { throw failure("JSON nesting/value budget exceeded"); }
        space();
        if (index == source.length()) { throw failure("missing value"); }
        char c = source.charAt(index);
        if (c == '"') { return string(); }
        if (c == '{') {
            index++; var map = new LinkedHashMap<String, Object>(); space();
            if (take('}')) { return Map.copyOf(map); }
            do {
                space(); String key = string(); space(); require(':');
                if (map.putIfAbsent(key, value(depth + 1)) != null) { throw failure("duplicate object key"); }
                space(); if (take('}')) { return Map.copyOf(map); } require(',');
            } while (true);
        }
        if (c == '[') {
            index++; var list = new ArrayList<Object>(); space();
            if (take(']')) { return List.copyOf(list); }
            do {
                list.add(value(depth + 1)); space();
                if (take(']')) { return List.copyOf(list); } require(',');
            } while (true);
        }
        if (source.startsWith("true", index)) { index += 4; return Boolean.TRUE; }
        if (source.startsWith("false", index)) { index += 5; return Boolean.FALSE; }
        int start = index;
        take('-');
        if (!take('0')) { digits(); }
        if (take('.')) { digits(); }
        if (take('e') || take('E')) { if (!take('+')) { take('-'); } digits(); }
        if (index == start || index - start > 256) { throw failure("invalid or oversized JSON number; null is unsupported"); }
        try {
            var number = new BigDecimal(source.substring(start, index));
            if (Math.abs((long) number.scale()) > 10_000) { throw failure("JSON numeric scale exceeds budget"); }
            return number;
        } catch (NumberFormatException ex) { throw failure("invalid JSON number"); }
    }
    private void digits() {
        int start = index;
        while (index < source.length() && source.charAt(index) >= '0' && source.charAt(index) <= '9') { index++; }
        if (start == index) { throw failure("expected digit"); }
    }
    private String string() {
        require('"'); var result = new StringBuilder(); boolean closed = false;
        while (index < source.length()) {
            char c = source.charAt(index++);
            if (c == '"') { closed = true; break; }
            if (c < 32) { throw failure("unescaped control character"); }
            if (c == '\\') {
                if (index == source.length()) { throw failure("incomplete escape"); }
                c = source.charAt(index++);
                switch (c) {
                    case '"', '\\', '/' -> result.append(c);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (index + 4 > source.length()) { throw failure("incomplete Unicode escape"); }
                        int code = 0;
                        for (int n = 0; n < 4; n++) {
                            int digit = Character.digit(source.charAt(index++), 16);
                            if (digit < 0) { throw failure("invalid Unicode escape"); }
                            code = code * 16 + digit;
                        }
                        result.append((char) code);
                    }
                    default -> throw failure("unknown JSON escape");
                }
            } else { result.append(c); }
        }
        if (!closed) { throw failure("unterminated string"); }
        for (int n = 0; n < result.length(); n++) {
            char c = result.charAt(n);
            if (Character.isHighSurrogate(c)) {
                if (++n == result.length() || !Character.isLowSurrogate(result.charAt(n))) { throw failure("unpaired surrogate"); }
            } else if (Character.isLowSurrogate(c)) { throw failure("unpaired surrogate"); }
        }
        return result.toString();
    }
    private void space() { while (index < source.length() && " \r\n\t".indexOf(source.charAt(index)) >= 0) { index++; } }
    private boolean take(char c) { if (index < source.length() && source.charAt(index) == c) { index++; return true; } return false; }
    private void require(char c) { if (!take(c)) { throw failure("expected '" + c + "'"); } }
    private IllegalArgumentException failure(String reason) { return new IllegalArgumentException(reason + " at JSON offset " + index); }
}
