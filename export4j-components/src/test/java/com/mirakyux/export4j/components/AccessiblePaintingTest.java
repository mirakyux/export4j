// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.AccessibilityTree;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.layout.core.DocumentPainter;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessiblePaintingTest {
    @Test void sourceOrderListsFiguresAndCrossPageTableHeadersHaveExactBindings() throws Exception {
        var rows = new ArrayList<TableRow>(); var cell = CellFormat.of(12);
        rows.add(new TableRow(List.of(new TableCell("Name", cell), new TableCell("Value", cell))));
        for (int i = 0; i < 16; i++) { rows.add(new TableRow(List.of(new TableCell("Item " + i, cell), new TableCell("Value " + i, cell)))); }
        var table = new Table("table", List.of(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1)), rows, 1, TableFormat.DEFAULT);
        var format = ParagraphFormat.of(12); var source = new Document(new DocumentMetadata("Accessible model", ""),
                new PageSettings(new PageSize(320, 240), PageMargins.uniform(20)), List.of(
                new Heading("heading", "Heading", 1, format), new Paragraph("body", "Ordinary body", format),
                new BulletItem("bullet", "Bullet body", "-", 1, format), new NumberedItem("number", "list", "Number body", 0, format, 1),
                Paragraph.mixed("figure", List.of(new InlineImage(image(), 18, 12, 0, "Blue square")), format), table));
        var result = DocumentEngine.builder(fonts()).build().layout(source);
        var display = DocumentPainter.paintAccessible(source, result.body(), "en-US");
        display.accessibility().validatePages(display.pages()); var logical = display.accessibility().children();
        assertEquals(List.of("H1", "P", "L", "L", "FIGURE", "TABLE"), logical.stream().map(node -> node.role().name()).toList());
        assertEquals("Blue square", logical.get(4).alternativeText());
        var figureCommands = logical.get(4).content().stream().flatMap(range -> display.pages().get(range.pageIndex()).commands()
                .subList(range.firstCommand(), range.endCommand()).stream()).toList();
        assertEquals(1, figureCommands.size(), "a figure must not expose the ordinary U+FFFC selection anchor as spoken text");
        assertTrue(figureCommands.getFirst() instanceof DrawCommand.Image);
        for (int index : List.of(2, 3)) {
            var item = logical.get(index).children().getFirst(); assertEquals("LI", item.role().name());
            assertEquals(index == 2 ? "- " : "1. ", text(display, item.children().get(0)));
            assertEquals(index == 2 ? "Bullet body" : "Number body", text(display, item.children().get(1)));
        }
        var logicalTable = logical.get(5); var header = logicalTable.children().getFirst().children().getFirst();
        var body = logicalTable.children().get(1).children().getFirst();
        assertEquals(List.of(header.id()), body.cell().headers());
        assertEquals(1, header.content().size(), "repeated visual headers must not repeat logical header content");
        assertTrue(display.pages().size() > 1); assertTrue(!display.accessibility().artifacts().isEmpty());
        var composed = new com.mirakyux.export4j.layout.core.PageComposer().composeDetailed(display,
                display.pages().stream().map(page -> new com.mirakyux.export4j.layout.core.PageComposer.PageSpec(source.pageSettings(), com.mirakyux.export4j.layout.core.PageTemplate.EMPTY, 1)).toList(), fonts()).display();
        assertEquals(display.accessibility(), composed.accessibility());
        for (int p = 0; p < display.pages().size(); p++) {
            var ordinary = DocumentPainter.paintPage(result.body().pages().get(p));
            assertEquals(glyphPositions(ordinary.commands()), glyphPositions(display.pages().get(p).commands()), "tagging changed positioned glyphs");
        }
    }
    @Test void wrongSourceMissingAltAndSkippedHeadingLevelsFailBeforeOutput() throws Exception {
        var format = ParagraphFormat.of(12); var source = new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(new Heading("h", "Heading", 2, format)));
        var result = DocumentEngine.builder(fonts()).build().layout(source);
        assertThrows(IllegalArgumentException.class, () -> DocumentPainter.paintAccessible(source, result.body(), "en-US"));
        var wrong = new Document(source.metadata(), source.pageSettings(), List.of(new Paragraph("wrong", "body", format)));
        assertThrows(IllegalArgumentException.class, () -> DocumentPainter.paintAccessible(wrong, result.body(), "en-US"));
        var noAlt = new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(Paragraph.mixed("image", List.of(new InlineImage(image(), 10, 10, 0, "")), format)));
        var figure = DocumentEngine.builder(fonts()).build().layout(noAlt);
        assertThrows(IllegalArgumentException.class, () -> DocumentPainter.paintAccessible(noAlt, figure.body(), "en-US"));
    }
    private static String text(com.mirakyux.export4j.graphics.DisplayDocument display, AccessibilityTree.Node node) {
        var result = new StringBuilder(); for (var range : node.content()) { for (int i = range.firstCommand(); i < range.endCommand(); i++) {
            if (display.pages().get(range.pageIndex()).commands().get(i) instanceof DrawCommand.GlyphText command) { result.append(command.run().text()); }
        } } return result.toString();
    }
    private static List<String> glyphPositions(List<DrawCommand> commands) {
        var result = new ArrayList<String>(); for (var command : commands) { if (command instanceof DrawCommand.GlyphText text) {
            double x = text.baseline().x(); for (var glyph : text.run().glyphs()) { result.add(glyph.codePoint() + ":" + Math.round(x * 1000) + ":" + text.baseline().y()); x += glyph.advance(); }
        } } return result;
    }
    private static FontGroup fonts() throws Exception {
        try (var input = AccessiblePaintingTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) { return FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES))); }
    }
    private static ImageResource image() throws Exception {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB); var output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output); image.flush(); return ImageResource.load(output.toByteArray());
    }
}
