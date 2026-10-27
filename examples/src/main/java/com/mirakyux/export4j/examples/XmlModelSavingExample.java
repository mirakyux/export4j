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
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageMargins;
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
import com.mirakyux.export4j.model.processing.ProcessingException;
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

/** Semantic model -> existing XML/ZIP -> reopen/edit -> PDF/DOCX, with an unchanged control. */
public final class XmlModelSavingExample {
    private static final Path OUTPUT = Path.of("examples/build/xml-model-saving");
    private static final ParagraphFormat FORMAT = ParagraphFormat.builder().fontSize(11).lineHeight(22)
            .firstLineIndent(4).subsequentLineIndent(2).spaceBefore(2).spaceAfter(4)
            .lineBreakPolicy(LineBreakPolicy.CJK_STRICT).build();
    private static final InlineStyle ACCENT = new InlineStyle("bold", 11, 0x224488, -2, true, false);
    private XmlModelSavingExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var source = document();
        var saved = resources(XmlTemplateWriter.from(source)).prepare();
        saved.to(OUTPUT.resolve("original.zip"));
        var bundle = XmlTemplateBundle.read(Files.readAllBytes(OUTPUT.resolve("original.zip")));
        var reopened = bundle.compile().renderSections(Map.of());
        if (!source.equals(reopened) || !saved.preview().issues().isEmpty()) { throw new IOException("semantic model changed"); }
        render(source, bundle, "source"); render(reopened, bundle, "reopened");
        for (int index = 0; index < 3; index++) {
            var next = resources(XmlTemplateWriter.from(reopened)).prepare().bundle();
            if (!next.source().equals(bundle.source())) { throw new IOException("repeated XML saving changed source"); }
            bundle = XmlTemplateBundle.read(next.encode()); reopened = bundle.compile().renderSections(Map.of());
        }
        bundle.write(OUTPUT.resolve("cycle-3.zip")); render(reopened, bundle, "cycle-3");
        var edited = DocumentEditor.edit(reopened).replaceText("intro", "ORIGINAL-MODEL", "EDITED-MODEL").execute();
        if (edited.changes().size() != 1) { throw new IOException("expected one targeted edit"); }
        var finalBundle = resources(XmlTemplateWriter.from(edited.document())).prepare().bundle();
        finalBundle.write(OUTPUT.resolve("edited.zip"));
        render(XmlTemplateBundle.read(Files.readAllBytes(OUTPUT.resolve("edited.zip"))).compile().renderSections(Map.of()), finalBundle, "edited");
        policies();
        System.out.println("Semantic XML saving: 3 exact model cycles, targeted editing, 4 two-page PDF/DOCX pairs and 3 loss actions");
    }

    private static XmlTemplateWriter.Request resources(XmlTemplateWriter.Request request) throws IOException {
        return request.font("Regular.ttf", Files.readAllBytes(Path.of("assets/fonts/LiberationSans-Regular.ttf")))
                .font("Bold.ttf", Files.readAllBytes(Path.of("assets/fonts/LiberationSans-Bold.ttf")))
                .license("LiberationSans-OFL.txt", Files.readString(Path.of("assets/fonts/LiberationSans-OFL.txt")));
    }
    private static SectionedDocument document() throws IOException {
        var image = new BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { graphics.setColor(new Color(0x2255aa)); graphics.fillRect(0, 0, 32, 16); } finally { graphics.dispose(); }
        var png = new ByteArrayOutputStream(); ImageIO.write(image, "png", png);
        var intro = Paragraph.mixed("intro", List.of(new InlineText("ORIGINAL-MODEL ${literal} ", ACCENT),
                new InlineText("LINK", InlineStyle.of(11), "https://example.com/model"),
                new InlineImage(ImageResource.load(png.toByteArray()), 32, 16, 0, "Blue ${literal}")), FORMAT);
        var fields = Paragraph.mixed("fields", List.of(text("PAGE="), field(InlineField.Kind.PAGE, ""),
                text("TOTAL="), field(InlineField.Kind.PAGES, ""), text("TARGET="), field(InlineField.Kind.REFERENCE_PAGE, "target"),
                text("NUMBER="), field(InlineField.Kind.REFERENCE_NUMBER, "item")), FORMAT);
        var table = new Table("table", List.of(new ColumnWidth.Fixed(140), new ColumnWidth.Fraction(1)), List.of(
                new TableRow(List.of(cell("NAME"), cell("VALUE"))),
                new TableRow(List.of(TableCell.builder().format(CellFormat.of(11)).columnSpan(2).content(List.of(text("MERGED-MODEL"))).build())),
                new TableRow(List.of(TableCell.builder().format(CellFormat.of(11)).rowSpan(2).content(List.of(text("OWNER-MODEL"))).build(), cell("CELL-C"))),
                new TableRow(List.of(cell("CELL-D")))), 1, new TableFormat(new PageMargins(3, 4, 5, 6), 3, 7, false));
        var inner = new Table("inner", List.of(new ColumnWidth.Fixed(220)), List.of(new TableRow(List.of(cell("INNER-MODEL")))), 0, TableFormat.DEFAULT);
        var outer = new Table("outer", List.of(new ColumnWidth.Fixed(320)), List.of(new TableRow(List.of(
                TableCell.builder().format(CellFormat.of(11)).blocks(List.of(paragraph("nested-before", "NESTED-BEFORE"), inner,
                        paragraph("nested-after", "NESTED-AFTER"))).build()))), 0, TableFormat.DEFAULT);
        return new SectionedDocument(new DocumentMetadata("Semantic XML model", "Export4j"), List.of(
                new Section("first", PageSettings.builder().size(420, 500).margins(24).build(), List.of(
                        heading("heading", "XML-MODEL-SAVING"), intro, fields,
                        BulletItem.rich("bullet", List.of(text("BULLET-MODEL")), "-", 2, FORMAT, ACCENT),
                        NumberedItem.rich("item", "steps", List.of(text("NUMBER-MODEL")), 4, FORMAT, 1, ACCENT), table), 3),
                new Section("second", PageSettings.builder().size(460, 400).margins(24).build(), List.of(
                        heading("target", "TARGET-MODEL"), outer, paragraph("end", "END-MODEL")), 7)));
    }
    private static InlineText text(String value) { return new InlineText(value, InlineStyle.of(11)); }
    private static Paragraph paragraph(String id, String value) { return Paragraph.mixed(id, List.of(text(value)), FORMAT); }
    private static Heading heading(String id, String value) { return Heading.rich(id, List.of(text(value)), 1, FORMAT); }
    private static TableCell cell(String value) { return TableCell.mixed(List.of(text(value)), CellFormat.of(11)); }
    private static InlineField field(InlineField.Kind kind, String target) { return new InlineField(kind, target, 18, InlineStyle.of(11)); }
    private record Opaque(String id) implements Block { }
    private static void policies() throws IOException {
        var source = new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(new Opaque("opaque"), paragraph("kept", "KEPT-MODEL")));
        var report = new StringBuilder("action,warnings,losses,feature\n");
        for (var action : ProcessingPolicy.Action.values()) {
            var policy = ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DISCARD).build();
            try {
                var prepared = XmlTemplateWriter.from(source).policy(policy).prepare();
                var actual = prepared.to(OUTPUT.resolve("policy-" + action + ".zip"));
                if (!actual.issues().equals(prepared.preview().issues())) { throw new IOException("loss report changed"); }
                report.append(action).append(',').append(actual.warnings().size()).append(',').append(actual.losses().size()).append(",UNSUPPORTED_BLOCK\n");
            } catch (ProcessingException rejected) {
                if (action != ProcessingPolicy.Action.ERROR) { throw rejected; }
                report.append("ERROR,0,0,UNSUPPORTED_BLOCK\n");
            }
        }
        Files.writeString(OUTPUT.resolve("policy.csv"), report);
    }
    private static void render(SectionedDocument document, XmlTemplateBundle bundle, String name) throws IOException {
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(bundle.fonts().get("Regular.ttf")))))
                .fontRole("bold", FontGroup.strict(List.of(TrueTypeFontLoader.load(bundle.fonts().get("Bold.ttf"))))).build();
        var result = engine.export(document).backend(new PdfBackend()).to(OUTPUT.resolve(name + ".pdf"));
        if (result.display().pages().size() != 2 || !result.diagnostics().isEmpty()) { throw new IOException("unexpected model pagination/diagnostics"); }
        try (var stream = Files.newOutputStream(OUTPUT.resolve(name + ".docx"))) { new DocxAdapter().write(document, stream); }
    }
}
