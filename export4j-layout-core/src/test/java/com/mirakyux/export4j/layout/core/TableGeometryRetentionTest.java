// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.FontMetrics;
import com.mirakyux.export4j.font.GlyphMetrics;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.layout.CellFragment;
import com.mirakyux.export4j.layout.DocumentLayout;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.text.GlyphRun;

class TableGeometryRetentionTest {
    private static final CellFormat CELL = new CellFormat(10, 12, ParagraphAlignment.LEFT);
    private static final PageMargins PADDING = new PageMargins(2, 1, 1, 1);
    private static FontGroup fonts;

    @BeforeAll
    static void loadFont() throws IOException {
        FontData data;
        try (var input = TableGeometryRetentionTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            if (input == null) { throw new IOException("missing test font"); }
            data = TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES).data();
        }
        FontFace face = new FontFace() {
            @Override public FontData data() { return data; }
            @Override public FontMetrics metrics() { return new FontMetrics(0.7, 0.2, 0); }
            @Override public Optional<GlyphMetrics> glyph(int codePoint) {
                return Optional.of(new GlyphMetrics(1, 1));
            }
        };
        fonts = FontGroup.strict(List.of(face));
    }

    @Test
    void nearbyGeometryKeepsExactValuesAndIndependentCellSources() throws IOException {
        double delta = Math.scalb(1.0, -30);
        var nearPadding = new PageMargins(2 + delta, 1, 1, 1 + delta);
        var document = document(120, 200, 0,
                table("original", 40, 44, PADDING, "A\uD83D\uDE00B"),
                table("nearby", 40, 44 + delta, nearPadding, "C\uD83D\uDE00D"),
                table("same-geometry-new-source", 40, 44 + delta, nearPadding, "G\uD83D\uDE00H"));

        var fragments = tables(collectedMatchesPages(new DocumentLayouter(), document));
        assertEquals(List.of("original", "nearby", "same-geometry-new-source"),
                fragments.stream().map(TableFragment::blockId).toList());
        assertEquals(List.of(0, 1, 2), fragments.stream().map(TableFragment::blockIndex).toList());
        var original = fragments.get(0);
        var nearby = fragments.get(1);
        var following = fragments.get(2);

        assertEquals(new Rect(0, 0, 40, 15), original.rows().getFirst().cells().getFirst().bounds());
        assertEquals(new Point(1, 2), original.rows().getFirst().cells().getFirst().textOrigin());
        assertEquals(new Rect(40, 0, 44 + delta, 15 + delta), nearby.rows().getFirst().cells().get(1).bounds());
        assertEquals(new Point(1 + delta, 2 + delta), nearby.rows().getFirst().cells().getFirst().textOrigin());
        assertEquals(new Rect(0, 15 + delta, 40, 15 + delta), nearby.rows().get(1).cells().getFirst().bounds());
        assertEquals(new Point(1 + delta, 17 + 2 * delta), nearby.rows().get(1).cells().getFirst().textOrigin());
        assertNotEquals(original.rows().getFirst().cells().getFirst().bounds(),
                nearby.rows().getFirst().cells().getFirst().bounds());
        assertEquals(nearby.rows().getFirst().cells().getFirst().bounds(),
                following.rows().getFirst().cells().getFirst().bounds());
        assertCellSource(original.rows().getFirst().cells().getFirst(), 0, "A\uD83D\uDE00B");
        assertCellSource(nearby.rows().getFirst().cells().getFirst(), 0, "C\uD83D\uDE00D");
        assertCellSource(following.rows().getFirst().cells().getFirst(), 0, "G\uD83D\uDE00H");
        for (var fragment : fragments) {
            assertEquals(List.of(0, 1), fragment.rows().stream().map(row -> row.rowIndex()).toList());
            assertCellSource(fragment.rows().getFirst().cells().get(1), 1, "C");
            assertCellSource(fragment.rows().get(1).cells().get(1), 1, "E\uD83D\uDE00F");
        }
    }

    @Test
    void earlierIrSurvivesMoreThanTwoThousandDistinctPositionsAndLaterReuse() throws IOException {
        var small = new CellFormat(1, 1, ParagraphAlignment.LEFT);
        var format = new TableFormat(PageMargins.uniform(0.25), 0, 0);
        var rows = new ArrayList<TableRow>();
        var sources = new ArrayList<String>();
        for (int row = 0; row < 2057; row++) {
            String source = row == 0 ? "A\uD83D\uDE00B" : "row-" + row;
            sources.add(source);
            rows.add(new TableRow(List.of(new TableCell(source, small))));
        }
        var columns = List.<ColumnWidth>of(new ColumnWidth.Fixed(40));
        var many = new Table("many-rows", columns, rows, 0, format);
        var returning = new Table("returning-position", columns,
                List.of(new TableRow(List.of(new TableCell("Z\uD83D\uDE00Q", small)))), 0, format);
        var result = collectedMatchesPages(new DocumentLayouter(), document(60, 3500, 1, many, returning));
        assertEquals(1, result.pages().size());
        var fragments = tables(result);
        assertEquals(List.of("many-rows", "returning-position"), fragments.stream().map(TableFragment::blockId).toList());
        assertEquals(List.of(0, 1), fragments.stream().map(TableFragment::blockIndex).toList());
        var retained = fragments.getFirst();
        assertEquals(sources.size(), retained.rows().size());
        for (int row = 0; row < sources.size(); row++) {
            assertEquals(row, retained.rows().get(row).rowIndex());
            assertCellSource(retained.rows().get(row).cells().getFirst(), 0, sources.get(row));
        }
        var first = retained.rows().getFirst().cells().getFirst();
        var last = retained.rows().getLast().cells().getFirst();
        var revisited = fragments.getLast().rows().getFirst().cells().getFirst();
        assertEquals(new Rect(0, 0, 40, 1.5), first.bounds());
        assertEquals(new Point(0.25, 0.25), first.textOrigin());
        assertEquals(new Rect(0, 3084, 40, 1.5), last.bounds());
        assertEquals(new Point(0.25, 3084.25), last.textOrigin());
        assertEquals(first.bounds(), revisited.bounds());
        assertEquals(first.textOrigin(), revisited.textOrigin());
        assertCellSource(first, 0, "A\uD83D\uDE00B");
        assertCellSource(revisited, 0, "Z\uD83D\uDE00Q");
    }

    @Test
    void reusingLayouterDoesNotCarryGeometryOrSourceOwnersBetweenDocuments() throws IOException {
        var leading = new Paragraph("leading", "a\nb", new ParagraphFormat(10, 12, 0, 0,
                ParagraphAlignment.LEFT, 0, 0, false));
        var firstDocument = document(100, 120, 2, leading,
                table("first-table", 40, 44, PADDING, "A\uD83D\uDE00B"));
        var secondDocument = document(170, 200, 7,
                table("second-table", 54, 65, new PageMargins(4, 2, 1, 3), "M\uD83D\uDE00N"));
        var layouter = new DocumentLayouter();
        var first = collectedMatchesPages(layouter, firstDocument);
        var retained = tables(first).getFirst();
        var second = tables(collectedMatchesPages(layouter, secondDocument)).getFirst();

        assertEquals("first-table", retained.blockId());
        assertEquals(1, retained.blockIndex());
        assertEquals(new Point(2, 26), retained.origin());
        assertEquals(new Rect(0, 0, 40, 15), retained.rows().getFirst().cells().getFirst().bounds());
        assertEquals(new Point(1, 2), retained.rows().getFirst().cells().getFirst().textOrigin());
        assertEquals("second-table", second.blockId());
        assertEquals(0, second.blockIndex());
        assertEquals(new Point(7, 7), second.origin());
        assertEquals(new Rect(0, 0, 54, 17), second.rows().getFirst().cells().getFirst().bounds());
        assertEquals(new Point(3, 4), second.rows().getFirst().cells().getFirst().textOrigin());
        assertCellSource(retained.rows().getFirst().cells().getFirst(), 0, "A\uD83D\uDE00B");
        assertCellSource(second.rows().getFirst().cells().getFirst(), 0, "M\uD83D\uDE00N");
        assertEquals(first.pages(), collectedMatchesPages(layouter, firstDocument).pages());
    }

    private static DocumentLayout collectedMatchesPages(DocumentLayouter layouter, Document document) throws IOException {
        var collected = layouter.layout(document, fonts);
        var emitted = new ArrayList<LayoutPage>();
        int count = layouter.layoutPages(document, fonts, Map.of(), emitted::add);
        assertEquals(collected.pages(), emitted);
        assertEquals(emitted.size(), count);
        return collected;
    }

    private static void assertCellSource(CellFragment cell, int column, String source) {
        assertEquals(column, cell.columnIndex());
        assertEquals(0, cell.firstLineIndex());
        assertEquals(1, cell.layout().lines().size());
        var line = cell.layout().lines().getFirst();
        assertEquals(0, line.sourceStart());
        assertEquals(source.length(), line.sourceEnd());
        assertEquals(source, line.runs().stream().map(GlyphRun::text).reduce("", String::concat));
    }

    private static Table table(String id, double firstWidth, double secondWidth, PageMargins padding, String firstSource) {
        return new Table(id, List.of(new ColumnWidth.Fixed(firstWidth), new ColumnWidth.Fixed(secondWidth)),
                List.of(new TableRow(List.of(new TableCell(firstSource, CELL), new TableCell("C", CELL))),
                        new TableRow(List.of(new TableCell("D", CELL), new TableCell("E\uD83D\uDE00F", CELL)))),
                0, new TableFormat(padding, 0, 0));
    }

    private static Document document(double width, double height, double margin, Block... blocks) {
        return new Document(DocumentMetadata.EMPTY,
                new PageSettings(new PageSize(width, height), PageMargins.uniform(margin)), List.of(blocks));
    }

    private static List<TableFragment> tables(DocumentLayout layout) {
        return layout.pages().stream().flatMap(page -> page.elements().stream())
                .filter(TableFragment.class::isInstance).map(TableFragment.class::cast).toList();
    }
}
