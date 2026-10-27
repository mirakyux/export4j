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
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

/** Validates embedded static font programs before PDFBox can request a substitute system font. */
final class PdfRasterResources {
    private enum FontKind { TRUE_TYPE, CFF_SIMPLE, CFF_CID }
    private final Set<COSDictionary> resources = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<COSStream, FontKind> fonts = new IdentityHashMap<>();
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
        else if ("Type1".equals(subtype)) { kind = FontKind.CFF_SIMPLE; }
        else { return "RENDER_FONT"; }
        var descriptor = font.getCOSDictionary(COSName.FONT_DESC);
        var key = kind == FontKind.TRUE_TYPE ? COSName.FONT_FILE2 : COSName.FONT_FILE3;
        if (descriptor == null || !(descriptor.getDictionaryObject(key) instanceof COSStream bytes)) { return "RENDER_FONT"; }
        if (descriptor.containsKey(COSName.FONT_FILE) || descriptor.containsKey(kind == FontKind.TRUE_TYPE ? COSName.FONT_FILE3 : COSName.FONT_FILE2)) { throw PdfObjects.invalid(); }
        String wrapper = bytes.getNameAsString(COSName.SUBTYPE, "");
        if (kind != FontKind.TRUE_TYPE && !wrapper.equals("OpenType")
                && !wrapper.equals(kind == FontKind.CFF_SIMPLE ? "Type1C" : "CIDFontType0C")) { return "RENDER_FONT"; }
        var previous = fonts.get(bytes);
        if (previous != null) { if (previous != kind) { throw PdfObjects.invalid(); } return null; }
        if (kind == FontKind.TRUE_TYPE) {
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
    private static void cff(CFFFont program, FontKind kind) throws IOException {
        if (program == null || program.getNumCharStrings() <= 0
                || !(kind == FontKind.CFF_SIMPLE ? program instanceof CFFType1Font : program instanceof CFFCIDFont)) { throw PdfObjects.invalid(); }
    }
    static void requireGlyph(PDFont font, int code) throws IOException {
        ExecutionContext.checkpoint();
        if (!font.isEmbedded() || font.isDamaged() || font instanceof PDVectorFont vector && !vector.hasGlyph(code)) { throw PdfObjects.unsupported(); }
    }
}
