// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Note;
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

class InlineImagesTest {
    private static final PageSettings PAGE = new PageSettings(new PageSize(220, 180), PageMargins.uniform(12));
    private static final ParagraphFormat FORMAT = new ParagraphFormat(12, 32, 0, 0, ParagraphAlignment.LEFT, 0, 0, false);
    private static DocumentEngine engine() throws IOException {
        try (var input = InlineImagesTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            return DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)))).build();
        }
    }
    private static InlineImage image() throws IOException {
        var data = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", data);
        return new InlineImage(ImageResource.load(data.toByteArray()), 22, 22, 2, "Icon", "https://example.com/icon");
    }
    @Test void paragraphPaginationAndFootnoteLoweringKeepEachImageAndExactLinkRectangle() throws IOException {
        var image = image(); var content = new ArrayList<InlineContent>();
        for (int i = 0; i < 12; i++) { content.add(new InlineText("LINE-" + i + " ", InlineStyle.of(12))); content.add(image); content.add(new InlineText("\n", InlineStyle.of(12))); }
        var paragraph = Paragraph.mixed("p", content, FORMAT);
        var document = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", PAGE, List.of(paragraph), 0)));
        var result = engine().layout(document, List.of(SectionTemplate.uniform(PageTemplate.EMPTY)), Navigation.EMPTY,
                List.of(new Note("n", "p", "Image paragraph footnote", Note.Kind.FOOTNOTE)));
        assertTrue(result.body().pages().size() > 1);
        var boxes = result.body().pages().stream().flatMap(p -> p.elements().stream()).map(ParagraphFragment.class::cast)
                .flatMap(p -> p.layout().lines().stream()).flatMap(l -> l.images().stream()).toList();
        assertEquals(paragraph.images().stream().map(Paragraph.ImageSpan::sourceIndex).toList(), boxes.stream().map(b -> b.sourceIndex()).toList());
        var commands = result.display().pages().stream().flatMap(p -> p.commands().stream()).filter(DrawCommand.Image.class::isInstance).map(DrawCommand.Image.class::cast).toList();
        assertEquals(12, commands.size()); assertEquals(12, result.display().extras().externalLinks().size());
        assertEquals(commands.stream().map(DrawCommand.Image::bounds).toList(), result.display().extras().externalLinks().stream().map(l -> l.bounds()).toList());
        assertTrue(result.contextualDiagnostics().isEmpty());
    }
    @Test void splitCellsKeepBodyImagesOnceAndRepeatHeaderImagesWithTheirLinks() throws IOException {
        var image = image(); var format = new CellFormat(12, 32, ParagraphAlignment.LEFT);
        var header = TableCell.mixed(List.of(new InlineText("Header ", InlineStyle.of(12)), image), format);
        var body = new ArrayList<InlineContent>();
        for (int i = 0; i < 12; i++) { body.add(image); body.add(new InlineText(" ROW-" + i + "\n", InlineStyle.of(12))); }
        var table = new Table("t", List.of(new ColumnWidth.Auto(40, 180)),
                List.of(new TableRow(List.of(header)), new TableRow(List.of(TableCell.mixed(body, format)))), 1,
                new TableFormat(PageMargins.uniform(2), 0, 0, true));
        var result = engine().layout(new Document(DocumentMetadata.EMPTY, PAGE, List.of(table)));
        var fragments = result.body().pages().stream().flatMap(p -> p.elements().stream()).map(TableFragment.class::cast).toList();
        assertTrue(fragments.size() > 1);
        assertEquals(12, fragments.stream().flatMap(t -> t.rows().stream()).filter(r -> !r.header()).flatMap(r -> r.cells().stream())
                .flatMap(c -> c.layout().lines().stream()).flatMap(l -> l.images().stream()).count());
        assertEquals(12 + fragments.size(), result.display().pages().stream().flatMap(p -> p.commands().stream()).filter(DrawCommand.Image.class::isInstance).count());
        assertEquals(12 + fragments.size(), result.display().extras().externalLinks().size());
    }
    @Test void canonicalMarkersWithoutStyledRunsStillProduceLinkedImages() throws IOException {
        var image = image();
        var paragraph = new Paragraph("p", "\ufffc", FORMAT, List.of(), List.of(new Paragraph.ImageSpan(0, image)));
        assertEquals(1, engine().layout(new Document(DocumentMetadata.EMPTY, PAGE, List.of(paragraph))).display().extras().externalLinks().size());
        var sectioned = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", PAGE, List.of(paragraph), 0)));
        var withNote = engine().layout(sectioned, List.of(SectionTemplate.uniform(PageTemplate.EMPTY)), Navigation.EMPTY,
                List.of(new Note("n", "p", "Canonical image note", Note.Kind.FOOTNOTE)));
        assertEquals(1, withNote.display().extras().externalLinks().size());
        assertEquals(1, withNote.display().pages().stream().flatMap(p -> p.commands().stream()).filter(DrawCommand.Image.class::isInstance).count());
    }
}
