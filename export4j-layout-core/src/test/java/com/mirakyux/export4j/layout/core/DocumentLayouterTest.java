// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontMetrics;
import com.mirakyux.export4j.font.GlyphMetrics;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.layout.DocumentLayout;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.text.GlyphRun;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.SimpleTextShaper;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextLayoutException;
import com.mirakyux.export4j.text.TextLayoutLimits;
import com.mirakyux.export4j.text.TextLine;

class DocumentLayouterTest {
    private static FontFace latin;
    private static FontFace chinese;
    private static FontFace synthetic;
    private static final ParagraphFormat FORMAT = format(10, 0, 0, false);

    @BeforeAll
    static void loadFonts() throws IOException {
        latin = load("LiberationSans-Regular.ttf");
        chinese = load("LXGWWenKaiTC-Regular.ttf");
        synthetic = new FontFace() {
            @Override
            public FontData data() {
                return latin.data();
            }

            @Override
            public FontMetrics metrics() {
                return new FontMetrics(0.7, 0.2, 0);
            }

            @Override
            public Optional<GlyphMetrics> glyph(int codePoint) {
                return Optional.of(new GlyphMetrics(1, 1));
            }
        };
    }

    @Test
    void splitsLongParagraphIntoContiguousSourceLinesWithinMargins() {
        String source = "0123456789ABCDEFGHIJKLMNOPQRSTUV";
        DocumentLayout layout = layout(settings(80, 30), new Paragraph("long", source, FORMAT));
        assertEquals(2, layout.pages().size());
        assertEquals(List.of(0, 3), fragments(layout).stream().map(ParagraphFragment::firstLineIndex).toList());
        assertEquals(List.of("01234567", "89ABCDEF", "GHIJKLMN", "OPQRSTUV"), texts(layout));
        assertEquals(List.of(0, 8, 16, 24), lines(layout).stream().map(TextLine::sourceStart).toList());
        assertEquals(List.of(8, 16, 24, 32), lines(layout).stream().map(TextLine::sourceEnd).toList());
        assertEquals(17, layout.pages().get(1).fragments().getFirst().origin().y()
                + lines(layout).getLast().baselineY());
        assertContained(layout);
    }

    @Test
    void continuesIndentAndAlignmentWithoutRewrappingAtPageBreak() {
        for (ParagraphAlignment alignment : ParagraphAlignment.values()) {
            ParagraphFormat style = new ParagraphFormat(10, 10, 20, 10, alignment, 0, 0, false);
            DocumentLayout layout = layout(settings(80, 20), new Paragraph("p", "A\nB\nC", style));
            assertEquals(2, layout.pages().size());
            List<Double> expected = switch (alignment) {
                case LEFT, JUSTIFY -> List.of(20.0, 10.0, 10.0);
                case CENTER -> List.of(45.0, 40.0, 40.0);
                case RIGHT -> List.of(70.0, 70.0, 70.0);
            };
            assertEquals(expected, lines(layout).stream().map(TextLine::offsetX).toList());
            assertEquals(List.of(7.0, 17.0), layout.pages().getFirst().fragments().getFirst().layout().lines()
                    .stream().map(TextLine::baselineY).toList());
            assertEquals(7, layout.pages().get(1).fragments().getFirst().layout().lines().getFirst().baselineY());
            assertContained(layout);
        }
    }

    @Test
    void keepsParagraphTogetherByMovingItToNextPage() {
        DocumentLayout layout = layout(settings(80, 30),
                new Paragraph("first", "A\nB", FORMAT),
                new Paragraph("keep", "C\nD", format(10, 0, 0, true)));
        assertEquals(2, layout.pages().size());
        assertEquals("keep", layout.pages().get(1).fragments().getFirst().paragraphId());
        assertEquals(0, layout.pages().get(1).fragments().getFirst().firstLineIndex());
        assertEquals(2, layout.pages().get(1).fragments().getFirst().layout().lines().size());
        assertEquals(List.of("A", "B", "C", "D"), texts(layout));
    }

    @Test
    void addsSpacingBetweenParagraphsAndSuppressesSpacingAtPageEdges() {
        DocumentLayout layout = layout(settings(80, 60),
                new Paragraph("a", "A", format(10, 100, 4, false)),
                new Paragraph("b", "B", format(10, 5, 100, false)));
        assertEquals(1, layout.pages().size());
        assertEquals(List.of(10.0, 29.0), fragments(layout).stream().map(f -> f.origin().y()).toList());
        DocumentLayout wrapped = layout(settings(80, 30),
                new Paragraph("a", "A", format(10, 0, Double.MAX_VALUE, false)),
                new Paragraph("b", "B", format(10, Double.MAX_VALUE, Double.MAX_VALUE, false)));
        assertEquals(2, wrapped.pages().size());
        assertEquals(List.of(10.0, 10.0), fragments(wrapped).stream().map(f -> f.origin().y()).toList());
    }

