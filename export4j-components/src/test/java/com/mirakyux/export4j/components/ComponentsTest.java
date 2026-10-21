// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.layout.core.PageBand;
import com.mirakyux.export4j.layout.core.PageComposer;
import com.mirakyux.export4j.layout.core.PageComposition;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.layout.core.Placement;
import com.mirakyux.export4j.layout.core.Position;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.ParagraphStyle;

class ComponentsTest {
    private static List<FontFace> fonts;
    private static final PageSettings SETTINGS = new PageSettings(new PageSize(220, 180), PageMargins.uniform(10));

    @BeforeAll
    static void fonts() throws IOException {
        fonts = List.of(load("LiberationSans-Regular.ttf"), load("LXGWWenKaiTC-Regular.ttf"));
    }

    @Test
    void resolvesAbsoluteAnchoredAndFractionalViewports() {
        Rect parent = new Rect(10, 20, 200, 100);
        assertEquals(new Rect(7, 8, 30, 40), new Position.Absolute(new Rect(7, 8, 30, 40)).resolve(parent));
        assertEquals(new Rect(182, 83, 30, 40), new Position.Relative(Position.Anchor.BOTTOM_RIGHT, 30, 40, 2, 3).resolve(parent));
        assertEquals(new Rect(60, 40, 100, 30), new Position.Fractional(0.25, 0.2, 0.5, 0.3).resolve(parent));
        assertThrows(IllegalArgumentException.class, () -> new Position.Fractional(0.8, 0, 0.3, 1));
    }

    @Test
    void reservesBandsBeforePaginationAndUsesFinalPageNumbers() {
        var footer = new PageNumberComponent("{page}/{pages}", ParagraphStyle.of(10), RgbColor.BLACK);
        var template = new PageTemplate(new PageBand(20, 5, List.of()), new PageBand(20, 5,
                List.of(new Placement(footer, new Position.Fractional(0, 0, 1, 1)))), List.of(), List.of());
        var source = document(List.of(new Paragraph("p", "line\n".repeat(16) + "last", ParagraphFormat.of(10))));
        var result = new ComponentDocumentRenderer().layout(source, template, Navigation.EMPTY, fonts);
        assertTrue(result.body().pages().size() > 1);
        for (var page : result.body().pages()) {
            for (var fragment : page.elements()) {
                assertTrue(fragment.origin().y() >= 35);
            }
        }
        int pages = result.display().pages().size();
        for (int page = 0; page < pages; page++) {
            assertEquals((page + 1) + "/" + pages, text(result.decorations().get(page).layout()));
        }
    }

    @Test
    void composesLayersInOrderAndRetainsFallbackDiagnostics() {
        Component background = context -> box(20, 20);
        var foreground = new TextComponent("中文", ParagraphStyle.of(10), RgbColor.BLACK);
        var template = new PageTemplate(PageBand.EMPTY, PageBand.EMPTY,
                List.of(new Placement(background, new Position.Absolute(new Rect(0, 0, 20, 20)))),
                List.of(new Placement(foreground, new Position.Absolute(new Rect(10, 10, 50, 30)))));
        var body = new DisplayDocument(DocumentMetadata.EMPTY, List.of(new DisplayPage(SETTINGS.size(),
                List.of(new DrawCommand.FillRect(new Rect(50, 50, 5, 5), RgbColor.BLACK)))));
        var result = new PageComposer().composeDetailed(body, SETTINGS, template, fonts);
        assertEquals(List.of(PageComposition.Region.BACKGROUND, PageComposition.Region.FOREGROUND),
                result.placements().stream().map(PageComposition.Placed::region).toList());
        assertFalse(result.placements().getLast().layout().diagnostics().isEmpty());
        assertInstanceOf(DrawCommand.Save.class, result.display().pages().getFirst().commands().getFirst());
        assertEquals(body.pages().getFirst().commands().getFirst(), result.display().pages().getFirst().commands().get(4));
    }

