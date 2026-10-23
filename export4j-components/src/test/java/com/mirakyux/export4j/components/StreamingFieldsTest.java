// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.PageBackend;
import com.mirakyux.export4j.graphics.PageWriter;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.layout.core.StreamingTableLayouter;
import com.mirakyux.export4j.layout.core.StreamingTableLimits;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.TextOutline;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents early page resolution, unverified totals, repeated-source charging and lost atomic targets. */
class StreamingFieldsTest {
    @TempDir Path temporary;
    private static final CellFormat CELL = CellFormat.builder().fontSize(10).lineHeight(20).build();
    private static final InlineStyle STYLE = InlineStyle.of(10);
    private static FontGroup fonts;

    @BeforeAll static void fonts() throws IOException {
        try (var input = StreamingFieldsTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    @Test void repeatedHeadersAndMovedBodyRowsResolveAtTheirActualPageWithoutSourceRecounting() throws IOException {
        var spec = spec(3);
        var rows = new ArrayList<TableRow>();
        for (int index = 1; index <= 7; index++) {
            rows.add(row(TableCell.mixed(List.of(new InlineText("R" + index + " P=", STYLE), field(InlineField.Kind.PAGE)), CELL)));
        }
        var pages = new ArrayList<LayoutPage>();
        var source = new Source(rows);
        var summary = layout(spec, source, pages, StreamingTableLimits.DEFAULT);
        assertEquals(3, summary.pages()); assertEquals(8, summary.sourceCells()); assertEquals(10, summary.outputLines());
        assertEquals(8, source.reads);
        long sourcePoints = spec.headerRows().getFirst().cells().getFirst().text().codePointCount(0,
                spec.headerRows().getFirst().cells().getFirst().text().length());
        for (var row : rows) { sourcePoints += row.cells().getFirst().text().length(); }
        assertEquals(sourcePoints, summary.sourceCodePoints());
        int next = 1;
        for (int index = 0; index < pages.size(); index++) {
            var table = (TableFragment) pages.get(index).elements().getFirst();
            assertEquals("P=" + (index + 1) + " T=3", logical(table.rows().getFirst().cells().getFirst().layout()));
            for (var row : table.rows()) {
                if (!row.header()) { assertEquals("R" + next++ + " P=" + (index + 1), logical(row.cells().getFirst().layout())); }
                assertTrue(row.cells().stream().flatMap(cell -> cell.layout().lines().stream()).allMatch(line -> line.fields().isEmpty()));
            }
        }
        assertEquals(8, next);
    }

    @Test void splitRowFieldUsesItsPresentationPageAndOriginalUtf16Slot() throws IOException {
        var spec = spec(2).toBuilder().allowRowSplit(true).build();
        var cell = TableCell.mixed(List.of(new InlineText("A\nB\nC\nPAGE=", STYLE), field(InlineField.Kind.PAGE)), CELL);
        var pages = new ArrayList<LayoutPage>();
        layout(spec, new Source(List.of(row(cell))), pages, StreamingTableLimits.DEFAULT);
        assertEquals(2, pages.size());
        var first = ((TableFragment) pages.getFirst().elements().getFirst()).rows().getLast().cells().getFirst();
        var last = ((TableFragment) pages.getLast().elements().getFirst()).rows().getLast().cells().getFirst();
        assertEquals("ABC", logical(first.layout())); assertEquals("PAGE=2", logical(last.layout()));
        assertEquals(3, last.firstLineIndex());
        assertEquals(6, last.layout().lines().getFirst().sourceStart());
        assertEquals(12, last.layout().lines().getFirst().sourceEnd());
    }

    @Test void fieldAndGeneratedValueBudgetsCountEveryActualHeaderPresentation() throws IOException {
        var rows = java.util.stream.IntStream.range(0, 7).mapToObj(index -> row(new TableCell("ROW", CELL))).toList();
        var exact = StreamingTableLimits.builder().maxFields(6).maxGeneratedCodePoints(6).build();
        assertEquals(3, layout(spec(3), new Source(rows), new ArrayList<>(), exact).pages());
        for (var limits : List.of(exact.toBuilder().maxFields(5).build(), exact.toBuilder().maxGeneratedCodePoints(5).build())) {
            assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, assertThrows(DocumentLayoutException.class,
                    () -> layout(spec(3), new Source(rows), new ArrayList<>(), limits)).code());
        }
    }

    @Test void unknownTotalPageFieldsReachTwoDigitsWithGeneratedValuesChargedSeparately() throws IOException {
        var spec = spec(0).toBuilder().headerRows(List.of(row(TableCell.mixed(List.of(
                new InlineText("P=", STYLE), field(InlineField.Kind.PAGE)), CELL)))).build();
        var rows = java.util.stream.IntStream.range(0, 31).mapToObj(index -> row(new TableCell("ROW", CELL))).toList();
        var pages = new ArrayList<LayoutPage>();
        var exact = StreamingTableLimits.builder().maxFields(11).maxGeneratedCodePoints(13).build();
        assertEquals(11, layout(spec, new Source(rows), pages, exact).pages());
        assertEquals("P=11", logical(((TableFragment) pages.getLast().elements().getFirst()).rows().getFirst().cells().getFirst().layout()));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, assertThrows(DocumentLayoutException.class,
                () -> layout(spec, new Source(rows), new ArrayList<>(), exact.toBuilder().maxGeneratedCodePoints(12).build())).code());
    }

