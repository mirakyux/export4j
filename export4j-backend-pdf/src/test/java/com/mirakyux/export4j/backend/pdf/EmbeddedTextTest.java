// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.graphics.core.TextLayoutPainter;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.GlyphRun;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.PositionedGlyph;
import com.mirakyux.export4j.text.SimpleTextShaper;

class EmbeddedTextTest {
    private static FontFace latin;
    private static FontFace chinese;
    private static final PageSize SIZE = new PageSize(300, 300);

    @BeforeAll
    static void loadFonts() throws IOException {
        latin = load("LiberationSans-Regular.ttf");
        chinese = load("LXGWWenKaiTC-Regular.ttf");
    }

    @Test
    void embedsSubsetFontsWithUnicodeAndMatchesMeasuredMixedTextPositions() throws IOException {
        var layout = new ParagraphLayouter().layout("A中B文", List.of(latin, chinese),
                200, ParagraphStyle.of(12));
        DisplayListBuilder builder = new DisplayListBuilder(SIZE);
        TextLayoutPainter.draw(builder, layout, new Point(20, 30), RgbColor.BLACK);
        DisplayPage page = builder.build();
        assertEquals(4, page.commands().size());
        assertEquals(latin.data().id(), ((DrawCommand.GlyphText) page.commands().getFirst()).run().font().id());
        try (PDDocument pdf = Loader.loadPDF(render(new PdfBackend(), document(page)))) {
            PositionCollector collector = new PositionCollector();
            assertEquals("A中B文", collector.getText(pdf).strip());
            List<PDFont> fonts = fonts(pdf.getPage(0).getResources());
            assertEquals(2, fonts.size());
            for (PDFont font : fonts) {
                assertTrue(font.isEmbedded());
                assertTrue(font.getName().matches("[A-Z]{6}\\+.+"), font.getName());
                assertNotNull(font.getCOSObject().getDictionaryObject("ToUnicode"));
            }
            double expectedX = 20;
            for (int i = 0; i < collector.positions.size(); i++) {
                TextPosition position = collector.positions.get(i);
                GlyphRun run = ((DrawCommand.GlyphText) page.commands().get(i)).run();
                assertEquals(expectedX, position.getXDirAdj(), 0.001);
                assertEquals(30 + layout.lines().getFirst().baselineY(), position.getYDirAdj(), 0.001);
                assertEquals(run.width(), position.getWidthDirAdj(), 0.001);
                expectedX += run.width();
            }
            assertEquals(20 + layout.lines().getFirst().width(), expectedX, 0.001);
        }
    }

