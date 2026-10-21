// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.SemanticPlacement;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.CellLocation;
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
import com.mirakyux.export4j.text.ParagraphLayouter;

class NestedSourceBudgetTest {
    private static final PageSettings PAGE = new PageSettings(new PageSize(240, 240), PageMargins.uniform(20));
    private static final CellFormat CELL = CellFormat.of(10);
    private static final ParagraphFormat PARAGRAPH = ParagraphFormat.of(10);
    private static final TableFormat TABLE = new TableFormat(PageMargins.uniform(2), 0, 0);
    private static FontGroup fonts;

    @BeforeAll
    static void loadFont() throws IOException {
        try (var input = NestedSourceBudgetTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            if (input == null) { throw new IOException("missing test font"); }
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    @Test
    void cumulativeCellSlotsKeepTheFailingSourcesHostIndexAndNestedPath() {
        var document = document();
        assertEquals(1, layouter(100, 3).layout(document, fonts).pages().size());

        var failure = assertThrows(DocumentLayoutException.class, () -> layouter(100, 2).layout(document, fonts));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals("inner", failure.blockId());
        assertEquals(2, failure.blockIndex());
        assertEquals(List.of(new CellLocation("outer", 0, 0, 1)), failure.sourcePath());
    }

    @Test
    void cumulativeSemanticNodesKeepTheDeepestBlocksHostIndexAndAncestry() {
        var document = document();
        assertEquals(1, layouter(8, 100).layout(document, fonts).pages().size());

        var failure = assertThrows(DocumentLayoutException.class, () -> layouter(7, 100).layout(document, fonts));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals("leaf", failure.blockId());
        assertEquals(2, failure.blockIndex());
        assertEquals(List.of(new CellLocation("outer", 0, 0, 1), new CellLocation("inner", 0, 0, 0)),
                failure.sourcePath());
    }

    private static Document document() {
        var first = table("first-source", new TableCell("first", CELL));
        var inner = table("inner", TableCell.builder().blocks(List.of(new Paragraph("leaf", "leaf", PARAGRAPH))).build());
        var outer = table("outer", TableCell.builder().blocks(List.of(
                new Paragraph("before", "before", PARAGRAPH), inner)).build());
        return new Document(DocumentMetadata.EMPTY, PAGE, List.of(new Paragraph("prefix", "prefix", PARAGRAPH),
                host("first-host", first), host("second-host", outer)));
    }

    private static ComponentBlock host(String id, Table source) {
        return new ComponentBlock(id, context -> {
            var cursor = new DocumentLayouter().prepareTable(source, context);
            var fragment = cursor.take(context.height()).orElseThrow();
            assertTrue(cursor.done());
            var placement = new SemanticPlacement(0, source, fragment, context.fontGroup(), context.fontRoles());
            int lines = Math.toIntExact(fragment.rows().stream().flatMap(row -> row.cells().stream())
                    .mapToLong(cell -> cell.lineCount()).sum());
            return new ComponentLayout(fragment.width(), fragment.height(), lines, List.of(), List.of(), List.of(placement));
        });
    }

    private static Table table(String id, TableCell cell) {
        return Table.builder(id).columns(new ColumnWidth.Fraction(1)).format(TABLE).row(cell).build();
    }

    private static DocumentLayouter layouter(int nodes, long cells) {
        return new DocumentLayouter(new ParagraphLayouter(), new DocumentLayoutLimits(nodes, 100, 100, 10, 10, 10, cells));
    }
}
