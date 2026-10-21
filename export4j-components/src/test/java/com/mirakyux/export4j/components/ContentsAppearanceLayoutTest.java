// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.FontMetrics;
import com.mirakyux.export4j.font.GlyphMetrics;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextLayoutException;

class ContentsAppearanceLayoutTest {
    private static final PageSettings PAGE = PageSettings.builder().size(240, 200).margins(12).build();
    private static final RgbColor RED = new RgbColor(0.8, 0.1, 0.2);
    private static final RgbColor GREEN = new RgbColor(0.1, 0.6, 0.2);
    private static final RgbColor BLUE = new RgbColor(0.1, 0.2, 0.8);
    private static FontFace regular;
    private static FontGroup fonts;

    @BeforeAll static void fonts() throws IOException {
        try (var input = ContentsAppearanceLayoutTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            if (input == null) { throw new IOException("missing test font"); }
            regular = TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
            fonts = FontGroup.strict(List.of(regular));
        }
    }

    private static Document source() {
        return new Document(DocumentMetadata.EMPTY, PAGE, List.of(new Paragraph("p", "Body", ParagraphFormat.of(10))));
    }
    private static Navigation navigation(TableOfContents contents, String entry) {
        return new Navigation(List.of(new Navigation.Bookmark(entry, "p", 1)), contents, List.of(), List.of());
    }
    private static ComponentDocumentLayout layout(Document source, Navigation navigation, FontGroup group) {
        return DocumentEngine.builder(group).navigation(navigation).build().layout(source);
    }
    private static List<DrawCommand.GlyphText> glyphs(List<DrawCommand> commands) {
        return commands.stream().filter(DrawCommand.GlyphText.class::isInstance).map(DrawCommand.GlyphText.class::cast).toList();
    }
    private static DrawCommand.GlyphText glyph(List<DrawCommand> commands, String text) {
        return glyphs(commands).stream().filter(value -> value.run().text().equals(text)).findFirst().orElseThrow();
    }
    private static String logical(ParagraphLayout paragraph) {
        return paragraph.lines().stream().map(line -> line.outlines().isEmpty()
                ? line.runs().stream().map(value -> value.text()).collect(Collectors.joining())
                : line.outlines().stream().map(value -> value.logicalText()).collect(Collectors.joining()))
                .collect(Collectors.joining());
    }
    private static FontFace observedFace(int missingCodePoint, IntConsumer observer) {
        return new FontFace() {
            @Override public FontData data() { return regular.data(); }
            @Override public FontMetrics metrics() { return regular.metrics(); }
            @Override public Optional<GlyphMetrics> glyph(int codePoint) {
                observer.accept(codePoint);
                return codePoint == missingCodePoint ? Optional.empty() : regular.glyph(codePoint);
            }
        };
    }
    private static ContentsLayouter.Result direct(Navigation navigation, PageSettings settings, FontGroup group,
            int maxPages, DocumentLayoutLimits limits, ComponentLimits componentLimits, java.util.function.IntBinaryOperator pageNumber) {
        var anchors = new java.util.HashMap<String, ResolvedAnchor>();
        navigation.bookmarks().forEach(value -> anchors.put(value.blockId(), new ResolvedAnchor(0, new Point(12, 12))));
        return new ContentsLayouter().layout(navigation, settings, group, anchors, maxPages, limits, componentLimits, pageNumber);
    }

