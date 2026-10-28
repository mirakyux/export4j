// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.text.PositionedGlyph;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.apache.fontbox.cff.CFFCIDFont;
import org.apache.fontbox.cff.CFFFont;
import org.apache.fontbox.ttf.OTFParser;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1CFont;

/** Complete static CFF embedding. Name-keyed CFF uses Type1C; CID-keyed CFF uses CIDFontType0C. */
final class PdfCffFont {
    private PdfCffFont() { }

    /** One encoding per Unicode page; complete CFF font stream is shared across all encodings. */
    static PDFont get(PDDocument document, FontData data, PositionedGlyph glyph, Map<String, PDFont> cache) throws IOException {
        String key = data.id() + ":cff:" + (glyph.codePoint() >>> 8);
        var existing = cache.get(key);
        if (existing != null) { validate(existing, glyph); return existing; }
        try (var buffer = new RandomAccessReadBuffer(data.bytes()); var font = new OTFParser().parse(buffer)) {
            CFFFont cff = font.getCFF().getFont();
            int base = glyph.codePoint() & ~255;
            var mappings = new HashMap<Integer, Integer>();
            var glyphIds = new HashMap<Integer, Integer>();
            var widths = new COSArray(); var differences = new COSArray();
            var cmap = font.getUnicodeCmapLookup(false);
            StringBuilder unicode = new StringBuilder(); StringBuilder cidMap = new StringBuilder();
            int entries = 0;
            var cidWidths = new COSArray();
            for (int code = 0; code < 256; code++) {
                ExecutionContext.checkpoint();
                int cp = base + code, gid = cmap.getGlyphId(cp);
                double width = gid == 0 ? 0 : font.getAdvanceWidth(gid) * 1000d / font.getUnitsPerEm();
                widths.add(new COSFloat((float) width));
                if (gid == 0) { continue; }
                mappings.put(cp, code); glyphIds.put(cp, gid);
                if (!(cff instanceof CFFCIDFont)) {
                    String name = cff.getCharset().getNameForGID(gid);
                    differences.add(COSInteger.get(code)); differences.add(COSName.getPDFName(name == null ? ".notdef" : name));
                }
                String encoded = String.format(java.util.Locale.ROOT, "%02X", code);
                String logical = java.util.HexFormat.of().withUpperCase().formatHex(new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_16BE));
                unicode.append('<').append(encoded).append("> <").append(logical).append(">\n");
                if (cff instanceof CFFCIDFont) {
                    int cid = cff.getCharset().getCIDForGID(gid);
                    cidMap.append('<').append(encoded).append("> ").append(cid).append('\n');
                    cidWidths.add(COSInteger.get(cid)); var value = new COSArray(); value.add(new COSFloat((float) width)); cidWidths.add(value);
                }
                entries++;
            }
            var descriptor = new PDFontDescriptor(new COSDictionary());
            descriptor.setFontName(cff.getName()); descriptor.setSymbolic(true);
            double scale = 1000d / font.getUnitsPerEm(); var head = font.getHeader();
            descriptor.setFontBoundingBox(new PDRectangle((float) (head.getXMin() * scale), (float) (head.getYMin() * scale),
                    (float) ((head.getXMax() - head.getXMin()) * scale), (float) ((head.getYMax() - head.getYMin()) * scale)));
            descriptor.setAscent((float) (font.getHorizontalHeader().getAscender() * scale)); descriptor.setDescent((float) (font.getHorizontalHeader().getDescender() * scale));
            descriptor.setCapHeight((float) (font.getOS2Windows().getCapHeight() * scale)); descriptor.setItalicAngle(font.getPostScript().getItalicAngle()); descriptor.setStemV(80);
            var first = cache.get(data.id() + ":cff-program");
            if (first == null) {
                var program = new PDStream(document, new ByteArrayInputStream(cff.getData()), COSName.FLATE_DECODE);
                program.getCOSObject().setName(COSName.SUBTYPE, cff instanceof CFFCIDFont ? "CIDFontType0C" : "Type1C"); descriptor.setFontFile3(program);
            } else { descriptor.setFontFile3(first.getFontDescriptor().getFontFile3()); }
            var dictionary = new COSDictionary(); dictionary.setItem(COSName.TYPE, COSName.FONT); dictionary.setName(COSName.BASE_FONT, cff.getName());
            dictionary.setItem(COSName.TO_UNICODE, cmapStream(document, unicode.toString(), entries, false, "Adobe", "UCS", 0));
            PDFont created;
            if (cff instanceof CFFCIDFont cid) {
                dictionary.setName(COSName.SUBTYPE, "Type0");
                dictionary.setItem(COSName.ENCODING, cmapStream(document, cidMap.toString(), entries, true, cid.getRegistry(), cid.getOrdering(), cid.getSupplement()));
                var descendant = new COSDictionary(); descendant.setItem(COSName.TYPE, COSName.FONT); descendant.setName(COSName.SUBTYPE, "CIDFontType0"); descendant.setName(COSName.BASE_FONT, cff.getName());
                var system = new COSDictionary(); system.setItem(COSName.REGISTRY, new COSString(cid.getRegistry())); system.setItem(COSName.ORDERING, new COSString(cid.getOrdering())); system.setInt(COSName.SUPPLEMENT, cid.getSupplement());
                descendant.setItem(COSName.CIDSYSTEMINFO, system); descendant.setItem(COSName.FONT_DESC, descriptor); descendant.setItem(COSName.W, cidWidths);
                var descendants = new COSArray(); descendants.add(descendant); dictionary.setItem(COSName.DESCENDANT_FONTS, descendants);
                created = new EncodedCid(dictionary, mappings, glyphIds);
            } else {
                dictionary.setName(COSName.SUBTYPE, "Type1"); dictionary.setItem(COSName.FONT_DESC, descriptor); dictionary.setInt(COSName.FIRST_CHAR, 0); dictionary.setInt(COSName.LAST_CHAR, 255); dictionary.setItem(COSName.WIDTHS, widths);
                var encoding = new COSDictionary(); encoding.setItem(COSName.TYPE, COSName.ENCODING); encoding.setItem(COSName.DIFFERENCES, differences); dictionary.setItem(COSName.ENCODING, encoding);
                created = new EncodedSimple(dictionary, mappings, glyphIds);
            }
            validate(created, glyph); cache.put(key, created); cache.putIfAbsent(data.id() + ":cff-program", created); return created;
        }
    }
    private static PDStream cmapStream(PDDocument document, String entries, int count, boolean cid, String registry, String ordering, int supplement) throws IOException {
        var text = new StringBuilder("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n/CIDSystemInfo << /Registry (")
                .append(psString(registry)).append(") /Ordering (").append(psString(ordering)).append(") /Supplement ").append(supplement).append(" >> def\n/CMapName /Export4jCFF def\n/CMapType ").append(cid ? 1 : 2).append(" def\n/WMode 0 def\n1 begincodespacerange\n<00> <FF>\nendcodespacerange\n");
        String[] rows = entries.split("\n");
        for (int start = 0; start < count; start += 100) {
            int size = Math.min(100, count - start); text.append(size).append(cid ? " begincidchar\n" : " beginbfchar\n");
            for (int index = 0; index < size; index++) { text.append(rows[start + index]).append('\n'); }
            text.append(cid ? "endcidchar\n" : "endbfchar\n");
        }
        text.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        var stream = new PDStream(document, new ByteArrayInputStream(text.toString().getBytes(StandardCharsets.US_ASCII)), COSName.FLATE_DECODE);
        if (cid) {
            var dictionary = stream.getCOSObject();
            dictionary.setItem(COSName.TYPE, COSName.CMAP); dictionary.setName(COSName.CMAPNAME, "Export4jCFF");
            var system = new COSDictionary(); system.setItem(COSName.REGISTRY, new COSString(registry));
            system.setItem(COSName.ORDERING, new COSString(ordering)); system.setInt(COSName.SUPPLEMENT, supplement);
            dictionary.setItem(COSName.CIDSYSTEMINFO, system); dictionary.setInt(COSName.getPDFName("WMode"), 0);
        }
        return stream;
    }
    private static String psString(String value) {
        if (!value.matches("[A-Za-z0-9_.-]{1,127}")) { throw new IllegalArgumentException("invalid embedded CFF registry or ordering"); }
        return value;
    }
    private static void validate(PDFont font, PositionedGlyph glyph) throws IOException {
        Map<Integer, Integer> ids = font instanceof EncodedSimple simple ? simple.glyphIds : ((EncodedCid) font).glyphIds;
        if (!Integer.valueOf(glyph.glyphId()).equals(ids.get(glyph.codePoint()))) { throw new IOException("CFF measured glyph mapping differs from embedded program"); }
    }
    private static final class EncodedSimple extends PDType1CFont {
        private final Map<Integer, Integer> mappings;
        private final Map<Integer, Integer> glyphIds;
        EncodedSimple(COSDictionary dictionary, Map<Integer, Integer> mappings, Map<Integer, Integer> glyphIds) throws IOException { super(dictionary); this.mappings = Map.copyOf(mappings); this.glyphIds = Map.copyOf(glyphIds); }
        @Override protected byte[] encode(int unicode) throws IOException { Integer code = mappings.get(unicode); if (code == null) { throw new IOException("CFF Unicode code is outside this encoding"); } return new byte[] {code.byteValue()}; }
    }
    private static final class EncodedCid extends PDType0Font {
        private final Map<Integer, Integer> mappings;
        private final Map<Integer, Integer> glyphIds;
        EncodedCid(COSDictionary dictionary, Map<Integer, Integer> mappings, Map<Integer, Integer> glyphIds) throws IOException { super(dictionary); this.mappings = Map.copyOf(mappings); this.glyphIds = Map.copyOf(glyphIds); }
        @Override protected byte[] encode(int unicode) throws IOException { Integer code = mappings.get(unicode); if (code == null) { throw new IOException("CFF Unicode code is outside this encoding"); } return new byte[] {code.byteValue()}; }
    }
}
