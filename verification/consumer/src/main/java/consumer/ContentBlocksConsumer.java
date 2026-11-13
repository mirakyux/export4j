// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.adapter.web.ContentImporter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.math.TexMath;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
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
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/** Isolated published-API proof of equivalent Java/XML source authoring and explicit DOCX losses. */
public final class ContentBlocksConsumer {
    private static final String TITLE = "CONTENT-BLOCKS / source authoring";
    private static final String TEXT = "Plain text keeps literal ${secret}; formulas $x^2$ stay text here.";
    private static final String MARKDOWN = """
            Markdown **bold** with [documentation](https://export4j.mirakyux.com/en/).

            Formula enabled: $\\frac{a+b}{c}$.

            | Kind | Value |
            | --- | --- |
            | Enabled table | $\\sqrt{x}$ |
            """;
    private static final String HTML = """
            <p>LOCAL-SCOPE inherits bold role and geometry.</p>
            <p style="font-family:'Fixture Sans';color:#CC3300;font-size:13pt;line-height:36pt">CSS-OVERRIDE with <strong>HTML bold</strong> and <a href="https://example.com/content-blocks">passive link</a>.</p>
            <p><img src="sample:badge" alt="Content badge"/> Explicit image and HTML formula $x_1^2$.</p>
            """;
    private static final String RESTORED = "SCOPE-RESTORED uses global regular appearance.";
    private static final String TEXT_FORMULA = "PAGE-TWO / Text formula enabled: $\\frac{1}{2}$.";
    private static final String FORMULAS_OFF = "FORMULAS-OFF $\\frac{z}{2}$ remains literal.";
    private static final String TABLES_OFF = """
            TABLES-OFF preserves source syntax:

            | Disabled | Value |
            | --- | --- |
            | Literal | $x^9$ |
            """;
    private static final List<String> FORMULAS = List.of("\\frac{a+b}{c}", "\\sqrt{x}", "x_1^2", "\\frac{1}{2}");
    private static final List<String> LINKS = List.of("https://export4j.mirakyux.com/en/", "https://example.com/content-blocks");
    private static final PageSettings PAGE = new PageSettings(new PageSize(600, 780), PageMargins.uniform(36));
    private static final Map<String, Object> DATA = Map.of(
            "secret", "MUST-NOT-APPEAR", "whole", "Whole field keeps literal ${secret}.",
            "items", List.of(Map.of("text", "Row one literal ${secret}."), Map.of("text", "Row two literal ${secret}.")));

    private ContentBlocksConsumer() { }

