// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;

class SplitTableTest {
    @Test void splitIsOptInAndPreservesEveryCellSourceLine() throws IOException {
        try (var input = getClass().getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            var fonts = List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES));
            String text = java.util.stream.IntStream.rangeClosed(1, 40).mapToObj(i -> "ROW-" + i).collect(java.util.stream.Collectors.joining("\n"));
            var cells = List.of(new TableCell("Header", CellFormat.of(10)), new TableCell("Other", CellFormat.of(10)));
            var rows = List.of(new TableRow(cells), new TableRow(List.of(new TableCell(text, CellFormat.of(10)), new TableCell("SHORT", CellFormat.of(10)))));
            var settings = new PageSettings(new PageSize(220, 150), PageMargins.uniform(10));
            var strict = new Table("t", List.of(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1)), rows, 1, TableFormat.DEFAULT);
            assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter().layout(new Document(DocumentMetadata.EMPTY, settings, List.of(strict)), fonts));
            var split = new Table("t", strict.columns(), rows, 1, new TableFormat(PageMargins.uniform(3), 0, 0, true));
            var result = new DocumentLayouter().layout(new Document(DocumentMetadata.EMPTY, settings, List.of(split)), fonts);
            var fragments = result.pages().stream().flatMap(p -> p.elements().stream()).map(TableFragment.class::cast).toList();
            assertTrue(fragments.size() >= 5);
            var body = fragments.stream().flatMap(t -> t.rows().stream()).filter(r -> !r.header()).toList();
            assertTrue(body.stream().skip(1).allMatch(com.mirakyux.export4j.layout.TableRowFragment::continuedRow));
            String actual = body.stream().flatMap(r -> r.cells().getFirst().layout().lines().stream()).flatMap(l -> l.runs().stream())
                    .map(com.mirakyux.export4j.text.GlyphRun::text).collect(java.util.stream.Collectors.joining());
            assertEquals(text.replace("\n", ""), actual);
            int offset = 0;
            for (var row : body) { assertEquals(offset, row.cells().getFirst().firstLineIndex()); offset += row.cells().getFirst().layout().lines().size(); }
            assertEquals(40, offset);
            assertTrue(fragments.stream().skip(1).allMatch(t -> t.rows().getFirst().repeatedHeader()));
        }
    }
}
