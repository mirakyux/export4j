// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.opentype;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.harfbuzz.HarfBuzz;
import org.lwjgl.util.harfbuzz.OpenType;
import org.lwjgl.util.harfbuzz.hb_variation_t;

/** Synchronous, task-confined owner; never escapes in a public API. */
final class NativeFont implements AutoCloseable {
    private long blob;
    final long face;
    final long font;

    NativeFont(OpenTypeFont source) {
        long newFace = 0;
        long newFont = 0;
        try {
            ByteBuffer bytes = MemoryUtil.memAlloc(source.byteSize());
            try {
                bytes.put(source.program()).flip();
                blob = HarfBuzz.hb_blob_create(bytes, HarfBuzz.HB_MEMORY_MODE_DUPLICATE, 0, null);
            } finally { MemoryUtil.memFree(bytes); }
            if (source.faceIndex() >= HarfBuzz.hb_face_count(blob)) {
                throw new OpenTypeException(OpenTypeException.Code.INVALID_FONT, -1, "font collection member does not exist");
            }
            newFace = HarfBuzz.hb_face_create(blob, source.faceIndex());
            if (HarfBuzz.hb_face_get_glyph_count(newFace) == 0 || HarfBuzz.hb_face_get_upem(newFace) == 0) {
                throw new OpenTypeException(OpenTypeException.Code.INVALID_FONT, -1, "font has no usable glyphs or units");
            }
            newFont = HarfBuzz.hb_font_create(newFace);
            OpenType.hb_ot_font_set_funcs(newFont);
            int upem = HarfBuzz.hb_face_get_upem(newFace);
            HarfBuzz.hb_font_set_scale(newFont, upem, upem);
            if (!source.coordinates().isEmpty()) {
                try (var stack = MemoryStack.stackPush()) {
                    var values = hb_variation_t.calloc(source.coordinates().size(), stack);
                    int index = 0;
                    for (var entry : source.coordinates().entrySet()) {
                        values.get(index++).tag(HarfBuzz.hb_tag_from_string(entry.getKey())).value(entry.getValue().floatValue());
                    }
                    HarfBuzz.hb_font_set_variations(newFont, values);
                }
            }
            face = newFace;
            font = newFont;
        } catch (Throwable failure) {
            if (newFont != 0) { HarfBuzz.hb_font_destroy(newFont); }
            if (newFace != 0) { HarfBuzz.hb_face_destroy(newFace); }
            if (blob != 0) { HarfBuzz.hb_blob_destroy(blob); blob = 0; }
            if (failure instanceof LinkageError) {
                throw new OpenTypeException(OpenTypeException.Code.NATIVE_UNAVAILABLE,
                        "explicit matching LWJGL and HarfBuzz native classifier is required", failure);
            }
            throw failure;
        }
    }

    @Override public void close() {
        if (blob != 0) {
            HarfBuzz.hb_font_destroy(font);
            HarfBuzz.hb_face_destroy(face);
            HarfBuzz.hb_blob_destroy(blob);
            blob = 0;
        }
    }
}
