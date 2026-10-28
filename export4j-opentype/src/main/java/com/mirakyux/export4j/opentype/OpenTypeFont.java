// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.opentype;

import com.mirakyux.export4j.model.ExecutionContext;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.harfbuzz.HarfBuzz;
import org.lwjgl.util.harfbuzz.OpenType;
import org.lwjgl.util.harfbuzz.hb_ot_var_axis_info_t;
import org.lwjgl.util.harfbuzz.hb_font_extents_t;

/**
 * Immutable explicit OpenType program, collection member, variation coordinates and color palette.
 * Owns no live native handles; safe to share. Loading never scans fonts or fetches resources.
 * Font bytes are limited to 32 MiB. Native resources are scoped to each synchronous operation.
 */
public final class OpenTypeFont {
    public enum OutlineFormat { TRUETYPE, CFF, CFF2 }
    public record Axis(String tag, double minimum, double defaultValue, double maximum) { }
    private final byte[] program;
    private final int faceIndex;
    private final Map<String, Double> coordinates;
    private final int palette;
    private final String id;
    private final List<Axis> axes;
    private final OutlineFormat outlineFormat;
    private final Set<String> tables;
    private final boolean subsetAllowed;
    private final int unitsPerEm;
    private final double ascent;
    private final double descent;

    private OpenTypeFont(byte[] bytes, int member, Map<String, Double> values, int paletteIndex,
            List<Axis> knownAxes) {
        program = bytes;
        faceIndex = member;
        coordinates = Map.copyOf(new TreeMap<>(values));
        if (knownAxes != null) { validateAxes(coordinates, knownAxes); }
        if (paletteIndex < 0 || paletteIndex > 65535) { throw new IllegalArgumentException("invalid color palette"); }
        palette = paletteIndex;
        tables = validate(program, member);
        outlineFormat = tables.contains("CFF2") ? OutlineFormat.CFF2 : tables.contains("CFF ") ? OutlineFormat.CFF : OutlineFormat.TRUETYPE;
        int fsType = embeddingFlags(program, member);
        if ((fsType & 0x0202) != 0) {
            throw new OpenTypeException(OpenTypeException.Code.EMBEDDING_RESTRICTED, -1, "restricted or bitmap-only font cannot be used as an outline resource");
        }
        subsetAllowed = (fsType & 0x0100) == 0;
        try {
            id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(program)) + ":" + member;
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
        ExecutionContext.checkpoint();
        try (var nativeFont = new NativeFont(this); var stack = MemoryStack.stackPush()) {
            unitsPerEm = HarfBuzz.hb_face_get_upem(nativeFont.face);
            var metrics = hb_font_extents_t.calloc(stack);
            if (!HarfBuzz.hb_font_get_h_extents(nativeFont.font, metrics)) { throw invalid(); }
            ascent = (double) metrics.ascender() / unitsPerEm;
            descent = -(double) metrics.descender() / unitsPerEm;
            if (knownAxes == null) {
                int count = OpenType.hb_ot_var_get_axis_count(nativeFont.face);
                if (count > 64) { throw new OpenTypeException(OpenTypeException.Code.BUDGET_EXCEEDED, -1, "font variation axis budget exceeded"); }
                var entries = hb_ot_var_axis_info_t.calloc(count, stack);
                OpenType.hb_ot_var_get_axis_infos(nativeFont.face, 0, stack.ints(count), entries);
                var result = new ArrayList<Axis>();
                for (int index = 0; index < count; index++) {
                    var entry = entries.get(index);
                    int tag = entry.tag();
                    String name = new String(new char[] {(char) (tag >>> 24), (char) (tag >>> 16 & 255), (char) (tag >>> 8 & 255), (char) (tag & 255)});
                    result.add(new Axis(name, entry.min_value(), entry.default_value(), entry.max_value()));
                }
                axes = List.copyOf(result);
            } else { axes = knownAxes; }
            int palettes = OpenType.hb_ot_color_palette_get_count(nativeFont.face);
            if (palette != 0 && palette >= palettes) { throw new IllegalArgumentException("font color palette does not exist"); }
        }
        validateAxes(coordinates, axes);
    }
    private static void validateAxes(Map<String, Double> coordinates, List<Axis> axes) {
        for (var entry : coordinates.entrySet()) {
            Axis axis = axes.stream().filter(value -> value.tag().equals(entry.getKey())).findFirst().orElseThrow(
                    () -> new OpenTypeException(OpenTypeException.Code.INVALID_AXIS, -1, "font variation axis does not exist"));
            double value = entry.getValue();
            if (!Double.isFinite(value) || value < axis.minimum() || value > axis.maximum()) {
                throw new OpenTypeException(OpenTypeException.Code.INVALID_AXIS, -1, "variation coordinate is outside the axis range");
            }
        }
    }