    @Test
    void emptyDocumentProducesOneBlankPageAndEmptyParagraphConsumesOneLine() {
        DocumentLayout empty = layout(settings(80, 30));
        assertEquals(1, empty.pages().size());
        assertTrue(empty.pages().getFirst().fragments().isEmpty());
        DocumentLayout paragraphs = layout(settings(80, 20),
                new Paragraph("blank", "", FORMAT), new Paragraph("body", "A\nB", FORMAT));
        assertEquals(2, paragraphs.pages().size());
        assertEquals(List.of("", "A", "B"), texts(paragraphs));
        assertEquals(2, paragraphs.pages().get(1).fragments().getFirst().paragraphIndex() + 1);
    }

    @Test
    void preservesExplicitEmptyAndTrailingLinesAcrossPages() {
        DocumentLayout layout = layout(settings(80, 30), new Paragraph("p", "A\r\n\nB\r", FORMAT));
        assertEquals(2, layout.pages().size());
        assertEquals(List.of("A", "", "B", ""), texts(layout));
        assertEquals(List.of(0, 3, 4, 6), lines(layout).stream().map(TextLine::sourceStart).toList());
        assertEquals(List.of(1, 3, 5, 6), lines(layout).stream().map(TextLine::sourceEnd).toList());
    }

    @Test
    void movesWholeLinesToNextPageWithoutCreatingTrailingEmptyPages() {
        DocumentLayout exact = layout(settings(80, 30),
                new Paragraph("p", "A\nB\nC", format(10, 0, 100, false)));
        assertEquals(1, exact.pages().size());
        DocumentLayout next = layout(settings(80, 30),
                new Paragraph("p", "A\nB\nC", FORMAT), new Paragraph("q", "D", FORMAT));
        assertEquals(2, next.pages().size());
        assertFalse(next.pages().getLast().fragments().isEmpty());
    }

    @Test
    void toleratesOnlyRoundingAtDecimalLineFitBoundaries() {
        ParagraphFormat tiny = new ParagraphFormat(0.01, 0.1, 0, 0, ParagraphAlignment.LEFT, 0, 0, false);
        DocumentLayout exact = layout(new PageSettings(new PageSize(80, 0.3), PageMargins.uniform(0)),
                new Paragraph("p", "A\nB\nC", tiny));
        assertEquals(1, exact.pages().size());
        DocumentLayout below = layout(new PageSettings(new PageSize(80, 0.3 - 1e-8), PageMargins.uniform(0)),
                new Paragraph("p", "A\nB\nC", tiny));
        assertEquals(2, below.pages().size());
        assertContained(exact);
        assertContained(below);
    }

    @Test
    void failsImmediatelyForTooTallLineOrKeptParagraph() {
        for (Paragraph paragraph : List.of(new Paragraph("line", "A", format(40, 0, 0, false)),
                new Paragraph("keep", "A\nB\nC\nD", format(10, 0, 0, true)))) {
            DocumentLayoutException failure = assertThrows(DocumentLayoutException.class,
                    () -> layout(settings(80, 30), paragraph));
            assertEquals(DocumentLayoutException.Code.CONTENT_TOO_TALL, failure.code());
            assertEquals(paragraph.id(), failure.paragraphId());
            assertEquals(0, failure.pageIndex());
        }
    }

    @Test
    void appliesDocumentParagraphCodePointLineAndPageBudgets() {
        Paragraph a = new Paragraph("a", "A\nB", FORMAT);
        Paragraph b = new Paragraph("b", "C\nD", FORMAT);
        Document document = document(settings(80, 30), a, b);
        assertBudget(document, new DocumentLayoutLimits(1, 100, 100, 10), "", -1, -1);
        assertBudget(document, new DocumentLayoutLimits(10, 5, 100, 10), "b", 1, -1);
        assertBudget(document, new DocumentLayoutLimits(10, 100, 3, 10), "b", 1, 0);
        DocumentLayoutException pages = assertBudget(document,
                new DocumentLayoutLimits(10, 100, 100, 1), "b", 1, 1);
        assertEquals(2, pages.sourceIndex());
    }