    public static void main(String[] args) throws IOException {
        var output = Files.createDirectories(Path.of(System.getProperty("consumer.outputDirectory", "build/output")).resolve("content-blocks"));
        var assets = Path.of("../../assets/fonts");
        for (var type : List.of(DocumentTemplate.Source.class, ContentImporter.class, TexMath.class)) {
            var location = type.getProtectionDomain().getCodeSource().getLocation();
            require(location.getProtocol().equals("file") && location.getPath().endsWith(".jar"),
                    "source authoring APIs must load from published JARs");
            require(java.util.Collections.list(type.getClassLoader().getResources(type.getName().replace('.', '/') + ".class")).size() == 1,
                    "source authoring API has multiple providers");
        }
        var image = image();
        var importer = ContentImporter.builder().formulaProcessor(TexMath.processor())
                .image("sample:badge", image).fontFamily("Fixture Sans", "", "bold", "italic", "boldItalic").build();
        var javaDocument = javaTemplate(importer).render(DATA);
        var xml = xml();
        var xmlDocument = XmlTemplate.builder().contentParser(importer).build().compile(xml).render(DATA);
        require(javaDocument.equals(xmlDocument), "Java/XML model mismatch: " + mismatch(javaDocument, xmlDocument));
        var observed = inspect(javaDocument);
        require(observed.formulas.equals(FORMULAS), "formula occurrences changed: " + observed.formulas);
        require(observed.tables == 2 && observed.images == 1 && observed.links.equals(new LinkedHashSet<>(LINKS)),
                "table, image or passive-link inventory changed");
        require(observed.literalCount == 4 && !observed.text.toString().contains("MUST-NOT-APPEAR"),
                "source content was interpolated a second time");
        require(observed.colors.equals(Set.of(0x223344, 0x224466, 0xCC3300)), "source colors changed");
        verifyScope(observed.paragraphs);

        var regular = FontGroup.strict(List.of(TrueTypeFontLoader.load(assets.resolve("LiberationSans-Regular.ttf"))));
        var bold = FontGroup.strict(List.of(TrueTypeFontLoader.load(assets.resolve("LiberationSans-Bold.ttf"))));
        var engine = DocumentEngine.builder(regular).fontRole("bold", bold).build();
        var javaDisplay = engine.layout(javaDocument).display();
        var xmlDisplay = engine.layout(xmlDocument).display();
        require(javaDisplay.pages().size() == 2 && xmlDisplay.pages().size() == 2, "content specimen must have two pages");
        try (var stream = Files.newOutputStream(output.resolve("java.pdf"))) { new PdfBackend().write(javaDisplay, stream); }
        try (var stream = Files.newOutputStream(output.resolve("xml.pdf"))) { new PdfBackend().write(xmlDisplay, stream); }

        // Only inline vectors may degrade; every replacement must retain its TeX logical text.
        var policy = ProcessingPolicy.builder().feature("INLINE_VECTOR", ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DEGRADE).build();
        var prepared = DocumentConversion.from(javaDocument).policy(policy).prepare(new DocxAdapter().conversionTarget());
        var issues = prepared.preview().issues();
        require(issues.size() == 4 && issues.stream().allMatch(issue -> issue.feature().equals("INLINE_VECTOR")
                && issue.stage().equals("EXPORT") && issue.treatment() == ProcessingPolicy.Treatment.DEGRADE
                && issue.effects().equals(List.of("VECTOR_REPLACED_WITH_LOGICAL_TEXT"))), "unexpected DOCX degradation");
        var saved = prepared.to(output.resolve("content.docx"));
        require(saved.report().issues().equals(issues), "DOCX preview and actual report disagree");
        Files.writeString(output.resolve("source.xml"), xml);
        Files.write(output.resolve("badge.png"), image.resource().bytes());
        Files.writeString(output.resolve("result.json"), report(observed, issues.size()));
        System.out.println("Generated equivalent Java/XML content PDFs and explicitly degraded DOCX in " + output);
    }

    private static DocumentTemplate javaTemplate(ContentImporter parser) {
        var global = ContentOptions.builder().fontRole("").rgb(0x223344).fontSize(11).lineHeight(24).spaceAfter(6)
                .cellFormat(new CellFormat(11, 40, ParagraphAlignment.LEFT)).build().resolve(ContentOptions.defaults());
        var local = ContentOptions.builder().fontRole("bold").rgb(0x224466).fontSize(12).lineHeight(32)
                .firstLineIndent(8).subsequentLineIndent(4).spaceAfter(4)
                .format(ContentOptions.Format.HTML).formulas(true).build().resolve(global);
        var cellFormat = new CellFormat(11, 24, ParagraphAlignment.LEFT);
        var cellParagraph = new ParagraphFormat(11, 24, 0, 0, ParagraphAlignment.LEFT, 0, 0, false);
        var cellOptions = ContentOptions.builder().paragraphFormat(cellParagraph).cellFormat(cellFormat).build().resolve(global);
        var cell = TableCell.builder().format(cellFormat).blocks(List.of(new Paragraph("placeholder", "", cellParagraph))).build();
        var template = DocumentTemplate.builder().metadata(new DocumentMetadata("Content blocks authoring", "Export4j")).pageSettings(PAGE)
                .node(DocumentTemplate.Source.literal("title", TITLE, ContentOptions.builder().fontRole("bold")
                        .fontSize(20).lineHeight(28).spaceAfter(10).build().resolve(global), parser))
                .node(DocumentTemplate.Source.literal("plain", TEXT, global, parser))
                .node(DocumentTemplate.Source.literal("markdown", MARKDOWN, ContentOptions.builder().format(ContentOptions.Format.MARKDOWN)
                        .formulas(true).lineHeight(40).build().resolve(global), parser))
                .node(DocumentTemplate.Source.literal("html", HTML, local, parser))
                .node(DocumentTemplate.Source.literal("restored", RESTORED, global, parser))
                .node(DocumentTemplate.Source.literal("text-formula", TEXT_FORMULA, ContentOptions.builder().formulas(true)
                        .lineHeight(40).pageBreakBefore(true).build().resolve(global), parser))
                .node(DocumentTemplate.Source.literal("formulas-off", FORMULAS_OFF, ContentOptions.builder()
                        .format(ContentOptions.Format.MARKDOWN).formulas(false).build().resolve(global), parser))
                .node(DocumentTemplate.Source.literal("tables-off", TABLES_OFF, ContentOptions.builder()
                        .format(ContentOptions.Format.MARKDOWN).formulas(true).tables(false).build().resolve(global), parser))
                .node(DocumentTemplate.Source.value("whole", "whole", global, parser))
                .node(new DocumentTemplate.Rows("source-rows", "items", "item", List.of(new ColumnWidth.Fraction(1)),
                        List.of(), new TableRow(List.of(cell)), global.tableFormat()));
        return template.cellContents(List.of(new DocumentTemplate.CellContent(cell,
                List.of(DocumentTemplate.Source.value("row-source", "item.text", cellOptions, parser))))).build();
    }

