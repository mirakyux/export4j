// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.ParagraphStyle;

class FluentAuthoringTest {
    private static FontGroup fonts;
    @TempDir Path directory;

    @BeforeAll
    static void fonts() throws IOException {
        try (var input = FluentAuthoringTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.builder().font(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)).build();
        }
    }

    private static ComponentContext context() { return new ComponentContext(200, 300, fonts, 0, 0, 1); }

    @Test
    void rowsAutoPlaceAroundSpansAndTakeCallbackSnapshots() {
        var heldRow = new AtomicReference<GridTable.RowBuilder>();
        var heldCell = new AtomicReference<GridTable.CellBuilder>();
        var heldDefaults = new AtomicReference<GridTable.CellBuilder>();
        var builder = GridTable.builder().columns(1, 2, 1)
                .defaults(cell -> { heldDefaults.set(cell); cell.padding(2); })
                .row(row -> {
                    heldRow.set(row);
                    row.minHeight(30).cell("A", cell -> { heldCell.set(cell); cell.rowSpan(2); })
                            .cell("B", cell -> cell.columnSpan(2));
                })
                .row(row -> row.minHeight(40).cells("C", "D"));
        heldRow.get().minHeight(500).cell("late");
        heldCell.get().columnSpan(3).background(RgbColor.BLACK);
        heldDefaults.get().padding(100);
        var table = builder.build();
        assertEquals(List.of(30.0, 40.0), table.minimumRowHeights());
        assertEquals(List.of(0, 1, 1, 2), table.cells().stream().map(GridTable.Cell::column).toList());
        assertEquals(2, table.cells().getFirst().rowSpan());
        assertEquals(PageMargins.uniform(2), table.cells().getLast().padding());
        assertEquals(70, table.layout(context()).height());
        builder.row(row -> row.cells("E", "F", "G"));
        assertEquals(2, table.rowCount());
        assertEquals(3, builder.build().rowCount());
        assertThrows(UnsupportedOperationException.class, () -> table.minimumRowHeights().clear());
    }

    @Test
    void holesOverflowSpanCollisionsAndHeaderCrossingsFailExplicitly() {
        assertThrows(IllegalArgumentException.class, () -> GridTable.builder().columns(1, 1).row(row -> row.cell("hole")).build());
        assertThrows(IllegalArgumentException.class, () -> GridTable.builder().columns(1).row(row -> row.cells("A", "B")).build());
        assertThrows(IllegalArgumentException.class, () -> GridTable.builder().row(row -> row.cell("A", cell -> cell.rowSpan(2))).build());
        assertThrows(IllegalArgumentException.class, () -> GridTable.builder().columns(1, 1, 1)
                .row(row -> row.cell("A").cell("B", cell -> cell.rowSpan(2)).cell("C"))
                .row(row -> row.cell("overlap", cell -> cell.columnSpan(2))).build());
        assertThrows(IllegalArgumentException.class, () -> GridTable.builder().columns(1, 1).headerRows(1)
                .row(row -> row.cell("A", cell -> cell.rowSpan(2)).cell("B"))
                .row(row -> row.cell("C")).build());
        assertThrows(IllegalArgumentException.class, () -> GridTable.builder().row(row -> row.minHeight(Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> GridTable.builder().row(row -> row.cell("A", cell -> cell.rowSpan(0))));
    }

    @Test
    void mergedHeightAndEveryCellRectangleAreIndependentOfDeclarationOrder() {
        var table = GridTable.builder().columns(1, 1).defaults(cell -> cell.padding(0))
                .row(row -> row.cell(new Spacer(60), cell -> cell.rowSpan(2)).cell(new Spacer(10)))
                .row(row -> row.cell(new Spacer(50))).build();
        var reversed = new ArrayList<>(table.cells());
        Collections.reverse(reversed);
        var same = new GridTable(2, table.columnWeights(), 0, reversed);
        assertEquals(60, table.layout(context()).height());
        assertEquals(table.layout(context()).height(), same.layout(context()).height());
        var order = Comparator.comparingDouble(com.mirakyux.export4j.graphics.Rect::x)
                .thenComparingDouble(com.mirakyux.export4j.graphics.Rect::y);
        var first = table.layout(context()).commands().stream().filter(DrawCommand.FillRect.class::isInstance)
                .map(DrawCommand.FillRect.class::cast).map(DrawCommand.FillRect::bounds).sorted(order).toList();
        var second = same.layout(context()).commands().stream().filter(DrawCommand.FillRect.class::isInstance)
                .map(DrawCommand.FillRect.class::cast).map(DrawCommand.FillRect::bounds).sorted(order).toList();
        assertEquals(first, second);
    }

    @Test
    void nestedAuthoringKeepsSourceOrderAndMinimumTextHeight() {
        var document = Documents.builder().title("Fluent").author("test")
                .column(column -> column.fixedText("A", text -> text.minHeight(30))
                        .column(inner -> inner.text("B")).row(row -> row.text("C").text("D")))
                .paragraph("E").build();
        var result = DocumentEngine.builder(fonts).build().layout(document);
        assertEquals("ABCDE", text(result));
        assertEquals("Fluent", document.metadata().title());
        assertEquals(30, TextComponent.builder("A").minHeight(30).build().layout(context()).height());
        assertThrows(IllegalArgumentException.class, () -> TextComponent.builder("A").minHeight(-1).build());
    }

    @Test
    void formBuildersPreserveInitialValuesAndSnapshotOptionLists() {
        var target = new Navigation.BlockTarget("body", new com.mirakyux.export4j.graphics.Rect(0, 0, 90, 20));
        var options = new ArrayList<>(List.of("A", "B"));
        var choice = Navigation.ChoiceField.builder().target(target).name("choice").options(options).value("B");
        options.clear();
        var navigation = Navigation.builder()
                .field(Navigation.TextField.builder().target(target).name("text").value("INITIAL").multiline(true).build())
                .field(choice.build())
                .field(Navigation.RadioGroup.builder().target(target).name("radio").options(List.of("A", "B")).value("B").build())
                .field(Navigation.CheckBox.builder().target(target).name("checked").checked(true).build()).build();
        var doc = Document.builder().paragraph("body", "A").build();
        var result = DocumentEngine.builder(fonts).navigation(navigation).build().layout(doc);
        var fields = result.display().extras().fields();
        assertEquals(4, fields.size());
        assertEquals("INITIAL", ((com.mirakyux.export4j.graphics.DocumentExtras.TextField) fields.get(0)).value());
        assertEquals("B", ((com.mirakyux.export4j.graphics.DocumentExtras.ChoiceField) fields.get(1)).value());
        assertEquals("B", ((com.mirakyux.export4j.graphics.DocumentExtras.RadioGroup) fields.get(2)).value());
        assertTrue(((com.mirakyux.export4j.graphics.DocumentExtras.CheckBox) fields.get(3)).checked());
    }

    @Test
    void configuredTemplateReservesBodyAndResolvesFinalFooterThroughExport() throws IOException {
        var template = PageTemplate.builder().footer(footer -> footer.height(16).gap(4)
                .content(PageNumberComponent.builder("P{page}/{pages}").style(ParagraphStyle.builder().fontSize(10).lineHeight(16).build()).build())).build();
        var settings = PageSettings.builder().size(140, 100).margins(10).build();
        var doc = Document.builder().pageSettings(settings).bodyFormat(format -> format.fontSize(10).lineHeight(20).spaceAfter(0))
                .paragraph("A\nB\nC\nD").build();
        var engineBuilder = DocumentEngine.builder(fonts).pageTemplate(template);
        var engine = engineBuilder.build();
        engineBuilder.pageTemplate(PageTemplate.EMPTY);
        var result = engine.export(doc).backend((display, out) -> out.write(1)).to(new ByteArrayOutputStream());
        assertEquals(2, result.display().pages().size());
        assertTrue(text(result).contains("P1/2"));
        assertTrue(text(result).contains("P2/2"));
        assertEquals(60, template.bodySettings(settings).contentHeight());
    }

    @Test
    void fluentExportPreservesAtomicTargetExecutionLimitsAndBorrowedStreamOwnership() throws IOException {
        var engine = DocumentEngine.builder(fonts).build();
        var doc = Document.builder().paragraph("A").build();
        Path target = directory.resolve("report.pdf");
        Files.writeString(target, "previous");
        assertThrows(IOException.class, () -> engine.export(doc).backend((display, out) -> {
            out.write(new byte[]{1, 2, 3}); throw new IOException("failed backend");
        }).to(target));
        assertEquals("previous", Files.readString(target));
        try (var paths = Files.list(directory)) { assertEquals(List.of(target), paths.toList()); }
        var limited = assertThrows(com.mirakyux.export4j.model.ExecutionException.class,
                () -> engine.export(doc).backend((display, out) -> out.write(new byte[]{1, 2, 3}))
                        .options(options -> options.maxOutputBytes(2)).to(target));
        assertEquals(com.mirakyux.export4j.model.ExecutionException.Code.OUTPUT_LIMIT, limited.code());
        assertEquals("previous", Files.readString(target));
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var borrowed = new ByteArrayOutputStream() { @Override public void close() { closed.set(true); } };
        engine.export(doc).backend((display, out) -> out.write(1)).to(borrowed);
        assertFalse(closed.get());
        assertThrows(NullPointerException.class, () -> engine.export(doc).to(target));
    }

    private static String text(ComponentDocumentLayout layout) {
        return layout.display().pages().stream().flatMap(page -> page.commands().stream())
                .filter(DrawCommand.GlyphText.class::isInstance).map(DrawCommand.GlyphText.class::cast)
                .flatMap(command -> command.run().glyphs().stream()).map(com.mirakyux.export4j.text.PositionedGlyph::text)
                .collect(java.util.stream.Collectors.joining());
    }
}
