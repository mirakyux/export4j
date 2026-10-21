// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import com.mirakyux.export4j.model.LineBreakPolicy;

/** Common CJK opening/closing punctuation prohibition; this is not a complete locale-specific typesetting standard. */
public final class LineBreakRules {
    private static final String NO_START = "、。，．！？：；）］｝〉》」』】〕〗〙〛’”％‰…";
    private static final String NO_END = "（［｛〈《「『【〔〖〘〚‘“";
    private LineBreakRules() { }

    public static boolean allowed(String text, int boundary) {
        return boundary <= 0 || boundary >= text.length()
                || NO_END.indexOf(text.codePointBefore(boundary)) < 0 && NO_START.indexOf(text.codePointAt(boundary)) < 0;
    }

    /** Tests original UTF-16 source, including nonbreaking characters and paired Chinese punctuation. */
    public static boolean allowed(String text, int boundary, LineBreakPolicy policy) {
        if (boundary <= 0 || boundary >= text.length()) { return true; }
        if (policy == LineBreakPolicy.STANDARD) { return allowed(text, boundary); }
        int before = text.codePointBefore(boundary);
        int after = text.codePointAt(boundary);
        return allowed(text, boundary) && "([{﹙﹛﹝".indexOf(before) < 0
                && ")]},.;:!?%﹚﹜﹞".indexOf(after) < 0
                && !nonbreaking(before) && !nonbreaking(after)
                && !(before == after && (before == 0x2026 || before == 0x2014));
    }

    private static boolean nonbreaking(int cp) {
        return cp == 0xa0 || cp == 0x202f || cp == 0x2060 || cp == 0xfeff;
    }

    /** Eligible expansion boundaries, preferring internal ASCII spaces to adjacent CJK letters.
     * Indices are local UTF-16 offsets. No gap is inserted inside a grapheme or punctuation group.
     */
    public static java.util.List<Integer> expansionBoundaries(String text) {
        var spaces = new java.util.ArrayList<Integer>();
        var cjk = new java.util.ArrayList<Integer>();
        int first = 0; int last = text.length();
        while (first < last && text.charAt(first) == ' ') { first++; }
        while (last > first && text.charAt(last - 1) == ' ') { last--; }
        var breaks = java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT);
        breaks.setText(text);
        for (int boundary = breaks.first(); (boundary = breaks.next()) != java.text.BreakIterator.DONE;) {
            if (boundary >= text.length() || !allowed(text, boundary, LineBreakPolicy.CJK_STRICT)) { continue; }
            int before = text.codePointBefore(boundary);
            int after = text.codePointAt(boundary);
            if (before == ' ' && boundary > first && boundary < last) {
                spaces.add(boundary);
            } else if (cjk(before) && cjk(after)) { cjk.add(boundary); }
        }
        return java.util.List.copyOf(spaces.isEmpty() ? cjk : spaces);
    }

    private static boolean cjk(int cp) {
        return switch (Character.UnicodeScript.of(cp)) {
            case HAN, HIRAGANA, KATAKANA, HANGUL, BOPOMOFO -> Character.isLetter(cp);
            default -> false;
        };
    }
}
