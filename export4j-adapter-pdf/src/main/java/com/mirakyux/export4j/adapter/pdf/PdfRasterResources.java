// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.fontbox.cff.CFFCIDFont;
import org.apache.fontbox.cff.CFFFont;
import org.apache.fontbox.cff.CFFParser;
import org.apache.fontbox.cff.CFFType1Font;
import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.type1.Type1Font;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

/** Validates embedded static font programs before PDFBox can request a substitute system font. */
final class PdfRasterResources {
    private enum FontKind { TRUE_TYPE, CFF_SIMPLE, CFF_CID, TYPE1 }
    private final PdfLimits limits;
    private final PdfType1ProgramGuard type1Guard;
    private final PdfSemanticGraphicsState graphicsStates;
    private final Set<COSDictionary> resources = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<COSStream, FontKind> fonts = new IdentityHashMap<>();
    PdfRasterResources() { this(PdfLimits.DEFAULT); }
    PdfRasterResources(PdfLimits limits) { this.limits = limits; type1Guard = new PdfType1ProgramGuard(limits); graphicsStates = new PdfSemanticGraphicsState(limits); }
    String check(PDPage page) throws IOException {
        String issue = check(page.getResources()); if (issue != null) { return issue; }
        for (var annotation : page.getAnnotations()) {
            var appearance = annotation.getNormalAppearanceStream();
            if (appearance == null) {
                if (annotation instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink) { continue; }
                return "FORM_APPEARANCE";
            }
            issue = check(appearance.getResources()); if (issue != null) { return issue; }
        }
        return null;
    }
    private String check(PDResources value) throws IOException {
        ExecutionContext.checkpoint();
        if (value == null || !resources.add(value.getCOSObject())) { return null; }
        var fontGroup = value.getCOSObject().getCOSDictionary(COSName.FONT);
        if (fontGroup != null) {
            for (var key : fontGroup.keySet()) {
                if (!(fontGroup.getDictionaryObject(key) instanceof COSDictionary font)) { throw PdfObjects.invalid(); }
                String issue = font(font); if (issue != null) { return issue; }
            }
        }
        for (var name : value.getXObjectNames()) {
            if (value.getXObject(name) instanceof PDFormXObject form) { String issue = check(form.getResources()); if (issue != null) { return issue; } }
        }
        // Tiling paint uses its own drawer; its repeated work is outside this request's checked drawer.
        if (value.getPatternNames().iterator().hasNext()) { return "RENDER_PATTERN"; }
        for (var name : value.getExtGStateNames()) {
            if (!graphicsStates.renderable(value, name)) { return "RENDER_GRAPHICS_STATE"; }
            var state = value.getExtGState(name);
            if (state.getCOSObject().containsKey(COSName.FONT)) {
                if (!(state.getCOSObject().getDictionaryObject(COSName.FONT) instanceof COSArray setting) || setting.size() != 2
                        || !(setting.getObject(0) instanceof COSDictionary font)) { throw PdfObjects.invalid(); }
                String issue = font(font); if (issue != null) { return issue; }
            }
            if (state.getSoftMask() != null && state.getSoftMask().getGroup() != null) {
                String issue = check(state.getSoftMask().getGroup().getResources()); if (issue != null) { return issue; }
            }
        }
        return null;
    }
    private String font(COSDictionary font) throws IOException {
        String subtype = font.getNameAsString(COSName.SUBTYPE);
        if ("Type3".equals(subtype)) {
            validateType3Declaration(font, limits);
            var local = font.getCOSDictionary(COSName.RESOURCES);
            return local == null ? null : check(new PDResources(local));
        }
        FontKind kind;
        if ("Type0".equals(subtype)) {
            if (!(font.getDictionaryObject(COSName.DESCENDANT_FONTS) instanceof COSArray children) || children.size() != 1
                    || !(children.getObject(0) instanceof COSDictionary child)) { throw PdfObjects.invalid(); }
            kind = switch (child.getNameAsString(COSName.SUBTYPE, "")) {
                case "CIDFontType2" -> FontKind.TRUE_TYPE;
                case "CIDFontType0" -> FontKind.CFF_CID;
                default -> null;
            };
            if (kind == null) { return "RENDER_FONT"; }
            font = child;
        } else if ("TrueType".equals(subtype)) { kind = FontKind.TRUE_TYPE; }
        else if ("Type1".equals(subtype)) {
            var descriptor = font.getCOSDictionary(COSName.FONT_DESC);
            kind = descriptor != null && descriptor.containsKey(COSName.FONT_FILE) ? FontKind.TYPE1 : FontKind.CFF_SIMPLE;
        }
        else { return "RENDER_FONT"; }
        var descriptor = font.getCOSDictionary(COSName.FONT_DESC);
        var key = kind == FontKind.TYPE1 ? COSName.FONT_FILE : kind == FontKind.TRUE_TYPE ? COSName.FONT_FILE2 : COSName.FONT_FILE3;
        if (descriptor == null || !(descriptor.getDictionaryObject(key) instanceof COSStream bytes)) { return "RENDER_FONT"; }
        for (var other : java.util.List.of(COSName.FONT_FILE, COSName.FONT_FILE2, COSName.FONT_FILE3)) {
            if (!other.equals(key) && descriptor.containsKey(other)) { throw PdfObjects.invalid(); }
        }
        String wrapper = bytes.getNameAsString(COSName.SUBTYPE, "");
        if (kind != FontKind.TRUE_TYPE && kind != FontKind.TYPE1 && !wrapper.equals("OpenType")
                && !wrapper.equals(kind == FontKind.CFF_SIMPLE ? "Type1C" : "CIDFontType0C")) { return "RENDER_FONT"; }
        var previous = fonts.get(bytes);
        if (previous != null) { if (previous != kind) { throw PdfObjects.invalid(); } return null; }
        if (kind == FontKind.TYPE1) {
            type1(bytes);
        } else if (kind == FontKind.TRUE_TYPE) {
            try (var stream = bytes.createInputStream(); var program = new TTFParser().parseEmbedded(stream)) {
                if (program.getNumberOfGlyphs() <= 0 || program.getTableMap().containsKey("fvar")) { throw PdfObjects.invalid(); }
            }
        } else {
            // All streams were bounded by PdfPreflight before resource traversal. Parse the exact
            // declared container and font kind, rather than allowing PDFBox to repair a mismatch.
            try (var source = bytes.createView()) {
                if (wrapper.equals("OpenType")) {
                    try (var program = new OTFParser(true).parse(source)) {
                        if (!program.isPostScript() || program.getNumberOfGlyphs() <= 0 || !program.getTableMap().containsKey("CFF ")
                                || program.getTableMap().containsKey("fvar") || program.getTableMap().containsKey("CFF2")) { throw PdfObjects.invalid(); }
                        cff(program.getCFF().getFont(), kind);
                    }
                } else {
                    if (source.read() != 1) { throw PdfObjects.invalid(); }
                    source.rewind(1);
                    var programs = new CFFParser().parse(source);
                    if (programs.size() != 1) { throw PdfObjects.invalid(); }
                    cff(programs.getFirst(), kind);
                }
            }
        }
        ExecutionContext.checkpoint();
        fonts.put(bytes, kind);
        return null;
    }
    private void type1(COSStream source) throws IOException {
        int clear = segmentLength(source, COSName.LENGTH1); int encrypted = segmentLength(source, COSName.LENGTH2);
        int trailer = source.containsKey(COSName.LENGTH3) ? segmentLength(source, COSName.LENGTH3) : 0;
        long count = (long) clear + encrypted + trailer;
        if (count > limits.maxDecodedStreamBytes() || count > Integer.MAX_VALUE - 8) { throw new PdfOperationException(PdfOperationException.Code.DECODED_STREAM_LIMIT, 0, -1); }
        try (var input = source.createInputStream()) {
            byte[] ascii = input.readNBytes(clear); byte[] binary = input.readNBytes(encrypted);
            if (ascii.length != clear || binary.length != encrypted || input.readNBytes(trailer).length != trailer || input.read() != -1) { throw PdfObjects.invalid(); }
            var program = Type1Font.createWithSegments(ascii, binary);
            if (program.getFontType() != 1 || program.getCharStringsDict().isEmpty() || !program.hasGlyph(".notdef")) { throw PdfObjects.invalid(); }
            if ((long) program.getCharStringsDict().size() + program.getSubrsArray().size() > limits.maxObjects()) { throw new PdfOperationException(PdfOperationException.Code.OBJECT_LIMIT, 0, -1); }
            type1Guard.validate(program);
            for (String glyph : program.getCharStringsDict().keySet()) { ExecutionContext.checkpoint(); program.getType1CharString(glyph).getPath(); }
        } catch (PdfOperationException failure) { throw failure; }
        catch (IOException | RuntimeException failure) { throw PdfObjects.invalid(); }
    }
    private static int segmentLength(COSStream source, COSName key) throws IOException {
        if (!(source.getDictionaryObject(key) instanceof COSInteger length) || length.longValue() < 0 || length.longValue() > Integer.MAX_VALUE - 8) { throw PdfObjects.invalid(); }
        return length.intValue();
    }
    /** Raw validation runs before constructing a Type3 PDFont and is shared by every input path. */
    static void validateType3Declaration(COSDictionary font, PdfLimits limits) throws IOException {
        if (!(font.getDictionaryObject(COSName.FIRST_CHAR) instanceof COSInteger first)
                || !(font.getDictionaryObject(COSName.LAST_CHAR) instanceof COSInteger last)
                || first.longValue() < 0 || last.longValue() > 255 || first.longValue() > last.longValue()
                || !(font.getDictionaryObject(COSName.WIDTHS) instanceof COSArray widths)
                || widths.size() != last.longValue() - first.longValue() + 1) { throw PdfObjects.invalid(); }
        for (int index = 0; index < widths.size(); index++) { finite(widths.getObject(index)); }
        if (!(font.getDictionaryObject(COSName.FONT_MATRIX) instanceof COSArray matrix) || matrix.size() != 6) { throw PdfObjects.invalid(); }
        for (int index = 0; index < matrix.size(); index++) { finite(matrix.getObject(index)); }
        double a = ((COSNumber) matrix.getObject(0)).floatValue(); double b = ((COSNumber) matrix.getObject(1)).floatValue();
        double c = ((COSNumber) matrix.getObject(2)).floatValue(); double d = ((COSNumber) matrix.getObject(3)).floatValue();
        if (a * d - b * c == 0 || !Double.isFinite(a * d - b * c)) { throw PdfObjects.invalid(); }
        if (!(font.getDictionaryObject(COSName.FONT_BBOX) instanceof COSArray box) || box.size() != 4) { throw PdfObjects.invalid(); }
        for (int index = 0; index < box.size(); index++) { finite(box.getObject(index)); }
        type3Encoding(font.getDictionaryObject(COSName.ENCODING));
        if (!(font.getDictionaryObject(COSName.CHAR_PROCS) instanceof COSDictionary programs) || programs instanceof COSStream || programs.size() == 0) { throw PdfObjects.invalid(); }
        if (programs.size() > limits.maxObjects()) { throw new PdfOperationException(PdfOperationException.Code.OBJECT_LIMIT, 0, -1); }
        for (var entry : programs.entrySet()) { ExecutionContext.checkpoint(); if (!(entry.getValue() instanceof COSStream) && !(programs.getDictionaryObject(entry.getKey()) instanceof COSStream)) { throw PdfObjects.invalid(); } }
        if (font.containsKey(COSName.RESOURCES) && (!(font.getDictionaryObject(COSName.RESOURCES) instanceof COSDictionary local) || local instanceof COSStream)) { throw PdfObjects.invalid(); }
    }
    private static void type3Encoding(COSBase value) throws IOException {
        var names = Set.of("StandardEncoding", "WinAnsiEncoding", "MacRomanEncoding", "MacExpertEncoding");
        if (value instanceof COSName name) { if (!names.contains(name.getName())) { throw PdfObjects.unsupported(); } return; }
        if (!(value instanceof COSDictionary dictionary) || value instanceof COSStream) { throw PdfObjects.invalid(); }
        for (var key : dictionary.keySet()) { if (!Set.of(COSName.TYPE, COSName.BASE_ENCODING, COSName.DIFFERENCES).contains(key)) { throw PdfObjects.unsupported(); } }
        if (dictionary.containsKey(COSName.TYPE) && !COSName.ENCODING.equals(dictionary.getDictionaryObject(COSName.TYPE))) { throw PdfObjects.invalid(); }
        var base = dictionary.getDictionaryObject(COSName.BASE_ENCODING);
        if (base != null && !(base instanceof COSName)) { throw PdfObjects.invalid(); }
        if (base instanceof COSName name && !names.contains(name.getName())) { throw PdfObjects.unsupported(); }
        var differences = dictionary.getDictionaryObject(COSName.DIFFERENCES);
        if (differences == null) { return; }
        if (!(differences instanceof COSArray array)) { throw PdfObjects.invalid(); }
        int code = -1;
        for (int index = 0; index < array.size(); index++) {
            ExecutionContext.checkpoint(); var entry = array.getObject(index);
            if (entry instanceof COSInteger number) {
                if (number.longValue() < 0 || number.longValue() > 255) { throw PdfObjects.invalid(); } code = number.intValue();
            } else if (entry instanceof COSName) { if (code < 0 || code > 255) { throw PdfObjects.invalid(); } code++; }
            else { throw PdfObjects.invalid(); }
        }
    }
    private static void finite(org.apache.pdfbox.cos.COSBase value) throws IOException {
        if (!(value instanceof COSNumber number) || !Float.isFinite(number.floatValue())) { throw PdfObjects.invalid(); }
    }
    private static void cff(CFFFont program, FontKind kind) throws IOException {
        if (program == null || program.getNumCharStrings() <= 0
                || !(kind == FontKind.CFF_SIMPLE ? program instanceof CFFType1Font : program instanceof CFFCIDFont)) { throw PdfObjects.invalid(); }
    }
    static void requireGlyph(PDFont font, int code) throws IOException {
        ExecutionContext.checkpoint();
        if (font instanceof PDType3Font type3) {
            String glyph = type3.getEncoding().getName(code);
            if (code < type3.getCOSObject().getInt(COSName.FIRST_CHAR) || code > type3.getCOSObject().getInt(COSName.LAST_CHAR)
                    || glyph == null || glyph.equals(".notdef") || type3.getCharProcs() == null
                    || !(type3.getCharProcs().getDictionaryObject(COSName.getPDFName(glyph)) instanceof COSStream)
                    || type3.getCharProc(code) == null || type3.isDamaged()) { throw PdfObjects.invalid(); }
            return;
        }
        if (!font.isEmbedded() || font.isDamaged() || font instanceof PDVectorFont vector && !vector.hasGlyph(code)) { throw PdfObjects.unsupported(); }
    }
}
