// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.encoding.Encoding;
import org.apache.pdfbox.pdmodel.font.encoding.DictionaryEncoding;
import org.apache.pdfbox.pdmodel.font.encoding.StandardEncoding;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

/** Per-operation explicit substitutions, applied before constructing any source PDFont. */
final class PdfSourceFonts {
    private static final Set<String> STANDARD_LATIN_FONTS = Set.of(
            "Helvetica", "Helvetica-Bold", "Helvetica-Oblique", "Helvetica-BoldOblique",
            "Times-Roman", "Times-Bold", "Times-Italic", "Times-BoldItalic",
            "Courier", "Courier-Bold", "Courier-Oblique", "Courier-BoldOblique");
    private record Key(PdfFont font, COSBase encoding) { }
    private record Range(int first, int last) { }
    private final PDDocument document;
    private final Map<String, PdfFont> mappings;
    private final PdfLimits limits;
    private final ProcessingPolicy policy;
    private final List<ProcessingIssue> issues;
    private final String format;
    private final Map<Key, COSDictionary> programs = new HashMap<>();
    private final Map<COSDictionary, Range> mapped = new IdentityHashMap<>();
    private final Map<COSDictionary, Set<Integer>> compositeMapped = new IdentityHashMap<>();
    private final Map<PdfFont, COSDictionary> compositePrograms = new HashMap<>();
    private final PdfCompositeSourceFont.Work compositeWork;
    private long inputBytes;

    PdfSourceFonts(PDDocument document, Map<String, PdfFont> mappings, PdfLimits limits,
                   ProcessingPolicy policy, List<ProcessingIssue> issues, String format, long sourceBytes) {
        this.document = document; this.mappings = mappings; this.limits = limits;
        this.policy = policy; this.issues = issues; this.format = format; inputBytes = sourceBytes;
        compositeWork = new PdfCompositeSourceFont.Work(limits);
    }

    static void register(Map<String, PdfFont> mappings, String name, PdfFont font, PdfLimits limits) {
        Objects.requireNonNull(name, "exactBaseFontName"); Objects.requireNonNull(font, "font");
        if (name.isBlank() || name.length() > 256) { throw new IllegalArgumentException("invalid PDF BaseFont name"); }
        if (!mappings.containsKey(name) && mappings.size() >= limits.maxResources()) { throw new IllegalArgumentException("source font mapping budget exceeded"); }
        mappings.put(name, font);
    }

    void apply(PDPage page, int pageIndex) throws IOException {
        if (mappings.isEmpty()) { return; }
        Set<COSDictionary> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<COSDictionary> fonts = Collections.newSetFromMap(new IdentityHashMap<>());
        resources(page.getResources(), visited, fonts, pageIndex);
        for (var annotation : page.getAnnotations()) {
            var appearance = annotation.getNormalAppearanceStream();
            if (appearance != null) { resources(appearance.getResources(), visited, fonts, pageIndex); }
        }
    }

    void validate() throws IOException {
        // Recount the complete graph, including the source streams and newly embedded programs.
        if (!mapped.isEmpty() || !compositeMapped.isEmpty()) { new PdfPreflight(limits, true, true).inspect(document); }
    }

    void requireGlyph(PDFont font, int code) throws IOException {
        var range = mapped.get(font.getCOSObject());
        if (range != null && (code < range.first() || code > range.last())) { throw PdfObjects.unsupported(); }
        var codes = compositeMapped.get(font.getCOSObject());
        if (codes != null && !codes.contains(code)) { throw PdfObjects.unsupported(); }
        PdfRasterResources.requireGlyph(font, code);
    }

    private void resources(PDResources value, Set<COSDictionary> visited, Set<COSDictionary> fonts, int page) throws IOException {
        ExecutionContext.checkpoint();
        if (value == null || !visited.add(value.getCOSObject())) { return; }
        var group = value.getCOSObject().getCOSDictionary(COSName.FONT);
        if (group != null) {
            for (var name : group.keySet()) {
                if (!(group.getDictionaryObject(name) instanceof COSDictionary font)) { throw PdfObjects.invalid(); }
                font(font, fonts, page);
            }
        }
        for (var name : value.getXObjectNames()) {
            if (value.getXObject(name) instanceof PDFormXObject form) { resources(form.getResources(), visited, fonts, page); }
        }
        for (var name : value.getExtGStateNames()) {
            var state = value.getExtGState(name);
            if (state.getCOSObject().containsKey(COSName.FONT)) {
                if (!(state.getCOSObject().getDictionaryObject(COSName.FONT) instanceof COSArray setting) || setting.size() != 2
                        || !(setting.getObject(0) instanceof COSDictionary font)) { throw PdfObjects.invalid(); }
                font(font, fonts, page);
            }
            if (state.getSoftMask() != null && state.getSoftMask().getGroup() != null) {
                resources(state.getSoftMask().getGroup().getResources(), visited, fonts, page);
            }
        }
    }

