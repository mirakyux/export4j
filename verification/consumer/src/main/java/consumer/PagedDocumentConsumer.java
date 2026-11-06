// SPDX-License-Identifier: Apache-2.0
package consumer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.pdf.PdfSpoolBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Navigation;
import com.mirakyux.export4j.components.TableOfContents;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.CrossReference;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
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
import com.mirakyux.export4j.text.ParagraphStyle;

/** Same semantic source through complete and page-at-a-time published APIs; external tools inspect both PDFs. */
public final class PagedDocumentConsumer {
    private static final InlineStyle INLINE = InlineStyle.of(10);
    private static final ParagraphFormat BODY = new ParagraphFormat(10, 13, 0, 0,
            ParagraphAlignment.LEFT, 0, 4, false);
    private static final CellFormat CELL = new CellFormat(9, 12, ParagraphAlignment.LEFT);
    private static final TableFormat TABLE = new TableFormat(PageMargins.uniform(2), 0, 4, true);
    private static final String TEXT_URI = "https://example.org/paged/text";
    private static final String PNG_URI = "https://example.org/paged/png";
    private static final String JPEG_URI = "https://example.org/paged/jpeg";

    private PagedDocumentConsumer() { }

    public static void main(String[] args) throws Exception {
        var font = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Regular.ttf")));
        var fonts = FontGroup.strict(List.of(font));
        Path output = Path.of(System.getProperty("consumer.outputDirectory")).resolve("paged-document");
        Files.createDirectories(output);
        var fixture = fixture();
        var navigation = Navigation.builder()
                .bookmark("Section one", "section-one-heading", 1)
                .bookmark("Nested tables", "nested-heading", 2)
                .bookmark("Section two", "section-two-heading", 1)
                .bookmark("Future target", "future-target", 2)
                .contents(new TableOfContents(true, "PAGED CONTENTS", ParagraphStyle.of(10)))
                .build();
        var engine = DocumentEngine.builder(fonts).navigation(navigation).notes(fixture.notes()).build();
        var full = engine.export(fixture.document()).backend(new PdfBackend()).to(output.resolve("full.pdf"));
        var paged = engine.exportPaged(fixture.document()).backend(new PdfSpoolBackend(output)).to(output.resolve("paged.pdf"));
        var inventory = "{\n"
                + "  \"full\":{\"pages\":" + full.display().pages().size()
                + ",\"bodyPages\":" + full.body().pages().size()
                + ",\"contentsPages\":" + full.contentsPages().size()
                + ",\"diagnostics\":" + full.diagnostics().size() + "},\n"
                + "  \"paged\":{\"pages\":" + paged.pages()
                + ",\"bodyPages\":" + paged.bodyPages()
                + ",\"contentsPages\":" + paged.contentsPages()
                + ",\"diagnostics\":" + paged.diagnostics()
                + ",\"outputBytes\":" + paged.outputBytes() + "},\n"
                + "  \"bodySectionIndices\":" + full.bodySectionIndices() + ",\n"
                + "  \"sections\":[{\"width\":360,\"height\":280,\"firstPageNumber\":1,"
                + "\"headerMarker\":\"S1HEADER\",\"footerMarker\":\"S1FOOTER\"},"
                + "{\"width\":420,\"height\":240,\"firstPageNumber\":7,"
                + "\"headerMarker\":\"S2HEADER\",\"footerMarker\":\"S2FOOTER\"}],\n"
                + "  \"uniqueMarkers\":" + strings(fixture.uniqueMarkers()) + ",\n"
                + "  \"repeatingMarkers\":[\"TABLEHEAD\",\"NESTHEAD\"],\n"
                + "  \"externalUris\":" + strings(List.of(TEXT_URI, PNG_URI, JPEG_URI)) + ",\n"
                + "  \"futureTargetMarker\":\"FUTURETARGET\",\"futureReferenceMarker\":\"FUTUREREF\",\n"
                + "  \"footnoteMarkers\":" + strings(fixture.footnoteMarkers()) + "\n}\n";
        Files.writeString(output.resolve("counts.json"), inventory);
    }

