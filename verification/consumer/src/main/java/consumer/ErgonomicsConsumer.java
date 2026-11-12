// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.HtmlImporter;
import com.mirakyux.export4j.adapter.web.MarkdownImporter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.pdf.PdfSpoolBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Images;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.FontSet;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageDocument;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSources;
import com.mirakyux.export4j.model.Units;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/** Runs common file, font, image, row-source and conversion conveniences through published JARs. */
public final class ErgonomicsConsumer {
    private ErgonomicsConsumer() { }

    public static void main(String[] args) throws Exception {
        var output = Files.createDirectories(Path.of(System.getProperty("consumer.outputDirectory")).resolve("ergonomics"));
        var temporary = Files.createDirectories(output.resolve("temporary"));
        var regular = FontGroup.builder().fontFile("../../assets/fonts/LiberationSans-Regular.ttf").build();
        var bold = FontGroup.strict(TrueTypeFontLoader.load("../../assets/fonts/LiberationSans-Bold.ttf"));
        var italic = FontGroup.strict(TrueTypeFontLoader.load("../../assets/fonts/LiberationSans-Italic.ttf"));
        var combined = FontGroup.strict(TrueTypeFontLoader.load("../../assets/fonts/LiberationSans-BoldItalic.ttf"));
        var fonts = FontSet.builder(regular).bold(bold).italic(italic).boldItalic(combined).build();
        var engine = DocumentEngine.builder(fonts).build();

        // Compile and exercise the engine budget callbacks through published JARs, including the header row.
        var budgeted = DocumentEngine.builder(fonts)
                .layoutLimits(limits -> limits.maxTableRows(3))
                .componentLimits(limits -> limits.maxCommands(2_000_000)).build();
        var budgetTable = Table.builder("budget-table").header("ID").row("R1").row("R2").build();
        budgeted.write(Document.builder().add(budgetTable).build(), new PdfBackend(), output.resolve("budgets.pdf"));

        var pixels = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 20; x++) { pixels.setRGB(x, y, x < 10 ? 0xff0000 : 0x0000ff); }
        }
        var imageFile = output.resolve("logo.png");
        try { if (!ImageIO.write(pixels, "png", imageFile.toFile())) { throw new IllegalStateException("PNG encoder unavailable"); } }
        finally { pixels.flush(); }
        var resource = Images.load(imageFile.toFile());
        var logo = InlineImage.atWidth(resource, Units.inches(1), "LOGO");
        var fitted = InlineImage.fit(resource, 72, 20, "FITTED");
        if (logo.height() != 36 || fitted.width() != 40 || fitted.height() != 20) { throw new IllegalStateException("image aspect ratio changed"); }
        var body = Document.builder()
                .pageSettings(page -> page.size(PageSize.A4.landscape()).margins(Units.mm(20)))
                .paragraph("Ergonomics body.")
                .add(Paragraph.mixed("image", List.of(new InlineText("LOGO ", InlineStyle.of(12)), logo, fitted),
                        ParagraphFormat.builder().lineHeight(54).build()))
                .build();
        engine.write(body, new PdfBackend(), output.resolve("direct.pdf").toString());
        var styles = Document.builder().add(Paragraph.mixed("styles", List.of(
                new InlineText("REGULAR ", InlineStyle.of(12)),
                new InlineText("BOLD ", new InlineStyle("bold", 12, 0, 0, false, false)),
                new InlineText("ITALIC ", new InlineStyle("italic", 12, 0, 0, false, false)),
                new InlineText("COMBINED", new InlineStyle("boldItalic", 12, 0, 0, false, false))), ParagraphFormat.of(12))).build();
        engine.write(styles, new PdfBackend(), output.resolve("styles.pdf").toFile());

        var docx = new DocxAdapter(); var direct = output.resolve("direct.docx");
        docx.write(body, direct.toString(), ExecutionOptions.DEFAULT);
        if (docx.read(direct.toFile()).document().sections().getFirst().blocks().size() != 2) { throw new IllegalStateException("DOCX body was lost"); }
        var paged = new PageDocument(body, new PageRegions(new PageRegion(24, 2,
                List.of(new Paragraph("header", "HEADER PRESERVED", ParagraphFormat.of(12)))), PageRegion.EMPTY));
        docx.write(paged, output.resolve("paged.docx").toFile());
        var sections = new SectionedDocument(body.metadata(), List.of(new Section("one", body.pageSettings(), body.blocks(), 0)));
        docx.write(sections, output.resolve("sectioned.docx"));
        var pdfTarget = engine.conversionTarget("PDF", new PdfBackend());
        var converted = docx.conversion(direct).convertTo(pdfTarget, output.resolve("from-docx.pdf"));
        if (!converted.report().losses().isEmpty()) { throw new IllegalStateException("unexpected DOCX conversion loss"); }
        docx.conversion(direct.toString()).convertTo(docx.conversionTarget(), output.resolve("roundtrip.docx").toFile());

        var htmlSource = output.resolve("source.html"); Files.writeString(htmlSource, "<p>HTML import body.</p>");
        var html = new HtmlImporter(); html.readFile(htmlSource.toString()).execute();
        html.conversionFile(htmlSource.toString()).convertTo(docx.conversionTarget(), output.resolve("from-html.docx").toString());
        html.conversion(htmlSource.toFile()).convertTo(pdfTarget, output.resolve("from-html.pdf"));
        var markdownSource = output.resolve("source.md"); Files.writeString(markdownSource, "Markdown import body.\n");
        var markdown = new MarkdownImporter(); markdown.read(markdownSource).execute();
        markdown.conversion(markdownSource).convertTo(new HtmlAdapter().conversionTarget(), output.resolve("from-markdown.html"));
        try (var input = Files.newInputStream(markdownSource)) {
            markdown.conversion(input).convertTo(pdfTarget, output.resolve("from-markdown.pdf"));
        }

        var spec = StreamingTable.builder("rows").columns(120).header("ID").build();
        var rows = TableRowSources.fromIterable(List.of("R1", "R2", "R3"), value -> TableRow.text(value));
        engine.export(spec, rows).backend(new PdfSpoolBackend(temporary)).to(output.resolve("stream.pdf"));
        engine.exportSemantic(spec, rows).backend(new PdfSpoolBackend(temporary.toFile())).to(output.resolve("semantic.pdf"));
        var opens = new AtomicInteger(); var closes = new AtomicInteger();
        var replay = TableRowSources.fromStreams(() -> {
            opens.incrementAndGet(); return Stream.of("R1", "R2", "R3").onClose(closes::incrementAndGet);
        }, value -> TableRow.text(value));
        engine.exportReplay(spec, replay).backend(new PdfSpoolBackend(temporary.toString())).to(output.resolve("replay.pdf"));
        if (opens.get() != 2 || closes.get() != 2) { throw new IllegalStateException("replay stream ownership changed"); }
        try (var files = Files.list(temporary)) {
            if (files.findAny().isPresent()) { throw new IllegalStateException("temporary spool files remain"); }
        }
        System.out.println("Common authoring conveniences completed: " + output);
    }
}
