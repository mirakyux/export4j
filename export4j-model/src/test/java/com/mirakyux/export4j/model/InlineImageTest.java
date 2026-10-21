// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class InlineImageTest {
    private static byte[] png() throws IOException {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB), "png", bytes);
        return bytes.toByteArray();
    }
    @Test void encodedResourcesAreImmutableAndInvalidOrOversizedImagesFail() throws IOException {
        byte[] source = png(); byte[] snapshot = source.clone();
        var resource = ImageResource.load(source); source[0] = 0;
        assertArrayEquals(snapshot, resource.bytes());
        byte[] copy = resource.bytes(); copy[0] = 0;
        assertArrayEquals(snapshot, resource.bytes());
        assertEquals(2, resource.pixelWidth()); assertEquals(3, resource.pixelHeight());
        assertEquals(resource, ImageResource.load(snapshot));
        assertThrows(IOException.class, () -> ImageResource.load(new byte[] {1, 2, 3}));
        byte[] oversized = snapshot.clone(); ByteBuffer.wrap(oversized, 16, 8).putInt(100_000).putInt(100_000);
        assertThrows(IOException.class, () -> ImageResource.load(oversized));
        byte[] animation = new byte[snapshot.length + 12];
        System.arraycopy(snapshot, 0, animation, 0, 33);
        System.arraycopy(new byte[] {'a', 'c', 'T', 'L'}, 0, animation, 37, 4);
        System.arraycopy(snapshot, 33, animation, 45, snapshot.length - 33);
        assertThrows(IOException.class, () -> ImageResource.load(animation));
    }
    @Test void mixedContentRetainsStylesSourceIndicesAndImageLinks() throws IOException {
        var image = new InlineImage(ImageResource.load(png()), 18, 20, 2, "Icon", "https://example.com/icon");
        var before = new InlineText("A😀", InlineStyle.of(12));
        var after = new InlineText("B", new InlineStyle("bold", 12, 0x123456, 0, false, false));
        var paragraph = Paragraph.mixed("p", List.of(before, image, after, image), ParagraphFormat.of(12));
        assertEquals("A😀\ufffcB\ufffc", paragraph.text());
        assertEquals(List.of(3, 5), paragraph.images().stream().map(Paragraph.ImageSpan::sourceIndex).toList());
        assertEquals(List.of(before, image, after, image), paragraph.content());
        assertThrows(IllegalArgumentException.class, () -> new Paragraph("p", "x", ParagraphFormat.of(12), List.of(), List.of(new Paragraph.ImageSpan(0, image))));
        assertThrows(IllegalArgumentException.class, () -> new Paragraph("p", "\ufffc", ParagraphFormat.of(12),
                List.of(new InlineText("\ufffc", InlineStyle.of(12))), List.of(new Paragraph.ImageSpan(0, image))));
        assertThrows(IllegalArgumentException.class, () -> new Paragraph("p", "\ufffc", ParagraphFormat.of(12),
                List.of(new InlineText("\ufffc", InlineStyle.of(14), image.hyperlink())), List.of(new Paragraph.ImageSpan(0, image))));
        assertThrows(IllegalArgumentException.class, () -> new InlineImage(image.resource(), 0, 20, 0, "bad"));
        assertThrows(IllegalArgumentException.class, () -> new InlineImage(image.resource(), 10, 20, 0, "bad\uffff"));
        assertThrows(IllegalArgumentException.class, () -> new InlineImage(image.resource(), 10, 20, 0, "bad", "javascript:alert(1)"));
    }
    @Test void bindingRecalculatesMarkersAndBindsDescriptionsInParagraphsAndCells() throws IOException {
        var image = new InlineImage(ImageResource.load(png()), 18, 20, 0, "${name}");
        var content = List.of(new InlineText("${name}: ", InlineStyle.of(12)), image);
        var paragraph = Paragraph.mixed("p", content, ParagraphFormat.of(12));
        var table = new Table("t", List.of(new ColumnWidth.Fraction(1)), List.of(new TableRow(List.of(TableCell.mixed(content, CellFormat.of(12))))), 0, TableFormat.DEFAULT);
        var template = new DocumentTemplate(DocumentMetadata.EMPTY, PageSettings.A4,
                List.of(new DocumentTemplate.ValueBlock(paragraph), new DocumentTemplate.ValueBlock(table)), DocumentTemplate.Limits.DEFAULT);
        var result = template.render(Map.of("name", "Customer"));
        var bound = (Paragraph) result.blocks().getFirst();
        assertEquals(10, bound.images().getFirst().sourceIndex());
        assertEquals("Customer", bound.images().getFirst().image().altText());
        assertEquals(image.resource(), bound.images().getFirst().image().resource());
        assertEquals(bound.images(), ((Table) result.blocks().get(1)).rows().getFirst().cells().getFirst().images());
    }
}
