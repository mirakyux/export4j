// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType3CharProc;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.RenderDestination;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

/** Task-confined page rasterization. CropBox, viewer rotation and UserUnit define output geometry.
 * DPI is physical pixels per inch. PNG supports transparency; JPEG uses an explicit opaque RGB background.
 * Fonts must be valid embedded programs or use an explicit supported source-font mapping;
 * no system discovery or implicit substitution occurs.
 */
public final class PdfRasterRequest {
    public enum Format { PNG, JPEG }
    private final PdfAdapter adapter;
    private final PdfSelection selection;
    private final PdfLimits limits;
    private double dpi = 144;
    private Format format = Format.PNG;
    private int background = 0xFFFFFF;
    private boolean transparent;
    private float quality = .9f;
    private ProcessingPolicy policy = ProcessingPolicy.STRICT;
    private ExecutionOptions execution = ExecutionOptions.DEFAULT;
    private final Map<String, PdfFont> sourceFonts = new HashMap<>();
    PdfRasterRequest(PdfAdapter adapter, PdfSelection selection, PdfLimits limits) { this.adapter = adapter; this.selection = selection; this.limits = limits; }
    public PdfRasterRequest dpi(double value) { if (!Double.isFinite(value) || value <= 0 || value > 1200) { throw new IllegalArgumentException("DPI must be within (0,1200]"); } dpi = value; return this; }
    public PdfRasterRequest format(Format value) { format = Objects.requireNonNull(value, "format"); return this; }
    public PdfRasterRequest background(int rgb) { if (rgb < 0 || rgb > 0xFFFFFF) { throw new IllegalArgumentException("invalid RGB background"); } background = rgb; return this; }
    public PdfRasterRequest transparent(boolean value) { transparent = value; return this; }
    public PdfRasterRequest jpegQuality(double value) { if (!Double.isFinite(value) || value <= 0 || value > 1) { throw new IllegalArgumentException("JPEG quality must be within (0,1]"); } quality = (float) value; return this; }
    public PdfRasterRequest policy(ProcessingPolicy value) { policy = Objects.requireNonNull(value, "policy"); return this; }
    public PdfRasterRequest execution(ExecutionOptions value) { execution = Objects.requireNonNull(value, "execution"); return this; }
    /** Maps an exact PDF BaseFont name to explicit static TrueType bytes for this request only.
     * Supports unembedded simple Type1/TrueType with a supported single-byte encoding (including
     * dictionary Differences) and source widths, plus horizontal Type0 fonts with Identity-H or a
     * bounded embedded CID CMap and an unambiguous single-code-point ToUnicode mapping.
     * Latin standard-14 fonts may use their fixed AFM metrics. Source codes and widths remain intact;
     * embedded fonts are never replaced or repaired.
     * Actual substitutions require RENDER_FONT_MAPPING/DEGRADE. The request and mapping are task confined.
     */
    public PdfRasterRequest sourceFont(String exactBaseFontName, PdfFont font) {
        PdfSourceFonts.register(sourceFonts, exactBaseFontName, font, limits); return this;
    }
    public PreparedPdfImages prepare() throws IOException {
        var targetFormat = format; var strategy = policy; var control = execution; double resolution = dpi;
        var fontMappings = Map.copyOf(sourceFonts);
        int rgb = background; boolean alpha = transparent; float jpegQuality = quality;
        if (alpha && targetFormat == Format.JPEG) { throw new IllegalArgumentException("JPEG cannot preserve transparency"); }
        return ExecutionContext.execute(control, () -> {
            var preflight = adapter.preflight(true);
            try (var document = adapter.open(selection.input(), preflight)) {
                if (!document.getCurrentAccessPermission().canExtractContent()) { throw preflight.failure(PdfOperationException.Code.PERMISSION_DENIED); }
                var issues = new ArrayList<ProcessingIssue>(); String name = targetFormat.name();
                int count = selection.includesAllPages() ? document.getNumberOfPages() : selection.indices().size();
                if (count == 0 || count > limits.maxPages()) { throw preflight.failure(PdfOperationException.Code.PAGE_LIMIT); }
                var sourcePrograms = new PdfSourceFonts(document, fontMappings, limits, strategy, issues, name, selection.input().byteSize());
                for (int offset = 0; offset < count; offset++) {
                    int index = selection.includesAllPages() ? offset : selection.indices().get(offset);
                    if (index < 0 || index >= document.getNumberOfPages()) { throw new PdfOperationException(PdfOperationException.Code.INVALID_SELECTION, 0, index); }
                    sourcePrograms.apply(document.getPage(index), index);
                }
                sourcePrograms.validate();
                var snapshot = PdfObjects.inspect(document, limits); var images = new ArrayList<PreparedPdfImages.Image>();
                rasterLosses(document, snapshot, strategy, issues, name);
                var structures = preflight.structures();
                if (structures != null && structures.hasLayers()) { PdfImageSupport.loss(strategy, issues, name, "PDF_LAYERS", "RASTERIZE", ProcessingPolicy.Treatment.DEGRADE, "DEFAULT_VIEW_BECOMES_PIXELS_NO_LAYER_TOGGLE", -1); }
                if (structures != null && structures.hasTags()) { PdfImageSupport.loss(strategy, issues, name, "PDF_TAGS", "RASTERIZE", ProcessingPolicy.Treatment.DISCARD, "TAG_TREE_AND_ACCESSIBILITY_NOT_RETAINED", -1); }
                if (targetFormat == Format.JPEG) { PdfImageSupport.loss(strategy, issues, name, "JPEG_ENCODING", "RASTERIZE", ProcessingPolicy.Treatment.DEGRADE, "LOSSY_JPEG_PIXELS", -1); }
                var renderer = new CheckedRenderer(document, limits, sourcePrograms); long pixels = 0; long encoded = 0;
                for (int offset = 0; offset < count; offset++) {
                    int index = selection.includesAllPages() ? offset : selection.indices().get(offset);
                    if (index < 0 || index >= document.getNumberOfPages()) { throw new PdfOperationException(PdfOperationException.Code.INVALID_SELECTION, 0, index); }
                    ExecutionContext.progress("PDF_RASTERIZE", "", index, offset, count);
                    var page = document.getPage(index); String resourceIssue = new PdfRasterResources(limits).check(page);
                    var form = document.getDocumentCatalog().getAcroForm(null);
                    if (form != null && form.getNeedAppearances()) { resourceIssue = "FORM_APPEARANCE"; }
                    if (resourceIssue != null) { PdfImageSupport.loss(strategy, issues, name, resourceIssue, "RASTERIZE", ProcessingPolicy.Treatment.SKIP_BLOCK, "PAGE_NOT_RENDERED", index); continue; }
                    float scale = (float) (resolution / 72 * page.getUserUnit());
                    var crop = page.getCropBox();
                    long width = Math.max(1, (long) Math.floor(crop.getWidth() * scale)); long height = Math.max(1, (long) Math.floor(crop.getHeight() * scale));
                    if (width > Integer.MAX_VALUE || height > Integer.MAX_VALUE || width > (limits.maxImagePixels() - pixels) / height) { throw new PdfOperationException(PdfOperationException.Code.IMAGE_LIMIT, 0, index); }
                    pixels += width * height;
                    BufferedImage raster = renderer.renderImage(index, scale, ImageType.ARGB, RenderDestination.VIEW);
                    BufferedImage image = raster;
                    try {
                        if (!alpha) {
                            image = new BufferedImage(raster.getWidth(), raster.getHeight(), BufferedImage.TYPE_INT_RGB); var graphics = image.createGraphics();
                            try { graphics.setColor(new Color(rgb)); graphics.fillRect(0, 0, image.getWidth(), image.getHeight()); graphics.drawImage(raster, 0, 0, null); } finally { graphics.dispose(); }
                        }
                        var bytes = PdfImageSupport.encode(image, targetFormat == Format.PNG ? "png" : "jpeg", jpegQuality, limits.maxOutputBytes() - encoded, resolution); encoded += bytes.length;
                        images.add(new PreparedPdfImages.Image(String.format(Locale.ROOT, "page-%04d.%s", offset + 1, targetFormat == Format.PNG ? "png" : "jpg"),
                                targetFormat == Format.PNG ? "image/png" : "image/jpeg", image.getWidth(), image.getHeight(), List.of(index), bytes, control));
                    } finally { if (image != raster) { image.flush(); } raster.flush(); }
                }
                return new PreparedPdfImages(images, issues, limits, control);
            }
        });
    }
    private static void rasterLosses(PDDocument document, PdfObjects.Snapshot snapshot, ProcessingPolicy policy, List<ProcessingIssue> issues, String format) {
        if (!snapshot.fields().isEmpty()) { PdfImageSupport.loss(policy, issues, format, "FORMS", "RASTERIZE", ProcessingPolicy.Treatment.DEGRADE, "FIELD_EDITABILITY_AND_VALUES_BECOME_PIXELS", -1); }
        if (snapshot.annotations().stream().anyMatch(annotation -> annotation.type().equals("LINK"))) { PdfImageSupport.loss(policy, issues, format, "LINKS", "RASTERIZE", ProcessingPolicy.Treatment.DISCARD, "LINK_DESTINATIONS_NOT_RETAINED", -1); }
        if (snapshot.annotations().stream().anyMatch(annotation -> annotation.type().equals("TEXT"))) { PdfImageSupport.loss(policy, issues, format, "COMMENTS", "RASTERIZE", ProcessingPolicy.Treatment.DEGRADE, "COMMENT_CONTENT_AND_EDITABILITY_NOT_RETAINED", -1); }
        if (!snapshot.bookmarks().isEmpty()) { PdfImageSupport.loss(policy, issues, format, "BOOKMARKS", "RASTERIZE", ProcessingPolicy.Treatment.DISCARD, "BOOKMARKS_NOT_RETAINED", -1); }
        if (!snapshot.attachments().isEmpty()) { PdfImageSupport.loss(policy, issues, format, "ATTACHMENTS", "RASTERIZE", ProcessingPolicy.Treatment.DISCARD, "ATTACHMENTS_NOT_RETAINED", -1); }
        if (document.getDocumentInformation().getCOSObject().size() != 0 || document.getDocumentCatalog().getCOSObject().containsKey(COSName.METADATA)) {
            PdfImageSupport.loss(policy, issues, format, "DOCUMENT_METADATA", "RASTERIZE", ProcessingPolicy.Treatment.DISCARD, "SOURCE_METADATA_NOT_RETAINED", -1);
        }
    }
    static final class CheckedRenderer extends PDFRenderer {
        private final PdfLimits limits;
        private final PdfSourceFonts sourceFonts;
        private long operations;
        private long glyphs;
        long operations() { return operations; }
        long glyphs() { return glyphs; }
        CheckedRenderer(PDDocument document, PdfLimits limits, PdfSourceFonts sourceFonts) { super(document); this.limits = limits; this.sourceFonts = sourceFonts; setSubsamplingAllowed(false); }
        @Override protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
            return new PageDrawer(parameters) {
                private final Set<COSStream> active = Collections.newSetFromMap(new IdentityHashMap<>());
                private int streamDepth;
                private int graphicsDepth;
                @Override protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
                    ExecutionContext.checkpoint();
                    if (++operations > limits.maxContentOperators()) { throw new PdfOperationException(PdfOperationException.Code.OPERATOR_LIMIT, 0, -1); }
                    if (operator.getName().equals("Tf") && (operands.size() != 2 || !(operands.getFirst() instanceof COSName key)
                            || getResources() == null || getResources().getCOSObject().getCOSDictionary(COSName.FONT) == null
                            || !getResources().getCOSObject().getCOSDictionary(COSName.FONT).containsKey(key))) { throw PdfObjects.invalid(); }
                    if (operator.getName().equals("q")) {
                        if (streamDepth + graphicsDepth + 1 > limits.maxDepth()) { throw new PdfOperationException(PdfOperationException.Code.DEPTH_LIMIT, 0, -1); }
                        graphicsDepth++;
                    } else if (operator.getName().equals("Q")) { graphicsDepth--; }
                    super.processOperator(operator, operands);
                }
                private int enter(COSStream stream) throws IOException {
                    ExecutionContext.checkpoint();
                    if (active.contains(stream)) { throw PdfObjects.invalid(); }
                    if (streamDepth + graphicsDepth + 1 > limits.maxDepth()) { throw new PdfOperationException(PdfOperationException.Code.DEPTH_LIMIT, 0, -1); }
                    active.add(stream); streamDepth++; return graphicsDepth;
                }
                private void leave(COSStream stream, int parentGraphicsDepth) {
                    graphicsDepth = parentGraphicsDepth; streamDepth--; active.remove(stream);
                }
                @Override public void showForm(PDFormXObject form) throws IOException {
                    int parent = enter(form.getCOSObject());
                    try { super.showForm(form); } finally { leave(form.getCOSObject(), parent); }
                }
                @Override protected void processTransparencyGroup(PDTransparencyGroup group) throws IOException {
                    int parent = enter(group.getCOSObject());
                    try { super.processTransparencyGroup(group); } finally { leave(group.getCOSObject(), parent); }
                }
                @Override protected void processType3Stream(PDType3CharProc procedure, Matrix matrix) throws IOException {
                    int parent = enter(procedure.getCOSObject());
                    try { super.processType3Stream(procedure, matrix); } finally { leave(procedure.getCOSObject(), parent); }
                }
                @Override protected void showText(byte[] string) throws IOException {
                    if (getGraphicsState().getTextState().getFont() == null) { throw PdfObjects.invalid(); } super.showText(string);
                }
                @Override protected void showFontGlyph(Matrix matrix, PDFont font, int code, Vector displacement) throws IOException {
                    ExecutionContext.checkpoint();
                    if (++glyphs > limits.maxTextCharacters()) { throw new PdfOperationException(PdfOperationException.Code.TEXT_LIMIT, 0, -1); }
                    sourceFonts.requireGlyph(font, code);
                    super.showFontGlyph(matrix, font, code, displacement);
                }
                @Override protected void showType3Glyph(Matrix matrix, PDType3Font font, int code, Vector displacement) throws IOException {
                    ExecutionContext.checkpoint();
                    if (++glyphs > limits.maxTextCharacters()) { throw new PdfOperationException(PdfOperationException.Code.TEXT_LIMIT, 0, -1); }
                    sourceFonts.requireGlyph(font, code);
                    super.showType3Glyph(matrix, font, code, displacement);
                }
                @Override protected void operatorException(Operator operator, List<COSBase> operands, IOException exception) throws IOException { throw exception; }
            };
        }
    }
}
