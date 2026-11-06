// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextLine;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentLayouterFragmentReuseTest {
    private static final TextDiagnostic FIRST = new TextDiagnostic(TextDiagnostic.Code.FONT_FALLBACK, 0, 1, "font");
    private static final TextDiagnostic SECOND = new TextDiagnostic(TextDiagnostic.Code.FONT_FALLBACK, 1, 2, "font");

    @Test
    void completeUnchangedParagraphKeepsItsPooledImmutableValue() {
        var layout = new ParagraphLayout(100, 24, List.of(line(0, 8), line(1, 20)), List.of(FIRST, SECOND));
        var fragment = DocumentLayouter.fragment(layout, 0, 2, 12);
        assertSame(layout, fragment);
        assertEquals(List.of(FIRST, SECOND), fragment.diagnostics());
        assertEquals(List.of(0, 1), fragment.lines().stream().map(TextLine::sourceStart).toList());
    }

    @Test
    void partialPagesKeepOriginalUtf16AndOnlyRebaseMovedLines() {
        var layout = new ParagraphLayout(100, 24, List.of(line(0, 8), line(1, 20)), List.of(FIRST, SECOND));
        var first = DocumentLayouter.fragment(layout, 0, 1, 12);
        var second = DocumentLayouter.fragment(layout, 1, 1, 12);
        assertSame(layout.lines().getFirst(), first.lines().getFirst());
        assertNotSame(layout.lines().getLast(), second.lines().getFirst());
        assertEquals(new ParagraphLayout(100, 12, List.of(line(0, 8)), List.of(FIRST)), first);
        assertEquals(new ParagraphLayout(100, 12, List.of(line(1, 8)), List.of(SECOND)), second);
        assertEquals(20, layout.lines().getLast().baselineY());
        assertEquals(List.of(FIRST, SECOND), layout.diagnostics());
    }

    @Test
    void wholeRangeStillNormalizesAnUnexpectedBaselineOrHeight() {
        var layout = new ParagraphLayout(100, 25, List.of(line(0, 8), line(1, 21)), List.of());
        var fragment = DocumentLayouter.fragment(layout, 0, 2, 12);
        assertNotSame(layout, fragment);
        assertSame(layout.lines().getFirst(), fragment.lines().getFirst());
        assertEquals(new ParagraphLayout(100, 24, List.of(line(0, 8), line(1, 20)), List.of()), fragment);
    }

    @Test
    void wholeRangeDoesNotRetainDiagnosticsOutsideItsSourceExtent() {
        var layout = new ParagraphLayout(100, 12, List.of(line(0, 8)), List.of(FIRST, SECOND));
        var fragment = DocumentLayouter.fragment(layout, 0, 1, 12);
        assertNotSame(layout, fragment);
        assertSame(layout.lines().getFirst(), fragment.lines().getFirst());
        assertEquals(List.of(FIRST), fragment.diagnostics());
        assertEquals(List.of(FIRST, SECOND), layout.diagnostics());
    }

    private static TextLine line(int sourceStart, double baseline) {
        return new TextLine(sourceStart, sourceStart + 1, 0, baseline, 5, List.of());
    }
}
