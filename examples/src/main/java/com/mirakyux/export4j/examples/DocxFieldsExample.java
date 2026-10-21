// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Navigation;
import com.mirakyux.export4j.components.SectionTemplate;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
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

/** Native Word fields and an independently paginated PDF from the strictly imported semantic model. */
public final class DocxFieldsExample {
    private static final Path OUTPUT = Path.of("examples/build/docx-fields");
    private static final ParagraphFormat FORMAT = new ParagraphFormat(12, 22, 0, 0, ParagraphAlignment.LEFT, 0, 0, false);
    private DocxFieldsExample() { }
    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var bytes = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB), "png", bytes);
        List<InlineContent> contents = List.of(text("BODY PAGE="), field(InlineField.Kind.PAGE, ""), text(" TOTAL="), field(InlineField.Kind.PAGES, ""),
                text("\nTARGET PAGE="), field(InlineField.Kind.REFERENCE_PAGE, "numbered-target"), text(" NUMBER="), field(InlineField.Kind.REFERENCE_NUMBER, "numbered-target"),
                text("\nTABLE PAGE="), field(InlineField.Kind.REFERENCE_PAGE, "table"), text(" "),
                new InlineImage(ImageResource.load(bytes.toByteArray()), 12, 12, 0, "Black marker"),
                new InlineText(" LINK", InlineStyle.of(12), "https://example.com/docx-fields"));
        var cellFormat = new CellFormat(12, 22, ParagraphAlignment.LEFT);
        var header = TableCell.mixed(List.of(text("CELL TOTAL="), field(InlineField.Kind.PAGES, ""),
                text("\nTARGET="), field(InlineField.Kind.REFERENCE_PAGE, "numbered-target"), text(" NUMBER="), field(InlineField.Kind.REFERENCE_NUMBER, "numbered-target")), cellFormat);
        var rows = new java.util.ArrayList<TableRow>(); rows.add(new TableRow(List.of(header)));
        rows.add(new TableRow(List.of(TableCell.mixed(List.of(text("ROW-1 PAGE="), field(InlineField.Kind.PAGE, "")), cellFormat))));
        for (int i = 2; i <= 7; i++) { rows.add(new TableRow(List.of(new TableCell("ROW-" + i, cellFormat)))); }
        var settings = new PageSettings(new PageSize(420, 320), PageMargins.uniform(24));
        var table = new Table("table", List.of(new ColumnWidth.Fraction(1)), rows, 1, new TableFormat(PageMargins.uniform(3), 0, 0, false));
        var source = new SectionedDocument(new DocumentMetadata("Native DOCX fields", "mirakyux"), List.of(
                new Section("body", settings, List.of(Paragraph.mixed("fields", contents, FORMAT), table), 0),
                new Section("target-section", settings, List.of(new NumberedItem("numbered-target", "list", "NUMBER-TARGET", 4, FORMAT)), 7)));
        var adapter = new DocxAdapter();
        try (var output = Files.newOutputStream(OUTPUT.resolve("fields.docx"))) { adapter.write(source, output); }
        DocxAdapter.ReadResult imported;
        try (var input = Files.newInputStream(OUTPUT.resolve("fields.docx"))) { imported = adapter.read(input, DocxAdapter.Policy.STRICT); }
        if (!imported.diagnostics().isEmpty() || !source.equals(imported.document())) { throw new IOException("DOCX semantic fields did not roundtrip"); }
        try (var font = DocxFieldsExample.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(font, TrueTypeFontLoader.DEFAULT_MAX_BYTES)))).build();
            var layout = engine.layout(imported.document(), imported.document().sections().stream().map(s -> SectionTemplate.uniform(PageTemplate.EMPTY)).toList(), Navigation.EMPTY, List.of());
            try (var output = Files.newOutputStream(OUTPUT.resolve("imported.pdf"))) { new PdfBackend().write(layout.display(), output); }
            System.out.println("DOCX fields: " + layout.display().pages().size() + " imported PDF pages, " + layout.display().extras().links().size() + " links");
        }
    }
    private static InlineText text(String value) { return new InlineText(value, InlineStyle.of(12)); }
    private static InlineField field(InlineField.Kind kind, String target) { return new InlineField(kind, target, 30, InlineStyle.of(12)); }
}
