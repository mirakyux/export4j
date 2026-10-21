// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.layout.core.DocumentPainter;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;

class FlowPdfTest {
    @Test
    void semanticParagraphFlowsAcrossFivePagesWithUnicodeOrderAndNoLoss() throws IOException {
        List<FontFace> fonts = List.of(load("LiberationSans-Regular.ttf"), load("LXGWWenKaiTC-Regular.ttf"));
        List<String> expected = IntStream.range(0, 21)
                .mapToObj(index -> String.format(Locale.ROOT, "行 %02d / Line %02d", index, index)).toList();
        Document source = new Document(new DocumentMetadata("Flow integration", "Export4j"),
                new PageSettings(new PageSize(200, 130), PageMargins.uniform(20)),
                List.of(new Paragraph("body", String.join("\n", expected), ParagraphFormat.of(12))));
        var layout = new DocumentLayouter().layout(source, fonts);
        TrackingOutput output = new TrackingOutput();
        new PdfBackend().write(DocumentPainter.paint(layout), output);
        assertFalse(output.closed);
        try (PDDocument pdf = Loader.loadPDF(output.toByteArray())) {
            assertEquals(5, pdf.getNumberOfPages());
            assertEquals("Flow integration", pdf.getDocumentInformation().getTitle());
            assertEquals(expected, new PDFTextStripper().getText(pdf).lines().filter(line -> !line.isBlank()).toList());
            for (var page : pdf.getPages()) {
                assertEquals(200, page.getMediaBox().getWidth());
                assertEquals(130, page.getMediaBox().getHeight());
                for (var name : page.getResources().getFontNames()) {
                    assertTrue(page.getResources().getFont(name).isEmbedded());
                }
            }
        }
    }

    @Test
    void emptySemanticDocumentProducesAValidSingleBlankPdfPage() throws IOException {
        Document source = new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of());
        var layout = new DocumentLayouter().layout(source, List.of(load("LiberationSans-Regular.ttf")));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new PdfBackend().write(DocumentPainter.paint(layout), output);
        try (PDDocument pdf = Loader.loadPDF(output.toByteArray())) {
            assertEquals(1, pdf.getNumberOfPages());
            assertTrue(new PDFTextStripper().getText(pdf).isBlank());
        }
    }

    private static FontFace load(String name) throws IOException {
        try (InputStream input = FlowPdfTest.class.getResourceAsStream("/fonts/" + name)) {
            if (input == null) {
                throw new IOException("Missing test font");
            }
            return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
    }

    private static final class TrackingOutput extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }
}
