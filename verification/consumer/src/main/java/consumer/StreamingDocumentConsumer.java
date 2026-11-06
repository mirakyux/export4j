// SPDX-License-Identifier: Apache-2.0
package consumer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.pdf.PdfSpoolBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.PageNumberComponent;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BlockSource;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;

/** Exercises lazy block sources through actually resolved Maven artifacts. */
public final class StreamingDocumentConsumer {
    private static final InlineStyle TEXT = InlineStyle.of(10);
    private static final ParagraphFormat BODY = ParagraphFormat.builder().fontSize(10).lineHeight(14).spaceAfter(4).build();
    private static final CellFormat CELL = CellFormat.builder().fontSize(10).lineHeight(14).build();
    private static final String URI = "https://example.org/stream/document";

    private StreamingDocumentConsumer() { }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(System.getProperty("consumer.outputDirectory")).resolve("streaming-document");
        Files.createDirectories(output);
        var fonts = FontGroup.strict(TrueTypeFontLoader.load(Path.of("../../assets/fonts/LiberationSans-Regular.ttf")));
        var settings = PageSettings.builder().size(300, 360).margins(20).build();
        var engine = DocumentEngine.builder(fonts).pageTemplate(template -> template.footer(footer -> footer.height(16).gap(4)
                .content(PageNumberComponent.builder("STREAM PAGE {page} OF {pages}").fontSize(10).lineHeight(14).build()))).build();
        var blocks = fixture();
        var complete = engine.export(new Document(DocumentMetadata.EMPTY, settings, blocks))
                .backend(new PdfBackend()).to(output.resolve("full.pdf"));
        int pages = complete.display().pages().size();
        var singleOpens = new AtomicInteger();
        var singleCloses = new AtomicInteger();
        var single = engine.exportSemantic(settings, factory(blocks, singleOpens, singleCloses)).expectedPages(pages)
                .backend(new PdfSpoolBackend(output)).to(output.resolve("single.pdf"));
        var replayOpens = new AtomicInteger();
        var replayCloses = new AtomicInteger();
        var replay = engine.exportReplay(settings, factory(blocks, replayOpens, replayCloses))
                .backend(new PdfSpoolBackend(output)).to(output.resolve("replay.pdf"));
        if (single.pages() != pages || replay.pages() != pages || singleOpens.get() != 1 || singleCloses.get() != 1
                || replayOpens.get() != 2 || replayCloses.get() != 2 || single.diagnostics() != 0 || replay.diagnostics() != 0) {
            throw new IllegalStateException("lazy source ownership, pagination or diagnostic contract differs");
        }
        Files.writeString(output.resolve("counts.json"), "{\"pages\":" + pages + ",\"segments\":3,\"rowsPerSegment\":18,"
                + "\"singleOpens\":" + singleOpens + ",\"singleCloses\":" + singleCloses
                + ",\"replayOpens\":" + replayOpens + ",\"replayCloses\":" + replayCloses
                + ",\"diagnostics\":0,\"uri\":\"" + URI + "\"}\n");
    }

    private static BlockSource.Factory factory(List<Block> blocks, AtomicInteger opens, AtomicInteger closes) {
        return () -> {
            opens.incrementAndGet();
            var iterator = blocks.iterator();
            return new BlockSource() {
                @Override public Optional<Block> next() { return iterator.hasNext() ? Optional.of(iterator.next()) : Optional.empty(); }
                @Override public void close() { closes.incrementAndGet(); }
            };
        };
    }

    private static List<Block> fixture() throws IOException {
        var blocks = new ArrayList<Block>();
        var png = image("png", true);
        var jpeg = image("jpeg", false);
        for (int segment = 1; segment <= 3; segment++) {
            String prefix = "s" + segment;
            blocks.add(new Heading(prefix + "-heading", "STREAM SEGMENT " + segment, 1,
                    BODY.toBuilder().pagination(PaginationPolicy.builder().keepWithNext(true).build()).build()));
            blocks.add(Paragraph.mixed(prefix + "-intro", List.of(new InlineText("INTRO-" + segment + " Page ", TEXT),
                    field(InlineField.Kind.PAGE), new InlineText(" of ", TEXT), field(InlineField.Kind.PAGES),
                    new InlineText(" stream link", TEXT, URI)), BODY));
            var table = Table.builder(prefix + "-table").cellFormat(CELL).headerCells(TableCell.mixed(List.of(
                    new InlineText("STREAM HEADER ", TEXT), field(InlineField.Kind.PAGE), new InlineText(" / ", TEXT),
                    field(InlineField.Kind.PAGES)), CELL));
            for (int row = 1; row <= 18; row++) { table.row("SR-" + segment + "-" + row + "-END"); }
            blocks.add(table.build());
            blocks.add(Paragraph.mixed(prefix + "-images", List.of(
                    new InlineImage(png, 50, 25, 0, "transparent", URI), new InlineText(" PNG JPEG ", TEXT),
                    new InlineImage(jpeg, 50, 25, 0, "opaque", URI)), BODY.toBuilder().lineHeight(32).build()));
            blocks.add(new Paragraph(prefix + "-tail", "TAIL-" + segment + "-END", BODY));
            if (segment != 3) { blocks.add(new PageBreak(prefix + "-break")); }
        }
        return List.copyOf(blocks);
    }

    private static InlineField field(InlineField.Kind kind) { return new InlineField(kind, "", 20, TEXT); }

    private static ImageResource image(String format, boolean alpha) throws IOException {
        var image = new BufferedImage(20, 10, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        try {
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    image.setRGB(x, y, ((alpha ? 96 + x * 7 : 255) << 24) | ((x * 12) << 16) | ((y * 24) << 8) | 80);
                }
            }
            var encoded = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, encoded)) { throw new IOException("missing fixture image encoder"); }
            return ImageResource.load(encoded.toByteArray());
        } finally { image.flush(); }
    }
}
