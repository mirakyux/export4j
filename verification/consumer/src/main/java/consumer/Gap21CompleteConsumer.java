// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.raster.RasterBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.SvgComponent;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.BlendMode;
import com.mirakyux.export4j.graphics.CmykColor;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DocumentBackend;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.FontFamily;
import com.mirakyux.export4j.graphics.IccColor;
import com.mirakyux.export4j.graphics.Paint;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.SpotColor;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Complete native-resource and appearance consumer for GAP-21.1 and GAP-21.2. */
public final class Gap21CompleteConsumer {
    private Gap21CompleteConsumer() { }
    private static final RgbColor RED = new RgbColor(1, 0, 0), BLUE = new RgbColor(0, 0, 1);
    private static final PageSize SIZE = new PageSize(420, 360);
    private static final ProcessingPolicy WARN = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
    public static void main(String[] arguments) throws Exception {
        Path output = Path.of(System.getProperty("consumer.outputDirectory", "build/output")).resolve("gap21-complete"); Files.createDirectories(output);
        var font = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Regular.ttf")));
        var fonts = Map.of(FontFamily.SANS_SERIF, font.data());
        var pages = List.of(gradients(), blends(), colors(), svg(font)); var names = List.of("gradients", "blends", "colors", "svg-input"); int losses = 0;
        for (int i = 0; i < pages.size(); i++) {
            var page = pages.get(i); var document = new DisplayDocument(new DocumentMetadata("GAP21 " + names.get(i), "Export4j"), List.of(page));
            try (var stream = Files.newOutputStream(output.resolve(names.get(i) + ".pdf"))) { new PdfBackend().write(document, stream); }
            try (var stream = Files.newOutputStream(output.resolve(names.get(i) + ".svg"))) { losses += new SvgBackend(fonts).write(document, stream, WARN).losses().size(); }
            try (var stream = Files.newOutputStream(output.resolve(names.get(i) + ".png"))) { losses += new RasterBackend(144, fonts).writePage(page, stream, WARN).losses().size(); }
        }
        var profile = java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB).getData();
        var archival = new com.mirakyux.export4j.backend.pdf.PdfArchival(com.mirakyux.export4j.backend.pdf.PdfArchival.Profile.A2B, profile, font.data());
        var archiveBackend = new PdfBackend(com.mirakyux.export4j.backend.pdf.PdfLimits.DEFAULT, com.mirakyux.export4j.backend.pdf.PdfOptions.DEFAULT.withArchival(archival));
        try (var stream = Files.newOutputStream(output.resolve("advanced-a2b.pdf"))) { archiveBackend.write(new DisplayDocument(new DocumentMetadata("Advanced graphic resources", "Export4j"), List.of(gradients())), stream); }
        Files.writeString(output.resolve("source.svg"), SOURCE);
        int refusals = 0;
        var printDoc = new DisplayDocument(DocumentMetadata.EMPTY, List.of(colors()));
        for (var backend : List.<DocumentBackend>of(new SvgBackend(), new RasterBackend(72))) { var outputBytes = new ByteArrayOutputStream(); try { backend.write(printDoc, outputBytes); throw new IllegalStateException("print color silently converted"); } catch (ProcessingException expected) { if (outputBytes.size() != 0) { throw new IllegalStateException("strict refusal wrote bytes"); } refusals++; } }
        for (String unsafe : List.of("<use href='https://example.com/x'/>", "<use href='#absent'/>", "<defs><g id='a'><use href='#a'/></g></defs><use href='#a'/>", "<defs><linearGradient id='a' href='#b'/><linearGradient id='b' href='#a'/></defs><rect width='10' height='10' fill='url(#a)'/>", "<defs><clipPath id='a'><rect width='10' height='10' clip-path='url(#a)'/></clipPath></defs><g clip-path='url(#a)'/>", "<g style='filter:blur(4px)'/>")) { try { SvgComponent.parse("<svg viewBox='0 0 10 10'>" + unsafe + "</svg>"); throw new IllegalStateException("unsafe SVG accepted"); } catch (IllegalArgumentException expected) { refusals++; } }
        if (losses == 0) { throw new IllegalStateException("print-color loss reports absent"); }
        Files.writeString(output.resolve("summary.json"), "{\"pages\":5,\"blendModes\":16,\"losses\":" + losses + ",\"refusals\":" + refusals + "}");
    }
    private static DisplayPage gradients() {
        var builder = new DisplayListBuilder(SIZE);
        var stops = List.of(new Paint.Stop(0, RED), new Paint.Stop(.5, new RgbColor(0, 1, 0)), new Paint.Stop(1, BLUE));
        int i = 0; for (var spread : Paint.Spread.values()) {
            double y = 10 + i++ * 70; builder.fillPath(rect(10, y, 190, 55), new Paint.Linear(new Point(50, y), new Point(110, y), stops, spread, AffineTransform.IDENTITY));
            builder.fillPath(rect(220, y, 190, 55), new Paint.Radial(new Point(300, y + 28), new Point(290, y + 23), 25, stops, spread, AffineTransform.IDENTITY));
        }
        builder.fillPath(rect(10, 225, 190, 55), new Paint.Linear(new Point(10, 225), new Point(200, 225), List.of(new Paint.Stop(0, RED, 0), new Paint.Stop(1, BLUE, 1))));
        var tile = new Paint.Tile(new Rect(0, 0, 16, 16), 16, 16, new AffineTransform(1, .25, 0, 1, 0, 0), List.of(new DrawCommand.FillRect(new Rect(0, 0, 8, 8), RED), new DrawCommand.FillRect(new Rect(8, 8, 8, 8), BLUE)));
        builder.save().opacity(.6, .75).fillPath(rect(220, 225, 190, 55), tile).restore();
        builder.opacity(.5, .65).strokePath(new VectorPath(List.of(new VectorPath.MoveTo(new Point(10, 315)), new VectorPath.LineTo(new Point(410, 315)))), new Paint.Linear(new Point(10, 315), new Point(410, 315), stops), 16).opacity(1, 1);
        return builder.build();
    }
    private static DisplayPage blends() {
        var builder = new DisplayListBuilder(SIZE); int index = 0;
        for (var mode : BlendMode.values()) {
            double x = 10 + index % 4 * 102, y = 10 + index / 4 * 72; index++;
            builder.fillRect(new Rect(x, y, 90, 58), new RgbColor(.2, .4, .6)).save().blend(mode).opacity(.7, 1).fillRect(new Rect(x + 12, y + 8, 66, 42), new RgbColor(.8, .3, .15)).restore();
        }
        var children = List.<DrawCommand>of(new DrawCommand.FillRect(new Rect(20, 310, 70, 35), RED), new DrawCommand.FillRect(new Rect(60, 310, 70, 35), BLUE));
        builder.group(new Rect(10, 300, 140, 50), children, .5, BlendMode.NORMAL);
        builder.save().opacity(.5, 1).fillRect(new Rect(180, 310, 70, 35), RED).fillRect(new Rect(220, 310, 70, 35), BLUE).restore();
        return builder.build();
    }
    private static DisplayPage colors() {
        var builder = new DisplayListBuilder(SIZE); var profile = java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB).getData();
        var icc = new IccColor(profile, List.of(.2, .6, .9)); var alternate = new RgbColor(1, .4, 0); var spot = new SpotColor("Export4j Orange", 1, alternate);
        var cmyk = new CmykColor(1, 0, 0, 0); builder.fillPath(rect(10, 10, 120, 90), new Paint.Solid(cmyk)); builder.fillPath(rect(150, 10, 120, 90), new Paint.Solid(icc)); builder.fillPath(rect(290, 10, 120, 90), new Paint.Solid(spot));
        builder.fillPath(rect(10, 130, 190, 80), new Paint.Linear(new Point(10, 130), new Point(200, 130), List.of(new Paint.Stop(0, cmyk), new Paint.Stop(1, new CmykColor(0, 1, 0, 0)))));
        builder.fillPath(rect(220, 130, 190, 80), new Paint.Linear(new Point(220, 130), new Point(410, 130), List.of(new Paint.Stop(0, new SpotColor(spot.name(), 0, alternate)), new Paint.Stop(1, spot))));
        builder.fillPath(rect(10, 240, 400, 80), new Paint.Linear(new Point(10, 240), new Point(410, 240), List.of(new Paint.Stop(0, new IccColor(profile, List.of(0.0, 1.0, 0.0))), new Paint.Stop(1, new IccColor(profile, List.of(0.0, 0.0, 1.0))))));
        return builder.build();
    }
    private static DisplayPage svg(com.mirakyux.export4j.font.FontFace font) {
        var layout = SvgComponent.parse(SOURCE).layout(new ComponentContext(SIZE.width(), SIZE.height(), List.of(font), 0, 0)); return new DisplayPage(SIZE, layout.commands());
    }
    private static VectorPath rect(double x, double y, double w, double h) { return new VectorPath(List.of(new VectorPath.MoveTo(new Point(x, y)), new VectorPath.LineTo(new Point(x + w, y)), new VectorPath.LineTo(new Point(x + w, y + h)), new VectorPath.LineTo(new Point(x, y + h)), new VectorPath.Close())); }
    private static final String SOURCE = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 420 360">
              <defs>
                <linearGradient id="rainbow"><stop offset="0" stop-color="#ff0000"/><stop offset=".5" style="stop-color:#00ff00;stop-opacity:.8"/><stop offset="1" stop-color="#0000ff"/></linearGradient>
                <linearGradient id="inherited" href="#rainbow" gradientTransform="rotate(20 .5 .5)" spreadMethod="reflect"/>
                <radialGradient id="radial" cx="50%" cy="50%" fx="35%" fy="40%" r="60%"><stop offset="0" stop-color="#ffffff"/><stop offset="1" stop-color="#2244cc"/></radialGradient>
                <pattern id="tile-base" patternUnits="userSpaceOnUse" width="18" height="18"><rect width="9" height="9" fill="#ee4422"/><rect x="9" y="9" width="9" height="9" fill="#2244cc"/></pattern>
                <pattern id="tile" href="#tile-base" patternTransform="rotate(15)"/>
                <pattern id="object-tile" patternUnits="objectBoundingBox" patternContentUnits="objectBoundingBox" width=".25" height=".25"><rect width=".125" height=".25" fill="#228844"/></pattern>
                <symbol id="preview" viewBox="0 0 20 30" preserveAspectRatio="none"><rect width="20" height="30" fill="url(#object-tile)"/></symbol>
                <clipPath id="hole" clipPathUnits="objectBoundingBox"><path clip-rule="evenodd" d="M 0 0 L 1 0 L 1 1 L 0 1 Z M .3 .3 L .7 .3 L .7 .7 L .3 .7 Z"/></clipPath>
                <clipPath id="round"><circle cx="100" cy="65" r="55"/><rect x="100" y="10" width="90" height="110"/></clipPath>
                <clipPath id="nested" clip-path="url(#round)"><rect transform="translate(5 5)" x="10" y="10" width="185" height="100"/></clipPath>
                <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 Z" fill="context-stroke"/></marker>
                <g id="shape"><rect width="180" height="100" fill="url(#inherited)"/></g>
              </defs>
              <g transform="translate(5 5) rotate(5 100 65)" clip-path="url(#nested)"><use href="#shape" x="15" y="15"/></g>
              <rect x="225" y="15" width="180" height="105" fill="url(#radial)" clip-path="url(#hole)"/>
              <g transform="translate(15 145) skewX(10)" opacity=".55"><rect width="170" height="85" fill="url(#tile)"/><rect x="65" y="15" width="100" height="55" fill="#2244cc"/></g>
              <path d="M 240 165 L 285 205 C 300 230 350 150 390 200" fill="none" stroke="#228844" stroke-width="3" marker-start="url(#arrow)" marker-mid="url(#arrow)" marker-end="url(#arrow)"/>
              <g transform="translate(15 265) scale(1.8 .65)" clip-path="url(#hole)"><use href="#shape"/></g>
              <use href="#preview" x="365" y="265" width="40" height="70"/>
            </svg>
            """;
}
