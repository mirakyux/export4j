// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionedTableContractTest {
    @Test void headingRotationTransformsInkAndHyperlinkBoundsWithoutChangingLayout() throws IOException {
        FontGroup fonts;
        try (var input = getClass().getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
        var engine = DocumentEngine.builder(fonts).build();
        var runs = List.of(new InlineText("ROTATION-LINK", InlineStyle.of(12), "https://example.com/heading"));
        var format = ParagraphFormat.of(12);
        var settings = PageSettings.builder().size(240, 180).margins(24).build();
        var before = engine.layout(new Document(DocumentMetadata.EMPTY, settings,
                List.of(Heading.rich("heading", runs, 1, format))));
        for (double angle : List.of(45.0, 90.0, 180.0)) {
            var rotatedFormat = format.toBuilder().rotation(angle).build();
            var heading = engine.layout(new Document(DocumentMetadata.EMPTY, settings,
                    List.of(Heading.rich("heading", runs, 1, rotatedFormat))));
            var paragraph = engine.layout(new Document(DocumentMetadata.EMPTY, settings,
                    List.of(new Paragraph("heading", runs, rotatedFormat))));
            assertEquals(before.body(), heading.body());
            assertEquals(paragraph.display(), heading.display());
            assertTrue(!before.display().pages().getFirst().commands().equals(heading.display().pages().getFirst().commands()));
            assertEquals(1, heading.display().extras().externalLinks().size());
            assertTrue(!before.display().extras().externalLinks().equals(heading.display().extras().externalLinks()));
        }
    }

    @Test void staticTableAndNestedParagraphRotationsReachTheDrawingState() throws IOException {
        com.mirakyux.export4j.font.FontGroup fonts;
        try (var input = getClass().getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = com.mirakyux.export4j.font.FontGroup.strict(List.of(com.mirakyux.export4j.font.TrueTypeFontLoader.load(
                    input, com.mirakyux.export4j.font.TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
        var child = new com.mirakyux.export4j.model.Paragraph("child", "LEFT", com.mirakyux.export4j.model.ParagraphFormat.builder()
                .fontSize(10).lineHeight(18).spaceAfter(0).rotation(45).build());
        var plain = Table.builder("static").columns(new com.mirakyux.export4j.model.ColumnWidth.Fraction(1))
                .format(format -> format.padding(2).spaceAfter(0))
                .row(TableCell.builder().blocks(List.of(child)).build()).row("BOTTOM").build();
        var rotated = new Table(plain.id(), plain.columns(), plain.rows(), plain.headerRows(), plain.format().toBuilder().rotation(180).build());
        var context = new com.mirakyux.export4j.layout.core.ComponentContext(200, 100, fonts, 0, 0, 1);
        var before = new TableComponent(plain).layout(context);
        var after = new TableComponent(rotated).layout(context);
        assertEquals(before.width(), after.width()); assertEquals(before.height(), after.height()); assertEquals(before.textLines(), after.textLines());
        var transforms = after.commands().stream().filter(com.mirakyux.export4j.graphics.DrawCommand.Transform.class::isInstance)
                .map(com.mirakyux.export4j.graphics.DrawCommand.Transform.class::cast).map(command -> command.matrix()).toList();
        assertTrue(transforms.stream().anyMatch(matrix -> Math.abs(matrix.a() + 1) < 1e-9 && Math.abs(matrix.d() + 1) < 1e-9));
        assertTrue(transforms.stream().anyMatch(matrix -> matrix.a() > 0 && matrix.b() > 0 && matrix.c() < 0));
        assertTrue(!before.commands().equals(after.commands()), "an asymmetric table must visibly rotate");
    }
    @Test void linkedTextCannotBeSilentlyConvertedToStaticDrawing() {
        var cell = TableCell.rich(List.of(new InlineText("Link", InlineStyle.of(12), "https://example.com")), CellFormat.of(12));
        var table = Table.builder("linked").row(cell).build();
        assertThrows(IllegalArgumentException.class, () -> new TableComponent(table));
    }
    @Test void linkedImagesCannotBeSilentlyConvertedToStaticDrawing() throws IOException {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", bytes);
        var image = InlineImage.builder().resource(ImageResource.load(bytes.toByteArray())).width(8).height(8)
                .hyperlink("https://example.com").build();
        var cell = TableCell.mixed(List.of(image), CellFormat.of(12));
        assertThrows(IllegalArgumentException.class, () -> new TableComponent(Table.builder("image").row(cell).build()));
    }
    @Test void fieldsAndRolesRequireSemanticContextWhilePlainRichInkRemainsSupported() {
        var field = InlineField.builder().kind(InlineField.Kind.PAGE).width(20).build();
        var fieldTable = Table.builder("fields").row(TableCell.mixed(List.of(field), CellFormat.of(12))).build();
        assertThrows(IllegalArgumentException.class, () -> new TableComponent(fieldTable));
        var role = InlineStyle.builder().fontRole("bold").fontSize(12).build();
        var roleTable = Table.builder("role").row(TableCell.rich(List.of(new InlineText("Bold", role)), CellFormat.of(12))).build();
        assertThrows(IllegalArgumentException.class, () -> new TableComponent(roleTable));
        var plain = Table.builder("plain").row(TableCell.rich(List.of(new InlineText("Ink", InlineStyle.of(12))), CellFormat.of(12))).build();
        assertDoesNotThrow(() -> new TableComponent(plain));
    }
}
