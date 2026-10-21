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
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.TextOutline;

class RowSpanComponentsTest {
    private static final CellFormat CELL = CellFormat.builder().fontSize(8).lineHeight(20).build();
    private static final PageSettings PAGE = PageSettings.builder().size(300, 120).margins(10).build();
    private static FontGroup fonts;

    @BeforeAll static void fonts() throws IOException {
        try (var input = RowSpanComponentsTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    @Test void mergedHeadersFieldsImagesAndLinksResolveOnceInBodyFlowAndColumns() throws IOException {
        for (int host = 0; host < 3; host++) {
            var source = source();
            Block block = switch (host) {
                case 0 -> source;
                case 1 -> new ComponentBlock("host", FlowColumn.builder().semanticTable(source).build(), 0, 0);
                default -> new ComponentBlock("host", ContinuousColumns.builder(new SemanticTable(source)).gap(10).build(), 0, 0);
            };
            var result = DocumentEngine.builder(fonts).build().layout(new Document(DocumentMetadata.EMPTY, PAGE, List.of(block)));
            int images = 0;
            var bodyRows = new ArrayList<Integer>();
            for (var page : result.body().pages()) {
                var parts = new ArrayList<TableFragment>();
                for (var fragment : page.elements()) {
                    if (fragment instanceof TableFragment table) { parts.add(table); }
                    else if (fragment instanceof ComponentFragment component) {
                        ComponentSemantics.locate(component.layout(), component.origin()).forEach(p -> parts.add(p.placement().fragment()));
                    }
                }
                for (var part : parts) {
                    var header = part.rows().getFirst();
                    assertEquals(0, header.rowIndex()); assertEquals(1, header.cells().size());
                    assertEquals("H " + (page.pageIndex() + 1) + "\nHEAD", logical(header.cells().getFirst().layout()));
                    assertEquals(40, header.cells().getFirst().bounds().height());
                    for (var row : part.rows()) {
                        for (var cell : row.cells()) {
                            assertTrue(source.grid().anchorAt(row.rowIndex(), cell.columnIndex()).isPresent());
                            assertTrue(cell.layout().lines().stream().allMatch(line -> line.fields().isEmpty()));
                            images += cell.layout().lines().stream().mapToInt(line -> line.images().size()).sum();
                        }
                        if (!row.header()) {
                            bodyRows.add(row.rowIndex());
                            if (row.rowIndex() % 2 == 1) { assertEquals(List.of(1), row.cells().stream().map(c -> c.columnIndex()).toList()); }
                        }
                    }
                }
            }
            assertEquals(List.of(2, 3, 4, 5, 6, 7), bodyRows); assertEquals(1, images);
            var links = result.display().extras().externalLinks();
            assertEquals(2, links.size());
            assertEquals(java.util.Set.of("https://example.com/row-text", "https://example.com/row-image"),
                    links.stream().map(link -> link.destination().uri()).collect(java.util.stream.Collectors.toSet()));
            assertEquals(links.getFirst().pageIndex(), links.getLast().pageIndex());
        }
    }

    @Test void generatedFieldFailureKeepsOriginalLowerAnchorRowAndPhysicalColumn() {
        var bad = TableCell.mixed(List.of(text("p "), new InlineField(InlineField.Kind.PAGE, "", .1, InlineStyle.of(8))), CELL)
                .toBuilder().columnSpan(2).build();
        var source = new Table("bad", columns(), List.of(new TableRow(List.of(cell("A", 1, 2), cell("B", 2, 1))),
                new TableRow(List.of(bad))), 0, new TableFormat(PageMargins.uniform(0), 0, 0));
        for (boolean nested : List.of(false, true)) {
            Block block = nested ? new ComponentBlock("host", FlowColumn.builder().semanticTable(source).build(), 0, 0) : source;
            var failure = assertThrows(DocumentLayoutException.class, () -> DocumentEngine.builder(fonts).build()
                    .layout(new Document(DocumentMetadata.EMPTY, PAGE, List.of(block))));
            assertEquals("bad", failure.blockId()); assertEquals(1, failure.rowIndex());
            assertEquals(1, failure.columnIndex()); assertEquals(2, failure.sourceIndex());
        }
    }

    private static Table source() throws IOException {
        var rows = new ArrayList<TableRow>();
        rows.add(new TableRow(List.of(TableCell.mixed(List.of(text("H "), field(InlineField.Kind.PAGE), text("\nHEAD")), CELL)
                .toBuilder().columnSpan(3).rowSpan(2).build())));
        rows.add(TableRow.covered());
        var pixel = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        byte[] encoded;
        try (var output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(pixel, "png", output)) { throw new IOException("missing PNG writer"); }
            encoded = output.toByteArray();
        }
        for (int group = 0; group < 3; group++) {
            rows.add(new TableRow(List.of(cell("A\nA", 1, 2), cell("B" + group, 2, 1))));
            List<InlineContent> contents = new ArrayList<>(List.of(text("P="), field(InlineField.Kind.PAGE)));
            if (group == 2) {
                contents.add(text(" N=")); contents.add(field(InlineField.Kind.PAGES));
                contents.add(new InlineText(" L", InlineStyle.of(8), "https://example.com/row-text"));
                contents.add(new InlineImage(ImageResource.load(encoded), 8, 8, 0, "pixel", "https://example.com/row-image"));
            }
            rows.add(new TableRow(List.of(TableCell.mixed(contents, CELL).toBuilder().columnSpan(2).build())));
        }
        return new Table("spans", columns(), rows, 2, new TableFormat(PageMargins.uniform(0), 0, 0));
    }
    private static List<ColumnWidth> columns() { return List.of(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1)); }
    private static TableCell cell(String text, int columns, int rows) {
        return TableCell.builder().text(text).format(CELL).columnSpan(columns).rowSpan(rows).build();
    }
    private static InlineText text(String value) { return new InlineText(value, InlineStyle.of(8)); }
    private static InlineField field(InlineField.Kind kind) { return new InlineField(kind, "", 12, InlineStyle.of(8)); }
    private static String logical(ParagraphLayout layout) {
        return layout.lines().stream().map(line -> line.outlines().stream().map(TextOutline::logicalText)
                .collect(java.util.stream.Collectors.joining())).collect(java.util.stream.Collectors.joining("\n"));
    }
}
