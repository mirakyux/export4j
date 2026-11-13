// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.web.HtmlImporter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.raster.RasterBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
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
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.VectorResource;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Offline, example-only TeX preparation; core accepts immutable paths, not a TeX parser. */
public final class InlineVectorsExample {
    private static final Path OUTPUT = Path.of("examples/build/inline-vectors");
    private static final ParagraphFormat FORMAT = new ParagraphFormat(12, 58, 0, 0,
            ParagraphAlignment.LEFT, 0, 8, false);
    private static final List<String> TEX = List.of(
            "x = \\frac{-b \\pm \\sqrt{b^2-4ac}}{2a}",
            "x_1^2 + x_2^2 = r^2",
            "\\sum_{i=1}^{n}i = \\frac{n(n+1)}{2}");
    private InlineVectorsExample() { }

    public static void main(String[] args) throws IOException {
        var prepared = TEX.stream().map(tex -> ManuWeaveMath.prepare(tex, 14,
                new ManuWeaveMath.GeometryBudget(1024, VectorResource.MAX_SEGMENTS))).toList();
        var vectors = prepared.stream().map(formula -> new InlineVector(resource(formula), formula.tex())).toList();
        var blocks = new ArrayList<Block>();
        blocks.add(new Paragraph("title", "Inline vector mathematics", ParagraphFormat.of(20)));
        blocks.add(new Paragraph("intro", "Explicit paths keep their baseline in paragraphs, cells and HTML imports.", ParagraphFormat.of(12)));
        blocks.add(Paragraph.mixed("quadratic", List.of(text("Solution: "), vectors.get(0), text(" for a nonzero a.")), FORMAT));
        blocks.add(Paragraph.mixed("powers", List.of(text("The circle satisfies "), vectors.get(1), text(" in this plane.")), FORMAT));
        blocks.add(Paragraph.mixed("display", List.of(vectors.get(2)), FORMAT.withAlignment(ParagraphAlignment.CENTER)));
        var cellFormat = new CellFormat(12, 58, ParagraphAlignment.LEFT);
        var table = Table.builder("formula-table").columns(new ColumnWidth.Fixed(110), new ColumnWidth.Fraction(1))
                .header("Form", "Vector formula");
        var names = List.of("Fraction and root", "Sub/superscripts", "Summation");
        for (int index = 0; index < vectors.size(); index++) {
            table.row(new TableCell(names.get(index), cellFormat), TableCell.mixed(List.of(vectors.get(index)), cellFormat));
        }
        blocks.add(table.build());
        var importer = HtmlImporter.builder().paragraphFormat(FORMAT)
                .vector("formula:quadratic", vectors.get(0)).vector("formula:powers", vectors.get(1)).build();
        var imported = importer.read("<p>HTML inline: <img src='formula:quadratic'> keeps its source.</p>"
                + "<p>HTML inline: <img src='formula:powers'> keeps its baseline.</p>").execute();
        if (!imported.report().issues().isEmpty()) { throw new IOException("unexpected HTML import loss"); }
        blocks.addAll(imported.document().blocks());
        var document = new Document(new DocumentMetadata("Inline vector mathematics", "Export4j"),
                new PageSettings(new PageSize(600, 900), PageMargins.uniform(36)), blocks);
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf")))).build();
        var display = engine.layout(document).display();
        var expected = expectations(document);
        var observed = new Observed();
        for (int page = 0; page < display.pages().size(); page++) {
            observe(display.pages().get(page).commands(), page + 1, new AffineTransform(), vectors, observed);
        }
        for (var vector : vectors) {
            long count = expected.stream().filter(value -> value.resource().equals(vector.resource().id())).count();
            if (count != observed.vectors.stream().filter(value -> value.resource().equals(vector.resource().id())).count()) {
                throw new IOException("vector placement count changed for " + vector.logicalText());
            }
        }
        if (observed.images != 0 || observed.glyphRuns == 0) { throw new IOException("expected native ordinary text and vector-only mathematics"); }
        // No interactions/accessibility are removed here; static backends validate the original display.
        Files.createDirectories(OUTPUT);
        try (var output = Files.newOutputStream(OUTPUT.resolve("mixed.pdf"))) { new PdfBackend().write(display, output); }
        try (var output = Files.newOutputStream(OUTPUT.resolve("mixed.svg"))) { new SvgBackend().write(display, output); }
        var raster = new RasterBackend(144);
        for (int page = 0; page < display.pages().size(); page++) {
            try (var output = Files.newOutputStream(OUTPUT.resolve(String.format(Locale.ROOT, "page-%05d.png", page + 1)))) {
                raster.writePage(display.pages().get(page), output);
            }
        }
        Files.writeString(OUTPUT.resolve("geometry.json"), geometry(prepared, vectors, display, expected, observed, imported.report().losses().size()));
        System.out.println("Generated " + display.pages().size() + " pages and " + expected.size() + " vector placements in " + OUTPUT);
    }

    private static InlineText text(String value) { return new InlineText(value, InlineStyle.of(12)); }

