// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.FontFamily;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.TextStyle;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.SemanticPlacement;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BlockSource;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnFlow;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.GridItem;
import com.mirakyux.export4j.model.GridLayout;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.text.ParagraphLayouter;

class DocumentBlockStreamingTest {
    private static final PageSettings PAGE = new PageSettings(new PageSize(120, 80), PageMargins.uniform(5));
    private static final ParagraphFormat TEXT = new ParagraphFormat(10, 12, 0, 0,
            ParagraphAlignment.LEFT, 0, 0, false);
    private static final ParagraphFormat KEPT = TEXT.withPagination(new PaginationPolicy(true, false, 1, 1));
    private static final String LONG_TEXT = "a\nb\nc\nd\ne\nf\ng\nh";
    private static FontGroup fonts;

    @BeforeAll
    static void loadFont() throws IOException {
        try (var input = DocumentBlockStreamingTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            if (input == null) { throw new IOException("missing test font"); }
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    @Test
    void incrementalPagesMatchCollectedLayoutAndCarryOnlyTheirOriginalRoots() throws IOException {
        var table = Table.builder("table").columns(new ColumnWidth.Fraction(1)).cellFormat(CellFormat.of(10))
                .format(new TableFormat(PageMargins.uniform(1), 0, 0)).header("header");
        for (int row = 0; row < 9; row++) { table.row("row " + row); }
        var sourceTable = table.build();
        var heading = new Heading("heading", "Title", 1, KEPT);
        var columns = new ColumnFlow("columns", List.of(new Paragraph("left", "left", TEXT),
                new Paragraph("right", "right", TEXT)), 2, 4, false);
        var grid = new GridLayout("grid", 1, 0, 0,
                List.of(new GridItem("item", 1, 1, 1, List.of(new Paragraph("grid-text", "grid", TEXT)))));
        List<Block> roots = List.of(heading, sourceTable, new PageBreak("break"), columns, grid,
                new ComponentBlock("component", context -> new ComponentLayout(10, 10, 0, List.of(), List.of())),
                new Paragraph("tail", LONG_TEXT, TEXT));
        java.util.function.IntFunction<List<Rect>> exclusions = page -> page == 0
                ? List.of(new Rect(5, 55, 110, 10)) : List.of();
        var layouter = new DocumentLayouter();
        var expected = layouter.layout(new Document(DocumentMetadata.EMPTY, PAGE, roots), fonts, Map.of(), exclusions).pages();
        var pages = new ArrayList<LayoutPage>();
        var sources = new ArrayList<List<Block>>();
        int count = layouter.layoutBlocks(PAGE, source(roots), fonts, Map.of(), exclusions, (page, original) -> {
            pages.add(page);
            sources.add(original);
            var indices = page.elements().stream().map(element -> element.blockIndex()).distinct().toList();
            assertEquals(indices.size(), original.size());
            for (int index = 0; index < indices.size(); index++) { assertSame(roots.get(indices.get(index)), original.get(index)); }
            assertThrows(UnsupportedOperationException.class, () -> original.add(heading));
        });
        assertEquals(expected, pages);
        assertEquals(pages.size(), count);
        assertSame(heading, sources.getFirst().getFirst());
        assertTrue(sources.stream().filter(values -> values.contains(sourceTable)).count() > 1);
        assertTrue(sources.stream().flatMap(List::stream).noneMatch(PageBreak.class::isInstance));
    }

    @Test
    void consumerFailureStopsWithinTheFirstBlockAndBorrowsTheSource() {
        var reads = new AtomicInteger();
        var closed = new AtomicBoolean();
        var first = new Paragraph("first", LONG_TEXT, TEXT);
        BlockSource source = new BlockSource() {
            @Override public Optional<Block> next() {
                assertEquals(1, reads.incrementAndGet(), "later source must not be read after the callback fails");
                return Optional.of(first);
            }
            @Override public void close() { closed.set(true); }
        };
        var expected = new IOException("consumer stopped");
        var actual = assertThrows(IOException.class, () -> new DocumentLayouter().layoutBlocks(
                PAGE, source, fonts, Map.of(), (page, roots) -> {
                    assertEquals(0, page.pageIndex());
                    assertEquals(List.of(first), roots);
                    throw expected;
                }));
        assertSame(expected, actual);
        assertEquals(1, reads.get());
        assertFalse(closed.get());
    }

    @Test
    void laterSourceIOExceptionRetainsItsIdentityAfterAnEarlierPageWasEmitted() {
        var reads = new AtomicInteger();
        var callbacks = new AtomicInteger();
        var expected = new IOException("source stopped");
        BlockSource source = () -> {
            if (reads.incrementAndGet() == 1) { return Optional.of(new Paragraph("first", LONG_TEXT, TEXT)); }
            throw expected;
        };
        var actual = assertThrows(IOException.class, () -> new DocumentLayouter().layoutBlocks(
                PAGE, source, fonts, Map.of(), (page, roots) -> callbacks.incrementAndGet()));
        assertSame(expected, actual);
        assertEquals(2, reads.get());
        assertEquals(1, callbacks.get());
    }

    @Test
    void keepChainReadsItsSuccessorBeforeMovingButDoesNotReadTheFollowingRoot() {
        List<Block> values = List.of(new Paragraph("filler", "one\ntwo\nthree\nfour", TEXT),
                new Heading("heading", "Title", 1, KEPT), new Paragraph("body", "body", TEXT));
        var reads = new AtomicInteger();
        BlockSource source = () -> {
            int index = reads.getAndIncrement();
            assertTrue(index < values.size(), "unrelated root must not be read before the page callback");
            return Optional.of(values.get(index));
        };
        var expected = new IOException("stop at first page");
        var actual = assertThrows(IOException.class, () -> new DocumentLayouter().layoutBlocks(
                PAGE, source, fonts, Map.of(), (page, roots) -> {
                    assertEquals(3, reads.get());
                    assertEquals(List.of(values.getFirst()), roots);
                    throw expected;
                }));
        assertSame(expected, actual);
    }

    @Test
    void anUnfittableKeepChainStopsBeforeReadingTheRestOfTheSource() {
        var reads = new AtomicInteger();
        BlockSource source = () -> Optional.of(new Paragraph("p-" + reads.incrementAndGet(), "kept", KEPT));
        var failure = assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter().layoutBlocks(
                PAGE, source, fonts, Map.of(), (page, roots) -> { throw new AssertionError("kept chain cannot emit a page"); }));
        assertEquals(DocumentLayoutException.Code.CONTENT_TOO_TALL, failure.code());
        assertEquals(6, reads.get());
    }

    @Test
    void completedRootsStillContributeToTheSharedTextBudgetAndAbsoluteSourceIndex() {
        var callbacks = new AtomicInteger();
        var layouter = new DocumentLayouter(new ParagraphLayouter(), new DocumentLayoutLimits(10, 20, 100, 10));
        var failure = assertThrows(DocumentLayoutException.class, () -> layouter.layoutBlocks(PAGE,
                source(List.of(new Paragraph("first", LONG_TEXT, TEXT), new Paragraph("second", "123456", TEXT))),
                fonts, Map.of(), (page, roots) -> callbacks.incrementAndGet()));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals("second", failure.blockId());
        assertEquals(1, failure.blockIndex());
        assertEquals(1, callbacks.get());
    }

    @Test
    void loadingAnotherRootDoesNotEraseAlreadyChargedStaticComponentText() {
        var command = new DrawCommand.Text("abc", new Point(0, 10), new TextStyle(FontFamily.SANS_SERIF, 10, RgbColor.BLACK));
        var component = new ComponentBlock("component", context -> new ComponentLayout(30, 12, 1, List.of(command), List.of()));
        var layouter = new DocumentLayouter(new ParagraphLayouter(), new DocumentLayoutLimits(10, 5, 100, 10));
        var failure = assertThrows(DocumentLayoutException.class, () -> layouter.layoutBlocks(PAGE,
                source(List.of(component, new Paragraph("second", "def", TEXT))), fonts, Map.of(), (page, roots) -> { }));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals("second", failure.blockId());
        assertEquals(1, failure.blockIndex());
    }

    @Test
    void duplicateIdsRemainInvalidAfterTheirFirstBlockHasFinished() {
        var callbacks = new AtomicInteger();
        var failure = assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter().layoutBlocks(PAGE,
                source(List.of(new Paragraph("duplicate", LONG_TEXT, TEXT), new Paragraph("duplicate", "tail", TEXT))),
                fonts, Map.of(), (page, roots) -> callbacks.incrementAndGet()));
        assertEquals(DocumentLayoutException.Code.INVALID_GEOMETRY, failure.code());
        assertEquals("duplicate", failure.blockId());
        assertEquals(1, failure.blockIndex());
        assertEquals(1, callbacks.get());
    }

