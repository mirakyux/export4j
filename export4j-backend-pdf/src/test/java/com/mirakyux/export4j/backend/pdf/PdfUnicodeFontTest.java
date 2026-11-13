// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.GlyphRun;
import com.mirakyux.export4j.text.PositionedGlyph;

class PdfUnicodeFontTest {
    private static final int FIRST_SCALAR = 0xf0000;
    private static final int SCALARS = 63_500;
    private static final int GLYPHS = SCALARS + 2;
    private static final int FIRST_BANK_SIZE = 63_487;
    private static final double FONT_SIZE = 5.0 / 32;
    private static final double ORIGIN_X = 20;
    private static final double BASELINE = 50;

    @Test
    void preservesAliasedScalarsAcrossSurrogateCodesAndTheNextFontBank() throws IOException {
        byte[] original;
        try (var input = getClass().getResourceAsStream("/fonts/PositionedTest.ttf")) {
            assertNotNull(input);
            original = input.readAllBytes();
        }
        var originalGlyph = TrueTypeFontLoader.load(original).glyph('A').orElseThrow();
        byte[] program = aliasedCmap(original, originalGlyph.glyphId());
        assertTrue(program.length < 1024 * 1024, "the runtime font fixture must remain bounded");
        var face = TrueTypeFontLoader.load(program);
        double advance = originalGlyph.advance() * FONT_SIZE;
        // Binary-exact advances avoid cumulative floating-point drift in a 63,500-glyph line.
        assertEquals(3.0 / 32, advance);
        var glyphs = new ArrayList<PositionedGlyph>(GLYPHS);
        var expected = new StringBuilder(2 * GLYPHS);
        var expectedX = new double[GLYPHS];
        double cursor = ORIGIN_X;
        for (int index = 0; index < GLYPHS; index++) {
            int scalar = scalarAt(index);
            assertEquals(originalGlyph, face.glyph(scalar).orElseThrow());
            // The last glyph in the first bank must apply its trailing TJ before switching fonts.
            double positionedAdvance = advance + (index == FIRST_BANK_SIZE - 1 ? 1.0 / 32 : 0);
            glyphs.add(new PositionedGlyph(scalar, originalGlyph.glyphId(), positionedAdvance));
            expectedX[index] = cursor;
            cursor += positionedAdvance;
            expected.appendCodePoint(scalar);
        }
        var page = new DisplayListBuilder(new PageSize(cursor + ORIGIN_X, 80))
                .drawGlyphRun(new GlyphRun(face.data(), FONT_SIZE, glyphs),
                        new Point(ORIGIN_X, BASELINE), RgbColor.BLACK).build();
        var output = new ByteArrayOutputStream();
        new PdfBackend().write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(page)), output);
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            assertEquals(1, pdf.getNumberOfPages());
            assertFontCodes(pdf.getPage(0));
            var collector = new SourceCollector();
            collector.getText(pdf);
            assertEquals(expected.toString(), collector.source.toString());
            assertEquals(GLYPHS, collector.count);
            assertEquals(GLYPHS, collector.source.codePointCount(0, collector.source.length()));
            for (int index = 0; index < GLYPHS; index++) {
                // PDF positions are in points; 0.001pt also detects a lost/reset advance at a bank switch.
                assertEquals(expectedX[index], collector.x[index], .001, "glyph " + index);
                assertEquals(BASELINE, collector.y[index], .001, "baseline " + index);
            }
        }
    }

    private static int scalarAt(int sourceIndex) {
        if (sourceIndex < SCALARS) { return FIRST_SCALAR + sourceIndex; }
        return sourceIndex == SCALARS ? FIRST_SCALAR : FIRST_SCALAR + SCALARS - 1;
    }

    private static void assertFontCodes(PDPage page) throws IOException {
        var fonts = new ArrayList<PDType0Font>();
        for (var name : page.getResources().getFontNames()) {
            fonts.add(assertInstanceOf(PDType0Font.class, page.getResources().getFont(name)));
        }
        assertEquals(2, fonts.size(), "the first encoding bank must overflow into a second font resource");
        assertSame(fonts.getFirst().getFontDescriptor().getFontFile2().getCOSObject(),
                fonts.getLast().getFontDescriptor().getFontFile2().getCOSObject(), "banks must share the actual font program");
        var used = new HashMap<PDType0Font, Set<Integer>>();
        var operands = new ArrayList<COSBase>();
        PDType0Font current = null;
        PDType0Font first = null;
        PDType0Font second = null;
        int sourceIndex = 0;
        int begins = 0, ends = 0, matrices = 0, fontSelections = 0;
        Integer finalGlyph = null;
        for (var token : new PDFStreamParser(page).parse()) {
            if (!(token instanceof Operator operator)) {
                operands.add((COSBase) token);
                continue;
            }
            if (operator.getName().equals("BT")) { begins++; }
            if (operator.getName().equals("ET")) { ends++; }
            if (operator.getName().equals("Tm")) { matrices++; }
            if (operator.getName().equals("Tf")) {
                fontSelections++;
                current = assertInstanceOf(PDType0Font.class, page.getResources().getFont((COSName) operands.getFirst()));
            } else if (operator.getName().equals("TJ")) {
                assertNotNull(current);
                var array = (COSArray) operands.getFirst();
                if (sourceIndex == 0) {
                    assertEquals(-200, assertInstanceOf(COSNumber.class, array.getObject(array.size() - 1)).floatValue(), .001,
                            "the custom first-bank advance must remain in its final TJ");
                }
                for (var item : array) {
                    if (!(item instanceof COSString string)) { continue; }
                    byte[] codes = string.getBytes();
                    assertEquals(0, codes.length % 2, "Identity-H text must retain complete two-byte codes");
                    for (int offset = 0; offset < codes.length; offset += 2) {
                        int code = Byte.toUnsignedInt(codes[offset]) << 8 | Byte.toUnsignedInt(codes[offset + 1]);
                        assertTrue(code > 0 && (code < 0xd800 || code > 0xdfff), "reserved or surrogate CID " + code);
                        assertEquals(sourceIndex < SCALARS, used.computeIfAbsent(current, ignored -> new HashSet<>()).add(code),
                                "new scalars receive distinct CIDs and repeated scalars retain their original CIDs");
                        assertEquals(new String(Character.toChars(scalarAt(sourceIndex))), current.toUnicode(code));
                        var descendant = assertInstanceOf(PDCIDFontType2.class, current.getDescendantFont());
                        int glyph = descendant.codeToGID(code);
                        assertTrue(glyph > 0, "CIDToGIDMap must select a real embedded glyph");
                        if (finalGlyph == null) { finalGlyph = glyph; }
                        assertEquals(finalGlyph.intValue(), glyph, "all source scalars deliberately share one outline");
                        assertEquals(600, current.getWidth(code), .001);
                        if (sourceIndex == 0) { first = current; assertEquals(1, code); }
                        if (sourceIndex == 55_294) { assertEquals(0xd7ff, code); }
                        if (sourceIndex == 55_295) { assertEquals(0xe000, code); }
                        if (sourceIndex == FIRST_BANK_SIZE - 1) { assertEquals(0xffff, code); assertSame(first, current); }
                        if (sourceIndex == FIRST_BANK_SIZE) {
                            assertEquals(1, code);
                            assertTrue(first != current, "the next bank must use another PDF font resource");
                            second = current;
                        }
                        if (sourceIndex == SCALARS) { assertSame(first, current); assertEquals(1, code); }
                        if (sourceIndex == SCALARS + 1) { assertSame(second, current); assertEquals(SCALARS - FIRST_BANK_SIZE, code); }
                        sourceIndex++;
                    }
                }
            }
            operands.clear();
        }
        assertEquals(GLYPHS, sourceIndex);
        assertEquals(1, begins, "one GlyphRun must retain one continuous PDF text object");
        assertEquals(1, ends);
        assertEquals(1, matrices, "bank switches must retain the existing text matrix and advance");
        assertEquals(4, fontSelections, "the run returns to the first bank and then switches back");
        assertNotNull(first);
        assertEquals(FIRST_BANK_SIZE, used.get(first).size());
        assertEquals(List.of(SCALARS - FIRST_BANK_SIZE, FIRST_BANK_SIZE), used.values().stream().map(Set::size).sorted().toList());
        for (var font : fonts) {
            var bounds = assertInstanceOf(PDCIDFontType2.class, font.getDescendantFont()).getPath(1).getBounds2D();
            assertEquals(400, bounds.getWidth(), .001);
            assertEquals(700, bounds.getHeight(), .001);
        }
    }

    /** Reuses the original Apache-2.0 geometric font, replacing only its Unicode cmap in memory. */
    private static byte[] aliasedCmap(byte[] original, int glyphId) {
        var input = ByteBuffer.wrap(original).order(ByteOrder.BIG_ENDIAN);
        int tableCount = Short.toUnsignedInt(input.getShort(4));
        var tables = new ArrayList<FontTable>(tableCount);
        for (int index = 0; index < tableCount; index++) {
            int entry = 12 + index * 16;
            int tag = input.getInt(entry), offset = input.getInt(entry + 8), length = input.getInt(entry + 12);
            byte[] bytes = Arrays.copyOfRange(original, offset, offset + length);
            if (tag == 0x636d6170) { bytes = cmap(glyphId); } // cmap
            if (tag == 0x68656164) { ByteBuffer.wrap(bytes).putInt(8, 0); } // head.checkSumAdjustment
            tables.add(new FontTable(tag, bytes));
        }
        int size = 12 + tableCount * 16;
        for (var table : tables) { size += aligned(table.bytes().length); }
        var output = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        output.put(original, 0, 12);
        int offset = 12 + tableCount * 16;
        int headOffset = -1;
        for (int index = 0; index < tableCount; index++) {
            var table = tables.get(index);
            output.position(12 + index * 16);
            output.putInt(table.tag()).putInt(checksum(table.bytes())).putInt(offset).putInt(table.bytes().length);
            output.position(offset); output.put(table.bytes());
            if (table.tag() == 0x68656164) { headOffset = offset; }
            offset += aligned(table.bytes().length);
        }
        assertTrue(headOffset >= 0);
        output.putInt(headOffset + 8, (int) (0xb1b0afbaL - Integer.toUnsignedLong(checksum(output.array()))));
        assertEquals(0xb1b0afba, checksum(output.array()), "sfnt checksum adjustment must describe the rebuilt font");
        return output.array();
    }

    private static byte[] cmap(int glyphId) {
        int subtableLength = 16 + 12 * SCALARS;
        var output = ByteBuffer.allocate(12 + subtableLength).order(ByteOrder.BIG_ENDIAN);
        output.putShort((short) 0).putShort((short) 1);
        output.putShort((short) 3).putShort((short) 10).putInt(12); // Windows Unicode full repertoire
        output.putShort((short) 12).putShort((short) 0).putInt(subtableLength).putInt(0).putInt(SCALARS);
        for (int index = 0; index < SCALARS; index++) {
            int scalar = FIRST_SCALAR + index;
            output.putInt(scalar).putInt(scalar).putInt(glyphId);
        }
        return output.array();
    }

    private static int aligned(int length) { return (length + 3) & ~3; }

    private static int checksum(byte[] bytes) {
        int sum = 0;
        for (int offset = 0; offset < bytes.length; offset += 4) {
            int word = 0;
            for (int index = 0; index < 4; index++) {
                word = word << 8 | (offset + index < bytes.length ? Byte.toUnsignedInt(bytes[offset + index]) : 0);
            }
            sum += word;
        }
        return sum;
    }

    private record FontTable(int tag, byte[] bytes) { }

    private static final class SourceCollector extends PDFTextStripper {
        private final StringBuilder source = new StringBuilder(2 * GLYPHS);
        private final double[] x = new double[GLYPHS];
        private final double[] y = new double[GLYPHS];
        private int count;

        @Override protected void processTextPosition(TextPosition position) {
            assertTrue(count < GLYPHS, "extraction produced extra glyphs");
            assertEquals(1, position.getUnicode().codePointCount(0, position.getUnicode().length()));
            source.append(position.getUnicode());
            x[count] = position.getXDirAdj(); y[count] = position.getYDirAdj(); count++;
        }
    }
}
