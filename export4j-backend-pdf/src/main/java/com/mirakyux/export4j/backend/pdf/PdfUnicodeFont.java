// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import org.apache.fontbox.util.BoundingBox;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;
import com.mirakyux.export4j.model.ExecutionContext;

/** Document-owned Unicode encodings over one original or finalized TrueType program.
 * Distinct input scalars receive distinct CIDs, including when the font shares their glyph.
 * The underlying Identity-H font remains unchanged for finalized-program cache snapshots.
 */
final class PdfUnicodeFont extends PDFont {
    static final int FIRST_CID = 1;
    static final int SURROGATE_START = 0xd800;
    static final int SURROGATE_END = 0xdfff;
    static final int LAST_CID = 0xffff;
    static final int MAX_CODES_PER_FONT = 63_487;
    private static final int MAX_CID_MAP_BYTES = 2 * (LAST_CID + 1);
    private static final HexFormat HEX = HexFormat.of();

    private final State state;
    private final TreeMap<Integer, Integer> unicodeByCid = new TreeMap<>();
    private int nextCid = FIRST_CID;

    private record Mapping(PdfUnicodeFont font, int cid, int glyphId) { }

    private static final class State {
        private final PDDocument document;
        private final PDFont original;
        private final int[] reservedCodePoints;
        private final TreeMap<Integer, Mapping> mappings = new TreeMap<>();
        private final List<PdfUnicodeFont> fonts = new ArrayList<>();
        private boolean finalized;

        private State(PDDocument document, PDFont original, int[] codePointOrder) {
            this.document = document;
            this.original = original;
            reservedCodePoints = codePointOrder.length <= MAX_CODES_PER_FONT ? codePointOrder.clone() : new int[0];
        }
    }

    /** The optional known order reserves CIDs without loading or trusting any glyph mapping. */
    PdfUnicodeFont(PDDocument document, PDFont original, int[] codePointOrder) {
        super(encodingDictionary(original));
        state = new State(document, original, codePointOrder);
        state.fonts.add(this);
        nextCid = cidAtOrdinal(state.reservedCodePoints.length);
    }

    private PdfUnicodeFont(State state) {
        super(encodingDictionary(state.original));
        this.state = state;
        state.fonts.add(this);
    }

    private static COSDictionary encodingDictionary(PDFont original) {
        var dictionary = new COSDictionary(original.getCOSObject());
        // The wrapper supplies its own mapping; avoid parsing the original full-font ToUnicode.
        dictionary.removeItem(COSName.TO_UNICODE);
        return dictionary;
    }

    int glyphId(int codePoint) throws IOException { return mapping(codePoint).glyphId(); }
    PdfUnicodeFont fontFor(int codePoint) throws IOException { return mapping(codePoint).font(); }
    float widthForGlyph(int glyphId) throws IOException { return state.original.getWidth(glyphId); }

    private Mapping mapping(int codePoint) throws IOException {
        ExecutionContext.checkpoint();
        var existing = state.mappings.get(codePoint);
        if (existing != null) { return existing; }
        if (state.finalized) { throw new IllegalStateException("the Unicode font is already finalized"); }
        if (!Character.isValidCodePoint(codePoint) || codePoint >= SURROGATE_START && codePoint <= SURROGATE_END) {
            throw new IllegalArgumentException("invalid Unicode scalar");
        }
        byte[] encoded = state.original.encode(Character.toString(codePoint));
        if (encoded.length != 2) { throw new IllegalArgumentException("the original font must encode a two-byte glyph ID"); }
        int glyphId = Byte.toUnsignedInt(encoded[0]) << 8 | Byte.toUnsignedInt(encoded[1]);
        int reserved = Arrays.binarySearch(state.reservedCodePoints, codePoint);
        PdfUnicodeFont font;
        int cid;
        if (reserved >= 0) {
            font = state.fonts.getFirst();
            cid = cidAtOrdinal(reserved);
        } else {
            font = state.fonts.getLast();
            if (font.nextCid > LAST_CID) { font = new PdfUnicodeFont(state); }
            cid = font.nextCid++;
            // Identity-H consumers may decode these bytes as UTF-16 before applying ToUnicode.
            if (font.nextCid == SURROGATE_START) { font.nextCid = SURROGATE_END + 1; }
        }
        var created = new Mapping(font, cid, glyphId);
        state.mappings.put(codePoint, created);
        font.unicodeByCid.put(cid, codePoint);
        return created;
    }

