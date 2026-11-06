// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.layout.CellFragment;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.DocumentLayout;
import com.mirakyux.export4j.layout.LayoutFragment;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.SemanticPlacement;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.TableRowFragment;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.text.ImageBox;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.TextLine;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DocumentPainterImageReuseTest {
    private static final PageSize SIZE = new PageSize(100, 100);
    private static final Rect CONTENT = new Rect(0, 0, 100, 100);
    private static FontGroup fonts;

    @BeforeAll
    static void loadFont() throws IOException {
        try (var input = DocumentPainterImageReuseTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    @Test
    void oneDocumentReusesImagesAcrossPagesAndRecursiveTableCells() throws IOException {
        var resource = resource();
        var paragraph = imageLayout(resource);
        var nested = table("nested", new Point(1, 10), List.of(new CellFragment(0,
                new Rect(0, 0, 8, 8), new Point(0, 0), paragraph)), 8, 8);
        var blocks = CellFragment.blocks(0, new Rect(0, 0, 20, 20), new Point(0, 0),
                List.of(new ParagraphFragment("cell-paragraph", 0, 0, new Point(1, 1), paragraph), nested));
        var inline = new CellFragment(1, new Rect(20, 0, 20, 20), new Point(20, 0), paragraph);
        var outer = table("outer", new Point(10, 30), List.of(blocks, inline), 40, 20);
        var first = page(0, List.of(new ParagraphFragment("first", 0, 0, new Point(3, 4), paragraph), outer));
        var second = page(1, List.of(new ParagraphFragment("second", 2, 0, new Point(7, 8), paragraph)));
        var output = DocumentPainter.paint(new DocumentLayout(DocumentMetadata.EMPTY, List.of(first, second)));
        var images = output.pages().stream().flatMap(item -> images(item).stream()).toList();
        assertEquals(5, images.size());
        for (var image : images) {
            assertSame(images.getFirst().image(), image.image());
        }
        assertEquals(new Rect(3, 4, 6, 6), images.getFirst().bounds());
        assertEquals(new Rect(7, 8, 6, 6), images.getLast().bounds());
    }

    @Test
    void documentCacheReachesEveryDeferredComponentTable() throws IOException {
        var resource = resource();
        var component = component(resource);
        var page = page(0, List.of(new ParagraphFragment("body", 0, 0, new Point(0, 0), imageLayout(resource)),
                new ComponentFragment("component", 1, new Point(10, 20), component)));
        var output = DocumentPainter.paint(new DocumentLayout(DocumentMetadata.EMPTY, List.of(page)));
        var images = images(output.pages().getFirst());
        assertEquals(3, images.size());
        assertSame(images.get(0).image(), images.get(1).image());
        assertSame(images.get(0).image(), images.get(2).image());

        var standalone = new DisplayListBuilder(SIZE);
        DocumentPainter.paintComponent(standalone, component);
        var standaloneImages = images(standalone.build());
        assertEquals(2, standaloneImages.size());
        assertSame(standaloneImages.getFirst().image(), standaloneImages.getLast().image());
        assertNotSame(images.getFirst().image(), standaloneImages.getFirst().image());
    }

    @Test
    void legacyPagePaintingKeepsCachesIndependentBetweenCalls() throws IOException {
        var layout = imageLayout(resource());
        var page = page(0, List.of(new ParagraphFragment("one", 0, 0, new Point(0, 0), layout),
                new ParagraphFragment("two", 1, 0, new Point(0, 20), layout)));
        var first = images(DocumentPainter.paintPage(page));
        var second = images(DocumentPainter.paintPage(page));
        assertEquals(2, first.size());
        assertEquals(2, second.size());
        assertSame(first.getFirst().image(), first.getLast().image());
        assertSame(second.getFirst().image(), second.getLast().image());
        assertNotSame(first.getFirst().image(), second.getFirst().image());
    }

    private static ComponentLayout component(ImageResource resource) {
        var first = placement("component-first", resource, new Point(0, 0));
        var second = placement("component-second", resource, new Point(10, 0));
        return new ComponentLayout(24, 12, 2, List.of(), List.of(), List.of(first, second));
    }

    private static SemanticPlacement placement(String id, ImageResource resource, Point origin) {
        var cell = TableCell.mixed(List.of(new InlineImage(resource, 6, 6, 0, "sample")), CellFormat.of(12));
        var source = Table.builder(id).columns(new ColumnWidth.Fixed(8)).row(cell).build();
        var fragment = table(id, origin, List.of(new CellFragment(0, new Rect(0, 0, 8, 8),
                new Point(0, 0), imageLayout(resource))), 8, 8);
        return new SemanticPlacement(0, source, fragment, fonts, Map.of());
    }

    private static TableFragment table(String id, Point origin, List<CellFragment> cells,
            double width, double height) {
        return new TableFragment(id, 1, origin, width, height,
                List.of(new TableRowFragment(0, false, false, cells)), false);
    }

    private static LayoutPage page(int index, List<LayoutFragment> fragments) {
        return new LayoutPage(index, SIZE, CONTENT, fragments);
    }

    private static List<DrawCommand.Image> images(DisplayPage page) {
        return page.commands().stream().filter(DrawCommand.Image.class::isInstance)
                .map(DrawCommand.Image.class::cast).toList();
    }

    private static ParagraphLayout imageLayout(ImageResource resource) {
        var image = new InlineImage(resource, 6, 6, 0, "sample");
        var line = new TextLine(0, 1, 0, 6, 6, List.of(), List.of(), List.of(new ImageBox(0, image, 0)));
        return new ParagraphLayout(8, 8, List.of(line), List.of());
    }

    private static ImageResource resource() throws IOException {
        var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        try {
            image.setRGB(0, 0, 0x80ff0022);
            try (var bytes = new ByteArrayOutputStream()) {
                if (!ImageIO.write(image, "png", bytes)) {
                    throw new IOException("PNG writer unavailable");
                }
                return ImageResource.load(bytes.toByteArray());
            }
        } finally {
            image.flush();
        }
    }
}