    @Test void unknownTotalsAndFutureTargetsFailBeforeReadingAnyBodyRow() {
        for (var kind : List.of(InlineField.Kind.PAGES, InlineField.Kind.REFERENCE_PAGE, InlineField.Kind.REFERENCE_NUMBER)) {
            var field = new InlineField(kind, kind == InlineField.Kind.PAGES ? "" : "target", 15, STYLE);
            var spec = spec(0).toBuilder().headerRows(List.of(row(TableCell.mixed(List.of(new InlineText("X", STYLE), field), CELL)))).build();
            var source = new Source(List.of(row(new TableCell("UNREAD", CELL))));
            var failure = assertThrows(DocumentLayoutException.class, () -> layout(spec, source, new ArrayList<>(), StreamingTableLimits.DEFAULT));
            assertEquals(DocumentLayoutException.Code.UNSUPPORTED_COMPONENT, failure.code());
            assertEquals(1, failure.sourceIndex()); assertEquals(0, source.reads);
        }
    }

    @Test void generatedWarningsRetainFieldContextAndCallbackExceptionsAreNotWrapped() throws IOException {
        var face = fonts.fonts().getFirst();
        FontFace missingDigits = new FontFace() {
            @Override public com.mirakyux.export4j.font.FontData data() { return face.data(); }
            @Override public com.mirakyux.export4j.font.FontMetrics metrics() { return face.metrics(); }
            @Override public Optional<com.mirakyux.export4j.font.GlyphMetrics> glyph(int point) {
                return point >= '0' && point <= '9' ? Optional.empty() : face.glyph(point);
            }
        };
        var field = field(InlineField.Kind.PAGE).toBuilder().style(STYLE.toBuilder().fontRole("digits").build()).build();
        var spec = spec(0).toBuilder().headerRows(List.of(row(TableCell.mixed(List.of(new InlineText("P=", STYLE), field), CELL)))).build();
        var engine = DocumentEngine.builder(fonts).fontRole("digits", FontGroup.placeholder(List.of(missingDigits), '?')).build();
        var diagnostics = new ArrayList<ContextualDiagnostic>();
        var source = new Source(List.of(row(new TableCell("ROW", CELL))));
        engine.export(spec, () -> source).backend(sink(new ArrayList<>(), new int[1])).diagnostics(diagnostics::add).to(new ByteArrayOutputStream());
        assertEquals(1, diagnostics.size());
        var diagnostic = diagnostics.getFirst();
        assertEquals("INLINE_FIELD:2", diagnostic.region()); assertEquals(0, diagnostic.diagnostic().sourceStart());
        assertEquals(1, diagnostic.diagnostic().sourceEnd()); assertEquals('1', diagnostic.diagnostic().sourceCodePoint());
        var marker = new IllegalArgumentException("diagnostic callback");
        var failed = new Source(List.of());
        assertSame(marker, assertThrows(IllegalArgumentException.class, () -> engine.export(spec, () -> failed)
                .backend(sink(new ArrayList<>(), new int[1])).diagnostics(value -> { throw marker; }).to(new ByteArrayOutputStream())));
        assertEquals(1, failed.closes);
    }

