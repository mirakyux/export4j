// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math.web;

import com.mirakyux.export4j.math.api.MathException;
import com.mirakyux.export4j.math.api.MathOptions;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.VectorResource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualMathContentTest {
    @Test void sourceContentProcessorUsesOccurrenceSizeDisplayAndColor() {
        var processor = VisualMathContent.processor(MathOptions.builder().fontSize(8).display(false).build());
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
        var sourceLimited = VisualMathContent.processor(MathOptions.builder().maxSourceLength(2).build());
        assertEquals(MathException.Reason.LIMIT_EXCEEDED,
                assertThrows(MathException.class, () -> sourceLimited.render("x+y", false, 12, 0, 10_000)).reason());
        var commandLimited = VisualMathContent.processor(MathOptions.builder().maxCommands(1).build());
        assertEquals(MathException.Reason.LIMIT_EXCEEDED,
                assertThrows(MathException.class,
                        () -> commandLimited.render("\\alpha+\\beta", false, 12, 0, 10_000)).reason());
        var pathsLimited = VisualMathContent.processor(MathOptions.builder().maxPaths(1).build());
        assertEquals(MathException.Reason.LIMIT_EXCEEDED,
                assertThrows(MathException.class, () -> pathsLimited.render("x+y", false, 12, 0, 10_000)).reason());
        var configuredSegments = VisualMathContent.processor(MathOptions.builder().maxSegments(1).build());
        assertEquals(MathException.Reason.LIMIT_EXCEEDED,
                assertThrows(MathException.class, () -> configuredSegments.render("x", false, 12, 0, 10_000)).reason());
        var processor = VisualMathContent.processor();
        assertEquals(MathException.Reason.LIMIT_EXCEEDED,
                assertThrows(MathException.class, () -> processor.render("x", false, 12, 0, 1)).reason());
        // An exhausted session never parses the unsupported command or allocates its paths.
        assertEquals(MathException.Reason.LIMIT_EXCEEDED,
                assertThrows(MathException.class, () -> processor.render("\\unknown", false, 12, 0, 0)).reason());
        assertTrue(processor.render("x", false, 12, 0, Integer.MAX_VALUE).resource().segmentCount() > 1);
    }

    @Test void sourceContentProcessorRejectsUnsupportedSyntaxAndInvalidOccurrenceAppearance() {
        var processor = VisualMathContent.processor();
        assertEquals(MathException.Reason.UNSUPPORTED_SYNTAX,
                assertThrows(MathException.class,
                        () -> processor.render("\\input{secret}", false, 12, 0, 10_000)).reason());
        assertThrows(IllegalArgumentException.class, () -> processor.render("x", false, 5, 0, 10_000));
        assertThrows(IllegalArgumentException.class, () -> processor.render("x", false, 12, -1, 10_000));
        assertThrows(IllegalArgumentException.class, () -> processor.render("x", false, 12, 0x1000000, 10_000));
        assertThrows(NullPointerException.class, () -> VisualMathContent.processor(null));
    }

    @Test void sourceContentProcessorPropagatesCancellationWithoutPoisoningReuse() throws Exception {
        var processor = VisualMathContent.processor();
        for (int cancelAfter : new int[] {2, 20, 120}) {
            var checks = new java.util.concurrent.atomic.AtomicInteger();
            var execution = ExecutionOptions.builder().cancelled(() -> checks.incrementAndGet() > cancelAfter).build();
            var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(execution,
                    () -> processor.render("\\frac{x_1^2+y_2^2}{\\sqrt{z}}", false, 14, 0, 10_000)));
            assertEquals(ExecutionException.Code.CANCELLED, failure.code());
            assertTrue(processor.render("x", false, 12, 0, 10_000).resource().width() > 0);
        }
    }

    private static double height(VectorResource resource) { return resource.ascent() + resource.descent(); }
}
