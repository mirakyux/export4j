// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.SemanticPlacement;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.GridItem;
import com.mirakyux.export4j.model.GridLayout;
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

class DocumentPageStreamingTest {
    private static final PageSettings PAGE = new PageSettings(new PageSize(120, 80), PageMargins.uniform(5));
    private static final ParagraphFormat TEXT = new ParagraphFormat(10, 12, 0, 0,
            ParagraphAlignment.LEFT, 0, 0, false);
    private static FontGroup fonts;

    @BeforeAll
    static void loadFont() throws IOException {
        try (var input = DocumentPageStreamingTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            if (input == null) { throw new IOException("missing test font"); }
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    @Test
    void emittedIrMatchesCollectedPagesIncludingRepeatedHeadersAndExclusions() throws IOException {
        var table = Table.builder("table").columns(new ColumnWidth.Fraction(1)).cellFormat(CellFormat.of(10))
                .format(new TableFormat(PageMargins.uniform(1), 0, 0)).header("header");
        for (int row = 0; row < 9; row++) { table.row("row " + row); }
        var document = document(new Paragraph("title", "Title", TEXT.withPagination(
                new PaginationPolicy(true, false, 1, 1))), table.build());
        var layouter = new DocumentLayouter();
        java.util.function.IntFunction<List<Rect>> exclusions = page -> page == 0
                ? List.of(new Rect(5, 55, 110, 10)) : List.of();
        var collected = layouter.layout(document, fonts, Map.of(), exclusions);
        var emitted = new ArrayList<LayoutPage>();
        int count = layouter.layoutPages(document, fonts, Map.of(), exclusions, emitted::add);
        assertEquals(collected.pages(), emitted);
        assertEquals(emitted.size(), count);
        var tables = emitted.stream().flatMap(page -> page.elements().stream())
                .filter(TableFragment.class::isInstance).map(TableFragment.class::cast).toList();
        assertTrue(tables.size() > 1);
        assertTrue(tables.stream().allMatch(part -> part.rows().getFirst().header()));
    }

    @Test
    void keepChainRetainsPreparedGridRowsWithoutChargingTheirLinesTwice() throws IOException {
        var grid = new GridLayout("grid", 1, 0, 0, List.of(new GridItem("item", 1, 1, 1,
                List.of(new Paragraph("grid-text", "grid", TEXT)))));
        var document = document(new Paragraph("title", "Title", TEXT.withPagination(
                new PaginationPolicy(true, false, 1, 1))), grid, new Paragraph("tail", "tail", TEXT));
        var layouter = new DocumentLayouter(new ParagraphLayouter(), new DocumentLayoutLimits(10, 1000, 3, 10));
        var expected = layouter.layout(document, fonts).pages();
        var emitted = new ArrayList<LayoutPage>();
        layouter.layoutPages(document, fonts, Map.of(), emitted::add);
        assertEquals(expected, emitted);
    }

    @Test
    void consumerReceivesPagesBeforeLaterBlocksAndCheckedFailureStopsImmediately() {
        var laterMeasurements = new AtomicInteger();
        var callbacks = new AtomicInteger();
        var later = new ComponentBlock("later", context -> {
            laterMeasurements.incrementAndGet();
            return new ComponentLayout(10, 10, 0, List.of(), List.of());
        });
        var document = document(new Paragraph("long", "a\nb\nc\nd\ne\nf\ng\nh", TEXT), later);
        var expected = new IOException("consumer stopped");
        var actual = assertThrows(IOException.class, () -> new DocumentLayouter().layoutPages(document, fonts,
                Map.of(), page -> {
                    assertEquals(0, page.pageIndex());
                    assertEquals(0, laterMeasurements.get());
                    callbacks.incrementAndGet();
                    throw expected;
                }));
        assertSame(expected, actual);
        assertEquals(1, callbacks.get());
        assertEquals(0, laterMeasurements.get());
    }

    @Test
    void completedSourcesStillContributeToStructuralBudgetsAndIdCollisions() {
        var first = source("source-one");
        var second = source("source-two");
        var layouter = new DocumentLayouter(new ParagraphLayouter(),
                new DocumentLayoutLimits(10, 1000, 100, 10, 10, 10, 1));
        var failure = assertThrows(DocumentLayoutException.class, () -> layouter.layoutPages(
                document(host("host-one", first), host("host-two", second)), fonts, Map.of(), page -> { }));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals("source-two", failure.blockId());
        assertEquals(1, failure.blockIndex());

        var collision = assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter().layoutPages(
                document(host("host-one", first), host("host-two", source("source-one"))),
                fonts, Map.of(), page -> { }));
        assertEquals(DocumentLayoutException.Code.COMPONENT_LAYOUT_FAILED, collision.code());
        assertEquals("host-two", collision.blockId());
    }

    @Test
    void emptyDocumentEmitsOneBlankPhysicalPage() throws IOException {
        var pages = new ArrayList<LayoutPage>();
        assertEquals(1, new DocumentLayouter().layoutPages(document(), fonts, Map.of(), pages::add));
        assertEquals(1, pages.size());
        assertTrue(pages.getFirst().elements().isEmpty());
    }

    private static Table source(String id) {
        return Table.builder(id).columns(new ColumnWidth.Fraction(1)).cellFormat(CellFormat.of(10))
                .format(new TableFormat(PageMargins.uniform(1), 0, 0)).row("body").build();
    }

    private static ComponentBlock host(String id, Table source) {
        return new ComponentBlock(id, context -> {
            var cursor = new DocumentLayouter().prepareTable(source, context);
            var fragment = cursor.take(context.height()).orElseThrow();
            var placement = new SemanticPlacement(0, source, fragment, context.fontGroup(), context.fontRoles());
            return new ComponentLayout(fragment.width(), fragment.height(), 1, List.of(), List.of(), List.of(placement));
        });
    }

    private static Document document(Block... blocks) {
        return new Document(DocumentMetadata.EMPTY, PAGE, List.of(blocks));
    }
}
