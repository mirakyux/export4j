// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.opentype;

import com.mirakyux.export4j.model.ExecutionContext;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.Callback;
import org.lwjgl.system.JNI;
import org.lwjgl.util.harfbuzz.HarfBuzz;
import org.lwjgl.util.harfbuzz.HarfBuzzRaster;
import org.lwjgl.util.harfbuzz.hb_paint_color_func_t;
import org.lwjgl.util.harfbuzz.hb_paint_image_func_t;
import org.lwjgl.util.harfbuzz.hb_paint_linear_gradient_func_t;
import org.lwjgl.util.harfbuzz.hb_paint_pop_clip_func_t;
import org.lwjgl.util.harfbuzz.hb_paint_pop_group_func_t;
import org.lwjgl.util.harfbuzz.hb_paint_pop_transform_func_t;
import org.lwjgl.util.harfbuzz.hb_paint_push_clip_glyph_func_t;
import org.lwjgl.util.harfbuzz.hb_paint_push_clip_rectangle_func_t;
import org.lwjgl.util.harfbuzz.hb_paint_push_group_func_t;
import org.lwjgl.util.harfbuzz.hb_paint_push_transform_func_t;
import org.lwjgl.util.harfbuzz.hb_paint_radial_gradient_func_t;
import org.lwjgl.util.harfbuzz.hb_paint_sweep_gradient_func_t;

/** Task-local paint proxy. Bounds work before forwarding allocations; callback failures never cross JNI. */
final class NativePaintGuard implements AutoCloseable {
    final long funcs;
    private final long target;
    private final long painter;
    private final long pixels;
    private final int maxImagePixels;
    private final List<Callback> callbacks = new ArrayList<>();
    private final NativeOutline outlines;
    private long chargedBytes;
    private int operations;
    private Throwable failure;

