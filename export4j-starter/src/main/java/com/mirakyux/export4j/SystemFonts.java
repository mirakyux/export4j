// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j;

import com.mirakyux.export4j.adapter.docx.DocxFontFamilies;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontFaceCache;
import com.mirakyux.export4j.font.FontLoadException;
import com.mirakyux.export4j.font.FontSource;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.ExecutionContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Known local physical fonts for the no-argument starter. No directories are walked. */
final class SystemFonts {
    record Families(FontFamily latin, FontFamily eastAsia) { }

    private record Face(Path path, String member) {
        Face(Path path) { this(path, null); }

        FontSource source() { return member == null ? FontSource.file(path) : FontSource.collection(path, member); }

        FontFace load(FontFaceCache cache) throws IOException {
            ExecutionContext.checkpoint();
            if (!Files.isRegularFile(path)) { return null; }
            try {
                if (Files.size(path) > TrueTypeFontLoader.DEFAULT_MAX_BYTES) { return null; }
                return source().load(cache, ExecutionContext::checkpoint);
            } catch (NoSuchFileException missing) {
                // The font may disappear between checking the path and opening it.
                return null;
            } catch (FontLoadException unsupported) {
                if (unsupported.code() == FontLoadException.Code.BUDGET_EXCEEDED) { throw unsupported; }
                return null;
            }
        }
    }

    private record Candidate(String name, Face regular, Face bold, Face italic, Face boldItalic) {
        FontFamily declaration() {
            var builder = FontFamily.builder(name, regular.source());
            if (bold != null) { builder.bold(bold.source()); }
            if (italic != null) { builder.italic(italic.source()); }
            if (boldItalic != null) { builder.boldItalic(boldItalic.source()); }
            return builder.build();
        }

        FontFamily load(FontFaceCache cache) throws IOException {
            var face = regular.load(cache);
            if (face == null) { return null; }
            var builder = FontFamily.builder(name, loaded(face));
            if (bold != null && (face = bold.load(cache)) != null) { builder.bold(loaded(face)); }
            if (italic != null && (face = italic.load(cache)) != null) { builder.italic(loaded(face)); }
            if (boldItalic != null && (face = boldItalic.load(cache)) != null) { builder.boldItalic(loaded(face)); }
            return builder.build();
        }

        private static FontSource loaded(FontFace face) { return ignored -> face; }
    }

    private final List<Candidate> latin;
    private final List<Candidate> eastAsia;

    private SystemFonts(List<Candidate> latin, List<Candidate> eastAsia) {
        this.latin = List.copyOf(latin);
        this.eastAsia = List.copyOf(eastAsia);
    }