    private void font(COSDictionary source, Set<COSDictionary> fonts, int page) throws IOException {
        ExecutionContext.checkpoint();
        if (!fonts.add(source)) { return; }
        if (mapped.containsKey(source) || compositeMapped.containsKey(source)) { loss(page); return; }
        var rawName = source.getDictionaryObject(COSName.BASE_FONT);
        if (!(rawName instanceof COSName name) || !mappings.containsKey(name.getName())) { return; }
        if (COSName.TYPE0.equals(source.getCOSName(COSName.SUBTYPE))) { composite(source, mappings.get(name.getName()), page); return; }
        var rawDescriptor = source.getDictionaryObject(COSName.FONT_DESC);
        if (rawDescriptor != null && !(rawDescriptor instanceof COSDictionary)) { throw PdfObjects.invalid(); }
        var descriptor = (COSDictionary) rawDescriptor;
        if (descriptor != null) {
            for (var key : List.of(COSName.FONT_FILE, COSName.FONT_FILE2, COSName.FONT_FILE3)) {
                if (descriptor.containsKey(key)) {
                    if (!(descriptor.getDictionaryObject(key) instanceof COSStream)) { throw PdfObjects.invalid(); }
                    return; // A supplied mapping never repairs or replaces a declared program.
                }
            }
        }
        if (!(source.getDictionaryObject(COSName.SUBTYPE) instanceof COSName subtypeName)) { throw PdfObjects.invalid(); }
        String subtype = subtypeName.getName();
        if (!subtype.equals("Type1") && !subtype.equals("TrueType")) { return; }
        if (subtype.equals("Type1") && Set.of("Symbol", "ZapfDingbats").contains(name.getName())
                && !(source.getDictionaryObject(COSName.ENCODING) instanceof COSDictionary)) { return; }
        boolean standard = subtype.equals("Type1") && STANDARD_LATIN_FONTS.contains(name.getName());
        if (!standard && descriptor == null) { throw PdfObjects.invalid(); }
        if (descriptor != null) {
            if (!(descriptor.getDictionaryObject(COSName.FLAGS) instanceof COSInteger flags)
                    || flags.longValue() < 0 || flags.longValue() > Integer.MAX_VALUE
                    || (flags.intValue() & 36) == 36) { throw PdfObjects.invalid(); }
            if ((flags.intValue() & 36) != 32) { return; }
        }
        var rawEncoding = source.getDictionaryObject(COSName.ENCODING);
        Encoding encoding;
        if (rawEncoding == null) {
            if (!standard) { return; }
            encoding = StandardEncoding.INSTANCE;
        } else if (rawEncoding instanceof COSName encodingName) {
            if (!Set.of("StandardEncoding", "WinAnsiEncoding", "MacRomanEncoding").contains(encodingName.getName())) { return; }
            encoding = Encoding.getInstance(encodingName);
        } else if (rawEncoding instanceof COSDictionary dictionary) { encoding = dictionaryEncoding(dictionary, standard); }
        else { throw PdfObjects.invalid(); }
        if (encoding == null) { return; }
        var widths = source.getDictionaryObject(COSName.WIDTHS);
        int first; int last;
        COSArray declared;
        if (widths != null || source.containsKey(COSName.FIRST_CHAR) || source.containsKey(COSName.LAST_CHAR)) {
            if (!(widths instanceof COSArray array) || !(source.getDictionaryObject(COSName.FIRST_CHAR) instanceof COSInteger start)
                    || !(source.getDictionaryObject(COSName.LAST_CHAR) instanceof COSInteger end)
                    || start.longValue() < 0 || end.longValue() > 255 || start.longValue() > end.longValue()
                    || array.size() != end.longValue() - start.longValue() + 1) { throw PdfObjects.invalid(); }
            for (int index = 0; index < array.size(); index++) {
                var value = array.getObject(index);
                if (!(value instanceof COSNumber number) || !Float.isFinite(number.floatValue()) || number.floatValue() < 0) { throw PdfObjects.invalid(); }
            }
            first = start.intValue(); last = end.intValue(); declared = array;
        } else {
            if (!standard) { return; }
            first = 0; last = 255; declared = new COSArray();
            var metrics = Standard14Fonts.getAFM(name.getName());
            for (int code = 0; code <= 255; code++) {
                String glyph = encoding.getName(code);
                declared.add(new COSFloat(glyph == null ? 0 : metrics.getCharacterWidth(glyph)));
            }
        }
        // Record the loss before allocating a font or changing the task-local source graph.
        loss(page);
        var key = new Key(mappings.get(name.getName()), encoding.getCOSObject());
        var program = programs.get(key);
        if (program == null) {
            int bytes = key.font().bytes().length;
            if (bytes > limits.maxDecodedStreamBytes()) { throw new PdfOperationException(PdfOperationException.Code.DECODED_STREAM_LIMIT, 0, page); }
            if (bytes > limits.maxTotalInputBytes() - inputBytes) { throw new PdfOperationException(PdfOperationException.Code.INPUT_LIMIT, 0, page); }
            inputBytes += bytes;
            program = PDTrueTypeFont.load(document, new ByteArrayInputStream(key.font().bytes()), encoding).getCOSObject();
            programs.put(key, program);
        }
        var replacement = new COSDictionary(program);
        replacement.setInt(COSName.FIRST_CHAR, first); replacement.setInt(COSName.LAST_CHAR, last); replacement.setItem(COSName.WIDTHS, declared);
        if (source.containsKey(COSName.TO_UNICODE)) {
            if (!(source.getDictionaryObject(COSName.TO_UNICODE) instanceof COSStream)) { throw PdfObjects.invalid(); }
            replacement.setItem(COSName.TO_UNICODE, source.getItem(COSName.TO_UNICODE));
        }
        else { replacement.removeItem(COSName.TO_UNICODE); }
        source.clear(); source.addAll(replacement); mapped.put(source, new Range(first, last));
    }

