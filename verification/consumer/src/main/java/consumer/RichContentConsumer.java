// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.docx.DocxFontFamilies;
import com.mirakyux.export4j.adapter.web.ContentImporter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.math.MathContent;
import com.mirakyux.export4j.math.MathTextFonts;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import java.io.IOException;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import javax.imageio.ImageIO;

/** Generic offline published-API consumer. Resources and presentation come only from explicit input.
 * Usage: RichContentConsumer settings.properties source.md output-directory.
 * Font values are semicolon-separated local paths relative to the properties file. Images use
 * image.count and image.N.key/path/width. This fixture owns no source parser or equation converter.
 */
public final class RichContentConsumer {
    private RichContentConsumer() { }

    public static void main(String[] args) throws Exception {
        boolean smoke = args.length == 0;
        if (smoke) { args = smokeInputs(); }
        if (args.length != 3) { throw new IllegalArgumentException("Expected settings.properties source.md output-directory"); }
        var settings = Path.of(args[0]).toAbsolutePath();
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(settings, StandardCharsets.UTF_8)) { properties.load(reader); }
        var base = settings.getParent();
        var output = Files.createDirectories(Path.of(args[2]));
        verifyProviders();

        var page = new PageSettings(new PageSize(number(properties, "page.width", 595.28), number(properties, "page.height", 841.89)),
                PageMargins.uniform(number(properties, "page.margin", 36)));
        double size = number(properties, "body.size", 11);
        double cellSize = number(properties, "cell.size", 8);
        var defaults = ContentOptions.builder().format(ContentOptions.Format.MARKDOWN).formulas(true).rawHtml(true)
                .fontSize(size).lineHeight(number(properties, "body.lineHeight", 16.5)).spaceAfter(6)
                .cellFormat(new CellFormat(cellSize, number(properties, "cell.lineHeight", 12), ParagraphAlignment.LEFT))
                .tableFormat(new TableFormat(PageMargins.uniform(number(properties, "cell.padding", 3)), 6, 6, true)).build();
        var content = MathContent.builder().defaults(defaults).pageSettings(page)
                .metadata(new DocumentMetadata(properties.getProperty("title", "Rich content"), "Export4j"))
                .fallbacks(List.of(MathContent.Fallback.IMAGE, MathContent.Fallback.TEXT))
                .fontFamily("Explicit Serif", "", "bold", "italic", "boldItalic");
        var formulaFonts = new ArrayList<byte[]>();
        for (var path : required(properties, "font.regular").split(";")) {
            if (path.isBlank()) { throw new IllegalArgumentException("Empty explicit formula font path"); }
            formulaFonts.add(Files.readAllBytes(base.resolve(path)));
        }
        content.textFonts(MathTextFonts.of(formulaFonts.toArray(byte[][]::new)));
        int images = Integer.parseInt(properties.getProperty("image.count", "0"));
        for (int index = 0; index < images; index++) {
            String prefix = "image." + index + ".";
            var resource = ImageResource.load(Files.readAllBytes(base.resolve(required(properties, prefix + "path"))));
            content.image(required(properties, prefix + "key"), InlineImage.atWidth(resource,
                    number(properties, prefix + "width", page.contentWidth()), properties.getProperty(prefix + "alt", "")));
        }
        var compiled = content.build().read(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8));
        if (smoke && (compiled.formulas().size() != 4 || compiled.docx().equations().size() != 4
                || !compiled.degradations().isEmpty())) {
            throw new IOException("Rich-content smoke lost a formula or changed its representation");
        }
        var engine = DocumentEngine.builder(fonts(properties, base, "regular"))
                .fontRole("bold", fonts(properties, base, "bold"))
                .fontRole("italic", fonts(properties, base, "italic"))
                .fontRole("boldItalic", fonts(properties, base, "boldItalic")).build();
        var pdf = output.resolve("document.pdf");
        var layout = engine.write(compiled.document(), new PdfBackend(), pdf);
        var adapter = DocxAdapter.builder().defaultFonts(new DocxFontFamilies(required(properties, "docx.latin"),
                required(properties, "docx.eastAsia"))).lineSpacing(DocxAdapter.LineSpacing.OBJECTS_AT_LEAST).build();
        var docx = output.resolve("document.docx");
        try (var stream = Files.newOutputStream(docx)) { adapter.writeMath(compiled.docx(), stream); }
        var report = report(compiled, layout.display().pages().size(), pdf, docx);
        Files.writeString(output.resolve("semantic-report.json"), report, StandardCharsets.UTF_8);
        System.out.println("Rich source compiled through public APIs: " + layout.display().pages().size()
                + " PDF pages, " + compiled.formulas().size() + " formulas, " + compiled.degradations().size() + " explicit representation changes");
    }

    private static String[] smokeInputs() throws IOException {
        var output = Files.createDirectories(Path.of(System.getProperty("consumer.outputDirectory", "build/output")).resolve("rich-content"));
        var input = Files.createDirectories(output.resolve("input"));
        var settings = new Properties();
        var fonts = Path.of("../../assets/fonts").toAbsolutePath().normalize();
        for (var role : List.of("regular", "bold", "italic", "boldItalic")) {
            String variant = switch (role) {
                case "regular" -> "Regular";
                case "bold" -> "Bold";
                case "italic" -> "Italic";
                default -> "BoldItalic";
            };
            settings.setProperty("font." + role, fonts.resolve("LiberationSerif-" + variant + ".ttf").toString());
        }
        settings.setProperty("docx.latin", "Liberation Serif");
        settings.setProperty("docx.eastAsia", "Liberation Serif");
        settings.setProperty("title", "Rich-content published API smoke");
        settings.setProperty("image.count", "1");
        settings.setProperty("image.0.key", "sample:badge");
        settings.setProperty("image.0.path", "badge.png");
        settings.setProperty("image.0.width", "18");
        var image = new BufferedImage(12, 12, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 12; y++) { for (int x = 0; x < 12; x++) { image.setRGB(x, y, 0x224466); } }
        try {
            if (!ImageIO.write(image, "png", input.resolve("badge.png").toFile())) { throw new IOException("PNG writer unavailable"); }
        } finally { image.flush(); }
        var properties = input.resolve("settings.properties");
        try (var writer = Files.newBufferedWriter(properties, StandardCharsets.UTF_8)) { settings.store(writer, "Explicit public fixture resources"); }
        var source = input.resolve("source.md");
        Files.writeString(source, """
                # Rich heading $x^2$ ![badge](sample:badge)

                A bounded equation $\\frac{a+b}{c}$ and literal `code $z^2$`.

                - Mixed list $\\sqrt{x}$ ![badge](sample:badge)

                ```java
                  first  column
                    second column
                ```

                <table><tr><td rowspan="2">Owner</td><td colspan="2">Cell $x_i$</td></tr><tr><td>Left</td><td>Right</td></tr></table>
                """, StandardCharsets.UTF_8);
        return new String[] { properties.toString(), source.toString(), output.toString() };
    }

    private static FontGroup fonts(Properties properties, Path base, String role) throws IOException {
        var faces = new ArrayList<FontFace>();
        for (var path : required(properties, "font." + role).split(";")) {
            if (path.isBlank()) { throw new IllegalArgumentException("Empty explicit font path"); }
            faces.add(TrueTypeFontLoader.load(base.resolve(path)));
        }
        return FontGroup.strict(faces);
    }

    private static void verifyProviders() throws IOException {
        for (var type : List.of(MathContent.class, ContentImporter.class, DocumentEngine.class, DocxAdapter.class)) {
            var source = type.getProtectionDomain().getCodeSource().getLocation();
            if (!source.getProtocol().equals("file") || !source.getPath().endsWith(".jar")) {
                throw new IOException("Consumer must load published JARs");
            }
            var resources = java.util.Collections.list(type.getClassLoader().getResources(type.getName().replace('.', '/') + ".class"));
            if (resources.size() != 1) { throw new IOException("Consumer API has duplicate class providers"); }
        }
    }

    private static String report(MathContent.Result compiled, int pages, Path pdf, Path docx) throws Exception {
        var document = compiled.document();
        var tables = new ArrayList<Table>();
        var text = new StringBuilder();
        int[] objects = new int[2];
        SemanticTree.walk(document.blocks(), visit -> {
            if (visit.block() instanceof Table table) {
                tables.add(table);
                for (var row : table.rows()) {
                    for (var cell : row.cells()) {
                        if (!cell.hasBlocks()) {
                            text.append(cell.text().replace("\ufffc", "")).append('\n');
                            objects[0] += cell.vectors().size(); objects[1] += cell.images().size();
                        }
                    }
                }
            } else {
                var paragraph = paragraph(visit.block());
                appendParagraphText(text, paragraph);
                objects[0] += paragraph.vectors().size(); objects[1] += paragraph.images().size();
            }
        });
        var result = new StringBuilder("{\n  \"pages\": " + pages + ",\n  \"tables\": [");
        int merges = 0, cells = 0;
        for (int tableIndex = 0; tableIndex < tables.size(); tableIndex++) {
            if (tableIndex > 0) { result.append(','); }
            var table = tables.get(tableIndex);
            result.append("\n    {\"rows\":").append(table.rows().size()).append(",\"columns\":").append(table.columns().size()).append(",\"cells\":[");
            boolean first = true;
            for (var row : table.grid().anchorRows()) {
                for (var anchor : row) {
                    var cell = anchor.cell(); cells++;
                    if (cell.columnSpan() > 1 || cell.rowSpan() > 1) { merges++; }
                    if (!first) { result.append(','); } first = false;
                    result.append("{\"row\":").append(anchor.rowIndex()).append(",\"column\":").append(anchor.columnIndex())
                            .append(",\"rowSpan\":").append(cell.rowSpan()).append(",\"columnSpan\":").append(cell.columnSpan())
                            .append(",\"text\":").append(json(cellText(cell))).append('}');
                }
            }
            result.append("]}");
        }
        result.append("\n  ],\n  \"cells\":").append(cells).append(",\n  \"mergedCells\":").append(merges)
                .append(",\n  \"vectors\":").append(objects[0]).append(",\n  \"images\":").append(objects[1])
                .append(",\n  \"text\":").append(json(text.toString())).append(",\n  \"nativeEquations\":").append(compiled.docx().equations().size())
                .append(",\n  \"formulas\":[");
        for (int index = 0; index < compiled.formulas().size(); index++) {
            if (index > 0) { result.append(','); }
            var formula = compiled.formulas().get(index);
            result.append("{\"index\":").append(formula.index()).append(",\"display\":").append(formula.display())
                    .append(",\"tex\":").append(json(formula.tex())).append('}');
        }
        result.append("],\n  \"degradations\":[");
        for (int index = 0; index < compiled.degradations().size(); index++) {
            if (index > 0) { result.append(','); }
            var change = compiled.degradations().get(index);
            result.append("{\"formulaIndex\":").append(change.formulaIndex()).append(",\"target\":").append(json(change.target().name()))
                    .append(",\"representation\":").append(json(change.representation().name())).append(",\"reason\":").append(json(change.reason().name())).append('}');
        }
        return result.append("],\n  \"pdfSha256\":").append(json(sha(pdf))).append(",\n  \"docxSha256\":").append(json(sha(docx))).append("\n}\n").toString();
    }

    private static String cellText(TableCell cell) {
        var result = new StringBuilder();
        if (cell.hasBlocks()) {
            SemanticTree.walk(cell.blocks(), visit -> {
                if (!(visit.block() instanceof Table)) { appendParagraphText(result, paragraph(visit.block())); }
            });
        } else { result.append(cell.text().replace("\ufffc", "")); }
        return result.toString();
    }

    private static Paragraph paragraph(Block block) {
        if (block instanceof Paragraph paragraph) { return paragraph; }
        if (block instanceof Heading heading) { return heading.paragraph(); }
        if (block instanceof NumberedItem item) { return item.paragraph(); }
        if (block instanceof BulletItem item) { return item.paragraph(); }
        throw new IllegalArgumentException("Unexpected source block in consumer: " + block.getClass().getSimpleName());
    }

    private static void appendParagraphText(StringBuilder result, Paragraph paragraph) {
        for (var item : paragraph.content()) {
            if (item instanceof InlineText text) { result.append(text.text()); }
        }
        result.append('\n');
    }

    private static double number(Properties settings, String key, double fallback) {
        return Double.parseDouble(settings.getProperty(key, Double.toString(fallback)));
    }

    private static String required(Properties settings, String key) {
        var value = settings.getProperty(key);
        if (value == null || value.isBlank()) { throw new IllegalArgumentException("Missing property: " + key); }
        return value;
    }

    private static String sha(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static String json(String value) {
        var encoded = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"' -> encoded.append("\\\"");
                case '\\' -> encoded.append("\\\\");
                case '\n' -> encoded.append("\\n");
                case '\r' -> encoded.append("\\r");
                case '\t' -> encoded.append("\\t");
                default -> { if (c < 0x20) { encoded.append(String.format("\\u%04x", (int) c)); } else { encoded.append(c); } }
            }
        }
        return encoded.append('"').toString();
    }
}
