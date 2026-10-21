// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import java.text.BreakIterator;
import java.util.Collections;
import java.util.Locale;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;
import com.mirakyux.export4j.model.ExecutionContext;

/** Conservative indivisible source clusters shared by physical-font text layout.
 * Positions are UTF-16 offsets, including zero and the source length. The immutable result uses
 * this JDK's ROOT character boundaries and additionally retains Devanagari consonant/virama chains,
 * including an intervening ZWJ. This is a documented subset, not a complete Unicode segmentation
 * implementation. It does not load fonts, normalize text or decide whether a font supports a cluster.
 */
public final class TextClusters {
    private TextClusters() { }

    /** Returns immutable source boundaries. Null is rejected; source text is otherwise unchanged.
     * Safe to call concurrently. Cancellation and execution deadlines propagate from the caller.
     */
    public static NavigableSet<Integer> boundaries(String source) {
        Objects.requireNonNull(source, "source");
        var iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(source);
        var result = new TreeSet<Integer>();
        int visited = 0;
        for (int boundary = iterator.first(); boundary != BreakIterator.DONE; boundary = iterator.next()) {
            if ((visited++ & 1023) == 0) { ExecutionContext.checkpoint(); }
            if (boundary == 0 || boundary == source.length() || !joinsDevanagari(source, boundary)) {
                result.add(boundary);
            }
        }
        return Collections.unmodifiableNavigableSet(result);
    }

    private static boolean joinsDevanagari(String source, int boundary) {
        int next = source.codePointAt(boundary);
        if (!Character.isLetter(next) || Character.UnicodeScript.of(next) != Character.UnicodeScript.DEVANAGARI) {
            return false;
        }
        int cursor = boundary;
        while (cursor > 0 && source.codePointBefore(cursor) == 0x200d) { cursor--; }
        if (cursor == 0 || source.codePointBefore(cursor) != 0x094d) { return false; }
        cursor--;
        while (cursor > 0) {
            int cp = source.codePointBefore(cursor);
            if (Character.isLetter(cp)) { return Character.UnicodeScript.of(cp) == Character.UnicodeScript.DEVANAGARI; }
            int type = Character.getType(cp);
            if (type != Character.NON_SPACING_MARK && type != Character.COMBINING_SPACING_MARK
                    && type != Character.ENCLOSING_MARK) { return false; }
            cursor -= Character.charCount(cp);
        }
        return false;
    }
}