    private static int cidAtOrdinal(int ordinal) {
        int cid = FIRST_CID + ordinal;
        return cid >= SURROGATE_START ? cid + SURROGATE_END - SURROGATE_START + 1 : cid;
    }

    @Override protected byte[] encode(int unicode) throws IOException {
        var mapping = mapping(unicode);
        if (mapping.font() != this) { throw new IllegalArgumentException("the Unicode scalar belongs to another font resource"); }
        return new byte[] {(byte) (mapping.cid() >>> 8), (byte) mapping.cid()};
    }

    /** Finalize the shared program once, then give each encoding its own CID metadata. */
    void finish() throws IOException {
        if (state.finalized) { return; }
        ExecutionContext.checkpoint();
        if (state.original.willBeSubset()) {
            for (int codePoint : state.mappings.keySet()) {
                ExecutionContext.checkpoint();
                state.original.addToSubset(codePoint);
            }
            state.original.subset();
        }
        var descendants = state.original.getCOSObject().getCOSArray(COSName.DESCENDANT_FONTS);
        if (descendants == null || descendants.size() != 1 || !(descendants.getObject(0) instanceof COSDictionary source)) {
            throw new IOException("the original font must have one CID descendant");
        }
        byte[] originalCidMap = originalCidMap(source);
        for (var font : state.fonts) {
            ExecutionContext.checkpoint();
            font.finishEncoding(source, originalCidMap);
        }
        state.finalized = true;
    }

