// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfSemanticImporterTest {
    static final ProcessingPolicy POLICY = ProcessingPolicy.builder()
            .defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
            .feature("PDF_LAYOUT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
            .feature("PDF_FONT_MAPPING", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
            .feature("PDF_STRUCTURE", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.SKIP_BLOCK)
            .feature("PDF_PAGE_VIEW", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.SKIP_BLOCK).build();
    @Test void recoversHeadingParagraphGridAndPlacedImageAsEditableSemanticBlocks() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(source(), "");
        assertThrows(ProcessingException.class, () -> adapter.importDocument(input).prepare());
        var prepared = grid(adapter.importDocument(input)).policy(POLICY).prepare(); var result = prepared.execute();
        var blocks = result.document().sections().getFirst().blocks(); assertEquals(4, blocks.size());
        var heading = assertInstanceOf(Heading.class, blocks.get(0)); assertEquals("RECOVERED TITLE", heading.text()); assertEquals(2, heading.level());
        assertEquals("BODY TEXT", assertInstanceOf(Paragraph.class, blocks.get(1)).text());
        var table = assertInstanceOf(Table.class, blocks.get(2)); assertEquals(1, table.headerRows()); assertEquals(2, table.rows().size());
        assertEquals(List.of("NAME", "VALUE"), table.rows().getFirst().cells().stream().map(cell -> cell.text()).toList());
        assertEquals(List.of("ALPHA", "42"), table.rows().get(1).cells().stream().map(cell -> cell.text()).toList());
        var image = assertInstanceOf(Paragraph.class, blocks.get(3)).images().getFirst().image(); assertEquals(40, image.width()); assertEquals(30, image.height());
        assertEquals(0xFF2266AA, javax.imageio.ImageIO.read(new ByteArrayInputStream(image.resource().bytes())).getRGB(0, 0));
        assertEquals(4, prepared.sources().size()); assertEquals(20, prepared.sources().getLast().x()); assertEquals(190, prepared.sources().getLast().y());
        assertEquals(prepared.preview().issues(), result.report().issues()); assertEquals(ProcessingReport.Phase.ACTUAL, result.report().phase());
    }
    @Test void ignoreStillReturnsInferenceLossAndUnmappedFontsWhileExplicitRolePreventsFontFallback() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(source(), ""); String name;
        try (var pdf = org.apache.pdfbox.Loader.loadPDF(input.bytes())) { name = pdf.getPage(0).getResources().getFont(pdf.getPage(0).getResources().getFontNames().iterator().next()).getName(); }
        var policy = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DISCARD)
                .feature("PDF_LAYOUT", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE).build();
        var result = grid(adapter.importDocument(input)).fontRole(name, "body").policy(policy).prepare();
        assertTrue(result.preview().warnings().isEmpty()); assertEquals(2, result.preview().losses().size());
        assertTrue(result.preview().issues().stream().noneMatch(issue -> issue.feature().equals("PDF_FONT_MAPPING")));
        assertEquals("body", ((Heading) result.document().sections().getFirst().blocks().getFirst()).inlines().getFirst().style().fontRole());
    }
    @Test void tableEdgesAreSnapshotsAndInvalidCutsAndOverlapAreRejected() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(source(), ""); var x = new java.util.ArrayList<>(List.of(20d, 140d, 260d));
        var request = adapter.importDocument(input).table(0, x, List.of(80d, 120d, 150d), 1).policy(POLICY); x.set(1, 21d);
        assertInstanceOf(Table.class, request.prepare().document().sections().getFirst().blocks().get(2));
        assertThrows(IllegalArgumentException.class, () -> request.table(0, List.of(25d, 150d), List.of(80d, 120d), 0));
        assertThrows(IllegalArgumentException.class, () -> adapter.importDocument(input).table(0, List.of(20d, 27d, 260d), List.of(80d, 120d, 150d), 1).policy(POLICY).prepare());
        assertThrows(IllegalArgumentException.class, () -> adapter.importDocument(input).table(0, List.of(27d, 260d), List.of(80d, 150d), 0).policy(POLICY).prepare());
        assertThrows(IllegalArgumentException.class, () -> adapter.importDocument(input).table(0, List.of(20d, 260d), List.of(80d, 99d), 0).policy(POLICY).prepare());
        assertThrows(IllegalArgumentException.class, () -> adapter.importDocument(input).table(0, List.of(20d, 60d), List.of(180d, 230d), 0).policy(POLICY).prepare());
    }
    @Test void budgetsApplyToActualGlyphAndGridWorkAndNoModelIsReturnedOnCancellation() throws Exception {
        var input = new PdfAdapter().input(source(), "");
        var bounded = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxTextCharacters(2).build()).build();
        assertEquals(PdfOperationException.Code.TEXT_LIMIT, assertThrows(PdfOperationException.class, () -> bounded.importDocument(input).policy(POLICY).prepare()).code());
        var cancelled = new AtomicBoolean(); var prepared = grid(new PdfAdapter().importDocument(input)).policy(POLICY).execution(ExecutionOptions.builder().cancelled(cancelled::get).build()).prepare();
        cancelled.set(true); assertThrows(ExecutionException.class, prepared::execute);
        assertThrows(UnsupportedOperationException.class, () -> prepared.sources().clear());
    }
    @Test void unsupportedPagesCannotBecomeAFalseSuccessfulEmptyDocument() throws Exception {
        var adapter = new PdfAdapter(); byte[] bytes;
        try (var pdf = org.apache.pdfbox.Loader.loadPDF(PdfTextEditorTest.source(false, false))) {
            pdf.getPage(0).setRotation(90); var output = new ByteArrayOutputStream(); pdf.save(output); bytes = output.toByteArray();
        }
        var input = adapter.input(bytes, "");
        assertThrows(PdfOperationException.class, () -> adapter.importDocument(input).policy(POLICY).prepare());
    }
    @Test void logicalAlternateTextIsReportedAndCannotReplaceVerifiedGlyphText() throws Exception {
        byte[] bytes;
        try (var pdf = org.apache.pdfbox.Loader.loadPDF(source())) {
            var page = pdf.getPage(0); var streams = new java.util.ArrayList<org.apache.pdfbox.pdmodel.common.PDStream>();
            var prefix = new org.apache.pdfbox.pdmodel.common.PDStream(pdf); var suffix = new org.apache.pdfbox.pdmodel.common.PDStream(pdf);
            try (var output = prefix.createOutputStream()) { output.write("/Span << /ActualText (UNVERIFIED-ALTERNATE) >> BDC\n".getBytes(StandardCharsets.US_ASCII)); }
            try (var output = suffix.createOutputStream()) { output.write("\nEMC".getBytes(StandardCharsets.US_ASCII)); }
            streams.add(prefix); page.getContentStreams().forEachRemaining(streams::add); streams.add(suffix); page.setContents(streams);
            var output = new ByteArrayOutputStream(); pdf.save(output); bytes = output.toByteArray();
        }
        var adapter = new PdfAdapter(); var result = grid(adapter.importDocument(adapter.input(bytes, ""))).policy(POLICY).prepare();
        assertEquals("RECOVERED TITLE", ((Heading) result.document().sections().getFirst().blocks().getFirst()).text());
        assertTrue(result.preview().issues().stream().anyMatch(issue -> issue.feature().equals("PDF_LOGICAL_TEXT")));
    }
    @Test void fractionalTextRenderingModesCannotBeTruncatedToASupportedMode() throws Exception {
        for (String mode : List.of("0.5", "3.5")) {
            byte[] bytes;
            try (var pdf = org.apache.pdfbox.Loader.loadPDF(source())) {
                var page = pdf.getPage(0); var streams = new java.util.ArrayList<org.apache.pdfbox.pdmodel.common.PDStream>();
                var prefix = new org.apache.pdfbox.pdmodel.common.PDStream(pdf);
                try (var output = prefix.createOutputStream()) { output.write((mode + " Tr\n").getBytes(StandardCharsets.US_ASCII)); }
                streams.add(prefix); page.getContentStreams().forEachRemaining(streams::add); page.setContents(streams);
                var output = new ByteArrayOutputStream(); pdf.save(output); bytes = output.toByteArray();
            }
            var adapter = new PdfAdapter(); var input = adapter.input(bytes, "");
            assertThrows(PdfOperationException.class, () -> adapter.importDocument(input).policy(POLICY).prepare());
        }
    }
    @Test void conversionSnapshotsTableConfigurationAndReconcilesImportLosses() throws Exception {
        var adapter = new PdfAdapter(); var request = grid(adapter.importDocument(adapter.input(source(), ""))); var conversion = request.conversion();
        request.table(0, List.of(0d, 10d), List.of(0d, 10d), 0);
        var prepared = conversion.policy(POLICY).prepare(new DocumentConversion.Target("TEXT", (document, notes, policy, output) -> {
            assertEquals(4, document.sections().getFirst().blocks().size()); output.write("MODEL".getBytes(StandardCharsets.UTF_8)); return new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of());
        }));
        var output = new ByteArrayOutputStream(); var result = prepared.to(output); assertEquals("MODEL", output.toString(StandardCharsets.UTF_8)); assertEquals(prepared.preview().issues(), result.report().issues());
        assertTrue(result.report().issues().stream().allMatch(issue -> issue.stage().equals("IMPORT")));
    }
    static PdfSemanticImporter grid(PdfSemanticImporter request) { return request.table(0, List.of(20d, 140d, 260d), List.of(80d, 120d, 150d), 1); }
    static byte[] source() throws Exception {
        try (var pdf = new PDDocument()) {
            var page = new PDPage(new PDRectangle(300, 260)); pdf.addPage(page);
            var font = PDType0Font.load(pdf, new ByteArrayInputStream(Files.readAllBytes(Path.of("../assets/fonts/LiberationSans-Regular.ttf"))));
            var image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB); var graphics = image.createGraphics(); graphics.setColor(new Color(0x2266AA)); graphics.fillRect(0, 0, 8, 6); graphics.dispose();
            try (var content = new PDPageContentStream(pdf, page)) {
                text(content, font, 18, 20, 230, "RECOVERED TITLE"); text(content, font, 12, 20, 200, "BODY TEXT");
                text(content, font, 12, 25, 160, "NAME"); text(content, font, 12, 145, 160, "VALUE");
                text(content, font, 12, 25, 130, "ALPHA"); text(content, font, 12, 145, 130, "42");
                content.addRect(20, 110, 240, 70); content.stroke(); content.drawImage(LosslessFactory.createFromImage(pdf, image), 20, 40, 40, 30);
            }
            var output = new ByteArrayOutputStream(); pdf.save(output); return output.toByteArray();
        }
    }
    private static void text(PDPageContentStream content, PDType0Font font, float size, float x, float y, String text) throws Exception {
        content.beginText(); content.setFont(font, size); content.newLineAtOffset(x, y); content.showText(text); content.endText();
    }
}
