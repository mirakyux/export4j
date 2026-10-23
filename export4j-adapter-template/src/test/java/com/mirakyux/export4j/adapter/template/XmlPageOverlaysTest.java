// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.model.DocumentTheme;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlPageOverlaysTest {
    private static String xml(String content) { return "<document version='1'><page width='240' height='180' margin='10'/>" + content + "</document>"; }
    private static String layer(String kind, String text) { return "<" + kind + "><box x='10' y='10' width='200' height='20'><p>" + text + "</p></box></" + kind + ">"; }
    private static DocumentEngine engine() throws Exception {
        try (var input = XmlPageOverlaysTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            return DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)))).build();
        }
    }
    private static List<String> painted(com.mirakyux.export4j.graphics.DisplayPage page) {
        return page.commands().stream().filter(value -> value instanceof DrawCommand.GlyphText || value instanceof DrawCommand.OutlineText)
                .map(value -> value instanceof DrawCommand.GlyphText glyph ? glyph.run().text() : ((DrawCommand.OutlineText) value).logicalText()).toList();
    }

    @Test void finalPageFieldsUseDisplayNumbersAndPaintOrderDoesNotChangeBodyGeometry() throws Exception {
        var declarations = layer("background", "BG-${name}") + layer("foreground", "FG-<page-number width='24'/>/<page-count width='24'/>");
        var source = XmlTemplate.builder().build().compile(xml(declarations
                + "<section id='a' first-page-number='8'><p>BODY-A</p><page-break/><p>BODY-B</p></section>"))
                .renderSections(Map.of("name", "BOUND"));
        var result = engine().layout(source);
        assertEquals(2, result.display().pages().size());
        assertTrue(result.body().pages().stream().allMatch(page -> page.contentBounds().y() == 10 && page.contentBounds().height() == 160));
        for (int index = 0; index < 2; index++) {
            var text = painted(result.display().pages().get(index));
            assertEquals(List.of("BG-BOUND", index == 0 ? "BODY-A" : "BODY-B", "FG-" + (8 + index) + "/2"), text);
        }
        assertEquals(4, result.decorations().size());
    }

    @Test void rootLayersInheritReplaceAndClearByWholeLayer() throws Exception {
        var source = XmlTemplate.builder().build().compile(xml(layer("background", "ROOT") + layer("foreground", "FOREGROUND")
                + "<section id='a'><p>A</p></section><section id='b'><background/><p>B</p></section>"
                + "<section id='c'>" + layer("background", "LOCAL") + "<foreground/><p>C</p></section>" )).renderSections(Map.of());
        var sections = source.sections();
        assertEquals(1, sections.getFirst().pageRegions().background().size());
        assertEquals(0, sections.get(1).pageRegions().background().size());
        assertEquals(1, sections.get(1).pageRegions().foreground().size());
        assertEquals("LOCAL", sections.getLast().pageRegions().background().getFirst().content().paragraphs().getFirst().text());
        assertEquals(List.of("ROOT", "A", "FOREGROUND"), painted(engine().layout(source).display().pages().getFirst()));
    }

    @Test void independentOverlayThemeAndLocalValuesWinOverGlobalDefaults() {
        var theme = DocumentTheme.builder().fontRole("global").rgb(0x998877).bodyFontRole("body")
                .backgroundFontRole("brand").backgroundRgb(0x112233).build();
        var source = XmlTemplate.builder().theme(theme).build().compile(xml(layer("background", "DEFAULT<span role='' color='#000000'>LOCAL</span>") + "<p>BODY</p>"))
                .renderWithPageRegions(Map.of());
        var runs = source.pageRegions().background().getFirst().content().paragraphs().getFirst().inlines();
        assertEquals("brand", runs.getFirst().style().fontRole()); assertEquals(0x112233, runs.getFirst().style().rgb());
        assertEquals("", runs.getLast().style().fontRole()); assertEquals(0, runs.getLast().style().rgb());
    }

    @Test void duplicateDynamicOutOfBoundsAndMisplacedDeclarationsAreRejected() {
        var valid = layer("background", "A");
        for (var source : List.of(valid + valid, "<p>A</p>" + valid, valid.replace("x='10'", "x='${secret}'"),
                valid.replace("width='200'", "width='999'"), valid.replace("height='20'", "height='0'"),
                valid.replace("width='200'", "width='-1'"), "<background variant='first'/>",
                "<if test='flag'><then/><else>" + valid + "</else></if>",
                valid + "<section id='small'><page width='50' height='60' margin='1'/><p>A</p></section>")) {
            assertThrows(TemplateException.class, () -> XmlTemplate.builder().build().compile(xml(source)), source);
        }
        assertThrows(TemplateException.class, () -> XmlTemplate.builder().fragment("v", "<fragment version='1'>" + valid + "</fragment>")
                .build().compile(xml("<include ref='v' prefix='v'/>")));
    }

    @Test void overflowAndUnknownFontRolesFailBeforeAnyExport() throws Exception {
        var compiler = XmlTemplate.builder().build();
        var overflow = compiler.compile(xml(layer("background", "A").replace("height='20'", "height='1'") + "<p>B</p>" )).renderWithPageRegions(Map.of());
        assertThrows(IllegalArgumentException.class, () -> engine().layout(overflow));
        var unknown = compiler.compile(xml(layer("foreground", "<span role='missing'>A</span>") + "<p>B</p>" )).renderWithPageRegions(Map.of());
        assertThrows(IllegalArgumentException.class, () -> engine().layout(unknown));
    }
}
