// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class InlineContentsTest {
    private static final InlineStyle STYLE = InlineStyle.of(12);
    private static final ParagraphFormat PARAGRAPH = ParagraphFormat.of(12);
    private static final CellFormat CELL = CellFormat.of(12);

    @Test
    void singleTextContentReusesSourceWithoutChangingExplicitRuns() {
        var source = new String(new char[] {'A', '\ud83d', '\ude00', 'B'});
        var run = new InlineText(source, STYLE, "https://example.com/text");
        var paragraph = Paragraph.mixed("p", List.of(run), PARAGRAPH);
        var cell = TableCell.mixed(List.of(run), CELL);

        assertSame(source, paragraph.text());
        assertSame(source, cell.text());
        assertEquals(List.of(run), paragraph.inlines());
        assertEquals(List.of(run), cell.inlines());
        assertEquals(paragraph, new Paragraph("p", List.of(run), PARAGRAPH));
        assertEquals(cell, TableCell.rich(List.of(run), CELL));
    }

    @Test
    void matchingUsesCompleteUtf16SourceAndSnapshotsEmptyRunBoundaries() {
        var empty = new InlineText("", STYLE);
        var before = new InlineText("A😀", STYLE);
        var after = new InlineText("B", new InlineStyle("emphasis", 12, 0x123456, 0, false, false));
        var expected = List.of(empty, before, empty, after, empty);
        var mutable = new ArrayList<>(expected);
        var paragraph = new Paragraph("p", "A😀B", PARAGRAPH, mutable);
        var cell = new TableCell("A😀B", CELL, mutable);
        mutable.clear();

        assertEquals(expected, paragraph.inlines());
        assertEquals(expected, cell.inlines());
        assertEquals("A😀B", paragraph.text());
        assertEquals("A😀B", cell.text());
        assertThrows(UnsupportedOperationException.class, () -> paragraph.inlines().clear());
        assertThrows(UnsupportedOperationException.class, () -> cell.inlines().clear());
        for (var text : List.of("A😀", "A😀BC", "A😀C")) {
            assertRejected(text, expected, List.of(), List.of());
        }
        assertRejected("A😀B", List.of(new InlineText("A😀BC", STYLE)), List.of(), List.of());
    }

    @Test
    void mixedObjectsRetainOriginalUtf16PositionsAndBuilderSnapshots() throws IOException {
        var image = image();
        var field = new InlineField(InlineField.Kind.PAGE, "", 24, STYLE);
        var leading = new InlineText("A😀", STYLE);
        var empty = new InlineText("", STYLE);
        var trailing = new InlineText("Z", STYLE);
        List<InlineContent> input = new ArrayList<>(List.of(empty, leading, image, field, trailing, empty));
        var paragraph = Paragraph.mixed("p", input, PARAGRAPH);
        var builder = TableCell.builder().format(CELL).columnSpan(2).content(input);
        input.clear();
        var cell = builder.build();

        assertEquals("A😀\ufffc\ufffcZ", paragraph.text());
        assertEquals(paragraph.text(), cell.text());
        assertEquals(List.of(new Paragraph.ImageSpan(3, image)), paragraph.images());
        assertEquals(List.of(new Paragraph.FieldSpan(4, field)), paragraph.fields());
        assertEquals(paragraph.images(), cell.images());
        assertEquals(paragraph.fields(), cell.fields());
        assertEquals(paragraph.inlines(), cell.inlines());
        assertEquals(6, paragraph.inlines().size());
        assertEquals(2, cell.columnSpan());
        assertEquals(image.hyperlink(), paragraph.inlines().get(2).hyperlink());
        assertEquals(field.style(), paragraph.inlines().get(3).style());
    }

    @Test
    void sharedValidationRejectsMarkerCollisionsMisalignmentAndWrongObjectStyles() throws IOException {
        var image = image();
        var field = new InlineField(InlineField.Kind.PAGE, "", 24, STYLE);
        var imageSpan = new Paragraph.ImageSpan(0, image);
        var fieldSpan = new Paragraph.FieldSpan(0, field);
        var imageRun = new InlineText("\ufffc", STYLE, image.hyperlink());
        var fieldRun = new InlineText("\ufffc", STYLE);

        assertRejected("x", List.of(new InlineText("x", STYLE)), List.of(imageSpan), List.of());
        assertRejected("😀\ufffc", List.of(new InlineText("😀\ufffc", STYLE, image.hyperlink())),
                List.of(new Paragraph.ImageSpan(1, image)), List.of());
        assertRejected("\ufffc", List.of(imageRun), List.of(imageSpan, imageSpan), List.of());
        assertRejected("\ufffc", List.of(imageRun), List.of(imageSpan), List.of(fieldSpan));
        assertRejected("\ufffc", List.of(fieldRun), List.of(imageSpan), List.of());
        assertRejected("\ufffc", List.of(new InlineText("\ufffc", InlineStyle.of(14), image.hyperlink())),
                List.of(imageSpan), List.of());
        assertRejected("\ufffc", List.of(), List.of(), List.of(fieldSpan));
        assertRejected("\ufffc", List.of(imageRun), List.of(), List.of(fieldSpan));
        assertRejected("\ufffc", List.of(new InlineText("\ufffc", InlineStyle.of(14))),
                List.of(), List.of(fieldSpan));

        var note = new InlineField(InlineField.Kind.NOTE_MARKER, "note", 24, STYLE);
        assertEquals(note, Paragraph.mixed("p", List.of(note), PARAGRAPH).fields().getFirst().field());
        assertThrows(IllegalArgumentException.class, () -> TableCell.mixed(List.of(note), CELL));
        assertThrows(IllegalArgumentException.class, () -> TableCell.builder().format(CELL).content(List.of(note)).build());
    }

    private static void assertRejected(String source, List<InlineText> runs, List<Paragraph.ImageSpan> images,
            List<Paragraph.FieldSpan> fields) {
        assertThrows(IllegalArgumentException.class, () -> new Paragraph("p", source, PARAGRAPH, runs, images, fields));
        assertThrows(IllegalArgumentException.class, () -> new TableCell(source, CELL, runs, images, fields));
    }

    private static InlineImage image() throws IOException {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB), "png", bytes);
        return new InlineImage(ImageResource.load(bytes.toByteArray()), 12, 18, 0, "Icon", "https://example.com/icon");
    }
}
