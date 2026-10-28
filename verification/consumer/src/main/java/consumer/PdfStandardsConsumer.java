// SPDX-License-Identifier: Apache-2.0
package consumer;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import com.mirakyux.export4j.backend.pdf.PdfAccessibility;
import com.mirakyux.export4j.backend.pdf.PdfArchival;
import com.mirakyux.export4j.backend.pdf.PdfArchivalConverter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.pdf.PdfLimits;
import com.mirakyux.export4j.backend.pdf.PdfOptions;
import com.mirakyux.export4j.backend.pdf.PdfTaggedDocument;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.AccessibilityTree;
import com.mirakyux.export4j.graphics.AccessibilityTree.Cell;
import com.mirakyux.export4j.graphics.AccessibilityTree.CommandRange;
import com.mirakyux.export4j.graphics.AccessibilityTree.Node;
import com.mirakyux.export4j.graphics.AccessibilityTree.Role;
import com.mirakyux.export4j.graphics.AccessibilityTree.Scope;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.text.GlyphRun;
import com.mirakyux.export4j.text.PositionedGlyph;

/** Public-API fixtures for independently validated archive editions and PDF/UA semantic order. */
public final class PdfStandardsConsumer {
    private PdfStandardsConsumer() { }
    public static void main(String[] args) throws Exception {
        Path output = Path.of(System.getProperty("consumer.outputDirectory")).resolve("pdf-standards"); Files.createDirectories(output);
        var font = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Regular.ttf")));
        var first = new DisplayListBuilder(PageSize.A4);
        text(first, font, "Accessible export", 40, 55, 20); // 0
        text(first, font, "Reading order follows meaning.", 40, 90, 12); // 1
        text(first, font, "1.", 40, 120, 12); // 2
        text(first, font, "Explicit list body", 65, 120, 12); // 3
        text(first, font, "Inventory", 40, 165, 16); // 4
        text(first, font, "Item", 40, 195, 12); // 5
        text(first, font, "Quantity", 240, 195, 12); // 6
        text(first, font, "Pencils", 40, 220, 12); // 7
        text(first, font, "4", 240, 220, 12); // 8
        first.fillRect(new Rect(35, 185, 290, 1), RgbColor.BLACK); // 9 artifact
        var second = new DisplayListBuilder(PageSize.A4);
        text(second, font, "Paper", 40, 55, 12); // 0
        text(second, font, "8", 240, 55, 12); // 1
        byte[] pixels = new byte[12 * 12 * 3]; for (int i = 0; i < pixels.length; i += 3) { pixels[i] = (byte) 30; pixels[i + 1] = (byte) 90; pixels[i + 2] = (byte) 180; }
        second.drawImage(new RgbImage(12, 12, pixels), new Rect(40, 95, 48, 48)); // 2
        text(second, font, "End of accessible document.", 40, 180, 12); // 3
        var title = new DocumentMetadata("Accessible inventory", "Export4j");
        var source = new DisplayDocument(title, List.of(first.build(), second.build()));
        Path plain = output.resolve("source.pdf"); try (var stream = Files.newOutputStream(plain)) { new PdfBackend().write(source, stream); }
        var table = new Node("inventory", Role.TABLE, List.of(), List.of(
                new Node("headers", Role.TR, List.of(), List.of(cell("item", Role.TH, Scope.COLUMN, List.of(), 0, 5), cell("quantity", Role.TH, Scope.COLUMN, List.of(), 0, 6))),
                new Node("row-one", Role.TR, List.of(), List.of(cell("pencils", Role.TD, Scope.NONE, List.of("item"), 0, 7), cell("four", Role.TD, Scope.NONE, List.of("quantity"), 0, 8))),
                new Node("row-two", Role.TR, List.of(), List.of(cell("paper", Role.TD, Scope.NONE, List.of("item"), 1, 0), cell("eight", Role.TD, Scope.NONE, List.of("quantity"), 1, 1)))));
        var tree = new AccessibilityTree("en-US", List.of(node("title", Role.H1, 0, 0), node("intro", Role.P, 0, 1),
                new Node("steps", Role.L, List.of(), List.of(new Node("step-one", Role.LI, List.of(), List.of(node("label", Role.LBL, 0, 2), node("body", Role.LBODY, 0, 3))))),
                node("inventory-title", Role.H2, 0, 4), table,
                new Node("illustration", Role.FIGURE, "Blue inventory marker", "", Cell.NONE, List.of(range(1, 2)), List.of()), node("end", Role.P, 1, 3)), List.of(range(0, 9)));
        var tagged = new DisplayDocument(title, source.pages(), DocumentExtras.EMPTY, tree);
        try (var stream = Files.newOutputStream(output.resolve("ua1.pdf"))) { new PdfBackend(PdfLimits.DEFAULT, PdfOptions.DEFAULT.withAccessibility(PdfAccessibility.PDF_UA_1)).write(tagged, stream); }
        var inspected = PdfTaggedDocument.inspect(output.resolve("ua1.pdf"), PdfTaggedDocument.Limits.DEFAULT);
        require(inspected.markedContent() == 13 && inspected.elements().size() == 1, "existing tagged content inspection changed");
        var retitled = PdfTaggedDocument.metadata(output.resolve("ua1.pdf"), output.resolve("ua1-retitled.pdf"), "Revised accessible inventory", "en-GB", PdfTaggedDocument.Limits.DEFAULT);
        require(retitled.language().equals("en-GB") && retitled.title().equals("Revised accessible inventory") && retitled.elements().equals(inspected.elements()), "tagged metadata update changed structure");
        sourceModel(output, font);
        byte[] icc = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
        var alphaPage = new DisplayListBuilder(new PageSize(120, 80)).drawImage(new RgbImage(1, 1, new byte[] {30, 90, (byte) 180}, new byte[] {(byte) 128}), new Rect(20, 20, 60, 40)).build();
        try (var stream = Files.newOutputStream(output.resolve("a2b-alpha.pdf"))) { new PdfBackend(PdfLimits.DEFAULT, PdfOptions.DEFAULT.withArchival(new PdfArchival(icc, font.data()))).write(new DisplayDocument(title, List.of(alphaPage)), stream); }
        for (var profile : PdfArchival.Profile.values()) {
            String name = profile.name().toLowerCase(java.util.Locale.ROOT);
            var attachments = profile == PdfArchival.Profile.A3B || profile == PdfArchival.Profile.A4F
                    ? List.of(new PdfArchival.AssociatedFile("inventory.csv", "text/csv", "Inventory source data", "Data", Instant.parse("2026-01-01T00:00:00Z"), "Item,Quantity\nPencils,4\nPaper,8\n".getBytes(StandardCharsets.UTF_8))) : List.<PdfArchival.AssociatedFile>of();
            var archival = new PdfArchival(profile, icc, font.data(), attachments);
            try (var stream = Files.newOutputStream(output.resolve(name + ".pdf"))) { new PdfBackend(PdfLimits.DEFAULT, PdfOptions.DEFAULT.withArchival(archival)).write(source, stream); }
            var options = PdfArchivalConverter.Options.defaults(archival, title);
            Path converted = output.resolve(name + "-converted.pdf");
            Files.writeString(converted, "OLD TARGET");
            try { PdfArchivalConverter.convert(plain, converted, options, ProcessingPolicy.STRICT); throw new AssertionError("strict archival conversion accepted rasterization loss"); }
            catch (ProcessingException expected) { require(Files.readString(converted).equals("OLD TARGET"), "strict conversion replaced old target"); }
            var policy = ProcessingPolicy.builder().feature("PDF_ARCHIVAL_RASTERIZATION", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
            var report = PdfArchivalConverter.convert(plain, converted, options, policy);
            require(report.warnings().size() == 1 && report.losses().size() == 1, "conversion did not report actual rasterization loss");
        }
        Files.writeString(output.resolve("expected-reading-order.txt"), "Accessible export\nReading order follows meaning.\n1.\nExplicit list body\nInventory\nItem\nQuantity\nPencils\n4\nPaper\n8\nBlue inventory marker\nEnd of accessible document.\n");
        PdfPadesConsumer.verify(output);
    }
    private static void text(DisplayListBuilder builder, FontFace font, String value, double x, double y, double size) {
        var glyphs = new ArrayList<PositionedGlyph>(); value.codePoints().forEach(cp -> { var metrics = font.glyph(cp).orElseThrow(); glyphs.add(new PositionedGlyph(cp, metrics.glyphId(), metrics.advance() * size)); });
        builder.drawGlyphRun(new GlyphRun(font.data(), size, glyphs), new Point(x, y), RgbColor.BLACK);
    }
    private static CommandRange range(int page, int command) { return new CommandRange(page, command, command + 1); }
    private static Node node(String id, Role role, int page, int command) { return new Node(id, role, List.of(range(page, command)), List.of()); }
    private static Node cell(String id, Role role, Scope scope, List<String> headers, int page, int command) { return new Node(id, role, "", "", new Cell(scope, 1, 1, headers), List.of(range(page, command)), List.of()); }
    private static void require(boolean value, String message) { if (!value) { throw new AssertionError(message); } }
    private static void sourceModel(Path output, FontFace font) throws Exception {
        var rows = new ArrayList<com.mirakyux.export4j.model.TableRow>(); var cell = com.mirakyux.export4j.model.CellFormat.of(12);
        rows.add(new com.mirakyux.export4j.model.TableRow(List.of(new com.mirakyux.export4j.model.TableCell("Name", cell), new com.mirakyux.export4j.model.TableCell("Count", cell))));
        for (int i = 0; i < 12; i++) { rows.add(new com.mirakyux.export4j.model.TableRow(List.of(new com.mirakyux.export4j.model.TableCell("Item " + i, cell), new com.mirakyux.export4j.model.TableCell("Count " + i, cell)))); }
        var table = new com.mirakyux.export4j.model.Table("model-table", List.of(new com.mirakyux.export4j.model.ColumnWidth.Fraction(1), new com.mirakyux.export4j.model.ColumnWidth.Fraction(1)), rows, 1, com.mirakyux.export4j.model.TableFormat.DEFAULT);
        var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB); for (int y = 0; y < 2; y++) { for (int x = 0; x < 2; x++) { image.setRGB(x, y, 0x1e5ab4); } }
        var encoded = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(image, "png", encoded); image.flush();
        var format = com.mirakyux.export4j.model.ParagraphFormat.of(12);
        var model = new com.mirakyux.export4j.model.Document(new DocumentMetadata("Accessible model inventory", "Export4j"),
                new com.mirakyux.export4j.model.PageSettings(new PageSize(320, 240), com.mirakyux.export4j.model.PageMargins.uniform(20)), List.of(
                new com.mirakyux.export4j.model.Heading("model-title", "Model heading", 1, format),
                new com.mirakyux.export4j.model.Paragraph("model-body", "Model reading order", format),
                new com.mirakyux.export4j.model.BulletItem("model-bullet", "Bullet body", "-", 1, format),
                new com.mirakyux.export4j.model.NumberedItem("model-number", "model-list", "Number body", 0, format, 1),
                com.mirakyux.export4j.model.Paragraph.mixed("model-image", List.of(new com.mirakyux.export4j.model.InlineImage(com.mirakyux.export4j.model.ImageResource.load(encoded.toByteArray()), 18, 12, 0, "Blue model marker")), format), table));
        var result = com.mirakyux.export4j.components.DocumentEngine.builder(com.mirakyux.export4j.font.FontGroup.strict(List.of(font))).build().layout(model);
        var display = com.mirakyux.export4j.layout.core.DocumentPainter.paintAccessible(model, result.body(), "en-US");
        try (var stream = Files.newOutputStream(output.resolve("ua1-model.pdf"))) { new PdfBackend(PdfLimits.DEFAULT, PdfOptions.DEFAULT.withAccessibility(PdfAccessibility.PDF_UA_1)).write(display, stream); }
        var report = PdfTaggedDocument.inspect(output.resolve("ua1-model.pdf"), PdfTaggedDocument.Limits.DEFAULT); require(report.pages() > 1 && report.markedContent() > 20, "semantic source bridge lost pagination or logical content");
    }
}
