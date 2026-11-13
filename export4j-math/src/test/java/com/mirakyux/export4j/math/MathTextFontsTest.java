// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.InlineVector;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MathTextFontsTest {
    @Test void literatureUnitsAndBarAreTrueExplicitFontOutlines() throws IOException {
        var fonts = cjkFonts();
        for (String tex : List.of("(\\bar{x} \\pm s) / \\text{岁}",
                "(\\bar{x} \\pm s) / \\text{年}", "\\overline{xy} / \\textrm{年龄}")) {
            var vector = TexMath.vector(tex, MathOptions.DEFAULT, fonts);
            assertEquals(tex, vector.logicalText());
            assertTrue(vector.resource().segmentCount() > 20);
            assertTrue(vector.resource().ascent() > 0);
            assertTrue(vector.resource().width() > 0);
            assertEquals(vector, TexMath.vector(tex, MathOptions.DEFAULT, fonts));
        }
        var year = TexMath.vector("\\text{年}", MathOptions.DEFAULT, fonts);
        var age = TexMath.vector("\\text{岁}", MathOptions.DEFAULT, fonts);
        assertNotEquals(year.resource().segments(), age.resource().segments());
    }

    @Test void explicitTextIsComposedInsideNestedMathStructures() throws IOException {
        var fonts = cjkFonts();
        for (String tex : List.of("\\frac{\\text{年龄}}{\\text{年}}",
                "\\dfrac{\\text{岁}}{x}", "\\tfrac{x}{\\text{年}}",
                "\\sqrt[\\text{年}]{\\text{年龄}}", "x_{\\text{年}}^{\\text{岁}}",
                "\\left[\\frac{\\text{岁}}{x}\\right]", "\\overline{\\text{年}}",
                "\\bar{\\text{年}}", "\\mathrm{\\text{年}}+\\mathit{\\text{岁}}",
                "\\sum_{\\text{年}}^{n}x", "\\begin{pmatrix}\\text{年}&x\\\\y&\\text{岁}\\end{pmatrix}",
                "\\begin{bmatrix}\\text{年}\\end{bmatrix}",
                "\\begin{cases}\\text{年龄}&x>0\\\\\\text{年}&x<0\\end{cases}")) {
            var resource = TexMath.vector(tex, MathOptions.DEFAULT, fonts).resource();
            assertTrue(resource.segmentCount() > 0, tex);
            for (var segment : resource.segments()) {
                for (int index = 0; index < segment.coordinates().size(); index += 2) {
                    assertTrue(segment.coordinates().get(index) >= 0
                            && segment.coordinates().get(index) <= resource.width(), tex);
                    assertTrue(segment.coordinates().get(index + 1) >= -resource.ascent()
                            && segment.coordinates().get(index + 1) <= resource.descent(), tex);
                }
            }
        }
    }

    @Test void fontConfigurationSnapshotsBytesAndUsesOnlyExplicitFallbacks() throws IOException {
        byte[] latin = bytes("LiberationSans-Regular.ttf");
        var configured = MathTextFonts.of(latin);
        Arrays.fill(latin, (byte) 0);
        var expected = TexMath.vector("\\text{mean value}", MathOptions.DEFAULT, configured);
        assertTrue(expected.resource().segmentCount() > 0);
        assertNotEquals(expected.resource(), TexMath.vector("\\text{mean value}").resource());
        var withoutExtra = TexMath.vector("\\text{age}", MathOptions.DEFAULT, configured);
        var withExtra = TexMath.vector("\\text{age}", MathOptions.DEFAULT,
                MathTextFonts.of(bytes("LiberationSans-Regular.ttf"), bytes("ZCOOLXiaoWei-Regular.ttf")));
        assertEquals(withoutExtra, withExtra);
        assertEquals(MathException.Reason.UNSUPPORTED_SYNTAX,
                assertThrows(MathException.class,
                        () -> TexMath.vector("\\text{年}", MathOptions.DEFAULT, configured)).reason());
        assertEquals(MathException.Reason.UNSUPPORTED_SYNTAX,
                assertThrows(MathException.class, () -> TexMath.vector("\\text{年}")).reason());
        assertEquals(TexMath.vector("x+y"), TexMath.vector("x+y", MathOptions.DEFAULT, configured));
    }

    @Test void textConfigurationDoesNotExpandTheMathGrammarOrHideLimits() throws IOException {
        var fonts = cjkFonts();
        for (String tex : List.of("年+x", "\\text{\\input{secret}}", "\\text{\u202E年}",
                "\\text{\ud800}", "\\text{a\u0301}", "\\text{عمر}")) {
            assertThrows(MathException.class, () -> TexMath.vector(tex, MathOptions.DEFAULT, fonts));
        }
        assertEquals(MathException.Reason.LIMIT_EXCEEDED, assertThrows(MathException.class,
                () -> TexMath.vector("\\text{年}", MathOptions.builder().maxSegments(1).build(), fonts)).reason());
        assertEquals(MathException.Reason.LIMIT_EXCEEDED, assertThrows(MathException.class,
                () -> TexMath.vector("\\text{age}", MathOptions.builder().maxSourceLength(2).build(), fonts)).reason());
        assertEquals(MathException.Reason.RENDERING_FAILED, assertThrows(MathException.class,
                () -> MathTextFonts.of(new byte[] {1})).reason());
        assertTrue(MathTextFonts.EMPTY.isEmpty());
        assertTrue(MathTextFonts.of().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> MathTextFonts.of(new byte[0]));
        byte[][] tooMany = new byte[9][];
        Arrays.fill(tooMany, new byte[] {1});
        assertThrows(IllegalArgumentException.class, () -> MathTextFonts.of(tooMany));
        assertThrows(NullPointerException.class, () -> TexMath.vector("x", MathOptions.DEFAULT, null));
    }

    @Test void fontOutlinePermissionsCannotBeBypassedByJava2d() throws IOException {
        for (int flags : new int[] {0x0002, 0x0100, 0x0200}) {
            byte[] bytes = bytes("LiberationSans-Regular.ttf");
            var table = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN);
            int count = Short.toUnsignedInt(table.getShort(4));
            boolean found = false;
            for (int index = 0; index < count; index++) {
                int record = 12 + 16 * index;
                if (table.getInt(record) == 0x4f532f32) {
                    table.putShort(table.getInt(record + 8) + 8, (short) flags);
                    found = true; break;
                }
            }
            assertTrue(found);
            assertEquals(MathException.Reason.RENDERING_FAILED,
                    assertThrows(MathException.class, () -> MathTextFonts.of(bytes)).reason());
        }
    }

    @Test void processorRetainsExplicitFontsAndPerOccurrenceGeometry() throws IOException {
        var processor = TexMath.processor(MathOptions.DEFAULT, cjkFonts());
        String tex = "(\\bar{x} \\pm s) / \\text{岁}";
        var small = processor.render(tex, false, 12, 0x123456, 10_000);
        var large = processor.render(tex, false, 24, 0xabcdef, 10_000);
        assertEquals(tex, small.logicalText());
        assertEquals(0x123456, small.rgb());
        assertEquals(0xabcdef, large.rgb());
        assertTrue(large.resource().width() > small.resource().width() * 1.5);
        assertEquals(MathException.Reason.LIMIT_EXCEEDED, assertThrows(MathException.class,
                () -> processor.render(tex, false, 12, 0, 1)).reason());
    }

    @Test void cancellationAndConcurrentReusePreserveGeometry() throws Exception {
        var fonts = MathTextFonts.of(bytes("LiberationSans-Regular.ttf"));
        String tex = "\\frac{\\text{age}}{x}";
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        var cancellation = ExecutionOptions.builder().cancelled(() -> checks.incrementAndGet() > 40).build();
        assertEquals(ExecutionException.Code.CANCELLED, assertThrows(ExecutionException.class,
                () -> ExecutionContext.execute(cancellation,
                        () -> TexMath.vector(tex, MathOptions.DEFAULT, fonts))).code());
        var expected = TexMath.vector(tex, MathOptions.DEFAULT, fonts);
        List<Callable<InlineVector>> calls = List.of(
                () -> TexMath.vector(tex, MathOptions.DEFAULT, fonts),
                () -> TexMath.vector(tex, MathOptions.DEFAULT, fonts),
                () -> TexMath.vector(tex, MathOptions.DEFAULT, fonts),
                () -> TexMath.vector(tex, MathOptions.DEFAULT, fonts));
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (var future : executor.invokeAll(calls, 30, TimeUnit.SECONDS)) {
                assertFalse(future.isCancelled());
                assertEquals(expected, future.get(1, TimeUnit.SECONDS));
            }
        }
    }

    private static MathTextFonts cjkFonts() throws IOException {
        return MathTextFonts.of(bytes("LiberationSans-Regular.ttf"), bytes("ZCOOLXiaoWei-Regular.ttf"));
    }

    private static byte[] bytes(String name) throws IOException {
        try (var input = MathTextFontsTest.class.getResourceAsStream("/fonts/" + name)) {
            if (input == null) { throw new IOException("missing test font"); }
            return input.readAllBytes();
        }
    }
}