    @Test
    void mixedInlineImagesKeepNativeChineseTextAndOriginalGlyphPositions() throws IOException {
        var encoded = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "png", encoded);
        var image = new com.mirakyux.export4j.model.InlineImage(
                com.mirakyux.export4j.model.ImageResource.load(encoded.toByteArray()), 24, 12, 0, "formula");
        var group = com.mirakyux.export4j.font.FontGroup.strict(List.of(latin, chinese));
        var span = new com.mirakyux.export4j.text.StyledSpan("年龄\ufffcDN病程", group, 12, 0, 0, false, false);
        var layout = new ParagraphLayouter().layout(List.of(span), 240, ParagraphStyle.of(12), java.util.Map.of(2, image));
        assertFalse(layout.lines().getFirst().glyphRunBoxes().isEmpty());
        var builder = new DisplayListBuilder(SIZE);
        TextLayoutPainter.draw(builder, layout, new Point(20, 30), RgbColor.BLACK);
        var page = builder.build();
        assertEquals(1, page.commands().stream().filter(DrawCommand.Image.class::isInstance).count());
        assertEquals(List.of("\ufffc"), page.commands().stream().filter(DrawCommand.OutlineText.class::isInstance)
                .map(DrawCommand.OutlineText.class::cast).map(DrawCommand.OutlineText::logicalText).toList());
        assertFalse(page.commands().stream().anyMatch(DrawCommand.FillPath.class::isInstance));
        try (var pdf = Loader.loadPDF(render(new PdfBackend(), document(page)))) {
            var collector = new PositionCollector();
            String extracted = collector.getText(pdf);
            assertTrue(extracted.contains("年龄"), extracted);
            assertTrue(extracted.contains("DN病程"), extracted);
            for (var box : layout.lines().getFirst().glyphRunBoxes()) {
                double x = 20 + box.x();
                for (var glyph : box.run().glyphs()) {
                    String character = new String(Character.toChars(glyph.codePoint()));
                    var position = collector.positions.stream().filter(value -> value.getUnicode().equals(character))
                            .findFirst().orElseThrow();
                    assertEquals(x, position.getXDirAdj(), .001);
                    x += glyph.advance();
                }
            }
        }
    }

    @Test
    void honorsExplicitAdvanceWithoutRemeasuringAndReusesFontAcrossPages() throws IOException {
        PositionedGlyph a = new SimpleTextShaper().shape("A", List.of(latin), 12).glyphs().getFirst().glyph();
        GlyphRun spaced = new GlyphRun(latin.data(), 12, List.of(
                new PositionedGlyph('A', a.glyphId(), 40), a));
        DisplayPage page = new DisplayListBuilder(SIZE)
                .drawGlyphRun(spaced, new Point(20, 50), RgbColor.BLACK).build();
        DisplayDocument document = new DisplayDocument(DocumentMetadata.EMPTY, List.of(page, page));
        TrackingOutput output = new TrackingOutput();
        new PdfBackend().write(document, output);
        assertFalse(output.closed);
        try (PDDocument pdf = Loader.loadPDF(output.toByteArray())) {
            PositionCollector collector = new PositionCollector();
            collector.getText(pdf);
            assertEquals(4, collector.positions.size());
            assertEquals(20, collector.positions.get(0).getXDirAdj(), 0.001);
            assertEquals(60, collector.positions.get(1).getXDirAdj(), 0.001);
            assertSame(fonts(pdf.getPage(0).getResources()).getFirst().getCOSObject(),
                    fonts(pdf.getPage(1).getResources()).getFirst().getCOSObject());
        }
    }

    @Test
    void joinsNaturalAdvancesWithoutCrossingRunBoundariesOrChangingGlyphPositions() throws IOException {
        var face = load("PositionedTest.ttf");
        String text = "AB".repeat(32);
        var glyphs = new SimpleTextShaper().shape(text, List.of(face), 10).glyphs().stream()
                .map(value -> value.glyph()).toList();
        var run = new GlyphRun(face.data(), 10, glyphs);
        var page = new DisplayListBuilder(new PageSize(1_000, 300))
                .drawGlyphRun(run, new Point(20, 50), RgbColor.BLACK)
                .drawGlyphRun(run, new Point(20, 80), new RgbColor(0, 0, 1)).build();
        try (var pdf = Loader.loadPDF(render(new PdfBackend(), document(page)))) {
            var arrays = textArrays(pdf);
            assertEquals(2, arrays.size());
            for (var array : arrays) {
                assertEquals(1, array.size());
                assertEquals(128, ((COSString) array.getObject(0)).getBytes().length);
            }
            var collector = new PositionCollector();
            assertEquals(List.of(text, text), collector.getText(pdf).lines().toList());
            assertEquals(128, collector.positions.size());
            for (int line = 0; line < 2; line++) {
                double cursor = 20;
                for (int index = 0; index < glyphs.size(); index++) {
                    var actual = collector.positions.get(line * glyphs.size() + index);
                    assertEquals(cursor, actual.getXDirAdj(), .001);
                    assertEquals(50 + line * 30, actual.getYDirAdj(), .001);
                    cursor += glyphs.get(index).advance();
                }
            }
            assertEquals(1, fonts(pdf.getPage(0).getResources()).size());
        }
    }

    @Test
    void keepsExplicitNonzeroAdvanceBetweenJoinedStrings() throws IOException {
        var face = load("PositionedTest.ttf");
        var natural = new SimpleTextShaper().shape("A", List.of(face), 10).glyphs().getFirst().glyph();
        var changed = new PositionedGlyph('A', natural.glyphId(), natural.advance() + .25);
        var page = new DisplayListBuilder(SIZE).drawGlyphRun(
                new GlyphRun(face.data(), 10, List.of(natural, changed, natural)),
                new Point(20, 50), RgbColor.BLACK).build();
        try (var pdf = Loader.loadPDF(render(new PdfBackend(), document(page)))) {
            var arrays = textArrays(pdf);
            assertEquals(1, arrays.size());
            var array = arrays.getFirst();
            assertEquals(3, array.size());
            assertEquals(4, ((COSString) array.getObject(0)).getBytes().length);
            assertEquals(-25, ((COSNumber) array.getObject(1)).floatValue());
            assertEquals(2, ((COSString) array.getObject(2)).getBytes().length);
            var collector = new PositionCollector();
            assertEquals("AAA", collector.getText(pdf).strip());
            assertEquals(3, collector.positions.size());
            assertEquals(20, collector.positions.get(0).getXDirAdj(), .001);
            assertEquals(26, collector.positions.get(1).getXDirAdj(), .001);
            assertEquals(32.25, collector.positions.get(2).getXDirAdj(), .001);
        }
    }

    @Test
    void joinsSupplementaryScalarsWithoutSplittingUtf16Pairs() throws IOException {
        String text = "中\uD840\uDC03文";
        var glyphs = new SimpleTextShaper().shape(text, List.of(chinese), 10).glyphs().stream()
                .map(value -> value.glyph()).toList();
        var page = new DisplayListBuilder(SIZE).drawGlyphRun(new GlyphRun(chinese.data(), 10, glyphs),
                new Point(20, 50), RgbColor.BLACK).build();
        try (var pdf = Loader.loadPDF(render(new PdfBackend(), document(page)))) {
            var arrays = textArrays(pdf);
            assertEquals(1, arrays.size());
            assertEquals(1, arrays.getFirst().size());
            assertEquals(6, ((COSString) arrays.getFirst().getObject(0)).getBytes().length);
            var collector = new PositionCollector();
            assertEquals(text, collector.getText(pdf).strip());
            assertEquals(3, collector.positions.size());
            for (int index = 0; index < 3; index++) {
                assertEquals(20 + index * 10, collector.positions.get(index).getXDirAdj(), .001);
            }
        }
    }

    @Test
    void relativeGlyphSpacingIsIndependentOfTheAbsoluteTextOrigin() throws IOException {
        var shaped = new SimpleTextShaper().shape("GROUP-01", List.of(latin), 11);
        var run = new GlyphRun(latin.data(), 11, shaped.glyphs().stream().map(g -> g.glyph()).toList());
        var page = new DisplayListBuilder(SIZE)
                .drawGlyphRun(run, new Point(20.12345, 50), RgbColor.BLACK)
                .drawGlyphRun(run, new Point(130.56789, 80), RgbColor.BLACK).build();
        try (var pdf = Loader.loadPDF(render(new PdfBackend(), document(page)))) {
            var adjustments = new ArrayList<List<Float>>();
            for (var token : new PDFStreamParser(pdf.getPage(0)).parse()) {
                if (token instanceof COSArray array) {
                    var values = new ArrayList<Float>();
                    for (int i = 0; i < array.size(); i++) {
                        if (array.getObject(i) instanceof COSNumber number) { values.add(number.floatValue()); }
                    }
                    adjustments.add(values);
                }
            }
            assertEquals(2, adjustments.size());
            assertEquals(adjustments.getFirst(), adjustments.getLast());
            var collector = new PositionCollector();
            collector.getText(pdf);
            assertEquals(16, collector.positions.size());
            for (int i = 0; i < 8; i++) {
                double first = collector.positions.get(i).getXDirAdj() - collector.positions.getFirst().getXDirAdj();
                double second = collector.positions.get(i + 8).getXDirAdj() - collector.positions.get(8).getXDirAdj();
                assertEquals(first, second, .001);
            }
        }
    }

    @Test
    void rejectsGlyphMappingThatDiffersFromMeasuredFontBeforeWriting() {
        GlyphRun invalid = new GlyphRun(latin.data(), 12,
                List.of(new PositionedGlyph('A', latin.glyph('B').orElseThrow().glyphId(), 10)));
        DisplayPage page = new DisplayListBuilder(SIZE)
                .drawGlyphRun(invalid, new Point(20, 50), RgbColor.BLACK).build();
        TrackingOutput output = new TrackingOutput();
        PdfRenderException failure = assertThrows(PdfRenderException.class,
                () -> new PdfBackend().write(document(page), output));
        assertEquals(PdfErrorCode.INVALID_FONT, failure.code());
        assertEquals(0, failure.pageIndex());
        assertEquals(0, failure.commandIndex());
        assertEquals(0, output.size());
        assertFalse(output.closed);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cachedMappingsStillValidateEveryGlyphAndEveryUnicodeScalar(boolean session) throws IOException {
        var natural = latinRun("AA");
        var a = natural.glyphs().getFirst();
        for (var invalid : List.of(
                new PositionedGlyph('A', latin.glyph('B').orElseThrow().glyphId(), a.advance()),
                new PositionedGlyph('B', a.glyphId(), a.advance()))) {
            var page = new DisplayListBuilder(SIZE)
                    .drawGlyphRun(natural, new Point(20, 50), RgbColor.BLACK)
                    .drawGlyphRun(new GlyphRun(latin.data(), 12, List.of(invalid)), new Point(20, 80), RgbColor.BLACK).build();
            var failure = assertThrows(PdfRenderException.class,
                    () -> render(new PdfBackend(), document(page), session));
            assertEquals(PdfErrorCode.INVALID_FONT, failure.code());
            assertEquals(0, failure.pageIndex());
            assertEquals(1, failure.commandIndex());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void glyphMappingsStayIndependentAcrossFontsAndWritesOnTheSameBackend(boolean session) throws IOException {
        var geometric = load("PositionedTest.ttf");
        var backend = new PdfBackend();
        for (var faces : List.of(List.of(geometric, latin, chinese), List.of(chinese, geometric, latin))) {
            var builder = new DisplayListBuilder(SIZE);
            var runs = new ArrayList<GlyphRun>();
            for (int index = 0; index < faces.size(); index++) {
                var face = faces.get(index);
                var run = new GlyphRun(face.data(), 10, new SimpleTextShaper().shape("ABAA", List.of(face), 10)
                        .glyphs().stream().map(value -> value.glyph()).toList());
                runs.add(run);
                builder.drawGlyphRun(run, new Point(20, 40 + index * 30), RgbColor.BLACK);
            }
            try (var pdf = Loader.loadPDF(render(backend, document(builder.build()), session))) {
                var collector = new PositionCollector();
                assertEquals(List.of("ABAA", "ABAA", "ABAA"), collector.getText(pdf).lines().toList());
                assertEquals(3, fonts(pdf.getPage(0).getResources()).size());
                assertEquals(12, collector.positions.size());
                for (int line = 0; line < runs.size(); line++) {
                    double cursor = 20;
                    for (int index = 0; index < 4; index++) {
                        var position = collector.positions.get(line * 4 + index);
                        assertEquals(cursor, position.getXDirAdj(), .001);
                        cursor += runs.get(line).glyphs().get(index).advance();
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void boundedGlyphReuseKeepsUncachedAndSupplementaryScalarsCorrect(boolean session) throws IOException {
        var text = new StringBuilder();
        IntStream.range(0x4e00, 0x5200)
                .filter(value -> chinese.glyph(value).isPresent()).limit(300)
                .forEach(text::appendCodePoint);
        assertEquals(300, text.codePointCount(0, text.length()));
        int first = text.codePointAt(0);
        text.appendCodePoint(0x20003).appendCodePoint(first).appendCodePoint(0x20003);
        String expected = text.toString();
        var run = new GlyphRun(chinese.data(), 1, new SimpleTextShaper().shape(expected, List.of(chinese), 1)
                .glyphs().stream().map(value -> value.glyph()).toList());
        var page = new DisplayListBuilder(new PageSize(1_000, 100))
                .drawGlyphRun(run, new Point(20, 50), RgbColor.BLACK).build();
        try (var pdf = Loader.loadPDF(render(new PdfBackend(), document(page), session))) {
            var collector = new PositionCollector();
            assertEquals(expected, collector.getText(pdf).strip());
            assertEquals(303, collector.positions.size());
            for (int index = 0; index < 303; index++) {
                assertEquals(20 + index, collector.positions.get(index).getXDirAdj(), .001);
            }
        }
        var invalid = new PositionedGlyph(0x20003, chinese.glyph('A').orElseThrow().glyphId(), 1);
        var badPage = new DisplayListBuilder(new PageSize(1_000, 100))
                .drawGlyphRun(run, new Point(20, 50), RgbColor.BLACK)
                .drawGlyphRun(new GlyphRun(chinese.data(), 1, List.of(invalid)), new Point(20, 80), RgbColor.BLACK).build();
        var failure = assertThrows(PdfRenderException.class,
                () -> render(new PdfBackend(), document(badPage), session));
        assertEquals(PdfErrorCode.INVALID_FONT, failure.code());
        assertEquals(1, failure.commandIndex());
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void preservesDistinctUnicodeScalarsSharingOneGlyphWithSubsetOrFullEmbedding(boolean session, boolean full)
            throws IOException {
        var face = full ? withoutSubsetting(chinese) : chinese;
        assertEquals(face.glyph('丢').orElseThrow().glyphId(), face.glyph('丟').orElseThrow().glyphId(),
                "the real font must exercise two Unicode scalars sharing one glyph");
        var texts = List.of("丢丟丢A\uD840\uDC03丟", "丟丢丟B\uD840\uDC03丢", "丢", "丟");
        var runs = new ArrayList<GlyphRun>();
        var pages = new ArrayList<DisplayPage>();
        for (String text : texts) {
            var glyphs = new ArrayList<>(new SimpleTextShaper().shape(text, List.of(face), 12)
                    .glyphs().stream().map(value -> value.glyph()).toList());
            if (glyphs.size() > 2) {
                var repeated = glyphs.get(1);
                glyphs.set(1, new PositionedGlyph(repeated.codePoint(), repeated.glyphId(), repeated.advance() + .375));
            }
            var run = new GlyphRun(face.data(), 12, glyphs);
            runs.add(run);
            pages.add(new DisplayListBuilder(SIZE).drawGlyphRun(run, new Point(20, 50), RgbColor.BLACK).build());
        }
        try (var pdf = Loader.loadPDF(render(new PdfBackend(), new DisplayDocument(DocumentMetadata.EMPTY, pages), session))) {
            var collector = new PositionCollector();
            assertEquals(texts, collector.getText(pdf).lines().toList());
            int positionIndex = 0;
            var firstFont = fonts(pdf.getPage(0).getResources()).getFirst();
            int programBytes = firstFont.getFontDescriptor().getFontFile2().toByteArray().length;
            if (full) { assertEquals(face.data().byteSize(), programBytes); }
            else { assertTrue(programBytes < face.data().byteSize()); }
            for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
                assertSame(firstFont.getCOSObject(), fonts(pdf.getPage(pageIndex).getResources()).getFirst().getCOSObject());
                double cursor = 20;
                for (var glyph : runs.get(pageIndex).glyphs()) {
                    var position = collector.positions.get(positionIndex++);
                    assertEquals(Character.toString(glyph.codePoint()), position.getUnicode());
                    assertEquals(cursor, position.getXDirAdj(), .001);
                    assertEquals(50, position.getYDirAdj(), .001);
                    cursor += glyph.advance();
                }
            }
            assertEquals(positionIndex, collector.positions.size());
        }
    }

    @Test
    void embedsCompleteFontWhenNoSubsettingPermissionIsSet() throws IOException {
        FontFace complete = withoutSubsetting(latin);
        byte[] bytes = complete.data().bytes();
        GlyphRun run = new GlyphRun(complete.data(), 12, new SimpleTextShaper()
                .shape("Full font", List.of(complete), 12).glyphs().stream().map(g -> g.glyph()).toList());
        DisplayPage page = new DisplayListBuilder(SIZE)
                .drawGlyphRun(run, new Point(20, 50), RgbColor.BLACK).build();
        try (PDDocument pdf = Loader.loadPDF(render(new PdfBackend(), document(page)))) {
            PDFont font = fonts(pdf.getPage(0).getResources()).getFirst();
            assertTrue(font.isEmbedded());
            assertFalse(font.getName().contains("+"));
            assertEquals(bytes.length, font.getFontDescriptor().getFontFile2().toByteArray().length);
            assertEquals("Full font", new PDFTextStripper().getText(pdf).strip());
        }
    }

    private static FontFace withoutSubsetting(FontFace source) throws IOException {
        byte[] bytes = source.data().bytes();
        ByteBuffer table = ByteBuffer.wrap(bytes);
        boolean changed = false;
        for (int index = 0; index < Short.toUnsignedInt(table.getShort(4)); index++) {
            int entry = 12 + index * 16;
            if (new String(bytes, entry, 4, StandardCharsets.US_ASCII).equals("OS/2")) {
                table.putShort(table.getInt(entry + 8) + 8, (short) 0x0100);
                changed = true;
                break;
            }
        }
        assertTrue(changed);
        return TrueTypeFontLoader.load(bytes);
    }

    @Test
    void countsUniqueFontBytesAndEveryGlyphAgainstBudgets() throws IOException {
        GlyphRun run = latinRun("AB");
        DisplayPage page = new DisplayListBuilder(SIZE)
                .drawGlyphRun(run, new Point(20, 50), RgbColor.BLACK)
                .drawGlyphRun(run, new Point(20, 80), RgbColor.BLACK).build();
        PdfLimits exact = new PdfLimits(2, 10, 2, 100, 4, 100, latin.data().byteSize());
        assertTrue(render(new PdfBackend(exact), document(page)).length > 0);
        for (PdfLimits limits : List.of(
                new PdfLimits(2, 10, 2, 100, 3, 100, latin.data().byteSize()),
                new PdfLimits(2, 10, 2, 100, 4, 100, latin.data().byteSize() - 1))) {
            TrackingOutput output = new TrackingOutput();
            PdfRenderException failure = assertThrows(PdfRenderException.class,
                    () -> new PdfBackend(limits).write(document(page), output));
            assertEquals(PdfErrorCode.BUDGET_EXCEEDED, failure.code());
            assertEquals(0, output.size());
        }
    }

    @Test
    void sameBackendRendersIndependentEmbeddedFontDocumentsConcurrently() throws Exception {
        PdfBackend backend = new PdfBackend();
        DisplayPage page = new DisplayListBuilder(SIZE)
                .drawGlyphRun(latinRun("Parallel"), new Point(20, 50), RgbColor.BLACK).build();
        List<Callable<byte[]>> tasks = IntStream.range(0, 4)
                .<Callable<byte[]>>mapToObj(index -> () -> render(backend, document(page))).toList();
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (var result : executor.invokeAll(tasks)) {
                try (PDDocument pdf = Loader.loadPDF(result.get())) {
                    assertEquals("Parallel", new PDFTextStripper().getText(pdf).strip());
                    assertTrue(fonts(pdf.getPage(0).getResources()).getFirst().isEmbedded());
                }
            }
        }
    }

    private static GlyphRun latinRun(String text) {
        return new GlyphRun(latin.data(), 12, new SimpleTextShaper().shape(text, List.of(latin), 12)
                .glyphs().stream().map(g -> g.glyph()).toList());
    }

    private static List<COSArray> textArrays(PDDocument pdf) throws IOException {
        var arrays = new ArrayList<COSArray>();
        var tokens = new PDFStreamParser(pdf.getPage(0)).parse();
        for (int index = 1; index < tokens.size(); index++) {
            if (tokens.get(index) instanceof Operator operator && operator.getName().equals("TJ")) {
                arrays.add((COSArray) tokens.get(index - 1));
            }
        }
        return arrays;
    }

    private static List<PDFont> fonts(PDResources resources) throws IOException {
        List<PDFont> fonts = new ArrayList<>();
        for (var name : resources.getFontNames()) {
            fonts.add(resources.getFont(name));
        }
        return fonts;
    }

    private static DisplayDocument document(DisplayPage page) {
        return new DisplayDocument(DocumentMetadata.EMPTY, List.of(page));
    }

    private static byte[] render(PdfBackend backend, DisplayDocument document) throws IOException {
        return render(backend, document, false);
    }

    private static byte[] render(PdfBackend backend, DisplayDocument document, boolean session) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (session) {
            try (var writer = backend.open(document.metadata(), output)) {
                for (var page : document.pages()) { writer.accept(page); }
                writer.finish();
            }
        } else { backend.write(document, output); }
        return output.toByteArray();
    }

    private static FontFace load(String name) throws IOException {
        try (InputStream input = EmbeddedTextTest.class.getResourceAsStream("/fonts/" + name)) {
            if (input == null) {
                throw new IOException("Missing test font");
            }
            return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
    }

    private static final class PositionCollector extends PDFTextStripper {
        private final List<TextPosition> positions = new ArrayList<>();

        @Override
        protected void processTextPosition(TextPosition position) {
            positions.add(position);
            super.processTextPosition(position);
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
