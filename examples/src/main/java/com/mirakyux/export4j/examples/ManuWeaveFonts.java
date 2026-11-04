// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import java.awt.Font;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Explicit example-only font selection. No system font search, synthetic styles or network access. */
final class ManuWeaveFonts {
    private static final int MAX_MAP_BYTES = 1_048_576;
    private static final int MAX_MAPPINGS = 128;
    private static final long MAX_EXTERNAL_BYTES = 128L * 1024 * 1024;
    private static final List<String> VARIANTS = List.of("regular", "bold", "italic", "boldItalic");
    private final Map<String, Face> bundled = new HashMap<>();
    private final Map<String, Map<String, Face>> external = new LinkedHashMap<>();

    ManuWeaveFonts() throws IOException { this(null); }

    /** Properties XML keys are family.&lt;source family&gt;.&lt;regular|bold|italic|boldItalic&gt;.
     * Paths are absolute or relative to the XML. A declared family must provide every requested
     * variant; missing variants fail instead of silently changing faces. Resources stay caller-owned.
     */
    ManuWeaveFonts(Path optionalMap) throws IOException {
        for (String family : List.of("LiberationSans", "LiberationSerif")) {
            for (String variant : VARIANTS) {
                String file = family + "-" + Character.toUpperCase(variant.charAt(0)) + variant.substring(1) + ".ttf";
                bundled.put(family + "." + variant, describe(AuthoringExample.face(file), false));
            }
        }
        bundled.put("cjk", describe(AuthoringExample.face("LXGWWenKaiTC-Regular.ttf"), false));
        if (optionalMap != null) { loadMap(optionalMap); }
    }

    FontGroup group(boolean bold, boolean italic, boolean chinese) {
        return group("Liberation Sans", "", bold, italic, chinese);
    }
    String name(boolean bold, boolean italic, boolean chinese) {
        return name("Liberation Sans", "", bold, italic, chinese);
    }
    FontGroup group(String requested, String eastAsian, boolean bold, boolean italic, boolean chinese) {
        return selection(requested, eastAsian, bold, italic, chinese).fonts();
    }
    String name(String requested, String eastAsian, boolean bold, boolean italic, boolean chinese) {
        return selection(requested, eastAsian, bold, italic, chinese).actual();
    }

    Selection selection(String requested, String eastAsian, boolean bold, boolean italic, boolean chinese) {
        requested = requested == null ? "" : requested.trim();
        eastAsian = eastAsian == null ? "" : eastAsian.trim();
        String variant = variant(bold, italic);
        Face primary = resolve(requested, variant);
        var faces = new ArrayList<FontFace>();
        faces.add(primary.face());
        var names = new ArrayList<String>();
        names.add(primary.name());
        boolean substituted = !sameFamily(requested, primary.family());
        boolean useExternal = primary.external();
        String requestedDescription = requested.isEmpty() ? "unspecified" : requested;
        requestedDescription += "." + variant;
        if (chinese) {
            String cjkFamily = eastAsian.isEmpty() && isCjkName(requested) ? requested : eastAsian;
            Face cjk = external.containsKey(normalize(cjkFamily))
                    ? resolve(cjkFamily, variant) : bundled.get("cjk");
            if (!cjk.face().data().id().equals(primary.face().data().id())) {
                faces.add(cjk.face()); names.add(cjk.name());
            }
            substituted |= !sameFamily(cjkFamily, cjk.family()) || (bold || italic) && !cjk.external();
            useExternal |= cjk.external();
            requestedDescription += ";" + (cjkFamily.isEmpty() ? "unspecified-CJK" : cjkFamily) + "." + variant;
        }
        return new Selection(FontGroup.strict(faces), String.join(";", names), requestedDescription,
                substituted, useExternal);
    }

    private Face resolve(String requested, String variant) {
        var mapped = external.get(normalize(requested));
        if (mapped != null) {
            Face face = mapped.get(variant);
            if (face == null) {
                throw new IllegalArgumentException("Explicit font map has no " + variant + " face for " + requested);
            }
            return face;
        }
        String name = requested.toLowerCase(Locale.ROOT);
        boolean sans = name.contains("sans") || name.contains("arial") || name.contains("helvetica")
                || name.contains("calibri") || name.contains("biolinum") || name.contains("黑体");
        return bundled.get((sans ? "LiberationSans" : "LiberationSerif") + "." + variant);
    }

