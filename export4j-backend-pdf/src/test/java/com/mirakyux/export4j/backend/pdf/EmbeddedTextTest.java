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
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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

    @Test
    void embedsCompleteFontWhenNoSubsettingPermissionIsSet() throws IOException {
        byte[] bytes = latin.data().bytes();
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
        FontFace complete = TrueTypeFontLoader.load(bytes);
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
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        backend.write(document, output);
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