    private static Fixture fixture() throws IOException {
        var unique = new ArrayList<String>();
        var footnoteMarkers = new ArrayList<String>();
        var first = new ArrayList<Block>();
        var second = new ArrayList<Block>();
        var png = picture("png", true);
        var jpeg = picture("jpeg", false);
        first.add(new Heading("section-one-heading", "SECTION ONE", 1));
        unique.add("FUTUREREF");
        first.add(Paragraph.mixed("future-reference", List.of(new InlineText("FUTUREREF page=", INLINE),
                new InlineField(InlineField.Kind.REFERENCE_PAGE, "future-target", 28, INLINE)), BODY));
        unique.add("BLOCKREF");
        first.add(new CrossReference("future-block-reference", "BLOCKREF page=", "future-target", CrossReference.Kind.PAGE, BODY));
        unique.add("ANCHORNOTE");
        first.add(Paragraph.mixed("note-anchor", List.of(new InlineText("ANCHORNOTE", INLINE),
                new InlineField(InlineField.Kind.NOTE_MARKER, "continued-note", 18, INLINE),
                new InlineText(" A footnote continues onto later pages.", INLINE)), BODY));
        var noteText = new StringBuilder();
        for (int index = 1; index <= 64; index++) {
            String marker = String.format(Locale.ROOT, "NOTE%03d", index);
            footnoteMarkers.add(marker);
            unique.add(marker);
            if (!noteText.isEmpty()) { noteText.append('\n'); }
            noteText.append(marker).append(" Continuing footnote preserves source order.");
        }
        for (int index = 1; index <= 12; index++) {
            String marker = String.format(Locale.ROOT, "FIRST%03d", index);
            first.add(markedParagraph("first-" + index, marker, unique));
            if (index % 4 == 0) { first.add(images("first-images-" + index, "IMAGEA" + index, png, jpeg, unique)); }
        }
        first.add(new Heading("nested-heading", "NESTED TABLES", 2));
        var nestedText = new StringBuilder();
        for (int index = 1; index <= 42; index++) {
            String marker = String.format(Locale.ROOT, "NEST%03d", index);
            unique.add(marker);
            if (!nestedText.isEmpty()) { nestedText.append('\n'); }
            nestedText.append(marker).append(" Nested paragraph line.");
        }
        var nested = Table.builder("nested-table").columns(new ColumnWidth.Fraction(1))
                .cellFormat(CELL).format(TABLE).header("NESTHEAD")
                .row(TableCell.builder().format(CELL).blocks(List.of(
                        new Paragraph("nested-long-paragraph", nestedText.toString(), BODY))).build())
                .row("NESTLAST").build();
        unique.add("NESTLAST");
        unique.add("MERGEDROW");
        unique.add("MERGEDCOL");
        var table = Table.builder("outer-table").columns(new ColumnWidth.Fraction(1),
                        new ColumnWidth.Fraction(2), new ColumnWidth.Fraction(2))
                .cellFormat(CELL).format(TABLE).header("TABLEHEAD", "COLUMN B", "COLUMN C")
                .row(TableCell.builder().format(CELL).text("MERGEDROW").rowSpan(2).build(),
                        TableCell.builder().format(CELL).text("MERGEDCOL").columnSpan(2).build())
                .row(TableCell.builder().format(CELL).columnSpan(2).blocks(List.of(nested)).build());
        for (int index = 1; index <= 24; index++) {
            String marker = String.format(Locale.ROOT, "ROW%03d", index);
            unique.add(marker);
            table.row(marker, "Stable source order", "Repeated header remains visible");
        }
        first.add(table.build());
        first.add(markedParagraph("first-end", "FIRSTEND", unique));
        second.add(new Heading("section-two-heading", "SECTION TWO", 1));
        second.add(markedParagraph("second-start", "SECONDSTART", unique));
        unique.add("BACKREF");
        second.add(Paragraph.mixed("back-reference", List.of(new InlineText("BACKREF page=", INLINE),
                new InlineField(InlineField.Kind.REFERENCE_PAGE, "section-one-heading", 28, INLINE)), BODY));
        for (int index = 1; index <= 22; index++) {
            String marker = String.format(Locale.ROOT, "SECOND%03d", index);
            second.add(markedParagraph("second-" + index, marker, unique));
            if (index % 7 == 0) { second.add(images("second-images-" + index, "IMAGEB" + index, png, jpeg, unique)); }
        }
        second.add(new Heading("future-target", "FUTURETARGET", 2));
        second.add(markedParagraph("last-paragraph", "FINALMARKER", unique));
        var document = new SectionedDocument(new DocumentMetadata("Paged document equivalence", "Export4j consumer"), List.of(
                new Section("first-section", new PageSettings(new PageSize(360, 280), PageMargins.uniform(20)), first, 1, regions("S1")),
                new Section("second-section", new PageSettings(new PageSize(420, 240), PageMargins.uniform(20)), second, 7, regions("S2"))));
        return new Fixture(document, List.of(new Note("continued-note", "note-anchor", noteText.toString(), Note.Kind.FOOTNOTE)),
                List.copyOf(unique), List.copyOf(footnoteMarkers));
    }