    @Test
    void rejectsOutOfRegionAndExhaustedPlacementOrCommandBudgets() {
        var placement = new Placement(context -> box(20, 20), new Position.Absolute(new Rect(-1, 0, 20, 20)));
        var template = new PageTemplate(PageBand.EMPTY, PageBand.EMPTY, List.of(placement), List.of());
        var blank = new DisplayDocument(DocumentMetadata.EMPTY, List.of(new DisplayPage(SETTINGS.size(), List.of())));
        assertThrows(IllegalArgumentException.class, () -> new PageComposer().compose(blank, SETTINGS, template, fonts));
        var valid = new Placement(context -> box(20, 20), new Position.Absolute(new Rect(0, 0, 20, 20)));
        var tooMany = new PageTemplate(PageBand.EMPTY, PageBand.EMPTY, List.of(valid, valid), List.of());
        assertThrows(IllegalArgumentException.class, () -> new PageComposer(new ComponentLimits(1, 100, 1))
                .compose(blank, SETTINGS, tooMany, fonts));
        assertThrows(IllegalArgumentException.class, () -> new PageComposer(new ComponentLimits(10, 3, 1))
                .compose(blank, SETTINGS, new PageTemplate(PageBand.EMPTY, PageBand.EMPTY, List.of(valid), List.of()), fonts));
        assertThrows(IllegalArgumentException.class, () -> new PageTemplate(new PageBand(160, 0, List.of()),
                PageBand.EMPTY, List.of(), List.of()).bodySettings(SETTINGS));
    }

    @Test
    void movesIndivisibleComponentsWithoutRemeasuringOrLosingSource() {
        int[] calls = {0};
        Component component = context -> { calls[0]++; return box(100, 100); };
        var source = document(List.of(new ComponentBlock("first", component, 0, 0), new ComponentBlock("second", component, 0, 0)));
        var result = new DocumentLayouter().layout(source, fonts);
        assertEquals(2, calls[0]);
        assertEquals(2, result.pages().size());
        var second = assertInstanceOf(ComponentFragment.class, result.pages().get(1).elements().getFirst());
        assertEquals("second", second.blockId());
        assertEquals(1, second.blockIndex());
        assertEquals(new Point(10, 10), second.origin());
    }

