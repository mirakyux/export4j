// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.opentype;

import com.mirakyux.export4j.model.ExecutionContext;
import java.awt.geom.Path2D;
import org.lwjgl.util.harfbuzz.HarfBuzz;
import org.lwjgl.util.harfbuzz.hb_draw_close_path_func_t;
import org.lwjgl.util.harfbuzz.hb_draw_cubic_to_func_t;
import org.lwjgl.util.harfbuzz.hb_draw_line_to_func_t;
import org.lwjgl.util.harfbuzz.hb_draw_move_to_func_t;
import org.lwjgl.util.harfbuzz.hb_draw_quadratic_to_func_t;

/** Bounded synchronous outline callback owner. Exceptions never cross JNI callbacks. */
final class NativeOutline implements AutoCloseable {
    private final long funcs = HarfBuzz.hb_draw_funcs_create();
    private Path2D.Double path;
    private int segments;
    private Throwable failure;
    private final hb_draw_move_to_func_t move = hb_draw_move_to_func_t.create((f, d, s, x, y, u) -> guard(() -> path.moveTo(x, y)));
    private final hb_draw_line_to_func_t line = hb_draw_line_to_func_t.create((f, d, s, x, y, u) -> guard(() -> path.lineTo(x, y)));
    private final hb_draw_quadratic_to_func_t quad = hb_draw_quadratic_to_func_t.create((f, d, s, x, y, tx, ty, u) -> guard(() -> path.quadTo(x, y, tx, ty)));
    private final hb_draw_cubic_to_func_t cubic = hb_draw_cubic_to_func_t.create((f, d, s, x, y, x2, y2, tx, ty, u) -> guard(() -> path.curveTo(x, y, x2, y2, tx, ty)));
    private final hb_draw_close_path_func_t close = hb_draw_close_path_func_t.create((f, d, s, u) -> guard(() -> path.closePath()));
    NativeOutline() {
        HarfBuzz.hb_draw_funcs_set_move_to_func(funcs, move, 0, null);
        HarfBuzz.hb_draw_funcs_set_line_to_func(funcs, line, 0, null);
        HarfBuzz.hb_draw_funcs_set_quadratic_to_func(funcs, quad, 0, null);
        HarfBuzz.hb_draw_funcs_set_cubic_to_func(funcs, cubic, 0, null);
        HarfBuzz.hb_draw_funcs_set_close_path_func(funcs, close, 0, null);
    }
    Path2D.Double glyph(long font, int glyph) {
        path = new Path2D.Double();
        HarfBuzz.hb_font_draw_glyph(font, glyph, funcs, 0);
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure instanceof Error error) { throw error; }
        return path;
    }
    private void guard(Runnable action) {
        if (failure != null) { return; }
        try {
            if (++segments > 2_000_000) { throw new OpenTypeException(OpenTypeException.Code.BUDGET_EXCEEDED, -1, "native outline segment budget exceeded"); }
            if ((segments & 255) == 0) { ExecutionContext.checkpoint(); }
            action.run();
        } catch (RuntimeException | Error caught) { failure = caught; }
    }
    @Override public void close() {
        HarfBuzz.hb_draw_funcs_destroy(funcs);
        close.free(); cubic.free(); quad.free(); line.free(); move.free();
    }
}
