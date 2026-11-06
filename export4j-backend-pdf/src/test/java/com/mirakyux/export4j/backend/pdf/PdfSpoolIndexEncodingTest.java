// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.PageNavigation;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;

class PdfSpoolIndexEncodingTest {
    @TempDir Path temporary;

    @Test
    void writesExactClassicXrefEntriesAndPreservesFractionalNavigationHeights() throws IOException {
        var destination = new Point(13.25, 27.5);
        var navigation = new PageNavigation(2,
                List.of(new DocumentExtras.Bookmark("Target", 1, 1, destination)),
                List.of(new DocumentExtras.Link(0, new Rect(8.5, 9.75, 20.5, 11.25), 1, destination)));
        var bytes = new ByteArrayOutputStream();
        try (var writer = new PdfSpoolBackend(temporary).open(DocumentMetadata.EMPTY, bytes)) {
            writer.configureNavigation(navigation);
            writer.accept(new DisplayListBuilder(new PageSize(200, 100.25))
                    .fillRect(new Rect(10, 10, 20, 20), RgbColor.BLACK).build());
            writer.accept(new DisplayListBuilder(new PageSize(200, 300.625))
                    .fillRect(new Rect(10, 10, 20, 20), RgbColor.BLACK).build());
            writer.finish();
        }

        // Inspect raw entries: a tolerant PDF reader could otherwise repair malformed xref offsets.
        String encoded = bytes.toString(StandardCharsets.ISO_8859_1);
        int startMarker = encoded.lastIndexOf("\nstartxref\n") + "\nstartxref\n".length();
        int xrefOffset = Integer.parseInt(encoded.substring(startMarker, encoded.indexOf('\n', startMarker)));
        String[] xref = encoded.substring(xrefOffset).split("\n");
        assertEquals("xref", xref[0]);
        String[] subsection = xref[1].split(" ");
        assertEquals("0", subsection[0]);
        int objectCount = Integer.parseInt(subsection[1]);
        assertEquals("0000000000 65535 f ", xref[2]);
        for (int object = 1; object < objectCount; object++) {
            String entry = xref[object + 2];
            assertTrue(entry.matches("[0-9]{10} 00000 n "), entry);
            int position = Integer.parseInt(entry.substring(0, 10));
            assertTrue(encoded.startsWith(object + " 0 obj\n", position), "incorrect xref offset for " + object);
        }

        try (var pdf = Loader.loadPDF(bytes.toByteArray())) {
            assertEquals(2, pdf.getNumberOfPages());
            var bookmark = pdf.getDocumentCatalog().getDocumentOutline().getFirstChild().getCOSObject().getCOSArray(COSName.DEST);
            assertSame(pdf.getPage(1).getCOSObject(), bookmark.getObject(0));
            assertEquals(13.25f, ((COSNumber) bookmark.getObject(2)).floatValue());
            assertEquals(273.125f, ((COSNumber) bookmark.getObject(3)).floatValue());
            var annotation = pdf.getPage(0).getAnnotations().getFirst();
            assertEquals(79.25f, annotation.getRectangle().getLowerLeftY());
            var link = annotation.getCOSObject().getCOSArray(COSName.DEST);
            assertSame(pdf.getPage(1).getCOSObject(), link.getObject(0));
            assertEquals(273.125f, ((COSNumber) link.getObject(3)).floatValue());
        }
    }
}
