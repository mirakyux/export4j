// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

/** Fresh content reconstruction for an explicit opaque static subset. Intersecting glyphs are
 * removed whole; image pixels and path geometry are actually removed, not merely clipped.
 * Regions use the rotated CropBox view, top-left origin, physical pt. Outside fill text remains
 * searchable and painted paths remain vector outlines. This is not sensitive-content discovery.
 */
public final class PdfSelectiveRedactor {
    private record Region(int page, double x, double y, double width, double height) { }
    private record View(double width, double height, AffineTransform transform) { }
    private final PdfAdapter adapter;
    private final PdfInput input;
    private final PdfLimits limits;
    private final List<Region> regions = new ArrayList<>();
    private ProcessingPolicy policy = ProcessingPolicy.STRICT;
    private ExecutionOptions execution = ExecutionOptions.DEFAULT;
    private int fill;
    PdfSelectiveRedactor(PdfAdapter adapter, PdfInput input, PdfLimits limits) { this.adapter = adapter; this.input = input; this.limits = limits; }
    public PdfSelectiveRedactor area(int page, double x, double y, double width, double height) {
        if (page < 0 || !finite(x) || !finite(y) || !finite(width) || !finite(height) || x < 0 || y < 0 || width <= 0 || height <= 0) { throw new IllegalArgumentException("invalid redaction region"); }
        if (regions.size() >= limits.maxObjects()) { throw new IllegalArgumentException("redaction region budget exceeded"); }
        regions.add(new Region(page, x, y, width, height)); return this;
    }
    public PdfSelectiveRedactor fill(int rgb) { if (rgb < 0 || rgb > 0xFFFFFF) { throw new IllegalArgumentException("invalid RGB fill"); } fill = rgb; return this; }
    public PdfSelectiveRedactor policy(ProcessingPolicy value) { policy = Objects.requireNonNull(value, "policy"); return this; }
    public PdfSelectiveRedactor execution(ExecutionOptions value) { execution = Objects.requireNonNull(value, "execution"); return this; }
    public PreparedPdf prepare() throws IOException {
        var selection = List.copyOf(regions); var strategy = policy; var control = execution; int color = fill;
        if (selection.isEmpty()) { throw new IllegalStateException("redaction requires at least one explicit region"); }
        return ExecutionContext.execute(control, () -> {
            try (var source = adapter.openForEdit(input, adapter.preflight(true)); var target = new PDDocument()) {
                var state = new State(target, strategy); var catalog = source.getDocumentCatalog().getCOSObject();
                if (catalog.containsKey(COSName.OCPROPERTIES)) { throw PdfObjects.unsupported(); }
                state.loss("PDF_CONTENT_RECONSTRUCTION", ProcessingPolicy.Treatment.DEGRADE, "FRESH_GLYPH_IMAGE_AND_VECTOR_GEOMETRY_WITHOUT_SOURCE_STREAMS_OR_HISTORY", -1);
                if (source.isEncrypted()) { state.loss("ENCRYPTION_REMOVED", ProcessingPolicy.Treatment.DEGRADE, "OUTPUT_IS_UNENCRYPTED", -1); }
                if (!source.getDocumentInformation().getCOSObject().keySet().isEmpty() || catalog.containsKey(COSName.METADATA)) { state.discard("DOCUMENT_METADATA", -1); }
                if (catalog.containsKey(COSName.ACRO_FORM)) { state.discard("FORMS", -1); }
                if (catalog.containsKey(COSName.OUTLINES)) { state.discard("BOOKMARKS", -1); }
                if (catalog.containsKey(COSName.NAMES) || catalog.containsKey(COSName.AF)) { state.discard("ATTACHMENTS", -1); }
                if (catalog.containsKey(COSName.STRUCT_TREE_ROOT)) { state.discard("PDF_TAGS", -1); }
                for (var region : selection) { if (region.page() >= source.getNumberOfPages()) { throw new PdfOperationException(PdfOperationException.Code.INVALID_SELECTION, 0, region.page()); } }
                for (int index = 0; index < source.getNumberOfPages(); index++) {
                    state.work(); var original = source.getPage(index); var view = view(original); var removed = new Area();
                    if (original.getCOSObject().containsKey(COSName.GROUP)) { throw PdfObjects.unsupported(); }
                    for (var region : selection) {
                        if (region.page() != index) { continue; }
                        if (region.x() + region.width() > view.width() || region.y() + region.height() > view.height()) { throw new IllegalArgumentException("redaction region exceeds visible page"); }
                        removed.add(new Area(new Rectangle2D.Double(region.x(), view.height() - region.y() - region.height(), region.width(), region.height())));
                    }
                    if (!original.getAnnotations().isEmpty()) { state.discard("ANNOTATIONS", index); }
                    var media=original.getMediaBox(); var crop=original.getCropBox();
                    var page = new PDPage(new PDRectangle(media.getLowerLeftX(),media.getLowerLeftY(),media.getWidth(),media.getHeight()));
                    page.setCropBox(new PDRectangle(crop.getLowerLeftX(),crop.getLowerLeftY(),crop.getWidth(),crop.getHeight()));
                    page.setRotation(original.getRotation()); page.setUserUnit(original.getUserUnit()); target.addPage(page); page.setResources(new PDResources());
                    var stream = new PDStream(target); page.setContents(stream);
                    try (var encoded = stream.createOutputStream(COSName.FLATE_DECODE)) {
                        var engine = new Engine(original, page, view, removed, state, index, new ContentStreamWriter(state.output(encoded)));
                        engine.trailing(state.forms.page(original));
                        engine.processPage(original); engine.complete(0); engine.paint(removed, color);
                    }
                }
                state.finishFonts(); new PdfPreflight(limits).inspect(target);
                return PreparedPdf.encode(target, selection.size(), state.issues, limits, control);
            }
        });
    }
    private static View view(PDPage page) throws IOException {
        var crop = page.getCropBox(); double a = crop.getLowerLeftX(), b = crop.getLowerLeftY(), w = crop.getWidth(), h = crop.getHeight(), u = page.getUserUnit();
        int rotation = Math.floorMod(page.getRotation(), 360);
        if (!finite(a) || !finite(b) || !finite(w) || !finite(h) || !finite(u) || w <= 0 || h <= 0 || u <= 0 || rotation % 90 != 0) { throw PdfObjects.invalid(); }
        var transform = switch (rotation) {
            case 0 -> new AffineTransform(u, 0, 0, u, -a*u, -b*u);
            case 90 -> new AffineTransform(0, -u, u, 0, -b*u, (a+w)*u);
            case 180 -> new AffineTransform(-u, 0, 0, -u, (a+w)*u, (b+h)*u);
            case 270 -> new AffineTransform(0, u, -u, 0, (b+h)*u, -a*u);
            default -> throw PdfObjects.invalid();
        };
        double width = (rotation % 180 == 0 ? w : h)*u, height = (rotation % 180 == 0 ? h : w)*u;
        if (!finite(width) || !finite(height)) { throw PdfObjects.unsupported(); }
        return new View(width, height, transform);
    }
    private static boolean finite(double value) { return Double.isFinite(value) && Math.abs(value) <= 1_000_000; }
    private final class State {
        final PDDocument target; final ProcessingPolicy strategy; final List<ProcessingIssue> issues = new ArrayList<>();
        final Map<PDFont, Font> fonts = new IdentityHashMap<>(); final Set<String> reported = new java.util.HashSet<>();
        final PdfTextForms forms = new PdfTextForms(limits); long work, nodes, pixels, characters, fontBytes, contentBytes;
        State(PDDocument target, ProcessingPolicy strategy) { this.target = target; this.strategy = strategy; }
        void work() throws IOException { ExecutionContext.checkpoint(); if (++work > limits.maxContentOperators()) { throw new PdfOperationException(PdfOperationException.Code.OPERATOR_LIMIT, 0, -1); } }
        void node() throws IOException { if (++nodes > limits.maxObjects()) { throw new PdfOperationException(PdfOperationException.Code.OBJECT_LIMIT, 0, -1); } }
        java.io.OutputStream output(java.io.OutputStream destination) {
            return new java.io.OutputStream() {
                long pageBytes;
                void reserve(int count) throws IOException {
                    ExecutionContext.checkpoint();
                    if (count > limits.maxDecodedStreamBytes()-pageBytes || count > limits.maxDecodedBytes()-contentBytes) { throw new PdfOperationException(PdfOperationException.Code.DECODED_STREAM_LIMIT,0,-1); }
                    pageBytes+=count; contentBytes+=count;
                }
                @Override public void write(int value) throws IOException { reserve(1); destination.write(value); }
                @Override public void write(byte[] value,int offset,int count) throws IOException { Objects.checkFromIndexSize(offset,count,value.length); reserve(count); destination.write(value,offset,count); }
            };
        }
        void loss(String feature, ProcessingPolicy.Treatment treatment, String effect, int page) {
            if (reported.add(feature+":"+effect+":"+page)) { PdfImageSupport.loss(strategy, issues, "PDF", feature, "REDACT_SELECTIVE", treatment, effect, page); }
        }
        void discard(String feature, int page) { loss(feature, ProcessingPolicy.Treatment.DISCARD, "SOURCE_OBJECTS_AND_ASSOCIATED_HIDDEN_CONTENT_NOT_COPIED", page); }
        Font font(PDFont source) throws IOException {
            var known = fonts.get(source); if (known != null) { return known; }
            if (!(source instanceof PDTrueTypeFont || source instanceof PDType0Font composite && composite.getDescendantFont() instanceof PDCIDFontType2) || source.isVertical() || !source.isEmbedded() || source.getFontDescriptor() == null || source.getFontDescriptor().getFontFile2() == null) { throw PdfObjects.unsupported(); }
            node(); byte[] bytes = source.getFontDescriptor().getFontFile2().toByteArray();
            if (bytes.length > limits.maxDecodedStreamBytes() || bytes.length > limits.maxDecodedBytes() - fontBytes) { throw new PdfOperationException(PdfOperationException.Code.DECODED_STREAM_LIMIT, 0, -1); } fontBytes += bytes.length;
            var targetFont = PDType0Font.load(target, new ByteArrayInputStream(bytes), true);
            var result = new Font(targetFont); fonts.put(source, result); return result;
        }
        void finishFonts() throws IOException {
            for (var font : fonts.values()) {
                work(); font.target.addGlyphsToSubset(font.unicode.keySet()); font.target.subset();
                // Never copy the source CMap, marked-content strings or arbitrary font dictionaries.
                var cmap = new StringBuilder("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n/CIDSystemInfo << /Registry (Export4j) /Ordering (Retained) /Supplement 0 >> def\n/CMapName /Retained def\n/CMapType 2 def\n1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n");
                var entries = new ArrayList<>(font.unicode.entrySet());
                for (int start = 0; start < entries.size(); start += 100) {
                    int end = Math.min(entries.size(), start+100); cmap.append(end-start).append(" beginbfchar\n");
                    for (int index = start; index < end; index++) {
                        work(); var entry = entries.get(index); cmap.append('<').append(String.format(java.util.Locale.ROOT, "%04X", entry.getKey())).append("> <");
                        for (byte value : entry.getValue().getBytes(StandardCharsets.UTF_16BE)) { cmap.append(String.format(java.util.Locale.ROOT, "%02X", value & 255)); }
                        cmap.append(">\n");
                    }
                    cmap.append("endbfchar\n");
                }
                cmap.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
                var stream = new PDStream(target, new ByteArrayInputStream(cmap.toString().getBytes(StandardCharsets.US_ASCII)), COSName.FLATE_DECODE);
                font.target.getCOSObject().setItem(COSName.TO_UNICODE, stream);
                var widths=new COSArray();
                for(var entry:font.widths.entrySet()) { widths.add(COSInteger.get(entry.getKey())); var width=new COSArray(); width.add(new COSFloat(entry.getValue())); widths.add(width); }
                font.target.getDescendantFont().getCOSObject().setItem(COSName.W,widths);
            }
        }
    }
    private static final class Font {
        final PDType0Font target; final Map<Integer, String> unicode = new LinkedHashMap<>(); final Map<Integer,Float> widths=new LinkedHashMap<>();
        Font(PDType0Font target) { this.target = target; }
    }
    private final class Engine extends PDFGraphicsStreamEngine {
        private static final Set<String> OPERATORS = Set.of("q", "Q", "cm", "w", "J", "j", "M", "d", "ri", "i", "gs", "m", "l", "c", "v", "y", "h", "re", "S", "s", "f", "F", "f*", "B", "B*", "b", "b*", "n", "W", "W*", "BT", "ET", "Tc", "Tw", "Tz", "TL", "Tf", "Tr", "Ts", "Td", "TD", "Tm", "T*", "Tj", "TJ", "'", "\"", "Do", "BI", "BMC", "BDC", "EMC", "MP", "DP", "CS", "cs", "SC", "sc", "SCN", "scn", "G", "g", "RG", "rg", "K", "k");
        final PDPage output; final View view; final Area removed; final State state; final int page; final ContentStreamWriter writer;
        GeneralPath path = new GeneralPath(); int pendingClip = -1, depth, graphicsDepth, markedDepth, markedAncestors; boolean inText;
        Font runFont; PDFont runSource; Matrix runMatrix, previousMatrix; float previousWidth, runSize; int runColor;
        COSArray runParts; ByteArrayOutputStream runCodes;
        Engine(PDPage source, PDPage output, View view, Area removed, State state, int page, ContentStreamWriter writer) { super(source); this.output=output; this.view=view; this.removed=removed; this.state=state; this.page=page; this.writer=writer; }
        @Override protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            state.work(); if (!OPERATORS.contains(operator.getName())) { throw PdfObjects.unsupported(); }
            arguments(operator.getName(),operands);
            switch(operator.getName()) {
                case "BT" -> {if(inText) {throw PdfObjects.invalid();}inText=true;}
                case "ET" -> {if(!inText) {throw PdfObjects.invalid();}inText=false;}
                case "Tj","TJ","'","\"","Td","TD","Tm","T*" -> {if(!inText) {throw PdfObjects.invalid();}}
                case "BMC","BDC" -> {if((long)markedAncestors+markedDepth+graphicsDepth+depth+2>limits.maxDepth()) {throw new PdfOperationException(PdfOperationException.Code.DEPTH_LIMIT,0,page);}markedDepth++;}
                case "EMC" -> {if(markedDepth==0) {throw PdfObjects.invalid();}markedDepth--;}
                case "Do","BI","m","l","c","v","y","h","re","S","s","f","F","f*","B","B*","b","b*","n","W","W*" -> {if(inText) {throw PdfObjects.invalid();}}
                default -> { }
            }
            if(operator.getName().equals("q")) {if((long)markedAncestors+markedDepth+graphicsDepth+depth+2>limits.maxDepth()) {throw new PdfOperationException(PdfOperationException.Code.DEPTH_LIMIT,0,page);}graphicsDepth++;}
            else if(operator.getName().equals("Q")) {if(graphicsDepth<=0) {throw PdfObjects.invalid();}graphicsDepth--;}
            for (var operand : operands) { if (operand instanceof COSNumber number && !finite(number.floatValue())) { throw PdfObjects.invalid(); } }
            if (operator.getName().equals("gs") && !state.forms.graphicsStates.supported(getResources(), operands)) { throw PdfObjects.unsupported(); }
            if (Set.of("BMC", "BDC").contains(operator.getName()) && !operands.isEmpty() && COSName.OC.equals(operands.getFirst())) { throw PdfObjects.unsupported(); }
            if (Set.of("BMC", "BDC", "MP", "DP").contains(operator.getName())) { state.discard("PDF_ALTERNATIVE_TEXT", page); }
            super.processOperator(operator, operands);
        }
        void arguments(String operator,List<COSBase> operands) throws IOException {
            int numeric=switch(operator) {
                case "cm","Tm","c" -> 6;
                case "v","y","re","K","k" -> 4;
                case "RG","rg" -> 3;
                case "m","l","Td","TD" -> 2;
                case "w","J","j","M","i","Tc","Tw","Tz","TL","Tr","Ts","G","g" -> 1;
                default -> -1;
            };
            if(numeric>=0) {if(operands.size()!=numeric) {throw PdfObjects.invalid();}for(var operand:operands) {number(operand);} }
            else switch(operator) {
                case "q","Q","h","S","s","f","F","f*","B","B*","b","b*","n","W","W*","BT","ET","T*","EMC","BI" -> {if(!operands.isEmpty()) {throw PdfObjects.invalid();}}
                case "Do","gs","CS","cs","ri","BMC","MP" -> {if(operands.size()!=1 || !(operands.getFirst() instanceof COSName)) {throw PdfObjects.invalid();}}
                case "Tf" -> {if(operands.size()!=2 || !(operands.getFirst() instanceof COSName)) {throw PdfObjects.invalid();}number(operands.get(1));}
                case "Tj","'" -> {if(operands.size()!=1 || !(operands.getFirst() instanceof COSString)) {throw PdfObjects.invalid();}}
                case "\"" -> {if(operands.size()!=3 || !(operands.get(2) instanceof COSString)) {throw PdfObjects.invalid();}number(operands.get(0));number(operands.get(1));}
                case "TJ" -> {if(operands.size()!=1 || !(operands.getFirst() instanceof COSArray array)) {throw PdfObjects.invalid();}for(var item:array) {state.work();if(!(item instanceof COSString)) {number(item);}}}
                case "BDC","DP" -> {if(operands.size()!=2 || !(operands.getFirst() instanceof COSName) || !(operands.get(1) instanceof COSName || operands.get(1) instanceof COSDictionary && !(operands.get(1) instanceof COSStream))) {throw PdfObjects.invalid();}}
                case "SC","sc","SCN","scn" -> {var space=operator.startsWith("S")?getGraphicsState().getStrokingColorSpace():getGraphicsState().getNonStrokingColorSpace();if(!Set.of("DeviceRGB","DeviceGray","DeviceCMYK").contains(space.getName())) {throw PdfObjects.unsupported();}if(operands.size()!=space.getNumberOfComponents()) {throw PdfObjects.invalid();}for(var item:operands) {double value=number(item);if(value<0 || value>1) {throw PdfObjects.invalid();}}}
                case "d" -> {if(operands.size()!=2 || !(operands.getFirst() instanceof COSArray array) || number(operands.get(1))<0) {throw PdfObjects.invalid();}boolean positive=array.size()==0;for(var item:array) {state.work();double value=number(item);if(value<0) {throw PdfObjects.invalid();}positive |= value>0;}if(!positive) {throw PdfObjects.invalid();}}
                default -> throw PdfObjects.unsupported();
            }
            if(Set.of("J","j","Tr").contains(operator)) {double value=number(operands.getFirst());if(value!=(int)value || value<0 || value>(operator.equals("Tr")?7:2)) {throw PdfObjects.invalid();}}
            if(operator.equals("w") && number(operands.getFirst())<0 || operator.equals("M") && number(operands.getFirst())<1) {throw PdfObjects.invalid();}
            if(Set.of("G","g","RG","rg","K","k").contains(operator)) {for(var item:operands) {double value=number(item);if(value<0 || value>1) {throw PdfObjects.invalid();}}}
            if(operator.equals("i") && (number(operands.getFirst())<0 || number(operands.getFirst())>100)) {throw PdfObjects.invalid();}
            if(operator.equals("ri") && !Set.of("AbsoluteColorimetric","RelativeColorimetric","Saturation","Perceptual").contains(((COSName)operands.getFirst()).getName())) {throw PdfObjects.unsupported();}
        }
        double number(COSBase value) throws IOException {if(!(value instanceof COSNumber number) || !finite(number.floatValue())) {throw PdfObjects.invalid();}return number.floatValue();}
        void complete(int expectedGraphics) throws IOException {if(inText || markedDepth!=0 || graphicsDepth!=expectedGraphics || pendingClip>=0) {throw PdfObjects.invalid();}flushText();}
        void trailing(List<Object> tokens) throws IOException {if(!tokens.isEmpty() && !(tokens.getLast() instanceof Operator)) {throw PdfObjects.invalid();}}
        @Override protected void unsupportedOperator(Operator operator, List<COSBase> operands) throws IOException { throw PdfObjects.unsupported(); }
        @Override protected void operatorException(Operator operator, List<COSBase> operands, IOException failure) throws IOException { throw failure; }
        @Override public void showForm(PDFormXObject form) throws IOException {
            flushText(); state.work(); if((long)markedAncestors+markedDepth+graphicsDepth+depth+2>limits.maxDepth()) {throw new PdfOperationException(PdfOperationException.Code.DEPTH_LIMIT,0,page);}
            state.forms.enter(form, ++depth); var saved = path; int savedClip = pendingClip,savedDepth=graphicsDepth,savedMarked=markedDepth,savedAncestors=markedAncestors;boolean savedText=inText; path = new GeneralPath(); pendingClip = -1;markedAncestors+=markedDepth;markedDepth=0;inText=false;
            try { if (!state.forms.supported(form)) { throw PdfObjects.unsupported(); } trailing(state.forms.tokens(form));super.showForm(form); complete(savedDepth); }
            finally { state.forms.leave(form); depth--; graphicsDepth=savedDepth;markedDepth=savedMarked;markedAncestors=savedAncestors;inText=savedText; path = saved; pendingClip = savedClip; }
        }
        @Override protected void showGlyph(Matrix matrix, PDFont font, int code, Vector displacement) throws IOException {
            state.work(); if (++state.characters > limits.maxTextCharacters()) { throw new PdfOperationException(PdfOperationException.Code.TEXT_LIMIT, 0, page); }
            var mode = getGraphicsState().getTextState().getRenderingMode(); if (mode.isClip()) { throw PdfObjects.unsupported(); }
            if (!mode.isFill() && !mode.isStroke()) { flushText(); state.discard("PDF_HIDDEN_TEXT", page); return; }
            if (!(font instanceof PDVectorFont vector)) { throw PdfObjects.unsupported(); }
            var transform = new AffineTransform(view.transform()); transform.concatenate(matrix.createAffineTransform()); transform.scale(.001, .001);
            Shape outline = transform.createTransformedShape(vector.getNormalizedPath(code)); var ink = new Area(outline); var visible = new Area(ink); visible.intersect(clip());
            if (visible.isEmpty() && !ink.isEmpty()) { flushText(); state.discard("PDF_HIDDEN_TEXT", page); return; }
            var selected = new Area(visible.getBounds2D()); selected.intersect(removed);
            // The full glyph code is omitted even when only part of its ink intersects the region.
            if (!selected.isEmpty()) { flushText(); return; }
            if (mode.isStroke()) { throw PdfObjects.unsupported(); }
            var hidden = new Area(ink); hidden.subtract(clip());
            if (!hidden.isEmpty()) { state.loss("PDF_CLIPPED_TEXT", ProcessingPolicy.Treatment.DEGRADE, "PARTIALLY_CLIPPED_GLYPH_BECOMES_VISIBLE_VECTOR_WITHOUT_HIDDEN_TEXT", page); paint(visible, rgb(getGraphicsState().getNonStrokingColor())); return; }
            String unicode = font.toUnicode(code);
            if (unicode == null || unicode.isEmpty() || unicode.codePoints().anyMatch(value -> value == 0 || value >= 0xD800 && value <= 0xDFFF)) { throw PdfObjects.unsupported(); }
            // One source glyph may expand to many UTF-16 units through ToUnicode.
            // Charge every retained occurrence before storing the mapping or building its CMap.
            int extraCharacters = unicode.length()-1;
            if (extraCharacters > limits.maxTextCharacters()-state.characters) { throw new PdfOperationException(PdfOperationException.Code.TEXT_LIMIT, 0, page); }
            state.characters += extraCharacters;
            if (ink.isEmpty() && !unicode.isBlank()) { flushText(); state.discard("PDF_HIDDEN_TEXT", page); return; }
            int gid = font instanceof PDType0Font composite ? composite.codeToGID(code) : font instanceof PDTrueTypeFont simple ? simple.codeToGID(code) : -1;
            if (gid <= 0 || gid > 65535) { throw PdfObjects.unsupported(); }
            var rebuilt = state.font(font); var previous = rebuilt.unicode.putIfAbsent(gid, unicode);
            if (previous != null && !previous.equals(unicode)) { throw PdfObjects.unsupported(); }
            float width=font.getWidth(code); var previousWidth=rebuilt.widths.putIfAbsent(gid,width);
            if(!Float.isFinite(width) || width<0 || previousWidth!=null && Math.abs(previousWidth-width)>.001f) {throw PdfObjects.unsupported();}
            appendGlyph(font,rebuilt,matrix,gid,width,rgb(getGraphicsState().getNonStrokingColor()),getGraphicsState().getTextState().getFontSize());
        }
        void appendGlyph(PDFont source,Font font,Matrix matrix,int gid,float width,int rgb,float size) throws IOException {
            if(!Float.isFinite(size) || size<=0) {throw PdfObjects.unsupported();}
            double adjustment=0;
            if(runFont!=null) {
                double[] before=new double[6],after=new double[6]; previousMatrix.createAffineTransform().getMatrix(before);matrix.createAffineTransform().getMatrix(after);
                boolean compatible=runSource==source && runColor==rgb && runSize==size;
                for(int index=0;index<4;index++) {compatible &= Math.abs(before[index]-after[index])<1e-6;}
                Point2D delta;
                try {delta=previousMatrix.createAffineTransform().createInverse().deltaTransform(new Point2D.Double(after[4]-before[4],after[5]-before[5]),null);}
                catch(java.awt.geom.NoninvertibleTransformException failure){throw PdfObjects.unsupported();}
                if(!compatible || Math.abs(delta.getY())>1e-5 || delta.getX()<0 || delta.getX()>Math.max(4,previousWidth/1000d*4)) {flushText();}
                else {adjustment=previousWidth-delta.getX()*1000;}
            }
            if(runFont==null) {runSource=source;runFont=font;runMatrix=matrix.clone();runColor=rgb;runSize=size;runParts=new COSArray();runCodes=new ByteArrayOutputStream();}
            if(Math.abs(adjustment)>.001) {if(runCodes.size()>0) {runParts.add(new COSString(runCodes.toByteArray()));runCodes.reset();}runParts.add(new COSFloat((float)adjustment));}
            runCodes.writeBytes(font.target.encodeGlyphId(gid));previousMatrix=matrix.clone();previousWidth=width;
        }
        void flushText() throws IOException {
            if(runFont==null) {return;}
            if(runCodes.size()>0) {runParts.add(new COSString(runCodes.toByteArray()));}
            double[] values=new double[6];runMatrix.createAffineTransform().getMatrix(values);for(int index=0;index<4;index++) {values[index]/=runSize;}
            write("q");color(runColor);write("BT");writer.writeTokens(output.getResources().add(runFont.target),new COSFloat(runSize),Operator.getOperator("Tf"));numbers(values,"Tm");
            writer.writeTokens(runParts,Operator.getOperator("TJ"));write("ET");write("Q");runFont=null;runSource=null;runParts=null;runCodes=null;runMatrix=null;previousMatrix=null;
        }
        Area clip() {
            var result = new Area(view.transform().createTransformedShape(getGraphicsState().getCurrentClippingPath()));
            result.intersect(new Area(new Rectangle2D.Double(0, 0, view.width(), view.height()))); return result;
        }
        int rgb(PDColor color) throws IOException {
            if (!Set.of("DeviceRGB", "DeviceGray", "DeviceCMYK").contains(color.getColorSpace().getName()) || color.isPattern()) { throw PdfObjects.unsupported(); }
            if (color.getColorSpace().getName().equals("DeviceCMYK")) { state.loss("PDF_COLOR_MODEL",ProcessingPolicy.Treatment.DEGRADE,"DEVICE_CMYK_CONVERTED_TO_RGB",page); }
            return color.toRGB();
        }
        @Override public void drawImage(PDImage image) throws IOException {
            flushText(); state.work(); long count=(long)image.getWidth()*image.getHeight();
            if (count <= 0 || count > limits.maxImagePixels()-state.pixels) { throw new PdfOperationException(PdfOperationException.Code.IMAGE_LIMIT,0,page); } state.pixels+=count;
            if (image.isStencil() || image.getBitsPerComponent()!=8 || image.getCOSObject() instanceof org.apache.pdfbox.cos.COSDictionary dictionary && (dictionary.containsKey(COSName.MASK) || dictionary.containsKey(COSName.SMASK)) || !Set.of("DeviceRGB","DeviceGray").contains(image.getColorSpace().getName())) { throw PdfObjects.unsupported(); }
            var transform=new AffineTransform(view.transform()); transform.concatenate(getGraphicsState().getCurrentTransformationMatrix().createAffineTransform());
            if (!(Math.abs(transform.getShearX())<1e-8 && Math.abs(transform.getShearY())<1e-8 || Math.abs(transform.getScaleX())<1e-8 && Math.abs(transform.getScaleY())<1e-8) || Math.abs(transform.getDeterminant())<1e-12) { throw PdfObjects.unsupported(); }
            var source=image.getImage(); if (source==null) { throw PdfObjects.invalid(); }
            var raster=new BufferedImage(source.getWidth(),source.getHeight(),BufferedImage.TYPE_INT_RGB);
            try {
                var graphics=raster.createGraphics(); try { graphics.setColor(Color.WHITE); graphics.fillRect(0,0,raster.getWidth(),raster.getHeight()); graphics.drawImage(source,0,0,null); } finally { graphics.dispose(); }
                var visible=clip(); boolean boundary=false;
                for(int y=0;y<raster.getHeight();y++) {
                    ExecutionContext.checkpoint();
                    for(int x=0;x<raster.getWidth();x++) {
                        var pixel=transform.createTransformedShape(new Rectangle2D.Double((double)x/raster.getWidth(),1d-(double)(y+1)/raster.getHeight(),1d/raster.getWidth(),1d/raster.getHeight())).getBounds2D();
                        if (removed.intersects(pixel)) { raster.setRGB(x,y,fill); }
                        else if (!visible.contains(pixel)) { raster.setRGB(x,y,0xFFFFFF); boundary=true; }
                    }
                }
                if (boundary) { state.loss("PDF_IMAGE_BOUNDARY",ProcessingPolicy.Treatment.DEGRADE,"CLIPPED_IMAGE_PIXELS_REMOVED_INCLUDING_PARTIAL_BOUNDARY_PIXELS",page); }
                state.node(); var clean=LosslessFactory.createFromImage(state.target,raster); clean.setInterpolate(image.getInterpolate());
                write("q");var imageClip=new Area(visible);imageClip.subtract(removed);writePath(imageClip);write("W");write("n");
                double[] values=new double[6]; getGraphicsState().getCurrentTransformationMatrix().createAffineTransform().getMatrix(values); numbers(values,"cm"); writer.writeTokens(output.getResources().add(clean),Operator.getOperator("Do")); write("Q");
            } finally { source.flush(); raster.flush(); }
        }
        @Override public void appendRectangle(Point2D p0,Point2D p1,Point2D p2,Point2D p3) { path.moveTo(p0.getX(),p0.getY()); path.lineTo(p1.getX(),p1.getY()); path.lineTo(p2.getX(),p2.getY()); path.lineTo(p3.getX(),p3.getY()); path.closePath(); }
        @Override public void moveTo(float x,float y) { path.moveTo(x,y); }
        @Override public void lineTo(float x,float y) { path.lineTo(x,y); }
        @Override public void curveTo(float x1,float y1,float x2,float y2,float x3,float y3) { path.curveTo(x1,y1,x2,y2,x3,y3); }
        @Override public Point2D getCurrentPoint() { return path.getCurrentPoint(); }
        @Override public void closePath() { path.closePath(); }
        @Override public void clip(int windingRule) { pendingClip=windingRule; }
        @Override public void endPath() { finishPath(); }
        void finishPath() { if(pendingClip>=0) { path.setWindingRule(pendingClip); getGraphicsState().intersectClippingPath(path); pendingClip=-1; } path=new GeneralPath(); }
        @Override public void strokePath() throws IOException { stroke(); finishPath(); }
        @Override public void fillPath(int windingRule) throws IOException { path.setWindingRule(windingRule); draw(new Area(path),rgb(getGraphicsState().getNonStrokingColor())); finishPath(); }
        @Override public void fillAndStrokePath(int windingRule) throws IOException { path.setWindingRule(windingRule); draw(new Area(path),rgb(getGraphicsState().getNonStrokingColor())); stroke(); finishPath(); }
        void stroke() throws IOException {
            var graphics=getGraphicsState(); if(graphics.getLineWidth()<=0) { throw PdfObjects.unsupported(); }
            var matrix=graphics.getCurrentTransformationMatrix().createAffineTransform(); Shape local;
            try { local=matrix.createInverse().createTransformedShape(path); } catch(java.awt.geom.NoninvertibleTransformException failure) { throw PdfObjects.unsupported(); }
            var dash=graphics.getLineDashPattern().getDashArray();
            var stroke=new BasicStroke(graphics.getLineWidth(),graphics.getLineCap(),graphics.getLineJoin(),graphics.getMiterLimit(),dash.length==0?null:dash,graphics.getLineDashPattern().getPhase());
            draw(new Area(matrix.createTransformedShape(stroke.createStrokedShape(local))),rgb(graphics.getStrokingColor()));
        }
        void draw(Area area,int rgb) throws IOException { area.transform(view.transform()); area.intersect(clip()); area.subtract(removed); paint(area,rgb); }
        void paint(Area area,int rgb) throws IOException {
            flushText();if(area.isEmpty()) { return; } state.node(); write("q"); color(rgb);writePath(area);write("f");write("Q");
        }
        void writePath(Area area) throws IOException {
            AffineTransform inverse;
            try {inverse=view.transform().createInverse();}catch(java.awt.geom.NoninvertibleTransformException failure){throw PdfObjects.invalid();}
            var iterator=area.getPathIterator(inverse); double[] coordinates=new double[6]; double currentX=0,currentY=0,startX=0,startY=0;
            while(!iterator.isDone()) {
                state.work(); int type=iterator.currentSegment(coordinates);
                switch(type) {
                    case PathIterator.SEG_MOVETO -> { numbers(java.util.Arrays.copyOf(coordinates,2),"m"); currentX=startX=coordinates[0]; currentY=startY=coordinates[1]; }
                    case PathIterator.SEG_LINETO -> { numbers(java.util.Arrays.copyOf(coordinates,2),"l"); currentX=coordinates[0]; currentY=coordinates[1]; }
                    case PathIterator.SEG_QUADTO -> { numbers(new double[]{currentX+2*(coordinates[0]-currentX)/3,currentY+2*(coordinates[1]-currentY)/3,coordinates[2]+2*(coordinates[0]-coordinates[2])/3,coordinates[3]+2*(coordinates[1]-coordinates[3])/3,coordinates[2],coordinates[3]},"c"); currentX=coordinates[2]; currentY=coordinates[3]; }
                    case PathIterator.SEG_CUBICTO -> { numbers(coordinates,"c"); currentX=coordinates[4]; currentY=coordinates[5]; }
                    case PathIterator.SEG_CLOSE -> { write("h"); currentX=startX; currentY=startY; }
                    default -> throw PdfObjects.unsupported();
                }
                iterator.next();
            }
        }
        void color(int value) throws IOException { numbers(new double[]{(value>>16&255)/255d,(value>>8&255)/255d,(value&255)/255d},"rg"); }
        void numbers(double[] values,String operator) throws IOException { var tokens=new ArrayList<Object>(); for(double value:values) { if(!finite(value)) { throw PdfObjects.unsupported(); } tokens.add(new COSFloat((float)value)); } tokens.add(Operator.getOperator(operator)); writer.writeTokens(tokens); }
        void write(String operator) throws IOException { writer.writeTokens(Operator.getOperator(operator)); }
        @Override public void shadingFill(COSName name) throws IOException { throw PdfObjects.unsupported(); }
    }
}
