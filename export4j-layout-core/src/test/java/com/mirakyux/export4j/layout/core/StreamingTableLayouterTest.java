// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.MissingGlyphPolicy;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.layout.DocumentLayout;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.TableRowFragment;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextLayoutException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingTableLayouterTest {
    private static FontGroup fonts;
    private static final CellFormat FORMAT = CellFormat.of(10);
    private static final PageSettings PAGE = new PageSettings(new PageSize(140, 80), PageMargins.uniform(10));

    @BeforeAll static void fonts() throws IOException {
        try (var input = StreamingTableLayouterTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    private static StreamingTable table() {
        return StreamingTable.builder("stream").pageSettings(PAGE).columns(100).padding(0).cellFormat(FORMAT).header("HEAD").build();
    }
    private static TableRow row(String text) { return new TableRow(List.of(new TableCell(text, FORMAT))); }
    private static TableCell span(String text, int columns) {
        return TableCell.builder().text(text).format(FORMAT).columnSpan(columns).build();
    }
    private static List<TableRowFragment> rows(LayoutPage page) { return ((TableFragment) page.elements().getFirst()).rows(); }
    private static String text(TableRowFragment row) {
        return row.cells().getFirst().layout().lines().stream().flatMap(line -> line.runs().stream()).map(run -> run.text()).collect(java.util.stream.Collectors.joining());
    }
    private static FontGroup replacements() {
        return FontGroup.builder().fonts(fonts.fonts()).missingGlyphPolicy(MissingGlyphPolicy.PLACEHOLDER_WITH_WARNING).placeholderCodePoint('?').build();
    }

    @Test void horizontalSpansRetainPhysicalColumnsHeaderReuseAndSingleRowLookahead() throws IOException {
        var spec = StreamingTable.builder("spans").pageSettings(PAGE).columns(30, 40, 50).padding(0)
                .header(new TableRow(List.of(span("MERGED HEADER", 3)))).build();
        var source = new Source(7, index -> new TableRow(switch (index % 3) {
            case 1 -> List.of(span("MERGED" + index, 2), span("TAIL" + index, 1));
            case 2 -> List.of(span("A" + index, 1), span("B" + index, 1), span("C" + index, 1));
            default -> List.of(span("WIDTH-SHARED" + index, 3));
        }));
        var pages = new ArrayList<LayoutPage>();
        var readCounts = new ArrayList<Integer>();
        var result = new StreamingTableLayouter().layout(spec, source, fonts, page -> {
            pages.add(page); readCounts.add(source.reads);
        }, diagnostic -> { throw new AssertionError("unexpected diagnostic"); }, StreamingTableLimits.DEFAULT);
        assertEquals(3, result.pages()); assertEquals(7, result.bodyRows()); assertEquals(15, result.sourceCells());
        assertEquals(List.of(4, 7, 8), readCounts); assertEquals(0, source.closes);
        for (var page : pages) {
            var header = rows(page).getFirst().cells().getFirst();
            assertEquals(120, header.bounds().width());
            assertSame(rows(pages.getFirst()).getFirst().cells().getFirst().layout(), header.layout());
            for (var row : rows(page)) {
                for (var cell : row.cells()) { assertEquals(1, cell.layout().lines().size()); }
                if (row.rowIndex() % 3 == 1) {
                    assertEquals(List.of(0, 2), row.cells().stream().map(value -> value.columnIndex()).toList());
                    assertEquals(70, row.cells().getFirst().bounds().width());
                    assertEquals(70, row.cells().getLast().bounds().x());
                    assertEquals(50, row.cells().getLast().bounds().width());
                }
            }
        }
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), pages.stream().flatMap(page -> rows(page).stream())
                .filter(row -> !row.header()).map(TableRowFragment::rowIndex).toList());
    }

    @Test void mergedStreamingCellsKeepPhysicalDiagnosticColumnsAndUtf16Offsets() {
        var spec = StreamingTable.builder("spans").pageSettings(PAGE).columns(30, 40, 50).padding(0).build();
        var source = new Source(1, index -> new TableRow(List.of(span("OK", 2), span("A😀", 1))));
        var failure = assertThrows(DocumentLayoutException.class, () -> layout(spec, source, new ArrayList<>(), StreamingTableLimits.DEFAULT));
        assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, failure.code());
        assertEquals(0, failure.rowIndex()); assertEquals(2, failure.columnIndex()); assertEquals(1, failure.sourceIndex());
        assertEquals(1, source.reads);
    }

    @Test void coverageBudgetsCountMergedSlotsWhileSourceCountsCountOnlyAnchors() throws IOException {
        var spec = StreamingTable.builder("spans").pageSettings(PAGE).columns(30, 40, 50).padding(0).build();
        var limits = StreamingTableLimits.DEFAULT.toBuilder().document(new DocumentLayoutLimits(10, 1_000, 100, 10, 100, 3, 2)).build();
        var source = new Source(1, index -> new TableRow(List.of(span("WIDE", 3))));
        var failure = assertThrows(DocumentLayoutException.class, () -> layout(spec, source, new ArrayList<>(), limits));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code()); assertEquals(0, failure.columnIndex());
        assertEquals(1, source.reads);
        var accepted = layout(spec, new Source(1, index -> new TableRow(List.of(span("WIDE", 3)))), new ArrayList<>(),
                limits.toBuilder().document(new DocumentLayoutLimits(10, 1_000, 100, 10, 100, 3, 3)).build());
        assertEquals(1, accepted.sourceCells()); assertEquals(4, accepted.sourceCodePoints());
        for (int count : List.of(2, 4, Integer.MAX_VALUE)) {
            var invalid = new Source(1, index -> new TableRow(List.of(span("WIDE", count))));
            assertEquals(DocumentLayoutException.Code.INVALID_GEOMETRY, assertThrows(DocumentLayoutException.class,
                    () -> layout(spec, invalid, new ArrayList<>(), StreamingTableLimits.DEFAULT)).code());
        }
        var vertical = new Source(1, index -> new TableRow(List.of(span("WIDE", 3).toBuilder().rowSpan(2).build())));
        assertEquals(DocumentLayoutException.Code.UNSUPPORTED_COMPONENT, assertThrows(DocumentLayoutException.class,
                () -> layout(spec, vertical, new ArrayList<>(), StreamingTableLimits.DEFAULT)).code());
    }
    private static final class Source implements TableRowSource {
        private final int size;
        private final IntFunction<TableRow> values;
        private int reads;
        private int closes;
        Source(int size) { this(size, index -> row("ROW" + index)); }
        Source(int size, IntFunction<TableRow> values) { this.size = size; this.values = values; }
        @Override public Optional<TableRow> next() {
            reads++;
            return reads <= size ? Optional.of(values.apply(reads)) : Optional.empty();
        }
        @Override public void close() { closes++; }
    }
    private static StreamingTableLayouter.Summary layout(StreamingTable table, Source source, List<LayoutPage> pages,
            StreamingTableLimits limits) throws IOException {
        return new StreamingTableLayouter().layout(table, source, fonts, pages::add, diagnostic -> { }, limits);
    }
    private static DocumentLayoutLimits document(long points, long lines, int pages, int rows, int columns, long cells) {
        return new DocumentLayoutLimits(1, points, lines, pages, rows, columns, cells);
    }

    @Test void lazyPagesRetainSourceOrderHeaderIdentityAndBoundedSummary() throws IOException {
        var source = new Source(8);
        var pages = new ArrayList<LayoutPage>();
        var summary = new StreamingTableLayouter().layout(table(), source, fonts, page -> {
            if (page.pageIndex() == 0) { assertEquals(4, source.reads); }
            pages.add(page);
        }, diagnostic -> { }, StreamingTableLimits.DEFAULT);
        assertEquals(new StreamingTableLayouter.Summary(8, 3, 9, 36, 11, 0), summary);
        assertEquals(9, source.reads);
        assertEquals(0, source.closes);
        assertEquals(List.of(0, 1, 2), pages.stream().map(LayoutPage::pageIndex).toList());
        assertEquals(IntStream.rangeClosed(1, 8).boxed().toList(), pages.stream().flatMap(page -> rows(page).stream()).filter(row -> !row.header()).map(TableRowFragment::rowIndex).toList());
        assertEquals(IntStream.rangeClosed(1, 8).mapToObj(index -> "ROW" + index).toList(), pages.stream().flatMap(page -> rows(page).stream()).filter(row -> !row.header()).map(StreamingTableLayouterTest::text).toList());
        for (var page : pages) {
            var header = rows(page).getFirst();
            assertEquals(0, header.rowIndex()); assertTrue(header.header());
            assertEquals(page.pageIndex() > 0, header.repeatedHeader());
            assertSame(rows(pages.getFirst()).getFirst().cells().getFirst().layout(), header.cells().getFirst().layout());
            for (var row : rows(page)) {
                var cell = row.cells().getFirst();
                assertEquals(0, cell.firstLineIndex());
                assertEquals(0, cell.layout().lines().getFirst().sourceStart());
                assertTrue(cell.bounds().y() + cell.bounds().height() <= PAGE.contentHeight());
            }
        }
        assertEquals(10, ((TableFragment) pages.getFirst().elements().getFirst()).origin().x());
        assertEquals(100, ((TableFragment) pages.getFirst().elements().getFirst()).width());
    }

    @Test void arbitraryLargeGeneratedSourceIsNotReadAheadBeyondOnePendingRow() throws IOException {
        var source = new Source(100_000);
        var sentinel = new IOException("sink failure");
        var failure = assertThrows(IOException.class, () -> new StreamingTableLayouter().layout(table(), source, fonts,
                page -> { assertEquals(0, page.pageIndex()); assertEquals(4, source.reads); throw sentinel; }, diagnostic -> { }, StreamingTableLimits.DEFAULT));
        assertSame(sentinel, failure); assertEquals(4, source.reads); assertEquals(0, source.closes);
    }

    @Test void emptySourcesEmitExactlyOnePageWithOrWithoutHeaders() throws IOException {
        for (var spec : List.of(table(), table().toBuilder().headerRows(List.of()).build())) {
            var source = new Source(0); var pages = new ArrayList<LayoutPage>();
            var summary = layout(spec, source, pages, StreamingTableLimits.DEFAULT);
            assertEquals(1, summary.pages()); assertEquals(0, summary.bodyRows()); assertEquals(1, source.reads);
            assertEquals(spec.headerRows().isEmpty(), pages.getFirst().elements().isEmpty());
        }
    }

    @Test void exactFullPageDoesNotProduceTrailingBlankPage() throws IOException {
        var pages = new ArrayList<LayoutPage>();
        var summary = layout(table(), new Source(3), pages, StreamingTableLimits.DEFAULT);
        assertEquals(1, summary.pages()); assertEquals(4, summary.outputLines());
        assertEquals(60, ((TableFragment) pages.getFirst().elements().getFirst()).height());
    }

    @Test void fractionalLineHeightsFitAFullPageDespiteSubtractionRounding() throws IOException {
        var format = new CellFormat(10, 22.2, ParagraphAlignment.LEFT);
        var settings = new PageSettings(new PageSize(100, 66.6), PageMargins.uniform(0));
        for (int headerCount : List.of(1, 2)) {
            var builder = StreamingTable.builder("fraction").pageSettings(settings).columns(100).padding(0).cellFormat(format);
            for (int index = 0; index < headerCount; index++) { builder.header("HEAD" + index); }
            var source = new Source(3 - headerCount, index -> new TableRow(List.of(new TableCell("ROW" + index, format))));
            var pages = new ArrayList<LayoutPage>(); var summary = layout(builder.build(), source, pages, StreamingTableLimits.DEFAULT);
            assertEquals(1, summary.pages()); assertEquals(3, summary.outputLines());
            assertEquals(3, rows(pages.getFirst()).size());
            assertTrue(((TableFragment) pages.getFirst().elements().getFirst()).height() <= Math.nextUp(66.6));
        }
    }

    @Test void fractionalHeaderSumAllowsOneUlpAndStillRejectsRealOverflow() throws IOException {
        var settings = new PageSettings(new PageSize(100, .3), PageMargins.uniform(0));
        var first = new TableRow(List.of(new TableCell("A", new CellFormat(.01, .1, ParagraphAlignment.LEFT))));
        var second = new TableRow(List.of(new TableCell("B", new CellFormat(.01, .2, ParagraphAlignment.LEFT))));
        var spec = StreamingTable.builder("fraction").pageSettings(settings).columns(100).padding(0).header(first).header(second).build();
        var pages = new ArrayList<LayoutPage>();
        assertEquals(1, layout(spec, new Source(0), pages, StreamingTableLimits.DEFAULT).pages());
        assertEquals(Math.nextUp(.3), ((TableFragment) pages.getFirst().elements().getFirst()).height());
        var overflow = new TableRow(List.of(new TableCell("B", new CellFormat(.01, Math.nextUp(Math.nextUp(Math.nextUp(.2))), ParagraphAlignment.LEFT))));
        var badHeaders = spec.toBuilder().headerRows(List.of(first, overflow)).build();
        var source = new Source(0);
        assertEquals(DocumentLayoutException.Code.CONTENT_TOO_TALL, assertThrows(DocumentLayoutException.class,
                () -> layout(badHeaders, source, new ArrayList<>(), StreamingTableLimits.DEFAULT)).code());
        assertEquals(0, source.reads);
        var tooTallBody = new Source(1, index -> new TableRow(List.of(new TableCell("ROW", new CellFormat(.01, .201, ParagraphAlignment.LEFT)))));
        var oneHeader = spec.toBuilder().headerRows(List.of(first)).build();
        var delivered = new ArrayList<LayoutPage>();
        assertEquals(DocumentLayoutException.Code.CONTENT_TOO_TALL, assertThrows(DocumentLayoutException.class,
                () -> layout(oneHeader, tooTallBody, delivered, StreamingTableLimits.DEFAULT)).code());
        assertTrue(delivered.isEmpty());
    }

    @Test void tooTallFirstRowFailsBeforeHeaderOnlyDelivery() {
        var pages = new ArrayList<LayoutPage>(); var source = new Source(1, index -> row("A\nB\nC\nD"));
        var failure = assertThrows(DocumentLayoutException.class, () -> layout(table(), source, pages, StreamingTableLimits.DEFAULT));
        assertEquals(DocumentLayoutException.Code.CONTENT_TOO_TALL, failure.code());
        assertEquals(1, failure.rowIndex()); assertEquals(0, failure.pageIndex());
        assertTrue(pages.isEmpty()); assertEquals(1, source.reads); assertEquals(0, source.closes);
    }

    @Test void allHeadersMustFitBeforeReadingAnyBody() {
        var spec = table().toBuilder().header("A\nB\nC\nD").build(); var source = new Source(1);
        var failure = assertThrows(DocumentLayoutException.class, () -> layout(spec, source, new ArrayList<>(), StreamingTableLimits.DEFAULT));
        assertEquals(DocumentLayoutException.Code.CONTENT_TOO_TALL, failure.code()); assertEquals(0, source.reads);
    }

    @Test void rowMustMatchColumnsAndUnsupportedFieldCellsFailExplicitly() {
        var bad = List.of(new TableRow(List.of(new TableCell("A", FORMAT), new TableCell("B", FORMAT))),
                new TableRow(List.of(TableCell.mixed(List.of(InlineField.builder().kind(InlineField.Kind.PAGES).width(20).build()), FORMAT))));
        for (int index = 0; index < bad.size(); index++) {
            var value = bad.get(index); var source = new Source(1, ignored -> value);
            var failure = assertThrows(DocumentLayoutException.class, () -> layout(table(), source, new ArrayList<>(), StreamingTableLimits.DEFAULT));
            assertEquals(index == 0 ? DocumentLayoutException.Code.INVALID_GEOMETRY : DocumentLayoutException.Code.UNSUPPORTED_COMPONENT, failure.code());
            assertEquals(1, failure.rowIndex()); assertEquals(1, source.reads);
        }
    }

    private static InlineImage image(String target) throws IOException {
        var encoded = new ByteArrayOutputStream();
        var raster = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        try { assertTrue(ImageIO.write(raster, "png", encoded)); }
        finally { raster.flush(); }
        return new InlineImage(ImageResource.load(encoded.toByteArray()), 8, 8, 0, "pixel", target);
    }

    @Test void mixedImagesAndLinksRetainRepeatedHeadersPhysicalColumnsAndSingleRowLookahead() throws IOException {
        var logo = image("https://example.com/image");
        var header = TableCell.mixed(List.of(logo, new InlineText(" HEAD", InlineStyle.of(10), "https://example.com/header")), FORMAT);
        var spec = table().toBuilder().columns(30, 40, 50).headerRows(List.of(new TableRow(List.of(
                header.toBuilder().columnSpan(3).build())))).build();
        var source = new Source(7, index -> new TableRow(List.of(span("ROW" + index, 2),
                TableCell.mixed(List.of(new InlineText("X", InlineStyle.of(10)), logo), FORMAT))));
        var pages = new ArrayList<LayoutPage>();
        var links = new ArrayList<List<com.mirakyux.export4j.graphics.DocumentExtras.ExternalLink>>();
        var reads = new ArrayList<Integer>();
        var consumer = new StreamingTableLayouter.PageConsumer() {
            @Override public void accept(LayoutPage page) { throw new AssertionError("links omitted"); }
            @Override public void accept(LayoutPage page, List<com.mirakyux.export4j.graphics.DocumentExtras.ExternalLink> value) {
                pages.add(page); links.add(value); reads.add(source.reads);
            }
        };
        var summary = new StreamingTableLayouter().layout(spec, source, fonts, consumer, ignored -> { }, StreamingTableLimits.DEFAULT);
        assertEquals(3, summary.pages()); assertEquals(List.of(4, 7, 8), reads);
        assertEquals(15, summary.sourceCells()); assertEquals(7, summary.bodyRows());
        for (int index = 0; index < pages.size(); index++) {
            var page = pages.get(index);
            assertSame(rows(pages.getFirst()).getFirst().cells().getFirst().layout(), rows(page).getFirst().cells().getFirst().layout());
            assertEquals(rows(page).size() + 1, links.get(index).size());
            assertEquals(10, links.get(index).getFirst().bounds().x());
            assertEquals(8, links.get(index).getFirst().bounds().width());
            assertTrue(links.get(index).stream().allMatch(link -> link.pageIndex() == page.pageIndex()));
            for (var row : rows(page)) {
                var cell = row.cells().getLast();
                assertEquals(row.header() ? 0 : 2, cell.columnIndex());
                var boxes = cell.layout().lines().stream().flatMap(line -> line.images().stream()).toList();
                assertEquals(1, boxes.size()); assertSame(logo, boxes.getFirst().image());
                assertEquals(row.header() ? 0 : 1, boxes.getFirst().sourceIndex());
            }
        }
    }

    @Test void linkBudgetsIncludeRepeatedHeadersAndLegacyConsumersFailInsteadOfDroppingLinks() {
        var linked = TableCell.rich(List.of(new InlineText("HEAD", InlineStyle.of(10), "https://example.com/")), FORMAT);
        var spec = table().toBuilder().headerRows(List.of(new TableRow(List.of(linked)))).build();
        var source = new Source(5);
        var accepted = new AtomicInteger();
        var consumer = new StreamingTableLayouter.PageConsumer() {
            @Override public void accept(LayoutPage page) { throw new AssertionError("links omitted"); }
            @Override public void accept(LayoutPage page, List<com.mirakyux.export4j.graphics.DocumentExtras.ExternalLink> links) {
                accepted.incrementAndGet(); assertEquals(1, links.size());
            }
        };
        var failure = assertThrows(DocumentLayoutException.class, () -> new StreamingTableLayouter().layout(spec, source, fonts,
                consumer, ignored -> { }, StreamingTableLimits.DEFAULT.toBuilder().maxLinks(1).build()));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals(1, failure.pageIndex()); assertEquals(0, failure.rowIndex()); assertEquals(1, accepted.get());
        assertEquals(4, source.reads);
        assertThrows(IOException.class, () -> layout(spec, new Source(5), new ArrayList<>(), StreamingTableLimits.DEFAULT));
    }

    @Test void splitRowsPreserveWholeLinesOffsetsImagesLinksAndOneSourceReadUntilComplete() throws IOException {
        var picture = image("https://example.com/picture");
        var spec = table().toBuilder().columns(30, 40, 50).padding(2).allowRowSplit(true)
                .headerRows(List.of(new TableRow(List.of(span("HEAD", 3))))).build();
        var source = new Source(1, ignored -> new TableRow(List.of(TableCell.rich(List.of(new InlineText("A\nB\nC\nD\nE",
                InlineStyle.of(10), "https://example.com/text")), FORMAT).toBuilder().columnSpan(2).build(),
                TableCell.mixed(List.of(new InlineText("SHORT", InlineStyle.of(10)), picture), FORMAT))));
        var pages = new ArrayList<LayoutPage>(); var reads = new ArrayList<Integer>();
        var links = new ArrayList<com.mirakyux.export4j.graphics.DocumentExtras.ExternalLink>();
        var consumer = new StreamingTableLayouter.PageConsumer() {
            @Override public void accept(LayoutPage page) { throw new AssertionError("links omitted"); }
            @Override public void accept(LayoutPage page, List<com.mirakyux.export4j.graphics.DocumentExtras.ExternalLink> value) {
                pages.add(page); reads.add(source.reads); links.addAll(value);
            }
        };
        var summary = new StreamingTableLayouter().layout(spec, source, fonts, consumer, ignored -> { }, StreamingTableLimits.DEFAULT);
        assertEquals(3, summary.pages()); assertEquals(1, summary.bodyRows()); assertEquals(3, summary.sourceCells());
        assertEquals(List.of(1, 1, 2), reads);
        assertEquals(List.of(0, 2, 4), pages.stream().map(page -> rows(page).getLast().cells().getFirst().firstLineIndex()).toList());
        assertEquals(List.of(false, true, true), pages.stream().map(page -> rows(page).getLast().continuedRow()).toList());
        assertEquals(1, pages.stream().flatMap(page -> rows(page).stream()).flatMap(row -> row.cells().stream())
                .flatMap(cell -> cell.layout().lines().stream()).flatMap(line -> line.images().stream()).count());
        assertEquals(6, links.size()); assertEquals(1, links.stream().filter(link -> link.destination().uri().endsWith("picture")).count());
        assertEquals(List.of(0, 2, 4, 6, 8), pages.stream().flatMap(page -> rows(page).getLast().cells().getFirst().layout().lines().stream())
                .map(line -> line.sourceStart()).toList());
        assertTrue(rows(pages.get(1)).getLast().cells().getLast().layout().lines().getFirst().runs().isEmpty());
        assertEquals(11, summary.outputLines());
    }

    @Test void splitRowsReportOriginalUtf16WarningsExactlyOnceAndRejectImpossibleNextLine() throws IOException {
        var spec = table().toBuilder().padding(2).allowRowSplit(true).build();
        var diagnostics = new ArrayList<StreamingTableDiagnostic>();
        var summary = new StreamingTableLayouter().layout(spec, new Source(1, ignored -> row("A\nB\n😀\nD\nE")), replacements(),
                page -> { }, diagnostics::add, StreamingTableLimits.DEFAULT);
        assertEquals(3, summary.pages()); assertEquals(1, diagnostics.size());
        assertEquals(1, diagnostics.getFirst().pageIndex()); assertEquals(4, diagnostics.getFirst().diagnostic().sourceStart());
        assertEquals(6, diagnostics.getFirst().diagnostic().sourceEnd());
        var impossible = table().toBuilder().allowRowSplit(true).headerRows(List.of(row("H\nH\nH"))).build();
        var source = new Source(1, ignored -> new TableRow(List.of(new TableCell("BODY", CellFormat.builder().fontSize(10).lineHeight(20).build()))));
        var pages = new ArrayList<LayoutPage>();
        assertEquals(DocumentLayoutException.Code.CONTENT_TOO_TALL, assertThrows(DocumentLayoutException.class,
                () -> layout(impossible, source, pages, StreamingTableLimits.DEFAULT)).code());
        assertTrue(pages.isEmpty()); assertEquals(1, source.reads);
    }

    @Test void malformedNullSourceResultFailsAndSourceIOExceptionIsUnchanged() {
        var pages = new ArrayList<LayoutPage>();
        var malformed = assertThrows(DocumentLayoutException.class, () -> new StreamingTableLayouter().layout(table(), () -> null, fonts,
                pages::add, diagnostic -> { }, StreamingTableLimits.DEFAULT));
        assertEquals(DocumentLayoutException.Code.INVALID_GEOMETRY, malformed.code()); assertTrue(pages.isEmpty());
        var sentinel = new IOException("read failure");
        assertSame(sentinel, assertThrows(IOException.class, () -> new StreamingTableLayouter().layout(table(), () -> { throw sentinel; }, fonts,
                pages::add, diagnostic -> { }, StreamingTableLimits.DEFAULT)));
    }

    @Test void structuralSourceBudgetsIncludeHeadersOnceAndReportCorrectCell() {
        var cases = List.of(document(100, 100, 10, 2, 1, 100), document(100, 100, 10, 100, 1, 1), document(4, 100, 10, 100, 1, 100));
        for (int index = 0; index < cases.size(); index++) {
            var limit = StreamingTableLimits.DEFAULT.toBuilder().document(cases.get(index)).build(); var source = new Source(3);
            var failure = assertThrows(DocumentLayoutException.class, () -> layout(table(), source, new ArrayList<>(), limit));
            assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
            assertEquals(index == 0 ? 2 : 1, failure.rowIndex());
            assertEquals(index == 0 ? -1 : 0, failure.columnIndex());
        }
    }

    @Test void columnBudgetFailsBeforeSourceRead() {
        var spec = table().toBuilder().columns(50, 50).headerRows(List.of()).build(); var source = new Source(1);
        var limit = StreamingTableLimits.DEFAULT.toBuilder().document(document(100, 100, 10, 100, 1, 100)).build();
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, assertThrows(DocumentLayoutException.class,
                () -> layout(spec, source, new ArrayList<>(), limit)).code());
        assertEquals(0, source.reads);
    }

    @Test void perCellAndPerRowCapsCountUnicodeCodePointsAndPreserveUtf16Offset() {
        var spec = table().toBuilder().headerRows(List.of()).build();
        for (var limits : List.of(StreamingTableLimits.DEFAULT.toBuilder().maxCellCodePoints(1).build(),
                StreamingTableLimits.DEFAULT.toBuilder().maxRowCodePoints(1).build())) {
            var source = new Source(1, index -> row("A😀"));
            var failure = assertThrows(DocumentLayoutException.class, () -> layout(spec, source, new ArrayList<>(), limits));
            assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
            assertEquals(1, failure.sourceIndex()); assertEquals(0, failure.rowIndex()); assertEquals(0, failure.columnIndex());
        }
    }

    @Test void perRowCapCombinesCellsBeforeShapingAndHeaderContentIsAlsoRestricted() {
        var spec = table().toBuilder().columns(50, 50).headerRows(List.of()).build();
        var source = new Source(1, index -> new TableRow(List.of(new TableCell("AB", FORMAT), new TableCell("CD", FORMAT))));
        var limits = StreamingTableLimits.DEFAULT.toBuilder().maxRowCodePoints(3).build();
        var failure = assertThrows(DocumentLayoutException.class, () -> layout(spec, source, new ArrayList<>(), limits));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals(1, failure.columnIndex()); assertEquals(1, failure.sourceIndex());
        var header = table().toBuilder().headerRows(List.of(new TableRow(List.of(TableCell.mixed(
                List.of(InlineField.builder().kind(InlineField.Kind.PAGES).width(20).build()), FORMAT))))).build();
        var untouched = new Source(1);
        assertEquals(DocumentLayoutException.Code.UNSUPPORTED_COMPONENT, assertThrows(DocumentLayoutException.class,
                () -> layout(header, untouched, new ArrayList<>(), StreamingTableLimits.DEFAULT)).code());
        assertEquals(0, untouched.reads);
    }

    @Test void multilineRowsStayWholeAndPreserveCellSourceRangesAndPadding() throws IOException {
        var spec = table().toBuilder().columns(50, 50).headerRows(List.of()).padding(2).build();
        var source = new Source(2, index -> new TableRow(List.of(new TableCell("A\nBC", FORMAT), new TableCell("SHORT", FORMAT))));
        var pages = new ArrayList<LayoutPage>(); var summary = layout(spec, source, pages, StreamingTableLimits.DEFAULT);
        assertEquals(2, summary.pages()); assertEquals(6, summary.outputLines());
        for (int index = 0; index < pages.size(); index++) {
            var row = rows(pages.get(index)).getFirst(); assertEquals(index, row.rowIndex()); assertFalse(row.header());
            var cell = row.cells().getFirst();
            assertEquals(34, cell.bounds().height()); assertEquals(2, cell.textOrigin().x()); assertEquals(2, cell.textOrigin().y());
            assertEquals(List.of(0, 2), cell.layout().lines().stream().map(line -> line.sourceStart()).toList());
            assertEquals(List.of(1, 4), cell.layout().lines().stream().map(line -> line.sourceEnd()).toList());
            assertEquals(52, row.cells().getLast().textOrigin().x());
        }
    }

    @Test void repeatedHeaderLinesConsumeOutputBudgetRatherThanSourceBudget() {
        var pages = new ArrayList<LayoutPage>(); var source = new Source(5);
        var limits = StreamingTableLimits.DEFAULT.toBuilder().document(document(100, 4, 10, 100, 1, 100)).build();
        var failure = assertThrows(DocumentLayoutException.class, () -> layout(table(), source, pages, limits));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals(1, failure.pageIndex()); assertEquals(0, failure.rowIndex()); assertEquals(1, pages.size());
    }

    @Test void pageCapStopsBeforeDeliveringAnExtraPage() {
        var pages = new ArrayList<LayoutPage>(); var source = new Source(5);
        var limits = StreamingTableLimits.DEFAULT.toBuilder().document(document(100, 100, 1, 100, 1, 100)).build();
        var failure = assertThrows(DocumentLayoutException.class, () -> layout(table(), source, pages, limits));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals(1, failure.pageIndex()); assertEquals(1, pages.size()); assertEquals(4, source.reads);
    }

    @Test void strictMissingGlyphRetainsSourceCellUtf16AndTextCause() {
        var source = new Source(1, index -> row("A😀"));
        var failure = assertThrows(DocumentLayoutException.class, () -> layout(table(), source, new ArrayList<>(), StreamingTableLimits.DEFAULT));
        assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, failure.code());
        assertEquals("stream", failure.blockId()); assertEquals(1, failure.rowIndex()); assertEquals(0, failure.columnIndex());
        assertEquals(1, failure.sourceIndex()); assertTrue(failure.getCause() instanceof TextLayoutException);
        assertFalse(failure.getMessage().contains("A😀"));
    }

    @Test void repeatedHeaderDiagnosticsUseActualPagesAndCountSupplementaryCodePointsOnce() throws IOException {
        var spec = table().toBuilder().headerRows(List.of(row("A😀"))).build(); var source = new Source(5, index -> row("B" + index));
        var diagnostics = new ArrayList<StreamingTableDiagnostic>();
        var summary = new StreamingTableLayouter().layout(spec, source, replacements(), page -> { }, diagnostics::add, StreamingTableLimits.DEFAULT);
        assertEquals(12, summary.sourceCodePoints()); assertEquals(6, summary.sourceCells()); assertEquals(7, summary.outputLines());
        assertEquals(2, summary.diagnostics()); assertEquals(List.of(0, 1), diagnostics.stream().map(StreamingTableDiagnostic::pageIndex).toList());
        assertTrue(diagnostics.stream().allMatch(value -> value.rowIndex() == 0 && value.columnIndex() == 0));
        assertTrue(diagnostics.stream().allMatch(value -> value.diagnostic().sourceStart() == 1 && value.diagnostic().sourceEnd() == 3
                && value.diagnostic().code() == TextDiagnostic.Code.MISSING_GLYPH_REPLACED));
    }

    @Test void diagnosticCapAndCallbackFailureStopBeforeAnyFurtherSourceRead() {
        var source = new Source(8, index -> row("A😀")); var sentinel = new IllegalStateException("diagnostic failure");
        assertSame(sentinel, assertThrows(IllegalStateException.class, () -> new StreamingTableLayouter().layout(table(), source, replacements(),
                page -> { throw new AssertionError("page must not be emitted"); }, diagnostic -> { throw sentinel; }, StreamingTableLimits.DEFAULT)));
        assertEquals(4, source.reads);
        var limited = new Source(8, index -> row("A😀")); var calls = new AtomicInteger();
        var failure = assertThrows(DocumentLayoutException.class, () -> new StreamingTableLayouter().layout(table(), limited, replacements(),
                page -> { throw new AssertionError("page must not be emitted"); }, diagnostic -> calls.incrementAndGet(),
                StreamingTableLimits.DEFAULT.toBuilder().maxDiagnostics(1).build()));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals(1, calls.get()); assertEquals(4, limited.reads); assertEquals(2, failure.rowIndex());
    }

    @Test void bodyLookaheadDiagnosticBelongsToItsEventualPage() throws IOException {
        var source = new Source(4, index -> row(index == 4 ? "A😀" : "ROW" + index));
        var diagnostics = new ArrayList<StreamingTableDiagnostic>();
        new StreamingTableLayouter().layout(table(), source, replacements(), page -> { }, diagnostics::add, StreamingTableLimits.DEFAULT);
        assertEquals(1, diagnostics.size()); assertEquals(1, diagnostics.getFirst().pageIndex()); assertEquals(4, diagnostics.getFirst().rowIndex());
    }

    @Test void cancellationAfterSourceCallPreventsMeasurementAndDeliveryAndRestoresScope() {
        var cancelled = new AtomicBoolean(); var reads = new AtomicInteger();
        TableRowSource source = () -> { reads.incrementAndGet(); cancelled.set(true); return Optional.of(row("ROW")); };
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(ExecutionOptions.builder().cancelled(cancelled::get).build(),
                () -> new StreamingTableLayouter().layout(table(), source, fonts, page -> { throw new AssertionError(); }, diagnostic -> { }, StreamingTableLimits.DEFAULT)));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code()); assertEquals(1, reads.get());
        ExecutionContext.checkpoint();
    }

    @Test void cancellationAfterPageDeliveryStopsFurtherReads() {
        var cancelled = new AtomicBoolean(); var source = new Source(100); var delivered = new AtomicInteger();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(ExecutionOptions.builder().cancelled(cancelled::get).build(),
                () -> new StreamingTableLayouter().layout(table(), source, fonts, page -> { delivered.incrementAndGet(); cancelled.set(true); }, diagnostic -> { }, StreamingTableLimits.DEFAULT)));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code()); assertEquals(4, source.reads); assertEquals(1, delivered.get());
        assertEquals(0, source.closes); ExecutionContext.checkpoint();
    }

    @Test void timeoutBeforeSourceAndProgressFailuresPreserveScope() {
        var source = new Source(1);
        var timeout = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(ExecutionOptions.builder().timeout(Duration.ofNanos(1)).build(),
                () -> layout(table(), source, new ArrayList<>(), StreamingTableLimits.DEFAULT)));
        assertEquals(ExecutionException.Code.TIMEOUT, timeout.code()); assertEquals(0, source.reads);
        var sentinel = new IllegalArgumentException("progress failure");
        assertSame(sentinel, assertThrows(IllegalArgumentException.class, () -> ExecutionContext.execute(ExecutionOptions.builder().progress(progress -> { throw sentinel; }).build(),
                () -> layout(table(), source, new ArrayList<>(), StreamingTableLimits.DEFAULT))));
        assertEquals(0, source.reads); ExecutionContext.checkpoint();
    }

    @Test void progressReportsUnknownCountsUntilEofAndFinalExactCount() throws IOException {
        var progress = new ArrayList<ExecutionOptions.Progress>();
        ExecutionContext.execute(ExecutionOptions.builder().progress(progress::add).build(),
                () -> layout(table(), new Source(4), new ArrayList<>(), StreamingTableLimits.DEFAULT));
        assertTrue(progress.stream().filter(value -> !value.stage().endsWith("DONE")).noneMatch(ExecutionOptions.Progress::hasKnownTotal));
        assertEquals(4, progress.getLast().completed()); assertEquals(4, progress.getLast().total());
        assertTrue(progress.getLast().hasKnownTotal());
    }

    @Test void onePagePainterAcceptsNonzeroPhysicalIndexAndMatchesCompletePainter() throws IOException {
        var pages = new ArrayList<LayoutPage>(); layout(table(), new Source(4), pages, StreamingTableLimits.DEFAULT);
        var page = pages.get(1); var painted = DocumentPainter.paintPage(page);
        assertEquals(1, page.pageIndex()); assertEquals(PAGE.size(), painted.size());
        assertEquals(DocumentPainter.paint(new DocumentLayout(DocumentMetadata.EMPTY, pages)).pages().get(1), painted);
        assertEquals(List.of("HEAD", "ROW4"), painted.commands().stream().filter(DrawCommand.GlyphText.class::isInstance)
                .map(DrawCommand.GlyphText.class::cast).map(glyph -> glyph.run().text()).toList());
    }

    @Test void streamingLimitBuilderSnapshotsAndRejectsNonpositiveCaps() {
        var builder = StreamingTableLimits.builder().maxCellCodePoints(10).maxRowCodePoints(20).maxDiagnostics(30);
        var value = builder.build(); builder.maxDiagnostics(1);
        assertEquals(value, value.toBuilder().build()); assertEquals(30, value.maxDiagnostics());
        assertThrows(IllegalArgumentException.class, () -> StreamingTableLimits.builder().maxDiagnostics(0).build());
        assertThrows(IllegalArgumentException.class, () -> StreamingTableLimits.builder().maxCellCodePoints(0).build());
        assertThrows(IllegalArgumentException.class, () -> StreamingTableLimits.builder().maxRowCodePoints(-1).build());
    }
}