    private static String xml() {
        return """
                <document version="1" title="Content blocks authoring" author="Export4j" role="" color="#223344"
                    size="11" line-height="24" space-after="6" cell-size="11" cell-line-height="40">
                  <page width="600" height="780" margin="36"/>
                  <content id="title" role="bold" size="20" line-height="28" space-after="10"><![CDATA[%s]]></content>
                  <content id="plain"><![CDATA[%s]]></content>
                  <content id="markdown" content-format="MARKDOWN" formulas="true" line-height="40"><![CDATA[%s]]></content>
                  <scope role="bold" color="#224466" size="12" line-height="32" first-indent="8" subsequent-indent="4" space-after="4"
                      content-format="HTML" formulas="true">
                    <content id="html"><![CDATA[%s]]></content>
                  </scope>
                  <content id="restored"><![CDATA[%s]]></content>
                  <content id="text-formula" formulas="true" line-height="40" page-break-before="true"><![CDATA[%s]]></content>
                  <content id="formulas-off" content-format="MARKDOWN" formulas="false"><![CDATA[%s]]></content>
                  <content id="tables-off" content-format="MARKDOWN" formulas="true" tables="false"><![CDATA[%s]]></content>
                  <content id="whole" source="whole"/>
                  <rows id="source-rows" items="items" as="item">
                    <columns><column weight="1"/></columns>
                    <row><cell size="11" line-height="24"><scope space-after="0"><content id="row-source" source="item.text"/></scope></cell></row>
                  </rows>
                </document>
                """.formatted(TITLE, TEXT, MARKDOWN, HTML, RESTORED, TEXT_FORMULA, FORMULAS_OFF, TABLES_OFF);
    }