    @Test
    void componentFailuresRetainBlockAndCauseAndRejectUnknownBlocks() {
        var tooTall = document(List.of(new ComponentBlock("tall", context -> box(10, 161), 0, 0)));
        assertEquals(DocumentLayoutException.Code.CONTENT_TOO_TALL,
                assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter().layout(tooTall, fonts)).code());
        var tooWide = document(List.of(new ComponentBlock("wide", context -> box(201, 10), 0, 0)));
        assertEquals(DocumentLayoutException.Code.INVALID_GEOMETRY,
                assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter().layout(tooWide, fonts)).code());
        var bad = document(List.of(new ComponentBlock("bad", context -> null, 0, 0)));
        var failure = assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter().layout(bad, fonts));
        assertEquals("bad", failure.paragraphId());
        assertNotNull(failure.getCause());
        Block unsupported = () -> "unknown";
        assertEquals(DocumentLayoutException.Code.UNSUPPORTED_COMPONENT,
                assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter().layout(document(List.of(unsupported)), fonts)).code());
    }

    @Test
    void chargesBodyComponentsAgainstAggregateTextAndCommandBudgets() {
        var source = document(List.of(new Paragraph("p", "1234", ParagraphFormat.of(10)),
                new ComponentBlock("c", new TextComponent("5678", ParagraphStyle.of(10), RgbColor.BLACK), 0, 0)));
        var limits = new DocumentLayoutLimits(10, 7, 100, 10);
        var failure = assertThrows(DocumentLayoutException.class,
                () -> new DocumentLayouter(new ParagraphLayouter(), limits).layout(source, fonts));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        var shapes = document(List.of(new ComponentBlock("a", context -> box(10, 10), 0, 0),
                new ComponentBlock("b", context -> box(10, 10), 0, 0)));
        assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter(new ParagraphLayouter(),
                DocumentLayoutLimits.DEFAULT, new ComponentLimits(10, 1, 10)).layout(shapes, fonts));
    }

    @Test
    void imagesContainCoverAndStretchWithExplicitClipping() {
        var image = new RgbImage(2, 1, new byte[6]);
        var context = new ComponentContext(100, 100, fonts, 0, 1);
        List<Rect> expected = List.of(new Rect(0, 25, 100, 50), new Rect(-50, 0, 200, 100), new Rect(0, 0, 100, 100));
        int index = 0;
        for (var fit : ImageComponent.Fit.values()) {
            var layout = new ImageComponent(image, fit).layout(context);
            assertInstanceOf(DrawCommand.Clip.class, layout.commands().get(1));
            assertEquals(expected.get(index++), assertInstanceOf(DrawCommand.Image.class, layout.commands().get(2)).bounds());
        }
    }

    @Test
    void ellipseLineAndWatermarkEmitRealPathsAndScopedTransforms() {
        var context = new ComponentContext(100, 100, fonts, 0, 1);
        var ellipse = new ShapeComponent(ShapeComponent.Kind.ELLIPSE, RgbColor.WHITE, RgbColor.BLACK, 2).layout(context);
        var fill = assertInstanceOf(DrawCommand.FillPath.class, ellipse.commands().getFirst());
        assertEquals(6, fill.path().segments().size());
        assertInstanceOf(DrawCommand.StrokePath.class, ellipse.commands().getLast());
        assertInstanceOf(DrawCommand.StrokePath.class, LineComponent.horizontal(1, RgbColor.BLACK).layout(context).commands().getFirst());
        var watermark = new WatermarkComponent("DRAFT", 12, -30, new RgbColor(0.9, 0.9, 0.9)).layout(context);
        assertInstanceOf(DrawCommand.Clip.class, watermark.commands().get(1));
        assertInstanceOf(DrawCommand.Transform.class, watermark.commands().get(2));
        assertEquals("DRAFT", text(watermark));
        assertInstanceOf(DrawCommand.Restore.class, watermark.commands().getLast());
        assertThrows(IllegalArgumentException.class, () -> new PageNumberComponent("{page}", ParagraphStyle.of(10), RgbColor.BLACK)
                .layout(new ComponentContext(100, 100, fonts, 0, 0)));
    }

    @Test
    void contentsAcrossMultiplePagesResolveActualAnchorsAndLabels() {
        List<Block> blocks = new ArrayList<>();
        List<Navigation.Bookmark> bookmarks = new ArrayList<>();
        for (int index = 0; index < 18; index++) {
            blocks.add(new Paragraph("p" + index, "Section " + index + "\nbody", ParagraphFormat.of(10)));
            bookmarks.add(new Navigation.Bookmark("Section " + index, "p" + index, index % 3 == 1 ? 2 : 1));
        }
        var fields = List.<Navigation.Field>of(new Navigation.TextField(new Navigation.BlockTarget("p17", new Rect(0, 0, 70, 10)),
                "name", "Ada", false));
        var nav = new Navigation(bookmarks, new TableOfContents(true, "Contents", ParagraphStyle.of(10)),
                List.of(new Navigation.Comment(new Navigation.BlockTarget("p0", new Rect(180, 0, 16, 16)), "Author", "Check")), fields);
        var result = new ComponentDocumentRenderer().layout(document(blocks), PageTemplate.EMPTY, nav, fonts);
        assertTrue(result.bodyPageOffset() > 1);
        assertEquals(result.body().pages().size() + result.bodyPageOffset(), result.display().pages().size());
        assertEquals(18, result.display().extras().links().size());
        for (int index = 0; index < bookmarks.size(); index++) {
            String id = bookmarks.get(index).blockId();
            var bodyPage = result.body().pages().stream().filter(p -> p.elements().stream().anyMatch(f -> f.blockId().equals(id))).findFirst().orElseThrow();
            int destination = bodyPage.pageIndex() + result.bodyPageOffset();
            assertEquals(destination, result.display().extras().bookmarks().get(index).pageIndex());
            assertEquals(destination, result.display().extras().links().get(index).targetPageIndex());
            int contentsPage = result.display().extras().links().get(index).pageIndex();
            assertTrue(text(result.contentsPages().get(contentsPage)).contains(Integer.toString(destination + 1)));
        }
        assertEquals(result.bodyPageOffset(), result.display().extras().comments().getFirst().pageIndex());
        assertEquals(result.display().extras().bookmarks().getLast().pageIndex(), result.display().extras().fields().getFirst().pageIndex());
    }

    @Test
    void rejectsUnknownTargetsInvalidHierarchyAndContentsPageExhaustion() {
        var source = document(List.of(new Paragraph("p", "text", ParagraphFormat.of(10))));
        var nav = new Navigation(List.of(new Navigation.Bookmark("Title", "missing", 1)), TableOfContents.NONE, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> new ComponentDocumentRenderer().layout(source, PageTemplate.EMPTY, nav, fonts));
        assertThrows(IllegalArgumentException.class, () -> new Navigation(List.of(new Navigation.Bookmark("Title", "p", 2)),
                TableOfContents.NONE, List.of(), List.of()));
        var contents = new Navigation(List.of(new Navigation.Bookmark("Title", "p", 1)),
                new TableOfContents(true, "Contents", ParagraphStyle.of(10)), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> new ComponentDocumentRenderer(new DocumentLayoutLimits(10, 1000, 1000, 1),
                ComponentLimits.DEFAULT).layout(source, PageTemplate.EMPTY, contents, fonts));
    }

    @Test
    void contentsSourceTextHasAnAggregateBudgetBeforeEntryMeasurement() {
        var source = document(List.of(new Paragraph("p", "text", ParagraphFormat.of(10))));
        var nav = new Navigation(List.of(new Navigation.Bookmark("A".repeat(30), "p", 1)),
                new TableOfContents(true, "Contents", ParagraphStyle.of(10)), List.of(), List.of());
        var failure = assertThrows(IllegalArgumentException.class, () -> new ComponentDocumentRenderer(
                new DocumentLayoutLimits(10, 20, 100, 10), ComponentLimits.DEFAULT).layout(source, PageTemplate.EMPTY, nav, fonts));
        assertEquals("contents source text budget exceeded", failure.getMessage());
    }

    @Test
    void contentsOutputLinesAndCommandsHaveExplicitBudgets() {
        var source = document(List.of(new Paragraph("p", "text", ParagraphFormat.of(10))));
        var nav = new Navigation(List.of(new Navigation.Bookmark("Title", "p", 1)),
                new TableOfContents(true, "Contents", ParagraphStyle.of(10)), List.of(), List.of());
        var lines = assertThrows(IllegalArgumentException.class, () -> new ComponentDocumentRenderer(
                new DocumentLayoutLimits(10, 100, 2, 10), ComponentLimits.DEFAULT).layout(source, PageTemplate.EMPTY, nav, fonts));
        assertEquals("contents output line budget exceeded", lines.getMessage());
        var commands = assertThrows(IllegalArgumentException.class, () -> new ComponentDocumentRenderer(
                DocumentLayoutLimits.DEFAULT, new ComponentLimits(10, 1, 10)).layout(source, PageTemplate.EMPTY, nav, fonts));
        assertEquals("contents command budget exceeded", commands.getMessage());
    }

    @Test
    void positionedTableMustFitOneViewportWhileFlowTableMayPaginate() {
        List<TableRow> rows = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            rows.add(new TableRow(List.of(new TableCell("Row " + index, CellFormat.of(12)))));
        }
        var table = new Table("t", List.of(new ColumnWidth.Fraction(1)), rows, 1, TableFormat.DEFAULT);
        var component = new TableComponent(table);
        assertThrows(IllegalArgumentException.class, () -> component.layout(new ComponentContext(100, 80, fonts, 0, 1)));
        assertTrue(component.layout(new ComponentContext(100, 500, fonts, 0, 1)).textLines() >= 8);
        assertTrue(new DocumentLayouter().layout(document(List.of(table)), fonts).pages().size() > 1);
    }

    private static Document document(List<? extends Block> blocks) {
        return new Document(DocumentMetadata.EMPTY, SETTINGS, blocks);
    }

    private static ComponentLayout box(double width, double height) {
        return new ComponentLayout(width, height, 0, List.of(new DrawCommand.FillRect(new Rect(0, 0, width, height), RgbColor.BLACK)), List.of());
    }

    private static String text(ComponentLayout layout) {
        StringBuilder text = new StringBuilder();
        for (var command : layout.commands()) {
            if (command instanceof DrawCommand.GlyphText glyph) {
                text.append(glyph.run().text());
            }
        }
        return text.toString();
    }

    private static FontFace load(String name) throws IOException {
        try (InputStream stream = ComponentsTest.class.getResourceAsStream("/fonts/" + name)) {
            if (stream == null) {
                throw new IOException("Missing test font");
            }
            return TrueTypeFontLoader.load(stream, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
    }
}
