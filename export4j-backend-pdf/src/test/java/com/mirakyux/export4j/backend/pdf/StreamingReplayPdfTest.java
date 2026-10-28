// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.StreamingReplayPlan;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.*;

import static org.junit.jupiter.api.Assertions.*;

class StreamingReplayPdfTest {
    @TempDir Path directory;
    private static FontGroup fonts() throws IOException {
        try (var input = StreamingReplayPdfTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            return FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }
    private static final CellFormat FORMAT = new CellFormat(10, 14, ParagraphAlignment.LEFT);
    private static StreamingTable table() {
        return StreamingTable.builder("stream").columns(100, 100).semanticCells(true).allowRowSpans(true).allowRowSplit(true)
                .pageSettings(new PageSettings(new PageSize(240, 180), PageMargins.uniform(10))).padding(2)
                .header(new TableRow(List.of(TableCell.mixed(List.of(new InlineText("Page ", InlineStyle.of(10)),
                        new InlineField(InlineField.Kind.PAGE, "", 18, InlineStyle.of(10)), new InlineText(" / ", InlineStyle.of(10)),
                        new InlineField(InlineField.Kind.PAGES, "", 18, InlineStyle.of(10))), FORMAT), new TableCell("HEADER", FORMAT)))).build();
    }
    private static List<TableRow> rows() {
        var paragraph = Paragraph.mixed("nested-first", List.of(new InlineText("Nested ", InlineStyle.of(10), "https://example.org/nested"),
                new InlineField(InlineField.Kind.REFERENCE_PAGE, "last", 22, InlineStyle.of(10))), ParagraphFormat.of(10));
        var inner = Table.builder("nested-table").columns(new ColumnWidth.Fraction(1))
                .format(new TableFormat(PageMargins.uniform(2), 0, 0, true)).header("INNER")
                .row(TableCell.builder().blocks(List.of(paragraph, new Paragraph("nested-long", "Nested content ".repeat(50), ParagraphFormat.of(10)))).build()).build();
        return List.of(new TableRow(List.of(TableCell.builder().format(FORMAT).rowSpan(2)
                        .content(List.of(new InlineText("Owner ", InlineStyle.of(10)),
                                new InlineField(InlineField.Kind.REFERENCE_NUMBER, "note", 22, InlineStyle.of(10)),
                                new InlineText("\n" + "Owner line\n".repeat(22), InlineStyle.of(10)))).build(),
                TableCell.builder().blocks(List.of(inner)).build())),
                new TableRow(List.of(new TableCell("Second physical row", FORMAT))),
                new TableRow(List.of(new TableCell("LAST TARGET", FORMAT), new TableCell("END", FORMAT))));
    }
    private static TableRowSource source(List<TableRow> rows, AtomicInteger closes) {
        return new TableRowSource() {
            private int next;
            @Override public Optional<TableRow> next() { return next < rows.size() ? Optional.of(rows.get(next++)) : Optional.empty(); }
            @Override public void close() { closes.incrementAndGet(); }
        };
    }

    @Test void probesReplaysNestedSpanGroupsTotalsFutureReferencesContentsAndFootnotes() throws Exception {
        var opens = new AtomicInteger(); var closes = new AtomicInteger();
        var plan = StreamingReplayPlan.builder().columns(List.of(new ColumnWidth.Auto(80, 110), new ColumnWidth.Fraction(1)), 2, 200_000)
                .rowTarget("last", 2, "III").blockTarget("nested", "nested-first", "1")
                .bookmark("Nested entry", "nested", 1).bookmark("Last entry", "last", 1).contents("Contents")
                .footnote("note", 0, "Footnote attached once to the first group page.").build();
        var output = new ByteArrayOutputStream();
        var result = DocumentEngine.builder(fonts()).build().exportReplay(table(), () -> { opens.incrementAndGet(); return source(rows(), closes); })
                .plan(plan).backend(new PdfSpoolBackend(directory)).to(output);
        assertEquals(3, opens.get()); assertEquals(3, closes.get()); assertEquals(3, result.bodyRows());
        assertTrue(result.pages() >= 4);
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            assertEquals(result.pages(), pdf.getNumberOfPages());
            var text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Contents")); assertTrue(text.contains("LAST TARGET"));
            assertEquals(1, text.split("Footnote attached", -1).length - 1);
            assertTrue(text.contains("/ " + result.pages()));
            assertNotNull(pdf.getDocumentCatalog().getDocumentOutline());
            var last = pdf.getDocumentCatalog().getDocumentOutline().getFirstChild().getNextSibling();
            assertEquals("Last entry", last.getTitle());
            var target = ((PDPageXYZDestination) last.getDestination()).retrievePageNumber();
            assertTrue(target > 1);
            int internal = 0, external = 0;
            for (var page : pdf.getPages()) {
                for (var annotation : page.getAnnotations()) {
                    if (annotation instanceof PDAnnotationLink link) {
                        if (link.getDestination() != null) { internal++; } else { external++; }
                    }
                }
            }
            assertTrue(internal >= 4); assertTrue(external > 0);
        }
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    @Test void semanticStyleChangeBetweenPassesFailsAndPreservesOldTarget() throws Exception {
        var opens = new AtomicInteger(); var closes = new AtomicInteger();
        Path target = directory.resolve("existing.pdf"); Files.writeString(target, "previous");
        var input = StreamingTable.builder("stream").columns(100).semanticCells(true).build();
        var failure = assertThrows(IOException.class, () -> DocumentEngine.builder(fonts()).build().exportReplay(input, () -> {
            int pass = opens.incrementAndGet();
            return source(List.of(new TableRow(List.of(new TableCell("same text", CellFormat.of(pass == 1 ? 10 : 11))))), closes);
        }).backend(new PdfSpoolBackend(directory)).to(target));
        assertTrue(failure.getMessage().contains("changed between passes"));
        assertEquals("previous", Files.readString(target)); assertEquals(2, closes.get());
        try (var files = Files.list(directory)) { assertEquals(List.of(target), files.toList()); }
    }

    @Test void probeChangeFailsBeforeBackendOpensAndClosesBothSources() throws Exception {
        var opens = new AtomicInteger(); var closes = new AtomicInteger(); var backend = new AtomicInteger();
        var input = StreamingTable.builder("stream").columns(100).semanticCells(true).build();
        var plan = StreamingReplayPlan.builder().columns(List.of(new ColumnWidth.Auto(50, 100)), 2, 100_000).build();
        assertThrows(IOException.class, () -> DocumentEngine.builder(fonts()).build().exportReplay(input, () -> {
            int pass = opens.incrementAndGet(); return source(List.of(new TableRow(List.of(new TableCell("Pass " + pass, FORMAT)))), closes);
        }).plan(plan).backend((metadata, output) -> { backend.incrementAndGet(); throw new AssertionError(); }).to(new ByteArrayOutputStream()));
        assertEquals(2, closes.get()); assertEquals(0, backend.get());
    }

    @Test void repeatedAndConcurrentRequestsHaveIndependentSourcesAndTemporaryFiles() throws Exception {
        var engine = DocumentEngine.builder(fonts()).build(); var backend = new PdfSpoolBackend(directory);
        var input = StreamingTable.builder("stream").columns(100).semanticCells(true).build();
        var closes = new AtomicInteger();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var jobs = java.util.stream.IntStream.range(0, 4).<java.util.concurrent.Callable<Integer>>mapToObj(run -> () -> {
                var output = new ByteArrayOutputStream();
                engine.exportReplay(input, () -> source(List.of(new TableRow(List.of(new TableCell("Run " + run, FORMAT)))), closes)).backend(backend).to(output);
                try (var pdf = Loader.loadPDF(output.toByteArray())) {
                    assertTrue(new PDFTextStripper().getText(pdf).contains("Run " + run)); return pdf.getNumberOfPages();
                }
            }).toList();
            for (var result : pool.invokeAll(jobs)) { assertEquals(1, result.get()); }
        }
        assertEquals(8, closes.get()); try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    @Test void onePassSemanticCellsSplitWithoutReopeningAndResolveDeclaredTotal() throws Exception {
        var closes = new AtomicInteger(); var opens = new AtomicInteger();
        var specification = StreamingTable.builder("one-pass").columns(100).semanticCells(true).allowRowSplit(true).expectedPages(2)
                .pageSettings(new PageSettings(new PageSize(140, 150), PageMargins.uniform(10))).padding(2)
                .header(new TableRow(List.of(TableCell.mixed(List.of(new InlineText("Page ", InlineStyle.of(10)),
                        new InlineField(InlineField.Kind.PAGE, "", 18, InlineStyle.of(10)), new InlineText(" / ", InlineStyle.of(10)),
                        new InlineField(InlineField.Kind.PAGES, "", 18, InlineStyle.of(10))), FORMAT)))).build();
        var paragraph = new Paragraph("original", java.util.stream.IntStream.rangeClosed(1, 12)
                .mapToObj(index -> "LINE-" + index).collect(java.util.stream.Collectors.joining("\n")), ParagraphFormat.of(10));
        var output = new ByteArrayOutputStream();
        var result = DocumentEngine.builder(fonts()).build().exportSemantic(specification, () -> {
            opens.incrementAndGet(); return source(List.of(new TableRow(List.of(TableCell.builder().blocks(List.of(paragraph)).build()))), closes);
        }).backend(new PdfSpoolBackend(directory)).to(output);
        assertEquals(1, opens.get()); assertEquals(1, closes.get()); assertEquals(2, result.pages());
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Page 1 / 2")); assertTrue(text.contains("Page 2 / 2"));
            assertTrue(text.contains("LINE-12"));
        }
    }

