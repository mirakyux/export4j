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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfTextEditorTest {
    @TempDir Path directory;
    private static final ProcessingPolicy POLICY = ProcessingPolicy.builder()
            .feature("TEXT_FONT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
            .feature("TEXT_SPACING", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
            .feature("TEXT_STYLE", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
            .feature("TEXT_CAPACITY", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.SKIP_MATCH)
            .feature("TEXT_SHAPING", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.SKIP_MATCH)
            .feature("MARKED_TEXT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.SKIP_MATCH)
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
    @Test void markedMatchesRequireExplicitSkipAndKeepMarkedTextUnchanged() throws Exception {
        var adapter = new PdfAdapter(); var prepared = adapter.editText(adapter.input(source(true, false), ""))
                .replace(0, "SECRET", "PUBLIC", font()).policy(POLICY).prepare();
        assertEquals("MARKED_TEXT", prepared.preview().issues().getFirst().feature()); assertTrue(text(write(prepared)).contains("SECRET"));
    }
    @Test void unmarkedReplacementPreservesNestedActualTextAndUnmatchedPositions() throws Exception {
        byte[] source = mixedMarked(false, false); var adapter = new PdfAdapter();
        var strategy = ProcessingPolicy.builder().feature("TEXT_FONT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
        var prepared = adapter.editText(adapter.input(source, "")).replace(0, "SECRET", "PUBLIC", font()).policy(strategy).prepare();
        var output = write(prepared);
        assertTrue(text(output).contains("PUBLIC")); assertTrue(text(output).contains("KEEP"));
        assertEquals(positions(source).subList(7, positions(source).size()), positions(output).subList(7, positions(output).size()));
        assertEquals(1, prepared.preview().losses().size());
        try (var pdf = Loader.loadPDF(output)) {
            var properties = pdf.getPage(0).getResources().getCOSObject().getCOSDictionary(org.apache.pdfbox.cos.COSName.PROPERTIES);
            assertEquals("LOGICAL-CONTROL", properties.getCOSDictionary(properties.keySet().iterator().next()).getString(org.apache.pdfbox.cos.COSName.ACTUAL_TEXT));
            var bytes = new String(pdf.getPage(0).getContents().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(bytes.contains("BMC") && bytes.contains("BDC") && bytes.contains("EMC"));
        }
    }
    @Test void malformedMarkedBalanceAndUnsupportedPropertiesCannotBeSilentlyEdited() throws Exception {
        var adapter = new PdfAdapter();
        assertThrows(java.io.IOException.class, () -> adapter.editText(adapter.input(mixedMarked(true, false), ""))
                .replace(0, "SECRET", "PUBLIC", font()).policy(POLICY).prepare());
        var rejected = assertThrows(ProcessingException.class, () -> adapter.editText(adapter.input(mixedMarked(false, true), ""))
                .replace(0, "SECRET", "PUBLIC", font()).prepare());
        assertEquals("TEXT_STRUCTURE", rejected.issue().feature());
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
    @Test void matchesAcrossConsecutiveTjAndTjArrayWithoutMovingUnmatchedGlyphs() throws Exception {
        var adapter = new PdfAdapter();
        for (boolean kerned : java.util.List.of(false, true)) {
            byte[] source = splitSource("", kerned); var before = positions(source);
            var prepared = adapter.editText(adapter.input(source, "")).replace(0, "SECRET", "HI", font()).policy(POLICY).prepare();
            byte[] output = write(prepared); var after = positions(output);
            assertFalse(text(output).contains("SECRET")); assertTrue(text(output).contains("HI"));
            assertEquals(before.subList(6, before.size()), after.subList(2, after.size()));
            assertEquals(1, prepared.to(new ByteArrayOutputStream()).changes());
            assertEquals(kerned ? 2 : 1, prepared.preview().issues().size());
        }
    }
    @Test void multipleCrossShowMatchesAreCountedAndFailedMatchesPreserveOriginalBytes() throws Exception {
        var adapter = new PdfAdapter(); byte[] source = splitSource("", false);
        var input = adapter.input(source, "");
        var twice = adapter.editText(input).replace(0, "RET K", "I", font()).replace(0, "SEC", "A", font()).policy(POLICY).prepare();
        assertEquals(2, twice.to(new ByteArrayOutputStream()).changes()); assertEquals("AIEEP", positions(write(twice)).stream().map(item -> item.substring(0, item.indexOf(':'))).collect(java.util.stream.Collectors.joining()));
        var unchanged = adapter.editText(input).replace(0, "SECRET", "TOO LONG TO FIT THE ORIGINAL RANGE", font()).policy(POLICY).prepare();
        assertEquals(0, unchanged.to(new ByteArrayOutputStream()).changes()); assertEquals(positions(source), positions(write(unchanged)));
        assertTrue(text(write(unchanged)).contains("SECRET")); assertEquals("TEXT_CAPACITY", unchanged.preview().issues().getFirst().feature());
        try (var before = Loader.loadPDF(source); var after = Loader.loadPDF(write(unchanged));
                var first = before.getPage(0).getContents(); var second = after.getPage(0).getContents()) {
            assertArrayEquals(first.readAllBytes(), second.readAllBytes());
        }
    }
    @Test void laterLineDoesNotRequireStyleAcceptanceForSameStateSplitShows() throws Exception {
        var adapter = new PdfAdapter();
        for (boolean kerned : java.util.List.of(false, true)) {
            byte[] source = splitSource("", kerned, 1, true);
            var policy = ProcessingPolicy.builder().feature("TEXT_FONT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
                    .feature("TEXT_SPACING", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
            var prepared = adapter.editText(adapter.input(source, "")).replace(0, "SECRET", "HI", font()).policy(policy).prepare();
            byte[] output = write(prepared);
            assertEquals(1, prepared.to(new ByteArrayOutputStream()).changes());
            assertEquals(positions(source).subList(6, positions(source).size()), positions(output).subList(2, positions(output).size()));
            assertTrue(text(output).contains("BODY"));
            assertFalse(prepared.preview().issues().stream().anyMatch(issue -> issue.feature().equals("TEXT_STYLE")));
            assertEquals(kerned ? 2 : 1, prepared.preview().issues().size());
        }
    }
    @Test void twoMatchesAcrossFourShowOperatorsKeepTheFinalOriginalTextInPlace() throws Exception {
        byte[] source = splitSource("", false, 2); var adapter = new PdfAdapter();
        var prepared = adapter.editText(adapter.input(source, "")).replace(0, "SECRET", "HI", font()).policy(POLICY).prepare();
        byte[] output = write(prepared);
        assertEquals(2, prepared.to(new ByteArrayOutputStream()).changes());
        assertEquals(positions(source).subList(12, 17), positions(output).subList(4, 9));
        assertFalse(text(output).contains("SECRET")); assertEquals(2, prepared.preview().issues().size());
    }
    @Test void distinctLinesAndTextObjectsCannotCreateCrossShowMatches() throws Exception {
        var adapter = new PdfAdapter();
        for (String barrier : java.util.List.of("0 -20 Td", "ET BT /Original 14 Tf 20 60 Td")) {
            byte[] source = splitSource(barrier, false);
            var result = adapter.editText(adapter.input(source, "")).replace(0, "SECRET", "HI", font()).policy(POLICY).prepare();
            assertEquals(0, result.to(new ByteArrayOutputStream()).changes()); assertEquals(positions(source), positions(write(result)));
            assertTrue(result.preview().issues().isEmpty());
        }
    }
    @Test void fontColorSpacingAndSameLinePositionChangesPreserveEveryUnmatchedGlyph() throws Exception {
        var adapter = new PdfAdapter();
        for (String barrier : java.util.List.of("/Original 16 Tf", "0.8 0.1 0.2 rg", "1 Tc", "ADVANCE 0 Td", "1 0 0 1 ABSOLUTE 80 Tm")) {
            byte[] source = splitSource(barrier, false); var prepared = adapter.editText(adapter.input(source, ""))
                    .replace(0, "SECRET", "HI", font()).policy(POLICY).prepare();
            var output = write(prepared);
            assertEquals(1, prepared.to(new ByteArrayOutputStream()).changes(), barrier);
            assertFalse(text(output).contains("SECRET"), barrier); assertTrue(text(output).contains("HI"), barrier);
            assertEquals(positions(source).subList(6, 11), positions(output).subList(2, 7), barrier);
            assertTrue(prepared.preview().losses().stream().anyMatch(issue -> issue.feature().equals("TEXT_STYLE")));
        }
    }
    @Test void styleLossIsStrictByDefaultAndIgnoreStillReportsActualLoss() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(splitSource("1 0 0 rg", false), "");
        var strictStyle = ProcessingPolicy.builder().feature("TEXT_FONT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
        assertEquals("TEXT_STYLE", assertThrows(ProcessingException.class, () -> adapter.editText(input).replace(0, "SECRET", "HI", font()).policy(strictStyle).prepare()).issue().feature());
        var ignored = ProcessingPolicy.builder().feature("TEXT_FONT", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE)
                .feature("TEXT_STYLE", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE).build();
        var output = adapter.editText(input).replace(0, "SECRET", "HI", font()).policy(ignored).prepare();
        assertEquals(2, output.preview().losses().size()); assertTrue(output.preview().warnings().isEmpty());
    }
    @Test void crossShowStrictWarnIgnoreBudgetsAndCancellationKeepTheirContracts() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(splitSource("", false), "");
        assertThrows(ProcessingException.class, () -> adapter.editText(input).replace(0, "SECRET", "HI", font()).prepare());
        var ignore = ProcessingPolicy.builder().feature("TEXT_FONT", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE).build();
        var silent = adapter.editText(input).replace(0, "SECRET", "HI", font()).policy(ignore).prepare();
        assertEquals(1, silent.to(new ByteArrayOutputStream()).changes()); assertEquals(1, silent.preview().losses().size()); assertTrue(silent.preview().warnings().isEmpty());
        // Eleven source glyphs are charged once as glyphs and once as UTF-16, plus two replacement units.
        var exact = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxTextCharacters(24).build()).build();
        assertEquals(1, exact.editText(input).replace(0, "SECRET", "HI", font()).policy(POLICY).prepare().to(new ByteArrayOutputStream()).changes());
        var bounded = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxTextCharacters(23).build()).build();
        assertEquals(PdfOperationException.Code.TEXT_LIMIT, assertThrows(PdfOperationException.class,
                () -> bounded.editText(input).replace(0, "SECRET", "HI", font()).policy(POLICY).prepare()).code());
        var cancelled = new AtomicBoolean(); var candidate = adapter.editText(input).replace(0, "SECRET", "HI", font()).policy(POLICY)
                .execution(ExecutionOptions.builder().cancelled(cancelled::get).build()).prepare();
        Path target = directory.resolve("split.pdf"); Files.writeString(target, "OLD"); cancelled.set(true);
        assertThrows(ExecutionException.class, () -> candidate.to(target)); assertEquals("OLD", Files.readString(target));
    }
    private static byte[] splitSource(String barrier, boolean kerned) throws Exception {
        return splitSource(barrier, kerned, 1);
    }
    private static byte[] splitSource(String barrier, boolean kerned, int copies) throws Exception {
        return splitSource(barrier, kerned, copies, false);
    }
    private static byte[] splitSource(String barrier, boolean kerned, int copies, boolean followingLine) throws Exception {
        try (var pdf = new PDDocument()) {
            var page = new PDPage(new PDRectangle(240, 120)); pdf.addPage(page);
            var original = PDType0Font.load(pdf, new ByteArrayInputStream(Files.readAllBytes(Path.of("../assets/fonts/LiberationSans-Regular.ttf"))), false);
            page.setResources(new org.apache.pdfbox.pdmodel.PDResources()); page.getResources().put(org.apache.pdfbox.cos.COSName.getPDFName("Original"), original);
            String left = java.util.HexFormat.of().formatHex(original.encode("SEC"));
            String right = java.util.HexFormat.of().formatHex(original.encode("RET"));
            String keep = java.util.HexFormat.of().formatHex(original.encode(" KEEP"));
            var content = new org.apache.pdfbox.pdmodel.common.PDStream(pdf); page.setContents(content);
            barrier = barrier.replace("ADVANCE", Double.toString(original.getStringWidth("SEC") * 14 / 1000))
                    .replace("ABSOLUTE", Double.toString(20 + original.getStringWidth("SEC") * 14 / 1000));
            String sequence = "<" + left + "> Tj " + barrier + " [" + (kerned ? "-50 " : "") + "<" + right + ">] TJ ";
            String nextLine = followingLine ? "0 -20 Td <" + java.util.HexFormat.of().formatHex(original.encode("BODY")) + "> Tj " : "";
            String commands = "BT /Original 14 Tf 20 80 Td " + sequence.repeat(copies) + "<" + keep + "> Tj " + nextLine + "ET";
            try (var output = content.createOutputStream()) { output.write(commands.getBytes(StandardCharsets.US_ASCII)); }
            var output = new ByteArrayOutputStream(); pdf.save(output); return output.toByteArray();
        }
    }
    static PdfFont font() throws Exception { return PdfFont.of(Files.readAllBytes(Path.of("../assets/fonts/LiberationSans-Regular.ttf"))); }
    private static byte[] mixedMarked(boolean malformed, boolean unsupported) throws Exception {
        try (var pdf = new PDDocument()) {
            var page = new PDPage(new PDRectangle(240, 160)); pdf.addPage(page);
            var font = PDType0Font.load(pdf, new ByteArrayInputStream(Files.readAllBytes(Path.of("../assets/fonts/LiberationSans-Regular.ttf"))));
            var property = new org.apache.pdfbox.cos.COSDictionary(); property.setString(org.apache.pdfbox.cos.COSName.ACTUAL_TEXT, "LOGICAL-CONTROL");
            if (unsupported) { property.setInt(org.apache.pdfbox.cos.COSName.MCID, 1); }
            try (var content = new PDPageContentStream(pdf, page)) {
                content.beginText(); content.setFont(font, 14); content.newLineAtOffset(20, 120); content.showText("SECRET KEEP"); content.endText();
                var span = org.apache.pdfbox.cos.COSName.getPDFName("Span"); content.beginMarkedContent(span);
                content.beginMarkedContent(span, org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList.create(property));
                content.beginText(); content.setFont(font, 14); content.newLineAtOffset(20, 80); content.showText("KEEP"); content.endText();
                content.endMarkedContent(); content.endMarkedContent();
                if (malformed) { content.endMarkedContent(); }
            }
            var output = new ByteArrayOutputStream(); pdf.save(output); return output.toByteArray();
        }
    }
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
    static java.util.List<String> positions(byte[] bytes) throws Exception {
        var values = new ArrayList<String>();
        var stripper = new PDFTextStripper() { @Override protected void processTextPosition(TextPosition position) { values.add(position.getUnicode() + ":" + Math.round(position.getXDirAdj() * 100) + ":" + Math.round(position.getYDirAdj() * 100)); super.processTextPosition(position); } };
        try (var pdf = Loader.loadPDF(bytes)) { stripper.getText(pdf); } return values;
    }
    private static byte[] write(PreparedPdf prepared) throws Exception { var output = new ByteArrayOutputStream(); var result = prepared.to(output); assertEquals(prepared.preview().issues(), result.report().issues()); return output.toByteArray(); }
}