    @Test
    void documentBudgetCountsSupplementaryCharactersAsCodePoints() {
        DocumentLayouter layouter = new DocumentLayouter(new ParagraphLayouter(),
                new DocumentLayoutLimits(1, 2, 10, 1));
        var result = layouter.layout(document(settings(80, 30),
                new Paragraph("p", "A\uD83D\uDE00", FORMAT)), List.of(synthetic));
        assertEquals(List.of("A\uD83D\uDE00"), texts(result));
    }

    @Test
    void wrapsTextFailureWithParagraphPageAndOriginalSourceContext() {
        DocumentLayoutException failure = assertThrows(DocumentLayoutException.class,
                () -> new DocumentLayouter(new ParagraphLayouter(new SimpleTextShaper(), TextLayoutLimits.DEFAULT), DocumentLayoutLimits.DEFAULT)
                        .layout(document(settings(80, 30), new Paragraph("private-id", "A\n\u0628", FORMAT)), List.of(synthetic)));
        assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, failure.code());
        assertEquals("private-id", failure.paragraphId());
        assertEquals(0, failure.paragraphIndex());
        assertEquals(0, failure.pageIndex());
        assertEquals(2, failure.sourceIndex());
        TextLayoutException cause = assertInstanceOf(TextLayoutException.class, failure.getCause());
        assertEquals(TextLayoutException.Code.UNSUPPORTED_SHAPING, cause.code());
        assertFalse(failure.getMessage().contains("\u0628"));
    }

    @Test
    void preservesPerParagraphBudgetsAndGeometryFailures() {
        DocumentLayouter layouter = new DocumentLayouter(
                new ParagraphLayouter(new SimpleTextShaper(), new TextLayoutLimits(1, 10)),
                DocumentLayoutLimits.DEFAULT);
        DocumentLayoutException bounded = assertThrows(DocumentLayoutException.class,
                () -> layouter.layout(document(settings(80, 30), new Paragraph("p", "AB", FORMAT)),
                        List.of(synthetic)));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, bounded.code());
        assertInstanceOf(TextLayoutException.class, bounded.getCause());
        ParagraphFormat indented = new ParagraphFormat(10, 10, 80, 0, ParagraphAlignment.LEFT, 0, 0, false);
        DocumentLayoutException geometry = assertThrows(DocumentLayoutException.class,
                () -> layout(settings(80, 30), new Paragraph("p", "A", indented)));
        assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, geometry.code());
        assertEquals(-1, geometry.sourceIndex());
    }

    @Test
    void assignsFallbackDiagnosticsToOriginalNodeAndCorrectPageFragment() {
        var layout = new DocumentLayouter().layout(document(settings(80, 30),
                new Paragraph("mixed", "中\n文\n测\n试", format(15, 0, 0, false))), List.of(latin, chinese));
        assertEquals(2, layout.pages().size());
        assertEquals(List.of(0, 2), layout.pages().getFirst().fragments().getFirst().layout().diagnostics()
                .stream().map(TextDiagnostic::sourceStart).toList());
        assertEquals(List.of(4, 6), layout.pages().getLast().fragments().getFirst().layout().diagnostics()
                .stream().map(TextDiagnostic::sourceStart).toList());
        assertTrue(fragments(layout).stream().allMatch(fragment -> fragment.paragraphId().equals("mixed")));
        assertContained(layout);
    }

    @Test
    void painterConsumesExistingFragmentsAndPositionsWithoutChangingLayout() {
        DocumentLayout layout = layout(settings(80, 20), new Paragraph("p", "AB\nCD\nEF", FORMAT));
        var display = DocumentPainter.paint(layout);
        assertEquals(layout.pages().size(), display.pages().size());
        assertEquals(layout.metadata(), display.metadata());
        for (int page = 0; page < layout.pages().size(); page++) {
            ParagraphFragment fragment = layout.pages().get(page).fragments().getFirst();
            var commands = display.pages().get(page).commands();
            assertEquals(fragment.layout().lines().size(), commands.size());
            for (int line = 0; line < commands.size(); line++) {
                DrawCommand.GlyphText command = assertInstanceOf(DrawCommand.GlyphText.class, commands.get(line));
                assertEquals(fragment.origin().x() + fragment.layout().lines().get(line).offsetX(),
                        command.baseline().x());
                assertEquals(fragment.origin().y() + fragment.layout().lines().get(line).baselineY(),
                        command.baseline().y());
            }
        }
    }

    @Test
    void layoutSnapshotsAreImmutableRepeatableAndConcurrentCallsAreIsolated() throws Exception {
        Document document = document(settings(80, 30), new Paragraph("p", "A\nB\nC\nD", FORMAT));
        DocumentLayouter layouter = new DocumentLayouter();
        DocumentLayout expected = layouter.layout(document, List.of(synthetic));
        assertThrows(UnsupportedOperationException.class, () -> expected.pages().clear());
        assertThrows(UnsupportedOperationException.class, () -> expected.pages().getFirst().fragments().clear());
        List<Callable<DocumentLayout>> tasks = IntStream.range(0, 4)
                .<Callable<DocumentLayout>>mapToObj(index -> () -> layouter.layout(document, List.of(synthetic)))
                .toList();
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (var result : executor.invokeAll(tasks)) {
                assertEquals(expected, result.get());
            }
        }
    }

    private static DocumentLayoutException assertBudget(Document document, DocumentLayoutLimits limits,
            String id, int paragraph, int page) {
        DocumentLayoutException failure = assertThrows(DocumentLayoutException.class,
                () -> new DocumentLayouter(new ParagraphLayouter(), limits).layout(document, List.of(synthetic)));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals(id, failure.paragraphId());
        assertEquals(paragraph, failure.paragraphIndex());
        assertEquals(page, failure.pageIndex());
        return failure;
    }

    private static void assertContained(DocumentLayout layout) {
        for (var page : layout.pages()) {
            var bounds = page.contentBounds();
            for (var fragment : page.fragments()) {
                assertTrue(fragment.origin().x() >= bounds.x());
                assertTrue(fragment.origin().y() >= bounds.y());
                assertTrue(fragment.origin().x() + fragment.layout().width() <= bounds.x() + bounds.width() + 1e-9);
                assertTrue(fragment.origin().y() + fragment.layout().height() <= bounds.y() + bounds.height() + 1e-9);
            }
        }
    }

    private static List<ParagraphFragment> fragments(DocumentLayout layout) {
        return layout.pages().stream().flatMap(page -> page.fragments().stream()).toList();
    }

    private static List<TextLine> lines(DocumentLayout layout) {
        return fragments(layout).stream().flatMap(fragment -> fragment.layout().lines().stream()).toList();
    }

    private static List<String> texts(DocumentLayout layout) {
        return lines(layout).stream().map(line ->
                line.runs().stream().map(GlyphRun::text).reduce("", String::concat)).toList();
    }

    private static ParagraphFormat format(double lineHeight, double before, double after, boolean keep) {
        return new ParagraphFormat(10, lineHeight, 0, 0, ParagraphAlignment.LEFT, before, after, keep);
    }

    private static PageSettings settings(double contentWidth, double contentHeight) {
        return new PageSettings(new PageSize(contentWidth + 20, contentHeight + 20), PageMargins.uniform(10));
    }

    private static Document document(PageSettings settings, Paragraph... paragraphs) {
        return new Document(new DocumentMetadata("Flow", "Export4j"), settings, List.of(paragraphs));
    }

    private static DocumentLayout layout(PageSettings settings, Paragraph... paragraphs) {
        return new DocumentLayouter().layout(document(settings, paragraphs), List.of(synthetic));
    }

    private static FontFace load(String name) throws IOException {
        try (InputStream input = DocumentLayouterTest.class.getResourceAsStream("/fonts/" + name)) {
            if (input == null) {
                throw new IOException("Missing test font");
            }
            return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
    }

    @Test
    void balancedPaginationLeavesTwoTailLinesAndPreservesSourceOrder() {
        var format = FORMAT.withPagination(com.mirakyux.export4j.model.PaginationPolicy.BALANCED);
        var result = layout(settings(80, 40), new Paragraph("p", "A\nB\nC\nD\nE", format));
        assertEquals(List.of(3, 2), fragments(result).stream().map(f -> f.layout().lines().size()).toList());
        assertEquals(List.of("A", "B", "C", "D", "E"), texts(result));
        var four = layout(settings(80, 30), new Paragraph("p", "A\nB\nC\nD", format));
        assertEquals(List.of(2, 2), fragments(four).stream().map(f -> f.layout().lines().size()).toList());
        assertEquals(1, layout(settings(80, 10), new Paragraph("short", "A", format)).pages().size());
    }

    @Test
    void impossibleBalancedFragmentFailsWithoutRetryingBlankPages() {
        var failure = assertThrows(DocumentLayoutException.class, () -> layout(settings(80, 10),
                new Paragraph("p", "A\nB\nC", FORMAT.withPagination(com.mirakyux.export4j.model.PaginationPolicy.BALANCED))));
        assertEquals(DocumentLayoutException.Code.CONTENT_TOO_TALL, failure.code());
        assertEquals(0, failure.pageIndex());
    }

    @Test
    void keepChainReservesIndivisibleShortFollowerAndMovesHeading() {
        var keep = FORMAT.withPagination(new com.mirakyux.export4j.model.PaginationPolicy(true, false, 1, 1));
        var result = layout(settings(80, 50), new Paragraph("prior", "A\nB", FORMAT), new Paragraph("heading", "H", keep),
                new Paragraph("follower", "C\nD\nE", FORMAT.withPagination(com.mirakyux.export4j.model.PaginationPolicy.BALANCED)));
        assertEquals(List.of("prior"), result.pages().getFirst().elements().stream().map(f -> f.blockId()).toList());
        assertEquals(List.of("heading", "follower"), result.pages().get(1).elements().stream().map(f -> f.blockId()).toList());
    }

    @Test
    void explicitBreaksOverrideKeepAndNeverProduceTrailingEmptyPages() {
        var keep = FORMAT.withPagination(new com.mirakyux.export4j.model.PaginationPolicy(true, false, 1, 1));
        var doc = new Document(new DocumentMetadata("Breaks", "Export4j"), settings(80, 50), List.of(
                new com.mirakyux.export4j.model.PageBreak("start"), new Paragraph("a", "A", keep),
                new com.mirakyux.export4j.model.PageBreak("middle"), new com.mirakyux.export4j.model.PageBreak("repeat"),
                new Paragraph("b", "B", FORMAT), new com.mirakyux.export4j.model.PageBreak("end")));
        var result = new DocumentLayouter().layout(doc, List.of(synthetic));
        assertEquals(2, result.pages().size());
        assertEquals(List.of("A", "B"), texts(result));
        assertEquals(List.of(1, 4), fragments(result).stream().map(ParagraphFragment::blockIndex).toList());
    }

    @Test
    void richTextUsesRegisteredVariantAndRetainsColorShiftAndSourceAcrossPages() {
        var regular = com.mirakyux.export4j.model.InlineStyle.of(10);
        var red = new com.mirakyux.export4j.model.InlineStyle("emphasis", 8, 0xcc2200, -2, true, false);
        var paragraph = new Paragraph("rich", List.of(new com.mirakyux.export4j.model.InlineText("AB\n", regular),
                new com.mirakyux.export4j.model.InlineText("CD", red)), FORMAT);
        var doc = new Document(new DocumentMetadata("Rich", "Export4j"), settings(80, 10), List.of(paragraph));
        var missingRole = assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter().layout(doc, List.of(synthetic)));
        assertEquals("rich", missingRole.blockId());
        assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, missingRole.code());
        assertEquals(3, missingRole.sourceIndex());
        var utf16 = new Paragraph("utf16", List.of(
                new com.mirakyux.export4j.model.InlineText("A😀", regular),
                new com.mirakyux.export4j.model.InlineText("D", red)), FORMAT);
        var utf16Document = new Document(doc.metadata(), doc.pageSettings(), List.of(utf16));
        var utf16Failure = assertThrows(DocumentLayoutException.class,
                () -> new DocumentLayouter().layout(utf16Document, List.of(synthetic)));
        assertEquals(3, utf16Failure.sourceIndex());
        var result = new DocumentLayouter().layout(doc, List.of(synthetic), java.util.Map.of("emphasis", List.of(synthetic)));
        assertEquals(2, result.pages().size());
        assertEquals(List.of(0, 3), lines(result).stream().map(TextLine::sourceStart).toList());
        var run = lines(result).get(1).runs().getFirst();
        assertEquals(0xcc2200, run.rgb());
        assertEquals(-2, run.baselineShift());
        var commands = DocumentPainter.paint(result).pages().get(1).commands();
        assertEquals(2, commands.size());
        var glyph = assertInstanceOf(DrawCommand.GlyphText.class, commands.getFirst());
        assertEquals(new com.mirakyux.export4j.graphics.RgbColor(0.8, 34 / 255.0, 0), glyph.color());
        assertEquals(15.6, glyph.baseline().y(), 1e-9);
    }
}