    private static Paragraph markedParagraph(String id, String marker, List<String> unique) {
        unique.add(marker);
        return new Paragraph(id, marker + " Body paragraph with deterministic wrapping and source order.", BODY);
    }

    private static Paragraph images(String id, String marker, ImageResource png, ImageResource jpeg, List<String> unique) {
        unique.add(marker);
        var format = BODY.toBuilder().lineHeight(24).build();
        return Paragraph.mixed(id, List.of(new InlineText(marker + " LINKTEXT ", INLINE, TEXT_URI),
                new InlineImage(png, 24, 18, 0, "transparent PNG", PNG_URI), new InlineText(" PNG JPEG ", INLINE),
                new InlineImage(jpeg, 24, 18, 0, "RGB JPEG", JPEG_URI)), format);
    }

    private static PageRegions regions(String prefix) {
        return new PageRegions(band(prefix + "header", prefix + "HEADER"), band(prefix + "footer", prefix + "FOOTER"));
    }

    private static PageRegion band(String id, String marker) {
        var style = InlineStyle.of(9);
        var format = ParagraphFormat.builder().fontSize(9).lineHeight(12).spaceAfter(0).build();
        return new PageRegion(18, 4, List.of(Paragraph.mixed(id, List.of(new InlineText(marker + " P=", style),
                new InlineField(InlineField.Kind.PAGE, "", 26, style), new InlineText(" / T=", style),
                new InlineField(InlineField.Kind.PAGES, "", 26, style)), format)));
    }

    private static ImageResource picture(String format, boolean alpha) throws IOException {
        var pixels = new BufferedImage(16, 12, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        try {
            for (int y = 0; y < pixels.getHeight(); y++) {
                for (int x = 0; x < pixels.getWidth(); x++) {
                    int opacity = alpha && (x + y) % 3 == 0 ? 0x70000000 : 0xff000000;
                    pixels.setRGB(x, y, opacity | ((x * 15) << 16) | ((y * 20) << 8) | 0x55);
                }
            }
            try (var encoded = new ByteArrayOutputStream()) {
                if (!ImageIO.write(pixels, format, encoded)) { throw new IOException("image encoder unavailable: " + format); }
                return ImageResource.load(encoded.toByteArray());
            }
        } finally {
            pixels.flush();
        }
    }

    private static String strings(List<String> values) {
        return values.stream().map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private record Fixture(SectionedDocument document, List<Note> notes, List<String> uniqueMarkers, List<String> footnoteMarkers) { }
}
