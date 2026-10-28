// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.layout.core.ComponentContext;

class SvgPaintStateTest {
    @Test void paintOpacityIsInheritedAndReplacedWithoutMultiplyingAncestors() throws Exception {
        var drawing = SvgComponent.parse("""
                <svg viewBox='0 0 100 80' fill-opacity='0.5' stroke-opacity='25%'>
                  <g fill='#ff0000' transform='translate(10 20) scale(2)'>
                    <rect width='10' height='10'/>
                    <g fill-opacity='0.8'><rect x='10' width='10' height='10' stroke-opacity='0.9'/></g>
                    <rect x='20' width='10' height='10'/>
                  </g>
                </svg>
                """).layout(context());
        assertEquals(List.of(new DrawCommand.Opacity(0.5, 0.25), new DrawCommand.Opacity(0.8, 0.9),
                new DrawCommand.Opacity(0.5, 0.25)), drawing.commands().stream().filter(DrawCommand.Opacity.class::isInstance).toList());
        var matrices = drawing.commands().stream().filter(DrawCommand.Transform.class::isInstance)
                .map(DrawCommand.Transform.class::cast).map(DrawCommand.Transform::matrix).toList();
        assertTrue(matrices.contains(AffineTransform.translation(10, 20)));
        assertTrue(matrices.contains(new AffineTransform(2, 0, 0, 2, 0, 0)));
    }

    @Test void completeAffineTransformListIsRecordedInSourceOrder() throws Exception {
        var drawing = SvgComponent.parse("<svg viewBox='0 0 100 80'><rect transform='matrix(1 0 0 1 2 3) rotate(90 5 6) skewX(45) skewY(-45)' width='10' height='10'/></svg>")
                .layout(context());
        var matrices = drawing.commands().stream().filter(DrawCommand.Transform.class::isInstance)
                .map(DrawCommand.Transform.class::cast).map(DrawCommand.Transform::matrix).toList();
        assertTrue(matrices.contains(AffineTransform.translation(5, 6)));
        assertTrue(matrices.contains(AffineTransform.translation(-5, -6)));
        assertTrue(matrices.stream().anyMatch(matrix -> Math.abs(matrix.c() + 1) < 1e-12 && Math.abs(matrix.b() - 1) < 1e-12));
    }

    @Test void invalidOpacityTransformsAndUnimplementedCompositingAreRejected() {
        for (String attribute : List.of("fill-opacity='NaN'", "stroke-opacity='1.1'", "fill-opacity='-1%'", "opacity='0.5'",
                "transform='translate(1) garbage'", "transform='matrix(1 2)'", "transform='rotate(1 2)'",
                "transform='translate(1e999)'", "transform='url(x)'", "transform='" + "translate(1) ".repeat(65) + "'")) {
            assertThrows(IllegalArgumentException.class, () -> SvgComponent.parse("<svg viewBox='0 0 100 80'><rect " + attribute + " width='10' height='10'/></svg>"), attribute);
        }
        assertThrows(IllegalArgumentException.class, () -> SvgComponent.parse("<svg viewBox='0 0 10 10'><g style='fill-opacity:.5'/></svg>"));
    }

    private static ComponentContext context() throws Exception {
        try (var input = SvgPaintStateTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            var font = TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
            return new ComponentContext(100, 80, List.of(font), 0, 0);
        }
    }
}
