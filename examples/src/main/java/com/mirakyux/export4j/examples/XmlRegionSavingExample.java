// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplateBundle;
import com.mirakyux.export4j.adapter.template.XmlTemplateWriter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineContent;
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
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.edit.DocumentEditor;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Saves shared page sources, reopens/edits them and checks unchanged layout controls. */
public final class XmlRegionSavingExample {
    private static final Path OUTPUT = Path.of("examples/build/xml-region-saving");
    private static final ParagraphFormat FORMAT = ParagraphFormat.builder().fontSize(10).lineHeight(20).build();
    private static final InlineStyle STYLE = new InlineStyle("bold", 10, 0x224488, 0, false, false);
    private XmlRegionSavingExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var source = document(); var prepared = resources(XmlTemplateWriter.from(source)).prepare();
        if (!prepared.preview().issues().isEmpty()) { throw new IOException("unexpected XML loss"); }
        prepared.to(OUTPUT.resolve("original.zip"));
        var bundle = XmlTemplateBundle.read(Files.readAllBytes(OUTPUT.resolve("original.zip")));
        var reopened = bundle.compile().renderSections(Map.of());
        if (!source.equals(reopened)) { throw new IOException("page regions changed"); }
        render(source, bundle, "source"); render(reopened, bundle, "reopened");
        for (int cycle = 0; cycle < 3; cycle++) {
            var next = resources(XmlTemplateWriter.from(reopened)).prepare().bundle();
            if (!next.source().equals(bundle.source())) { throw new IOException("page source changed between cycles"); }
            bundle = XmlTemplateBundle.read(next.encode()); reopened = bundle.compile().renderSections(Map.of());
            if (!source.equals(reopened)) { throw new IOException("model cycle changed"); }
        }
        bundle.write(OUTPUT.resolve("cycle-3.zip"));
        var edited = DocumentEditor.edit(reopened).replaceText("odd-header", "ODD", "EDIT").execute();
        if (edited.changes().size() != 1) { throw new IOException("shared header edited more than once"); }
        var changed = resources(XmlTemplateWriter.from(edited.document())).prepare().bundle();
        changed.write(OUTPUT.resolve("edited.zip")); render(changed.compile().renderSections(Map.of()), changed, "edited");
        System.out.println("XML regions: three exact cycles, shared sources, variants and five-page controls verified");
    }

    private static XmlTemplateWriter.Request resources(XmlTemplateWriter.Request request) throws IOException {
        return request.font("Regular.ttf", Files.readAllBytes(Path.of("assets/fonts/LiberationSans-Regular.ttf")))
                .font("Bold.ttf", Files.readAllBytes(Path.of("assets/fonts/LiberationSans-Bold.ttf")))
                .license("LiberationSans-OFL.txt", Files.readString(Path.of("assets/fonts/LiberationSans-OFL.txt")));
    }
    private static InlineText text(String value) { return new InlineText(value, STYLE); }
    private static InlineField field(InlineField.Kind kind) { return new InlineField(kind, "", 18, STYLE); }
    private static Paragraph paragraph(String id, List<InlineContent> values) { return Paragraph.mixed(id, values, FORMAT); }
    private static PageRegion band(String id, String label) {
        return new PageRegion(24, 4, List.of(paragraph(id, List.of(text(label + " PAGE="), field(InlineField.Kind.PAGE),
                text("TOTAL="), field(InlineField.Kind.PAGES)))));
    }
    private static ImageResource image(int rgb) throws IOException {
        var image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB); var graphics = image.createGraphics();
        try { graphics.setColor(new Color(rgb)); graphics.fillRect(0, 0, 4, 4); } finally { graphics.dispose(); }
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes); return ImageResource.load(bytes.toByteArray());
    }
    private static SectionedDocument document() throws IOException {
        var blue = new PageRegion(20, 0, List.of(paragraph("blue", List.of(new InlineImage(image(0x2255aa), 80, 16, 0, "Blue ${raw")))));
        var purple = new PageRegion(20, 0, List.of(paragraph("purple", List.of(new InlineImage(image(0x882244), 40, 16, 0, "Purple")))));
        var row = new TableRow(List.of(
                TableCell.mixed(List.of(text("FRONT ${literal}")), CellFormat.of(10)),
                TableCell.mixed(List.of(text("FINAL="), field(InlineField.Kind.PAGE), text("/"), field(InlineField.Kind.PAGES)), CellFormat.of(10))));
        var table = new Table("region-table", List.of(new ColumnWidth.Fixed(140), new ColumnWidth.Fixed(140)), List.of(row), 0, TableFormat.DEFAULT);
        var foreground = new PageRegion(40, 0, List.of(table));
        var regions = PageRegions.builder().header(band("odd-header", "ODD")).footer(band("footer", "FOOT"))
                .variant(PageRegions.Variant.FIRST, new PageRegions.Bands(band("first-header", "FIRST"), new PageRegion(10, 0, List.of())))
                .variant(PageRegions.Variant.EVEN, new PageRegions.Bands(band("even-header", "EVEN"), band("even-footer", "EVEN-FOOT")))
                .background(new PageOverlay(20, 140, 80, blue)).background(new PageOverlay(120, 140, 80, blue))
                .foreground(new PageOverlay(40, 140, 40, purple)).foreground(new PageOverlay(20, 190, 280, foreground)).build();
        var b = regions.toBuilder().inherit(PageRegions.Variant.FIRST).background(List.of()).build();
        var c = regions.toBuilder().variant(PageRegions.Variant.FIRST, PageRegions.Bands.EMPTY).foreground(List.of()).build();
        var settings = PageSettings.builder().size(360, 300).margins(20).build();
        return new SectionedDocument(new DocumentMetadata("Saved page regions", "Export4j"), List.of(
                new Section("a", settings, List.of(body("a1"), new PageBreak("break-a1"), body("a2"), new PageBreak("break-a2"), body("a3")), 2, regions),
                new Section("b", settings, List.of(body("b1")), 8, b), new Section("c", settings, List.of(body("c1")), 10, c)));
    }
    private static Block body(String id) { return paragraph(id, List.of(text("BODY-" + id.toUpperCase(java.util.Locale.ROOT)))); }
    private static void render(SectionedDocument document, XmlTemplateBundle bundle, String name) throws IOException {
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(bundle.fonts().get("Regular.ttf")))))
                .fontRole("bold", FontGroup.strict(List.of(TrueTypeFontLoader.load(bundle.fonts().get("Bold.ttf"))))).build();
        engine.export(document).backend(new PdfBackend()).to(OUTPUT.resolve(name + ".pdf"));
        var policy = ProcessingPolicy.builder().feature("PAGE_OVERLAYS", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD).build();
        var target = DocumentConversion.from(document, List.of()).policy(policy).prepare(new DocxAdapter().conversionTarget());
        var actual = target.to(OUTPUT.resolve(name + ".docx"));
        if (!target.preview().issues().equals(actual.report().issues()) || target.preview().losses().isEmpty()) { throw new IOException("DOCX loss report changed"); }
    }
}
