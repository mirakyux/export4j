// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.ComponentSemantics;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.model.Block;
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
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.text.TextOutline;

class ColumnSpanComponentsTest {
    private static final CellFormat CELL = CellFormat.builder().fontSize(8).lineHeight(20).build();
    private static final PageSettings PAGE = PageSettings.builder().size(300, 120).margins(10).build();
    private static FontGroup fonts;

    @BeforeAll static void fonts() throws IOException {
        try (var input = ColumnSpanComponentsTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    @Test void finalFieldsAndSecondAnchorLinksWorkInBodyFlowAndContinuousColumns() throws IOException {
        for (int host = 0; host < 3; host++) {
            var source = source();
            Block block = switch (host) {
                case 0 -> source;
                case 1 -> new ComponentBlock("flow", FlowColumn.builder().semanticTable(source).build(), 0, 0);
                default -> new ComponentBlock("columns", ContinuousColumns.builder(new SemanticTable(source)).gap(10).build(), 0, 0);
            };
            var result = DocumentEngine.builder(fonts).build().layout(new Document(DocumentMetadata.EMPTY, PAGE, List.of(block)));
            int next = 0;
            int images = 0;
            for (var page : result.body().pages()) {
                var tables = new ArrayList<TableFragment>();
                for (var part : page.elements()) {
                    if (part instanceof TableFragment table) { tables.add(table); }
                    else if (part instanceof ComponentFragment component) {
                        ComponentSemantics.locate(component.layout(), component.origin())
                                .forEach(p -> tables.add(p.placement().fragment()));
                    }
                }
                for (var table : tables) {
                    assertEquals(1, table.rows().getFirst().cells().size());
                    assertEquals("HEAD " + (page.pageIndex() + 1), logical(table.rows().getFirst().cells().getFirst().layout()));
                    for (var row : table.rows()) {
                        for (var cell : row.cells()) {
                            images += cell.layout().lines().stream().mapToInt(l -> l.images().size()).sum();
                            assertTrue(cell.layout().lines().stream().allMatch(l -> l.fields().isEmpty()));
                        }
                        if (row.header()) { continue; }
                        assertEquals(++next, row.rowIndex());
                        assertEquals(List.of(0, 2), row.cells().stream().map(c -> c.columnIndex()).toList());
                        assertTrue(logical(row.cells().getLast().layout()).startsWith("P=" + (page.pageIndex() + 1)));
                    }
                }
            }
            assertEquals(9, next); assertEquals(1, images);
            var links = result.display().extras().externalLinks();
            assertEquals(2, links.size());
            assertEquals(java.util.Set.of("https://example.com/text", "https://example.com/image"),
                    links.stream().map(l -> l.destination().uri()).collect(java.util.stream.Collectors.toSet()));
            for (var link : links) {
                assertTrue(link.bounds().x() > PAGE.margins().left() + 2 * (PAGE.contentWidth() - (host == 2 ? 10 : 0)) / (host == 2 ? 6 : 3));
            }
        }
    }

    @Test void mergedCellFieldFailureKeepsGridColumnAndOriginalUtf16() {
        var bad = TableCell.mixed(List.of(text("a "), new InlineField(InlineField.Kind.PAGE, "", .1, InlineStyle.of(8))), CELL);
        var source = new Table("bad", List.of(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1)),
                List.of(new TableRow(List.of(TableCell.builder().text("MERGED").format(CELL).columnSpan(2).build(), bad))), 0,
                new TableFormat(PageMargins.uniform(0), 0, 0));
        for (boolean nested : List.of(false, true)) {
            Block block = nested ? new ComponentBlock("host", FlowColumn.builder().semanticTable(source).build(), 0, 0) : source;
            var error = assertThrows(DocumentLayoutException.class, () -> DocumentEngine.builder(fonts).build()
                    .layout(new Document(DocumentMetadata.EMPTY, PAGE, List.of(block))));
            assertEquals("bad", error.blockId()); assertEquals(0, error.rowIndex());
            assertEquals(2, error.columnIndex()); assertEquals(2, error.sourceIndex());
        }
    }

    private static Table source() throws IOException {
        var rows = new ArrayList<TableRow>();
        rows.add(new TableRow(List.of(TableCell.mixed(List.of(text("HEAD "), field()), CELL).toBuilder().columnSpan(3).build())));
        var pixel = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        byte[] encoded;
        try (var output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(pixel, "png", output)) { throw new IOException("missing PNG writer"); }
            encoded = output.toByteArray();
        }
        for (int row = 1; row <= 9; row++) {
            List<InlineContent> contents = new ArrayList<>(List.of(text("P="), field()));
            if (row == 1) {
                contents.add(new InlineText("GO", InlineStyle.of(8), "https://example.com/text"));
                contents.add(new InlineImage(ImageResource.load(encoded), 8, 8, 0, "pixel", "https://example.com/image"));
            }
            rows.add(new TableRow(List.of(TableCell.builder().text("ROW-" + row).format(CELL).columnSpan(2).build(), TableCell.mixed(contents, CELL))));
        }
        return new Table("table", List.of(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1)),
                rows, 1, new TableFormat(PageMargins.uniform(0), 0, 0));
    }

    private static InlineText text(String value) { return new InlineText(value, InlineStyle.of(8)); }
    private static InlineField field() { return new InlineField(InlineField.Kind.PAGE, "", 8, InlineStyle.of(8)); }
    private static String logical(com.mirakyux.export4j.text.ParagraphLayout layout) {
        return layout.lines().stream().flatMap(l -> l.outlines().stream()).map(TextOutline::logicalText).collect(java.util.stream.Collectors.joining());
    }
}