    private void loadMap(Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        if (Files.size(absolute) > MAX_MAP_BYTES) { throw new IOException("Font map byte budget exceeded"); }
        var values = new Properties();
        try (var input = Files.newInputStream(absolute)) {
            byte[] bytes = input.readNBytes(MAX_MAP_BYTES + 1);
            if (bytes.length > MAX_MAP_BYTES) { throw new IOException("Font map byte budget exceeded"); }
            values.loadFromXML(new ByteArrayInputStream(bytes));
        }
        if (values.size() > MAX_MAPPINGS) { throw new IOException("Font map entry budget exceeded"); }
        long usedBytes = 0;
        for (String key : values.stringPropertyNames().stream().sorted().toList()) {
            int separator = key.lastIndexOf('.');
            if (!key.startsWith("family.") || separator <= 7 || key.length() > 300
                    || !VARIANTS.contains(key.substring(separator + 1))) {
                throw new IOException("Invalid font map key; use family.<source family>.<variant>");
            }
            String family = key.substring(7, separator).trim();
            String variant = key.substring(separator + 1);
            String value = values.getProperty(key).trim();
            if (family.isEmpty() || value.isEmpty() || value.length() > 2048) {
                throw new IOException("Invalid font map family or path");
            }
            Path path = Path.of(value);
            if (!path.isAbsolute()) { path = absolute.getParent().resolve(path); }
            long size = Files.size(path);
            if (size > TrueTypeFontLoader.DEFAULT_MAX_BYTES || usedBytes + size > MAX_EXTERNAL_BYTES) {
                throw new IOException("External font byte budget exceeded");
            }
            FontFace face;
            try (var input = Files.newInputStream(path)) {
                int remaining = (int) Math.min(TrueTypeFontLoader.DEFAULT_MAX_BYTES, MAX_EXTERNAL_BYTES - usedBytes);
                if (remaining <= 0) { throw new IOException("External font byte budget exceeded"); }
                face = TrueTypeFontLoader.load(input, remaining);
            }
            usedBytes += face.data().byteSize();
            Face described = describe(face, true);
            requireVariant(face, variant);
            var familyFaces = external.computeIfAbsent(normalize(family), ignored -> new HashMap<>());
            if (familyFaces.putIfAbsent(variant, described) != null) {
                throw new IOException("Font map contains duplicate normalized family and variant");
            }
        }
    }

    private static Face describe(FontFace face, boolean external) throws IOException {
        try (var input = new ByteArrayInputStream(face.data().bytes())) {
            Font font = Font.createFont(Font.TRUETYPE_FONT, input);
            return new Face(face, font.getPSName(), font.getFamily(Locale.ROOT), external);
        } catch (java.awt.FontFormatException failure) {
            throw new IOException("Font face metadata is unavailable", failure);
        }
    }

    /** OS/2 fsSelection and head macStyle are checked against the explicit requested physical face. */
    private static void requireVariant(FontFace face, String expected) throws IOException {
        var bytes = java.nio.ByteBuffer.wrap(face.data().bytes()).order(java.nio.ByteOrder.BIG_ENDIAN);
        int count = Short.toUnsignedInt(bytes.getShort(4));
        int flags = -1;
        int macStyle = 0;
        for (int index = 0; index < count; index++) {
            int record = 12 + index * 16;
            int tag = bytes.getInt(record), offset = bytes.getInt(record + 8), length = bytes.getInt(record + 12);
            if (offset < 0 || length < 0 || (long) offset + length > bytes.limit()) {
                throw new IOException("Invalid font metadata table bounds");
            }
            if (tag == 0x4F532F32 && length >= 64) { flags = Short.toUnsignedInt(bytes.getShort(offset + 62)); }
            if (tag == 0x68656164 && length >= 46) { macStyle = Short.toUnsignedInt(bytes.getShort(offset + 44)); }
        }
        boolean bold = flags >= 0 ? (flags & 0x20) != 0 : (macStyle & 1) != 0;
        boolean italic = flags >= 0 ? (flags & 1) != 0 : (macStyle & 2) != 0;
        if (!variant(bold, italic).equals(expected)) {
            throw new IOException("Explicit font map variant does not match the font's physical style: " + expected);
        }
    }

    private static String variant(boolean bold, boolean italic) {
        return bold ? italic ? "boldItalic" : "bold" : italic ? "italic" : "regular";
    }
    private static boolean sameFamily(String requested, String actual) {
        return !requested.isEmpty() && normalize(requested).equals(normalize(actual));
    }
    private static String normalize(String family) {
        return family.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
    }
    private static boolean isCjkName(String family) {
        return family.codePoints().anyMatch(point -> Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN);
    }
    record Selection(FontGroup fonts, String actual, String requested, boolean substituted, boolean external) { }
    private record Face(FontFace face, String name, String family, boolean external) { }
}