    private static Encoding dictionaryEncoding(COSDictionary dictionary, boolean standard) throws IOException {
        for (var key : dictionary.keySet()) {
            if (!Set.of(COSName.TYPE, COSName.BASE_ENCODING, COSName.DIFFERENCES).contains(key)) { return null; }
        }
        if (dictionary.containsKey(COSName.TYPE) && !COSName.ENCODING.equals(dictionary.getCOSName(COSName.TYPE))) { throw PdfObjects.invalid(); }
        var base = dictionary.getDictionaryObject(COSName.BASE_ENCODING);
        if (base != null && !(base instanceof COSName)) { throw PdfObjects.invalid(); }
        if (base == null && !standard) { return null; }
        var baseName = base == null ? COSName.STANDARD_ENCODING : (COSName) base;
        if (!Set.of("StandardEncoding", "WinAnsiEncoding", "MacRomanEncoding", "MacExpertEncoding").contains(baseName.getName())) { return null; }
        var differences = dictionary.getDictionaryObject(COSName.DIFFERENCES);
        if (differences != null && !(differences instanceof COSArray)) { throw PdfObjects.invalid(); }
        if (differences instanceof COSArray array) {
            int code = -1;
            for (var entry : array) {
                if (entry instanceof COSInteger number) {
                    if (number.longValue() < 0 || number.longValue() > 255) { throw PdfObjects.invalid(); } code = number.intValue();
                } else if (entry instanceof COSName) {
                    if (code < 0 || code > 255) { throw PdfObjects.invalid(); } code++;
                } else { throw PdfObjects.invalid(); }
            }
        }
        return new DictionaryEncoding(dictionary, true, Encoding.getInstance(baseName));
    }

    private void composite(COSDictionary source, PdfFont font, int page) throws IOException {
        PdfCompositeSourceFont.Analysis analysis;
        try { analysis = PdfCompositeSourceFont.analyse(source, font, limits, compositeWork); }
        catch (PdfOperationException failure) {
            if (failure.code() == PdfOperationException.Code.UNSUPPORTED_FEATURE) { return; }
            throw failure;
        }
        if (analysis == null) { return; }
        loss(page);
        var program = compositePrograms.get(font);
        if (program == null) {
            int bytes = font.bytes().length;
            if (bytes > limits.maxDecodedStreamBytes()) { throw new PdfOperationException(PdfOperationException.Code.DECODED_STREAM_LIMIT, 0, page); }
            if (bytes > limits.maxTotalInputBytes() - inputBytes) { throw new PdfOperationException(PdfOperationException.Code.INPUT_LIMIT, 0, page); }
            inputBytes += bytes;
            program = PDType0Font.load(document, new ByteArrayInputStream(font.bytes()), false).getCOSObject();
            compositePrograms.put(font, program);
        }
        analysis.apply(document, source, program);
        compositeMapped.put(source, analysis.codes());
    }

    private void loss(int page) {
        PdfImageSupport.loss(policy, issues, format, "RENDER_FONT_MAPPING", "RASTERIZE",
                ProcessingPolicy.Treatment.DEGRADE, "EXPLICIT_SOURCE_FONT_SUBSTITUTION", page);
    }
}
