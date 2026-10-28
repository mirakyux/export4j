// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfAutomaticRecoveryTest {
    @Test void columnsAreReadByColumnAndPhysicalLinesBecomeParagraphsWithoutDuplicates() throws Exception {
        byte[] source;
        try (var fixture = new Fixture()) {
            fixture.page(); fixture.text("TITLE", 40, 70, 22);
            fixture.text("Right first line", 340, 140, 12); fixture.text("Left first line", 40, 140, 12);
            fixture.text("Right second line", 340, 157, 12); fixture.text("Left second line", 40, 157, 12); source = fixture.save();
        }
        var prepared = automatic(source); var blocks = prepared.document().sections().getFirst().blocks();
        assertInstanceOf(Heading.class, blocks.getFirst());
        assertEquals(List.of("TITLE", "Left first line Left second line", "Right first line Right second line"), text(blocks));
        assertTrue(prepared.preview().issues().stream().anyMatch(issue -> issue.effects().contains("COLUMN_READING_ORDER_INFERRED_LEFT_TO_RIGHT_TOP_TO_BOTTOM")));
        assertEquals(5, prepared.sources().size());
        var adapter = new PdfAdapter(); var input = adapter.input(source, "");
        var legacy = adapter.importDocument(input).policy(policy(ProcessingPolicy.Action.WARN)).prepare();
        assertEquals(3, legacy.document().sections().getFirst().blocks().size());
        assertTrue(((Paragraph) legacy.document().sections().getFirst().blocks().get(1)).text().contains("Right first line"));
        assertArrayEquals(source, input.bytes());
    }
    @Test void markersBecomeEditableListsAndWrappedBodyDoesNotRepeatItsMarker() throws Exception {
        byte[] source;
        try (var fixture = new Fixture()) {
            fixture.page(); fixture.text("- First item", 40, 100, 12); fixture.text("continued body", 55, 117, 12);
            fixture.text("- Second item", 40, 150, 12); fixture.text("3. Third item", 40, 200, 12); fixture.text("4. Fourth item", 40, 230, 12); source = fixture.save();
        }
        var blocks = automatic(source).document().sections().getFirst().blocks();
        assertEquals(4, blocks.size()); assertEquals("First item continued body", assertInstanceOf(BulletItem.class, blocks.getFirst()).text());
        assertInstanceOf(BulletItem.class, blocks.get(1)); assertEquals(3, assertInstanceOf(NumberedItem.class, blocks.get(2)).restartAt());
        assertEquals(4, assertInstanceOf(NumberedItem.class, blocks.get(3)).restartAt());
    }
    @Test void connectedRulesInferBothSpanDirectionsAndKeepAdjacentBodyOutside() throws Exception {
        byte[] source;
        try (var fixture = new Fixture()) {
            fixture.page(); fixture.text("BEFORE", 50, 60, 12);
            double[] xs = {50, 180, 310, 440}; double[] ys = {100, 140, 180, 220};
            fixture.grid(xs, ys, List.of("v:0:2", "h:2:0"));
            fixture.text("HEAD", 55, 120, 12); fixture.text("MERGED", 185, 120, 12); fixture.text("ROWSPAN", 55, 160, 12);
            fixture.text("B2", 185, 160, 12); fixture.text("C2", 315, 160, 12); fixture.text("B3", 185, 200, 12); fixture.text("C3", 315, 200, 12);
            fixture.text("AFTER", 50, 270, 12); source = fixture.save();
        }
        var blocks = automatic(source).document().sections().getFirst().blocks(); assertEquals(3, blocks.size());
        var table = assertInstanceOf(Table.class, blocks.get(1)); assertEquals(3, table.columns().size()); assertEquals(3, table.rows().size()); table.grid();
        assertEquals(2, table.rows().getFirst().cells().get(1).columnSpan()); assertEquals(2, table.rows().get(1).cells().getFirst().rowSpan());
        assertEquals(List.of("BEFORE", "HEAD", "MERGED", "ROWSPAN", "B2", "C2", "B3", "C3", "AFTER"), text(blocks));
    }
    @Test void containedIndependentGridBecomesAnActualNestedTable() throws Exception {
        byte[] source;
        try (var fixture = new Fixture()) {
            fixture.page(); fixture.grid(new double[]{50, 300, 550}, new double[]{100, 280}, List.of()); fixture.text("PARENT", 60, 130, 12);
            fixture.grid(new double[]{70, 170, 270}, new double[]{160, 200, 240}, List.of()); fixture.text("N11", 75, 180, 12); fixture.text("N12", 175, 180, 12); fixture.text("N21", 75, 220, 12); fixture.text("N22", 175, 220, 12); fixture.text("RIGHT", 320, 130, 12); source = fixture.save();
        }
        var table = assertInstanceOf(Table.class, automatic(source).document().sections().getFirst().blocks().getFirst());
        assertEquals(2, table.rows().getFirst().cells().getFirst().blocks().size());
        assertInstanceOf(Table.class, table.rows().getFirst().cells().getFirst().blocks().get(1));
        assertEquals(List.of("PARENT", "N11", "N12", "N21", "N22", "RIGHT"), text(List.of(table)));
    }
    @Test void compactAlignedMatrixIsRecoveredButParallelProseRemainsTextAndGridOverridesDetection() throws Exception {
        byte[] source;
        try (var fixture = new Fixture()) {
            fixture.page(); for (int row = 0; row < 4; row++) { fixture.text("Name"+row, 50, 100+row*20, 12); fixture.text(""+(row+10), 220, 100+row*20, 12); } source = fixture.save();
        }
        var table = assertInstanceOf(Table.class, automatic(source).document().sections().getFirst().blocks().getFirst()); assertEquals(4, table.rows().size());
        byte[] prose;
        try (var fixture = new Fixture()) {
            fixture.page(); for (int row = 0; row < 4; row++) { fixture.text("Left prose with many words "+row+".", 35, 100+row*22, 10); fixture.text("Right prose with many words "+row+".", 335, 100+row*22, 10); } prose = fixture.save();
        }
        var recovered = automatic(prose); assertFalse(recovered.document().sections().getFirst().blocks().stream().anyMatch(Table.class::isInstance)); assertEquals(8, text(recovered.document().sections().getFirst().blocks()).size());
        var adapter = new PdfAdapter(); var explicit = adapter.importDocument(adapter.input(source, "")).analysis(PdfSemanticImporter.Analysis.AUTO)
                .table(0, List.of(40d, 350d), List.of(80d, 180d), 0).policy(policy(ProcessingPolicy.Action.WARN)).prepare();
        assertEquals(1, assertInstanceOf(Table.class, explicit.document().sections().getFirst().blocks().getFirst()).rows().size());
    }
    @Test void repeatedBandsLeaveTheBodyAndCrossPageTablesHaveOneHeaderAndMultiPageProvenance() throws Exception {
        byte[] source;
        try (var fixture = new Fixture()) {
            for (int page = 0; page < 2; page++) {
                fixture.page(); fixture.text("HEADER", 40, 30, 10); fixture.text("Page "+(page+1), 280, 780, 10);
                double top = page == 0 ? 620 : 100; fixture.grid(new double[]{50, 200, 350}, new double[]{top, top+40, top+80, top+120}, List.of());
                fixture.text("NAME", 55, top+20, 12); fixture.text("VALUE", 205, top+20, 12);
                for (int row = 1; row <= 2; row++) { fixture.text("P"+page+"R"+row, 55, top+20+row*40, 12); fixture.text(""+(page*10+row), 205, top+20+row*40, 12); }
            }
            source = fixture.save();
        }
        var prepared = automatic(source); var first = prepared.document().sections().getFirst();
        assertEquals("HEADER", ((Paragraph) first.pageRegions().header().blocks().getFirst()).text());
        assertEquals(1, first.blocks().size()); var table = assertInstanceOf(Table.class, first.blocks().getFirst()); assertEquals(5, table.rows().size()); assertEquals(1, table.headerRows());
        assertFalse(table.rows().getFirst().cells().getFirst().hasBlocks());
        assertTrue(prepared.sources().stream().anyMatch(value -> value.blockId().equals(table.id()) && value.page() == 1));
        assertTrue(prepared.sources().stream().anyMatch(value -> value.blockId().equals(table.id()) && value.cellPath().equals(List.of(0, 0))));
    }
    @Test void policyBudgetAndCancellationCannotReturnAFalseSuccess() throws Exception {
        byte[] source;
        try (var fixture = new Fixture()) { fixture.page(); fixture.text("First line", 40, 100, 12); fixture.text("Second line", 40, 117, 12); source = fixture.save(); }
        var adapter = new PdfAdapter(); var input = adapter.input(source, "");
        assertThrows(ProcessingException.class, () -> adapter.importDocument(input).analysis(PdfSemanticImporter.Analysis.AUTO).prepare());
        var warned = adapter.importDocument(input).analysis(PdfSemanticImporter.Analysis.AUTO).policy(policy(ProcessingPolicy.Action.WARN)).prepare();
        var ignored = adapter.importDocument(input).analysis(PdfSemanticImporter.Analysis.AUTO).policy(policy(ProcessingPolicy.Action.IGNORE)).prepare();
        assertEquals(warned.document(), ignored.document()); assertEquals(warned.sources(), ignored.sources()); assertTrue(ignored.preview().warnings().isEmpty());
        assertThrows(ExecutionException.class, () -> adapter.importDocument(input).analysis(PdfSemanticImporter.Analysis.AUTO).policy(policy(ProcessingPolicy.Action.IGNORE)).execution(ExecutionOptions.builder().cancelled(() -> true).build()).prepare());
    }
    private static PreparedPdfImport automatic(byte[] source) throws Exception { var adapter = new PdfAdapter(); return adapter.importDocument(adapter.input(source, "")).analysis(PdfSemanticImporter.Analysis.AUTO).policy(policy(ProcessingPolicy.Action.WARN)).prepare(); }
    private static ProcessingPolicy policy(ProcessingPolicy.Action action) { return ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DEGRADE).feature("PDF_VECTOR_GRAPHICS", action, ProcessingPolicy.Treatment.DISCARD).build(); }
    private static List<String> text(List<Block> blocks) {
        var result = new ArrayList<String>();
        for (var block : blocks) {
            if (block instanceof Paragraph value) { result.add(value.text()); } else if (block instanceof Heading value) { result.add(value.text()); }
            else if (block instanceof Table value) { for (var row : value.rows()) { for (var cell : row.cells()) { if (cell.hasBlocks()) { result.addAll(text(cell.blocks())); } else { result.add(cell.text()); } } } }
        }
        return result;
    }
    private static final class Fixture implements AutoCloseable {
        final PDDocument document = new PDDocument(); final PDType0Font font; PDPageContentStream output;
        Fixture() throws Exception { font = PDType0Font.load(document, new ByteArrayInputStream(Files.readAllBytes(Path.of("../assets/fonts/LiberationSans-Regular.ttf"))), false); }
        void page() throws Exception { if (output != null) { output.close(); } var page = new PDPage(new PDRectangle(600, 800)); document.addPage(page); output = new PDPageContentStream(document, page); }
        void text(String text, double x, double top, double size) throws Exception { output.beginText(); output.setFont(font, (float) size); output.newLineAtOffset((float) x, (float) (800-top)); output.showText(text); output.endText(); }
        void line(double x1, double y1, double x2, double y2) throws Exception { output.moveTo((float) x1, (float) (800-y1)); output.lineTo((float) x2, (float) (800-y2)); output.stroke(); }
        void grid(double[] x, double[] y, List<String> omitted) throws Exception {
            for (int row = 0; row < y.length; row++) { for (int col = 0; col < x.length-1; col++) { if (!omitted.contains("h:"+row+":"+col)) { line(x[col], y[row], x[col+1], y[row]); } } }
            for (int col = 0; col < x.length; col++) { for (int row = 0; row < y.length-1; row++) { if (!omitted.contains("v:"+row+":"+col)) { line(x[col], y[row], x[col], y[row+1]); } } }
        }
        byte[] save() throws Exception { output.close(); output = null; var bytes = new ByteArrayOutputStream(); document.save(bytes); return bytes.toByteArray(); }
        @Override public void close() throws java.io.IOException { if (output != null) { output.close(); } document.close(); }
    }
}
