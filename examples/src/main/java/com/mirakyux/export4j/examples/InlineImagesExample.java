// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.raster.RasterBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;

/** Generated, project-owned image fixtures; no external images or runtime resource fetching. */
public final class InlineImagesExample {
    private static final Path OUTPUT = Path.of("examples/build/inline-images");
    private static final ParagraphFormat FORMAT = new ParagraphFormat(12, 40, 0, 0, ParagraphAlignment.LEFT, 0, 8, false);
    private InlineImagesExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var faces = new ArrayList<com.mirakyux.export4j.font.FontFace>();
        for (var name : List.of("LiberationSans-Regular.ttf", "NotoNaskhArabic-Regular.ttf", "LXGWWenKaiTC-Regular.ttf")) {
            try (var input = InlineImagesExample.class.getResourceAsStream("/fonts/" + name)) { faces.add(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)); }
        }
        var engine = DocumentEngine.builder(FontGroup.strict(faces)).build();
        var png = resource(true); var jpeg = resource(false);
        var icon = new InlineImage(png, 28, 28, 3, "Transparent blue icon / 蓝色图标", "https://example.com/inline-image");
        var photo = new InlineImage(jpeg, 42, 28, 0, "Orange JPEG / 橙色图片");
        var blocks = new ArrayList<Block>();
        blocks.add(new Heading("title", "Inline images / 行内图像", 1, FORMAT));
        blocks.add(Paragraph.mixed("intro", List.of(text("Text before "), icon, text(" after; adjacent pictures "), photo, icon, text(" retain their dimensions.")), FORMAT));
        blocks.add(Paragraph.mixed("rtl", List.of(text("العربية "), icon, text(" لا 123 "), photo, text(" 中文 a\u0301")), FORMAT));
        var flow = new ArrayList<InlineContent>();
        for (int i = 1; i <= 18; i++) {
            flow.add(text(String.format(Locale.ROOT, "FLOW-%02d  图文混排 ", i))); flow.add(i % 2 == 0 ? photo : icon);
            flow.add(text(" keeps its source and baseline.\n"));
        }
        blocks.add(Paragraph.mixed("flow", flow, FORMAT));
        var cell = new CellFormat(12, 40, ParagraphAlignment.LEFT);
        var rows = new ArrayList<TableRow>();
        rows.add(new TableRow(List.of(TableCell.mixed(List.of(text("Header "), icon), cell), new TableCell("Description", cell))));
        var body = new ArrayList<InlineContent>();
        for (int i = 1; i <= 12; i++) { body.add(text(String.format(Locale.ROOT, "CELL-%02d ", i))); body.add(i % 2 == 0 ? photo : icon); body.add(text("\n")); }
        rows.add(new TableRow(List.of(TableCell.mixed(body, cell), new TableCell("A long cell continues across pages. Header pictures repeat with the header; body pictures occur once.", cell))));
        blocks.add(new Table("table", List.of(new ColumnWidth.Auto(100, 200), new ColumnWidth.Fraction(1)), rows, 1,
                new TableFormat(PageMargins.uniform(4), 0, 0, true)));
        var document = new Document(new DocumentMetadata("Inline image verification", "mirakyux"),
                new PageSettings(new PageSize(440, 480), PageMargins.uniform(28)), blocks);
        var result = engine.layout(document);
        try (var output = Files.newOutputStream(OUTPUT.resolve("mixed.pdf"))) { new PdfBackend().write(result.display(), output); }
        try (var output = Files.newOutputStream(OUTPUT.resolve("mixed.docx"))) { new DocxAdapter().write(document, output); }
        try (var input = Files.newInputStream(OUTPUT.resolve("mixed.docx"))) {
            var imported = new DocxAdapter().read(input, DocxAdapter.Policy.STRICT);
            if (!imported.diagnostics().isEmpty()) { throw new IOException("unexpected DOCX loss"); }
            var section = imported.document().sections().getFirst();
            try (var output = Files.newOutputStream(OUTPUT.resolve("roundtrip.pdf"))) {
                engine.write(new Document(imported.document().metadata(), section.pageSettings(), section.blocks()), new PdfBackend(), output);
            }
        }
        // Explicit static preview: the caller intentionally omits interactions for static backends.
        var preview = new DisplayDocument(result.display().metadata(), result.display().pages());
        try (var output = Files.newOutputStream(OUTPUT.resolve("preview.svg"))) { new SvgBackend().write(preview, output); }
        try (var output = Files.newOutputStream(OUTPUT.resolve("preview.zip"))) { new RasterBackend(144).write(preview, output); }
        var json = new StringBuilder("{\"pages\":" + preview.pages().size() + ",\"images\":[");
        boolean first = true;
        for (int page = 0; page < preview.pages().size(); page++) {
            for (var command : preview.pages().get(page).commands()) {
                if (command instanceof DrawCommand.Image image) {
                    if (!first) { json.append(','); } first = false; var b = image.bounds();
                    json.append(String.format(Locale.ROOT, "{\"page\":%d,\"x\":%.8f,\"y\":%.8f,\"width\":%.8f,\"height\":%.8f,\"pixels\":[%d,%d]}",
                            page, b.x(), b.y(), b.width(), b.height(), image.image().width(), image.image().height()));
                }
            }
        }
        json.append("],\"links\":").append(result.display().extras().externalLinks().size()).append('}');
        Files.writeString(OUTPUT.resolve("geometry.json"), json);
        System.out.println("Generated " + preview.pages().size() + " pages in " + OUTPUT);
    }
    private static InlineText text(String value) { return new InlineText(value, InlineStyle.of(12)); }
    private static ImageResource resource(boolean transparent) throws IOException {
        var image = new BufferedImage(90, 60, transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(transparent ? new Color(25, 100, 190, 0) : new Color(240, 155, 60)); graphics.fillRect(0, 0, 90, 60);
            graphics.setColor(transparent ? new Color(30, 110, 200, 200) : new Color(175, 70, 25)); graphics.fillOval(12, 4, 66, 52);
            graphics.setColor(new Color(255, 255, 255, 235)); graphics.fillRect(34, 15, 22, 30);
        } finally { graphics.dispose(); }
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, transparent ? "png" : "jpeg", bytes); image.flush();
        return ImageResource.load(bytes.toByteArray());
    }
}
