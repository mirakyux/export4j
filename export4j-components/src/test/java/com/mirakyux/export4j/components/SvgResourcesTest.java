// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.core.PrecisionRaster;
import com.mirakyux.export4j.layout.core.ComponentContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SvgResourcesTest {
    @Test void transformedClipUnionAndEvenOddHolesHaveVisibleGeometry() throws Exception {
        var component = SvgComponent.parse("""
                <svg viewBox='0 0 80 50'><defs>
                <clipPath id='hole' clipPathUnits='objectBoundingBox'><path clip-rule='evenodd' d='M 0 0 L 1 0 L 1 1 L 0 1 Z M .25 .25 L .75 .25 L .75 .75 L .25 .75 Z'/></clipPath>
                <g id='box'><rect width='40' height='40'/></g></defs>
                <g transform='translate(10 5)' clip-path='url(#hole)'><use href='#box'/></g></svg>
                """);
        var layout = component.layout(context()); var image = PrecisionRaster.render(layout.commands(), 80, 50, 1, true);
        assertEquals(0, image.getRGB(15, 10) & 0xffffff); assertEquals(0xffffff, image.getRGB(30, 25) & 0xffffff); assertEquals(0xffffff, image.getRGB(5, 10) & 0xffffff);
    }
    @Test void inheritedPaintsAndMarkerExpansionPreserveVectorCommands() throws Exception {
        var component = SvgComponent.parse("""
                <svg viewBox='0 0 80 50'><defs>
                <linearGradient id='base'><stop offset='0' stop-color='#ff0000'/><stop offset='1' stop-color='#0000ff'/></linearGradient>
                <linearGradient id='derived' href='#base' gradientTransform='translate(.1 0)'/>
                <marker id='arrow' markerUnits='userSpaceOnUse' markerWidth='10' markerHeight='10' viewBox='0 0 10 10' refX='10' refY='5' orient='auto'><path d='M 0 0 L 10 5 L 0 10 Z' fill='context-stroke'/></marker>
                </defs><rect x='10' y='5' width='40' height='20' fill='url(#derived)'/>
                <path d='M 10 40 L 65 40' fill='none' stroke='#228844' marker-end='url(#arrow)'/></svg>
                """);
        var layout = component.layout(context()); assertTrue(layout.commands().stream().anyMatch(DrawCommand.PaintFill.class::isInstance));
        var image = PrecisionRaster.render(layout.commands(), 80, 50, 1, true); assertTrue((image.getRGB(12, 15) >> 16 & 255) > 245); assertEquals(0x228844, image.getRGB(57, 37) & 0xffffff);
    }
    @Test void referencesCannotEscapeOrExpandExponentially() {
        for (String content : List.of("<use href='https://example.com/a'/>", "<use href='#missing'/>", "<defs><g id='a'><use href='#a'/></g></defs><use href='#a'/>", "<defs><pattern id='a' href='#b'/><pattern id='b' href='#a'/></defs><rect width='10' height='10' fill='url(#a)'/>")) { assertThrows(IllegalArgumentException.class, () -> SvgComponent.parse("<svg viewBox='0 0 80 50'>" + content + "</svg>")); }
        var source = new StringBuilder("<svg viewBox='0 0 80 50'><defs><g id='g0'><rect width='10' height='10'/></g>");
        for (int i = 1; i <= 20; i++) { source.append("<g id='g").append(i).append("'><use href='#g").append(i - 1).append("'/><use href='#g").append(i - 1).append("'/></g>"); }
        source.append("</defs><use href='#g20'/></svg>"); assertThrows(IllegalArgumentException.class, () -> SvgComponent.parse(source.toString()));
    }
    private static ComponentContext context() throws Exception { try (var input = SvgResourcesTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) { return new ComponentContext(80, 50, List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)), 0, 0); } }
}
