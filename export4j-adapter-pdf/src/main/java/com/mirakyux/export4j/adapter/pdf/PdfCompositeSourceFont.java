// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fontbox.cmap.CMap;
import org.apache.fontbox.cmap.CMapParser;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;

/** Builds explicit CID-to-glyph bindings without ever constructing the unembedded source font. */
final class PdfCompositeSourceFont {
    private PdfCompositeSourceFont() { }
    private record Binding(int cid, String unicode, int gid) { }

    record Analysis(COSDictionary descendant, byte[] glyphs, Set<Integer> codes) {
        Analysis { glyphs = glyphs.clone(); codes = Set.copyOf(codes); }
        void apply(PDDocument document, COSDictionary source, COSDictionary program) throws IOException {
            var children = (COSArray) program.getDictionaryObject(COSName.DESCENDANT_FONTS);
            var embedded = (COSDictionary) children.getObject(0);
            var replacement = new COSDictionary(descendant);
            replacement.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE2);
            replacement.setItem(COSName.FONT_DESC, embedded.getItem(COSName.FONT_DESC));
            var map = new PDStream(document);
            try (var output = map.createOutputStream(COSName.FLATE_DECODE)) { output.write(glyphs); }
            replacement.setItem(COSName.CID_TO_GID_MAP, map);
            var privateChildren = new COSArray(); privateChildren.add(replacement);
            source.setItem(COSName.DESCENDANT_FONTS, privateChildren);
            // Keep the original code-to-CID Encoding, Unicode map, CID system and all source widths.
            // Only the absent program and its explicit CID-to-glyph binding are supplied.
            // A descendant may be shared by roots with different code/Unicode evidence. Never mutate it.
        }
    }

    static Analysis analyse(COSDictionary source, PdfFont replacement, PdfLimits limits, Work work) throws IOException {
        if (!(source.getDictionaryObject(COSName.DESCENDANT_FONTS) instanceof COSArray children) || children.size() != 1
                || !(children.getObject(0) instanceof COSDictionary child)) { throw PdfObjects.invalid(); }
        String kind = child.getNameAsString(COSName.SUBTYPE, "");
        if (!Set.of("CIDFontType0", "CIDFontType2").contains(kind)) { return null; }
        var descriptor = child.getDictionaryObject(COSName.FONT_DESC);
        if (!(descriptor instanceof COSDictionary details)) { throw PdfObjects.invalid(); }
        for (var key : List.of(COSName.FONT_FILE, COSName.FONT_FILE2, COSName.FONT_FILE3)) {
            if (details.containsKey(key)) {
                if (!(details.getDictionaryObject(key) instanceof COSStream)) { throw PdfObjects.invalid(); }
                return null; // A mapping is never an embedded-font repair mechanism.
            }
        }
        if (!(child.getDictionaryObject(COSName.CIDSYSTEMINFO) instanceof COSDictionary system)
                || !(system.getDictionaryObject(COSName.REGISTRY) instanceof COSString)
                || !(system.getDictionaryObject(COSName.ORDERING) instanceof COSString)
                || !(system.getDictionaryObject(COSName.SUPPLEMENT) instanceof COSInteger supplement) || supplement.longValue() < 0) { throw PdfObjects.invalid(); }
        widths(child, work);
        if (child.containsKey(COSName.W2) || child.containsKey(COSName.DW2)) { return null; }
        var unicodeValue = source.getDictionaryObject(COSName.TO_UNICODE);
        if (unicodeValue == null) { return null; }
        if (!(unicodeValue instanceof COSStream unicodeStream)) { throw PdfObjects.invalid(); }
        Set<Integer> unicodeLengths = validateMap(unicodeStream, true, work);
        CMap unicode = parse(unicodeStream);
        var encodingValue = source.getDictionaryObject(COSName.ENCODING);
        CMap encoding = null;
        Set<Integer> encodingLengths;
        if (encodingValue instanceof COSName name) {
            if (!name.getName().equals("Identity-H")) { return null; }
            encodingLengths = Set.of(2);
        } else if (encodingValue instanceof COSStream stream) {
            encodingLengths = validateMap(stream, false, work); encoding = parse(stream);
            if (encoding.getWMode() != 0) { return null; }
        } else if (encodingValue == null) { throw PdfObjects.invalid(); }
        else { throw PdfObjects.invalid(); }
        if (unicodeLengths.isEmpty() || encodingLengths.isEmpty()) { throw PdfObjects.invalid(); }
        var codes = new HashSet<Integer>(); var gids = new HashMap<Integer, Integer>(); var bindings = new HashMap<Integer, Binding>();
        try (var input = new RandomAccessReadBuffer(replacement.bytes()); var font = new TTFParser().parse(input)) {
            var cmap = font.getUnicodeCmapLookup();
            for (int length : encodingLengths) {
                if (!unicodeLengths.contains(length)) { continue; }
                int maximum = length == 1 ? 255 : 65_535;
                for (int code = 0; code <= maximum; code++) {
                    work.step(1);
                    String text = unicode.toUnicode(code, length);
                    if (text == null) { continue; }
                    if (!singleCodePoint(text)) { return null; }
                    int cid = encoding == null ? code : encoding.toCID(code, length);
                    if (cid < 0 || cid > 65_535) { throw PdfObjects.invalid(); }
                    int gid = cmap.getGlyphId(text.codePointAt(0));
                    if (gid < 0 || gid >= font.getNumberOfGlyphs() || gid > 65_535) { throw PdfObjects.invalid(); }
                    var binding = new Binding(cid, text, gid); var previousCode = bindings.putIfAbsent(code, binding);
                    // The renderer exposes a decoded code as an int. Different byte lengths with
                    // the same value must agree, or that downstream representation loses evidence.
                    if (previousCode != null && !previousCode.equals(binding)) { return null; }
                    Integer previous = gids.putIfAbsent(cid, gid);
                    if (previous != null && previous != gid) { return null; }
                    if (gid != 0 && cid != 0) { codes.add(code); }
                }
            }
        }
        int maxCid = gids.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        int size = (maxCid + 1) * 2;
        if (size > limits.maxDecodedStreamBytes()) { throw new PdfOperationException(PdfOperationException.Code.DECODED_STREAM_LIMIT, 0, -1); }
        byte[] map = new byte[size];
        for (var entry : gids.entrySet()) {
            int offset = entry.getKey() * 2; map[offset] = (byte) (entry.getValue() >>> 8); map[offset + 1] = (byte) (int) entry.getValue();
        }
        return new Analysis(child, map, codes);
    }

    private static boolean singleCodePoint(String text) {
        if (text.codePointCount(0, text.length()) != 1) { return false; }
        int code = text.codePointAt(0); return code > 0 && !(code >= 0xD800 && code <= 0xDFFF) && code != 0xFFFE && code != 0xFFFF;
    }
    private static CMap parse(COSStream source) throws IOException {
        try (var input = source.createView()) { return new CMapParser(true).parse(input); }
        catch (IOException failure) { throw PdfObjects.invalid(); }
    }
    private static void widths(COSDictionary child, Work work) throws IOException {
        var defaultWidth = child.getDictionaryObject(COSName.DW);
        if (defaultWidth != null) { width(defaultWidth); }
        var values = child.getDictionaryObject(COSName.W);
        if (values == null) { return; }
        if (!(values instanceof COSArray array)) { throw PdfObjects.invalid(); }
        var assigned = new HashSet<Integer>();
        for (int i = 0; i < array.size();) {
            int first = cid(array.getObject(i++));
            if (i >= array.size()) { throw PdfObjects.invalid(); }
            var next = array.getObject(i++);
            if (next instanceof COSArray widths) {
                if (widths.size() == 0 || widths.size() > 65_536 - first) { throw PdfObjects.invalid(); }
                for (int j = 0; j < widths.size(); j++) { width(widths.getObject(j)); if (!assigned.add(first + j)) { throw PdfObjects.invalid(); } }
                work.step(widths.size());
            } else {
                int last = cid(next); if (last < first || i >= array.size()) { throw PdfObjects.invalid(); } width(array.getObject(i++));
                for (int j = first; j <= last; j++) { if (!assigned.add(j)) { throw PdfObjects.invalid(); } }
                work.step(last - first + 1L);
            }
            ExecutionContext.checkpoint();
        }
    }
    private static void width(COSBase value) throws IOException {
        if (!(value instanceof COSNumber number) || !Float.isFinite(number.floatValue()) || number.floatValue() < 0) { throw PdfObjects.invalid(); }
    }
    private static int cid(COSBase value) throws IOException {
        if (!(value instanceof COSInteger number) || number.longValue() < 0 || number.longValue() > 65_535) { throw PdfObjects.invalid(); }
        return number.intValue();
    }
    static final class Work {
        private final PdfLimits limits; private long count;
        Work(PdfLimits limits) { this.limits = limits; }
        void step(long value) throws IOException {
            ExecutionContext.checkpoint();
            if (value > limits.maxContentOperators() - count) { throw new PdfOperationException(PdfOperationException.Code.OPERATOR_LIMIT, 0, -1); } count += value;
        }
    }
    /** Rejects oversized or executable CMap constructs before the library expands ranges. */
    private static Set<Integer> validateMap(COSStream stream, boolean unicode, Work work) throws IOException {
        var lengths = new HashSet<Integer>(); var operands = new ArrayList<COSBase>();
        var spaces = Map.of(1, new BitSet(256), 2, new BitSet(65_536));
        var mappedCodes = Map.of(1, new BitSet(256), 2, new BitSet(65_536));
        String block = null; int expected = 0;
        var parser = new PDFStreamParser(new PdfContentStream(stream));
        try {
            Object token;
            while ((token = parser.parseNextToken()) != null) {
                work.step(1);
                if (token instanceof Operator operation) {
                    String name = operation.getName();
                    if (name.equals("usecmap")) { throw PdfObjects.unsupported(); }
                    if (name.startsWith("begin") && Set.of("begincodespacerange", "beginbfchar", "beginbfrange", "begincidchar", "begincidrange").contains(name)) {
                        if (block != null || operands.size() != 1 || !(operands.getFirst() instanceof COSInteger count)
                                || count.longValue() < 0 || count.longValue() > 65_536) { throw PdfObjects.invalid(); }
                        block = name.substring(5); expected = count.intValue(); operands.clear(); continue;
                    }
                    if (block != null) {
                        if (!name.equals("end" + block)) { throw PdfObjects.invalid(); }
                        int stride = block.endsWith("range") ? 3 : 2;
                        if (block.equals("codespacerange")) { stride = 2; }
                        if (operands.size() != (long) expected * stride) { throw PdfObjects.invalid(); }
                        if (unicode && block.startsWith("cid") || !unicode && block.startsWith("bf")) { throw PdfObjects.invalid(); }
                        for (int i = 0; i < operands.size(); i += stride) {
                            byte[] start = code(operands.get(i));
                            if (block.equals("codespacerange")) {
                                byte[] end = code(operands.get(i + 1)); if (end.length != start.length || number(end) < number(start)) { throw PdfObjects.invalid(); }
                                int first = number(start), last = number(end); work.step(last - first + 1L);
                                var declared = spaces.get(start.length); int overlap = declared.nextSetBit(first);
                                if (overlap >= 0 && overlap <= last) { throw PdfObjects.invalid(); }
                                declared.set(first, last + 1);
                                lengths.add(start.length);
                            } else if (block.endsWith("range")) {
                                byte[] end = code(operands.get(i + 1));
                                if (end.length != start.length || number(end) < number(start)) { throw PdfObjects.invalid(); }
                                int size = number(end) - number(start) + 1; work.step(size);
                                mappedCodes.get(start.length).set(number(start), number(end) + 1);
                                var target = operands.get(i + 2);
                                if (unicode) {
                                    if (target instanceof COSArray array) {
                                        if (array.size() != size) { throw PdfObjects.invalid(); } for (var value : array) { unicode(value); }
                                    } else { unicode(target); }
                                } else if (cid(target) > 65_536 - size) { throw PdfObjects.invalid(); }
                            } else {
                                mappedCodes.get(start.length).set(number(start));
                                if (unicode) { unicode(operands.get(i + 1)); }
                                else { cid(operands.get(i + 1)); }
                            }
                        }
                        block = null;
                    }
                    operands.clear();
                } else if (token instanceof COSBase value) { operands.add(value); }
                else { throw PdfObjects.invalid(); }
            }
        } finally { parser.close(); }
        if (block != null) { throw PdfObjects.invalid(); }
        for (int length : List.of(1, 2)) {
            var outside = (BitSet) mappedCodes.get(length).clone(); outside.andNot(spaces.get(length));
            if (!outside.isEmpty()) { throw PdfObjects.invalid(); }
        }
        var shorter = spaces.get(1); var longer = spaces.get(2);
        for (int prefix = shorter.nextSetBit(0); prefix >= 0; prefix = shorter.nextSetBit(prefix + 1)) {
            work.step(1); int first = prefix << 8, next = longer.nextSetBit(first);
            if (next >= 0 && next <= (first | 255)) { throw PdfObjects.invalid(); }
        }
        return lengths;
    }
    private static byte[] code(COSBase value) throws IOException {
        if (!(value instanceof COSString string) || string.getBytes().length < 1 || string.getBytes().length > 2) { throw PdfObjects.unsupported(); }
        return string.getBytes();
    }
    private static int number(byte[] bytes) { return bytes.length == 1 ? bytes[0] & 255 : (bytes[0] & 255) * 256 + (bytes[1] & 255); }
    private static void unicode(COSBase value) throws IOException {
        if (!(value instanceof COSString string) || string.getBytes().length == 0 || (string.getBytes().length & 1) != 0 || string.getBytes().length > 8) { throw PdfObjects.invalid(); }
    }
}
