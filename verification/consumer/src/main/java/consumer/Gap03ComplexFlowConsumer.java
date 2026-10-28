// SPDX-License-Identifier: Apache-2.0
package consumer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.raster.RasterBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.ContourFloatText;
import com.mirakyux.export4j.components.ContinuousColumns;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.FlowColumn;
import com.mirakyux.export4j.components.GridTable;
import com.mirakyux.export4j.components.OpenTypeText;
import com.mirakyux.export4j.components.SemanticTable;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.text.ParagraphStyle;

/** Exercises only published coordinates, including recursive semantics in unequal columns. */
public final class Gap03ComplexFlowConsumer {
    private Gap03ComplexFlowConsumer() { }
    public static void main(String[] arguments) throws Exception {
        var output = Path.of(System.getProperty("consumer.outputDirectory", "build/output")).resolve("gap03-complex-flows");
        Files.createDirectories(output);
        var font = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Regular.ttf")));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(font))).build();
        var style = InlineStyle.of(10);
        var cell = CellFormat.builder().fontSize(10).lineHeight(16).build();
        var paragraph = Paragraph.mixed("recursive-leaf", List.of(new InlineText(tokens("R", 36), style, "https://example.com/recursive"),
                new InlineText(" LEAF-PAGE=", style), new InlineField(InlineField.Kind.PAGE, "", 15, style)),
                ParagraphFormat.builder().fontSize(10).lineHeight(16).build());
        var deepest = Table.builder("recursive-deepest").cellFormat(cell).format(value -> value.padding(1).allowRowSplit(true))
                .row(TableCell.builder().blocks(List.of(paragraph)).build()).build();
        var nested = Table.builder("recursive-inner").cellFormat(cell).format(value -> value.padding(1).allowRowSplit(true))
                .row(TableCell.builder().blocks(List.of(deepest)).build()).build();
        var root = Table.builder("recursive-root").cellFormat(cell).format(value -> value.padding(1).allowRowSplit(true))
                .headerCells(TableCell.mixed(List.of(new InlineText("PAGE=", style), new InlineField(InlineField.Kind.PAGE, "", 12, style),
                        new InlineText(" OF=", style), new InlineField(InlineField.Kind.PAGES, "", 12, style)), cell))
                .row(TableCell.builder().blocks(List.of(nested)).build()).row("RECURSIVE-END").build();
        write(engine, output, "recursive", ContinuousColumns.builder(new SemanticTable(root)).columnWeights(List.of(1.0, 2.0)).gap(12).build());
        var grid = GridTable.builder().columns(1, 1).headerRows(1).allowRowSplit(true)
                .defaults(value -> value.padding(2).textStyle(ParagraphStyle.builder().fontSize(10).lineHeight(16).build()))
                .row(row -> row.cells("GRID-H-A", "GRID-H-B"))
                .row(row -> row.cell(FlowColumn.builder().semanticTable(root).text(tokens("G", 16), value -> value.fontSize(10).lineHeight(16)).build(),
                        value -> value.rowSpan(2)).cell("GRID-SIDE-A"))
                .row(row -> row.cell("GRID-SIDE-B")).row(row -> row.cells("GRID-END", "GRID-AFTER")).build();
        write(engine, output, "grid", ContinuousColumns.builder(grid).columnWeights(List.of(1.0, 2.0)).gap(12).build());
        var triangle = new VectorPath(List.of(new VectorPath.MoveTo(new Point(0, 0)), new VectorPath.LineTo(new Point(110, 0)),
                new VectorPath.CurveTo(new Point(110, 90), new Point(60, 180), new Point(0, 250)), new VectorPath.Close()));
        Component graphic = context -> new ComponentLayout(context.width(), context.height(), 0,
                List.of(new DrawCommand.FillRect(new Rect(0, 0, context.width(), context.height()), new RgbColor(.13, .47, .67))), List.of());
        var anchored = new ContourFloatText.Floating(graphic, 0, 110, 250, triangle, 4, new ContourFloatText.AfterText(0, 0));
        var text = new OpenTypeText(tokens("F", 64), ParagraphStyle.builder().fontSize(11).lineHeight(20).build(), RgbColor.BLACK);
        write(engine, output, "contour", new ContourFloatText(text, List.of(anchored)));
        var bitmap = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 2; y++) { for (int x = 0; x < 2; x++) { bitmap.setRGB(x, y, 0x2277aa); } }
        var encoded = new java.io.ByteArrayOutputStream();
        try { if (!javax.imageio.ImageIO.write(bitmap, "png", encoded)) { throw new IllegalStateException("PNG writer missing"); } }
        finally { bitmap.flush(); }
        var image = new com.mirakyux.export4j.model.InlineImage(com.mirakyux.export4j.model.ImageResource.load(encoded.toByteArray()),
                80, 8, 0, "float image", "https://example.com/float-image");
        var floatingTable = Table.builder("floating-semantics").cellFormat(cell).format(value -> value.padding(1).allowRowSplit(true))
                .headerCells(TableCell.mixed(List.of(new InlineText("PAGE=", style), new InlineField(InlineField.Kind.PAGE, "", 12, style),
                        new InlineText(" OF=", style), new InlineField(InlineField.Kind.PAGES, "", 12, style)), cell))
                .row(TableCell.mixed(List.of(new InlineText(tokens("S", 18), style, "https://example.com/float-source"), image), cell)).build();
        var floatRect = new VectorPath(List.of(new VectorPath.MoveTo(new Point(0, 0)), new VectorPath.LineTo(new Point(120, 0)),
                new VectorPath.LineTo(new Point(120, 360)), new VectorPath.LineTo(new Point(0, 360)), new VectorPath.Close()));
        var semanticFloat = new ContourFloatText.Floating(new SemanticTable(floatingTable), 0, 120, 360, floatRect, 5,
                new ContourFloatText.AtRegion(0, 20));
        write(engine, output, "semantic-float", new ContourFloatText(new OpenTypeText(tokens("B", 50), text.style(), RgbColor.BLACK), List.of(semanticFloat)));
    }
    private static String tokens(String prefix, int count) {
        return IntStream.rangeClosed(1, count).mapToObj(index -> String.format(Locale.ROOT, "%s%03d alpha beta ", prefix, index)).collect(Collectors.joining());
    }
    private static void write(DocumentEngine engine, Path output, String name, Component component) throws Exception {
        var document = Documents.builder().pageSettings(page -> page.size(400, 220).margins(20)).component("host-" + name, component).build();
        var result = engine.export(document).backend(new PdfBackend()).to(output.resolve(name + ".pdf"));
        if (!result.diagnostics().isEmpty() || result.display().pages().size() < 2) { throw new IllegalStateException("complex flow did not continue cleanly"); }
        // Static drawing sidecar; PDF retains the document's links and final fields.
        var drawing = new com.mirakyux.export4j.graphics.DisplayDocument(document.metadata(), result.display().pages());
        try (var target = Files.newOutputStream(output.resolve(name + ".svg"))) { new SvgBackend().write(drawing, target); }
        int index = 1;
        for (var page : result.display().pages()) {
            try (var target = Files.newOutputStream(output.resolve(name + "-" + index++ + ".png"))) { new RasterBackend(144).writePage(page, target); }
        }
    }
}
