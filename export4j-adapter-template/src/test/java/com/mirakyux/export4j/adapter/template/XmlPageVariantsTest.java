// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.PageRegionTemplates;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.model.PageRegions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlPageVariantsTest {
    private static String xml(String content) { return "<document version='1'><page width='260' height='200' margin='10'/>" + content + "</document>"; }
    private static String bands() {
        return "<header height='20'><p>ODD</p></header><header variant='even' height='30' gap='5'><p>EVEN</p></header>";
    }
    private static DocumentEngine engine() throws Exception {
        try (var input = XmlPageVariantsTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            return DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)))).build();
        }
    }
    private static List<String> decorations(com.mirakyux.export4j.components.ComponentDocumentLayout result) {
        return result.decorations().stream().map(placed -> placed.layout().commands().stream()
                .filter(DrawCommand.GlyphText.class::isInstance).map(DrawCommand.GlyphText.class::cast)
                .map(value -> value.run().text()).collect(java.util.stream.Collectors.joining())).toList();
    }

    @Test void firstPageWithoutAnOverrideUsesDisplayedParityAndAllVariantsReserveBodySpace() throws Exception {
        var document = XmlTemplate.builder().build().compile(xml(bands()
                + "<section id='a' first-page-number='2'><p>A</p><page-break/><p>B</p><page-break/><p>C</p></section>"))
                .renderSections(Map.of());
        var result = engine().layout(document);
        assertEquals(List.of("EVEN", "ODD", "EVEN"), decorations(result));
        assertTrue(result.body().pages().stream().allMatch(page -> page.contentBounds().y() == 45));
        assertThrows(IllegalArgumentException.class, () -> PageRegionTemplates.from(document.sections().getFirst().pageRegions()));
    }

    @Test void firstOverridesParityAndExplicitBlankPersistsAcrossBindingAndSections() throws Exception {
        var source = bands() + "<section id='first' first-page-number='2'><header variant='first' height='40'><p>FIRST</p></header>"
                + "<p>A</p><page-break/><p>B</p></section>"
                + "<section id='blank' first-page-number='4'><header variant='first' height='0'/><p>C</p><page-break/><p>D</p></section>";
        var document = XmlTemplate.builder().build().compile(xml(source)).renderSections(Map.of());
        assertEquals(List.of("FIRST", "ODD", "ODD"), decorations(engine().layout(document)));
        assertEquals(PageRegions.Bands.EMPTY, document.sections().getLast().pageRegions().variants().get(PageRegions.Variant.FIRST));
    }

    @Test void duplicateDynamicMisplacedAndFragmentVariantDeclarationsAreRejected() {
        for (var source : List.of(bands() + bands(), "<header variant='odd' height='20'/>",
                "<header variant='${secret}' height='20'/>", "<p>A</p><header variant='first' height='20'/>",
                "<if test='flag'><then/><else><header variant='even' height='20'/></else></if>")) {
            assertThrows(TemplateException.class, () -> XmlTemplate.builder().build().compile(xml(source)));
        }
        assertThrows(TemplateException.class, () -> XmlTemplate.builder().fragment("v", "<fragment version='1'><header variant='even' height='20'/></fragment>")
                .build().compile(xml("<include ref='v' prefix='v'/>")));
    }
}
