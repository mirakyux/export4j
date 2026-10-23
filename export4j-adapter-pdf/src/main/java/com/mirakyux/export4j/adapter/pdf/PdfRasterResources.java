// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

/** Validates embedded TrueType programs before PDFBox can request a substitute system font. */
final class PdfRasterResources {
    private final Set<COSDictionary> resources = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<COSStream> fonts = Collections.newSetFromMap(new IdentityHashMap<>());
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
        if ("Type0".equals(subtype)) {
            if (!(font.getDictionaryObject(COSName.DESCENDANT_FONTS) instanceof COSArray children) || children.size() != 1
                    || !(children.getObject(0) instanceof COSDictionary child)) { throw PdfObjects.invalid(); }
            if (!"CIDFontType2".equals(child.getNameAsString(COSName.SUBTYPE))) { return "RENDER_FONT"; } font = child;
        } else if (!"TrueType".equals(subtype)) { return "RENDER_FONT"; }
        var descriptor = font.getCOSDictionary(COSName.FONT_DESC);
        if (descriptor == null || !(descriptor.getDictionaryObject(COSName.FONT_FILE2) instanceof COSStream bytes)) { return "RENDER_FONT"; }
        if (fonts.add(bytes)) {
            try (var stream = bytes.createInputStream(); var program = new TTFParser().parseEmbedded(stream)) {
                if (program.getNumberOfGlyphs() <= 0 || program.getTableMap().containsKey("fvar")) { throw PdfObjects.invalid(); }
            }
        }
        return null;
    }
}