    @Test void oldConstructorExplicitDefaultAndEmptyAppearanceKeepCompleteLayoutDisplayAndInteractionsEqual() {
        var base = new ParagraphStyle(11, 20, 7, 3, TextAlignment.RIGHT, LineBreakPolicy.CJK_STRICT);
        var blocks = List.<Block>of(new Paragraph("p", "Body", ParagraphFormat.of(10)),
                Paragraph.mixed("fields", List.of(new InlineText("Page ", InlineStyle.of(10)),
                        new InlineField(InlineField.Kind.PAGE, "", 18, InlineStyle.of(10))), ParagraphFormat.of(10)));
        var source = new Document(DocumentMetadata.EMPTY, PAGE, blocks);
        var old = new TableOfContents(true, "Contents", base);
        var empty = TableOfContents.builder().title("Contents").style(base).appearance(value -> { }).build();
        var explicitDefault = new TableOfContents(true, "Contents", base, ContentsStyle.DEFAULT);
        var bookmarks = List.of(new Navigation.Bookmark("Alpha beta gamma delta epsilon zeta", "p", 1),
                new Navigation.Bookmark("Nested entry", "fields", 2));
        var comments = List.of(new Navigation.Comment(new Navigation.BlockTarget("p", new Rect(0, 0, 10, 10)), "Author", "Check"));
        var fields = List.<Navigation.Field>of(new Navigation.TextField(new Navigation.BlockTarget("p", new Rect(20, 0, 50, 12)), "name", "Ada", false));
        var expected = layout(source, new Navigation(bookmarks, old, comments, fields), fonts);
        for (var value : List.of(empty, explicitDefault, old.toBuilder().build())) {
            var actual = layout(source, new Navigation(bookmarks, value, comments, fields), fonts);
            assertEquals(expected, actual);
            assertEquals(expected.contextualDiagnostics(), actual.contextualDiagnostics());
        }
        assertEquals(1, expected.bodyPageOffset()); assertEquals(2, expected.display().pages().size());
        assertEquals(1, expected.display().extras().fields().getFirst().pageIndex());
        assertEquals(1, expected.display().extras().comments().getFirst().pageIndex());
    }

    @Test void independentTypographyAndColorsReachActualTitleEntryAndAlignedPageLabelInk() {
        var appearance = ContentsStyle.builder().titleStyle(new ParagraphStyle(20, 32, 0, 0, TextAlignment.CENTER))
                .pageLabelStyle(new ParagraphStyle(14, 30, 0, 0, TextAlignment.CENTER))
                .titleColor(RED).entryColor(GREEN).pageLabelColor(BLUE).build();
        var contents = new TableOfContents(true, "Contents", new ParagraphStyle(10, 16, 0, 0, TextAlignment.LEFT), appearance);
        var result = layout(source(), navigation(contents, "Alpha"), fonts);
        var commands = result.contentsPages().getFirst().commands();
        var title = glyph(commands, "Contents"); var entry = glyph(commands, "Alpha"); var label = glyph(commands, "2");
        assertEquals(20, title.run().fontSize()); assertEquals(10, entry.run().fontSize()); assertEquals(14, label.run().fontSize());
        assertEquals(RED, title.color()); assertEquals(GREEN, entry.color()); assertEquals(BLUE, label.color());
        for (var value : List.of(title, entry, label)) { assertEquals(regular.data().id(), value.run().font().id()); }
        assertEquals(PAGE.margins().left() + (PAGE.contentWidth() - title.run().width()) / 2, title.baseline().x(), 0.000001);
        assertEquals(PAGE.margins().left() + PAGE.contentWidth() - 48 + (48 - label.run().width()) / 2, label.baseline().x(), 0.000001);
        var link = result.display().extras().links().getFirst();
        assertEquals(new Rect(12, 56, PAGE.contentWidth(), 30), link.bounds());
        assertEquals(1, link.targetPageIndex());
        assertTrue(glyphs(result.display().pages().getLast().commands()).stream().allMatch(value -> value.color().equals(RgbColor.BLACK)));
    }