    private static byte[] originalCidMap(COSDictionary descendant) throws IOException {
        var mapping = descendant.getDictionaryObject(COSName.CID_TO_GID_MAP);
        if (mapping == null || COSName.IDENTITY.equals(mapping)) { return null; }
        if (!(mapping instanceof COSStream stream)) { throw new IOException("invalid original CID-to-glyph map"); }
        try (var input = stream.createInputStream(); var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16_384];
            for (;;) {
                ExecutionContext.checkpoint();
                int count = input.read(buffer, 0, Math.min(buffer.length, MAX_CID_MAP_BYTES + 1 - output.size()));
                if (count < 0) { break; }
                output.write(buffer, 0, count);
                if (output.size() > MAX_CID_MAP_BYTES) { throw new IOException("original CID-to-glyph map exceeds the CID range"); }
            }
            byte[] result = output.toByteArray();
            if ((result.length & 1) != 0) { throw new IOException("original CID-to-glyph map has an incomplete entry"); }
            return result;
        }
    }

    private void finishEncoding(COSDictionary source, byte[] originalCidMap) throws IOException {
        dict.clear();
        dict.addAll(state.original.getCOSObject());
        var descendant = new COSDictionary(source);
        var sourceDescriptor = source.getCOSDictionary(COSName.FONT_DESC);
        if (sourceDescriptor == null) { throw new IOException("the original font has no descriptor"); }
        // Each CIDSet describes this encoding; only the finalized font program is shared.
        var descriptor = new COSDictionary(sourceDescriptor);
        descendant.setItem(COSName.FONT_DESC, descriptor);
        var descendants = new COSArray();
        descendants.add(descendant);
        dict.setItem(COSName.DESCENDANT_FONTS, descendants);
        int lastCid = unicodeByCid.isEmpty() ? 0 : unicodeByCid.lastKey();
        byte[] cidMap = new byte[2 * (lastCid + 1)];
        byte[] cidSet = new byte[lastCid / 8 + 1];
        cidSet[0] = (byte) 0x80; // CID 0 retains .notdef.
        var widths = new COSArray();
        COSArray consecutiveWidths = null;
        int previousCid = -2;
        for (var entry : unicodeByCid.entrySet()) {
            ExecutionContext.checkpoint();
            int cid = entry.getKey();
            int originalGid = state.mappings.get(entry.getValue()).glyphId();
            int gid;
            if (originalCidMap == null) { gid = originalGid; }
            else {
                int offset = 2 * originalGid;
                if (offset + 1 >= originalCidMap.length) { throw new IOException("original glyph is absent from the finalized CID map"); }
                gid = Byte.toUnsignedInt(originalCidMap[offset]) << 8 | Byte.toUnsignedInt(originalCidMap[offset + 1]);
            }
            cidMap[2 * cid] = (byte) (gid >>> 8);
            cidMap[2 * cid + 1] = (byte) gid;
            cidSet[cid / 8] |= (byte) (0x80 >>> (cid & 7));
            float width = widthForGlyph(originalGid);
            if (!Float.isFinite(width)) { throw new IOException("original glyph width must be finite"); }
            if (cid != previousCid + 1) {
                widths.add(COSInteger.get(cid));
                consecutiveWidths = new COSArray();
                widths.add(consecutiveWidths);
            }
            consecutiveWidths.add(new COSFloat(width));
            previousCid = cid;
        }
        descendant.setItem(COSName.W, widths);
        descendant.setItem(COSName.CID_TO_GID_MAP, stream(cidMap));
        descriptor.setItem(COSName.CID_SET, stream(cidSet));
        dict.setItem(COSName.TO_UNICODE, toUnicode());
    }

    private PDStream stream(byte[] bytes) throws IOException {
        var result = new PDStream(state.document);
        try (var output = result.createOutputStream(COSName.FLATE_DECODE)) {
            for (int offset = 0; offset < bytes.length; offset += 16_384) {
                ExecutionContext.checkpoint();
                output.write(bytes, offset, Math.min(16_384, bytes.length - offset));
            }
        }
        return result;
    }

    private PDStream toUnicode() throws IOException {
        var result = new PDStream(state.document);
        try (var output = new OutputStreamWriter(result.createOutputStream(COSName.FLATE_DECODE), StandardCharsets.US_ASCII)) {
            output.write("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
                    + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                    + "/CMapName /Export4j-Unicode def\n/CMapType 2 def\n"
                    + "1 begincodespacerange\n<0000> <ffff>\nendcodespacerange\n");
            var entries = unicodeByCid.entrySet().iterator();
            for (int remaining = unicodeByCid.size(); remaining > 0;) {
                ExecutionContext.checkpoint();
                int count = Math.min(100, remaining);
                output.write(count + " beginbfchar\n");
                for (int index = 0; index < count; index++) {
                    ExecutionContext.checkpoint();
                    var entry = entries.next();
                    int codePoint = entry.getValue();
                    String unicode = codePoint <= 0xffff ? HEX.toHexDigits((short) codePoint)
                            : HEX.toHexDigits((short) Character.highSurrogate(codePoint))
                                    + HEX.toHexDigits((short) Character.lowSurrogate(codePoint));
                    output.write("<" + HEX.toHexDigits(entry.getKey().shortValue()) + "> <" + unicode + ">\n");
                }
                output.write("endbfchar\n");
                remaining -= count;
            }
            output.write("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        }
        return result;
    }

    private int originalGlyph(int cid) {
        Integer unicode = unicodeByCid.get(cid);
        if (unicode == null) { return 0; }
        return state.mappings.get(unicode).glyphId();
    }

    @Override public float getWidth(int code) throws IOException { return widthForGlyph(originalGlyph(code)); }
    @Override public String getName() { return dict.getNameAsString(COSName.BASE_FONT); }
    @Override public boolean isEmbedded() { return state.original.isEmbedded(); }
    @Override public boolean isVertical() { return false; }
    @Override public boolean willBeSubset() { return false; }
    @Override public void addToSubset(int codePoint) { throw new IllegalStateException("Unicode fonts are finalized explicitly"); }
    @Override public void subset() { throw new IllegalStateException("Unicode fonts are finalized explicitly"); }
    @Override protected float getStandard14Width(int code) { throw new UnsupportedOperationException("not a standard font"); }
    @Override public PDFontDescriptor getFontDescriptor() { return state.original.getFontDescriptor(); }
    @Override public Matrix getFontMatrix() { return state.original.getFontMatrix(); }
    @Override public BoundingBox getBoundingBox() throws IOException { return state.original.getBoundingBox(); }
    @Override @Deprecated public float getHeight(int code) throws IOException { return state.original.getHeight(originalGlyph(code)); }
    @Override public boolean hasExplicitWidth(int code) throws IOException { return state.original.hasExplicitWidth(originalGlyph(code)); }
    @Override public float getWidthFromFont(int code) throws IOException { return state.original.getWidthFromFont(originalGlyph(code)); }
    @Override public float getAverageFontWidth() { return state.original.getAverageFontWidth(); }
    @Override public Vector getPositionVector(int code) { return state.original.getPositionVector(originalGlyph(code)); }
    @Override public boolean isDamaged() { return state.original.isDamaged(); }
    @Override public String toUnicode(int code) {
        Integer unicode = unicodeByCid.get(code);
        return unicode == null ? null : Character.toString(unicode);
    }
    @Override public int readCode(InputStream input) throws IOException {
        int high = input.read();
        if (high < 0) { return -1; }
        int low = input.read();
        if (low < 0) { throw new IOException("incomplete two-byte CID"); }
        return high << 8 | low;
    }
}
