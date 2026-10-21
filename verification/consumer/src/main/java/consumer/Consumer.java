// SPDX-License-Identifier: Apache-2.0
package consumer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableRowSource;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.adapter.template.MarkdownTemplate;
import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.backend.raster.RasterBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.BoxComponent;
import com.mirakyux.export4j.components.ContinuousColumns;
import com.mirakyux.export4j.components.FlowColumn;
import com.mirakyux.export4j.components.GridTable;
import com.mirakyux.export4j.components.ImageComponent;
import com.mirakyux.export4j.components.TextComponent;
import com.mirakyux.export4j.components.OpenTypeText;
import com.mirakyux.export4j.components.MultiFloatText;
import com.mirakyux.export4j.components.ShapeComponent;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.text.ParagraphStyle;

/** Uses only published Maven coordinates: no project dependencies or private implementation types. */
public final class Consumer {
    private Consumer() { }
    public static void main(String[] args) throws Exception {
        var font = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Regular.ttf")));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(font))).build();
        var semantic = Document.builder(new DocumentMetadata("Clean consumer", ""), PageSettings.A4)
                .paragraph("p", "CONSUMER-OK-123", ParagraphFormat.of(12)).build();
        Path output = Path.of(System.getProperty("consumer.outputDirectory", "build/output")); Files.createDirectories(output);
        var fluent = com.mirakyux.export4j.components.Documents.builder().title("Fluent consumer")
                .pageSettings(page -> page.size(220, 200).margins(12))
                .column(column -> column.text("FLUENT-CONSUMER")
                        .table(table -> table.columns(1, 2).defaults(cell -> cell.padding(2))
                                .row(row -> row.minHeight(24).cell("Merged", cell -> cell.rowSpan(2)).cell("A"))
                                .row(row -> row.minHeight(24).cell("B"))))
                .table(table -> table.header("Key", "Value").row("C", "3")).build();
        var fluentEngine = DocumentEngine.builder(FontGroup.builder().font(font).build())
                .pageTemplate(template -> template.footer(footer -> footer.height(18).gap(2)
                        .content(com.mirakyux.export4j.components.PageNumberComponent.builder("P{page}/{pages}").build())))
                .build();
        var fluentResult = fluentEngine.export(fluent).backend(PdfBackend.builder()
                        .limits(com.mirakyux.export4j.backend.pdf.PdfLimits.builder().maxPages(10).build())
                        .options(options -> options.storage(com.mirakyux.export4j.backend.pdf.PdfOptions.Storage.TEMP_FILE)).build())
                .options(options -> options.timeout(Duration.ofSeconds(30)).maxOutputBytes(2_000_000))
                .to(output.resolve("consumer-fluent.pdf"));
        if (fluentResult.display().pages().size() != 1 || !fluentResult.diagnostics().isEmpty()) {
            throw new IllegalStateException("fluent consumer layout failed");
        }
        String splitSource = java.util.stream.IntStream.rangeClosed(1, 24)
                .mapToObj(index -> String.format(java.util.Locale.ROOT, "CON%03d", index)).collect(java.util.stream.Collectors.joining("\n"));
        var splitDocument = com.mirakyux.export4j.components.Documents.builder()
                .pageSettings(page -> page.size(260, 200).margins(16))
                .column(column -> column.grid(grid -> grid.columns(1, 1).headerRows(1).allowRowSplit(true)
                        .defaults(cell -> cell.padding(3))
                        .row(row -> row.cells("HEAD-A", "HEAD-B"))
                        .row(row -> row.cell(splitSource, cell -> cell.rowSpan(2)).cell("SIDE-A"))
                        .row(row -> row.cell("SIDE-B")))
                        .text("CONTINUED-TAIL").fixedText("FIXED-TAIL"))
                .build();
        var splitResult = engine.export(splitDocument).backend(new PdfBackend()).to(output.resolve("consumer-grid-continuation.pdf"));
        if (splitResult.display().pages().size() < 3 || !splitResult.diagnostics().isEmpty()) {
            throw new IllegalStateException("merged continuation consumer layout failed");
        }
        var authoringRun = new InlineText("CONSUMER-AUTHORING", new InlineStyle("", 12, 0x224488, 0, true, false), "https://example.com/consumer");
        var staticForm = com.mirakyux.export4j.components.Documents.builder()
                .pageSettings(page -> page.size(300, 200).margins(16))
                .column(column -> column.gap(4).formText("CONSUMER-FORM ☑ YES □ NO")
                        .choices(choices -> choices.kind(com.mirakyux.export4j.components.ChoiceGroup.Kind.RADIO)
                                .option("FIRST", true).option("SECOND", false))
                        .labelValue("Case", "CONSUMER-LABEL").fillLine()
                        .add(com.mirakyux.export4j.components.CheckMark.builder().selected(true).build())).build();
        var staticResult = engine.export(staticForm).backend(new PdfBackend()).to(output.resolve("consumer-static-forms.pdf"));
        if (!staticResult.display().extras().fields().isEmpty() || !staticResult.diagnostics().isEmpty()
                || staticResult.display().pages().size() != 1) {
            throw new IllegalStateException("static form consumer layout failed");
        }
        var concise = Document.builder().bodyFormat(ParagraphFormat.of(12).withAlignment(ParagraphAlignment.JUSTIFY)
                        .withLineBreakPolicy(com.mirakyux.export4j.model.LineBreakPolicy.CJK_STRICT))
                .heading(List.of(authoringRun), 1).paragraph("Plain paragraphs use automatic IDs and defaults.")
                .bullet(List.of(authoringRun)).numbered("list", List.of(authoringRun))
                .table(List.of("Name", "Value"), List.of(List.of("A", "120"))).build();
        var conciseLayout = engine.write(concise, new PdfBackend(), output.resolve("consumer-authoring.pdf"));
        if (conciseLayout.display().extras().externalLinks().size() != 3) { throw new IllegalStateException("rich semantic links were lost"); }
        var sections = new com.mirakyux.export4j.model.SectionedDocument(concise.metadata(), List.of(
                new com.mirakyux.export4j.model.Section("one", PageSettings.A4, concise.blocks(), 0)));
        engine.write(sections, new PdfBackend(), output.resolve("consumer-authoring-sections.pdf"));
        try (var stream = Files.newOutputStream(output.resolve("consumer.pdf"))) { engine.write(semantic, new PdfBackend(), stream); }
        try (var stream = Files.newOutputStream(output.resolve("consumer.docx"))) { new DocxAdapter().write(semantic, stream); }
        var shaped = Document.builder(semantic.metadata(), PageSettings.A4)
                .add(new ComponentBlock("shaped", new OpenTypeText("AVATAR a\u0301 CONSUMER", ParagraphStyle.of(12), RgbColor.BLACK), 0, 0)).build();
        var layout = engine.layout(shaped);
        try (var stream = Files.newOutputStream(output.resolve("consumer.svg"))) { new SvgBackend().write(layout.display(), stream); }
        try (var stream = Files.newOutputStream(output.resolve("consumer-pages.zip"))) { new RasterBackend(72).write(layout.display(), stream); }
        try (var stream = Files.newInputStream(output.resolve("consumer.docx"))) {
            var imported = new DocxAdapter().read(stream, DocxAdapter.Policy.STRICT);
            if (!imported.diagnostics().isEmpty() || !imported.document().sections().getFirst().blocks().getFirst().id().equals("p")) {
                throw new IllegalStateException("DOCX consumer roundtrip failed");
            }
        }
        var binding = new DocumentTemplate(semantic.metadata(), PageSettings.A4,
                List.of(new DocumentTemplate.ValueBlock(new Paragraph("bound", "${message}", ParagraphFormat.of(12)))),
                DocumentTemplate.Limits.DEFAULT);
        var controlled = new ExecutionOptions(Duration.ofSeconds(30), () -> false, progress -> { }, 2_000_000);
        engine.writeAtomically(binding.render(Map.of("message", "CONSUMER a\u0301 template")), new PdfBackend(),
                output.resolve("consumer-controlled.pdf"), controlled);
        var cff = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("../../assets/fonts/SourceSans3-Regular.otf")));
        DocumentEngine.builder(FontGroup.strict(List.of(cff))).build().writeAtomically(
                binding.render(Map.of("message", "CONSUMER-CFF a\u0301")), new PdfBackend(),
                output.resolve("consumer-cff.pdf"), controlled);
        var imageBytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", imageBytes);
        var image = new InlineImage(ImageResource.load(imageBytes.toByteArray()), 18, 18, 0, "Consumer image");
        var mixed = Paragraph.mixed("image", List.of(new InlineText("CONSUMER-IMAGE ", InlineStyle.of(12)), image),
                new ParagraphFormat(12, 28, 0, 0, ParagraphAlignment.LEFT, 0, 0, false));
        var imageDocument = new Document(semantic.metadata(), PageSettings.A4, List.of(mixed));
        engine.writeAtomically(imageDocument, new PdfBackend(), output.resolve("consumer-images.pdf"), controlled);
        try (var stream = Files.newOutputStream(output.resolve("consumer-images.docx"))) { new DocxAdapter().write(imageDocument, stream); }
        try (var stream = Files.newInputStream(output.resolve("consumer-images.docx"))) {
            var imported = new DocxAdapter().read(stream, DocxAdapter.Policy.STRICT);
            if (!((Paragraph) imported.document().sections().getFirst().blocks().getFirst()).images().getFirst().image().equals(image)) {
                throw new IllegalStateException("DOCX inline image consumer roundtrip failed");
            }
        }
        var nestedImage = image.toBuilder().width(14).height(14).hyperlink("https://example.com/consumer-image").build();
        var nestedCell = TableCell.builder().format(new CellFormat(10, 24, ParagraphAlignment.LEFT))
                .content(List.of(new InlineText("CONSUMER-NESTED PAGE=", InlineStyle.of(10)),
                        new InlineField(InlineField.Kind.PAGE, "", 18, InlineStyle.of(10)),
                        new InlineText(" TOTAL=", InlineStyle.of(10)), new InlineField(InlineField.Kind.PAGES, "", 18, InlineStyle.of(10)),
                        new InlineText("\nTARGET=", InlineStyle.of(10)),
                        new InlineField(InlineField.Kind.REFERENCE_PAGE, "nested-target", 18, InlineStyle.of(10)),
                        new InlineText("\nOPEN ", InlineStyle.of(10), "https://example.com/consumer-text"), nestedImage)).build();
        var nestedTable = Table.builder("consumer-nested-table").row(nestedCell).build();
        var nestedDocument = Documents.builder().pageSettings(page -> page.size(320, 220).margins(16))
                .column(column -> column.padding(6).column(inner -> inner.padding(4).semanticTable(nestedTable)))
                .pageBreak().paragraph("nested-target", "CONSUMER-NESTED-TARGET")
                .add(Paragraph.mixed("nested-back", List.of(new InlineText("BACK=", InlineStyle.of(10)),
                        new InlineField(InlineField.Kind.REFERENCE_PAGE, "consumer-nested-table", 18, InlineStyle.of(10))), ParagraphFormat.of(10))).build();
        var nestedResult = engine.export(nestedDocument).backend(new PdfBackend()).to(output.resolve("consumer-semantic-tables.pdf"));
        if (nestedResult.display().pages().size() != 2 || nestedResult.display().extras().links().size() != 2
                || nestedResult.display().extras().externalLinks().size() != 2 || !nestedResult.diagnostics().isEmpty()
                || nestedResult.display().extras().links().stream().noneMatch(link -> link.pageIndex() == 0 && link.targetPageIndex() == 1)
                || nestedResult.display().extras().links().stream().noneMatch(link -> link.pageIndex() == 1 && link.targetPageIndex() == 0)) {
            throw new IllegalStateException("nested semantic table consumer final-page or hyperlink resolution failed");
        }
        var flowCell = new CellFormat(10, 20, ParagraphAlignment.LEFT);
        var flowHeader = TableCell.mixed(List.of(new InlineText("CONSUMER-FLOW P=", InlineStyle.of(10)),
                new InlineField(InlineField.Kind.PAGE, "", 18, InlineStyle.of(10)), new InlineText(" T=", InlineStyle.of(10)),
                new InlineField(InlineField.Kind.PAGES, "", 18, InlineStyle.of(10))), flowCell);
        var flowTable = Table.builder("consumer-flow-table").cellFormat(flowCell).format(value -> value.padding(4)).row(flowHeader);
        for (int row = 1; row <= 30; row++) { flowTable.row(String.format(java.util.Locale.ROOT, "CFT%03d", row)); }
        var flowSeed = flowTable.build();
        var flowSource = new Table(flowSeed.id(), flowSeed.columns(), flowSeed.rows(), 1, flowSeed.format());
        var flowDocument = Documents.builder().pageSettings(page -> page.size(320, 240).margins(16))
                .column(column -> column.padding(4).column(inner -> inner.padding(3).semanticTable(flowSource))).build();
        var flowResult = engine.export(flowDocument).backend(new PdfBackend()).to(output.resolve("consumer-semantic-table-flow.pdf"));
        int nextFragment = 0;
        for (var page : flowResult.body().pages()) {
            for (var element : page.elements()) {
                if (!(element instanceof com.mirakyux.export4j.layout.ComponentFragment component)) { continue; }
                for (var placement : component.layout().semanticPlacements()) {
                    if (placement.fragmentIndex() != nextFragment++ || placement.fragmentCount() != flowResult.body().pages().size()) {
                        throw new IllegalStateException("semantic table continuation source identity was lost");
                    }
                }
            }
        }
        if (nextFragment < 2 || !flowResult.diagnostics().isEmpty()) {
            throw new IllegalStateException("semantic table did not continue through isolated public API");
        }
        var panel = new ShapeComponent(ShapeComponent.Kind.RECTANGLE, new RgbColor(.8, .9, 1), RgbColor.BLACK, 0);
        var floats = List.of(new MultiFloatText.FloatBox(panel, 60, 44, 0, 10, MultiFloatText.Side.LEFT),
                new MultiFloatText.FloatBox(panel, 70, 44, 22, 10, MultiFloatText.Side.RIGHT));
        var floatDocument = Document.builder(semantic.metadata(), PageSettings.A4).add(new ComponentBlock("float",
                new MultiFloatText(new OpenTypeText("CONSUMER-FLOATS a\u0301", new ParagraphStyle(12, 22, 0, 0,
                        com.mirakyux.export4j.text.TextAlignment.LEFT), RgbColor.BLACK), floats), 0, 0)).build();
        engine.writeAtomically(floatDocument, new PdfBackend(), output.resolve("consumer-floats.pdf"), controlled);
        var columnStyle = new ParagraphStyle(12, 20, 0, 0, com.mirakyux.export4j.text.TextAlignment.LEFT);
        String columnSource = java.util.stream.IntStream.rangeClosed(1, 22)
                .mapToObj(index -> String.format(java.util.Locale.ROOT, "COL-%02d", index))
                .collect(java.util.stream.Collectors.joining("\n"));
        var columnSettings = new PageSettings(new com.mirakyux.export4j.model.PageSize(260, 140),
                com.mirakyux.export4j.model.PageMargins.uniform(10));
        var columnDocument = Document.builder(semantic.metadata(), columnSettings)
                .add(new ComponentBlock("prefix", new BoxComponent(240, 35,
                        new TextComponent("Columns / consumer", columnStyle, RgbColor.BLACK)), 0, 0))
                .add(new ComponentBlock("columns", new ContinuousColumns(
                        new OpenTypeText(columnSource, columnStyle, RgbColor.BLACK), 2, 12), 0, 0))
                .add(new ComponentBlock("tail", new TextComponent("CONSUMER-COLUMNS-TAIL", columnStyle, RgbColor.BLACK), 0, 0)).build();
        engine.writeAtomically(columnDocument, new PdfBackend(), output.resolve("consumer-columns.pdf"), controlled);
        var gridCells = new java.util.ArrayList<GridTable.Cell>();
        for (int row = 0; row <= 4; row++) {
            gridCells.add(new GridTable.Cell(row, 0, 1, 1,
                    new TextComponent(row == 0 ? "GRID-HEAD" : "GRID-ROW-" + row, columnStyle, RgbColor.BLACK),
                    com.mirakyux.export4j.model.PageMargins.uniform(0), FlowColumn.Alignment.START, RgbColor.WHITE, RgbColor.BLACK, .5));
        }
        var mixedGrid = new GridTable(5, List.of(1.0), 1, gridCells);
        var mixedPicture = new BoxComponent(40, 20, new ImageComponent(
                new com.mirakyux.export4j.graphics.RgbImage(1, 1, new byte[] {40, 80, (byte) 200}), ImageComponent.Fit.STRETCH));
        String mixedSource = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(index -> String.format(java.util.Locale.ROOT, "MIX-%02d", index))
                .collect(java.util.stream.Collectors.joining("\n"));
        var mixedColumn = new FlowColumn(List.of(new TextComponent("MIXED", columnStyle, RgbColor.BLACK), mixedPicture,
                new OpenTypeText(mixedSource, columnStyle, RgbColor.BLACK), mixedGrid),
                com.mirakyux.export4j.model.PageMargins.uniform(2), 4, FlowColumn.Alignment.START);
        var mixedDocument = Document.builder(semantic.metadata(), columnSettings)
                .add(new ComponentBlock("prefix", new BoxComponent(240, 35,
                        new TextComponent("Mixed / consumer", columnStyle, RgbColor.BLACK)), 0, 0))
                .add(new ComponentBlock("columns", new ContinuousColumns(mixedColumn, 2, 12), 0, 0))
                .add(new ComponentBlock("tail", new TextComponent("CONSUMER-MIXED-TAIL", columnStyle, RgbColor.BLACK), 0, 0)).build();
        engine.writeAtomically(mixedDocument, new PdfBackend(), output.resolve("consumer-mixed-columns.pdf"), controlled);
        var fieldParagraph = Paragraph.mixed("fields", List.of(new InlineText("CONSUMER-PAGE=", InlineStyle.of(12)),
                new InlineField(InlineField.Kind.PAGE, "", 24, InlineStyle.of(12)), new InlineText(" TOTAL=", InlineStyle.of(12)),
                new InlineField(InlineField.Kind.PAGES, "", 24, InlineStyle.of(12)), new InlineText(" TARGET=", InlineStyle.of(12)),
                new InlineField(InlineField.Kind.REFERENCE_PAGE, "target", 24, InlineStyle.of(12))), ParagraphFormat.of(12));
        var fieldCell = TableCell.mixed(List.of(new InlineText("CONSUMER-CELL=", InlineStyle.of(12)),
                new InlineField(InlineField.Kind.PAGE, "", 24, InlineStyle.of(12)), new InlineText(" TOTAL=", InlineStyle.of(12)),
                new InlineField(InlineField.Kind.PAGES, "", 24, InlineStyle.of(12)), new InlineText(" TARGET=", InlineStyle.of(12)),
                new InlineField(InlineField.Kind.REFERENCE_PAGE, "target", 24, InlineStyle.of(12))), CellFormat.of(12));
        var fieldTable = new Table("field-table", List.of(new ColumnWidth.Auto(250, 400)),
                List.of(new TableRow(List.of(fieldCell))), 0, TableFormat.DEFAULT);
        var fieldDocument = new Document(semantic.metadata(), PageSettings.A4, List.of(fieldParagraph, fieldTable,
                new com.mirakyux.export4j.model.PageBreak("field-break"),
                Paragraph.mixed("target", List.of(new InlineText("FIELD-TARGET", InlineStyle.of(12))), ParagraphFormat.of(12))));
        var fieldLayout = engine.writeAtomically(fieldDocument, new PdfBackend(), output.resolve("consumer-inline-fields.pdf"), controlled);
        if (fieldLayout.display().pages().size() != 2 || fieldLayout.display().extras().links().size() != 2
                || fieldLayout.display().extras().links().stream().anyMatch(link -> link.targetPageIndex() != 1)) {
            throw new IllegalStateException("inline field consumer final-page resolution failed");
        }
        var docxFields = new DocxAdapter();
        try (var stream = Files.newOutputStream(output.resolve("consumer-fields.docx"))) { docxFields.write(fieldDocument, stream); }
        try (var stream = Files.newInputStream(output.resolve("consumer-fields.docx"))) {
            var importedFields = docxFields.read(stream, DocxAdapter.Policy.STRICT, 36);
            if (!importedFields.diagnostics().isEmpty() || !fieldDocument.blocks().equals(importedFields.document().sections().getFirst().blocks())) {
                throw new IllegalStateException("DOCX fields consumer semantic roundtrip failed");
            }
            var importedDocument = new Document(importedFields.document().metadata(), PageSettings.A4, importedFields.document().sections().getFirst().blocks());
            engine.writeAtomically(importedDocument, new PdfBackend(), output.resolve("consumer-docx-fields.pdf"), controlled);
        }
        var styleDocument = Documents.builder().pageSettings(page -> page.size(320, 240).margins(16))
                .bodyFormat(format -> format.fontSize(10).lineHeight(20))
                .column(column -> column.text("CONSUMER-STYLE")
                        .grid(grid -> grid.row(row -> row.cell("CONSUMER-GRID")))
                        .text("CONSUMER-PIN12", text -> text.style(ParagraphStyle.of(12)))
                        .add(com.mirakyux.export4j.components.StyleScope.builder(
                                FlowColumn.builder().text("CONSUMER-RED").formText("☑ Checked □ Empty").build())
                                .color(new RgbColor(1, 0, 0)).build())).build();
        var styleResult = engine.export(styleDocument).backend(new PdfBackend()).to(output.resolve("consumer-style-defaults.pdf"));
        if (styleResult.display().pages().size() != 1 || !styleResult.diagnostics().isEmpty()) {
            throw new IllegalStateException("component text default inheritance failed in isolated public API");
        }
        var propertyDocument = Documents.builder().pageSettings(page -> page.size(360, 280).margins(16))
                .bodyFormat(format -> format.fontSize(10).lineHeight(24).firstLineIndent(12).subsequentLineIndent(4))
                .column(column -> column.text("CP14-A\nCP14-B", text -> text.fontSize(14))
                        .fixedText("CP11", text -> text.fontSize(11))
                        .text("CP13-A\nCP13-B", text -> text.style(ParagraphStyle.of(12))
                                .inheritLineHeight().fontSize(13))
                        .grid(grid -> grid.columns(1, 1).defaults(cell -> cell.padding(3).fontSize(9)
                                        .firstLineIndent(0).subsequentLineIndent(0))
                                .row(row -> row.cell("CP-GRID9").cell("CP-FACTORY11", cell -> cell.textFactory(value ->
                                        com.mirakyux.export4j.components.FlowText.builder(value).fontSize(11).build())))
                                .defaults(cell -> cell.fontSize(12))
                                .row(row -> row.cells("CP-GRID12", "CP-SNAPSHOT12")))
                        .add(com.mirakyux.export4j.components.StyleScope.builder(
                                com.mirakyux.export4j.components.FlowText.builder("CP-SCOPE14").build())
                                .fontSize(14).color(new RgbColor(1, 0, 0)).build())).build();
        var propertyResult = engine.export(propertyDocument).backend(new PdfBackend())
                .to(output.resolve("consumer-property-styles.pdf"));
        if (propertyResult.display().pages().size() != 1 || !propertyResult.diagnostics().isEmpty()) {
            throw new IllegalStateException("per-property style inheritance failed in isolated public API");
        }
        var gridHeader = Table.builder("consumer-grid-header").format(value -> value.padding(2))
                .row(TableCell.mixed(List.of(new InlineText("CONSUMER-GRID P=", InlineStyle.of(10)),
                        new InlineField(InlineField.Kind.PAGE, "", 18, InlineStyle.of(10)),
                        new InlineText(" T=", InlineStyle.of(10)),
                        new InlineField(InlineField.Kind.PAGES, "", 18, InlineStyle.of(10))), flowCell)).build();
        var gridContents = new java.util.ArrayList<com.mirakyux.export4j.model.InlineContent>();
        for (int line = 1; line <= 24; line++) {
            if (line > 1) { gridContents.add(new InlineText("\n", InlineStyle.of(10))); }
            gridContents.add(new InlineText(String.format(java.util.Locale.ROOT, "CGF%03d P=", line), InlineStyle.of(10)));
            gridContents.add(new InlineField(InlineField.Kind.PAGE, "", 18, InlineStyle.of(10)));
        }
        var gridBody = Table.builder("consumer-grid-body").format(value -> value.padding(2).allowRowSplit(true))
                .row(TableCell.mixed(gridContents, flowCell)).build();
        var gridDocument = Documents.builder().pageSettings(page -> page.size(320, 240).margins(16))
                .grid(grid -> grid.columns(1).headerRows(1).allowRowSplit(true).defaults(cell -> cell.padding(3))
                        .row(row -> row.semanticTable(gridHeader))
                        .row(row -> row.cell(FlowColumn.builder().padding(2).semanticTable(gridBody).build(), cell -> cell.rowSpan(2)))
                        .row(row -> { })).build();
        var gridResult = engine.export(gridDocument).backend(new PdfBackend()).to(output.resolve("consumer-grid-semantic-flow.pdf"));
        var nextBySource = new java.util.HashMap<String, Integer>();
        for (var page : gridResult.body().pages()) {
            for (var element : page.elements()) {
                if (!(element instanceof com.mirakyux.export4j.layout.ComponentFragment component)) { continue; }
                for (var placement : component.layout().semanticPlacements()) {
                    int expected = nextBySource.getOrDefault(placement.source().id(), 0);
                    if (placement.fragmentIndex() != expected || placement.fragmentCount() != gridResult.body().pages().size()) {
                        throw new IllegalStateException("merged semantic grid continuation identity was lost");
                    }
                    nextBySource.put(placement.source().id(), expected + 1);
                }
            }
        }
        if (gridResult.body().pages().size() < 2 || nextBySource.size() != 2 || !gridResult.diagnostics().isEmpty()) {
            throw new IllegalStateException("merged semantic grid did not continue through isolated public API");
        }
        var xml = XmlTemplate.builder().pageSettings(PageSettings.builder().size(320, 260).margins(16).build())
                .bodyFormat(ParagraphFormat.builder().fontSize(10).lineHeight(18).build())
                .cellFormat(new CellFormat(10, 18, ParagraphAlignment.LEFT)).image("mark", image.resource()).build();
        var xmlTemplate = xml.compile("""
                <document version="1" title="Consumer template" author="Public API">
                  <heading level="1" size="12" line-height="22">${title}</heading>
                  <if test="needsReview">
                    <then><p>CONSUMER-REVIEW-YES</p></then>
                    <else><p><span color="#224488" underline="true">CONSUMER-REVIEW-NO</span></p></else>
                  </if>
                  <each items="items" as="item"><bullet>${item.label}</bullet></each>
                  <rows items="items" as="item" row-split="true">
                    <columns><column weight="1"/><column weight="1"/></columns>
                    <header><cell>Key</cell><cell>Value</cell></header>
                    <row><cell>${item.label}</cell><cell>${item.value}</cell></row>
                  </rows>
                  <p line-height="22">CONSUMER-TEMPLATE-END <image ref="mark" width="12" height="12" alt="Explicit consumer mark"/></p>
                </document>
                """);
        var xmlDocument = xmlTemplate.render(Map.of("title", "CONSUMER-TEMPLATE", "needsReview", false,
                "items", List.of(Map.of("label", "ITEM-A", "value", "VALUE-A"), Map.of("label", "ITEM-B", "value", "VALUE-B"))));
        var xmlResult = engine.export(xmlDocument).backend(new PdfBackend()).to(output.resolve("consumer-template.pdf"));
        if (xmlResult.display().pages().size() != 1 || !xmlResult.diagnostics().isEmpty()) {
            throw new IllegalStateException("XML template compilation/rendering failed in isolated public API");
        }
        var markdown = MarkdownTemplate.builder().pageSettings(PageSettings.builder().size(320, 260).margins(16).build())
                .bodyFormat(ParagraphFormat.builder().fontSize(10).lineHeight(20).build())
                .image("mark", image.resource(), 12, 12).build();
        var markdownDocument = markdown.compile("# ${title}\n\nPlain ${value}\n\n- One\n\nResource ![mark](mark)")
                .render(Map.of("title", "CONSUMER-MARKDOWN", "value", "**literal**"));
        var markdownResult = engine.export(markdownDocument).backend(new PdfBackend()).to(output.resolve("consumer-markdown.pdf"));
        if (markdownResult.display().pages().size() != 1 || !markdownResult.diagnostics().isEmpty()) {
            throw new IllegalStateException("Markdown public API failed in isolated consumer");
        }
        var referenceDocument = xml.compile("""
                <document version="1">
                  <p>XML current=<page-number width="24"/> total=<page-count width="24"/> target=<page-reference target="end" width="24"/> number=<number-reference target="step" width="24"/></p>
                  <p line-height="22"><link href="https://example.com/consumer">PASSIVE-LINK</link> <link href="https://example.com/image"><image ref="mark" width="12" height="12" alt="Consumer linked image"/></link></p>
                  <numbered id="step" list-id="steps" restart="3">NUMBERED-TARGET</numbered>
                  <page-break/>
                  <p id="end">XML-END page=<page-number width="24"/></p>
                </document>
                """).render(Map.of());
        var referenceResult = engine.export(referenceDocument).backend(new PdfBackend())
                .to(output.resolve("consumer-xml-references.pdf"));
        if (referenceResult.display().pages().size() != 2 || !referenceResult.diagnostics().isEmpty()) {
            throw new IllegalStateException("XML fields and links public API failed in isolated consumer");
        }
        try (var referenceOutput = Files.newOutputStream(output.resolve("consumer-xml-references.docx"))) {
            new DocxAdapter().write(referenceDocument, referenceOutput);
        }
        var pdfAdapter = PdfAdapter.builder().build();
        var pdfInput = pdfAdapter.input(output.resolve("consumer.pdf"), "");
        var pdfRead = pdfAdapter.read(pdfInput);
        if (pdfRead.pages().size() != 1 || !pdfRead.pages().getFirst().text().contains("CONSUMER-OK-123")) {
            throw new IllegalStateException("PDF extraction public API failed in isolated consumer");
        }
        var copied = pdfAdapter.merge(List.of(pdfInput.pages(0), pdfInput.allPages()), output.resolve("consumer-pdf-operations.pdf"));
        if (copied.pageCount() != 2 || copied.outputBytes() < 1) {
            throw new IllegalStateException("PDF selection/merge public API failed in isolated consumer");
        }
        var streamSpec = StreamingTable.builder("consumer-stream")
                .pageSettings(PageSettings.builder().size(300, 240).margins(20).build())
                .columns(80, 180).cellFormat(CellFormat.builder().fontSize(10).lineHeight(16).build())
                .header("STREAM-ID", "STREAM-VALUE").padding(3).build();
        var streamClosed = new java.util.concurrent.atomic.AtomicBoolean();
        var streamed = engine.export(streamSpec, () -> new TableRowSource() {
            private int index;
            @Override public java.util.Optional<TableRow> next() {
                if (++index > 15) { return java.util.Optional.empty(); }
                return java.util.Optional.of(new TableRow(List.of(
                        new TableCell(String.format(java.util.Locale.ROOT, "CON-%03d", index), CellFormat.of(10)),
                        new TableCell("STREAM-CONSUMER-" + index, CellFormat.of(10)))));
            }
            @Override public void close() { streamClosed.set(true); }
        }).backend(PdfBackend.builder().options(options -> options.storage(com.mirakyux.export4j.backend.pdf.PdfOptions.Storage.TEMP_FILE)).build())
                .limits(limits -> limits.maxCellCodePoints(100)).diagnostics(diagnostic -> {
                    throw new IllegalStateException("unexpected streaming warning");
                }).options(options -> options.timeout(Duration.ofSeconds(30)).maxOutputBytes(1_000_000))
                .to(output.resolve("consumer-streaming-table.pdf"));
        if (streamed.bodyRows() != 15 || streamed.pages() != 2 || streamed.sourceCells() != 32
                || streamed.outputLines() != 34 || streamed.diagnostics() != 0 || !streamClosed.get()
                || streamed.outputBytes() != Files.size(output.resolve("consumer-streaming-table.pdf"))) {
            throw new IllegalStateException("single-pass streaming table failed in isolated consumer");
        }
        var mergedCellFormat = CellFormat.builder().fontSize(10).lineHeight(20).build();
        var mergedTable = Table.builder("consumer-column-spans")
                .columns(new ColumnWidth.Fixed(60), new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1))
                .format(format -> format.padding(2)).cellFormat(mergedCellFormat)
                .headerCells(TableCell.builder().text("CON-COLS").format(mergedCellFormat).columnSpan(3).build())
                .row(TableCell.builder().text("CON-MERGED-A").format(mergedCellFormat).columnSpan(2).build(),
                        TableCell.mixed(List.of(new InlineText("P=", InlineStyle.of(10)),
                                new InlineField(InlineField.Kind.PAGE, "", 18, InlineStyle.of(10)),
                                new InlineText("LINK", InlineStyle.of(10), "https://example.com/columns"),
                                new InlineImage(image.resource(), 8, 8, 0, "Merged image", "https://example.com/columns-image")), mergedCellFormat))
                .row(new TableCell("CON-B", mergedCellFormat),
                        TableCell.builder().text("CON-MERGED-C").format(mergedCellFormat).columnSpan(2).build()).build();
        var mergedDocument = new Document(DocumentMetadata.EMPTY,
                PageSettings.builder().size(320, 220).margins(20).build(), List.of(mergedTable));
        var mergedResult = engine.export(mergedDocument).backend(new PdfBackend()).to(output.resolve("consumer-column-spans.pdf"));
        if (mergedResult.display().pages().size() != 1 || mergedResult.display().extras().externalLinks().size() != 2
                || !mergedResult.diagnostics().isEmpty() || mergedTable.rows().get(1).cellAtColumn(2).fields().size() != 1) {
            throw new IllegalStateException("semantic column spans failed in isolated consumer");
        }
        try (var mergedOutput = Files.newOutputStream(output.resolve("consumer-column-spans.docx"))) {
            new DocxAdapter().write(mergedDocument, mergedOutput);
        }
        try (var mergedInput = Files.newInputStream(output.resolve("consumer-column-spans.docx"))) {
            var mergedRead = new DocxAdapter().read(mergedInput, DocxAdapter.Policy.STRICT);
            var mergedBack = (Table) mergedRead.document().sections().getFirst().blocks().getFirst();
            if (!mergedBack.rows().stream().map(row -> row.cells().stream().map(TableCell::columnSpan).toList()).toList()
                    .equals(List.of(List.of(3), List.of(2, 1), List.of(1, 2)))) {
                throw new IllegalStateException("standard DOCX column spans lost in isolated consumer");
            }
        }
        var verticalTable = Table.builder("consumer-row-spans")
                .columns(new ColumnWidth.Fixed(60), new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1))
                .format(format -> format.padding(2)).cellFormat(mergedCellFormat)
                .headerCells(TableCell.builder().text("CON-ROWS\nCON-HEADER").format(mergedCellFormat).columnSpan(3).rowSpan(2).build())
                .coveredHeaderRow()
                .row(TableCell.builder().text("CON-V").format(mergedCellFormat).rowSpan(2).build(),
                        TableCell.builder().text("CON-UP").format(mergedCellFormat).columnSpan(2).build())
                .row(TableCell.mixed(List.of(new InlineText("P=", InlineStyle.of(10)),
                        new InlineField(InlineField.Kind.PAGE, "", 18, InlineStyle.of(10)),
                        new InlineText(" N=", InlineStyle.of(10)), new InlineField(InlineField.Kind.PAGES, "", 18, InlineStyle.of(10)),
                        new InlineText("LINK", InlineStyle.of(10), "https://example.com/rows"),
                        new InlineImage(image.resource(), 8, 8, 0, "Vertical image", "https://example.com/rows-image")), mergedCellFormat)
                        .toBuilder().columnSpan(2).build())
                .row(TableCell.builder().text("CON-COVERED\nCON-END").format(mergedCellFormat).columnSpan(3).rowSpan(2).build())
                .coveredRow().build();
        var verticalGrid = verticalTable.grid();
        if (verticalGrid.ownerAt(3, 0).rowIndex() != 2 || verticalGrid.anchorAt(3, 0).isPresent()
                || verticalGrid.anchorAt(3, 1).orElseThrow().cell().fields().size() != 2
                || !verticalGrid.anchorRows().get(5).isEmpty()) {
            throw new IllegalStateException("two-dimensional semantic owner lookup failed in isolated consumer");
        }
        var verticalDocument = new Document(DocumentMetadata.EMPTY,
                PageSettings.builder().size(320, 240).margins(20).build(), List.of(verticalTable));
        var verticalResult = engine.export(verticalDocument).backend(new PdfBackend()).to(output.resolve("consumer-row-spans.pdf"));
        if (verticalResult.display().pages().size() != 1 || verticalResult.display().extras().externalLinks().size() != 2
                || !verticalResult.diagnostics().isEmpty()) {
            throw new IllegalStateException("semantic row spans failed in isolated consumer");
        }
        try (var verticalOutput = Files.newOutputStream(output.resolve("consumer-row-spans.docx"))) {
            new DocxAdapter().write(verticalDocument, verticalOutput);
        }
        try (var verticalInput = Files.newInputStream(output.resolve("consumer-row-spans.docx"))) {
            var verticalRead = new DocxAdapter().read(verticalInput, DocxAdapter.Policy.STRICT);
            var verticalBack = (Table) verticalRead.document().sections().getFirst().blocks().getFirst();
            if (!verticalBack.rows().equals(verticalTable.rows()) || verticalBack.headerRows() != 2) {
                throw new IllegalStateException("standard DOCX vertical spans lost in isolated consumer");
            }
        }
        BlockCellsConsumer.verify(engine, image, output);
        XmlBlockCellsConsumer.verify(engine, image, output);
        StreamingRichTextConsumer.verify(font, output);
        ThemeConsumer.verify(font, output);
        XmlThemeConsumer.verify(font, output);
        FormStyleConsumer.verify(font, output);
        XmlIncludeConsumer.verify(font, output);
        ContentsStyleConsumer.verify(font, output);
        XmlNumberingConsumer.verify(font, output);
        XmlPageSettingsConsumer.verify(font, output);
        PageRegionFontsConsumer.verify(font, output);
        ComponentTextConsumer.verify(font, output);
        ListMarkerConsumer.verify(font, output);
        XmlTextDefaultsConsumer.verify(font, output);
        XmlPageRegionsConsumer.verify(font, output);
        XmlSectionsConsumer.verify(font, output);
        WebExportConsumer.verify(output);
        System.out.println("Consumer outputs: " + output.toAbsolutePath());
    }
}
