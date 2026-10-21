// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.raster.RasterBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.ComponentDocumentLayout;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Navigation;
import com.mirakyux.export4j.components.SectionTemplate;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.Caption;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;

/** Offline final-page fields, inline footnote anchors, bidi and image coexistence fixtures. */
public final class InlineFieldsExample {
    private static final Path OUTPUT = Path.of("examples/build/inline-fields");
    private static final DocumentMetadata META = new DocumentMetadata("Inline semantic fields", "mirakyux");
    private static final PageSettings SETTINGS = new PageSettings(new PageSize(420, 320), PageMargins.uniform(24));
    private static final ParagraphFormat FORMAT = new ParagraphFormat(12, 22, 0, 0, ParagraphAlignment.LEFT, 0, 0, false);
    private InlineFieldsExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var faces = new ArrayList<FontFace>();
        for (var name : List.of("LiberationSans-Regular.ttf", "LXGWWenKaiTC-Regular.ttf")) {
            try (var input = InlineFieldsExample.class.getResourceAsStream("/fonts/" + name)) {
                faces.add(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES));
            }
        }
        var engine = DocumentEngine.builder(FontGroup.strict(faces)).build();
        var fields = Paragraph.mixed("fields", List.of(text("A current="), field(InlineField.Kind.PAGE, "", 28),
                text(" / total="), field(InlineField.Kind.PAGES, "", 28), text("\nB target page="),
                field(InlineField.Kind.REFERENCE_PAGE, "figure", 28), text(" ; number="), field(InlineField.Kind.REFERENCE_NUMBER, "figure", 70)), FORMAT);
        var second = Paragraph.mixed("second", List.of(text("C current="), field(InlineField.Kind.PAGE, "", 28),
                text(" / total="), field(InlineField.Kind.PAGES, "", 28)), FORMAT);
        var last = Paragraph.mixed("last", List.of(text("D current="), field(InlineField.Kind.PAGE, "", 28),
                text(" / total="), field(InlineField.Kind.PAGES, "", 28)), FORMAT);
        var document = new SectionedDocument(META, List.of(
                new Section("a", SETTINGS, List.of(new Heading("heading", "Final fields", 1, FORMAT), fields, new PageBreak("break"), second), 0),
                new Section("b", SETTINGS, List.of(new Caption("figure", "Figure", "Reference target", FORMAT), last), 7)));
        write("fields", engine.layout(document, templates(document), Navigation.fromHeadings(document), List.of()));

        var pixels = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 4; y++) { for (int x = 0; x < 4; x++) { pixels.setRGB(x, y, 0x285080); } }
        var bytes = new ByteArrayOutputStream(); ImageIO.write(pixels, "png", bytes);
        var cellFormat = new CellFormat(12, 22, ParagraphAlignment.LEFT);
        var tableContent = new ArrayList<InlineContent>();
        for (int i = 1; i <= 12; i++) {
            tableContent.add(text(String.format(java.util.Locale.ROOT, "CELL-%02d page=", i)));
            tableContent.add(field(InlineField.Kind.PAGE, "", 24));
            tableContent.add(i == 12 ? new InlineText(" LINK", InlineStyle.of(12), "https://example.com/table-field") : text("\n"));
        }
        var header = new TableRow(List.of(
                TableCell.mixed(List.of(text("HEADER page="), field(InlineField.Kind.PAGE, "", 24), text(" total="), field(InlineField.Kind.PAGES, "", 24)), cellFormat),
                TableCell.mixed(List.of(text("TARGET="), field(InlineField.Kind.REFERENCE_PAGE, "table-caption", 24), text(" "), field(InlineField.Kind.REFERENCE_NUMBER, "table-caption", 70)), cellFormat)));
        var data = new TableRow(List.of(TableCell.mixed(tableContent, cellFormat),
                TableCell.mixed(List.of(new InlineImage(ImageResource.load(bytes.toByteArray()), 14, 14, 0, "Blue cell image"), text(" MEDIA")), cellFormat)));
        var table = new Table("field-table", List.of(new ColumnWidth.Auto(175, 190), new ColumnWidth.Fraction(1)),
                List.of(header, data), 1, new TableFormat(PageMargins.uniform(3), 0, 0, true));
        var tableSettings = new PageSettings(new PageSize(420, 240), PageMargins.uniform(20));
        var tables = new SectionedDocument(META, List.of(
                new Section("table-body", tableSettings, List.of(new Heading("table-heading", "Fields in split cells", 1, FORMAT), table), 0),
                new Section("table-target", tableSettings, List.of(new Caption("table-caption", "Figure", "Table reference target", FORMAT)), 7)));
        write("tables", engine.layout(tables, templates(tables), Navigation.fromHeadings(tables), List.of()));
        var content = new ArrayList<InlineContent>();
        content.add(text("Opening "));
        content.add(new InlineImage(ImageResource.load(bytes.toByteArray()), 12, 12, 0, "Blue marker"));
        content.add(marker("early")); content.add(text(" sentence\n"));
        for (int i = 1; i <= 13; i++) { content.add(text("NOTE-BODY-" + String.format(java.util.Locale.ROOT, "%02d", i) + "\n")); }
        content.add(text("Closing ")); content.add(marker("late")); content.add(text(" paragraph"));
        var notes = new SectionedDocument(META, List.of(new Section("notes",
                new PageSettings(new PageSize(320, 200), PageMargins.uniform(20)), List.of(Paragraph.mixed("note-body", content, FORMAT)), 0)));
        write("notes", engine.layout(notes, templates(notes), Navigation.EMPTY, List.of(
                new Note("early", "note-body", "Early note stays with the opening line.", Note.Kind.FOOTNOTE),
                new Note("late", "note-body", "Late note follows the closing line.", Note.Kind.FOOTNOTE))));

        try (var input = InlineFieldsExample.class.getResourceAsStream("/fonts/NotoNaskhArabic-Regular.ttf")) {
            faces.add(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES));
        }
        var bidiEngine = DocumentEngine.builder(FontGroup.strict(faces)).build();
        var bidi = Paragraph.mixed("bidi", List.of(text("中文 العربية "), field(InlineField.Kind.PAGE, "", 28),
                text(" a\u0301 / "), field(InlineField.Kind.PAGES, "", 28), text(" fin")), FORMAT);
        write("bidi", bidiEngine.layout(new Document(META, SETTINGS, List.of(bidi))));
    }

    private static List<SectionTemplate> templates(SectionedDocument source) {
        return source.sections().stream().map(s -> SectionTemplate.uniform(PageTemplate.EMPTY)).toList();
    }
    private static InlineText text(String value) { return new InlineText(value, InlineStyle.of(12)); }
    private static InlineField field(InlineField.Kind kind, String target, double width) { return new InlineField(kind, target, width, InlineStyle.of(12)); }
    private static InlineField marker(String target) {
        return new InlineField(InlineField.Kind.NOTE_MARKER, target, 18, new InlineStyle("", 8, 0x285080, -3.6, false, false));
    }
    private static void write(String name, ComponentDocumentLayout result) throws IOException {
        var display = result.display();
        try (var out = Files.newOutputStream(OUTPUT.resolve(name + ".pdf"))) { new PdfBackend().write(display, out); }
        // Static previews deliberately contain no interactions; PDF retains reference links and contents.
        var preview = new com.mirakyux.export4j.graphics.DisplayDocument(display.metadata(), display.pages());
        try (var out = Files.newOutputStream(OUTPUT.resolve(name + ".svg"))) { new SvgBackend().write(preview, out); }
        try (var out = Files.newOutputStream(OUTPUT.resolve(name + ".zip"))) { new RasterBackend(144).write(preview, out); }
        System.out.println(name + ": " + display.pages().size() + " pages");
    }
}
