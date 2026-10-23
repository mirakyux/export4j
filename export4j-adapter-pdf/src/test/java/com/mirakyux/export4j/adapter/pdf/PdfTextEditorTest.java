// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfTextEditorTest {
    @TempDir Path directory;
    private static final ProcessingPolicy POLICY = ProcessingPolicy.builder()
            .feature("TEXT_FONT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
            .feature("TEXT_SPACING", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
            .feature("TEXT_CAPACITY", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.SKIP_MATCH)
            .feature("TEXT_SHAPING", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.SKIP_MATCH)
            .feature("TEXT_STRUCTURE", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.SKIP_BLOCK).build();
    @Test void replacesRealBytesAndPreservesFollowingTextPosition() throws Exception {
        var adapter = new PdfAdapter(); byte[] bytes = source(false, false); var input = adapter.input(bytes, "");
        assertThrows(ProcessingException.class, () -> adapter.editText(input).replace(0, "SECRET", "PUBLIC", font()).prepare());
        var request = adapter.editText(input).replace(0, "SECRET", "PUBLIC", font()).policy(POLICY); var prepared = request.prepare();
        request.replace(0, "KEEP", "NO", font()); var output = write(prepared); var before = positions(bytes); var after = positions(output);
        assertTrue(text(output).contains("PUBLIC"), text(output)); assertTrue(text(output).contains("KEEP")); assertFalse(text(output).contains("SECRET"));
        assertEquals(before.subList(7, before.size()), after.subList(7, after.size()));
        try (var pdf = Loader.loadPDF(output)) {
            assertFalse(new String(pdf.getPage(0).getContents().readAllBytes(), StandardCharsets.ISO_8859_1).contains("SECRET"));
            for (var name : pdf.getPage(0).getResources().getFontNames()) { var font = pdf.getPage(0).getResources().getFont(name); assertTrue(font.isEmbedded()); assertTrue(font.getCOSObject().containsKey(org.apache.pdfbox.cos.COSName.TO_UNICODE)); }
        }
        assertEquals(1, prepared.preview().losses().size());
    }
    @Test void justifiedTjArrayIsReplacedWithExplicitInternalSpacingLoss() throws Exception {
        var adapter = new PdfAdapter(); var prepared = adapter.editText(adapter.input(source(false, true), ""))
                .replace(0, "SECRET", "PUBLIC", font()).policy(POLICY).prepare();
        assertTrue(text(write(prepared)).contains("PUBLIC")); assertEquals(2, prepared.preview().losses().size());
    }
    @Test void multipleMatchesPreserveUnmatchedGlyphCoordinatesInsideTheSameOperator() throws Exception {
        var adapter = new PdfAdapter(); byte[] source = source(false, false);
        var prepared = adapter.editText(adapter.input(source, "")).replace(0, "E", "I", font()).policy(POLICY).prepare();
        var output = write(prepared); var before = positions(source); var after = positions(output);
        assertEquals(before.size(), after.size());
        for (int index = 0; index < before.size(); index++) {
            if (!before.get(index).startsWith("E:")) { assertEquals(before.get(index), after.get(index)); }
        }
        assertEquals("SICRIT KIIP", after.stream().map(value -> value.substring(0, value.indexOf(':'))).collect(java.util.stream.Collectors.joining()));
    }
    @Test void overflowAndComplexTextSkipOnlyTheMatchedShowOperator() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(source(false, false), "");
        var tooLong = adapter.editText(input).replace(0, "SECRET", "A VERY LONG REPLACEMENT THAT DOES NOT FIT", font()).policy(POLICY).prepare();
        assertTrue(text(write(tooLong)).contains("SECRET")); assertEquals("TEXT_CAPACITY", tooLong.preview().issues().getFirst().feature());
        var complex = adapter.editText(input).replace(0, "SECRET", "e\u0301", font()).policy(POLICY).prepare();
        assertTrue(text(write(complex)).contains("SECRET")); assertEquals("TEXT_SHAPING", complex.preview().issues().getFirst().feature());
    }
    @Test void markedContentRequiresWholePageSkipEvenIfLogicalActualTextDiffers() throws Exception {
        var adapter = new PdfAdapter(); var prepared = adapter.editText(adapter.input(source(true, false), ""))
                .replace(0, "SECRET", "PUBLIC", font()).policy(POLICY).prepare();
        assertEquals("TEXT_STRUCTURE", prepared.preview().issues().getFirst().feature()); assertTrue(text(write(prepared)).contains("SECRET"));
    }
    @Test void cancellationBudgetAndInvalidUnicodeDoNotPublish() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(source(false, false), ""); var cancelled = new AtomicBoolean();
        var prepared = adapter.editText(input).replace(0, "SECRET", "PUBLIC", font()).policy(POLICY).execution(ExecutionOptions.builder().cancelled(cancelled::get).build()).prepare();
        var target = directory.resolve("old.pdf"); Files.writeString(target, "OLD"); cancelled.set(true);
        assertThrows(ExecutionException.class, () -> prepared.to(target)); assertEquals("OLD", Files.readString(target));
        assertThrows(IllegalArgumentException.class, () -> adapter.editText(input).replace(0, "", "X", font()));
        assertThrows(IllegalArgumentException.class, () -> adapter.editText(input).replace(0, "X", "\uD800", font()));
        var bounded = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxTextCharacters(2).build()).build();
        assertEquals(PdfOperationException.Code.TEXT_LIMIT, assertThrows(PdfOperationException.class, () -> bounded.editText(input).replace(0, "S", "P", font()).policy(POLICY).prepare()).code());
    }
    static PdfFont font() throws Exception { return PdfFont.of(Files.readAllBytes(Path.of("../assets/fonts/LiberationSans-Regular.ttf"))); }
    static byte[] source(boolean marked, boolean array) throws Exception {
        try (var pdf = new PDDocument()) {
            var page = new PDPage(new PDRectangle(240, 120)); pdf.addPage(page);
            var font = PDType0Font.load(pdf, new ByteArrayInputStream(Files.readAllBytes(Path.of("../assets/fonts/LiberationSans-Regular.ttf"))));
            try (var content = new PDPageContentStream(pdf, page)) {
                if (marked) { content.beginMarkedContent(org.apache.pdfbox.cos.COSName.getPDFName("Span")); }
                content.beginText(); content.setFont(font, 14); content.newLineAtOffset(20, 80);
                if (array) { content.showTextWithPositioning(new Object[] {"SEC", -50f, "RET"}); } else { content.showText("SECRET"); }
                content.showText(" KEEP"); content.endText();
                if (marked) { content.endMarkedContent(); }
            }
            var output = new ByteArrayOutputStream(); pdf.save(output); return output.toByteArray();
        }
    }
    private static String text(byte[] bytes) throws Exception { try (var pdf = Loader.loadPDF(bytes)) { return new PDFTextStripper().getText(pdf); } }
    private static java.util.List<String> positions(byte[] bytes) throws Exception {
        var values = new ArrayList<String>();
        var stripper = new PDFTextStripper() { @Override protected void processTextPosition(TextPosition position) { values.add(position.getUnicode() + ":" + Math.round(position.getXDirAdj() * 100) + ":" + Math.round(position.getYDirAdj() * 100)); super.processTextPosition(position); } };
        try (var pdf = Loader.loadPDF(bytes)) { stripper.getText(pdf); } return values;
    }
    private static byte[] write(PreparedPdf prepared) throws Exception { var output = new ByteArrayOutputStream(); var result = prepared.to(output); assertEquals(prepared.preview().issues(), result.report().issues()); return output.toByteArray(); }
}