    @Test void tallerLabelsReserveRowsBeforePaginationAndFinalFieldsLinksAndRestartedNumbersUseTheNewOffset() {
        var settings = PageSettings.builder().size(240, 180).margins(12).build();
        var format = ParagraphFormat.builder().fontSize(10).lineHeight(15).spaceAfter(0).build();
        var first = new ArrayList<Block>(); var bookmarks = new ArrayList<Navigation.Bookmark>();
        for (int index = 0; index < 8; index++) {
            if (index < 7) { first.add(new Paragraph("p" + index, "Body", format)); }
            bookmarks.add(new Navigation.Bookmark("Entry " + (char) ('A' + index), "p" + index, 1));
        }
        var field = Paragraph.mixed("fields", List.of(new InlineText("page=", InlineStyle.of(10)),
                new InlineField(InlineField.Kind.PAGE, "", 18, InlineStyle.of(10)), new InlineText(" total=", InlineStyle.of(10)),
                new InlineField(InlineField.Kind.PAGES, "", 18, InlineStyle.of(10)), new InlineText(" target=", InlineStyle.of(10)),
                new InlineField(InlineField.Kind.REFERENCE_PAGE, "p7", 18, InlineStyle.of(10))), format);
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("first", settings, first, 0),
                new Section("second", settings, List.of(new Paragraph("p7", "Last", format), field), 9)));
        var base = new TableOfContents(true, "Contents", ParagraphStyle.of(10));
        var tall = base.toBuilder().appearance(value -> value.pageLabelStyle(new ParagraphStyle(10, 40, 0, 0, TextAlignment.RIGHT))).build();
        var oldResult = DocumentEngine.builder(fonts).navigation(new Navigation(bookmarks, base, List.of(), List.of())).build().layout(source);
        var result = DocumentEngine.builder(fonts).navigation(new Navigation(bookmarks, tall, List.of(), List.of())).build().layout(source);
        assertEquals(2, oldResult.bodyPageOffset()); assertEquals(4, result.bodyPageOffset());
        assertEquals(2, result.body().pages().size()); assertEquals(6, result.display().pages().size());
        assertEquals(List.of(4, 4, 4, 4, 4, 4, 4, 5), result.display().extras().bookmarks().stream().map(value -> value.pageIndex()).toList());
        var contentsLinks = result.display().extras().links().stream().filter(value -> value.pageIndex() < 4).toList();
        assertEquals(8, contentsLinks.size()); assertEquals(List.of(4, 4, 4, 4, 4, 4, 4, 5), contentsLinks.stream().map(value -> value.targetPageIndex()).toList());
        assertTrue(contentsLinks.stream().allMatch(value -> value.bounds().height() == 40
                && value.bounds().y() + value.bounds().height() <= settings.size().height() - 12));
        assertEquals(List.of(0, 0, 1, 1, 2, 2, 3, 3), contentsLinks.stream().map(value -> value.pageIndex()).toList());
        var labels = result.contentsPages().stream().flatMap(value -> glyphs(value.commands()).stream())
                .filter(value -> value.run().text().equals("5") || value.run().text().equals("9")).map(value -> value.run().text()).toList();
        assertEquals(List.of("5", "5", "5", "5", "5", "5", "5", "9"), labels);
        var resolved = result.body().pages().getLast().fragments().stream().filter(value -> value.blockId().equals("fields")).findFirst().orElseThrow();
        assertEquals("page=9 total=6 target=9", logical(resolved.layout()));
        assertTrue(resolved.layout().lines().stream().allMatch(line -> line.fields().isEmpty()));
        var reference = result.display().extras().links().getLast(); assertEquals(5, reference.pageIndex()); assertEquals(5, reference.targetPageIndex());
    }

    @Test void aFontWithoutZeroStillLaysOutTheActualPageTwoWithoutAnyArtificialProbeRequest() {
        var zeroRequests = new AtomicInteger(); var actualRequests = new AtomicInteger();
        var face = observedFace('0', codePoint -> { if (codePoint == '0') { zeroRequests.incrementAndGet(); } if (codePoint == '2') { actualRequests.incrementAndGet(); } });
        var group = FontGroup.strict(List.of(face));
        var result = layout(source(), navigation(new TableOfContents(true, "Contents", ParagraphStyle.of(10)), "Alpha"), group);
        assertEquals(0, zeroRequests.get()); assertTrue(actualRequests.get() > 0);
        assertEquals("2", glyph(result.contentsPages().getFirst().commands(), "2").run().text());
        assertTrue(result.contextualDiagnostics().isEmpty());
    }

    @Test void actualTitleEntryAndLabelMissingGlyphWarningsKeepContentsCoordinatesAndFinalTargets() {
        var face = observedFace('2', ignored -> { });
        var group = FontGroup.placeholder(List.of(face), '?');
        var appearance = ContentsStyle.builder().titleColor(RED).entryColor(GREEN).pageLabelColor(BLUE).build();
        var result = layout(source(), navigation(new TableOfContents(true, "A😀", ParagraphStyle.of(10), appearance), "中"), group);
        var warnings = result.contextualDiagnostics().stream().filter(value -> value.diagnostic().severity() == TextDiagnostic.Severity.WARNING).toList();
        assertEquals(3, warnings.size()); assertEquals(Set.of(0x1f600, (int) '中', (int) '2'), warnings.stream().map(value -> value.diagnostic().sourceCodePoint()).collect(Collectors.toSet()));
        assertTrue(warnings.stream().allMatch(value -> value.pageIndex() == 0 && value.region().equals("CONTENTS") && value.blockId().isEmpty()
                && value.diagnostic().replacementCodePoint() == '?' && value.diagnostic().fontId().equals(regular.data().id())));
        var emoji = warnings.stream().filter(value -> value.diagnostic().sourceCodePoint() == 0x1f600).findFirst().orElseThrow();
        assertEquals(1, emoji.diagnostic().sourceStart()); assertEquals(3, emoji.diagnostic().sourceEnd());
        var label = warnings.stream().filter(value -> value.diagnostic().sourceCodePoint() == '2').findFirst().orElseThrow();
        assertEquals(0, label.diagnostic().sourceStart()); assertEquals(1, label.diagnostic().sourceEnd());
        assertTrue(glyphs(result.contentsPages().getFirst().commands()).stream().anyMatch(value -> value.run().text().equals("?") && value.color().equals(BLUE)));
        assertEquals(1, result.display().extras().links().getFirst().targetPageIndex());
        var strict = FontGroup.strict(List.of(face));
        var failure = assertThrows(TextLayoutException.class, () -> layout(source(), navigation(new TableOfContents(true, "Contents", ParagraphStyle.of(10)), "Alpha"), strict));
        assertEquals(TextLayoutException.Code.MISSING_GLYPH, failure.code()); assertEquals(0, failure.sourceIndex());
    }

    @Test void tooWideActualLabelsFailBeforeOutputAndWrappedLabelsCannotEscapeThe48PointColumn() {
        var largePage = PageSettings.builder().size(240, 400).margins(12).build();
        var source = new Document(DocumentMetadata.EMPTY, largePage, source().blocks());
        var appearance = ContentsStyle.builder().pageLabelStyle(new ParagraphStyle(100, 150, 0, 0, TextAlignment.RIGHT)).build();
        var nav = navigation(new TableOfContents(true, "Contents", ParagraphStyle.of(10), appearance), "Alpha");
        var output = new ByteArrayOutputStream(); var called = new AtomicBoolean();
        var failure = assertThrows(TextLayoutException.class, () -> DocumentEngine.builder(fonts).navigation(nav).build().export(source)
                .backend((document, target) -> { called.set(true); target.write(1); }).to(output));
        assertEquals(TextLayoutException.Code.GLYPH_TOO_WIDE, failure.code()); assertEquals(0, output.size()); assertFalse(called.get());
        var wrapped = assertThrows(IllegalArgumentException.class, () -> direct(navigation(new TableOfContents(true, "Contents", ParagraphStyle.of(10)), "Alpha"),
                PAGE, fonts, 1, DocumentLayoutLimits.DEFAULT, ComponentLimits.DEFAULT, (body, offset) -> 123456789));
        assertEquals("contents page label exceeds its reserved column", wrapped.getMessage());
    }

    @Test void invalidTitleOrLabelLineFramesAndRowsTooTallForAFreshPageFailExplicitly() {
        for (var appearance : List.of(ContentsStyle.builder().titleStyle(new ParagraphStyle(24, 1, 0, 0, TextAlignment.LEFT)).build(),
                ContentsStyle.builder().pageLabelStyle(new ParagraphStyle(24, 1, 0, 0, TextAlignment.RIGHT)).build())) {
            var failure = assertThrows(TextLayoutException.class, () -> layout(source(), navigation(new TableOfContents(true, "Contents", ParagraphStyle.of(10), appearance), "Alpha"), fonts));
            assertEquals(TextLayoutException.Code.LINE_HEIGHT_TOO_SMALL, failure.code());
        }
        var tall = ContentsStyle.builder().pageLabelStyle(new ParagraphStyle(10, 500, 0, 0, TextAlignment.RIGHT)).build();
        var overflow = assertThrows(IllegalArgumentException.class, () -> layout(source(), navigation(new TableOfContents(true, "Contents", ParagraphStyle.of(10), tall), "Alpha"), fonts));
        assertEquals("contents entry and title exceed a fresh page", overflow.getMessage());
    }

    @Test void styledRepeatedTitlesActualLabelsAndRowsStayWithinSourceLinePageAndCommandBudgets() {
        var settings = PageSettings.builder().size(200, 100).margins(10).build();
        var appearance = ContentsStyle.builder().titleStyle(ParagraphStyle.of(10)).titleColor(RED).entryColor(GREEN).pageLabelColor(BLUE).build();
        var toc = new TableOfContents(true, "Title", ParagraphStyle.of(10), appearance);
        var bookmarks = List.of(new Navigation.Bookmark("A", "a", 1), new Navigation.Bookmark("B", "b", 1),
                new Navigation.Bookmark("C", "c", 1), new Navigation.Bookmark("D", "d", 1));
        var nav = new Navigation(bookmarks, toc, List.of(), List.of());
        var exact = direct(nav, settings, fonts, 2, new DocumentLayoutLimits(10, 9, 10, 2), new ComponentLimits(100, 10, 100), (body, offset) -> body + offset + 1);
        assertEquals(2, exact.pages().size()); assertEquals(10, exact.traces().stream().mapToInt(value -> value.textLines()).sum());
        assertEquals(10, exact.pages().stream().mapToInt(value -> value.commands().size()).sum());
        assertEquals(4, exact.links().size());
        var source = assertThrows(IllegalArgumentException.class, () -> direct(nav, settings, fonts, 2, new DocumentLayoutLimits(10, 8, 10, 2), ComponentLimits.DEFAULT, (body, offset) -> 3));
        assertEquals("contents source text budget exceeded", source.getMessage());
        var lines = assertThrows(IllegalArgumentException.class, () -> direct(nav, settings, fonts, 2, new DocumentLayoutLimits(10, 9, 9, 2), ComponentLimits.DEFAULT, (body, offset) -> 3));
        assertEquals("contents output line budget exceeded", lines.getMessage());
        var pages = assertThrows(IllegalArgumentException.class, () -> direct(nav, settings, fonts, 1, DocumentLayoutLimits.DEFAULT, ComponentLimits.DEFAULT, (body, offset) -> 2));
        assertEquals("contents page budget exceeded", pages.getMessage());
        var commands = assertThrows(IllegalArgumentException.class, () -> direct(nav, settings, fonts, 2, DocumentLayoutLimits.DEFAULT, new ComponentLimits(100, 9, 100), (body, offset) -> 3));
        assertEquals("contents command budget exceeded", commands.getMessage());
    }

    @Test void cancellationDuringContentsMeasurementRestoresTheScopeAndKeepsTheLayouterReusable() {
        var cancelled = new AtomicBoolean();
        var face = observedFace(-1, codePoint -> { if (codePoint == 'Z') { cancelled.set(true); } });
        var group = FontGroup.strict(List.of(face));
        var nav = new Navigation(List.of(new Navigation.Bookmark("A", "a", 1), new Navigation.Bookmark("Z", "z", 1), new Navigation.Bookmark("B", "b", 1)),
                new TableOfContents(true, "Contents", ParagraphStyle.of(10), ContentsStyle.builder().pageLabelStyle(ParagraphStyle.of(10)).build()), List.of(), List.of());
        var options = ExecutionOptions.builder().cancelled(cancelled::get).build();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options,
                () -> direct(nav, PAGE, group, 10, DocumentLayoutLimits.DEFAULT, ComponentLimits.DEFAULT, (body, offset) -> body + offset + 1)));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code()); assertTrue(cancelled.get());
        ExecutionContext.checkpoint();
        var result = direct(nav, PAGE, group, 10, DocumentLayoutLimits.DEFAULT, ComponentLimits.DEFAULT, (body, offset) -> body + offset + 1);
        assertEquals(3, result.links().size());
        assertEquals(direct(nav, PAGE, fonts, 10, DocumentLayoutLimits.DEFAULT, ComponentLimits.DEFAULT, (body, offset) -> body + offset + 1), result);
    }

    @Test void disabledContentsDoesNotMeasureItsDeclaredTypographyAndKeepsBodyAndBookmarkTargets() {
        var appearance = ContentsStyle.builder().titleStyle(new ParagraphStyle(1000, 1, 0, 0, TextAlignment.LEFT))
                .pageLabelStyle(new ParagraphStyle(1000, 1, 0, 0, TextAlignment.RIGHT)).titleColor(RED).build();
        var nav = navigation(new TableOfContents(false, "", ParagraphStyle.of(10), appearance), "Alpha");
        var result = layout(source(), nav, fonts);
        assertEquals(0, result.bodyPageOffset()); assertTrue(result.contentsPages().isEmpty());
        assertEquals(1, result.display().pages().size()); assertTrue(result.display().extras().links().isEmpty());
        assertEquals(0, result.display().extras().bookmarks().getFirst().pageIndex()); assertTrue(result.contextualDiagnostics().isEmpty());
    }
}
