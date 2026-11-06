// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mirakyux.export4j.graphics.DisplayDocument;
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

        assertClassicXrefOffsets(bytes.toByteArray());

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

    @Test
    void preservesRandomNavigationAcrossIndexCacheAndPageTreeBoundaries() throws IOException {
        int pages = 1031;
        int[] targets = {1030, 0, 512};
        var destination = new Point(13.25, 27.5);
        var navigation = new PageNavigation(pages, List.of(
                new DocumentExtras.Bookmark("Last", 1, targets[0], destination),
                new DocumentExtras.Bookmark("First", 1, targets[1], destination),
                new DocumentExtras.Bookmark("Middle", 1, targets[2], destination)), List.of(
                new DocumentExtras.Link(0, new Rect(8.5, 9.75, 20.5, 11.25), 1030, destination),
                new DocumentExtras.Link(1029, new Rect(8.5, 9.75, 20.5, 11.25), 1, destination)));
        var bytes = new ByteArrayOutputStream();
        try (var writer = new PdfSpoolBackend(temporary).open(DocumentMetadata.EMPTY, bytes)) {
            writer.configureNavigation(navigation);
            for (int page = 0; page < pages; page++) {
                writer.accept(new DisplayListBuilder(new PageSize(200, pageHeight(page)))
                        .fillRect(new Rect(10, 10, 20, 20), RgbColor.BLACK).build());
            }
            writer.finish();
        }
        assertClassicXrefOffsets(bytes.toByteArray());
        try (var pdf = Loader.loadPDF(bytes.toByteArray())) {
            assertEquals(pages, pdf.getNumberOfPages());
            var bookmark = pdf.getDocumentCatalog().getDocumentOutline().getFirstChild();
            for (int target : targets) {
                var values = bookmark.getCOSObject().getCOSArray(COSName.DEST);
                assertSame(pdf.getPage(target).getCOSObject(), values.getObject(0));
                assertEquals((float) (pageHeight(target) - destination.y()), ((COSNumber) values.getObject(3)).floatValue());
                bookmark = bookmark.getNextSibling();
            }
            for (var link : navigation.links()) {
                var values = pdf.getPage(link.pageIndex()).getAnnotations().getFirst().getCOSObject().getCOSArray(COSName.DEST);
                assertSame(pdf.getPage(link.targetPageIndex()).getCOSObject(), values.getObject(0));
                assertEquals((float) (pageHeight(link.targetPageIndex()) - destination.y()),
                        ((COSNumber) values.getObject(3)).floatValue());
            }
        }
        assertClean();
    }

    @Test
    void retainsTheExactTemporaryAdmissionBoundaryWithoutCacheBlockPadding() throws IOException {
        var page = new DisplayListBuilder(new PageSize(200, 100.25))
                .fillRect(new Rect(10, 10, 20, 20), RgbColor.BLACK).build();
        var serialized = new ByteArrayOutputStream();
        new PdfBackend().write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(page)), serialized);
        var baseline = new ByteArrayOutputStream();
        try (var writer = new PdfSpoolBackend(temporary).open(DocumentMetadata.EMPTY, baseline)) {
            writer.accept(page); writer.finish();
        }
        long indexBytes = assertClassicXrefOffsets(baseline.toByteArray()) * (long) Long.BYTES;
        long exact = serialized.size() + indexBytes;
        var defaults = PdfSpoolLimits.DEFAULT;
        for (long budget : new long[] {exact - 1, exact}) {
            var limits = new PdfSpoolLimits(defaults.maxPages(), defaults.maxCommands(), defaults.maxObjectsPerPage(),
                    defaults.maxObjects(), defaults.maxPageBytes(), budget, defaults.maxOutputBytes());
            var output = new ByteArrayOutputStream();
            try (var writer = new PdfSpoolBackend(temporary, PdfLimits.DEFAULT, limits).open(DocumentMetadata.EMPTY, output)) {
                if (budget < exact) {
                    assertThrows(IOException.class, () -> writer.accept(page));
                    assertThrows(IllegalStateException.class, writer::finish);
                } else {
                    writer.accept(page); writer.finish();
                    assertClassicXrefOffsets(output.toByteArray());
                }
                long physical = 0;
                try (var files = Files.walk(temporary)) {
                    for (var file : files.filter(Files::isRegularFile).toList()) { physical += Files.size(file); }
                }
                assertTrue(physical <= budget, "cache storage exceeded the configured temporary byte budget");
            }
            assertClean();
        }
    }

    @Test
    void detectsAnUnwrittenReservationAfterLogicalGrowthWithoutWritingPadding() throws Exception {
        try (var writer = new PdfSpoolBackend(temporary).open(DocumentMetadata.EMPTY, new ByteArrayOutputStream())) {
            writer.accept(new DisplayListBuilder(new PageSize(200, 100)).build());
            // Exercise the internal xref invariant: no public operation deliberately leaves a reservation unwritten.
            var reserve = writer.getClass().getDeclaredMethod("reserve");
            reserve.setAccessible(true);
            for (int object = 0; object < 2050; object++) { reserve.invoke(writer); }
            var failure = assertThrows(IOException.class, writer::finish);
            assertTrue(failure.getMessage().contains("unwritten reserved object"), failure.getMessage());
            assertThrows(IllegalStateException.class, writer::finish);
        }
        assertClean();
    }

    private static double pageHeight(int page) { return 100.125 + page % 17 * 0.25; }

    private static int assertClassicXrefOffsets(byte[] bytes) {
        // Inspect raw entries: a tolerant PDF reader could otherwise repair malformed xref offsets.
        String encoded = new String(bytes, StandardCharsets.ISO_8859_1);
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
        return objectCount;
    }

    private void assertClean() throws IOException {
        try (var files = Files.list(temporary)) { assertEquals(0, files.count()); }
    }
}
