// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.DocumentLayout;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.text.FieldBox;
import com.mirakyux.export4j.text.TextLine;
import com.mirakyux.export4j.text.TextOutline;

class PartialInlineFieldReuseTest {
    private static final ParagraphFormat FORMAT = ParagraphFormat.builder().fontSize(10).lineHeight(20).spaceAfter(0).build();
    private static FontGroup fonts;

    @BeforeAll static void loadFonts() throws IOException {
        try (var input = PartialInlineFieldReuseTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES));
        }
    }

    @Test void oneFieldPageKeepsOtherPagesAndAdjacentFragmentsByIdentityWithoutChangingSourceGeometry() {
        var body = body();
        var anchors = anchors(body);
        var result = resolve(body, anchors, 1, 2);
        assertEquals(3, result.body().pages().size());
        assertSame(body.pages().getFirst(), result.body().pages().getFirst());
        assertSame(body.pages().getLast(), result.body().pages().getLast());
        var originalPage = body.pages().get(1);
        var resolvedPage = result.body().pages().get(1);
        assertNotSame(originalPage, resolvedPage);
        assertSame(originalPage.elements().get(1), resolvedPage.elements().get(1));
        var original = (ParagraphFragment) originalPage.elements().getFirst();
        var resolved = (ParagraphFragment) resolvedPage.elements().getFirst();
        assertNotSame(original, resolved);
        assertEquals(original.blockId(), resolved.blockId());
        assertEquals(original.blockIndex(), resolved.blockIndex());
        assertEquals(original.firstLineIndex(), resolved.firstLineIndex());
        assertEquals(original.origin(), resolved.origin());
        assertEquals(original.layout().width(), resolved.layout().width());
        assertEquals(original.layout().height(), resolved.layout().height());
        assertEquals(original.layout().lines().stream().map(TextLine::sourceStart).toList(), resolved.layout().lines().stream().map(TextLine::sourceStart).toList());
        assertEquals(original.layout().lines().stream().map(TextLine::sourceEnd).toList(), resolved.layout().lines().stream().map(TextLine::sourceEnd).toList());
        assertEquals(List.of(5, 11), original.layout().lines().stream().flatMap(line -> line.fields().stream()).map(FieldBox::sourceIndex).toList());
        assertTrue(resolved.layout().lines().stream().allMatch(line -> line.fields().isEmpty()));
        assertEquals("PAGE 3 REF 4", resolved.layout().lines().stream().flatMap(line -> line.outlines().stream())
                .map(TextOutline::logicalText).collect(java.util.stream.Collectors.joining()));
        assertTrue(result.diagnostics().isEmpty());
        assertEquals(1, result.links().size());
        var link = result.links().getFirst();
        assertEquals(2, link.pageIndex());
        assertEquals(3, link.targetPageIndex());
        assertEquals(anchors.get("target").point(), link.destination());
        var originalLine = original.layout().lines().getFirst();
        assertEquals(original.origin().x() + originalLine.offsetX() + originalLine.fields().get(1).x(), link.bounds().x());
        assertEquals(original.origin().y(), link.bounds().y());
        assertEquals(24, link.bounds().width());
        assertEquals(20, link.bounds().height());
    }

    @Test void reusingUnchangedIrDoesNotSkipReferenceLinksOrGeneratedValueBudgets() {
        var body = body();
        var anchors = anchors(body);
        assertEquals(1, resolve(body, anchors, 1, 2).links().size());
        for (long[] budget : List.of(new long[]{0, 2}, new long[]{1, 1})) {
            var failure = assertThrows(DocumentLayoutException.class, () -> resolve(body, anchors, budget[0], budget[1]));
            assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
            assertEquals(11, failure.sourceIndex());
            assertEquals(2, failure.pageIndex());
        }
        assertEquals(List.of(5, 11), ((ParagraphFragment) body.pages().get(1).elements().getFirst()).layout().lines().getFirst()
                .fields().stream().map(FieldBox::sourceIndex).toList());
    }

    @Test void pageSessionReusesUnchangedPagesDrainsOnlyPageResultsAndKeepsGlobalLinkAccounting() {
        var body = body();
        var session = new InlineFields.Session(4, 1, page -> page + 2, anchors(body), Map.of(), List.of(), 1, 100);
        var first = session.resolve(body.pages().getFirst(), fonts, Map.of(), Map.of());
        assertSame(body.pages().getFirst(), first.page());
        assertTrue(first.links().isEmpty());
        var changed = session.resolve(body.pages().get(1), fonts, Map.of(), Map.of());
        assertNotSame(body.pages().get(1), changed.page());
        assertSame(body.pages().get(1).elements().get(1), changed.page().elements().get(1));
        assertEquals(1, changed.links().size());
        var last = session.resolve(body.pages().getLast(), fonts, Map.of(), Map.of());
        assertSame(body.pages().getLast(), last.page());
        assertTrue(last.links().isEmpty());
        assertTrue(last.diagnostics().isEmpty());
        var anotherPresentation = new LayoutPage(2, body.pages().get(1).size(), body.pages().get(1).contentBounds(), body.pages().get(1).elements());
        var failure = assertThrows(DocumentLayoutException.class, () -> session.resolve(anotherPresentation, fonts, Map.of(), Map.of()));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals(11, failure.sourceIndex());
        assertEquals(3, failure.pageIndex());
    }

    private static InlineFields.Result resolve(DocumentLayout body, Map<String, ResolvedAnchor> anchors, long maxLinks, long maxGeneratedCodePoints) {
        return InlineFields.resolve(body, 1, page -> page + 2, anchors, Map.of(), List.of(), fonts, Map.of(), maxLinks, maxGeneratedCodePoints, Map.of());
    }

    private static Map<String, ResolvedAnchor> anchors(DocumentLayout body) {
        var target = body.pages().getLast().elements().getFirst();
        return Map.of("target", new ResolvedAnchor(body.pages().getLast().pageIndex(), target.origin()));
    }

    private static DocumentLayout body() {
        var source = new Document(DocumentMetadata.EMPTY, PageSettings.builder().size(200, 100).margins(10).build(), List.of(
                new Paragraph("plain", "A\nB\nC\nD", FORMAT),
                Paragraph.mixed("fields", List.of(new InlineText("PAGE ", InlineStyle.of(10)),
                        new InlineField(InlineField.Kind.PAGE, "", 24, InlineStyle.of(10)), new InlineText(" REF ", InlineStyle.of(10)),
                        new InlineField(InlineField.Kind.REFERENCE_PAGE, "target", 24, InlineStyle.of(10))), FORMAT),
                new Paragraph("unchanged", "TAIL", FORMAT),
                new Paragraph("target", "TARGET\nLINE\nEND", FORMAT.toBuilder().keepTogether(true).build())));
        var body = new DocumentLayouter().layout(source, fonts);
        assertEquals(3, body.pages().size());
        assertEquals(2, body.pages().get(1).elements().size());
        return body;
    }
}
