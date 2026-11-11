// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.BlendMode;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.FontFamily;
import com.mirakyux.export4j.graphics.Paint;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.TextStyle;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.layout.core.PageBand;
import com.mirakyux.export4j.layout.core.PageComposer;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.text.ParagraphLayouter;

class ComponentDrawingBudgetsTest {
    private static final Rect BOX = new Rect(0, 0, 10, 10);
    private static FontGroup fonts;

    @BeforeAll static void loadFont() throws IOException {
        try (var input = ComponentDrawingBudgetsTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            if (input == null) { throw new IOException("missing test font"); }
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    @Test void bodyCountsNestedGroupAndTileCommandsAtEveryUse() {
        var nested = nested("A");
        var source = new Document(DocumentMetadata.EMPTY, PageSettings.A4,
                List.of(new ComponentBlock("nested", component(List.of(nested, nested)))));
        assertEquals(1, layouter(2, 6).layout(source, fonts).pages().size());
        var failure = assertThrows(DocumentLayoutException.class, () -> layouter(2, 5).layout(source, fonts));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals("nested", failure.blockId());
    }

    @Test void nestedTextChargesUnicodeScalarsToTheBodyBudget() {
        var source = new Document(DocumentMetadata.EMPTY, PageSettings.A4,
                List.of(new ComponentBlock("text", component(List.of(nested("A\uD83D\uDE00B"))))));
        assertEquals(1, layouter(3, 3).layout(source, fonts).pages().size());
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentLayoutException.class, () -> layouter(2, 3).layout(source, fonts)).code());
    }

    @Test void pageCompositionCountsNestedDecorationCommandsIncludingRepeatedResources() {
        var group = new DrawCommand.Group(BOX, List.of(text("A")), 1, BlendMode.NORMAL);
        var template = PageTemplate.builder().header(PageBand.builder().height(12)
                .content(component(List.of(group, group))).build()).build();
        var body = new DisplayDocument(DocumentMetadata.EMPTY,
                List.of(new DisplayPage(PageSettings.A4.size(), List.of())));
        assertEquals(1, new PageComposer(new ComponentLimits(10, 7, 10))
                .compose(body, PageSettings.A4, template, fonts.fonts()).pages().size());
        assertThrows(IllegalArgumentException.class, () -> new PageComposer(new ComponentLimits(10, 6, 10))
                .compose(body, PageSettings.A4, template, fonts.fonts()));
    }

    @Test void finalBudgetIncludesPlainAndNestedTextGeneratedInPageRegions() {
        var source = new Document(DocumentMetadata.EMPTY, PageSettings.A4,
                List.of(new Paragraph("body", "A", ParagraphFormat.of(10))));
        for (var commands : List.of(List.<DrawCommand>of(text("B\uD83D\uDE00C")),
                List.<DrawCommand>of(nested("B\uD83D\uDE00C")))) {
            var template = PageTemplate.builder().header(PageBand.builder().height(12)
                    .content(component(commands)).build()).build();
            assertEquals(1, renderer(4).layout(source, template, Navigation.EMPTY, fonts).display().pages().size());
            assertThrows(IllegalArgumentException.class, () -> renderer(3).layout(source, template, Navigation.EMPTY, fonts));
        }
    }

    private static DrawCommand nested(String value) {
        var tile = new Paint.Tile(BOX, 10, 10, AffineTransform.IDENTITY, List.of(text(value)));
        var path = new VectorPath(List.of(new VectorPath.MoveTo(new Point(0, 0)), new VectorPath.LineTo(new Point(10, 10))));
        return new DrawCommand.Group(BOX, List.of(new DrawCommand.PaintFill(path, tile, DrawCommand.FillRule.NONZERO)), 1, BlendMode.NORMAL);
    }
    private static DrawCommand.Text text(String value) {
        return new DrawCommand.Text(value, new Point(0, 10), new TextStyle(FontFamily.SANS_SERIF, 10, RgbColor.BLACK));
    }
    private static Component component(List<DrawCommand> commands) {
        return context -> new ComponentLayout(10, 10, 0, commands, List.of());
    }
    private static DocumentLayouter layouter(long points, int commands) {
        return new DocumentLayouter(new ParagraphLayouter(), new DocumentLayoutLimits(10, points, 10, 10),
                new ComponentLimits(10, commands, 10));
    }
    private static ComponentDocumentRenderer renderer(long points) {
        return new ComponentDocumentRenderer(new DocumentLayoutLimits(10, points, 10, 10), new ComponentLimits(10, 100, 10));
    }
}