    NativePaintGuard(long painter, long pixels, int maxImagePixels) {
        this.painter = painter; this.pixels = pixels; this.maxImagePixels = maxImagePixels;
        // HB 14.3.0 takes the painter; 14.3.1 made this a no-argument API.
        target = OpenTypeFont.nativeVersion().equals("14.3.0")
                ? JNI.invokePP(painter, HarfBuzzRaster.Functions.paint_get_funcs) : HarfBuzzRaster.hb_raster_paint_get_funcs();
        funcs = HarfBuzz.hb_paint_funcs_create();
        outlines = new NativeOutline();
        try {
            charge(16 * pixels);
            HarfBuzz.hb_paint_funcs_set_push_transform_func(funcs, keep(hb_paint_push_transform_func_t.create((f, d, xx, yx, xy, yy, dx, dy, u) ->
                    guard(() -> HarfBuzz.hb_paint_push_transform(target, painter, xx, yx, xy, yy, dx, dy)))), 0, null);
            HarfBuzz.hb_paint_funcs_set_pop_transform_func(funcs, keep(hb_paint_pop_transform_func_t.create((f, d, u) ->
                    guard(() -> HarfBuzz.hb_paint_pop_transform(target, painter)))), 0, null);
            HarfBuzz.hb_paint_funcs_set_push_clip_glyph_func(funcs, keep(hb_paint_push_clip_glyph_func_t.create((f, d, g, font, u) ->
                    guard(() -> { charge(16 * pixels); outlines.glyph(font, g); HarfBuzz.hb_paint_push_clip_glyph(target, painter, g, font); }))), 0, null);
            HarfBuzz.hb_paint_funcs_set_push_clip_rectangle_func(funcs, keep(hb_paint_push_clip_rectangle_func_t.create((f, d, x0, y0, x1, y1, u) ->
                    guard(() -> { charge(16 * pixels); HarfBuzz.hb_paint_push_clip_rectangle(target, painter, x0, y0, x1, y1); }))), 0, null);
            HarfBuzz.hb_paint_funcs_set_pop_clip_func(funcs, keep(hb_paint_pop_clip_func_t.create((f, d, u) ->
                    guard(() -> HarfBuzz.hb_paint_pop_clip(target, painter)))), 0, null);
            HarfBuzz.hb_paint_funcs_set_color_func(funcs, keep(hb_paint_color_func_t.create((f, d, foreground, color, u) ->
                    guard(() -> HarfBuzz.hb_paint_color(target, painter, foreground, color)))), 0, null);
            HarfBuzz.hb_paint_funcs_set_push_group_func(funcs, keep(hb_paint_push_group_func_t.create((f, d, u) ->
                    guard(() -> { charge(16 * pixels); HarfBuzz.hb_paint_push_group(target, painter); }))), 0, null);
            HarfBuzz.hb_paint_funcs_set_pop_group_func(funcs, keep(hb_paint_pop_group_func_t.create((f, d, mode, u) ->
                    guard(() -> HarfBuzz.hb_paint_pop_group(target, painter, mode)))), 0, null);
            HarfBuzz.hb_paint_funcs_set_linear_gradient_func(funcs, keep(hb_paint_linear_gradient_func_t.create((f, d, colors, x0, y0, x1, y1, x2, y2, u) ->
                    guard(() -> { stops(colors); HarfBuzz.nhb_paint_linear_gradient(target, painter, colors, x0, y0, x1, y1, x2, y2); }))), 0, null);
            HarfBuzz.hb_paint_funcs_set_radial_gradient_func(funcs, keep(hb_paint_radial_gradient_func_t.create((f, d, colors, x0, y0, r0, x1, y1, r1, u) ->
                    guard(() -> { stops(colors); HarfBuzz.nhb_paint_radial_gradient(target, painter, colors, x0, y0, r0, x1, y1, r1); }))), 0, null);
            HarfBuzz.hb_paint_funcs_set_sweep_gradient_func(funcs, keep(hb_paint_sweep_gradient_func_t.create((f, d, colors, x, y, a0, a1, u) ->
                    guard(() -> { stops(colors); HarfBuzz.nhb_paint_sweep_gradient(target, painter, colors, x, y, a0, a1); }))), 0, null);
            HarfBuzz.hb_paint_funcs_set_image_func(funcs, keep(hb_paint_image_func_t.create((f, d, blob, w, h, format, slant, extents, u) -> {
                guard(() -> { image(blob, w, h, format); HarfBuzz.nhb_paint_image(target, painter, blob, w, h, format, slant, extents); });
                return failure == null ? 1 : 0;
            })), 0, null);
            // Missing fill-glyph, color-glyph and push-group-for callbacks use HarfBuzz's specified
            // clip/color, recursive-glyph and ordinary-group fallback, so all work passes this proxy.
        } catch (RuntimeException | Error caught) { close(); throw caught; }
    }
    private <T extends Callback> T keep(T callback) { callbacks.add(callback); return callback; }
    private void charge(long bytes) {
        chargedBytes += bytes;
        if (bytes < 0 || chargedBytes > 256L * 1024 * 1024) { throw OpenTypeShaper.budget("color paint intermediate allocation budget exceeded"); }
    }
    private static void stops(long colors) {
        if (Integer.toUnsignedLong(HarfBuzz.nhb_color_line_get_color_stops(colors, 0, 0, 0)) > 4096) {
            throw OpenTypeShaper.budget("color gradient stop budget exceeded");
        }
    }
    private void image(long blob, int width, int height, int format) {
        int length = HarfBuzz.hb_blob_get_length(blob);
        if (length <= 0 || length > 32 * 1024 * 1024) { throw OpenTypeShaper.budget("embedded color bitmap byte budget exceeded"); }
        if (format == HarfBuzz.HB_PAINT_IMAGE_FORMAT_PNG) {
            var data = HarfBuzz.hb_blob_get_data(blob);
            if (data == null || data.remaining() < 33 || data.order(ByteOrder.BIG_ENDIAN).getLong(0) != 0x89504e470d0a1a0aL
                    || data.getInt(8) != 13 || data.getInt(12) != 0x49484452) {
                throw new OpenTypeException(OpenTypeException.Code.UNSUPPORTED_PAINT, -1, "invalid embedded PNG header");
            }
            width = data.getInt(16); height = data.getInt(20);
        } else if (format != HarfBuzz.HB_PAINT_IMAGE_FORMAT_BGRA) {
            throw new OpenTypeException(OpenTypeException.Code.UNSUPPORTED_PAINT, -1, "embedded color image format is unsupported");
        }
        long count = (long) width * height;
        if (width <= 0 || height <= 0 || count > maxImagePixels) { throw OpenTypeShaper.budget("embedded color bitmap pixel budget exceeded"); }
        if (format == HarfBuzz.HB_PAINT_IMAGE_FORMAT_BGRA && count * 4 > length) {
            throw new OpenTypeException(OpenTypeException.Code.UNSUPPORTED_PAINT, -1, "embedded BGRA bitmap is truncated");
        }
        charge(16 * count);
    }
    private void guard(Runnable action) {
        if (failure != null) { return; }
        try {
            ExecutionContext.checkpoint();
            if (++operations > 100_000) { throw OpenTypeShaper.budget("color paint operation budget exceeded"); }
            action.run();
        } catch (RuntimeException | Error caught) { failure = caught; }
    }
    void check() {
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure instanceof Error error) { throw error; }
    }
    @Override public void close() {
        HarfBuzz.hb_paint_funcs_destroy(funcs);
        for (var callback : callbacks) { callback.free(); }
        outlines.close();
    }
}
