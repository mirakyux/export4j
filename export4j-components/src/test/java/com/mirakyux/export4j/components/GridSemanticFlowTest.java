// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.CellFragment;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.SemanticPlacement;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.ComponentSemantics;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.StyledSpan;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextOutline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GridSemanticFlowTest {
    private static final PageSettings PAGE = new PageSettings(new PageSize(400, 240), PageMargins.uniform(20));
    private static final CellFormat CELL = new CellFormat(10, 20, ParagraphAlignment.LEFT);
    private static FontGroup fonts;
    private static FontFace bold;

    @BeforeAll static void fonts() throws IOException {
        fonts = FontGroup.strict(List.of(face("LiberationSans-Regular.ttf")));
        bold = face("LiberationSans-Bold.ttf");
    }

    @Test void longMergedCellKeepsRichContentFieldImageLinksAndOriginalCellLocations() throws IOException {
        List<InlineContent> contents = new ArrayList<>();
        for (int line = 0; line < 34; line++) {
            if (line > 0) { contents.add(text("\n")); }
            contents.add(text("L" + line + " "));
            switch (line) {
                case 11 -> contents.add(new InlineText("BOLD", new InlineStyle("bold", 10, 0x224488, 0, false, false),
                        "https://example.com/text"));
                case 20 -> contents.add(text("A😀"));
                case 23 -> contents.add(image());
                case 25 -> { contents.add(text("P=")); contents.add(field(InlineField.Kind.PAGE, "")); }
                default -> contents.add(text("BODY"));
            }
        }
        var sourceCell = TableCell.mixed(contents, CELL);
        var table = Table.builder("rich-cell").cellFormat(CELL).format(format -> format.allowRowSplit(true))
                .header("INNER HEADER").row(sourceCell).build();
        var cellFlow = FlowColumn.builder().padding(2).semanticTable(table).build();
        var grid = merged(cellFlow, true);
        var local = FontGroup.placeholder(fonts.fonts(), '?');
        var role = FontGroup.strict(List.of(bold));
        var expectedBold = new ParagraphLayouter().layoutOpenType(List.of(
                new StyledSpan("L11 ", local, 10, 0, 0, false, false),
                new StyledSpan("BOLD", role, 10, 0x224488, 0, false, false)), 300, ParagraphStyle.of(10))
                .lines().getFirst().outlines().stream().filter(outline -> outline.logicalText().equals("BOLD"))
                .findFirst().orElseThrow();
        var result = DocumentEngine.builder(local).fontRole("bold", role).build().layout(document(grid));
        var placements = placements(result);
        assertTrue(placements.size() > 2);
        int nextLine = 0;
        int images = 0;
        boolean foundRole = false;
        for (int index = 0; index < placements.size(); index++) {
            var placed = placements.get(index);
            assertSame(table, placed.source());
            assertEquals(index, placed.fragmentIndex());
            assertEquals(placements.size(), placed.fragmentCount());
            assertEquals(local, placed.fonts());
            assertEquals(role, placed.roles().get("bold"));
            var header = placed.fragment().rows().getFirst();
            assertTrue(header.header());
            assertEquals(index > 0, header.repeatedHeader());
            for (var row : placed.fragment().rows()) {
                if (row.header()) { continue; }
                assertEquals(1, row.rowIndex());
                var cell = row.cells().getFirst();
                assertEquals(0, cell.columnIndex());
                assertEquals(nextLine, cell.firstLineIndex());
                assertEquals(nextLine > 0, row.continuedRow());
                nextLine += cell.layout().lines().size();
                images += cell.layout().lines().stream().mapToInt(line -> line.images().size()).sum();
                for (var outline : cell.layout().lines().stream().flatMap(line -> line.outlines().stream()).toList()) {
                    if (outline.logicalText().equals("BOLD")) {
                        assertEquals(expectedBold, outline);
                        foundRole = true;
                    }
                }
                assertTrue(cell.layout().lines().stream().allMatch(line -> line.fields().isEmpty()));
            }
        }
        assertEquals(34, nextLine);
        assertEquals(1, images);
        assertTrue(foundRole);
        assertEquals(2, result.display().extras().externalLinks().size());
        var imageLinks = result.display().extras().externalLinks().stream()
                .filter(link -> link.destination().uri().equals("https://example.com/image")).toList();
        assertEquals(1, imageLinks.size());
        assertEquals(14, imageLinks.getFirst().bounds().width());
        assertEquals(14, imageLinks.getFirst().bounds().height());
        var warning = result.contextualDiagnostics().stream()
                .filter(item -> item.diagnostic().code() == TextDiagnostic.Code.MISSING_GLYPH_REPLACED).toList();
        assertEquals(1, warning.size());
        assertEquals("rich-cell", warning.getFirst().blockId());
        assertEquals("TABLE", warning.getFirst().region());
        assertEquals(1, warning.getFirst().rowIndex());
        assertEquals(0, warning.getFirst().columnIndex());
        assertEquals(sourceCell.text().indexOf("😀"), warning.getFirst().diagnostic().sourceStart());
        assertEquals(sourceCell.text().indexOf("😀") + 2, warning.getFirst().diagnostic().sourceEnd());
        assertTrue(warning.getFirst().pageIndex() > 0);
        int fieldIndex = sourceCell.fields().getFirst().sourceIndex();
        boolean foundField = false;
        for (var page : result.body().pages()) {
            for (var element : page.elements()) {
                if (!(element instanceof ComponentFragment component)) { continue; }
                for (var located : ComponentSemantics.locate(component.layout(), component.origin())) {
                    for (var row : located.placement().fragment().rows()) {
                        if (row.header()) { continue; }
                        for (var line : row.cells().getFirst().layout().lines()) {
                            if (line.sourceStart() <= fieldIndex && line.sourceEnd() > fieldIndex) {
                                assertEquals("L25 P=" + (page.pageIndex() + 1), logicalLine(line));
                                foundField = true;
                            }
                        }
                    }
                }
            }
        }
        assertTrue(foundField);
    }

    @Test void differentlySizedShortRegionsConsumeCompleteLinesWithoutReshapingOrLosingSource() {
        var table = splitTable("short-regions", 30);
        var grid = merged(FlowColumn.builder().padding(2).semanticTable(table).build(), true);
        var context = new ComponentContext(300, 200, fonts, 0, 0, 1);
        var regions = new FlowRegions(List.of(75.0, 112.0), 200);
        var parts = grid.layoutRegions(context, regions);
        assertEquals(List.of(0, 1), parts.subList(0, 2).stream().map(part -> part.regionIndex()).toList());
        var cells = new ArrayList<CellFragment>();
        for (var part : parts) {
            assertTrue(part.layout().height() <= Math.nextUp(regions.height(part.regionIndex())));
            assertFalse(part.layout().semanticPlacements().isEmpty());
            for (var placed : part.layout().semanticPlacements()) {
                assertEquals("H", logical(placed.fragment().rows().getFirst().cells().getFirst().layout()));
                placed.fragment().rows().stream().filter(row -> !row.header())
                        .forEach(row -> cells.add(row.cells().getFirst()));
            }
        }
        int next = 0;
        for (var cell : cells) {
            assertEquals(next, cell.firstLineIndex());
            next += cell.layout().lines().size();
        }
        assertEquals(30, next);
        assertEquals(IntStream.range(0, 30).mapToObj(index -> "L" + index).collect(Collectors.joining()),
                cells.stream().map(cell -> logical(cell.layout())).collect(Collectors.joining()));
        var placements = parts.stream().flatMap(part -> part.layout().semanticPlacements().stream()).toList();
        for (int index = 0; index < placements.size(); index++) {
            assertEquals(index, placements.get(index).fragmentIndex());
            assertEquals(placements.size(), placements.get(index).fragmentCount());
        }
    }

    @Test void columnsResolveInnerHeadersAfterContentsOffsetAndRestartedTargetPageNumbers() {
        var seed = Table.builder("column-cell").cellFormat(CELL).format(format -> format.allowRowSplit(true))
                .row(TableCell.mixed(List.of(text("P="), field(InlineField.Kind.PAGE, ""), text(" T="),
                        field(InlineField.Kind.PAGES, ""), text(" R="), field(InlineField.Kind.REFERENCE_PAGE, "target")), CELL))
                .row(lines(32)).build();
        var table = new Table(seed.id(), seed.columns(), seed.rows(), 1, seed.format());
        var grid = GridTable.builder().columns(1).allowRowSplit(true).defaults(cell -> cell.padding(3))
                .row(row -> row.cell(FlowColumn.builder().padding(2).semanticTable(table).build(), cell -> cell.rowSpan(2)))
                .row(row -> { }).build();
        var columns = ContinuousColumns.builder(grid)
                .gap(12).build();
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(
                new Section("first", PAGE, List.of(new ComponentBlock("lead", new Spacer(110), 0, 0),
                        new ComponentBlock("host", columns, 0, 0)), 0),
                new Section("second", PAGE, List.of(new Paragraph("target", "TARGET", ParagraphFormat.of(10))), 7)));
        var navigation = Navigation.builder().bookmark("Grid", table.id(), 1).bookmark("Target", "target", 1)
                .contents(new TableOfContents(true, "Contents", ParagraphStyle.of(10))).build();
        var result = DocumentEngine.builder(fonts).navigation(navigation).build().layout(source);
        assertEquals(1, result.bodyPageOffset());
        var all = placements(result);
        assertTrue(all.size() > 2);
        assertTrue(result.body().pages().getFirst().elements().stream().filter(ComponentFragment.class::isInstance)
                .map(ComponentFragment.class::cast).anyMatch(component -> component.layout().semanticPlacements().size() == 2));
        int next = 0;
        for (var page : result.body().pages()) {
            for (var element : page.elements()) {
                if (!(element instanceof ComponentFragment component)) { continue; }
                for (var located : ComponentSemantics.locate(component.layout(), component.origin())) {
                    var placed = located.placement();
                    assertEquals(next++, placed.fragmentIndex());
                    assertEquals(all.size(), placed.fragmentCount());
                    assertEquals("P=" + (page.pageIndex() + 2) + " T=" + result.display().pages().size() + " R=7",
                            logical(placed.fragment().rows().getFirst().cells().getFirst().layout()));
                }
            }
        }
        assertEquals(all.size(), next);
        assertEquals(1, result.display().extras().bookmarks().getFirst().pageIndex());
        assertTrue(result.display().extras().links().stream().anyMatch(link -> link.pageIndex() > 0
                && link.targetPageIndex() == result.display().pages().size() - 1));
    }

    @Test void outerAndInnerRowSplittingAreIndependentExplicitRequirements() {
        var split = splitTable("split", 30);
        var noSplit = Table.builder("no-split").cellFormat(CELL).header("H").row(lines(30)).build();
        var context = new ComponentContext(300, 200, fonts, 0, 0, 1);
        var regions = new FlowRegions(List.of(), 200);
        assertThrows(IllegalArgumentException.class, () -> merged(new SemanticTable(split), false)
                .layoutRegions(context, regions));
        assertThrows(IllegalArgumentException.class, () -> merged(new SemanticTable(noSplit), true)
                .layoutRegions(context, regions));
        assertTrue(merged(new SemanticTable(split), true).layoutRegions(context, regions).size() > 1);
    }

    @Test void separateCellsChildrenAndInstancesCannotLaunderDuplicateSemanticSources() {
        var one = Table.builder("duplicate").cellFormat(CELL).row("ONE").build();
        var other = Table.builder("duplicate").cellFormat(CELL).row("TWO").build();
        for (boolean split : List.of(false, true)) {
            var cells = GridTable.builder().columns(1, 1).allowRowSplit(split)
                    .row(row -> row.semanticTable(one).semanticTable(one)).build();
            var children = GridTable.builder().columns(1).allowRowSplit(split)
                    .row(row -> row.cell(FlowColumn.builder().semanticTable(one).semanticTable(one).build())).build();
            var instances = GridTable.builder().columns(1, 1).allowRowSplit(split)
                    .row(row -> row.semanticTable(one).semanticTable(other)).build();
            var headerAndBody = GridTable.builder().columns(1).headerRows(1).allowRowSplit(split)
                    .row(row -> row.semanticTable(one)).row(row -> row.semanticTable(one)).build();
            var twoHeaders = GridTable.builder().columns(1, 1).headerRows(1).allowRowSplit(split)
                    .row(row -> row.semanticTable(one).semanticTable(one)).row(row -> row.cells("A", "B")).build();
            var firstGrid = GridTable.builder().columns(1).allowRowSplit(split).row(row -> row.semanticTable(one)).build();
            var secondGrid = GridTable.builder().columns(1).allowRowSplit(split).row(row -> row.semanticTable(one)).build();
            var acrossGrids = FlowColumn.builder().add(firstGrid).add(secondGrid).build();
            for (var invalid : List.of(cells, children, instances, headerAndBody, twoHeaders, acrossGrids)) {
                assertThrows(IllegalArgumentException.class, () -> DocumentEngine.builder(fonts).build().layout(document(invalid)));
            }
        }
    }

    @Test void outerCachedHeaderAndInnerContinuationHeaderUseTheirOwnActualPagePresentations() {
        var outer = Table.builder("outer-header").row(TableCell.mixed(List.of(text("OUTER="), field(InlineField.Kind.PAGE, "")), CELL)).build();
        var seed = Table.builder("inner-body").cellFormat(CELL).format(format -> format.allowRowSplit(true))
                .row(TableCell.mixed(List.of(text("INNER="), field(InlineField.Kind.PAGE, "")), CELL)).row(lines(30)).build();
        var inner = new Table(seed.id(), seed.columns(), seed.rows(), 1, seed.format());
        var grid = GridTable.builder().columns(1).allowRowSplit(true).headerRows(1).defaults(cell -> cell.padding(3))
                .row(row -> row.semanticTable(outer))
                .row(row -> row.cell(FlowColumn.builder().padding(2).semanticTable(inner).build(), cell -> cell.rowSpan(2)))
                .row(row -> { }).build();
        var result = DocumentEngine.builder(fonts).build().layout(document(grid));
        assertTrue(result.body().pages().size() > 2);
        int outerIndex = 0;
        int innerIndex = 0;
        for (var page : result.body().pages()) {
            for (var element : page.elements()) {
                if (!(element instanceof ComponentFragment component)) { continue; }
                var placed = component.layout().semanticPlacements();
                assertEquals(2, placed.size());
                assertEquals(outerIndex++, placed.getFirst().fragmentIndex());
                assertEquals(innerIndex++, placed.getLast().fragmentIndex());
                assertEquals("OUTER=" + (page.pageIndex() + 1), logical(placed.getFirst().fragment().rows().getFirst().cells().getFirst().layout()));
                var header = placed.getLast().fragment().rows().getFirst();
                assertEquals("INNER=" + (page.pageIndex() + 1), logical(header.cells().getFirst().layout()));
                assertEquals(innerIndex > 1, header.repeatedHeader());
                for (var item : placed) { assertEquals(result.body().pages().size(), item.fragmentCount()); }
            }
        }
        assertEquals(result.body().pages().size(), outerIndex);
        assertEquals(outerIndex, innerIndex);
    }

    @Test void exactSourceBudgetsCountOnceWhileRepeatedSemanticHeadersSpendPresentationLines() {
        var outer = Table.builder("budget-header").cellFormat(CELL).row("").build();
        var builder = Table.builder("budget-body").cellFormat(CELL).header("");
        for (int row = 0; row < 30; row++) { builder.row("ROW"); }
        var grid = GridTable.builder().columns(1).allowRowSplit(true).headerRows(1).defaults(cell -> cell.padding(3))
                .row(row -> row.semanticTable(outer)).row(row -> row.semanticTable(builder.build(), cell -> cell.rowSpan(2)))
                .row(row -> { }).build();
        var document = document(grid);
        var exactSources = new DocumentLayoutLimits(3, 90, 100, 20, 31, 1, 32);
        var result = new ComponentDocumentRenderer(exactSources, ComponentLimits.DEFAULT)
                .layout(document, PageTemplate.EMPTY, Navigation.EMPTY, fonts);
        assertTrue(result.body().pages().size() > 1);
        long lines = placements(result).stream().flatMap(placed -> placed.fragment().rows().stream())
                .flatMap(row -> row.cells().stream()).mapToLong(cell -> cell.layout().lines().size()).sum();
        assertTrue(lines > 32);
        var presentationLimit = new DocumentLayoutLimits(3, 90, 32, 20, 31, 1, 32);
        var lineFailure = assertThrows(DocumentLayoutException.class,
                () -> new ComponentDocumentRenderer(presentationLimit, ComponentLimits.DEFAULT)
                        .layout(document, PageTemplate.EMPTY, Navigation.EMPTY, fonts));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, lineFailure.code());
        var sourceLimit = new DocumentLayoutLimits(3, 89, 100, 20, 31, 1, 32);
        var sourceFailure = assertThrows(DocumentLayoutException.class,
                () -> new ComponentDocumentRenderer(sourceLimit, ComponentLimits.DEFAULT)
                        .layout(document, PageTemplate.EMPTY, Navigation.EMPTY, fonts));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, sourceFailure.code());
        var placementFailure = assertThrows(DocumentLayoutException.class,
                () -> new ComponentDocumentRenderer(exactSources,
                        new ComponentLimits(1, ComponentLimits.DEFAULT.maxCommands(), ComponentLimits.DEFAULT.maxInteractions()))
                        .layout(document, PageTemplate.EMPTY, Navigation.EMPTY, fonts));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, placementFailure.code());
    }

    @Test void cancellingPreparedSemanticCellRestoresScopeAndLeavesReusableSourceUnchanged() {
        var table = splitTable("cancel", 40);
        var grid = merged(FlowColumn.builder().padding(2).semanticTable(table).build(), true);
        var context = new ComponentContext(300, 200, fonts, 0, 0, 1);
        var regions = new FlowRegions(List.of(), 200);
        var checks = new AtomicInteger();
        var options = ExecutionOptions.builder().cancelled(() -> checks.incrementAndGet() > 8).build();
        var failure = assertThrows(ExecutionException.class,
                () -> ExecutionContext.execute(options, () -> grid.layoutRegions(context, regions)));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        ExecutionContext.checkpoint();
        var retried = grid.layoutRegions(context, regions);
        assertTrue(retried.size() > 1);
        assertEquals(0, retried.getFirst().layout().semanticPlacements().getFirst().fragmentIndex());
        assertEquals(40, retried.stream().flatMap(part -> part.layout().semanticPlacements().stream())
                .flatMap(placed -> placed.fragment().rows().stream()).filter(row -> !row.header())
                .mapToInt(row -> row.cells().getFirst().layout().lines().size()).sum());
    }

    private static GridTable merged(Component content, boolean split) {
        return GridTable.builder().columns(1, 1).allowRowSplit(split).defaults(cell -> cell.padding(3))
                .row(row -> row.cell(content, cell -> cell.rowSpan(2)).cell("SIDE-A"))
                .row(row -> row.cell("SIDE-B")).build();
    }

    private static Table splitTable(String id, int lines) {
        return Table.builder(id).cellFormat(CELL).format(format -> format.allowRowSplit(true)).header("H").row(lines(lines)).build();
    }

    private static String lines(int count) {
        return IntStream.range(0, count).mapToObj(index -> "L" + index).collect(Collectors.joining("\n"));
    }

    private static Document document(Component component) {
        return Documents.builder().pageSettings(PAGE).component("host", component).build();
    }

    private static List<SemanticPlacement> placements(ComponentDocumentLayout result) {
        return result.body().pages().stream().flatMap(page -> page.elements().stream()).filter(ComponentFragment.class::isInstance)
                .map(ComponentFragment.class::cast).flatMap(component -> component.layout().semanticPlacements().stream()).toList();
    }

    private static InlineText text(String value) { return new InlineText(value, InlineStyle.of(10)); }
    private static InlineField field(InlineField.Kind kind, String target) { return new InlineField(kind, target, 18, InlineStyle.of(10)); }

    private static InlineImage image() throws IOException {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        return new InlineImage(ImageResource.load(output.toByteArray()), 14, 14, 0, "Synthetic image", "https://example.com/image");
    }

    private static FontFace face(String name) throws IOException {
        try (var input = GridSemanticFlowTest.class.getResourceAsStream("/fonts/" + name)) {
            return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
    }

    private static String logical(ParagraphLayout layout) {
        return layout.lines().stream().map(GridSemanticFlowTest::logicalLine).collect(Collectors.joining());
    }

    private static String logicalLine(com.mirakyux.export4j.text.TextLine line) {
        return line.outlines().isEmpty() ? line.runs().stream().map(run -> run.text()).collect(Collectors.joining())
                : line.outlines().stream().map(TextOutline::logicalText).collect(Collectors.joining());
    }
}