    private static VectorResource resource(ManuWeaveMath.Formula formula) {
        // Capture paints separate black paths. Union their filled areas so overlap never cancels.
        var union = new Area();
        for (var path : formula.paths()) { union.add(new Area(awt(path))); }
        union.transform(AffineTransform.getTranslateInstance(formula.offsetX(), formula.offsetY() - formula.baseline()));
        var segments = new ArrayList<VectorResource.Segment>();
        var iterator = union.getPathIterator(null); var points = new double[6];
        double x = 0, y = 0;
        while (!iterator.isDone()) {
            int kind = iterator.currentSegment(points);
            switch (kind) {
                case PathIterator.SEG_MOVETO -> {
                    segments.add(segment(VectorResource.Operation.MOVE, points[0], points[1])); x = points[0]; y = points[1];
                }
                case PathIterator.SEG_LINETO -> {
                    segments.add(segment(VectorResource.Operation.LINE, points[0], points[1])); x = points[0]; y = points[1];
                }
                case PathIterator.SEG_CUBICTO -> {
                    segments.add(segment(VectorResource.Operation.CURVE, points[0], points[1], points[2], points[3], points[4], points[5]));
                    x = points[4]; y = points[5];
                }
                case PathIterator.SEG_QUADTO -> {
                    segments.add(segment(VectorResource.Operation.CURVE, x + (points[0] - x) * 2 / 3, y + (points[1] - y) * 2 / 3,
                            points[2] + (points[0] - points[2]) * 2 / 3, points[3] + (points[1] - points[3]) * 2 / 3, points[2], points[3]));
                    x = points[2]; y = points[3];
                }
                case PathIterator.SEG_CLOSE -> segments.add(segment(VectorResource.Operation.CLOSE));
                default -> throw new IllegalArgumentException("unsupported prepared vector operation");
            }
            if (segments.size() > VectorResource.MAX_SEGMENTS) { throw new IllegalArgumentException("union exceeds vector segment budget"); }
            iterator.next();
        }
        // Strict bounds validation preserves the original baseline and reserved box, without scaling.
        return VectorResource.of(formula.width(), formula.baseline(), formula.height() - formula.baseline(), segments);
    }

    private static VectorResource.Segment segment(VectorResource.Operation operation, double... coordinates) {
        return new VectorResource.Segment(operation, java.util.Arrays.stream(coordinates).boxed().toList());
    }

    private static Path2D.Double awt(VectorPath path) {
        var shape = new Path2D.Double(Path2D.WIND_NON_ZERO);
        for (var item : path.segments()) {
            switch (item) {
                case VectorPath.MoveTo move -> shape.moveTo(move.point().x(), move.point().y());
                case VectorPath.LineTo line -> shape.lineTo(line.point().x(), line.point().y());
                case VectorPath.CurveTo curve -> shape.curveTo(curve.control1().x(), curve.control1().y(),
                        curve.control2().x(), curve.control2().y(), curve.end().x(), curve.end().y());
                case VectorPath.Close ignored -> shape.closePath();
            }
        }
        return shape;
    }

