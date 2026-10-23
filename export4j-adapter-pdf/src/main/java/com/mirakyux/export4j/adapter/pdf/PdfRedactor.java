// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;

/** Raster redaction into a fresh image-only PDF. Source objects, original streams and document
 * history are never copied. Regions use the rotated CropBox view, top-left origin, physical pt.
 * All text/vector editability is lost, including outside regions. Callers must identify every
 * sensitive visible occurrence; this is not automatic discovery or OCR. Task confined.
 */
public final class PdfRedactor {
    private record Area(int page, double x, double y, double width, double height) { }
    private record Size(double width, double height) { }
    private final PdfAdapter adapter;
    private final PdfInput input;
    private final PdfLimits limits;
    private final List<Area> areas = new ArrayList<>();
    private double dpi = 144;
    private int fill;
    private ProcessingPolicy policy = ProcessingPolicy.STRICT;
    private ExecutionOptions execution = ExecutionOptions.DEFAULT;
    PdfRedactor(PdfAdapter adapter, PdfInput input, PdfLimits limits) { this.adapter = adapter; this.input = input; this.limits = limits; }
    public PdfRedactor area(int page, double x, double y, double width, double height) {
        if (page < 0 || !finite(x) || !finite(y) || !finite(width) || !finite(height) || x < 0 || y < 0 || width <= 0 || height <= 0) { throw new IllegalArgumentException("invalid redaction region"); }
        if (areas.size() >= limits.maxObjects()) { throw new IllegalArgumentException("redaction region budget exceeded"); }
        areas.add(new Area(page, x, y, width, height)); return this;
    }
    public PdfRedactor dpi(double value) { if (!Double.isFinite(value) || value <= 0 || value > 1200) { throw new IllegalArgumentException("DPI must be within (0,1200]"); } dpi = value; return this; }
    public PdfRedactor fill(int rgb) { if (rgb < 0 || rgb > 0xFFFFFF) { throw new IllegalArgumentException("invalid RGB fill"); } fill = rgb; return this; }
    public PdfRedactor policy(ProcessingPolicy value) { policy = Objects.requireNonNull(value, "policy"); return this; }
    public PdfRedactor execution(ExecutionOptions value) { execution = Objects.requireNonNull(value, "execution"); return this; }
    public PreparedPdf prepare() throws IOException {
        var regions = List.copyOf(areas); var strategy = policy; var control = execution; double resolution = dpi; int color = fill;
        if (regions.isEmpty()) { throw new IllegalStateException("redaction requires at least one explicit region"); }
        return ExecutionContext.execute(control, () -> {
            var sizes = new ArrayList<Size>(); var issues = new ArrayList<ProcessingIssue>();
            try (var source = adapter.openForEdit(input, new PdfPreflight(limits, true))) {
                loss(strategy, issues, "RASTERIZATION", ProcessingPolicy.Treatment.DEGRADE, "ALL_TEXT_AND_VECTOR_CONTENT_BECOMES_RGB_PIXELS");
                if (source.isEncrypted()) { loss(strategy, issues, "ENCRYPTION_REMOVED", ProcessingPolicy.Treatment.DEGRADE, "OUTPUT_IS_UNENCRYPTED"); }
                for (var page : source.getPages()) {
                    var crop = page.getCropBox(); double width = crop.getWidth() * page.getUserUnit(); double height = crop.getHeight() * page.getUserUnit();
                    if (page.getRotation() % 180 != 0) { double saved = width; width = height; height = saved; }
                    sizes.add(new Size(width, height));
                    var media = page.getMediaBox();
                    if (media.getLowerLeftX() != crop.getLowerLeftX() || media.getLowerLeftY() != crop.getLowerLeftY()
                            || media.getWidth() != crop.getWidth() || media.getHeight() != crop.getHeight() || page.getRotation() != 0 || page.getUserUnit() != 1) {
                        loss(strategy, issues, "PAGE_GEOMETRY", ProcessingPolicy.Treatment.DEGRADE, "ROTATED_CROP_VIEW_NORMALIZED_TO_NEW_MEDIA_BOX");
                    }
                }
                for (var area : regions) {
                    if (area.page() >= sizes.size()) { throw new PdfOperationException(PdfOperationException.Code.INVALID_SELECTION, 0, area.page()); }
                    var size = sizes.get(area.page());
                    if (area.x() + area.width() > size.width() || area.y() + area.height() > size.height()) { throw new IllegalArgumentException("redaction region exceeds visible page"); }
                }
            }
            var rendered = adapter.render(input.allPages()).dpi(resolution).policy(strategy).execution(control).prepare();
            // Skipping any page cannot produce a successful sanitized candidate.
            if (rendered.images().size() != sizes.size() || !rendered.preview().skipped().isEmpty()) { throw PdfObjects.unsupported(); }
            issues.addAll(rendered.preview().issues());
            try (var target = new PDDocument()) {
                for (int index = 0; index < sizes.size(); index++) {
                    ExecutionContext.checkpoint(); var size = sizes.get(index); var image = rendered.images().get(index);
                    if (!image.sourcePages().equals(List.of(index))) { throw PdfObjects.invalid(); }
                    var raster = ImageIO.read(new ByteArrayInputStream(image.bytes())); if (raster == null) { throw PdfObjects.invalid(); }
                    try {
                        var graphics = raster.createGraphics();
                        try {
                            graphics.setColor(new Color(color));
                            for (var area : regions) {
                                ExecutionContext.checkpoint();
                                if (area.page() != index) { continue; }
                                int left = Math.max(0, (int) Math.floor(area.x() * raster.getWidth() / size.width()) - 1);
                                int top = Math.max(0, (int) Math.floor(area.y() * raster.getHeight() / size.height()) - 1);
                                int right = Math.min(raster.getWidth(), (int) Math.ceil((area.x() + area.width()) * raster.getWidth() / size.width()) + 1);
                                int bottom = Math.min(raster.getHeight(), (int) Math.ceil((area.y() + area.height()) * raster.getHeight() / size.height()) + 1);
                                graphics.fillRect(left, top, right - left, bottom - top);
                            }
                        } finally { graphics.dispose(); }
                        var page = new PDPage(new PDRectangle((float) size.width(), (float) size.height())); target.addPage(page);
                        var redacted = LosslessFactory.createFromImage(target, raster);
                        try (var content = new PDPageContentStream(target, page)) { content.drawImage(redacted, 0, 0, (float) size.width(), (float) size.height()); }
                    } finally { raster.flush(); }
                }
                new PdfPreflight(limits).inspect(target);
                return PreparedPdf.encode(target, regions.size(), issues, limits, control);
            }
        });
    }
    private static void loss(ProcessingPolicy policy, List<ProcessingIssue> issues, String feature, ProcessingPolicy.Treatment treatment, String effect) {
        PdfImageSupport.loss(policy, issues, "PDF", feature, "REDACT", treatment, effect, -1);
    }
    private static boolean finite(double value) { return Double.isFinite(value) && value <= 1_000_000; }
}
