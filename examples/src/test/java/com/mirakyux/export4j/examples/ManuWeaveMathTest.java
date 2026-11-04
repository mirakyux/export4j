// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageSize;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManuWeaveMathTest {
    @Test void displayMathUsesCurvedVectorOutlinesAndRetainsOriginalTex() throws Exception {
        for (String tex : List.of(
                "E = m c^2", "\\frac{-b \\pm \\sqrt{b^2-4ac}}{2a}",
                "\\int_{0}^{1} x^2 dx = \\frac{1}{3}",
                "\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}",
                "\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}",
                "f(x)=\\begin{cases}x^2,&\\quad x\\geq 0\\\\-x,&\\quad x<0\\end{cases}",
                "\\alpha + \\beta = \\gamma", "\\left( x + y \\right)",
                "\\prod_{i=1}^{n} x_i", "\\iint_{D}x dx", "\\oint_{C}x dx",
                "\\lim_{n \\to \\infty}x_n", "\\sin x + \\cos x + \\tan x + \\log x + \\ln x + \\exp x",
                "\\Gamma + \\Delta + \\Theta + \\Lambda + \\Pi + \\Sigma + \\Phi + \\Omega",
                "\\delta + \\epsilon + \\theta + \\lambda + \\mu + \\pi + \\rho + \\sigma + \\tau + \\phi + \\omega")) {
            var formula = ManuWeaveMath.prepare(tex, 14);
            var layout = formula.layout(context(600, 300));
            assertEquals(1, layout.textLines());
            assertEquals(tex, formula.tex());
            assertTrue(formula.width() > 0 && formula.height() > 0);
            assertTrue(formula.baseline() > 0 && formula.baseline() < formula.height());
            assertFalse(formula.paths().isEmpty());
            assertTrue(formula.paths().stream().flatMap(path -> path.segments().stream())
                    .anyMatch(VectorPath.CurveTo.class::isInstance));
            assertTrue(layout.commands().stream().anyMatch(command -> command instanceof DrawCommand.OutlineText text
                    && text.logicalText().equals(tex) && !text.paths().isEmpty()));
            assertFalse(layout.commands().stream().anyMatch(DrawCommand.Image.class::isInstance));
            try (var output = new ByteArrayOutputStream()) {
                new SvgBackend().writePage(new DisplayPage(new PageSize(layout.width(), layout.height()), layout.commands()), output);
                String svg = output.toString(StandardCharsets.UTF_8);
                assertTrue(svg.contains("<path "));
                assertTrue(svg.contains("aria-label="));
                assertFalse(svg.contains("<image"));
            }
        }
    }

    @Test void bracedFractionsAndMatrixGeometryHaveRealVerticalExtent() {
        var plain = ManuWeaveMath.prepare("x", 16);
        assertTrue(ManuWeaveMath.prepare("\\frac{x}{y}", 16).height() > plain.height() * 1.4);
        assertTrue(ManuWeaveMath.prepare("\\begin{bmatrix}a&b\\\\c&d\\end{bmatrix}", 16).height() > plain.height() * 1.4);
        assertTrue(ManuWeaveMath.prepare("x_1^2", 16).height() > plain.height());
    }

    @Test void rejectsResourceAccessMacrosExtensionsAndNonSubsetTextBeforeParsing() {
        for (String tex : List.of(
                "\\includegraphics{https://example.invalid/image.png}", "\\includegraphics{local.png}",
                "\\newcommand{\\x}{x}", "\\renewcommand{\\frac}{x}", "\\def\\x{\\x}",
                "\\input{secret}", "\\href{https://example.invalid}{x}", "\\text{system font}",
                "\\color{red}x", "\\begin{unknown}x\\end{unknown}",
                "\\begin{pmatrix}x\\end{bmatrix}", "x%comment", "x$y", "中文", "x\n+y",
                "\\frac{x}\\frac{y}{z}", "\\sqrt[3]{x}", "x^\\frac{x}{y}", "x^^2", "x&y", "\\\\")) {
            assertThrows(IllegalArgumentException.class, () -> ManuWeaveMath.equation(tex, 12));
        }
    }

    @Test void sourceDepthCommandsCellsAndDimensionsAreBounded() {
        for (String tex : List.of("x".repeat(2049), "{".repeat(17) + "x" + "}".repeat(17),
                "\\quad ".repeat(257), "\\begin{matrix}" + "x&".repeat(64) + "x\\end{matrix}")) {
            assertThrows(IllegalArgumentException.class, () -> ManuWeaveMath.equation(tex, 12));
        }
        assertThrows(IllegalArgumentException.class, () -> ManuWeaveMath.equation("W".repeat(100), 72));
        for (double size : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 0, 5.9, 72.1}) {
            assertThrows(IllegalArgumentException.class, () -> ManuWeaveMath.equation("x", size));
        }
    }

    @Test void geometryBudgetsRejectBeforeProducingAComponent() {
        var paths = assertThrows(IllegalArgumentException.class,
                () -> ManuWeaveMath.prepare("x+y", 14, new ManuWeaveMath.GeometryBudget(1, 40_000)));
        assertEquals("formula geometry budget exceeded", paths.getMessage());
        var segments = assertThrows(IllegalArgumentException.class,
                () -> ManuWeaveMath.prepare("x", 14, new ManuWeaveMath.GeometryBudget(2048, 1)));
        assertEquals("formula geometry budget exceeded", segments.getMessage());
    }

    @Test void preparedFormulaIsReusableAndNeverScalesToFit() throws Exception {
        var formula = ManuWeaveMath.prepare("\\sqrt{x^2+y^2}", 14);
        var first = formula.layout(context(500, 300));
        assertEquals(first, formula.layout(context(600, 400)));
        assertThrows(UnsupportedOperationException.class, () -> formula.paths().clear());
        assertThrows(IllegalArgumentException.class, () -> formula.layout(context(formula.width() / 2, 300)));
        assertThrows(IllegalArgumentException.class, () -> formula.layout(context(500, formula.height() / 2)));
    }

    @Test void observesScopedCancellationBeforeParsing() {
        assertThrows(ExecutionException.class, () -> ExecutionContext.execute(
                ExecutionOptions.builder().cancelled(() -> true).build(),
                () -> ManuWeaveMath.equation("x", 12)));
    }

    private static ComponentContext context(double width, double height) throws Exception {
        return new ComponentContext(width, height, List.of(AuthoringExample.face("LiberationSans-Regular.ttf")), 0, 0);
    }
}