    @Test void onePassNestedSourceFailureAfterOutputPreservesTargetAndClosesOnce() throws Exception {
        var closes = new AtomicInteger(); Path target = directory.resolve("existing.pdf"); Files.writeString(target, "previous");
        var specification = StreamingTable.builder("one-pass").columns(100).semanticCells(true).allowRowSplit(true)
                .pageSettings(new PageSettings(new PageSize(140, 120), PageMargins.uniform(10))).build();
        var sourceFailure = new IOException("source failed after nested pages");
        var closeFailure = new IOException("source cleanup");
        var failure = assertThrows(IOException.class, () -> DocumentEngine.builder(fonts()).build().exportSemantic(specification, () -> new TableRowSource() {
            private boolean delivered;
            @Override public Optional<TableRow> next() throws IOException {
                if (delivered) { throw sourceFailure; } delivered = true;
                return Optional.of(new TableRow(List.of(TableCell.builder().paragraph(new Paragraph("long", "line\n".repeat(30), ParagraphFormat.of(10))).build())));
            }
            @Override public void close() throws IOException { closes.incrementAndGet(); throw closeFailure; }
        }).backend(new PdfSpoolBackend(directory)).to(target));
        assertSame(sourceFailure, failure); assertArrayEquals(new Throwable[] {closeFailure}, failure.getSuppressed());
        assertEquals(1, closes.get()); assertEquals("previous", Files.readString(target));
        try (var files = Files.list(directory)) { assertEquals(List.of(target), files.toList()); }
    }