    public static OpenTypeFont load(byte[] bytes) { return load(bytes, 0); }
    /** Zero-based collection member. Input is copied before validation. */
    public static OpenTypeFont load(byte[] bytes, int faceIndex) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > 32 * 1024 * 1024 || faceIndex < 0 || faceIndex > 65535) {
            throw new OpenTypeException(OpenTypeException.Code.BUDGET_EXCEEDED, -1, "font byte or collection-member budget exceeded");
        }
        return new OpenTypeFont(bytes.clone(), faceIndex, Map.of(), 0, null);
    }
    /** Replaces the complete axis selection; unspecified axes use font defaults. No silent clamping. */
    public OpenTypeFont withAxes(Map<String, Double> values) {
        Objects.requireNonNull(values, "values");
        if (values.size() > 64) { throw new IllegalArgumentException("too many variation coordinates"); }
        return new OpenTypeFont(program, faceIndex, values, palette, axes);
    }
    public OpenTypeFont withPalette(int index) { return new OpenTypeFont(program, faceIndex, coordinates, index, axes); }
    public String id() { return id; }
    public int faceIndex() { return faceIndex; }
    public int byteSize() { return program.length; }
    public List<Axis> axes() { return axes; }
    public Map<String, Double> coordinates() { return coordinates; }
    public int palette() { return palette; }
    public int unitsPerEm() { return unitsPerEm; }
    /** Horizontal font metrics in em, from the selected variable instance. */
    public double ascent() { return ascent; }
    public double descent() { return descent; }
    public OutlineFormat outlineFormat() { return outlineFormat; }
    public boolean subsetAllowed() { return subsetAllowed; }
    public Set<String> tables() { return tables; }
    public boolean color() { return tables.contains("COLR") || tables.contains("CBDT") || tables.contains("sbix") || tables.contains("SVG "); }
    byte[] program() { return program; }

    /** Actual bundled native version; does not report Java binding header versions. */
    public static String nativeVersion() {
        try { return Objects.requireNonNull(HarfBuzz.hb_version_string()); }
        catch (LinkageError failure) { throw new OpenTypeException(OpenTypeException.Code.NATIVE_UNAVAILABLE, "matching explicit OpenType native binaries are required", failure); }
    }

    private static int offset(ByteBuffer data, int member) {
        if (data.capacity() < 12) { throw invalid(); }
        if (data.getInt(0) != 0x74746366) {
            if (member != 0) { throw invalid(); }
            return 0;
        }
        int count = data.getInt(8);
        if (count <= 0 || count > 65536 || member >= count || 12L + count * 4L > data.capacity()) { throw invalid(); }
        int value = data.getInt(12 + member * 4);
        if (value < 0 || (long) value + 12 > data.capacity()) { throw invalid(); }
        return value;
    }
    private static Set<String> validate(byte[] bytes, int member) {
        var data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int base = offset(data, member), magic = data.getInt(base);
        if (magic != 0x00010000 && magic != 0x4f54544f && magic != 0x74727565) { throw invalid(); }
        int count = Short.toUnsignedInt(data.getShort(base + 4));
        if (count == 0 || count > 256 || (long) base + 12 + count * 16 > bytes.length) { throw invalid(); }
        var tags = new java.util.HashSet<String>();
        for (int index = 0; index < count; index++) {
            int pos = base + 12 + index * 16;
            String tag = new String(bytes, pos, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            long start = Integer.toUnsignedLong(data.getInt(pos + 8)), length = Integer.toUnsignedLong(data.getInt(pos + 12));
            if (start + length > bytes.length || !tags.add(tag)) { throw invalid(); }
        }
        if (!tags.contains("head") || !tags.contains("maxp") || !tags.contains("cmap")) { throw invalid(); }
        return Set.copyOf(tags);
    }
    private static int embeddingFlags(byte[] bytes, int member) {
        var data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int base = offset(data, member), count = Short.toUnsignedInt(data.getShort(base + 4));
        for (int index = 0; index < count; index++) {
            int pos = base + 12 + index * 16;
            if (data.getInt(pos) == 0x4f532f32) {
                int start = data.getInt(pos + 8), length = data.getInt(pos + 12);
                if (length < 10) { throw invalid(); }
                return Short.toUnsignedInt(data.getShort(start + 8));
            }
        }
        throw invalid();
    }
    private static OpenTypeException invalid() { return new OpenTypeException(OpenTypeException.Code.INVALID_FONT, -1, "invalid bounded SFNT font or collection member"); }
}