    static SystemFonts host() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            String root = System.getenv("SystemRoot");
            return windows(Path.of(root == null || root.isBlank() ? "C:/Windows" : root).resolve("Fonts"));
        }
        if (os.startsWith("mac") || os.equals("darwin")) { return mac(Path.of("/System/Library/Fonts")); }
        if (os.startsWith("linux")) { return linux(Path.of("/usr/share/fonts")); }
        return new SystemFonts(List.of(), List.of());
    }

    static SystemFonts windows(Path fonts) {
        return new SystemFonts(List.of(
                family(fonts, "Arial", "arial.ttf", "arialbd.ttf", "ariali.ttf", "arialbi.ttf"),
                family(fonts, "Segoe UI", "segoeui.ttf", "segoeuib.ttf", "segoeuii.ttf", "segoeuiz.ttf")),
                List.of(
                        collection(fonts, "Microsoft YaHei", "msyh.ttc", "MicrosoftYaHei", "msyhbd.ttc", "MicrosoftYaHei-Bold"),
                        family(fonts, "DengXian", "Deng.ttf", "Dengb.ttf", null, null),
                        family(fonts, "SimHei", "simhei.ttf", null, null, null),
                        collection(fonts, "SimSun", "simsun.ttc", "SimSun", null, null)));
    }

    static SystemFonts mac(Path fonts) {
        return new SystemFonts(List.of(
                family(fonts.resolve("Supplemental"), "Arial", "Arial.ttf", "Arial Bold.ttf", "Arial Italic.ttf", "Arial Bold Italic.ttf"),
                family(fonts, "Helvetica", "Helvetica.ttc", "Helvetica.ttc", "Helvetica.ttc", "Helvetica.ttc",
                        "Helvetica", "Helvetica-Bold", "Helvetica-Oblique", "Helvetica-BoldOblique")),
                List.of(
                        collection(fonts, "Hiragino Sans GB", "Hiragino Sans GB.ttc", "HiraginoSansGB-W3",
                                "Hiragino Sans GB.ttc", "HiraginoSansGB-W6"),
                        collection(fonts, "Heiti SC", "STHeiti Light.ttc", "STHeitiSC-Light", "STHeiti Medium.ttc", "STHeitiSC-Medium"),
                        collection(fonts, "PingFang SC", "PingFang.ttc", "PingFangSC-Regular", "PingFang.ttc", "PingFangSC-Semibold"),
                        collection(fonts.resolve("LanguageSupport"), "PingFang SC", "PingFang.ttc", "PingFangSC-Regular",
                                "PingFang.ttc", "PingFangSC-Semibold"),
                        collection(fonts.resolve("Supplemental"), "Songti SC", "Songti.ttc", "STSongti-SC-Regular",
                                "Songti.ttc", "STSongti-SC-Bold")));
    }

    static SystemFonts linux(Path fonts) {
        return new SystemFonts(List.of(
                family(fonts.resolve("truetype/liberation2"), "Liberation Sans", "LiberationSans-Regular.ttf",
                        "LiberationSans-Bold.ttf", "LiberationSans-Italic.ttf", "LiberationSans-BoldItalic.ttf"),
                family(fonts.resolve("truetype/liberation"), "Liberation Sans", "LiberationSans-Regular.ttf",
                        "LiberationSans-Bold.ttf", "LiberationSans-Italic.ttf", "LiberationSans-BoldItalic.ttf"),
                family(fonts.resolve("liberation-sans"), "Liberation Sans", "LiberationSans-Regular.ttf",
                        "LiberationSans-Bold.ttf", "LiberationSans-Italic.ttf", "LiberationSans-BoldItalic.ttf"),
                family(fonts.resolve("truetype/dejavu"), "DejaVu Sans", "DejaVuSans.ttf", "DejaVuSans-Bold.ttf",
                        "DejaVuSans-Oblique.ttf", "DejaVuSans-BoldOblique.ttf"),
                family(fonts.resolve("TTF"), "DejaVu Sans", "DejaVuSans.ttf", "DejaVuSans-Bold.ttf",
                        "DejaVuSans-Oblique.ttf", "DejaVuSans-BoldOblique.ttf"),
                family(fonts.resolve("truetype/noto"), "Noto Sans", "NotoSans-Regular.ttf", "NotoSans-Bold.ttf",
                        "NotoSans-Italic.ttf", "NotoSans-BoldItalic.ttf"),
                family(fonts.resolve("noto"), "Noto Sans", "NotoSans-Regular.ttf", "NotoSans-Bold.ttf",
                        "NotoSans-Italic.ttf", "NotoSans-BoldItalic.ttf")),
                List.of(notoCjk(fonts.resolve("opentype/noto")), notoCjk(fonts.resolve("noto-cjk")),
                        notoCjk(fonts.resolve("google-noto-sans-cjk")), notoCjk(fonts.resolve("noto"))));
    }

    private static Candidate notoCjk(Path fonts) {
        return collection(fonts, "Noto Sans CJK SC", "NotoSansCJK-Regular.ttc", "NotoSansCJKsc-Regular",
                "NotoSansCJK-Bold.ttc", "NotoSansCJKsc-Bold");
    }

    private static Candidate family(Path root, String name, String regular, String bold, String italic, String boldItalic) {
        return family(root, name, regular, bold, italic, boldItalic, null, null, null, null);
    }

    private static Candidate collection(Path root, String name, String regular, String regularName, String bold, String boldName) {
        return family(root, name, regular, bold, null, null, regularName, boldName, null, null);
    }

    private static Candidate family(Path root, String name, String regular, String bold, String italic, String boldItalic,
            String regularName, String boldName, String italicName, String boldItalicName) {
        return new Candidate(name, new Face(root.resolve(regular), regularName), face(root, bold, boldName),
                face(root, italic, italicName), face(root, boldItalic, boldItalicName));
    }

    private static Face face(Path root, String filename, String member) {
        return filename == null ? null : new Face(root.resolve(filename), member);
    }

    Families load(FontFamily explicitEastAsia, FontFaceCache cache) throws IOException {
        var body = first(latin, cache);
        var cjk = explicitEastAsia == null ? first(eastAsia, cache) : explicitEastAsia;
        if (body == null) { body = cjk; }
        if (body == null) {
            throw new IOException("no supported local default font was found; install a system font or use Export4j.builder(FontFamily)");
        }
        return new Families(body, cjk == null ? body : cjk);
    }

    Families declarations(FontFamily explicitEastAsia) {
        var body = firstPresent(latin);
        var cjk = explicitEastAsia == null ? firstPresent(eastAsia) : explicitEastAsia;
        if (body == null) { body = cjk; }
        return new Families(body, cjk == null ? body : cjk);
    }

    DocxFontFamilies wordFamilies(FontFamily body, FontFamily cjk, FontFamily complex) {
        String bodyName = body == null ? preferredName(latin, "Arial") : body.name();
        String cjkName = cjk == null ? preferredName(eastAsia, bodyName) : cjk.name();
        return new DocxFontFamilies(bodyName, bodyName, cjkName, complex == null ? bodyName : complex.name());
    }

    private static String preferredName(List<Candidate> candidates, String fallback) {
        return candidates.isEmpty() ? fallback : candidates.getFirst().name();
    }

    private static FontFamily firstPresent(List<Candidate> candidates) {
        for (var candidate : candidates) {
            ExecutionContext.checkpoint();
            if (Files.isRegularFile(candidate.regular().path())) { return candidate.declaration(); }
        }
        return null;
    }

    private static FontFamily first(List<Candidate> candidates, FontFaceCache cache) throws IOException {
        for (var candidate : candidates) {
            var family = candidate.load(cache);
            if (family != null) { return family; }
        }
        return null;
    }
}
