// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisplayContractTest {
    @Test
    void snapshotsListsAndRejectsNullElements() {
        List<DrawCommand> commands = new ArrayList<>();
        commands.add(new DrawCommand.FillRect(new Rect(0, 0, 10, 10), RgbColor.BLACK));
        DisplayPage page = new DisplayPage(PageSize.A4, commands);
        commands.clear();
        assertEquals(1, page.commands().size());
        assertThrows(UnsupportedOperationException.class, () -> page.commands().clear());
        List<DisplayPage> pages = new ArrayList<>(List.of(page));
        DisplayDocument document = new DisplayDocument(DocumentMetadata.EMPTY, pages);
        pages.clear();
        assertEquals(1, document.pages().size());
        assertThrows(IllegalArgumentException.class,
                () -> new DisplayDocument(DocumentMetadata.EMPTY, List.of()));
        commands.add(null);
        assertThrows(NullPointerException.class, () -> new DisplayPage(PageSize.A4, commands));
    }

    @Test
    void rejectsMalformedGraphicsStateBeforeBackendUse() {
        assertThrows(IllegalArgumentException.class,
                () -> new DisplayPage(PageSize.A4, List.of(new DrawCommand.Restore())));
        assertThrows(IllegalArgumentException.class,
                () -> new DisplayPage(PageSize.A4, List.of(new DrawCommand.Save())));
        assertThrows(IllegalArgumentException.class,
                () -> new DisplayPage(PageSize.A4, List.of(new DrawCommand.Restore(),
                        new DrawCommand.Save())));
    }

    @Test
    void imageBytesAreOwnedAndDimensionsCannotOverflow() {
        byte[] source = {1, 2, 3};
        RgbImage image = new RgbImage(1, 1, source);
        source[0] = 99;
        byte[] returned = image.pixels();
        returned[1] = 99;
        assertArrayEquals(new byte[]{1, 2, 3}, image.pixels());
        assertThrows(IllegalArgumentException.class,
                () -> new RgbImage(Integer.MAX_VALUE, Integer.MAX_VALUE, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new RgbImage(2, 1, new byte[3]));
    }

    @Test
    void rejectsNonfiniteGeometryAndInvalidTextContracts() {
        assertThrows(IllegalArgumentException.class, () -> new Point(Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new Rect(0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new AffineTransform(1, 0, 0, 1, Double.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> new RgbColor(0, -0.1, 1));
        TextStyle style = new TextStyle(FontFamily.SANS_SERIF, 12, RgbColor.BLACK);
        assertThrows(IllegalArgumentException.class,
                () -> new DrawCommand.Text("first\nsecond", new Point(0, 0), style));
    }

    @Test
    void pathRequiresAMoveBeforeSegmentsAndAfterClose() {
        assertThrows(IllegalArgumentException.class, () -> new VectorPath(List.of(
                new VectorPath.LineTo(new Point(1, 1)))));
        assertThrows(IllegalArgumentException.class, () -> new VectorPath(List.of(
                new VectorPath.MoveTo(new Point(0, 0)), new VectorPath.Close(),
                new VectorPath.LineTo(new Point(1, 1)))));
    }
}
