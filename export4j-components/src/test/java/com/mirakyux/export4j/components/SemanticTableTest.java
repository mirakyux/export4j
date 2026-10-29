// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.ComponentSemantics;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.PageBand;
import com.mirakyux.export4j.layout.core.PageComposer;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.layout.core.Placement;
import com.mirakyux.export4j.layout.core.Position;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
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
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextOutline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticTableTest {
    private static final PageSettings PAGE = new PageSettings(new PageSize(420, 300), PageMargins.uniform(20));
    private static final CellFormat CELL = new CellFormat(10, 24, ParagraphAlignment.LEFT);
    private static FontGroup fonts;
    private static FontFace bold;

    @BeforeAll static void fonts() throws IOException {
        fonts = FontGroup.strict(List.of(face("LiberationSans-Regular.ttf")));
        bold = face("LiberationSans-Bold.ttf");
    }

    @Test void finalFieldsAndNestedTargetsFollowContentsOffsetAndRestartedSectionNumbers() {
        var table = table("nested", List.of(text("PAGE="), field(InlineField.Kind.PAGE, ""), text(" TOTAL="),
                field(InlineField.Kind.PAGES, ""), text(" TARGET="), field(InlineField.Kind.REFERENCE_PAGE, "target")));
        var nested = FlowColumn.builder().padding(7).column(column -> column.padding(5).semanticTable(table)).build();
        var back = Paragraph.mixed("back", List.of(text("BACK="), field(InlineField.Kind.REFERENCE_PAGE, "nested")), ParagraphFormat.of(10));
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(
                new Section("first", PAGE, List.of(new ComponentBlock("host", nested, 0, 0)), 0),
                new Section("second", PAGE, List.of(new Paragraph("target", "TARGET", ParagraphFormat.of(10)), back), 7)));
        var nav = Navigation.builder().bookmark("Nested table", "nested", 1).bookmark("Target", "target", 1)
                .contents(new TableOfContents(true, "Contents", ParagraphStyle.of(10))).build();
        var result = DocumentEngine.builder(fonts).navigation(nav).build().layout(source);
        assertEquals(1, result.bodyPageOffset());
        assertEquals(3, result.display().pages().size());
        var component = (ComponentFragment) result.body().pages().getFirst().elements().getFirst();
        var placement = component.layout().semanticPlacements().getFirst();
        assertEquals(table, placement.source());
        assertEquals("PAGE=2 TOTAL=3 TARGET=7", logical(placement.fragment().rows().getFirst().cells().getFirst().layout()));
        assertTrue(placement.fragment().rows().stream().flatMap(row -> row.cells().stream())
                .flatMap(cell -> cell.layout().lines().stream()).allMatch(line -> line.fields().isEmpty()));
        assertTrue(result.display().extras().links().stream().anyMatch(link -> link.pageIndex() == 1 && link.targetPageIndex() == 2));
        var toNested = result.display().extras().links().stream().filter(link -> link.pageIndex() == 2 && link.targetPageIndex() == 1).findFirst().orElseThrow();
        assertEquals(new Point(32, 32), toNested.destination());
        assertEquals(List.of(1, 2), result.display().extras().bookmarks().stream().map(bookmark -> bookmark.pageIndex()).toList());
    }

    @Test void borderlessComponentTablesKeepOutlinesDisabledAfterIndexShiftAndFinalFieldResolution() {
        var sourceTable = Table.builder("borderless").cellFormat(CELL)
                .columns(new ColumnWidth.Fixed(150)).format(format -> format.drawBorders(false))
                .row(TableCell.mixed(List.of(text("PAGE="), field(InlineField.Kind.PAGE, "")), CELL)).build();
        var component = FlowColumn.builder().semanticTable(sourceTable).build();
        var source = new Document(DocumentMetadata.EMPTY, PAGE, List.of(
                new Paragraph("prefix", "Before", ParagraphFormat.of(10)),
                new ComponentBlock("host", component, 0, 0)));
        var result = DocumentEngine.builder(fonts).build().layout(source);
        var fragment = (ComponentFragment) result.body().pages().getFirst().elements().getLast();
        var placement = fragment.layout().semanticPlacements().getFirst();
        assertFalse(placement.fragment().drawBorders());
        assertEquals("PAGE=1", logical(placement.fragment().rows().getFirst().cells().getFirst().layout()));
        assertTrue(result.display().pages().stream().flatMap(page -> page.commands().stream())
                .noneMatch(DrawCommand.StrokePath.class::isInstance));
    }

    @Test void composedImageAndTextLinksKeepFinalGeometryAndOriginalImageSources() throws IOException {
        List<Function<Table, Component>> wrappers = List.of(
                table -> FlowColumn.builder().padding(7).column(column -> column.padding(5).semanticTable(table)).build(),
                table -> RowComponent.builder().padding(4).gap(10).text("LEFT").add(new SemanticTable(table)).build(),
                table -> GridTable.builder().columns(1).defaults(cell -> cell.padding(6)).row(row -> row.cell(new SemanticTable(table))).build(),
                table -> ContinuousColumns.builder(FlowColumn.builder().padding(3).add(new Spacer(240)).semanticTable(table).build()).gap(12).build());
        for (var wrap : wrappers) {
            var source = table("linked", List.of(new InlineText("LINK ", InlineStyle.of(10), "https://example.com/text"), image()));
            var result = DocumentEngine.builder(fonts).build().layout(document(wrap.apply(source)));
            assertEquals(1, result.display().pages().size());
            var page = result.display().pages().getFirst();
            var actualImages = images(page);
            assertEquals(1, actualImages.size());
            var imageLink = result.display().extras().externalLinks().stream()
                    .filter(link -> link.destination().uri().endsWith("/image")).findFirst().orElseThrow();
            assertEquals(actualImages.getFirst(), imageLink.bounds());
            assertEquals(2, result.display().extras().externalLinks().size());
            var component = (ComponentFragment) result.body().pages().getFirst().elements().getFirst();
            var located = ComponentSemantics.locate(component.layout(), component.origin()).getFirst();
            var placed = located.placement();
            assertEquals(source.rows().getFirst().cells().getFirst().images(), placed.source().rows().getFirst().cells().getFirst().images());
            var cell = placed.fragment().rows().getFirst().cells().getFirst();
            assertEquals(source.rows().getFirst().cells().getFirst().images().getFirst().sourceIndex(),
                    cell.layout().lines().getFirst().images().getFirst().sourceIndex());
            double left = located.translation().x() + placed.fragment().origin().x();
            double top = located.translation().y() + placed.fragment().origin().y();
            for (var link : result.display().extras().externalLinks()) {
                assertTrue(link.bounds().x() >= left && link.bounds().y() >= top);
                assertTrue(link.bounds().x() + link.bounds().width() <= left + placed.fragment().width() + .001);
                assertTrue(link.bounds().y() + link.bounds().height() <= top + placed.fragment().height() + .001);
            }
        }
    }

    @Test void localFontPolicyPreservesNamedRolesAndCellUtf16Warnings() {
        var role = FontGroup.strict(List.of(bold));
        var local = FontGroup.placeholder(fonts.fonts(), '?');
        var table = Table.builder("warnings").cellFormat(CELL).row("clean", "clean")
                .row("plain", "A😀")
                .row(new TableCell("role", CELL), TableCell.mixed(List.of(
                        new InlineText("BOLD", new InlineStyle("bold", 10, 0x224488, 0, false, false))), CELL)).build();
        var context = new ComponentContext(300, 200, fonts, 0, 0, 1, Map.of("bold", role));
        var measured = new FontScope(new SemanticTable(table), local).layout(context);
        assertEquals(local, measured.semanticPlacements().getFirst().fonts());
        assertEquals(Map.of("bold", role), measured.semanticPlacements().getFirst().roles());
        assertTrue(measured.semanticPlacements().getFirst().fragment().rows().get(2).cells().get(1).layout().lines().stream()
                .flatMap(line -> line.runs().stream()).anyMatch(run -> run.font().equals(bold.data()) && run.text().equals("BOLD")));
        var result = DocumentEngine.builder(fonts).fontRole("bold", role).build()
                .layout(document(new FontScope(new SemanticTable(table), local)));
        var warning = result.contextualDiagnostics().stream()
                .filter(d -> d.diagnostic().code() == TextDiagnostic.Code.MISSING_GLYPH_REPLACED).findFirst().orElseThrow();
        assertEquals("warnings", warning.blockId()); assertEquals("TABLE", warning.region());
        assertEquals(1, warning.rowIndex()); assertEquals(1, warning.columnIndex());
        assertEquals(1, warning.diagnostic().sourceStart()); assertEquals(3, warning.diagnostic().sourceEnd());
    }

    @Test void strictMissingGlyphAndNarrowFinalFieldKeepCellFailureContext() {
        var missing = Table.builder("missing").cellFormat(CELL).row("plain", "A😀").build();
        var strict = assertThrows(DocumentLayoutException.class,
                () -> DocumentEngine.builder(fonts).build().layout(document(FlowColumn.builder().column(
                        column -> column.semanticTable(missing)).build())));
        assertEquals("missing", strict.blockId()); assertEquals(0, strict.rowIndex()); assertEquals(1, strict.columnIndex()); assertEquals(1, strict.sourceIndex());
        var narrow = table("narrow", List.of(text("A "), new InlineField(InlineField.Kind.PAGE, "", .1, InlineStyle.of(10))));
        var failure = assertThrows(DocumentLayoutException.class, () -> DocumentEngine.builder(fonts).build().layout(document(new SemanticTable(narrow))));
        assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, failure.code());
        assertEquals("narrow", failure.blockId()); assertEquals(0, failure.rowIndex()); assertEquals(0, failure.columnIndex()); assertEquals(2, failure.sourceIndex());
    }

    @Test void nestedTablesShareIdentityAndSourceBudgetsWithTopLevelBlocks() {
        var one = Table.builder("one").cellFormat(CELL).row("AAAA", "BBBB").build();
        var two = Table.builder("two").cellFormat(CELL).row("CCCC", "DDDD").build();
        var both = document(FlowColumn.builder().semanticTable(one).semanticTable(two).build());
        var cells = new DocumentLayoutLimits(10, 1000, 100, 10, 10, 10, 3);
        var points = new DocumentLayoutLimits(10, 15, 100, 10, 10, 10, 100);
        var sources = new DocumentLayoutLimits(2, 1000, 100, 10, 10, 10, 100);
        for (var limits : List.of(cells, points, sources)) {
            var failure = assertThrows(DocumentLayoutException.class, () -> new ComponentDocumentRenderer(limits, ComponentLimits.DEFAULT)
                    .layout(both, PageTemplate.EMPTY, Navigation.EMPTY, fonts));
            assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        }
        var placement = assertThrows(DocumentLayoutException.class, () -> new ComponentDocumentRenderer(DocumentLayoutLimits.DEFAULT,
                new ComponentLimits(1, 10000, 100)).layout(both, PageTemplate.EMPTY, Navigation.EMPTY, fonts));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, placement.code());
        var duplicate = document(FlowColumn.builder().semanticTable(one).semanticTable(one).build());
        assertThrows(IllegalArgumentException.class, () -> DocumentEngine.builder(fonts).build().layout(duplicate));
        var duplicateTop = Documents.builder().pageSettings(PAGE).paragraph("one", "TOP").component(new SemanticTable(one)).build();
        assertThrows(IllegalArgumentException.class, () -> DocumentEngine.builder(fonts).build().layout(duplicateTop));
    }

    @Test void repeatingGridHeaderResolvesEachPreparedPresentationOnItsActualPage() {
        for (boolean split : List.of(false, true)) {
            var nested = new SemanticTable(table("header-source", List.of(text("HEADER P="), field(InlineField.Kind.PAGE, ""))));
            var builder = GridTable.builder().columns(1).headerRows(1).allowRowSplit(split)
                    .defaults(cell -> cell.padding(3).textStyle(ParagraphStyle.of(10)))
                    .row(row -> row.cell(nested));
            for (int i = 0; i < 24; i++) { int row = i; builder.row(value -> value.cell("ROW-" + row)); }
            var grid = builder.build();
            var fragments = grid.layoutParts(new ComponentContext(PAGE.contentWidth(), PAGE.contentHeight(), fonts, 0, 0, 1));
            assertTrue(fragments.size() > 1);
            assertTrue(fragments.stream().allMatch(fragment -> fragment.semanticPlacements().size() == 1));
            var result = DocumentEngine.builder(fonts).build().layout(document(grid));
            int index = 0;
            for (var page : result.body().pages()) {
                for (var element : page.elements()) {
                    if (!(element instanceof ComponentFragment component)) { continue; }
                    for (var placement : component.layout().semanticPlacements()) {
                        assertEquals(index++, placement.fragmentIndex());
                        assertEquals(result.body().pages().size(), placement.fragmentCount());
                        assertEquals("HEADER P=" + (page.pageIndex() + 1), logical(placement.fragment().rows().getFirst().cells().getFirst().layout()));
                    }
                }
            }
            assertEquals(result.body().pages().size(), index);
        }
    }

    @Test void shortSemanticTableMovesAndFixedViewportStillRejectsLongContent() {
        var shortTable = table("short", List.of(text("ONCE")));
        var source = Documents.builder().pageSettings(PAGE).component(new Spacer(250)).column(column -> column.semanticTable(shortTable)).build();
        var result = DocumentEngine.builder(fonts).build().layout(source);
        assertEquals(2, result.body().pages().size());
        assertTrue(result.body().pages().getFirst().elements().stream().filter(ComponentFragment.class::isInstance)
                .map(ComponentFragment.class::cast).allMatch(fragment -> fragment.layout().semanticPlacements().isEmpty()));
        assertEquals(1, result.body().pages().getLast().elements().stream().filter(ComponentFragment.class::isInstance)
                .map(ComponentFragment.class::cast).flatMap(fragment -> fragment.layout().semanticPlacements().stream()).count());
        var builder = Table.builder("long").cellFormat(CELL);
        for (int i = 0; i < 30; i++) { builder.row("ROW-" + i); }
        var longTable = new SemanticTable(builder.build());
        assertTrue(DocumentEngine.builder(fonts).build().layout(document(longTable)).body().pages().size() > 1);
        assertThrows(IllegalArgumentException.class, () -> longTable.layout(
                new ComponentContext(PAGE.contentWidth(), PAGE.contentHeight(), fonts, 0, 0, 1)));
    }

    @Test void rawDrawingAndFixedPageDecorationsRejectDeferredSemantics() {
        var component = new SemanticTable(table("decorated", List.of(text("SEMANTIC"))));
        var measured = component.layout(new ComponentContext(200, 100, fonts, 0, 0, 1));
        assertFalse(measured.semanticPlacements().isEmpty()); assertTrue(measured.staticCommands().isEmpty());
        assertThrows(IllegalStateException.class, measured::commands);
        var template = new PageTemplate(PageBand.EMPTY, PageBand.EMPTY, List.of(),
                List.of(new Placement(component, new Position.Absolute(new Rect(20, 20, 200, 100)))));
        var blank = new DisplayDocument(DocumentMetadata.EMPTY, List.of(new DisplayPage(PAGE.size(), List.of())));
        var failure = assertThrows(RuntimeException.class, () -> new PageComposer().compose(blank, PAGE, template, fonts.fonts()));
        assertTrue(failure.getMessage().contains("semantic"));
    }

    private static FontFace face(String name) throws IOException {
        try (var input = SemanticTableTest.class.getResourceAsStream("/fonts/" + name)) {
            return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
    }
    private static Document document(Component component) { return Documents.builder().pageSettings(PAGE).component("host", component).build(); }
    private static Table table(String id, List<? extends InlineContent> contents) { return Table.builder(id).row(TableCell.mixed(contents, CELL)).build(); }
    private static InlineText text(String text) { return new InlineText(text, InlineStyle.of(10)); }
    private static InlineField field(InlineField.Kind kind, String target) { return new InlineField(kind, target, 18, InlineStyle.of(10)); }
    private static InlineImage image() throws IOException {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new InlineImage(ImageResource.load(bytes.toByteArray()), 14, 14, 0, "Synthetic image", "https://example.com/image");
    }
    private static String logical(ParagraphLayout layout) {
        return layout.lines().stream().map(line -> line.outlines().isEmpty()
                ? line.runs().stream().map(run -> run.text()).collect(Collectors.joining())
                : line.outlines().stream().map(TextOutline::logicalText).collect(Collectors.joining())).collect(Collectors.joining());
    }
    private static List<Rect> images(DisplayPage page) {
        var result = new ArrayList<Rect>();
        var stack = new ArrayDeque<Point>();
        var offset = new Point(0, 0);
        for (var command : page.commands()) {
            switch (command) {
                case DrawCommand.Save ignored -> stack.push(offset);
                case DrawCommand.Restore ignored -> offset = stack.pop();
                case DrawCommand.Transform transform -> offset = new Point(offset.x() + transform.matrix().e(), offset.y() + transform.matrix().f());
                case DrawCommand.Image image -> result.add(new Rect(offset.x() + image.bounds().x(), offset.y() + image.bounds().y(), image.bounds().width(), image.bounds().height()));
                default -> { }
            }
        }
        return result;
    }
}