    @Test
    void futureRootsCannotReuseAnEarlierComponentsSemanticSourceId() {
        var table = Table.builder("shared").columns(new ColumnWidth.Fraction(1)).cellFormat(CellFormat.of(10))
                .format(new TableFormat(PageMargins.uniform(1), 0, 0)).row("body").build();
        var component = new ComponentBlock("host", context -> {
            var fragment = new DocumentLayouter().prepareTable(table, context).take(context.height()).orElseThrow();
            var placement = new SemanticPlacement(0, table, fragment, context.fontGroup(), context.fontRoles());
            return new ComponentLayout(fragment.width(), fragment.height(), 1, List.of(), List.of(), List.of(placement));
        });
        var failure = assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter().layoutBlocks(PAGE,
                source(List.of(component, new Paragraph("shared", "tail", TEXT))), fonts, Map.of(), (page, roots) -> { }));
        assertEquals(DocumentLayoutException.Code.INVALID_GEOMETRY, failure.code());
        assertEquals("shared", failure.blockId());
        assertEquals(1, failure.blockIndex());
    }

    @Test
    void cancellationAfterSourceReturnStopsBeforeShapingOrReadingAgain() {
        var cancelled = new AtomicBoolean();
        var reads = new AtomicInteger();
        BlockSource source = () -> {
            reads.incrementAndGet();
            cancelled.set(true);
            return Optional.of(new Paragraph("first", "first", TEXT));
        };
        var options = ExecutionOptions.builder().cancelled(cancelled::get).build();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options,
                () -> new DocumentLayouter().layoutBlocks(PAGE, source, fonts, Map.of(),
                        (page, roots) -> { throw new AssertionError("cancelled input must not emit a page"); })));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        assertEquals(1, reads.get());
    }

    @Test
    void emptySourceIsReadOnceAndEmitsAnEmptySourceSnapshot() throws IOException {
        var reads = new AtomicInteger();
        var callbacks = new AtomicInteger();
        int pages = new DocumentLayouter().layoutBlocks(PAGE, () -> {
            assertEquals(1, reads.incrementAndGet());
            return Optional.empty();
        }, fonts, Map.of(), (page, roots) -> {
            callbacks.incrementAndGet();
            assertTrue(page.elements().isEmpty());
            assertTrue(roots.isEmpty());
        });
        assertEquals(1, pages);
        assertEquals(1, callbacks.get());
    }

    @Test
    void unsupportedRootFailsExplicitlyWithItsAbsoluteIndex() {
        Block unsupported = () -> "unsupported";
        var failure = assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter().layoutBlocks(PAGE,
                source(List.of(new PageBreak("break"), unsupported)), fonts, Map.of(), (page, roots) -> { }));
        assertEquals(DocumentLayoutException.Code.UNSUPPORTED_COMPONENT, failure.code());
        assertEquals("unsupported", failure.blockId());
        assertEquals(1, failure.blockIndex());
    }

    private static BlockSource source(List<Block> values) {
        var iterator = values.iterator();
        return () -> iterator.hasNext() ? Optional.of(iterator.next()) : Optional.empty();
    }
}
