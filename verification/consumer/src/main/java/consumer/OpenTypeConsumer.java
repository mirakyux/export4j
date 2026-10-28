// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.pdf.PdfArchival;
import com.mirakyux.export4j.backend.pdf.PdfOptions;
import com.mirakyux.export4j.backend.pdf.PdfLimits;
import com.mirakyux.export4j.backend.raster.RasterBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.opentype.OpenTypeFont;
import com.mirakyux.export4j.opentype.OpenTypeShaper;
import com.mirakyux.export4j.opentype.OpenTypeText;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.ParagraphStyle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Public-artifact caller for explicit OpenType shaping, pagination and native static CFF embedding. */
public final class OpenTypeConsumer {
    private static final Path FONTS = Path.of("../../assets/fonts");
    private final Path output;
    private final StringBuilder glyphs = new StringBuilder("name\tfont\taxes\tdirection\tsize\tsource64\tindex\tgid\tstart\tend\tx\ty\txAdvance\tyAdvance\tpath64\n");
    private final StringBuilder fixtures = new StringBuilder("name\tpages\tcolor\tnativeCff\n");
    private OpenTypeConsumer(Path output) { this.output = output; }

    public static void main(String[] args) throws Exception {
        var caller = new OpenTypeConsumer(Path.of(System.getProperty("consumer.outputDirectory")).resolve("opentype"));
        Files.createDirectories(caller.output); caller.run();
    }
    private static OpenTypeFont font(String file) throws Exception { return OpenTypeFont.load(Files.readAllBytes(FONTS.resolve(file))); }
    private void run() throws Exception {
        var cjk = font("LXGWWenKaiTC-Regular.ttf"); var latin = font("RobotoFlex.ttf");
        String vertical = "「天地12玄黃」，宇宙洪荒 Hello 世界。\r\n".repeat(4);
        var stack = List.of(cjk, latin);
        fixture("vertical-general", vertical, OpenTypeText.builder(vertical, stack).fontSize(18).lineAdvance(30)
                .flow(OpenTypeText.Flow.VERTICAL_RIGHT_TO_LEFT).tateChuYoko(3, 5).build(), 160, 150, false);
        fixture("vertical-ltr", vertical, OpenTypeText.builder(vertical, stack).fontSize(18).lineAdvance(30)
                .flow(OpenTypeText.Flow.VERTICAL_LEFT_TO_RIGHT).tateChuYoko(3, 5).build(), 160, 150, false);
        String upright = "「天地玄黃」，宇宙 ABC 123。";
        fixture("vertical-upright", upright, OpenTypeText.builder(upright, stack).fontSize(18).lineAdvance(30)
                .flow(OpenTypeText.Flow.VERTICAL_RIGHT_TO_LEFT).orientation(OpenTypeText.Orientation.UPRIGHT).build(), 160, 150, false);
        shape("vertical-substitution", "LXGWWenKaiTC-Regular.ttf", cjk, "「天地」，", OpenTypeShaper.Options.of(18).withDirection(OpenTypeShaper.Direction.TOP_TO_BOTTOM));
        String[] fontFiles = {"NotoNaskhArabic-Regular.ttf", "NotoSansDevanagari-Regular.ttf", "NotoSansThai-Regular.ttf",
                "NotoSansBengali-Regular.ttf", "NotoSansTamil-Regular.ttf", "NotoSansMyanmar-Regular.ttf", "NotoSansKhmer-Regular.ttf",
                "NotoSansSinhala-Regular.ttf", "NotoSansHebrew-Regular.ttf"};
        String[] scriptNames = {"arabic", "devanagari", "thai", "bengali", "tamil", "myanmar", "khmer", "sinhala", "hebrew"};
        String[] scriptText = {"السَّلَامُ عَلَيْكُمْ", "नमस्ते दुनिया", "ภาษาไทยยินดีต้อนรับ", "বাংলা ভাষা", "தமிழ் மொழி", "မြန်မာဘာသာ",
                "ភាសាខ្មែរ", "සිංහල භාෂාව", "שָׁלוֹם עוֹלָם"};
        for (int index = 0; index < fontFiles.length; index++) {
            var f = font(fontFiles[index]); String name = "script-" + scriptNames[index]; String source = scriptText[index] + "\n" + scriptText[index];
            fixture(name, source, OpenTypeText.builder(source, List.of(f)).fontSize(24).lineAdvance(55).build(), 420, 120, false);
            shape(name, fontFiles[index], f, scriptText[index], OpenTypeShaper.Options.of(24));
        }
        String bidi = "Export4j 123 السَّلَامُ שָׁלוֹם 456 Java";
        fixture("mixed-bidi", bidi, OpenTypeText.builder(bidi, List.of(latin, font(fontFiles[0]), font(fontFiles[8]))).fontSize(22).lineAdvance(55).build(), 330, 180, false);
        String sample = "Hamburgefontsiv HÊjgpy 0123456789";
        fixture("variable-default", sample, OpenTypeText.builder(sample, List.of(latin)).fontSize(22).lineAdvance(45).build(), 500, 70, false);
        shape("variable-default", "RobotoFlex.ttf", latin, sample, OpenTypeShaper.Options.of(22));
        for (var axis : latin.axes()) {
            var instance = latin.withAxes(Map.of(axis.tag(), axis.maximum() == axis.defaultValue() ? axis.minimum() : axis.maximum()));
            String name = "variable-" + axis.tag();
            fixture(name, sample, OpenTypeText.builder(sample, List.of(instance)).fontSize(22).lineAdvance(45).build(), 500, 70, false);
            shape(name, "RobotoFlex.ttf", instance, sample, OpenTypeShaper.Options.of(22));
        }
        for (int weight : List.of(200, 900)) {
            var f = font("SourceSans3VF-Upright.otf").withAxes(Map.of("wght", (double) weight)); String name = "cff2-" + weight;
            fixture(name, sample, OpenTypeText.builder(sample, List.of(f)).fontSize(22).lineAdvance(45).build(), 500, 70, false);
            shape(name, "SourceSans3VF-Upright.otf", f, sample, OpenTypeShaper.Options.of(22));
        }
        for (String file : List.of("Export4j-ColorV0-Test.ttf", "Export4j-ColorV1-Test.ttf", "NotoColorEmoji-COLRv1.ttf", "NotoColorEmoji-sbix.ttf")) {
            var f = font(file); boolean test = file.startsWith("Export4j"); String source = test ? file.contains("V1") ? "ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`" : "ABCD" : "😀🌈🚀👍";
            String name = file.replace(".ttf", "");
            fixture(name, source, OpenTypeText.builder(source, List.of(f)).fontSize(30).lineAdvance(48).build(), 430, 160, true);
            if (test) { fixture(name + "-palette1", source, OpenTypeText.builder(source, List.of(f.withPalette(1))).fontSize(30).lineAdvance(48).build(), 430, 160, true); }
        }
        cff("cff-native", "SourceSans3-Regular.otf", "CFF native café Ω Ж €");
        cff("cff-cid-native", "Export4j-CID-Test.otf", "AB中😀");
        Files.writeString(output.resolve("glyphs.tsv"), glyphs.toString()); Files.writeString(output.resolve("fixtures.tsv"), fixtures.toString());
        Files.writeString(output.resolve("native-version.txt"), OpenTypeFont.nativeVersion());
    }
    private void fixture(String name, String source, OpenTypeText component, double width, double height, boolean color) throws Exception {
        var contextFont = TrueTypeFontLoader.load(Files.readAllBytes(FONTS.resolve("LiberationSans-Regular.ttf")));
        var result = component.layoutDetails(new ComponentContext(width, height, List.of(contextFont), 0, 0), height);
        var pages = new ArrayList<DisplayPage>();
        for (int index = 0; index < result.parts().size(); index++) {
            var part = result.parts().get(index); var commands = new ArrayList<DrawCommand>(); commands.add(new DrawCommand.Save()); commands.add(new DrawCommand.Transform(AffineTransform.translation(20, 20))); commands.addAll(part.commands()); commands.add(new DrawCommand.Restore());
            pages.add(new DisplayPage(new PageSize(width + 40, part.height() + 40), commands));
            int page = index;
            var positions = result.placements().stream().filter(value -> value.fragmentIndex() == page).sorted(java.util.Comparator.comparingInt(OpenTypeText.Placement::sourceStart)).toList();
            var original = new StringBuilder(); for (var position : positions) { original.append(source, position.sourceStart(), position.sourceEnd()); }
            Files.writeString(output.resolve(name + "-page-" + (index + 1) + ".txt"), original.toString());
        }
        write(name, pages); fixtures.append(name).append('\t').append(pages.size()).append('\t').append(color).append("\tfalse\n");
        Files.writeString(output.resolve(name + ".txt"), source);
    }
    private void cff(String name, String fontFile, String source) throws Exception {
        var face = TrueTypeFontLoader.load(Files.readAllBytes(FONTS.resolve(fontFile)));
        var layout = new ParagraphLayouter().layout(source, List.of(face), 500, ParagraphStyle.of(24)); var commands = new ArrayList<DrawCommand>();
        double x = 20;
        for (var run : layout.lines().getFirst().runs()) { commands.add(new DrawCommand.GlyphText(run, new Point(x, 50), RgbColor.BLACK)); x += run.width(); }
        write(name, List.of(new DisplayPage(new PageSize(550, 100), commands)));
        Files.writeString(output.resolve(name + ".txt"), source); Files.writeString(output.resolve(name + "-page-1.txt"), source);
        fixtures.append(name).append("\t1\tfalse\ttrue\n");
        var anchor = TrueTypeFontLoader.load(Files.readAllBytes(FONTS.resolve("LiberationSans-Regular.ttf")));
        for (var target : PdfArchival.Profile.values()) {
            var attachments = target == PdfArchival.Profile.A4F ? List.of(new PdfArchival.AssociatedFile("source.txt", "text/plain", "Source text", "Source", Instant.parse("2026-01-01T00:00:00Z"), source.getBytes(StandardCharsets.UTF_8))) : List.<PdfArchival.AssociatedFile>of();
            var archival = new PdfArchival(target, ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData(), anchor.data(), attachments);
            try (var stream = Files.newOutputStream(output.resolve(name + "-" + target.name().toLowerCase(java.util.Locale.ROOT) + ".pdf"))) {
                new PdfBackend(PdfLimits.DEFAULT, PdfOptions.DEFAULT.withArchival(archival)).write(new DisplayDocument(new DocumentMetadata(name, ""), List.of(new DisplayPage(new PageSize(550, 100), commands))), stream);
            }
        }
    }
    private void write(String name, List<DisplayPage> pages) throws Exception {
        var document = new DisplayDocument(new DocumentMetadata(name, ""), pages);
        try (var stream = Files.newOutputStream(output.resolve(name + ".pdf"))) { new PdfBackend().write(document, stream); }
        try (var stream = Files.newOutputStream(output.resolve(name + ".svg"))) { new SvgBackend().write(document, stream); }
        for (int index = 0; index < pages.size(); index++) {
            try (var stream = Files.newOutputStream(output.resolve(name + "-page-" + (index + 1) + ".png"))) { new RasterBackend(144).writePage(pages.get(index), stream); }
        }
    }
    private void shape(String name, String fontFile, OpenTypeFont font, String source, OpenTypeShaper.Options options) {
        var shaped = OpenTypeShaper.shape(font, source, options); String axes = font.coordinates().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> entry.getKey() + "=" + entry.getValue()).collect(java.util.stream.Collectors.joining(","));
        int index = 0;
        for (var glyph : shaped.glyphs()) {
            String path = svg(glyph.outlines());
            glyphs.append(name).append('\t').append(fontFile).append('\t').append(axes).append('\t').append(shaped.direction()).append('\t').append(options.fontSize()).append('\t').append(b64(source)).append('\t').append(index++).append('\t').append(glyph.glyphId()).append('\t').append(glyph.sourceStart()).append('\t').append(glyph.sourceEnd()).append('\t').append(glyph.x()).append('\t').append(glyph.y()).append('\t').append(glyph.xAdvance()).append('\t').append(glyph.yAdvance()).append('\t').append(b64(path)).append('\n');
        }
    }
    private static String b64(String source) { return Base64.getEncoder().encodeToString(source.getBytes(StandardCharsets.UTF_8)); }
    private static String svg(List<VectorPath> paths) {
        var text = new StringBuilder();
        for (var path : paths) {
            for (var segment : path.segments()) {
                switch (segment) {
                    case VectorPath.MoveTo value -> text.append('M').append(value.point().x()).append(' ').append(value.point().y());
                    case VectorPath.LineTo value -> text.append('L').append(value.point().x()).append(' ').append(value.point().y());
                    case VectorPath.CurveTo value -> text.append('C').append(value.control1().x()).append(' ').append(value.control1().y()).append(' ').append(value.control2().x()).append(' ').append(value.control2().y()).append(' ').append(value.end().x()).append(' ').append(value.end().y());
                    case VectorPath.Close ignored -> text.append('Z');
                }
            }
        }
        return text.toString();
    }
}
