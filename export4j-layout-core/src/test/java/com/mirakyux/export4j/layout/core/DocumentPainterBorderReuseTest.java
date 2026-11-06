// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.layout.CellFragment;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.DocumentLayout;
import com.mirakyux.export4j.layout.LayoutFragment;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.SemanticPlacement;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.TableRowFragment;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.TextLine;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DocumentPainterBorderReuseTest {
    private static final PageSize SIZE = new PageSize(4096, 4096);
    private static final Rect CONTENT = new Rect(0, 0, 4096, 4096);
    private static final ParagraphLayout EMPTY = new ParagraphLayout(1, 1,
            List.of(new TextLine(0, 0, 0, 0, 0, List.of())), List.of());
    private static FontGroup fonts;

    @BeforeAll
    static void loadFont() throws IOException {
        try (var input = DocumentPainterBorderReuseTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    @Test
    void repeatedPagesSharePathsWithoutDroppingHeaderOrStrokeCommands() {
        var table = table("header", new Point(10, 20), 20, 10, true);
        var output = DocumentPainter.paint(document(page(0, table), page(1, table), page(2, table)));
        var first = strokes(output.pages().getFirst()).getFirst();
        for (var page : output.pages()) {
            assertEquals(2, page.commands().size());
            assertEquals(new DrawCommand.FillRect(new Rect(10, 20, 20, 10),
                    new RgbColor(0.93, 0.95, 0.97)), page.commands().getFirst());
            var stroke = assertInstanceOf(DrawCommand.StrokePath.class, page.commands().getLast());
            assertSame(first.path(), stroke.path());
            assertEquals(RgbColor.BLACK, stroke.color());
            assertEquals(0.5, stroke.width());
        }
        assertSame(output.pages().get(0).commands().getLast(), output.pages().get(1).commands().getLast());
        assertOutline(first.path(), 10.25, 20.25, 29.75, 29.75);
    }

    @Test
    void distinctPositionsDimensionsAndSubPointEdgesKeepExactGeometry() {
        var output = DocumentPainter.paintPage(page(0,
                table("base", new Point(10, 20), 20, 10, false),
                table("x", new Point(11, 20), 20, 10, false),
                table("y", new Point(10, 21), 20, 10, false),
                table("width", new Point(10, 20), 21, 10, false),
                table("height", new Point(10, 20), 20, 11, false),
                table("thin", new Point(10, 20), 0.125, 10, false),
                table("ulp", new Point(Math.nextUp(10.0), 20), 20, 10, false)));
        var strokes = strokes(output);
        assertEquals(7, output.commands().size());
        assertEquals(7, strokes.size());
        assertOutline(strokes.get(0).path(), 10.25, 20.25, 29.75, 29.75);
        assertOutline(strokes.get(1).path(), 11.25, 20.25, 30.75, 29.75);
        assertOutline(strokes.get(2).path(), 10.25, 21.25, 29.75, 30.75);
        assertOutline(strokes.get(3).path(), 10.25, 20.25, 30.75, 29.75);
        assertOutline(strokes.get(4).path(), 10.25, 20.25, 29.75, 30.75);
        assertOutline(strokes.get(5).path(), 10.0625, 20.0625, 10.0625, 29.9375);
        assertEquals(0.125, strokes.get(5).width());
        assertEquals(new VectorPath.MoveTo(new Point(Math.nextUp(10.25), 20.25)),
                strokes.get(6).path().segments().getFirst());
        for (int first = 0; first < strokes.size(); first++) {
            for (int second = first + 1; second < strokes.size(); second++) {
                assertNotSame(strokes.get(first).path(), strokes.get(second).path());
            }
        }
    }

    @Test
    void sharedOutlineDoesNotShareOrChangeTheStrokeWidth() {
        var output = DocumentPainter.paintPage(page(0,
                table("narrow", new Point(10, 20), 0.125, 5, false),
                table("wider", new Point(9.9375, 19.9375), 0.25, 5.125, false)));
        var strokes = strokes(output);
        assertEquals(2, output.commands().size());
        assertSame(strokes.get(0).path(), strokes.get(1).path());
        assertNotSame(strokes.get(0), strokes.get(1));
        assertEquals(0.125, strokes.get(0).width());
        assertEquals(0.25, strokes.get(1).width());
        assertOutline(strokes.get(0).path(), 10.0625, 20.0625, 10.0625, 24.9375);
    }

    @Test
    void evictedPathsAreRebuiltWithoutChangingEarlierCommandsOrGeometry() {
        int distinct = 2048;
        var tables = new ArrayList<LayoutFragment>();
        for (int index = 0; index < distinct; index++) {
            tables.add(table("unique-" + index, new Point(10, index), 20, 10, false));
        }
        tables.add(table("again", new Point(10, 0), 20, 10, false));
        var output = DocumentPainter.paintPage(new LayoutPage(0, SIZE, CONTENT, tables));
        var strokes = strokes(output);
        assertEquals(distinct + 1, output.commands().size());
        assertEquals(distinct + 1, strokes.size());
        for (int index = 0; index < distinct; index++) {
            assertOutline(strokes.get(index).path(), 10.25, index + 0.25, 29.75, index + 9.75);
        }
        assertNotSame(strokes.getFirst().path(), strokes.getLast().path());
        assertEquals(strokes.getFirst().path(), strokes.getLast().path());
    }

    @Test
    void nestedAndComponentTablesReuseLocalPathsWhilePreservingTransformScopes() {
        var local = table("local", new Point(1, 2), 20, 10, false);
        var nested = table("nested", new Point(1, 2), 20, 10, false);
        var blocks = CellFragment.blocks(0, new Rect(0, 0, 40, 30), new Point(3, 4), List.of(nested));
        var outer = new TableFragment("outer", 1, new Point(40, 50), 40, 30,
                List.of(new TableRowFragment(0, false, false, List.of(blocks))), false);
        var component = component();
        var output = DocumentPainter.paint(document(page(0, local, outer,
                new ComponentFragment("component", 2, new Point(70, 80), component),
                table("after", new Point(10, 20), 20, 10, false)))).pages().getFirst();
        var strokes = strokes(output);
        assertEquals(4, strokes.size());
        assertSame(strokes.get(0).path(), strokes.get(1).path());
        assertSame(strokes.get(0).path(), strokes.get(2).path());
        assertNotSame(strokes.get(0).path(), strokes.get(3).path());
        assertOutline(strokes.get(0).path(), 1.25, 2.25, 20.75, 11.75);
        assertOutline(strokes.get(3).path(), 10.25, 20.25, 29.75, 29.75);
        assertEquals(List.of(strokes.get(0), new DrawCommand.Save(),
                new DrawCommand.Transform(AffineTransform.translation(43, 54)), strokes.get(1),
                new DrawCommand.Restore(), new DrawCommand.Save(),
                new DrawCommand.Transform(AffineTransform.translation(70, 80)), new DrawCommand.Save(),
                new DrawCommand.Transform(AffineTransform.translation(5, 6)), strokes.get(2),
                new DrawCommand.Restore(), new DrawCommand.Restore(), strokes.get(3)), output.commands());
    }

    @Test
    void everyPublicPaintingCallOwnsAnIndependentResourceScope() {
        var page = page(0, table("one", new Point(1, 2), 20, 10, false));
        var first = strokes(DocumentPainter.paint(document(page)).pages().getFirst()).getFirst().path();
        var second = strokes(DocumentPainter.paint(document(page)).pages().getFirst()).getFirst().path();
        var single = strokes(DocumentPainter.paintPage(page)).getFirst().path();
        var anotherSingle = strokes(DocumentPainter.paintPage(page)).getFirst().path();
        assertEquals(first, second);
        assertEquals(first, single);
        assertNotSame(first, second);
        assertNotSame(first, single);
        assertNotSame(single, anotherSingle);

        var builder = new DisplayListBuilder(SIZE);
        var component = component();
        DocumentPainter.paintComponent(builder, component);
        DocumentPainter.paintComponent(builder, component);
        var componentOutput = builder.build();
        var componentStrokes = strokes(componentOutput);
        assertEquals(8, componentOutput.commands().size());
        assertEquals(2, componentStrokes.size());
        assertEquals(first, componentStrokes.getFirst().path());
        assertNotSame(first, componentStrokes.getFirst().path());
        assertNotSame(componentStrokes.getFirst().path(), componentStrokes.getLast().path());
    }

    private static ComponentLayout component() {
        var source = Table.builder("semantic").columns(new ColumnWidth.Fixed(20))
                .row(new TableCell("", CellFormat.of(12))).build();
        var fragment = table(source.id(), new Point(1, 2), 20, 10, false);
        var placement = new SemanticPlacement(2, source, fragment, fonts, Map.of());
        return new ComponentLayout(100, 100, 1,
                List.of(new DrawCommand.Save(), new DrawCommand.Transform(AffineTransform.translation(5, 6)),
                        new DrawCommand.Restore()), List.of(), List.of(placement));
    }

    private static TableFragment table(String id, Point origin, double width, double height, boolean header) {
        var cell = new CellFragment(0, new Rect(0, 0, width, height), new Point(0, 0), EMPTY);
        return new TableFragment(id, 0, origin, width, height,
                List.of(new TableRowFragment(0, header, false, List.of(cell))));
    }

    private static LayoutPage page(int index, LayoutFragment... fragments) {
        return new LayoutPage(index, SIZE, CONTENT, List.of(fragments));
    }

    private static DocumentLayout document(LayoutPage... pages) {
        return new DocumentLayout(DocumentMetadata.EMPTY, List.of(pages));
    }

    private static List<DrawCommand.StrokePath> strokes(DisplayPage page) {
        return page.commands().stream().filter(DrawCommand.StrokePath.class::isInstance)
                .map(DrawCommand.StrokePath.class::cast).toList();
    }

    private static void assertOutline(VectorPath path, double left, double top, double right, double bottom) {
        assertEquals(List.of(new VectorPath.MoveTo(new Point(left, top)),
                new VectorPath.LineTo(new Point(right, top)), new VectorPath.LineTo(new Point(right, bottom)),
                new VectorPath.LineTo(new Point(left, bottom)), new VectorPath.Close()), path.segments());
    }
}
