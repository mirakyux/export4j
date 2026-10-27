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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.RenderDestination;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

/** Task-confined page rasterization. CropBox, viewer rotation and UserUnit define output geometry.
 * DPI is physical pixels per inch. PNG supports transparency; JPEG uses an explicit opaque RGB background.
 * Fonts must be valid, embedded static TrueType or CFF programs; no system discovery or substitution occurs.
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
    PdfRasterRequest(PdfAdapter adapter, PdfSelection selection, PdfLimits limits) { this.adapter = adapter; this.selection = selection; this.limits = limits; }
    public PdfRasterRequest dpi(double value) { if (!Double.isFinite(value) || value <= 0 || value > 1200) { throw new IllegalArgumentException("DPI must be within (0,1200]"); } dpi = value; return this; }
    public PdfRasterRequest format(Format value) { format = Objects.requireNonNull(value, "format"); return this; }
    public PdfRasterRequest background(int rgb) { if (rgb < 0 || rgb > 0xFFFFFF) { throw new IllegalArgumentException("invalid RGB background"); } background = rgb; return this; }
    public PdfRasterRequest transparent(boolean value) { transparent = value; return this; }
    public PdfRasterRequest jpegQuality(double value) { if (!Double.isFinite(value) || value <= 0 || value > 1) { throw new IllegalArgumentException("JPEG quality must be within (0,1]"); } quality = (float) value; return this; }
    public PdfRasterRequest policy(ProcessingPolicy value) { policy = Objects.requireNonNull(value, "policy"); return this; }
    public PdfRasterRequest execution(ExecutionOptions value) { execution = Objects.requireNonNull(value, "execution"); return this; }
    public PreparedPdfImages prepare() throws IOException {
        var targetFormat = format; var strategy = policy; var control = execution; double resolution = dpi;
        int rgb = background; boolean alpha = transparent; float jpegQuality = quality;
        if (alpha && targetFormat == Format.JPEG) { throw new IllegalArgumentException("JPEG cannot preserve transparency"); }
        return ExecutionContext.execute(control, () -> {
            var preflight = new PdfPreflight(limits, true);
            try (var document = adapter.open(selection.input(), preflight)) {
                if (!document.getCurrentAccessPermission().canExtractContent()) { throw preflight.failure(PdfOperationException.Code.PERMISSION_DENIED); }
                var snapshot = PdfObjects.inspect(document, limits); var issues = new ArrayList<ProcessingIssue>();
                String name = targetFormat.name(); var images = new ArrayList<PreparedPdfImages.Image>();
                rasterLosses(document, snapshot, strategy, issues, name);
                if (targetFormat == Format.JPEG) { PdfImageSupport.loss(strategy, issues, name, "JPEG_ENCODING", "RASTERIZE", ProcessingPolicy.Treatment.DEGRADE, "LOSSY_JPEG_PIXELS", -1); }
                var renderer = new CheckedRenderer(document, limits); long pixels = 0; long encoded = 0;
                int count = selection.includesAllPages() ? document.getNumberOfPages() : selection.indices().size();
                if (count == 0 || count > limits.maxPages()) { throw preflight.failure(PdfOperationException.Code.PAGE_LIMIT); }
                for (int offset = 0; offset < count; offset++) {
                    int index = selection.includesAllPages() ? offset : selection.indices().get(offset);
                    if (index < 0 || index >= document.getNumberOfPages()) { throw new PdfOperationException(PdfOperationException.Code.INVALID_SELECTION, 0, index); }
                    ExecutionContext.progress("PDF_RASTERIZE", "", index, offset, count);
                    var page = document.getPage(index); String resourceIssue = new PdfRasterResources().check(page);
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
    private static final class CheckedRenderer extends PDFRenderer {
        private final PdfLimits limits;
        private long operations;
        private long glyphs;
        CheckedRenderer(PDDocument document, PdfLimits limits) { super(document); this.limits = limits; setSubsamplingAllowed(false); }
        @Override protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
            return new PageDrawer(parameters) {
                @Override protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
                    ExecutionContext.checkpoint();
                    if (++operations > limits.maxContentOperators()) { throw new PdfOperationException(PdfOperationException.Code.OPERATOR_LIMIT, 0, -1); }
                    if (operator.getName().equals("Tf") && (operands.size() != 2 || !(operands.getFirst() instanceof COSName key)
                            || getResources() == null || getResources().getCOSObject().getCOSDictionary(COSName.FONT) == null
                            || !getResources().getCOSObject().getCOSDictionary(COSName.FONT).containsKey(key))) { throw PdfObjects.invalid(); }
                    super.processOperator(operator, operands);
                }
                @Override protected void showText(byte[] string) throws IOException {
                    if (getGraphicsState().getTextState().getFont() == null) { throw PdfObjects.invalid(); } super.showText(string);
                }
                @Override protected void showFontGlyph(Matrix matrix, PDFont font, int code, Vector displacement) throws IOException {
                    ExecutionContext.checkpoint();
                    if (++glyphs > limits.maxTextCharacters()) { throw new PdfOperationException(PdfOperationException.Code.TEXT_LIMIT, 0, -1); }
                    PdfRasterResources.requireGlyph(font, code);
                    super.showFontGlyph(matrix, font, code, displacement);
                }
                @Override protected void operatorException(Operator operator, List<COSBase> operands, IOException exception) throws IOException { throw exception; }
            };
        }
    }
}
