// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;

class SharedOpenTypeTest {
    @Test void justifiedOutlinesMoveInkWithoutChangingClustersSourcesOrFinalLines() throws IOException {
        var group = FontGroup.strict(List.of(face("LiberationSans-Regular.ttf"), face("NotoNaskhArabic-Regular.ttf"), face("LXGWWenKaiTC-Regular.ttf")));
        var layouter = new ParagraphLayouter();
        var style = new ParagraphStyle(12, 30, 0, 0, TextAlignment.LEFT);
        for (var source : List.of("AVATAR a\u0301 العربية لا 123 another line of text", "中文排版测试中文两端对齐布局检查结束")) {
            var spans = List.of(new StyledSpan(source, group, 12, 0x224488, 0, true, false));
            var left = layouter.layoutOpenType(spans, 97, style);
            var justified = layouter.layoutOpenType(spans, 97, style.withAlignment(TextAlignment.JUSTIFY));
            assertEquals(left.lines().stream().map(TextLine::sourceEnd).toList(), justified.lines().stream().map(TextLine::sourceEnd).toList());
            assertEquals(left.lines().getLast(), justified.lines().getLast());
            assertEquals(97, justified.lines().getFirst().width(), .001);
            assertFalse(left.lines().getFirst().outlines().equals(justified.lines().getFirst().outlines()));
            for (var line : justified.lines()) {
                assertEquals(source.substring(line.sourceStart(), line.sourceEnd()).stripTrailing(),
                        line.outlines().stream().map(TextOutline::logicalText).collect(java.util.stream.Collectors.joining()));
            }
        }
    }

    @Test void strictPolicyUsesOriginalSourceWhenMissingGlyphsBecomePlaceholders() throws IOException {
        var group = FontGroup.placeholder(List.of(face("LiberationSans-Regular.ttf")), '?');
        var style = new ParagraphStyle(12, 30, 0, 0, TextAlignment.LEFT)
                .withLineBreakPolicy(com.mirakyux.export4j.model.LineBreakPolicy.CJK_STRICT);
        String source = "a\u0301中（测试）完成";
        var result = new ParagraphLayouter().layout(source, group, 24, style);
        assertFalse(result.diagnostics().isEmpty());
        for (var line : result.lines()) { assertTrue(LineBreakRules.allowed(source, line.sourceEnd(), style.lineBreakPolicy())); }
        assertThrows(TextLayoutException.class, () -> new ParagraphLayouter().layout("（a\u0301）", group, 5, style));
    }

    private static FontFace face(String name) throws IOException {
        try (var input = SharedOpenTypeTest.class.getResourceAsStream("/fonts/" + name)) { return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES); }
    }
    @Test void commonParagraphShapesBidiCombiningAndPreservesLineInkDuringPagination() throws IOException {
        var group = FontGroup.strict(List.of(face("LiberationSans-Regular.ttf"), face("NotoNaskhArabic-Regular.ttf")));
        String source = "AVATAR a\u0301 العربية لا 123";
        var style = new ParagraphStyle(14, 32, 0, 0, TextAlignment.LEFT);
        var result = new ParagraphLayouter().layout(source, group, 500, style);
        var line = result.lines().getFirst();
        assertFalse(line.outlines().isEmpty());
        assertEquals(source, line.outlines().stream().map(TextOutline::logicalText).collect(java.util.stream.Collectors.joining()));
        assertEquals(line.outlines(), line.atBaseline(100).outlines());
        assertTrue(line.outlines().stream().anyMatch(outline -> !outline.segments().isEmpty()));
        assertThrows(TextLayoutException.class, () -> new ParagraphLayouter(new SimpleTextShaper(), TextLayoutLimits.DEFAULT)
                .layout(source, group, 500, style));
    }
    @Test void cffUsesOutlinesAndMissingScalarsKeepWarningSourceRanges() throws IOException {
        var cff = FontGroup.strict(List.of(face("SourceSans3-Regular.otf")));
        assertFalse(new ParagraphLayouter().layout("CFF AVATAR ffi", cff, 200, ParagraphStyle.of(12)).lines().getFirst().outlines().isEmpty());
        var group = FontGroup.placeholder(List.of(face("LiberationSans-Regular.ttf")), '?');
        var result = new ParagraphLayouter().layout("a\u0301\uD840\uDC00", group, 200, ParagraphStyle.of(12));
        var warning = result.diagnostics().stream().filter(d -> d.severity() == TextDiagnostic.Severity.WARNING).findFirst().orElseThrow();
        assertEquals(2, warning.sourceStart()); assertEquals(4, warning.sourceEnd());
        assertEquals(0x20000, warning.sourceCodePoint());
        assertThrows(TextLayoutException.class, () -> new ParagraphLayouter().layout("a\u0301", group, 1, ParagraphStyle.of(12)));
    }
}
