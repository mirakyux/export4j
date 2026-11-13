// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.raster.RasterBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.InlineVector;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.VectorResource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Exercises the public vector API using only the isolated consumer's Maven coordinates. */
public final class InlineVectorConsumer {
    private static final String BEFORE = "CONSUMER-VECTOR-BEFORE ";
    private static final String AFTER = " AFTER-VECTOR";
    private static final String LOGICAL = "\\frac{1}{2}";
    private static final String LINK = "https://example.com/consumer-inline-vector";
    private static final int RGB = 0x1755aa;
    private static final RgbColor INK = new RgbColor(0x17 / 255.0, 0x55 / 255.0, 0xaa / 255.0);
    private static final PageSize PAGE = new PageSize(420, 160);
    private static final ParagraphFormat FORMAT = new ParagraphFormat(12, 36, 0, 0,
            ParagraphAlignment.LEFT, 0, 0, false);

    private InlineVectorConsumer() { }

    public static void main(String[] args) throws Exception {
        var output = Files.createDirectories(Path.of(System.getProperty("consumer.outputDirectory", "build/output"))
                .resolve("inline-vectors"));
        var modelJar = Path.of(VectorResource.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        require(Files.isRegularFile(modelJar) && modelJar.getFileName().toString().endsWith(".jar"),
                "vector API must come from a published JAR, not project classes");
        require(InlineVector.class.getProtectionDomain().getCodeSource().getLocation()
                .equals(VectorResource.class.getProtectionDomain().getCodeSource().getLocation()),
                "vector model API has different providers");
        var resource = fraction();
        var linkedVector = new InlineVector(resource, LOGICAL, RGB, LINK);
        var linkedParagraph = paragraph(linkedVector);
        require(linkedParagraph.text().equals(BEFORE + '\ufffc' + AFTER)
                && linkedParagraph.vectors().size() == 1
                && linkedParagraph.vectors().getFirst().sourceIndex() == BEFORE.length()
                && linkedParagraph.content().equals(List.of(text(BEFORE), linkedVector, text(AFTER))),
                "one atomic vector marker must preserve both adjacent text runs");
        var font = TrueTypeFontLoader.load("../../assets/fonts/LiberationSans-Regular.ttf");
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(font))).build();
        var linkedResult = engine.layout(document(linkedParagraph));
        require(linkedResult.diagnostics().isEmpty(), "unexpected linked vector layout diagnostics");
        var linked = linkedResult.display();
        require(linked.pages().size() == 1 && linked.extras().externalLinks().size() == 1,
                "expected one page and one vector hyperlink");
        var link = linked.extras().externalLinks().getFirst();
        require(link.pageIndex() == 0 && link.destination().uri().equals(LINK), "vector hyperlink changed");
        var path = formulaPath(linked);
        require(path.segments().getFirst() instanceof VectorPath.MoveTo, "formula path must begin with MOVE");
        var first = ((VectorPath.MoveTo) path.segments().getFirst()).point();
        var sourceFirst = resource.segments().getFirst().coordinates();
        var baseline = new Point(first.x() - sourceFirst.get(0), first.y() - sourceFirst.get(1));
        var box = new Rect(baseline.x(), baseline.y() - resource.ascent(),
                resource.width(), resource.ascent() + resource.descent());
        require(close(box.width(), 24) && close(box.height(), 20)
                && box.x() > 24 && box.y() >= 24 && box.x() + box.width() < PAGE.width() - 24
                && box.y() + box.height() < PAGE.height() - 24, "vector box left its explicit content area: " + box);
        // Outline links use painted horizontal extents and the full line band, not the reserved object box.
        var linkBox = link.bounds();
        require(close(linkBox.x(), baseline.x() + 2) && close(linkBox.y(), 24)
                && close(linkBox.width(), 20) && close(linkBox.height(), FORMAT.lineHeight()),
                "vector link differs from its ink width and line band: " + linkBox);
        verifyInk(linked, resource, baseline);

