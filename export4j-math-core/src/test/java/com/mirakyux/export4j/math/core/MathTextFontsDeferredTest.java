// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math.core;

import com.mirakyux.export4j.math.api.MathException;
import com.mirakyux.export4j.math.api.MathOptions;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mathematical text-font preparation is lazy and scoped by the invoking operation. */
class MathTextFontsDeferredTest {
    @Test void symbolOnlyFormulaNeverEvaluatesTheSupplier() {
        var calls = new AtomicInteger();
        var fonts = MathTextFonts.deferred(() -> { calls.incrementAndGet(); throw new AssertionError("unexpected font preparation"); });
        assertFalse(fonts.isEmpty());
        assertTrue(TexVectorMath.vector("x^2", MathOptions.DEFAULT, fonts).resource().width() > 0);
        assertEquals(0, calls.get());
    }

    @Test void textAndTextrmGroupsEvaluateOnlyWhenUsedAndDoNotMemoizeGlobally() throws Exception {
        var prepared = textFonts();
        var calls = new AtomicInteger();
        var fonts = MathTextFonts.deferred(() -> { calls.incrementAndGet(); return prepared; });
        assertTrue(TexVectorMath.vector("\\text{One}", MathOptions.DEFAULT, fonts).resource().width() > 0);
        assertEquals(1, calls.get());
        assertTrue(TexVectorMath.vector("\\textrm{Two}", MathOptions.DEFAULT, fonts).resource().width() > 0);
        assertEquals(2, calls.get());
        assertTrue(TexVectorMath.vector("x", MathOptions.DEFAULT, fonts).resource().width() > 0);
        assertEquals(2, calls.get());
    }

    @Test void cancellationIsObservedBeforeCallingAnApplicationFontSupplier() throws Exception {
        var prepared = textFonts();
        var calls = new AtomicInteger();
        var fonts = MathTextFonts.deferred(() -> { calls.incrementAndGet(); return prepared; });
        var options = ExecutionOptions.builder().cancelled(() -> true).build();
        assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options,
                () -> TexVectorMath.vector("\\text{One}", MathOptions.DEFAULT, fonts)));
        assertEquals(0, calls.get());
        assertTrue(TexVectorMath.vector("\\text{One}", MathOptions.DEFAULT, fonts).resource().width() > 0);
        assertEquals(1, calls.get());
    }

    @Test void aDeferredSupplierCannotReturnAnotherDeferredConfiguration() {
        var nested = MathTextFonts.deferred(() -> MathTextFonts.deferred(() -> MathTextFonts.EMPTY));
        var failure = assertThrows(MathException.class, () -> TexVectorMath.vector("\\text{One}", MathOptions.DEFAULT, nested));
        assertEquals(MathException.Reason.RENDERING_FAILED, failure.reason());
        assertTrue(failure.getCause() instanceof IllegalArgumentException);
    }

    @Test void deferredEmptyFontsRetainTheOrdinaryAsciiTextRenderingPath() {
        var ordinary = TexVectorMath.vector("\\text{ASCII}", MathOptions.DEFAULT, MathTextFonts.EMPTY);
        var deferred = TexVectorMath.vector("\\text{ASCII}", MathOptions.DEFAULT,
                MathTextFonts.deferred(() -> MathTextFonts.EMPTY));
        assertEquals(ordinary, deferred);
    }

    private static MathTextFonts textFonts() throws IOException {
        try (var input = MathTextFontsDeferredTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            if (input == null) { throw new IOException("Missing explicit test font"); }
            return MathTextFonts.of(input.readAllBytes());
        }
    }
}
