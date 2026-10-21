// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
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

class PositionedTableContractTest {
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