        // Static backends reject links. Build an explicitly unlinked model with identical geometry.
        var staticResult = engine.layout(document(paragraph(new InlineVector(resource, LOGICAL, RGB, ""))));
        require(staticResult.diagnostics().isEmpty() && staticResult.display().pages().size() == 1
                && staticResult.display().extras().externalLinks().isEmpty(), "static fixture retained interactions");
        var unlinked = staticResult.display();
        verifyInk(unlinked, resource, baseline);
        try (var stream = Files.newOutputStream(output.resolve("linked.pdf"))) { new PdfBackend().write(linked, stream); }
        try (var stream = Files.newOutputStream(output.resolve("static.pdf"))) { new PdfBackend().write(unlinked, stream); }
        try (var stream = Files.newOutputStream(output.resolve("static.svg"))) { new SvgBackend().write(unlinked, stream); }
        try (var stream = Files.newOutputStream(output.resolve("page-00001.png"))) {
            new RasterBackend(144).writePage(unlinked.pages().getFirst(), stream);
        }
        var source = resource.segments().stream().map(segment -> "{\"operation\":\"" + segment.operation()
                + "\",\"coordinates\":" + segment.coordinates() + "}").collect(java.util.stream.Collectors.joining(","));
        Files.writeString(output.resolve("geometry.json"), "{\"schemaVersion\":1,\"units\":\"pt\",\"dpi\":144,"
                + "\"pages\":1,\"pageSize\":[420,160],\"sourceIndex\":" + BEFORE.length()
                + ",\"modelText\":" + quote(linkedParagraph.text()) + ",\"logicalText\":" + quote(LOGICAL)
                + ",\"expectedReadingText\":" + quote(BEFORE + LOGICAL + AFTER) + ",\"hyperlink\":" + quote(LINK)
                + ",\"rgb\":" + RGB + ",\"resource\":{\"id\":" + quote(resource.id())
                + ",\"width\":24,\"ascent\":12,\"descent\":8,\"segments\":" + resource.segmentCount()
                + ",\"path\":[" + source + "]},\"baseline\":[" + baseline.x() + "," + baseline.y()
                + "],\"box\":[" + box.x() + "," + box.y() + "," + box.width() + "," + box.height()
                + "],\"linkBox\":[" + linkBox.x() + "," + linkBox.y() + "," + linkBox.width() + "," + linkBox.height()
                + "],\"linkedPdfLinks\":1,\"staticModelLinks\":0,\"modelJar\":" + quote(modelJar.getFileName().toString())
                + ",\"modelJarSha256\":" + quote(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(modelJar)))) + "}\n");
        System.out.println("Published-coordinate inline vector API completed: " + output);
    }

    private static VectorResource fraction() {
        // A bounded, hand-authored 1/2 outline; this fixture does not parse TeX or use formula dependencies.
        return VectorResource.of(24, 12, 8, List.of(
                segment(VectorResource.Operation.MOVE, 10, -12), segment(VectorResource.Operation.LINE, 12, -12),
                segment(VectorResource.Operation.LINE, 12, -6), segment(VectorResource.Operation.LINE, 10, -6),
                segment(VectorResource.Operation.CLOSE),
                segment(VectorResource.Operation.MOVE, 2, -3), segment(VectorResource.Operation.LINE, 22, -3),
                segment(VectorResource.Operation.LINE, 22, -2), segment(VectorResource.Operation.LINE, 2, -2),
                segment(VectorResource.Operation.CLOSE),
                segment(VectorResource.Operation.MOVE, 8, 1), segment(VectorResource.Operation.CURVE, 8, -0.5, 16, -0.5, 16, 2),
                segment(VectorResource.Operation.CURVE, 16, 3.5, 11, 4.5, 10, 6),
                segment(VectorResource.Operation.LINE, 16, 6), segment(VectorResource.Operation.LINE, 16, 8),
                segment(VectorResource.Operation.LINE, 8, 8), segment(VectorResource.Operation.LINE, 8, 6),
                segment(VectorResource.Operation.CURVE, 8, 4.5, 14, 2.5, 14, 2),
                segment(VectorResource.Operation.CURVE, 14, 1, 10, 1, 10, 2),
                segment(VectorResource.Operation.LINE, 8, 2), segment(VectorResource.Operation.CLOSE)));
    }

    private static VectorResource.Segment segment(VectorResource.Operation operation, double... coordinates) {
        return new VectorResource.Segment(operation, java.util.Arrays.stream(coordinates).boxed().toList());
    }

    private static InlineText text(String value) { return new InlineText(value, InlineStyle.of(12)); }

    private static Paragraph paragraph(InlineVector vector) {
        return Paragraph.mixed("vector", List.of(text(BEFORE), vector, text(AFTER)), FORMAT);
    }

    private static Document document(Paragraph paragraph) {
        return new Document(new DocumentMetadata("Published-coordinate inline vector", "Export4j"),
                new PageSettings(PAGE, PageMargins.uniform(24)), List.of(paragraph));
    }

    private static VectorPath formulaPath(DisplayDocument display) {
        var commands = display.pages().getFirst().commands();
        require(commands.stream().noneMatch(command -> command instanceof DrawCommand.Transform
                || command instanceof DrawCommand.Group || command instanceof DrawCommand.Image),
                "simple vector fixture must remain untransformed and image-free");
        var paths = commands.stream().filter(DrawCommand.FillPath.class::isInstance).map(DrawCommand.FillPath.class::cast)
                .filter(command -> command.color().equals(INK)).toList();
        require(paths.size() == 1, "expected exactly one blue formula path");
        return paths.getFirst().path();
    }

    private static void verifyInk(DisplayDocument display, VectorResource resource, Point baseline) {
        var path = formulaPath(display);
        var expected = new VectorPath(resource.segments().stream().<VectorPath.Segment>map(segment -> {
            var c = segment.coordinates();
            return switch (segment.operation()) {
                case MOVE -> new VectorPath.MoveTo(new Point(baseline.x() + c.get(0), baseline.y() + c.get(1)));
                case LINE -> new VectorPath.LineTo(new Point(baseline.x() + c.get(0), baseline.y() + c.get(1)));
                case CURVE -> new VectorPath.CurveTo(new Point(baseline.x() + c.get(0), baseline.y() + c.get(1)),
                        new Point(baseline.x() + c.get(2), baseline.y() + c.get(3)),
                        new Point(baseline.x() + c.get(4), baseline.y() + c.get(5)));
                case CLOSE -> new VectorPath.Close();
            };
        }).toList());
        var actual = path.segments();
        require(actual.size() == expected.segments().size(), "vector segment count changed");
        for (int index = 0; index < actual.size(); index++) {
            require(sameSegment(actual.get(index), expected.segments().get(index)), "vector baseline or complete path changed");
        }
        var logical = display.pages().getFirst().commands().stream()
                .filter(DrawCommand.OutlineText.class::isInstance).map(DrawCommand.OutlineText.class::cast)
                .map(DrawCommand.OutlineText::logicalText).collect(java.util.stream.Collectors.joining());
        require(logical.equals(BEFORE + LOGICAL + AFTER), "vector reading text or adjacent text changed");
    }

    private static boolean close(double left, double right) { return Math.abs(left - right) <= 1e-7; }

    private static boolean close(Point left, Point right) { return close(left.x(), right.x()) && close(left.y(), right.y()); }

    private static boolean sameSegment(VectorPath.Segment actual, VectorPath.Segment expected) {
        // This compares in-memory doubles, before any backend decimal serialization.
        return switch (actual) {
            case VectorPath.MoveTo move -> expected instanceof VectorPath.MoveTo other && close(move.point(), other.point());
            case VectorPath.LineTo line -> expected instanceof VectorPath.LineTo other && close(line.point(), other.point());
            case VectorPath.CurveTo curve -> expected instanceof VectorPath.CurveTo other
                    && close(curve.control1(), other.control1()) && close(curve.control2(), other.control2()) && close(curve.end(), other.end());
            case VectorPath.Close ignored -> expected instanceof VectorPath.Close;
        };
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
