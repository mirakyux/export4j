// SPDX-License-Identifier: Apache-2.0
package consumer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import com.mirakyux.export4j.backend.pdf.PdfArchival;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.pdf.PdfLimits;
import com.mirakyux.export4j.backend.pdf.PdfOptions;
import com.mirakyux.export4j.backend.pdf.PdfRenderException;
import com.mirakyux.export4j.backend.raster.RasterBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.SvgComponent;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.FontFamily;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.graphics.TextStyle;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;

/** Isolated coordinate consumer for per-paint transparency and safe affine SVG input. */
public final class Gap21GraphicsConsumer {
    private Gap21GraphicsConsumer() { }

    public static void main(String[] arguments) throws Exception {
        var output = Path.of(System.getProperty("consumer.outputDirectory", "build/output")).resolve("gap21-graphics");
        Files.createDirectories(output);
        var font = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Regular.ttf")));
        var fonts = Map.of(FontFamily.SANS_SERIF, font.data());
        var red = new RgbColor(1, 0, 0);
        var green = new RgbColor(0, 1, 0);
        var blue = new RgbColor(0, 0, 1);
        var drawing = new DisplayListBuilder(new PageSize(320, 180));
        drawing.save().opacity(0.5, 0.25).fillRect(new Rect(10, 10, 80, 60), red)
                .fillRect(new Rect(50, 30, 80, 60), blue)
                .strokePath(new VectorPath(List.of(new VectorPath.MoveTo(new Point(10, 120)),
                        new VectorPath.LineTo(new Point(150, 120)))), RgbColor.BLACK, 8)
                .save().opacity(0, 0).fillRect(new Rect(150, 10, 30, 30), green)
                .save().opacity(1, 1).fillRect(new Rect(190, 10, 30, 30), green).restore()
                .fillRect(new Rect(230, 10, 30, 30), green).restore()
                .fillRect(new Rect(270, 10, 30, 30), green)
                .drawImage(new RgbImage(1, 1, new byte[] {0, 0, (byte) 255}, new byte[] {(byte) 128}), new Rect(170, 60, 30, 30))
                .restore().fillRect(new Rect(230, 60, 30, 30), RgbColor.BLACK)
                .save().opacity(0.4, 1).drawText("Paint opacity", new Point(10, 160), new TextStyle(FontFamily.SANS_SERIF, 18, RgbColor.BLACK)).restore();
        write(output, "paint", drawing.build(), fonts);

        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 320 180" fill-opacity="0.5">
                  <g fill="#ff0000" transform="translate(10 10) scale(2)">
                    <rect width="40" height="30"/>
                    <rect x="20" y="10" width="40" height="30" fill="#0000ff"/>
                  </g>
                  <g fill="#00ff00" fill-opacity="25%" transform="translate(160 10) scale(1)">
                    <rect width="30" height="30"/>
                    <rect x="40" width="30" height="30" fill-opacity="1"/>
                    <g fill-opacity="0"><rect x="80" width="30" height="30"/></g>
                    <rect x="120" width="30" height="30"/>
                  </g>
                  <line x1="10" y1="120" x2="150" y2="120" stroke="#000000" stroke-width="8" stroke-opacity="0.25"/>
                  <rect x="240" y="100" width="20" height="40" fill="#000000" fill-opacity="1" transform="rotate(90 250 120)"/>
                  <rect width="20" height="20" fill="#000000" fill-opacity="1" transform="matrix(1 0 0 1 170 100) skewX(45) skewY(0)"/>
                  <rect x="300" y="150" width="80" height="80" fill="#0000ff" fill-opacity="1"/>
                </svg>
                """;
        Files.writeString(output.resolve("source.svg"), svg, StandardCharsets.UTF_8);
        var component = SvgComponent.parse(svg).layout(new ComponentContext(320, 180, List.of(font), 0, 0));
        write(output, "svg-input", new DisplayPage(new PageSize(component.width(), component.height()), component.commands()), fonts);

        var profile = java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB).getData();
        var archivalPage = new DisplayListBuilder(new PageSize(320, 180)).opacity(0.5, 0.25).fillRect(new Rect(10, 10, 80, 60), red).build();
        var archivalDocument = new DisplayDocument(new DocumentMetadata("Paint opacity archival", "Export4j"), List.of(archivalPage));
        var a2 = new PdfBackend(PdfLimits.DEFAULT, PdfOptions.DEFAULT.withArchival(new PdfArchival(PdfArchival.Profile.A2B, profile, font.data())));
        try (var stream = Files.newOutputStream(output.resolve("paint-a2b.pdf"))) { a2.write(archivalDocument, stream); }
        var a1 = new PdfBackend(PdfLimits.DEFAULT, PdfOptions.DEFAULT.withArchival(new PdfArchival(PdfArchival.Profile.A1B, profile, font.data())));
        var rejected = new ByteArrayOutputStream();
        try { a1.write(archivalDocument, rejected); throw new IllegalStateException("PDF/A-1 accepted vector alpha"); }
        catch (PdfRenderException expected) { if (rejected.size() != 0) { throw new IllegalStateException("PDF/A-1 emitted bytes before refusing transparency"); } }

        int refusals = 1;
        for (String attribute : List.of("opacity='0.5'", "fill-opacity='NaN'", "stroke-opacity='101%'", "transform='translate(1) bad'",
                "transform='scale(1 2 3)'", "style='fill-opacity:.5'", "filter='url(x)'")) {
            try { SvgComponent.parse("<svg viewBox='0 0 10 10'><rect width='10' height='10' " + attribute + "/></svg>"); throw new IllegalStateException("unsupported SVG accepted"); }
            catch (IllegalArgumentException expected) { refusals++; }
        }
        Files.writeString(output.resolve("summary.json"), "{\"refusals\":" + refusals + ",\"pages\":3}", StandardCharsets.UTF_8);
    }

    private static void write(Path output, String name, DisplayPage page, Map<FontFamily, com.mirakyux.export4j.font.FontData> fonts) throws Exception {
        var document = new DisplayDocument(new DocumentMetadata("Paint opacity", "Export4j"), List.of(page));
        try (var stream = Files.newOutputStream(output.resolve(name + ".pdf"))) { new PdfBackend().write(document, stream); }
        try (var stream = Files.newOutputStream(output.resolve(name + ".svg"))) { new SvgBackend(fonts).write(document, stream); }
        try (var stream = Files.newOutputStream(output.resolve(name + ".png"))) { new RasterBackend(144, fonts).writePage(page, stream); }
    }
}
