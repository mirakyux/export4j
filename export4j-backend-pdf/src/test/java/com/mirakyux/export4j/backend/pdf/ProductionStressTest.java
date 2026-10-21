// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.ParagraphFormat;

class ProductionStressTest {
    private static DocumentEngine engine;
    private static final PdfBackend BACKEND = new PdfBackend();
    @BeforeAll static void fonts() throws IOException {
        try (var input = ProductionStressTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)))).build();
        }
    }
    @Test @Timeout(120) void sharedEngineAndBackendKeepConcurrentDocumentsIsolated() throws Exception {
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            int id = index;
            calls.add(() -> {
                String token = "DOC-" + id + "-END";
                var doc = Document.builder(new DocumentMetadata(token, ""), PageSettings.A4).paragraph("p", token, ParagraphFormat.of(12)).build();
                var output = new TrackingOutput(); engine.write(doc, BACKEND, output); assertFalse(output.closed);
                try (var pdf = Loader.loadPDF(output.toByteArray())) {
                    assertEquals(token, pdf.getDocumentInformation().getTitle()); assertEquals(token, new PDFTextStripper().getText(pdf).strip());
                    return pdf.getNumberOfPages();
                }
            });
        }
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (var result : executor.invokeAll(calls)) { assertEquals(1, result.get()); }
        }
    }
    @Test @Timeout(120) void largeExportKeepsEveryParagraphOnceAndInOrder() throws IOException {
        var builder = Document.builder(DocumentMetadata.EMPTY, PageSettings.A4);
        for (int index = 0; index < 2500; index++) { builder.paragraph("p-" + index, "BLOCK-" + index + "-END", ParagraphFormat.of(12)); }
        var output = new TrackingOutput(); var layout = engine.write(builder.build(), BACKEND, output);
        assertFalse(output.closed); assertTrue(layout.display().pages().size() >= 80);
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            String text = new PDFTextStripper().getText(pdf).replace("\r", "").strip();
            assertEquals(2500, text.lines().count());
            var lines = text.lines().toList();
            for (int index = 0; index < lines.size(); index++) { assertEquals("BLOCK-" + index + "-END", lines.get(index)); }
        }
    }
    private static final class TrackingOutput extends ByteArrayOutputStream { private boolean closed; @Override public void close() { closed = true; } }
}
