// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.opentype;

import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.model.ExecutionContext;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.harfbuzz.HarfBuzz;
import org.lwjgl.util.harfbuzz.HarfBuzzRaster;
import org.lwjgl.util.harfbuzz.hb_glyph_extents_t;
import org.lwjgl.util.harfbuzz.hb_raster_extents_t;

/** Evaluates font paint graphs using the matching optional HarfBuzz raster library. */
final class NativeColor {
    private NativeColor() { }
    static List<DrawCommand> paint(NativeFont font, int glyph, int palette,
            OpenTypeShaper.Options options, RgbColor foreground) {
        ExecutionContext.checkpoint();
        try (var stack = MemoryStack.stackPush()) {
            var extents = hb_glyph_extents_t.calloc(stack);
            if (!HarfBuzz.hb_font_get_glyph_extents(font.font, glyph, extents)) { return List.of(); }
            double scale = options.fontSize() / HarfBuzz.hb_face_get_upem(font.face);
            double pixelsPerUnit = scale * options.colorDpi() / 72;
            double left = Math.min(extents.x_bearing(), (double) extents.x_bearing() + extents.width());
            double top = Math.max(extents.y_bearing(), (double) extents.y_bearing() + extents.height());
            double w = Math.abs((double) extents.width()), h = Math.abs((double) extents.height());
            if (w == 0 || h == 0) { return List.of(); }
            int x = (int) Math.floor(left * pixelsPerUnit) - 2;
            int y = (int) Math.floor(-top * pixelsPerUnit) - 2;
            int pw = (int) Math.ceil(w * pixelsPerUnit) + 4, ph = (int) Math.ceil(h * pixelsPerUnit) + 4;
            if (pw <= 0 || ph <= 0 || (long) pw * ph > options.maxColorPixels()) {
                throw OpenTypeShaper.budget("color glyph pixel budget exceeded");
            }
            long painter = HarfBuzzRaster.hb_raster_paint_create_or_fail();
            if (painter == 0) { throw OpenTypeShaper.budget("native color painter allocation failed"); }
            try {
                HarfBuzzRaster.hb_raster_paint_set_transform(painter, (float) pixelsPerUnit, 0, 0, (float) -pixelsPerUnit, 0, 0);
                var rasterExtents = hb_raster_extents_t.calloc(stack).set(x, y, pw, ph, pw * 4);
                HarfBuzzRaster.hb_raster_paint_set_extents(painter, rasterExtents);
                int ink = 0xff000000 | (int) Math.round(foreground.red() * 255) << 16
                        | (int) Math.round(foreground.green() * 255) << 8 | (int) Math.round(foreground.blue() * 255);
                HarfBuzzRaster.hb_raster_paint_set_foreground(painter, ink);
                // The 3.4.3 Windows binary is HB 14.3.0; its raster convenience-glyph ABI differs
                // from the binding headers. Core paint + the stable raster callback SPI is shared.
                try (var guard = new NativePaintGuard(painter, (long) pw * ph, options.maxColorPixels())) {
                    long paintFunctions = guard.funcs;
                    HarfBuzz.hb_paint_push_transform(paintFunctions, painter, (float) pixelsPerUnit, 0, 0, (float) -pixelsPerUnit, 0, 0);
                    try {
                        boolean painted = HarfBuzz.hb_font_paint_glyph_or_fail(font.font, glyph, paintFunctions, painter, palette, ink);
                        guard.check();
                        if (!painted) { throw new OpenTypeException(OpenTypeException.Code.UNSUPPORTED_PAINT, -1, "native color glyph paint failed"); }
                    } finally { HarfBuzz.hb_paint_pop_transform(paintFunctions, painter); }
                    guard.check();
                }
                ExecutionContext.checkpoint();
                long rendered = HarfBuzzRaster.hb_raster_paint_render(painter);
                if (rendered == 0) { throw OpenTypeShaper.budget("native color raster allocation failed"); }
                try {
                    HarfBuzzRaster.hb_raster_image_get_extents(rendered, rasterExtents);
                    int width = rasterExtents.width(), height = rasterExtents.height(), stride = rasterExtents.stride();
                    if (width <= 0 || height <= 0 || (long) width * height > options.maxColorPixels()
                            || stride < (long) width * 4 || (long) stride * height > options.maxColorPixels() * 8L
                            || HarfBuzzRaster.hb_raster_image_get_format(rendered) != HarfBuzzRaster.HB_RASTER_FORMAT_BGRA32) {
                        throw OpenTypeShaper.budget("native color raster exceeds the declared format or pixel budget");
                    }
                    var data = HarfBuzzRaster.hb_raster_image_get_buffer(rendered, (long) stride * height);
                    if (data == null) { throw OpenTypeShaper.budget("native color raster has no buffer"); }
                    byte[] rgb = new byte[width * height * 3], alpha = new byte[width * height];
                    for (int row = 0; row < height; row++) {
                        ExecutionContext.checkpoint();
                        for (int col = 0; col < width; col++) {
                            int index = row * stride + col * 4, output = row * width + col;
                            int a = data.get(index + 3) & 255;
                            // HarfBuzz raster returns premultiplied BGRA. RgbImage owns straight RGB/alpha.
                            rgb[output * 3] = straight(data.get(index + 2) & 255, a);
                            rgb[output * 3 + 1] = straight(data.get(index + 1) & 255, a);
                            rgb[output * 3 + 2] = straight(data.get(index) & 255, a);
                            alpha[output] = (byte) a;
                        }
                    }
                    double pt = 72d / options.colorDpi();
                    return List.of(new DrawCommand.Image(new RgbImage(width, height, rgb, alpha),
                            new Rect(rasterExtents.x_origin() * pt, rasterExtents.y_origin() * pt, width * pt, height * pt)));
                } finally { HarfBuzzRaster.hb_raster_image_destroy(rendered); }
            } finally { HarfBuzzRaster.hb_raster_paint_destroy(painter); }
        } catch (LinkageError failure) {
            throw new OpenTypeException(OpenTypeException.Code.NATIVE_UNAVAILABLE, "matching explicit HarfBuzz raster native library is required", failure);
        }
    }
    private static byte straight(int value, int alpha) { return (byte) (alpha == 0 ? 0 : Math.min(255, (value * 255 + alpha / 2) / alpha)); }
}
