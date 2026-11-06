// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;

class PageComposerSinglePageTest {
    private static final PageSettings PAGE = new PageSettings(new PageSize(120, 100), PageMargins.uniform(5));
    private static final Position FILL = new Position.Fractional(0, 0, 1, 1);
    private static FontGroup fonts;

    @BeforeAll
    static void loadFont() throws IOException {
        try (var input = PageComposerSinglePageTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            if (input == null) { throw new IOException("missing test font"); }
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    @Test
    void singlePagesMatchWholeCompositionWithFinalPhysicalAndDisplayedNumbers() {
        var contexts = new ArrayList<String>();
        Component header = context -> {
            assertEquals(3, context.pageCount());
            assertSame(fonts, context.fontRoles().get("title"));
            contexts.add(context.pageIndex() + ":" + context.displayPageNumber());
            return marked(context, 0.3);
        };
        var template = new PageTemplate(new PageBand(10, 2, List.of(new Placement(header, FILL))),
                new PageBand(10, 2, List.of(new Placement(context -> marked(context, 0.4), FILL))),
                List.of(new Placement(context -> marked(context, 0.1), FILL),
                        new Placement(context -> marked(context, 0.2), FILL, 2)),
                List.of(new Placement(context -> marked(context, 0.5), FILL)));
        var sources = List.of(source(), source(), source());
        var specs = List.of(new PageComposer.PageSpec(PAGE, template, 1),
                new PageComposer.PageSpec(PAGE, template, 9), new PageComposer.PageSpec(PAGE, template, 10));
        var composer = new PageComposer();
        var roles = Map.of("title", fonts);
        var whole = composer.composeDetailed(new DisplayDocument(DocumentMetadata.EMPTY, sources), specs, fonts, roles);
        assertEquals(List.of("0:1", "1:9", "2:10"), contexts);
        contexts.clear();
        var traces = new ArrayList<PageComposition.Placed>();
        for (int index = 0; index < sources.size(); index++) {
            var single = composer.composePageDetailed(sources.get(index), specs.get(index), fonts, roles, index, 3);
            assertEquals(whole.display().pages().get(index), single.page());
            assertEquals(5, single.declaredPlacements());
            traces.addAll(single.placements());
            assertThrows(UnsupportedOperationException.class, () -> single.placements().clear());
        }
        assertEquals(whole.placements(), traces);
        assertEquals(List.of("0:1", "1:9", "2:10"), contexts);
        assertEquals(List.of(PageComposition.Region.BACKGROUND, PageComposition.Region.HEADER,
                PageComposition.Region.FOOTER, PageComposition.Region.FOREGROUND),
                traces.stream().filter(trace -> trace.pageIndex() == 0).map(PageComposition.Placed::region).toList());
    }

    @Test
    void invalidPhysicalIndexAndPageSizeFailBeforeInvokingDecorations() {
        var invocations = new AtomicInteger();
        var template = new PageTemplate(PageBand.EMPTY, PageBand.EMPTY, List.of(new Placement(context -> {
            invocations.incrementAndGet();
            return marked(context, 0.1);
        }, FILL)), List.of());
        var spec = new PageComposer.PageSpec(PAGE, template, 7);
        var composer = new PageComposer();
        assertThrows(IllegalArgumentException.class, () -> composer.composePageDetailed(source(), spec, fonts, Map.of(), -1, 3));
        assertThrows(IllegalArgumentException.class, () -> composer.composePageDetailed(source(), spec, fonts, Map.of(), 3, 3));
        assertThrows(IllegalArgumentException.class, () -> composer.composePageDetailed(source(), spec, fonts, Map.of(), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> composer.composePageDetailed(
                new DisplayPage(new PageSize(121, 100), List.of()), spec, fonts, Map.of(), 0, 3));
        assertEquals(0, invocations.get());
    }

    @Test
    void singlePageStillRejectsOutOfRangeSelectorsAndLocalPlacementAndCommandBudgets() {
        Component component = context -> marked(context, 0.1);
        var outside = new PageTemplate(PageBand.EMPTY, PageBand.EMPTY,
                List.of(new Placement(component, FILL, 4)), List.of());
        assertThrows(IllegalArgumentException.class, () -> new PageComposer().composePageDetailed(source(),
                new PageComposer.PageSpec(PAGE, outside, 1), fonts, Map.of(), 0, 3));

        var repeated = new PageTemplate(PageBand.EMPTY, PageBand.EMPTY,
                List.of(new Placement(component, FILL), new Placement(component, FILL)), List.of());
        assertThrows(IllegalArgumentException.class, () -> new PageComposer(new ComponentLimits(1, 100, 10))
                .composePageDetailed(source(), new PageComposer.PageSpec(PAGE, repeated, 1), fonts, Map.of(), 0, 1));

        var decorated = new PageTemplate(PageBand.EMPTY, PageBand.EMPTY,
                List.of(new Placement(component, FILL)), List.of());
        assertThrows(IllegalArgumentException.class, () -> new PageComposer(new ComponentLimits(10, 4, 10))
                .composePageDetailed(source(), new PageComposer.PageSpec(PAGE, decorated, 1), fonts, Map.of(), 0, 1));
    }

    private static ComponentLayout marked(ComponentContext context, double shade) {
        return new ComponentLayout(context.width(), context.height(), 0,
                List.of(new DrawCommand.FillRect(new Rect(0, 0, 1, 1), new RgbColor(shade, shade, shade))), List.of());
    }

    private static DisplayPage source() {
        return new DisplayPage(PAGE.size(), List.of(new DrawCommand.FillRect(new Rect(5, 20, 1, 1), RgbColor.BLACK)));
    }
}