    @Test void unknownTotalInOnePassAndOversizedProbeFailExplicitly() throws Exception {
        var closes = new AtomicInteger();
        var row = new TableRow(List.of(TableCell.mixed(List.of(new InlineField(InlineField.Kind.PAGES, "", 20, InlineStyle.of(10))), FORMAT)));
        var specification = StreamingTable.builder("one-pass").columns(100).semanticCells(true).build();
        assertThrows(com.mirakyux.export4j.layout.core.DocumentLayoutException.class, () -> DocumentEngine.builder(fonts()).build()
                .exportSemantic(specification, () -> source(List.of(row), closes)).backend(new PdfSpoolBackend(directory)).to(new ByteArrayOutputStream()));
        assertEquals(1, closes.get());
        var backendOpens = new AtomicInteger();
        var probe = StreamingReplayPlan.builder().columns(List.of(new ColumnWidth.Auto(50, 100)), 1, 64).build();
        assertThrows(IOException.class, () -> DocumentEngine.builder(fonts()).build().exportReplay(specification,
                () -> source(List.of(new TableRow(List.of(new TableCell("bounded probe text ".repeat(20), FORMAT)))), closes)).plan(probe)
                .backend((metadata, output) -> { backendOpens.incrementAndGet(); throw new AssertionError(); }).to(new ByteArrayOutputStream()));
        assertEquals(0, backendOpens.get()); assertEquals(2, closes.get());
    }

    @Test void planningSourceCloseFailurePreventsReopeningAndOutput() throws Exception {
        var opens = new AtomicInteger(); var output = new ByteArrayOutputStream(); var failure = new IOException("source close");
        assertSame(failure, assertThrows(IOException.class, () -> DocumentEngine.builder(fonts()).build()
                .exportReplay(StreamingTable.builder("replay").columns(100).build(), () -> {
                    opens.incrementAndGet(); return new TableRowSource() {
                        @Override public Optional<TableRow> next() { return Optional.empty(); }
                        @Override public void close() throws IOException { throw failure; }
                    };
                }).backend(new PdfSpoolBackend(directory)).to(output)));
        assertEquals(1, opens.get()); assertEquals(0, output.size());
    }
}