    private record Expected(String container, int row, int column, int sourceIndex, String sourceText, String resource) { }
    private static List<Expected> expectations(Document document) {
        var result = new ArrayList<Expected>();
        for (var block : document.blocks()) {
            if (block instanceof Paragraph paragraph) {
                for (var span : paragraph.vectors()) { result.add(new Expected(paragraph.id(), -1, -1, span.sourceIndex(), paragraph.text(), span.vector().resource().id())); }
            } else if (block instanceof Table table) {
                for (int row = 0; row < table.rows().size(); row++) {
                    var cells = table.rows().get(row).cells();
                    for (int column = 0; column < cells.size(); column++) {
                        for (var span : cells.get(column).vectors()) { result.add(new Expected(table.id(), row, column, span.sourceIndex(), cells.get(column).text(), span.vector().resource().id())); }
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private record Placement(String resource, int page, Rectangle2D box, Point2D baseline) { }
    private static final class Observed {
        final List<Placement> vectors = new ArrayList<>();
        final List<String> logicalText = new ArrayList<>();
        int glyphRuns;
        int images;
    }
    private static void observe(List<DrawCommand> commands, int page, AffineTransform initial,
            List<InlineVector> vectors, Observed result) {
        var matrix = new AffineTransform(initial); var stack = new ArrayDeque<AffineTransform>();
        for (var command : commands) {
            if (command instanceof DrawCommand.Save) { stack.push(new AffineTransform(matrix)); }
            else if (command instanceof DrawCommand.Restore) { matrix = stack.pop(); }
            else if (command instanceof DrawCommand.Transform transform) {
                var m = transform.matrix(); matrix.concatenate(new AffineTransform(m.a(), m.b(), m.c(), m.d(), m.e(), m.f()));
            } else if (command instanceof DrawCommand.Group group) { observe(group.commands(), page, matrix, vectors, result); }
            else if (command instanceof DrawCommand.GlyphText) { result.glyphRuns++; }
            else if (command instanceof DrawCommand.Image) { result.images++; }
            else if (command instanceof DrawCommand.FillPath fill) { placement(fill.path(), page, matrix, vectors, result); }
            else if (command instanceof DrawCommand.OutlineText outline) {
                result.logicalText.add(outline.logicalText());
                for (var path : outline.paths()) { placement(path, page, matrix, vectors, result); }
            }
        }
    }
    private static void placement(VectorPath path, int page, AffineTransform matrix, List<InlineVector> vectors, Observed result) {
        for (var vector : vectors) {
            var resource = vector.resource(); var source = resource.segments();
            if (path.segments().size() != source.size() || !(path.segments().getFirst() instanceof VectorPath.MoveTo first)) { continue; }
            double x = first.point().x() - source.getFirst().coordinates().get(0);
            double y = first.point().y() - source.getFirst().coordinates().get(1);
            boolean matches = true;
            for (int index = 0; index < source.size() && matches; index++) {
                var candidate = switch (path.segments().get(index)) {
                    case VectorPath.MoveTo move -> segment(VectorResource.Operation.MOVE, move.point().x() - x, move.point().y() - y);
                    case VectorPath.LineTo line -> segment(VectorResource.Operation.LINE, line.point().x() - x, line.point().y() - y);
                    case VectorPath.CurveTo curve -> segment(VectorResource.Operation.CURVE, curve.control1().x() - x, curve.control1().y() - y,
                            curve.control2().x() - x, curve.control2().y() - y, curve.end().x() - x, curve.end().y() - y);
                    case VectorPath.Close ignored -> segment(VectorResource.Operation.CLOSE);
                };
                var expected = source.get(index);
                matches = candidate.operation() == expected.operation();
                for (int coordinate = 0; coordinate < expected.coordinates().size() && matches; coordinate++) {
                    matches = Math.abs(candidate.coordinates().get(coordinate) - expected.coordinates().get(coordinate)) <= 1e-7;
                }
            }
            if (matches) {
                var box = matrix.createTransformedShape(new Rectangle2D.Double(x, y - resource.ascent(), resource.width(), resource.ascent() + resource.descent())).getBounds2D();
                result.vectors.add(new Placement(resource.id(), page, box, matrix.transform(new Point2D.Double(x, y), null))); return;
            }
        }
    }

    private static String geometry(List<ManuWeaveMath.Formula> prepared, List<InlineVector> vectors, DisplayDocument display,
            List<Expected> expected, Observed observed, int losses) {
        var json = new StringBuilder("{\"schemaVersion\":1,\"units\":\"pt\",\"dpi\":144,\"pages\":")
                .append(display.pages().size()).append(",\"htmlImportLosses\":").append(losses)
                .append(",\"nativeGlyphRuns\":").append(observed.glyphRuns).append(",\"images\":").append(observed.images).append(",\"formulas\":[");
        for (int index = 0; index < vectors.size(); index++) {
            if (index != 0) { json.append(','); }
            var formula = prepared.get(index); var resource = vectors.get(index).resource();
            json.append("{\"tex\":").append(quote(formula.tex())).append(",\"resource\":").append(quote(resource.id()))
                    .append(",\"width\":").append(resource.width()).append(",\"ascent\":").append(resource.ascent())
                    .append(",\"descent\":").append(resource.descent()).append(",\"sourceBaseline\":").append(formula.baseline())
                    .append(",\"sourceOffset\":[").append(formula.offsetX()).append(',').append(formula.offsetY())
                    .append("],\"segments\":").append(resource.segmentCount()).append(",\"path\":[");
            for (int pathIndex = 0; pathIndex < resource.segments().size(); pathIndex++) {
                if (pathIndex != 0) { json.append(','); }
                var segment = resource.segments().get(pathIndex);
                json.append("{\"operation\":").append(quote(segment.operation().name())).append(",\"coordinates\":")
                        .append(segment.coordinates()).append('}');
            }
            json.append("]}");
        }
        json.append("],\"expected\":[");
        for (int index = 0; index < expected.size(); index++) {
            if (index != 0) { json.append(','); } var value = expected.get(index);
            json.append("{\"container\":").append(quote(value.container())).append(",\"row\":").append(value.row())
                    .append(",\"column\":").append(value.column()).append(",\"sourceIndex\":").append(value.sourceIndex())
                    .append(",\"sourceText\":").append(quote(value.sourceText()))
                    .append(",\"resource\":").append(quote(value.resource())).append('}');
        }
        json.append("],\"placements\":[");
        for (int index = 0; index < observed.vectors.size(); index++) {
            if (index != 0) { json.append(','); } var value = observed.vectors.get(index); var box = value.box();
            json.append("{\"resource\":").append(quote(value.resource())).append(",\"page\":").append(value.page())
                    .append(",\"box\":[").append(box.getX()).append(',').append(box.getY()).append(',').append(box.getWidth()).append(',').append(box.getHeight())
                    .append("],\"baseline\":[").append(value.baseline().getX()).append(',').append(value.baseline().getY()).append("]}");
        }
        json.append("],\"outlineLogicalText\":[").append(observed.logicalText.stream().map(InlineVectorsExample::quote).collect(java.util.stream.Collectors.joining(",")));
        return json.append("]}\n").toString();
    }
    private static String quote(String value) { return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + '"'; }
}
