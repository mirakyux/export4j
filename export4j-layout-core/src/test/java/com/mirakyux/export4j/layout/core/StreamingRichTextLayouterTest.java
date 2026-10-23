// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.TableRowFragment;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.StyledSpan;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextLayoutException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingRichTextLayouterTest {
    private static final CellFormat FORMAT = CellFormat.builder().fontSize(10).lineHeight(20).build();
    private static final InlineStyle PLAIN = InlineStyle.of(10);
    private static FontGroup regular;
    private static FontGroup bold;
    private static FontGroup arabic;
    private static FontGroup chinese;

    @BeforeAll static void loadFonts() throws IOException {
        regular = load("LiberationSans-Regular.ttf");
        bold = load("LiberationSans-Bold.ttf");
        arabic = load("NotoNaskhArabic-Regular.ttf");
        chinese = load("ZCOOLXiaoWei-Regular.ttf");
    }

    private static FontGroup load(String name) throws IOException {
        try (var input = StreamingRichTextLayouterTest.class.getResourceAsStream("/fonts/" + name)) {
            return FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    private static StreamingTable table() {
        return StreamingTable.builder("rich-stream").columns(160).padding(0)
                .pageSettings(new PageSettings(new PageSize(180, 100), PageMargins.uniform(10)))
                .cellFormat(FORMAT).build();
    }

    private static InlineStyle role(String name) { return PLAIN.toBuilder().fontRole(name).build(); }
    private static TableCell rich(InlineText... runs) { return TableCell.rich(List.of(runs), FORMAT); }
    private static TableRow row(TableCell... cells) { return new TableRow(List.of(cells)); }
    private static List<TableRowFragment> rows(LayoutPage page) { return ((TableFragment) page.elements().getFirst()).rows(); }

    private static final class Source implements TableRowSource {
        private final int count;
        private final IntFunction<TableRow> values;
        private int reads;
        private int closes;
        private Source(int count, IntFunction<TableRow> values) { this.count = count; this.values = values; }
        @Override public Optional<TableRow> next() {
            reads++;
            return reads <= count ? Optional.of(values.apply(reads)) : Optional.empty();
        }
        @Override public void close() { closes++; }
    }

    private static StreamingTableLayouter.Summary layout(StreamingTable spec, Source source,
            Map<String, FontGroup> roles, List<LayoutPage> pages, StreamingTableLimits limits) throws IOException {
        return new StreamingTableLayouter().layout(spec, source, regular, roles, pages::add, diagnostic -> { }, limits);
    }

    @Test void richRunsKeepRealFontSizeColorShiftAndDecorationsInFinalCommands() throws IOException {
        var emphasis = InlineStyle.builder().fontRole("bold").fontSize(12).rgb(0x224488)
                .baselineShift(-1).underline(true).strikeThrough(true).build();
        var source = new Source(1, index -> row(rich(new InlineText("A", PLAIN), new InlineText("B", emphasis))));
        var pages = new ArrayList<LayoutPage>();
        var summary = layout(table(), source, Map.of("bold", bold), pages, StreamingTableLimits.DEFAULT);
        var cell = rows(pages.getFirst()).getFirst().cells().getFirst();
        var runs = cell.layout().lines().getFirst().runs();
        assertEquals(2, runs.size());
        assertEquals(regular.fonts().getFirst().data().id(), runs.getFirst().font().id());
        assertEquals(bold.fonts().getFirst().data().id(), runs.getLast().font().id());
        assertEquals(12, runs.getLast().fontSize()); assertEquals(0x224488, runs.getLast().rgb());
        assertEquals(-1, runs.getLast().baselineShift());
        assertTrue(runs.getLast().underline()); assertTrue(runs.getLast().strikeThrough());
        assertEquals(0, cell.layout().lines().getFirst().sourceStart());
        assertEquals(2, cell.layout().lines().getFirst().sourceEnd());
        var commands = DocumentPainter.paintPage(pages.getFirst()).commands();
        var glyphs = commands.stream().filter(DrawCommand.GlyphText.class::isInstance)
                .map(DrawCommand.GlyphText.class::cast).toList();
        assertEquals(List.of("A", "B"), glyphs.stream().map(value -> value.run().text()).toList());
        var ink = new RgbColor(0x22 / 255.0, 0x44 / 255.0, 0x88 / 255.0);
        assertEquals(ink, glyphs.getLast().color());
        assertEquals(glyphs.getFirst().baseline().y() - 1, glyphs.getLast().baseline().y());
        assertEquals(2, commands.stream().filter(DrawCommand.StrokePath.class::isInstance)
                .map(DrawCommand.StrokePath.class::cast).filter(value -> value.color().equals(ink)).count());
        assertEquals(2, summary.sourceCodePoints()); assertEquals(1, summary.outputLines());
        assertEquals(2, source.reads); assertEquals(0, source.closes);
    }

    @Test void roleMissingGlyphPolicyIsIndependentFromDefaultAndRetainsUtf16() throws IOException {
        var replacement = FontGroup.placeholder(bold.fonts(), '?');
        var source = new Source(1, index -> row(rich(new InlineText("A", PLAIN), new InlineText("😀Z", role("warn")))));
        var diagnostics = new ArrayList<StreamingTableDiagnostic>();
        var summary = new StreamingTableLayouter().layout(table(), source, regular, Map.of("warn", replacement),
                page -> { }, diagnostics::add, StreamingTableLimits.DEFAULT);
        var warning = diagnostics.stream().filter(value -> value.diagnostic().severity() == TextDiagnostic.Severity.WARNING).toList();
        assertEquals(1, warning.size());
        var diagnostic = warning.getFirst();
        assertEquals("rich-stream", diagnostic.blockId()); assertEquals(0, diagnostic.pageIndex());
        assertEquals(0, diagnostic.rowIndex()); assertEquals(0, diagnostic.columnIndex());
        assertEquals(1, diagnostic.diagnostic().sourceStart()); assertEquals(3, diagnostic.diagnostic().sourceEnd());
        assertEquals(0x1f600, diagnostic.diagnostic().sourceCodePoint()); assertEquals('?', diagnostic.diagnostic().replacementCodePoint());
        assertEquals(bold.fonts().getFirst().data().id(), diagnostic.diagnostic().fontId());
        assertEquals(3, summary.sourceCodePoints());

        var strictSource = new Source(1, index -> row(rich(new InlineText("A", PLAIN), new InlineText("😀", role("strict")))));
        var failure = assertThrows(DocumentLayoutException.class, () -> new StreamingTableLayouter().layout(table(),
                strictSource, FontGroup.placeholder(regular.fonts(), '?'), Map.of("strict", bold), page -> { },
                diagnosticValue -> { }, StreamingTableLimits.DEFAULT));
        assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, failure.code()); assertEquals(1, failure.sourceIndex());
        assertEquals(TextLayoutException.Code.MISSING_GLYPH, assertInstanceOf(TextLayoutException.class, failure.getCause()).code());
    }

    @Test void allCellsAreValidatedBeforeShapingAndUnknownRoleLocatesItsRun() {
        var spec = table().toBuilder().columns(80, 80).build();
        var source = new Source(1, index -> row(rich(new InlineText("😀", PLAIN)),
                rich(new InlineText("A😀", PLAIN), new InlineText("secret", role("missing")))));
        var pages = new ArrayList<LayoutPage>();
        var failure = assertThrows(DocumentLayoutException.class,
                () -> layout(spec, source, Map.of(), pages, StreamingTableLimits.DEFAULT));
        assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, failure.code());
        assertEquals("rich-stream", failure.blockId()); assertEquals(0, failure.blockIndex());
        assertEquals(0, failure.pageIndex()); assertEquals(0, failure.rowIndex()); assertEquals(1, failure.columnIndex());
        assertEquals(3, failure.sourceIndex()); assertFalse(failure.getMessage().contains("secret"));
        assertTrue(pages.isEmpty()); assertEquals(1, source.reads);
    }

    @Test void emptyHyperlinkRunStillConsumesItsRunBudgetBeforeAnyCellIsShaped() {
        var spec = table().toBuilder().columns(80, 80).build();
        var source = new Source(1, index -> row(rich(new InlineText("😀", PLAIN)),
                rich(new InlineText("SAFE", PLAIN), new InlineText("", PLAIN, "https://example.com/"))));
        var pages = new ArrayList<LayoutPage>();
        var failure = assertThrows(DocumentLayoutException.class,
                () -> layout(spec, source, Map.of(), pages, StreamingTableLimits.DEFAULT.toBuilder().maxCellInlines(1).build()));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals(0, failure.rowIndex()); assertEquals(1, failure.columnIndex());
        assertTrue(pages.isEmpty()); assertEquals(1, source.reads);
    }

    @Test void emptyRunsCountAtExactCellAndRowCapsWithoutDoubleCountingText() throws IOException {
        var spec = table().toBuilder().columns(80, 80).build();
        IntFunction<TableRow> values = index -> row(rich(new InlineText("", PLAIN), new InlineText("A", PLAIN), new InlineText("", PLAIN)),
                rich(new InlineText("B", PLAIN), new InlineText("", PLAIN)));
        var limits = StreamingTableLimits.DEFAULT.toBuilder().maxCellInlines(3).maxRowInlines(5)
                .maxCellCodePoints(1).maxRowCodePoints(2).build();
        var summary = layout(spec, new Source(1, values), Map.of(), new ArrayList<>(), limits);
        assertEquals(2, summary.sourceCells()); assertEquals(2, summary.sourceCodePoints());
        assertEquals(2, summary.outputLines());
        var cellFailure = assertThrows(DocumentLayoutException.class, () -> layout(spec, new Source(1, values),
                Map.of(), new ArrayList<>(), limits.toBuilder().maxCellInlines(2).build()));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, cellFailure.code()); assertEquals(0, cellFailure.columnIndex());
        assertEquals(1, cellFailure.sourceIndex());
        var rowFailure = assertThrows(DocumentLayoutException.class, () -> layout(spec, new Source(1, values),
                Map.of(), new ArrayList<>(), limits.toBuilder().maxRowInlines(4).build()));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, rowFailure.code()); assertEquals(1, rowFailure.columnIndex());
        assertEquals(1, rowFailure.sourceIndex());
    }

    @Test void ordinaryCellsCountOneEffectiveRunAndRunLimitsValidateBeforeMissingGlyphs() throws IOException {
        var spec = table().toBuilder().columns(80, 80).build();
        IntFunction<TableRow> values = index -> row(new TableCell("A", FORMAT), new TableCell("B", FORMAT));
        var limits = StreamingTableLimits.DEFAULT.toBuilder().maxCellInlines(1).maxRowInlines(2).build();
        assertEquals(2, layout(spec, new Source(1, values), Map.of(), new ArrayList<>(), limits).sourceCodePoints());
        var failure = assertThrows(DocumentLayoutException.class, () -> layout(spec, new Source(1, values),
                Map.of(), new ArrayList<>(), limits.toBuilder().maxRowInlines(1).build()));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code()); assertEquals(1, failure.columnIndex());
        var invalidEarlierText = new Source(1, index -> row(new TableCell("😀", FORMAT),
                rich(new InlineText("", PLAIN), new InlineText("B", PLAIN))));
        var preflightFailure = assertThrows(DocumentLayoutException.class, () -> layout(spec, invalidEarlierText,
                Map.of(), new ArrayList<>(), limits));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, preflightFailure.code());
        assertEquals(1, preflightFailure.columnIndex());
    }

    @Test void headerRunBudgetFailsBeforeOpeningBodyAndEmptyLargeRunsAffectFixedLineFrame() {
        var header = row(rich(new InlineText("", PLAIN), new InlineText("H", PLAIN), new InlineText("", PLAIN)));
        var source = new Source(1, index -> row(new TableCell("BODY", FORMAT)));
        var failure = assertThrows(DocumentLayoutException.class, () -> layout(table().toBuilder().header(header).build(), source,
                Map.of(), new ArrayList<>(), StreamingTableLimits.DEFAULT.toBuilder().maxCellInlines(2).build()));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code()); assertEquals(0, source.reads);
        var oversized = new Source(1, index -> row(rich(new InlineText("", InlineStyle.of(40)), new InlineText("A", PLAIN))));
        var frameFailure = assertThrows(DocumentLayoutException.class,
                () -> layout(table(), oversized, Map.of(), new ArrayList<>(), StreamingTableLimits.DEFAULT));
        assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, frameFailure.code());
        assertEquals(TextLayoutException.Code.LINE_HEIGHT_TOO_SMALL,
                assertInstanceOf(TextLayoutException.class, frameFailure.getCause()).code());
    }

    @Test void repeatedRichHeadersKeepMeasuredIdentityAndChargeActualPageDiagnostics() throws IOException {
        var replacement = FontGroup.placeholder(bold.fonts(), '?');
        var spec = table().toBuilder().header(row(rich(new InlineText("H", PLAIN), new InlineText("😀", role("head"))))).build();
        var source = new Source(5, index -> row(new TableCell("R" + index, FORMAT)));
        var pages = new ArrayList<LayoutPage>(); var diagnostics = new ArrayList<StreamingTableDiagnostic>();
        var summary = new StreamingTableLayouter().layout(spec, source, regular, Map.of("head", replacement), pages::add,
                diagnostics::add, StreamingTableLimits.DEFAULT.toBuilder().maxCellInlines(2).maxRowInlines(2).build());
        assertEquals(2, summary.pages()); assertEquals(12, summary.sourceCodePoints());
        assertEquals(6, summary.sourceCells()); assertEquals(7, summary.outputLines()); assertEquals(2, summary.diagnostics());
        assertEquals(List.of(0, 1), diagnostics.stream().map(StreamingTableDiagnostic::pageIndex).toList());
        assertTrue(diagnostics.stream().allMatch(value -> value.rowIndex() == 0 && value.columnIndex() == 0
                && value.diagnostic().sourceStart() == 1 && value.diagnostic().sourceEnd() == 3));
        assertSame(rows(pages.getFirst()).getFirst().cells().getFirst().layout(), rows(pages.getLast()).getFirst().cells().getFirst().layout());
        assertTrue(rows(pages.getLast()).getFirst().repeatedHeader());
        var limited = StreamingTableLimits.DEFAULT.toBuilder().document(new DocumentLayoutLimits(1, 1_000, 4, 10)).build();
        var failure = assertThrows(DocumentLayoutException.class, () -> layout(spec,
                new Source(5, index -> row(new TableCell("R" + index, FORMAT))), Map.of("head", replacement), new ArrayList<>(), limited));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals(1, failure.pageIndex()); assertEquals(0, failure.rowIndex());
    }

    @Test void roleMapIsSnapshottedBeforeSourceMutatesItAndOnlyOnePendingRowIsRead() throws IOException {
        var roles = new HashMap<String, FontGroup>(); roles.put("bold", bold);
        var spec = table().toBuilder().header("HEAD").build();
        var source = new Source(5, index -> {
            roles.clear();
            return row(rich(new InlineText("R" + index, role("bold"))));
        });
        var pages = new ArrayList<LayoutPage>();
        var failure = new IOException("page failure");
        assertSame(failure, assertThrows(IOException.class, () -> new StreamingTableLayouter().layout(spec, source, regular, roles,
                page -> { pages.add(page); assertEquals(4, source.reads); throw failure; }, diagnostic -> { }, StreamingTableLimits.DEFAULT)));
        assertEquals(4, source.reads); assertEquals(0, source.closes); assertEquals(1, pages.size());
        assertTrue(rows(pages.getFirst()).stream().filter(value -> !value.header()).allMatch(value ->
                value.cells().getFirst().layout().lines().getFirst().runs().getFirst().font().id().equals(bold.fonts().getFirst().data().id())));
    }

    @Test void invalidRoleRegistrationsFailBeforeAnySourceRead() {
        for (String name : List.of("", " ", "\t")) {
            var source = new Source(1, index -> row(new TableCell("A", FORMAT)));
            assertThrows(IllegalArgumentException.class, () -> layout(table(), source, Map.of(name, bold),
                    new ArrayList<>(), StreamingTableLimits.DEFAULT));
            assertEquals(0, source.reads);
        }
        var nullValue = new HashMap<String, FontGroup>(); nullValue.put("bold", null);
        var nullKey = new HashMap<String, FontGroup>(); nullKey.put(null, bold);
        for (var roles : List.of(nullValue, nullKey)) {
            var source = new Source(1, index -> row(new TableCell("A", FORMAT)));
            assertThrows(NullPointerException.class, () -> layout(table(), source, roles, new ArrayList<>(), StreamingTableLimits.DEFAULT));
            assertEquals(0, source.reads);
        }
    }

    @Test void mixedArabicAndChineseUseSharedComplexWrappingAndKeepOriginalSource() throws IOException {
        var format = CellFormat.builder().fontSize(10).lineHeight(32).build();
        var inlines = List.of(new InlineText("L ", PLAIN), new InlineText("العربية ", role("arabic")),
                new InlineText("中文", role("chinese")), new InlineText(" R", PLAIN));
        var source = new Source(1, index -> row(TableCell.rich(inlines, format)));
        var pages = new ArrayList<LayoutPage>();
        var roles = Map.of("arabic", arabic, "chinese", chinese);
        layout(table(), source, roles, pages, StreamingTableLimits.DEFAULT);
        var expected = new ParagraphLayouter().layout(inlines.stream().map(value -> new StyledSpan(value.text(),
                value.style().fontRole().isEmpty() ? regular : roles.get(value.style().fontRole()), 10, 0, 0, false, false)).toList(),
                160, ParagraphStyle.builder().fontSize(10).lineHeight(32).build());
        var actual = rows(pages.getFirst()).getFirst().cells().getFirst().layout();
        assertEquals(expected, actual);
        assertTrue(actual.lines().stream().anyMatch(line -> !line.outlines().isEmpty()));
        assertEquals(0, actual.lines().getFirst().sourceStart());
        assertEquals(inlines.stream().map(InlineText::text).collect(java.util.stream.Collectors.joining()).length(), actual.lines().getLast().sourceEnd());
    }

    @Test void richRowsRemainIndivisibleAndOversizedFirstRowNeverEmitsHeaderOnlyPage() {
        var spec = table().toBuilder().header("HEAD").build();
        var source = new Source(1, index -> row(rich(new InlineText("A\nB\nC\nD", role("bold")))));
        var pages = new ArrayList<LayoutPage>();
        var failure = assertThrows(DocumentLayoutException.class,
                () -> layout(spec, source, Map.of("bold", bold), pages, StreamingTableLimits.DEFAULT));
        assertEquals(DocumentLayoutException.Code.CONTENT_TOO_TALL, failure.code());
        assertEquals(1, failure.rowIndex()); assertEquals(1, source.reads); assertTrue(pages.isEmpty());
    }

    @Test void oldLayoutAndFourArgumentLimitsRemainCompatibleWhileNewCapsSnapshot() throws IOException {
        var legacy = new StreamingTableLimits(DocumentLayoutLimits.DEFAULT, 100, 200, 300);
        assertEquals(StreamingTableLimits.DEFAULT.maxCellInlines(), legacy.maxCellInlines());
        assertEquals(StreamingTableLimits.DEFAULT.maxRowInlines(), legacy.maxRowInlines());
        var builder = legacy.toBuilder().maxCellInlines(3).maxRowInlines(7); var limits = builder.build();
        builder.maxCellInlines(1).maxRowInlines(1);
        assertEquals(3, limits.maxCellInlines()); assertEquals(7, limits.maxRowInlines());
        assertEquals(limits, limits.toBuilder().build());
        assertThrows(IllegalArgumentException.class, () -> legacy.toBuilder().maxCellInlines(0).build());
        assertThrows(IllegalArgumentException.class, () -> legacy.toBuilder().maxRowInlines(-1).build());
        var pages = new ArrayList<LayoutPage>();
        var summary = new StreamingTableLayouter().layout(table(), new Source(1, index -> row(rich(new InlineText("R", PLAIN)))),
                regular, pages::add, diagnostic -> { }, legacy);
        assertEquals(1, summary.bodyRows()); assertEquals(1, summary.sourceCodePoints()); assertEquals(1, pages.size());
    }
}
