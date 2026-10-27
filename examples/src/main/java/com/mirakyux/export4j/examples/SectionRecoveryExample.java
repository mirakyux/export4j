// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfRecoveryCodec;
import com.mirakyux.export4j.adapter.template.XmlModelRecovery;
import com.mirakyux.export4j.adapter.template.XmlTemplateBundle;
import com.mirakyux.export4j.adapter.template.XmlTemplateWriter;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.HtmlImporter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownImporter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.raster.RasterBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.PageOverlay;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.edit.DocumentEditor;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

/** Section variants, shared overlays and nested table recovery through real target readers. */
public final class SectionRecoveryExample {
    private static final Path OUTPUT = Path.of("examples/build/section-recovery");
    private static final List<String> FORMATS = List.of("PDF", "DOCX", "HTML", "MARKDOWN", "SVG", "PNG_ZIP");
    private static final List<String> EXACT = List.of("PDF", "SVG", "PNG_ZIP");
    private static final ParagraphFormat FORMAT = ParagraphFormat.builder().fontSize(10).lineHeight(18).build();
    private static final InlineStyle STYLE = InlineStyle.of(10);
    private SectionRecoveryExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var resources = XmlTemplateBundle.builder("<document version='1'><p>UNUSED-REGION-SOURCE</p></document>")
                .font("Regular.ttf", Files.readAllBytes(Path.of("assets/fonts/LiberationSans-Regular.ttf")))
                .font("Bold.ttf", Files.readAllBytes(Path.of("assets/fonts/LiberationSans-Bold.ttf")))
                .license("Font.txt", Files.readString(Path.of("assets/fonts/LiberationSans-OFL.txt"))).build();
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(resources.fonts().get("Regular.ttf")))))
                .fontRole("bold", FontGroup.strict(List.of(TrueTypeFontLoader.load(resources.fonts().get("Bold.ttf"))))).build();
        var source = source(); var policy = policy(ProcessingPolicy.Action.WARN);
        engine.export(source).backend(new PdfBackend()).to(OUTPUT.resolve("source.pdf"));
        var rows = new StringBuilder("format,action,export_losses,import_losses,total,warnings,images,sections\n");
        for (String format : FORMATS) {
            var first = capture(DocumentConversion.from(source, List.of()), format, resources, policy, engine);
            publish(first, "original");
            var baseline = reopen(first).prepare();
            require(source.equals(baseline.document()) == EXACT.contains(format), "section preservation boundary differs: " + format);
            require(baseline.resources().orElseThrow().images().size() == (EXACT.contains(format) ? 2 : 0), "discarded overlay image returned");
            var control = first;
            for (int round = 0; round < 3; round++) {
                control = XmlModelRecovery.capture(reopen(control).conversion().prepare(XmlTemplateWriter.conversionTarget()))
                        .resources(resources).prepare();
                var opened = reopen(control).prepare();
                require(opened.document().equals(baseline.document()) && opened.report().issues().equals(baseline.report().issues()), "XML control changed model/history");
            }
            control.writeDocument(directory(format).resolve("cycle-3.xml.zip"));
            var saved = first;
            for (int round = 0; round < 3; round++) {
                int losses = saved.preview().losses().size();
                saved = capture(reopen(saved).conversion(), format, resources, policy, engine);
                require(saved.preview().losses().size() >= losses, "target cycle dropped history");
                if (EXACT.contains(format)) { require(reopen(saved).prepare().document().equals(source), "exact target cycle changed"); }
            }
            publish(saved, "cycle-3");
            var edit = reopen(saved).conversion().edit(editor -> editor.replaceText("ORIGINAL", "EDITED"));
            var edited = capture(edit, format, resources, policy, engine); publish(edited, "edited");
            var current = reopen(edited).prepare();
            String xml = current.resources().orElseThrow().source();
            require(!xml.contains("ORIGINAL") && !DocumentEditor.edit(current.document()).find("INNER-EDITED").matches().isEmpty()
                    && !xml.contains("UNUSED-REGION-SOURCE"), "nested/shared edit returned stale content");
            render(baseline.document(), engine, directory(format).resolve("model-control.pdf"));
            render(current.document(), engine, directory(format).resolve("model-edited.pdf"));
            report(rows, format, "WARN", first, baseline);
            if (!EXACT.contains(format)) {
                var ignore = capture(DocumentConversion.from(source, List.of()), format, resources, policy(ProcessingPolicy.Action.IGNORE), engine);
                require(Arrays.equals(first.documentBytes(), ignore.documentBytes()), "WARN/IGNORE changed standard bytes");
                var ignored = reopen(ignore).prepare();
                require(ignored.document().equals(baseline.document()) && ignored.report().warnings().isEmpty(), "IGNORE changed model/warnings");
                publish(ignore, "ignored"); report(rows, format, "IGNORE", ignore, ignored);
                try { DocumentConversion.from(source, List.of()).prepare(target(format, engine)); throw new IOException("strict target accepted loss"); }
                catch (ProcessingException expected) { rows.append(format).append(",ERROR,0,0,0,0,0,0\n"); }
            }
        }
        Files.writeString(OUTPUT.resolve("policy.csv"), rows);
        // Native DOCX preserves supported sections/variants/nested tables when no overlays are present.
        var nativeSource = new SectionedDocument(source.metadata(), source.sections().stream().map(section ->
                new Section(section.id(), section.pageSettings(), section.blocks(), section.firstPageNumber(),
                        section.pageRegions().toBuilder().background(List.of()).foreground(List.of()).build())).toList());
        var nativeSaved = capture(DocumentConversion.from(nativeSource, List.of()), "DOCX", resources, ProcessingPolicy.STRICT, engine);
        publish(nativeSaved, "native");
        var nativeRead = new DocxAdapter().read(new ByteArrayInputStream(nativeSaved.documentBytes()), DocxAdapter.Policy.STRICT);
        // Native DOCX normalizes default-style cell runs to plain text; XML expands those runs again.
        var normalizedNative = XmlTemplateWriter.from(nativeRead.document()).prepare().bundle().compile().renderSections(java.util.Map.of());
        require(normalizedNative.equals(nativeSource), "native DOCX section/variant/nesting round trip changed");
        render(nativeRead.document(), engine, OUTPUT.resolve("native-docx.pdf"));
        try { DocumentConversion.from(source, List.of()).prepare(engine.conversionTarget("PNG", new RasterBackend(72).singlePage()));
            throw new IOException("single PNG accepted multiple pages"); }
        catch (IllegalArgumentException expected) { require(expected.getMessage().contains("one page"), "wrong single PNG rejection"); }
        matrix(source, resources, policy, engine);
        System.out.println("Section recovery: shared variants/overlays, nested tables, three cycles, real edits, native DOCX and 36 A-B-A cases verified");
    }

    private static void matrix(SectionedDocument source, XmlTemplateBundle resources, ProcessingPolicy policy, DocumentEngine engine) throws IOException {
        var rows = new StringBuilder("a,b,first_losses,b_losses,final_losses,exact_model\n");
        for (String a : FORMATS) {
            var first = capture(DocumentConversion.from(source, List.of()), a, resources, policy, engine);
            for (String b : FORMATS) {
                var middle = capture(reopen(first).conversion(), b, resources, policy, engine);
                var last = capture(reopen(middle).conversion(), a, resources, policy, engine);
                var result = reopen(last).prepare();
                boolean exact = source.equals(result.document());
                require(exact == (EXACT.contains(a) && EXACT.contains(b)), "section matrix exact boundary differs");
                require(last.preview().losses().size() >= middle.preview().losses().size(), "section matrix dropped losses");
                rows.append(a).append(',').append(b).append(',').append(first.preview().losses().size()).append(',')
                        .append(middle.preview().losses().size()).append(',').append(last.preview().losses().size()).append(',').append(exact).append('\n');
            }
        }
        Files.writeString(OUTPUT.resolve("matrix.csv"), rows);
    }
    private static XmlModelRecovery.Saved capture(DocumentConversion.Request request, String format, XmlTemplateBundle resources,
            ProcessingPolicy policy, DocumentEngine engine) throws IOException {
        var prepared = request.policy(policy).prepare(target(format, engine));
        var save = XmlModelRecovery.capture(prepared).resources(resources).policy(policy);
        if (!EXACT.contains(format)) { save.reimportOnLoss((bytes, shared) -> readCurrent(format, bytes, shared)); }
        return save.prepare();
    }
    private static DocumentConversion.Input readCurrent(String format, byte[] bytes, ProcessingPolicy policy) throws IOException {
        if (format.equals("DOCX")) {
            var result = new DocxAdapter().conversion(bytes).policy(policy).prepare(XmlTemplateWriter.conversionTarget()).to(OutputStream.nullOutputStream());
            return new DocumentConversion.Input(result.document(), result.notes(), result.report());
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        var imported = format.equals("HTML") ? HtmlImporter.builder().policy(policy).build().read(text).execute()
                : MarkdownImporter.builder().policy(policy).build().read(text).execute();
        return DocumentConversion.Input.of(imported.document(), imported.report());
    }
    private static ProcessingPolicy policy(ProcessingPolicy.Action action) {
        return ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DEGRADE)
                .feature("METADATA", action, ProcessingPolicy.Treatment.DISCARD)
                .feature("PAGE_NUMBERING", action, ProcessingPolicy.Treatment.DISCARD)
                .feature("HTML_METADATA", action, ProcessingPolicy.Treatment.DISCARD)
                .feature("PAGE_OVERLAYS", action, ProcessingPolicy.Treatment.DISCARD).build();
    }
    private static DocumentConversion.Target target(String format, DocumentEngine engine) {
        return switch (format) {
            case "DOCX" -> new DocxAdapter().conversionTarget(); case "HTML" -> new HtmlAdapter().conversionTarget();
            case "MARKDOWN" -> new MarkdownAdapter().conversionTarget(); case "PDF" -> engine.conversionTarget(format, new PdfBackend());
            case "SVG" -> engine.conversionTarget(format, new SvgBackend()); case "PNG_ZIP" -> engine.conversionTarget(format, new RasterBackend(72));
            default -> throw new IllegalArgumentException("unknown section target");
        };
    }
    private static SectionedDocument source() throws IOException {
        var blue = layer("blue-layer", 0x2255aa, "BLUE-LAYER", 80); var purple = layer("purple-layer", 0x882244, "PURPLE-LAYER", 40);
        var regions = PageRegions.builder().header(band("shared-header", "SHARED-ORIGINAL")).footer(band("footer", "FOOT-BAND"))
                .variant(PageRegions.Variant.FIRST, new PageRegions.Bands(band("first-header", "FIRST-BAND"), PageRegion.EMPTY))
                .variant(PageRegions.Variant.EVEN, new PageRegions.Bands(band("even-header", "EVEN-BAND"), band("even-footer", "EVEN-FOOT")))
                .background(new PageOverlay(24, 340, 80, blue)).background(new PageOverlay(114, 340, 80, blue))
                .foreground(new PageOverlay(44, 340, 40, purple)).build();
        var inner = Table.builder("inner").columns(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1)).cellFormat(CellFormat.of(10))
                .headerCells(cell("INNER-HEAD-A"), cell("INNER-HEAD-B")).row(cell("INNER-ORIGINAL"), cell("INNER-RIGHT")).build();
        var outer = Table.builder("outer").columns(new ColumnWidth.Fraction(3), new ColumnWidth.Fraction(2)).cellFormat(CellFormat.of(10))
                .headerCells(cell("OUTER-HEAD-A"), cell("OUTER-HEAD-B"))
                .row(TableCell.builder().format(CellFormat.of(10)).blocks(List.of(paragraph("before", "BEFORE-INNER"), inner, paragraph("after", "AFTER-INNER"))).build(),
                        TableCell.builder().format(CellFormat.of(10)).content(List.of(text("TALL-CELL"))).rowSpan(2).build())
                .row(TableCell.builder().format(CellFormat.of(10)).content(List.of(text("OUTER-TAIL"))).build()).build();
        var page = PageSettings.builder().size(420, 420).margins(24).build();
        var second = regions.toBuilder().variant(PageRegions.Variant.FIRST, PageRegions.Bands.EMPTY).build();
        return new SectionedDocument(new DocumentMetadata("Section recovery", "Export4j"), List.of(
                new Section("a", page, List.of(paragraph("a1", "BODY-A1 ${literal}"), outer, new PageBreak("break-a1"),
                        paragraph("a2", "BODY-A2"), new PageBreak("break-a2"), paragraph("a3", "BODY-A3")), 2, regions),
                new Section("b", page, List.of(paragraph("b1", "BODY-B1")), 8, second)));
    }
    private static PageRegion band(String id, String label) { return new PageRegion(24, 4, List.of(Paragraph.mixed(id,
            List.of(text(label + " PAGE="), new InlineField(InlineField.Kind.PAGE, "", 18, STYLE), text("/"),
                    new InlineField(InlineField.Kind.PAGES, "", 18, STYLE)), FORMAT))); }
    private static PageRegion layer(String id, int rgb, String alt, double width) throws IOException {
        var pixels = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 4; y++) { for (int x = 0; x < 4; x++) { pixels.setRGB(x, y, rgb); } }
        var bytes = new ByteArrayOutputStream(); require(ImageIO.write(pixels, "png", bytes), "PNG writer unavailable");
        var image = new InlineImage(ImageResource.load(bytes.toByteArray()), width, 12, 0, alt);
        return new PageRegion(18, 0, List.of(Paragraph.mixed(id, List.of(image), FORMAT)));
    }
    private static InlineText text(String text) { return new InlineText(text, STYLE); }
    private static TableCell cell(String text) { return TableCell.builder().format(CellFormat.of(10)).content(List.of(text(text))).build(); }
    private static Paragraph paragraph(String id, String text) { return Paragraph.mixed(id, List.of(text(text)), FORMAT); }
    private static void render(SectionedDocument document, DocumentEngine engine, Path path) throws IOException {
        DocumentConversion.from(document, List.of()).prepare(engine.conversionTarget("PDF", new PdfBackend())).to(path);
    }
    private static String extension(String format) { return switch (format) {
        case "MARKDOWN" -> "md"; case "PNG_ZIP" -> "png.zip"; default -> format.toLowerCase(java.util.Locale.ROOT);
    }; }
    private static Path directory(String format) throws IOException { var path = OUTPUT.resolve(format.toLowerCase(java.util.Locale.ROOT)); Files.createDirectories(path); return path; }
    private static XmlModelRecovery.OpenRequest reopen(XmlModelRecovery.Saved saved) { return XmlModelRecovery.open(saved.format(), saved.documentBytes()).companion(saved.companionBytes().orElseThrow()); }
    private static void publish(XmlModelRecovery.Saved saved, String name) throws IOException {
        var path = directory(saved.format()); String file = name + "." + extension(saved.format());
        saved.writeDocument(path.resolve(file)); saved.writeCompanion(path.resolve(file + ".recovery.zip"));
        if (saved.format().equals("PDF")) { saved.writeEmbedded(new PdfRecoveryCodec(), path.resolve("embedded-" + file)); }
        else { saved.writeEmbedded(path.resolve("embedded-" + file)); }
    }
    private static void report(StringBuilder rows, String format, String action, XmlModelRecovery.Saved saved, XmlModelRecovery.Opened opened) {
        var issues = saved.preview().losses();
        rows.append(format).append(',').append(action).append(',')
                .append(issues.stream().filter(issue -> issue.stage().equals("EXPORT") && !issue.feature().equals(XmlModelRecovery.FEATURE)).count()).append(',')
                .append(issues.stream().filter(issue -> issue.stage().equals("IMPORT")).count()).append(',').append(issues.size()).append(',')
                .append(saved.preview().warnings().size()).append(',').append(opened.resources().orElseThrow().images().size()).append(',')
                .append(opened.document().sections().size()).append('\n');
    }
    private static void require(boolean condition, String message) throws IOException { if (!condition) { throw new IOException(message); } }
}
