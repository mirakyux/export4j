// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.FontMetrics;
import com.mirakyux.export4j.font.GlyphMetrics;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.layout.CellFragment;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.DocumentLayout;
import com.mirakyux.export4j.layout.LayoutFragment;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnFlow;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.GridLayout;
import com.mirakyux.export4j.model.GridItem;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.SimpleTextShaper;
import com.mirakyux.export4j.text.TextLayoutLimits;
import com.mirakyux.export4j.text.TextLine;
import com.mirakyux.export4j.text.TextShaper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GridLayoutTest {
    private static final ParagraphFormat FORMAT = new ParagraphFormat(10, 10, 0, 0, ParagraphAlignment.LEFT, 0, 0, false);
    private static FontFace font;
    private static ImageResource image;

    @BeforeAll static void resources() throws IOException {
        FontFace real;
        try (var input = GridLayoutTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            real = TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
        font = new FontFace() {
            @Override public FontData data() { return real.data(); }
            @Override public FontMetrics metrics() { return new FontMetrics(0.7, 0.2, 0); }
            @Override public Optional<GlyphMetrics> glyph(int cp) { return Optional.of(new GlyphMetrics(1, 1)); }
        };
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", bytes);
        image = ImageResource.load(bytes.toByteArray());
    }

    @Test void explicitTwelveTracksSupportHalvesThirdsAsymmetryAndOffsets() {
        var grid = new GridLayout("g", 12, 12, 7, List.of(
                item("a", 1, 1, 6, p("a-p", "A\nB")), item("b", 1, 7, 6, p("b-p", "B")),
                item("c", 2, 1, 8, p("c-p", "C")), item("d", 2, 9, 4, p("d-p", "D")),
                item("e", 3, 1, 4, p("e-p", "E")), item("f", 3, 5, 4, p("f-p", "F")), item("h", 3, 9, 4, p("h-p", "H")),
                item("offset", 7, 4, 5, p("offset-p", "O"))));
        var result = layout(252, 100, grid, p("tail", "T"));
        assertEquals(1, result.pages().size());
        assertMarker(result, "a", 10, 10, 120, 20);
        assertMarker(result, "b", 142, 10, 120, 20);
        assertMarker(result, "c", 10, 37, 164, 10);
        assertMarker(result, "d", 186, 37, 76, 10);
        assertMarker(result, "e", 10, 54, 76, 10);
        assertMarker(result, "f", 98, 54, 76, 10);
        assertMarker(result, "h", 186, 54, 76, 10);
        assertMarker(result, "offset", 76, 71, 98, 10);
        assertEquals(81, paragraph(result, "tail").origin().y());
        assertEquals(4, elements(result).stream().filter(f -> f.blockId().equals("g")).count());
        assertContained(result); DocumentPainter.paint(result);
    }

    @Test void columnsGridAndColumnsUseTheSamePageAndKeepChildSources() {
        var first = new ColumnFlow("first", List.of(p("before", "A\nB\nC\nD")), 2, 12, true);
        var second = new ColumnFlow("second", List.of(p("after", "E\nF\nG\nH\nI\nJ")), 3, 12, true);
        var table = table("table", "T");
        var picture = Paragraph.mixed("image", List.of(new InlineImage(image, 90, 5, 0, "figure")), FORMAT.toBuilder().lineHeight(20).build());
        var grid = new GridLayout("grid", 12, 0, 8, List.of(item("left", 1, 1, 8,
                p("original", "A😀B\nC"), table), item("right", 1, 9, 4, picture)));
        var result = layout(300, 100, first, grid, second, p("end", "Z"));
        assertEquals(1, result.pages().size());
        assertEquals(30, paragraph(result, "original").origin().y());
        assertEquals(60, paragraph(result, "after").origin().y());
        assertEquals(80, paragraph(result, "end").origin().y());
        var original = paragraph(result, "original");
        assertEquals(1, original.blockIndex());
        assertEquals(List.of(0, 5), original.layout().lines().stream().map(TextLine::sourceStart).toList());
        assertEquals("A😀BC", original.layout().lines().stream().flatMap(l -> l.runs().stream()).map(r -> r.text()).collect(Collectors.joining()));
        var fragment = (TableFragment) elements(result).stream().filter(f -> f.blockId().equals("table")).findFirst().orElseThrow();
        assertEquals(200, fragment.width()); assertEquals(1, fragment.blockIndex());
        assertEquals(210, paragraph(result, "image").origin().x());
        assertEquals(1, paragraph(result, "image").layout().lines().getFirst().images().size());
        assertContained(result); DocumentPainter.paint(result);
    }

    @Test void wholeRowsAdvanceTogetherAndRowGapsDisappearAtPageBoundaries() {
        var grid = new GridLayout("g", 12, 0, 15, List.of(
                item("a", 1, 1, 6, p("a-p", "A\nB")), item("b", 1, 7, 6, p("b-p", "C")),
                item("c", 2, 1, 12, p("c-p", "D\nE"))));
        var result = layout(240, 40, p("before", "X\nY\nZ"), grid, p("after", "Q"));
        assertEquals(3, result.pages().size());
        assertTrue(result.pages().getFirst().elements().stream().noneMatch(f -> f.blockId().equals("g")));
        assertEquals(List.of("a-p", "b-p"), result.pages().get(1).fragments().stream().map(ParagraphFragment::blockId).toList());
        assertEquals(10, paragraph(result, "a-p").origin().y());
        assertEquals(10, paragraph(result, "b-p").origin().y());
        assertEquals(10, paragraph(result, "c-p").origin().y());
        assertEquals(30, paragraph(result, "after").origin().y());
        assertContained(result);
    }

    @Test void shortFreeBandsSkipWithoutConsumingAnItemOrItsImage() {
        var grid = new GridLayout("g", 12, 0, 0, List.of(item("i", 1, 1, 12, p("p", "A\nB\nC"))));
        var source = document(120, 80, grid);
        var result = new DocumentLayouter().layout(source, FontGroup.strict(List.of(font)), Map.of(),
                page -> page == 0 ? List.of(new Rect(10, 25, 120, 15)) : List.of());
        assertEquals(1, result.pages().size());
        assertEquals(40, paragraph(result, "p").origin().y());
        assertEquals(3, paragraph(result, "p").layout().lines().size());
        assertContained(result);
    }

    @Test void atomicTableRowsAndNestedCellBlocksRetainTheirRealIdentity() {
        var nested = table("nested", "N");
        var cell = TableCell.builder().blocks(List.of(p("inner", "A😀B"), nested)).build();
        var table = new Table("outer", List.of(new ColumnWidth.Fraction(1)), List.of(new TableRow(List.of(cell))),
                0, new TableFormat(PageMargins.uniform(0), 0, 0));
        var result = layout(240, 50, p("before", "0\n1\n2\n3"),
                new GridLayout("g", 12, 0, 0, List.of(item("i", 1, 1, 12, table))));
        assertEquals(2, result.pages().size());
        var fragment = (TableFragment) result.pages().getLast().elements().stream().filter(TableFragment.class::isInstance).findFirst().orElseThrow();
        assertEquals("outer", fragment.blockId()); assertEquals(1, fragment.blockIndex());
        var blocks = ((CellFragment.Blocks) fragment.rows().getFirst().cells().getFirst().content()).fragments();
        assertEquals(List.of("inner", "nested"), blocks.stream().map(LayoutFragment::blockId).toList());
        assertEquals(4, ((ParagraphFragment) blocks.getFirst()).layout().lines().getFirst().sourceEnd());
        assertContained(result); DocumentPainter.paint(result);
    }

    @Test void overTallRowsAndConsumedTrackWidthFailExplicitly() {
        var tall = new GridLayout("g", 12, 0, 0, List.of(item("i", 1, 1, 12, p("p", "A\nB\nC"))));
        assertEquals(DocumentLayoutException.Code.CONTENT_TOO_TALL,
                assertThrows(DocumentLayoutException.class, () -> layout(240, 20, tall)).code());
        var wideGap = new GridLayout("g", 12, 30, 0, List.of(item("i", 1, 1, 12, p("p", "A"))));
        assertEquals(DocumentLayoutException.Code.INVALID_GEOMETRY,
                assertThrows(DocumentLayoutException.class, () -> layout(240, 100, wideGap)).code());
        var rotated = new Paragraph("p", "A", FORMAT.toBuilder().rotation(90).build());
        assertEquals(DocumentLayoutException.Code.INVALID_GEOMETRY, assertThrows(DocumentLayoutException.class,
                () -> layout(240, 100, new GridLayout("g", 12, 0, 0, List.of(item("i", 1, 1, 12, rotated))))).code());
    }

    @Test void indentedAtomicPicturesAndFixedTablesCannotOverflowTheirItem() {
        var exact = Paragraph.mixed("picture", List.of(new InlineImage(image, 50, 5, 0, "image")),
                FORMAT.toBuilder().lineHeight(20).firstLineIndent(10).build());
        var good = new GridLayout("g", 12, 0, 0, List.of(item("i", 1, 1, 6, exact)));
        assertEquals(10, paragraph(layout(120, 100, good), "picture").layout().lines().getFirst().offsetX());
        var overflow = Paragraph.mixed("picture", List.of(new InlineImage(image, 51, 5, 0, "image")),
                FORMAT.toBuilder().lineHeight(20).firstLineIndent(10).build());
        assertThrows(DocumentLayoutException.class, () -> layout(120, 100,
                new GridLayout("g", 12, 0, 0, List.of(item("i", 1, 1, 6, overflow)))));
        var wide = Table.builder("table").columns(new ColumnWidth.Fixed(61)).row("x").build();
        assertThrows(DocumentLayoutException.class, () -> layout(120, 100,
                new GridLayout("g", 12, 0, 0, List.of(item("i", 1, 1, 6, wide)))));
    }

    @Test void sharedNodeSourceOutputAndTrackBudgetsIncludeEveryItemOnce() {
        var grid = new GridLayout("g", 12, 0, 0, List.of(item("i", 1, 1, 12, p("p", "A\nB\nC"))));
        var source = document(120, 100, grid);
        var exact = new DocumentLayoutLimits(3, 5, 3, 1, 10, 12, 12);
        assertEquals(3, paragraph(new DocumentLayouter(new ParagraphLayouter(), exact).layout(source, List.of(font)), "p").layout().lines().size());
        for (var limits : List.of(new DocumentLayoutLimits(2, 5, 3, 1, 10, 12, 12),
                new DocumentLayoutLimits(3, 4, 3, 1, 10, 12, 12), new DocumentLayoutLimits(3, 5, 2, 1, 10, 12, 12),
                new DocumentLayoutLimits(3, 5, 3, 1, 10, 12, 11))) {
            assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, assertThrows(DocumentLayoutException.class,
                    () -> new DocumentLayouter(new ParagraphLayouter(), limits).layout(source, List.of(font))).code());
        }
    }

    @Test void aHeadingKeepChainMeasuresTheFirstGridRowOnce() {
        var heading = new Paragraph("heading", "H", FORMAT.withPagination(new PaginationPolicy(true, false, 1, 1)));
        var grid = new GridLayout("g", 12, 0, 0, List.of(item("i", 1, 1, 12, p("p", "A\nB"))));
        var source = document(120, 40, p("before", "X\nY"), heading, grid);
        var limits = new DocumentLayoutLimits(5, 9, 5, 2);
        var result = new DocumentLayouter(new ParagraphLayouter(), limits).layout(source, List.of(font));
        assertEquals(2, result.pages().size());
        assertEquals(10, paragraph(result, "heading").origin().y());
        assertEquals(20, paragraph(result, "p").origin().y());
    }

    @Test void siblingColumnsAndGridShareTheMeasurementBudgetBeforeHeavyShaping() {
        var columns = new ColumnFlow("columns", List.of(p("small", "ABCDE")), 2, 0, false);
        var grid = new GridLayout("grid", 12, 0, 0,
                List.of(item("item", 1, 1, 12, p("heavy", "X".repeat(3_999_998)))));
        var simple = new SimpleTextShaper();
        TextShaper guarded = (text, fonts, size, index) -> {
            assertTrue(text.length() < 1000, "measurement budget must reject before heavy shaping");
            return simple.shape(text, fonts, size, index);
        };
        var limits = new DocumentLayoutLimits(10, 5_000_000, 10, 1);
        var failure = assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter(
                new ParagraphLayouter(guarded, TextLayoutLimits.DEFAULT), limits).layout(document(240, 100, columns, grid), List.of(font)));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals("heavy", failure.blockId());
        assertTrue(failure.getMessage().contains("measurement"));
    }

    @Test void cancellationRestoresTheReusableLayouterAndExecutionScope() {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var simple = new SimpleTextShaper();
        TextShaper controlled = (text, fonts, size, index) -> { var value = simple.shape(text, fonts, size, index); cancelled.set(true); return value; };
        var layouter = new DocumentLayouter(new ParagraphLayouter(controlled, TextLayoutLimits.DEFAULT), DocumentLayoutLimits.DEFAULT);
        var source = document(120, 40, new GridLayout("g", 12, 0, 0, List.of(item("i", 1, 1, 12, p("p", "A\nB")))));
        var options = ExecutionOptions.builder().cancelled(cancelled::get).build();
        assertEquals(ExecutionException.Code.CANCELLED, assertThrows(ExecutionException.class,
                () -> ExecutionContext.execute(options, () -> layouter.layout(source, List.of(font)))).code());
        ExecutionContext.checkpoint();
        assertEquals(1, layouter.layout(source, List.of(font)).pages().size());
    }

    private static Paragraph p(String id, String text) { return new Paragraph(id, text, FORMAT); }
    private static GridItem item(String id, int row, int start, int span, Block... children) { return new GridItem(id, row, start, span, List.of(children)); }
    private static Table table(String id, String text) { return Table.builder(id).cellFormat(new CellFormat(10, 10, ParagraphAlignment.LEFT)).format(new TableFormat(PageMargins.uniform(0), 0, 0)).row(text).build(); }
    private static Document document(double width, double height, Block... blocks) { return new Document(DocumentMetadata.EMPTY, new PageSettings(new PageSize(width + 20, height + 20), PageMargins.uniform(10)), List.of(blocks)); }
    private static DocumentLayout layout(double width, double height, Block... blocks) { return new DocumentLayouter().layout(document(width, height, blocks), List.of(font)); }
    private static List<LayoutFragment> elements(DocumentLayout result) { return result.pages().stream().flatMap(p -> p.elements().stream()).toList(); }
    private static ParagraphFragment paragraph(DocumentLayout result, String id) { return elements(result).stream().filter(f -> f.blockId().equals(id)).map(ParagraphFragment.class::cast).findFirst().orElseThrow(); }
    private static void assertMarker(DocumentLayout result, String id, double x, double y, double width, double height) {
        var c = (ComponentFragment) elements(result).stream().filter(f -> f.blockId().equals(id)).findFirst().orElseThrow();
        assertEquals(x, c.origin().x(), 1e-9); assertEquals(y, c.origin().y(), 1e-9);
        assertEquals(width, c.layout().width(), 1e-9); assertEquals(height, c.layout().height(), 1e-9);
        assertTrue(c.layout().staticCommands().isEmpty()); assertTrue(c.layout().semanticPlacements().isEmpty());
    }
    private static void assertContained(DocumentLayout result) {
        for (var page : result.pages()) {
            var b = page.contentBounds();
            for (var f : page.elements()) {
                double w = f instanceof ParagraphFragment p ? p.layout().width() : f instanceof TableFragment t ? t.width() : ((ComponentFragment) f).layout().width();
                double h = f instanceof ParagraphFragment p ? p.layout().height() : f instanceof TableFragment t ? t.height() : ((ComponentFragment) f).layout().height();
                assertTrue(f.origin().x() >= b.x()); assertTrue(f.origin().y() >= b.y());
                assertTrue(f.origin().x() + w <= b.x() + b.width() + 1e-9);
                assertTrue(f.origin().y() + h <= b.y() + b.height() + 1e-9);
            }
        }
    }
}