    private static InlineImage image() throws IOException {
        var raster = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) { raster.setRGB(x, y, x < 8 ^ y < 8 ? 0x224466 : 0xE5EAF0); }
        }
        var bytes = new ByteArrayOutputStream();
        require(ImageIO.write(raster, "PNG", bytes), "PNG writer unavailable");
        raster.flush();
        return new InlineImage(ImageResource.load(bytes.toByteArray()), 24, 24, 0, "Content badge");
    }

    private static Observed inspect(Document document) {
        var result = new Observed();
        inspect(document.blocks(), result);
        String all = result.text.toString();
        for (int offset = 0; (offset = all.indexOf("literal ${secret}", offset)) >= 0; offset += "literal ${secret}".length()) {
            result.literalCount++;
        }
        return result;
    }

    private static void inspect(List<Block> blocks, Observed result) {
        for (var block : blocks) {
            if (block instanceof Paragraph paragraph) { inspect(paragraph, result); }
            else if (block instanceof Table table) {
                result.tables++;
                for (var row : table.rows()) {
                    for (var cell : row.cells()) {
                        if (cell.hasBlocks()) { inspect(cell.blocks(), result); }
                        else {
                            var f = cell.format();
                            inspect(new Paragraph("inventory", cell.text(), new ParagraphFormat(f.fontSize(), f.lineHeight(),
                                    0, 0, f.alignment(), 0, 0, false), cell.inlines(), cell.images(), cell.fields(), cell.vectors()), result);
                        }
                    }
                }
            } else { throw new IllegalStateException("unexpected specimen block " + block.getClass().getSimpleName()); }
        }
    }

    private static void inspect(Paragraph paragraph, Observed result) {
        result.paragraphs.add(paragraph);
        for (var part : paragraph.content()) {
            if (part instanceof InlineText text) {
                result.text.append(text.text()); result.colors.add(text.style().rgb());
                if (!text.hyperlink().isEmpty()) { result.links.add(text.hyperlink()); }
            } else if (part instanceof InlineVector vector) { result.formulas.add(vector.logicalText()); }
            else if (part instanceof InlineImage) { result.images++; }
        }
        result.text.append('\n');
    }

    private static void verifyScope(List<Paragraph> paragraphs) {
        var local = find(paragraphs, "LOCAL-SCOPE");
        require(local.format().fontSize() == 12 && local.format().lineHeight() == 32
                && local.format().firstLineIndent() == 8 && local.format().subsequentLineIndent() == 4
                && local.inlines().getFirst().style().fontRole().equals("bold")
                && local.inlines().getFirst().style().rgb() == 0x224466, "local HTML scope was lost");
        var css = find(paragraphs, "CSS-OVERRIDE");
        require(css.format().fontSize() == 13 && css.format().lineHeight() == 36
                && css.inlines().getFirst().style().rgb() == 0xCC3300, "explicit HTML CSS did not override defaults");
        var restored = find(paragraphs, "SCOPE-RESTORED");
        require(restored.format().fontSize() == 11 && restored.format().lineHeight() == 24
                && restored.format().firstLineIndent() == 0 && restored.format().subsequentLineIndent() == 0
                && restored.inlines().getFirst().style().fontRole().isEmpty()
                && restored.inlines().getFirst().style().rgb() == 0x223344, "local scope leaked to its sibling");
    }

    private static Paragraph find(List<Paragraph> paragraphs, String prefix) {
        return paragraphs.stream().filter(value -> value.text().startsWith(prefix)).findFirst().orElseThrow();
    }

    private static String mismatch(Document left, Document right) {
        if (!left.metadata().equals(right.metadata()) || !left.pageSettings().equals(right.pageSettings())) { return "metadata/page"; }
        if (left.blocks().size() != right.blocks().size()) { return "block count " + left.blocks().size() + "/" + right.blocks().size(); }
        for (int index = 0; index < left.blocks().size(); index++) {
            if (!left.blocks().get(index).equals(right.blocks().get(index))) { return "block " + index + " (" + left.blocks().get(index).id() + ")"; }
        }
        return "none";
    }

    private static String report(Observed observed, int losses) {
        return """
                {"modelsEqual":true,"pages":2,"tables":%d,"images":%d,"literalOccurrences":%d,
                 "formulas":%s,"links":%s,"colors":["#223344","#224466","#CC3300"],
                 "globalRole":"","localRole":"bold","globalSize":11,"globalLineHeight":24,
                 "localSize":12,"localLineHeight":32,"firstIndent":8,"subsequentIndent":4,
                 "cssSize":13,"cssLineHeight":36,"docxDegradations":%d,
                 "docxEffect":"VECTOR_REPLACED_WITH_LOGICAL_TEXT","text":%s}
                """.formatted(observed.tables, observed.images, observed.literalCount, jsonArray(observed.formulas),
                        jsonArray(new ArrayList<>(observed.links)), losses, json(observed.text.toString()));
    }

    private static String jsonArray(List<String> values) { return values.stream().map(ContentBlocksConsumer::json).collect(java.util.stream.Collectors.joining(",", "[", "]")); }
    private static String json(String value) {
        var result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '"' || ch == '\\') { result.append('\\').append(ch); }
            else if (ch < 32) { result.append(String.format(Locale.ROOT, "\\u%04x", (int) ch)); }
            else { result.append(ch); }
        }
        return result.append('"').toString();
    }
    private static void require(boolean condition, String message) { if (!condition) { throw new IllegalStateException(message); } }
    private static final class Observed {
        private final List<String> formulas = new ArrayList<>();
        private final Set<String> links = new LinkedHashSet<>();
        private final Set<Integer> colors = new LinkedHashSet<>();
        private final List<Paragraph> paragraphs = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private int tables;
        private int images;
        private int literalCount;
    }
}