    @Test void declaredTotalMismatchKeepsOldTargetClosesSourceAndNeverFinishesWriter() throws IOException {
        var rows = java.util.stream.IntStream.range(0, 7).mapToObj(index -> row(new TableCell("ROW", CELL))).toList();
        var engine = DocumentEngine.builder(fonts).build();
        var target = temporary.resolve("target.bin");
        byte[] original = {7, 8, 9};
        for (int declared : new int[]{2, 4}) {
            Files.write(target, original);
            var source = new Source(rows); var pages = new ArrayList<DisplayPage>(); int[] finishes = {0};
            assertEquals(DocumentLayoutException.Code.INVALID_GEOMETRY, assertThrows(DocumentLayoutException.class,
                    () -> engine.export(spec(declared).toBuilder().headerRows(List.of(row(new TableCell("HEADER", CELL)))).build(),
                            () -> source).backend(sink(pages, finishes)).to(target)).code());
            assertTrue(!pages.isEmpty() && pages.size() < 3);
            assertEquals(1, source.closes); assertEquals(0, finishes[0]); assertArrayEquals(original, Files.readAllBytes(target));
            try (var files = Files.list(temporary)) { assertEquals(List.of(target), files.toList()); }
        }
        var source = new Source(rows);
        assertEquals(3, engine.export(spec(3), () -> source).backend(sink(new ArrayList<>(), new int[1])).to(target).pages());
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(target)); assertEquals(1, source.closes);
    }

    @Test void reservedFieldOverflowRetainsOriginalCellSourceAndFailsBeforePageDelivery() {
        var spec = spec(0).toBuilder().headerRows(List.of(row(TableCell.mixed(List.of(
                new InlineText("X", STYLE), field(InlineField.Kind.PAGE).toBuilder().width(.2).build()), CELL)))).build();
        var failure = assertThrows(DocumentLayoutException.class, () -> layout(spec, new Source(List.of()),
                new ArrayList<>(), StreamingTableLimits.DEFAULT));
        assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, failure.code());
        assertEquals(1, failure.sourceIndex()); assertEquals(0, failure.rowIndex()); assertEquals(0, failure.columnIndex());
    }

    private static StreamingTable spec(int total) {
        return StreamingTable.builder("fields").columns(180).padding(0).expectedPages(total)
                .pageSettings(PageSettings.builder().size(200, 100).margins(10).build())
                .header(row(TableCell.mixed(List.of(new InlineText("P=", STYLE), field(InlineField.Kind.PAGE),
                        new InlineText(" T=", STYLE), field(InlineField.Kind.PAGES)), CELL))).build();
    }
    private static InlineField field(InlineField.Kind kind) { return new InlineField(kind, "", 15, STYLE); }
    private static TableRow row(TableCell cell) { return new TableRow(List.of(cell)); }
    private static StreamingTableLayouter.Summary layout(StreamingTable table, Source source, List<LayoutPage> pages,
            StreamingTableLimits limits) throws IOException {
        return new StreamingTableLayouter().layout(table, source, fonts, Map.of(), pages::add, value -> { }, limits);
    }
    private static String logical(ParagraphLayout layout) {
        return layout.lines().stream().flatMap(line -> line.outlines().stream()).map(TextOutline::logicalText).collect(java.util.stream.Collectors.joining());
    }
    private static PageBackend sink(List<DisplayPage> pages, int[] finishes) {
        return (metadata, output) -> new PageWriter() {
            @Override public void accept(DisplayPage page) { pages.add(page); }
            @Override public void finish() throws IOException { finishes[0]++; output.write(1); }
            @Override public void close() { }
        };
    }
    private static final class Source implements TableRowSource {
        private final List<TableRow> rows; private int reads; private int closes;
        private Source(List<TableRow> rows) { this.rows = List.copyOf(rows); }
        @Override public Optional<TableRow> next() { return reads < rows.size() ? Optional.of(rows.get(reads++)) : end(); }
        private Optional<TableRow> end() { reads++; return Optional.empty(); }
        @Override public void close() { closes++; }
    }
}
