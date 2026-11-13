// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.VectorResource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.scilab.forge.jlatexmath.TeXFormula;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TexMathTest {
    @Test void formulasHaveCurvedOutlinesAndARealBaseline() {
        for (String tex : List.of("E=mc^2", "\\frac{-b\\pm\\sqrt{b^2-4ac}}{2a}",
                "\\sqrt[3]{x}", "\\sum_{i=1}^{n}i", "\\int_0^1 x^2dx",
                "\\alpha+\\beta=\\gamma", "\\left(x+y\\right)",
                "\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}",
                "f(x)=\\begin{cases}x^2&x\\geq0\\\\-x&x<0\\end{cases}")) {
            var vector = TexMath.vector(tex, MathOptions.builder().fontSize(14).display(true).build());
            assertEquals(tex, vector.logicalText());
            var resource = vector.resource();
            assertTrue(resource.width() > 0);
            assertTrue(resource.ascent() > 0);
            assertTrue(resource.descent() >= 0);
            assertTrue(resource.segments().stream().anyMatch(segment -> segment.operation() == VectorResource.Operation.CURVE));
            for (var segment : resource.segments()) {
                for (int index = 0; index < segment.coordinates().size(); index += 2) {
                    assertTrue(segment.coordinates().get(index) >= 0 && segment.coordinates().get(index) <= resource.width());
                    assertTrue(segment.coordinates().get(index + 1) >= -resource.ascent()
                            && segment.coordinates().get(index + 1) <= resource.descent());
                }
            }
            assertThrows(UnsupportedOperationException.class, () -> resource.segments().clear());
        }
    }

    @Test void fractionsAndMatricesKeepVerticalExtentAndDisplayStyle() {
        var options = MathOptions.builder().fontSize(16).display(true).build();
        double plain = height(TexMath.vector("x", options).resource());
        assertTrue(height(TexMath.vector("\\frac{x}{y}", options).resource()) > plain * 1.4);
        assertTrue(height(TexMath.vector("\\begin{bmatrix}a&b\\\\c&d\\end{bmatrix}", options).resource()) > plain * 1.4);
        assertTrue(height(TexMath.vector("x_1^2", options).resource()) > plain);
        assertTrue(height(TexMath.vector("\\sum_{i=1}^{n}i", options).resource())
                > height(TexMath.vector("\\sum_{i=1}^{n}i", options.withDisplay(false)).resource()));
    }

    @Test void inlineReadingTextNormalizesOnlyAsciiWhitespace() {
        assertEquals("x + y", TexMath.vector("x\n+\ty").logicalText());
        assertEquals("x  +y", TexMath.vector("x\r\n+y").logicalText());
    }

    @Test void sourceContentProcessorUsesOccurrenceSizeDisplayAndColor() {
        var processor = TexMath.processor(MathOptions.builder().fontSize(8).display(false).build());
        var small = processor.render("\\sum_{i=1}^{n}i", false, 12, 0x123456, 10_000);
        var large = processor.render("\\sum_{i=1}^{n}i", false, 24, 0xabcdef, 10_000);
        var display = processor.render("\\sum_{i=1}^{n}i", true, 12, 0, 10_000);
        assertEquals("\\sum_{i=1}^{n}i", small.logicalText());
        assertEquals(0x123456, small.rgb());
        assertEquals(0xabcdef, large.rgb());
        assertEquals("", small.hyperlink());
        assertTrue(large.resource().width() > small.resource().width() * 1.5);
        assertTrue(height(display.resource()) > height(small.resource()));
        assertTrue(small.resource().segments().stream()
                .anyMatch(segment -> segment.operation() == VectorResource.Operation.CURVE));
    }

    @Test void sourceContentProcessorRetainsConfiguredLimitsAndTightensSegmentBudget() {
        var sourceLimited = TexMath.processor(MathOptions.builder().maxSourceLength(2).build());
        assertEquals(MathException.Reason.LIMIT_EXCEEDED,
                assertThrows(MathException.class, () -> sourceLimited.render("x+y", false, 12, 0, 10_000)).reason());
        var commandLimited = TexMath.processor(MathOptions.builder().maxCommands(1).build());
        assertEquals(MathException.Reason.LIMIT_EXCEEDED,
                assertThrows(MathException.class,
                        () -> commandLimited.render("\\alpha+\\beta", false, 12, 0, 10_000)).reason());
        var pathsLimited = TexMath.processor(MathOptions.builder().maxPaths(1).build());
        assertEquals(MathException.Reason.LIMIT_EXCEEDED,
                assertThrows(MathException.class, () -> pathsLimited.render("x+y", false, 12, 0, 10_000)).reason());
        var configuredSegments = TexMath.processor(MathOptions.builder().maxSegments(1).build());
        assertEquals(MathException.Reason.LIMIT_EXCEEDED,
                assertThrows(MathException.class, () -> configuredSegments.render("x", false, 12, 0, 10_000)).reason());
        var processor = TexMath.processor();
        assertEquals(MathException.Reason.LIMIT_EXCEEDED,
                assertThrows(MathException.class, () -> processor.render("x", false, 12, 0, 1)).reason());
        // An exhausted session never parses the unsupported command or allocates its paths.
        assertEquals(MathException.Reason.LIMIT_EXCEEDED,
                assertThrows(MathException.class, () -> processor.render("\\unknown", false, 12, 0, 0)).reason());
        assertTrue(processor.render("x", false, 12, 0, Integer.MAX_VALUE).resource().segmentCount() > 1);
    }

    @Test void sourceContentProcessorRejectsUnsupportedSyntaxAndInvalidOccurrenceAppearance() {
        var processor = TexMath.processor();
        assertEquals(MathException.Reason.UNSUPPORTED_SYNTAX,
                assertThrows(MathException.class,
                        () -> processor.render("\\input{secret}", false, 12, 0, 10_000)).reason());
        assertThrows(IllegalArgumentException.class, () -> processor.render("x", false, 5, 0, 10_000));
        assertThrows(IllegalArgumentException.class, () -> processor.render("x", false, 12, -1, 10_000));
        assertThrows(IllegalArgumentException.class, () -> processor.render("x", false, 12, 0x1000000, 10_000));
        assertThrows(NullPointerException.class, () -> TexMath.processor(null));
    }

    @Test void sourceContentProcessorPropagatesCancellationWithoutPoisoningReuse() throws Exception {
        var processor = TexMath.processor();
        for (int cancelAfter : new int[] {2, 20, 120}) {
            var checks = new java.util.concurrent.atomic.AtomicInteger();
            var execution = ExecutionOptions.builder().cancelled(() -> checks.incrementAndGet() > cancelAfter).build();
            var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(execution,
                    () -> processor.render("\\frac{x_1^2+y_2^2}{\\sqrt{z}}", false, 14, 0, 10_000)));
            assertEquals(ExecutionException.Code.CANCELLED, failure.code());
            assertTrue(processor.render("x", false, 12, 0, 10_000).resource().width() > 0);
        }
    }

    @Test void supportedAsciiTextAndStylesUseExplicitBundledFonts() {
        for (String tex : List.of("\\text{mean value}=x", "\\textrm{rate}=r",
                "\\mathrm{abc}+\\mathit{x}")) {
            var vector = TexMath.vector(tex);
            assertEquals(tex, vector.logicalText());
            assertTrue(vector.resource().segmentCount() > 0);
        }
    }

    @Test void unsupportedCommandsNeverReachMacroExpansionOrResourceLoading() {
        for (String tex : List.of("\\includegraphics{https://example.invalid/a}",
                "\\input{local-file}", "\\newcommand{\\a}{x}", "\\def\\a{\\a}",
                "\\href{https://example.invalid}{x}", "\\color{red}x", "\\fontsize{9999}{9999}x",
                "\\begin{unknown}x\\end{unknown}", "中文", "x%comment", "x$y")) {
            var failure = assertThrows(MathException.class, () -> TexMath.vector(tex));
            assertEquals(MathException.Reason.UNSUPPORTED_SYNTAX, failure.reason());
            assertFalse(failure.getMessage().contains(tex));
        }
    }

    @Test void malformedAndUnbracedRecursiveConstructsAreRejectedBeforeParsing() {
        for (String tex : List.of("\\frac{x}\\frac{y}{z}", "\\sqrt\\sqrt{x}",
                "x^\\frac{x}{y}", "x^^2", "\\left(x", "\\begin{matrix}x&y\\\\z\\end{matrix}",
                "\\begin{pmatrix}x\\end{bmatrix}", "\\frac".repeat(100) + "x")) {
            assertThrows(MathException.class, () -> TexMath.vector(tex));
        }
    }

    @Test void sourceCommandsDepthCellsAndActualGeometryAreLimited() {
        assertLimit("x".repeat(1025), MathOptions.DEFAULT);
        assertLimit("{".repeat(17) + "x" + "}".repeat(17), MathOptions.DEFAULT);
        assertLimit("\\quad ".repeat(3) + "x", MathOptions.builder().maxCommands(2).build());
        assertLimit("\\begin{matrix}" + "x&".repeat(64) + "x\\end{matrix}", MathOptions.DEFAULT);
        assertLimit("x+y", MathOptions.builder().maxPaths(1).build());
        assertLimit("x", MathOptions.builder().maxSegments(1).build());
        assertLimit("WWWW", MathOptions.builder().maxExtent(8).build());
        assertLimit("\\unknown " + "\\alpha ".repeat(3), MathOptions.builder().maxCommands(2).build());
        assertLimit("\\unknown " + "{".repeat(17) + "x" + "}".repeat(17), MathOptions.DEFAULT);
    }

    @Test void malformedPrefixesCannotHideLaterBudgetsOrEnableFallback() {
        var commands = MathOptions.builder().maxCommands(1).build();
        for (String prefix : List.of("}", "\u0000", "\ud800", "\uffff")) {
            assertLimit(prefix + "\\alpha\\beta", commands);
            assertEquals(MathException.Reason.INVALID_SYNTAX,
                    assertThrows(MathException.class, () -> TexMath.vector(prefix + "x")).reason());
        }
        assertLimit("}}}" + "{{{{x}}}}", MathOptions.builder().maxDepth(3).build());
        var permissive = MarkdownMath.builder().options(commands).fallback(MarkdownMath.Fallback.KEEP_SOURCE).build();
        assertEquals(MathException.Reason.LIMIT_EXCEEDED,
                assertThrows(MathException.class, () -> permissive.vector("$}\\alpha\\beta$")).reason());
    }

    @Test void concurrentCallsProduceIdenticalImmutableGeometry() throws Exception {
        var options = MathOptions.builder().fontSize(14).display(true).build();
        var expected = TexMath.vector("\\frac{x_1^2+y_2^2}{\\sqrt{z}}", options);
        var tasks = new ArrayList<Callable<com.mirakyux.export4j.model.InlineVector>>();
        for (int index = 0; index < 12; index++) {
            tasks.add(() -> TexMath.vector("\\frac{x_1^2+y_2^2}{\\sqrt{z}}", options));
        }
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (var future : executor.invokeAll(tasks, 30, TimeUnit.SECONDS)) {
                assertFalse(future.isCancelled());
                assertEquals(expected, future.get(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test void dependencyDpiOverrideCannotSilentlyChangePointUnits() {
        float original = TeXFormula.PIXELS_PER_POINT;
        try {
            TeXFormula.PIXELS_PER_POINT = 2;
            assertEquals(MathException.Reason.RENDERING_FAILED,
                    assertThrows(MathException.class, () -> TexMath.vector("x")).reason());
        } finally {
            TeXFormula.PIXELS_PER_POINT = original;
        }
        assertTrue(TexMath.vector("x").resource().width() > 0);
    }

    @Test void cancellationDuringGrammarOrDrawingPropagatesAndRestoresScope() throws Exception {
        for (int cancelAfter : new int[] {2, 20, 120}) {
            var checks = new java.util.concurrent.atomic.AtomicInteger();
            var options = ExecutionOptions.builder().cancelled(() -> checks.incrementAndGet() > cancelAfter).build();
            var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options,
                    () -> TexMath.vector("\\frac{x_1^2+y_2^2}{\\sqrt{z}}")));
            assertEquals(ExecutionException.Code.CANCELLED, failure.code());
            assertTrue(TexMath.vector("x").resource().width() > 0);
        }
    }

    private static double height(VectorResource resource) { return resource.ascent() + resource.descent(); }
    private static void assertLimit(String tex, MathOptions options) {
        assertEquals(MathException.Reason.LIMIT_EXCEEDED,
                assertThrows(MathException.class, () -> TexMath.vector(tex, options)).reason());
    }
}
