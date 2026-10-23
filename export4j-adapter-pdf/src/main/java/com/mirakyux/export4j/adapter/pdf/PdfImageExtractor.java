// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.color.PDICCBased;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;

/** Task-confined extraction of unique reachable image resources as decoded PNG pixels.
 * Includes resources in forms, tiling patterns and current annotation appearances, even unused resources.
 * Does not infer placements or OCR. Soft masks stay alpha; standalone stencil color is context dependent.
 */
public final class PdfImageExtractor {
    private record Resource(PDImageXObject image, List<Integer> pages) { }
    private final PdfAdapter adapter;
    private final PdfSelection selection;
    private final PdfLimits limits;
    private ProcessingPolicy policy = ProcessingPolicy.STRICT;
    private ExecutionOptions execution = ExecutionOptions.DEFAULT;
    PdfImageExtractor(PdfAdapter adapter, PdfSelection selection, PdfLimits limits) { this.adapter = adapter; this.selection = selection; this.limits = limits; }
    public PdfImageExtractor policy(ProcessingPolicy value) { policy = Objects.requireNonNull(value, "policy"); return this; }
    public PdfImageExtractor execution(ExecutionOptions value) { execution = Objects.requireNonNull(value, "execution"); return this; }
    public PreparedPdfImages prepare() throws IOException {
        var strategy = policy; var control = execution;
        return ExecutionContext.execute(control, () -> {
            var preflight = new PdfPreflight(limits, true);
            try (var document = adapter.open(selection.input(), preflight)) {
                if (!document.getCurrentAccessPermission().canExtractContent()) { throw preflight.failure(PdfOperationException.Code.PERMISSION_DENIED); }
                PdfObjects.inspect(document, limits);
                var unique = new IdentityHashMap<COSStream, Resource>(); var ordered = new ArrayList<Resource>();
                int count = selection.includesAllPages() ? document.getNumberOfPages() : selection.indices().size();
                if (count == 0 || count > limits.maxPages()) { throw preflight.failure(PdfOperationException.Code.PAGE_LIMIT); }
                for (int offset = 0; offset < count; offset++) {
                    int index = selection.includesAllPages() ? offset : selection.indices().get(offset);
                    if (index < 0 || index >= document.getNumberOfPages()) { throw new PdfOperationException(PdfOperationException.Code.INVALID_SELECTION, 0, index); }
                    var page = document.getPage(index); Set<COSDictionary> seen = Collections.newSetFromMap(new IdentityHashMap<>());
                    collect(page.getResources(), index, unique, ordered, seen, 0);
                    for (var annotation : page.getAnnotations()) {
                        var appearance = annotation.getNormalAppearanceStream();
                        if (appearance != null) { collect(appearance.getResources(), index, unique, ordered, seen, 0); }
                    }
                }
                var issues = new ArrayList<ProcessingIssue>(); var images = new ArrayList<PreparedPdfImages.Image>(); long encoded = 0;
                for (var resource : ordered) {
                    ExecutionContext.checkpoint(); var image = resource.image(); int page = resource.pages().getFirst();
                    if (image.isStencil()) { PdfImageSupport.loss(strategy, issues, "PNG", "IMAGE_STENCIL", "EXTRACT_IMAGES", ProcessingPolicy.Treatment.SKIP_MATCH, "CONTEXT_DEPENDENT_STENCIL_NOT_EXTRACTED", page); continue; }
                    if (image.getBitsPerComponent() > 8) { PdfImageSupport.loss(strategy, issues, "PNG", "IMAGE_PRECISION", "EXTRACT_IMAGES", ProcessingPolicy.Treatment.DEGRADE, "IMAGE_CHANNELS_REDUCED_TO_8_BITS", page); }
                    if (image.getColorSpace().getNumberOfComponents() > 3 || image.getColorSpace() instanceof PDICCBased) {
                        PdfImageSupport.loss(strategy, issues, "PNG", "IMAGE_COLOR_SPACE", "EXTRACT_IMAGES", ProcessingPolicy.Treatment.DEGRADE, "IMAGE_CONVERTED_TO_RGB", page);
                    }
                    var raster = image.getImage();
                    try {
                        var bytes = PdfImageSupport.encode(raster, "png", 1, limits.maxOutputBytes() - encoded); encoded += bytes.length;
                        images.add(new PreparedPdfImages.Image(String.format(Locale.ROOT, "image-%04d.png", images.size() + 1), "image/png", raster.getWidth(), raster.getHeight(), resource.pages(), bytes, control));
                    } finally { raster.flush(); }
                }
                return new PreparedPdfImages(images, issues, limits, control);
            }
        });
    }
    private void collect(PDResources resources, int page, IdentityHashMap<COSStream, Resource> unique, List<Resource> ordered,
            Set<COSDictionary> seen, int depth) throws IOException {
        ExecutionContext.checkpoint();
        if (depth > limits.maxDepth()) { throw new PdfOperationException(PdfOperationException.Code.DEPTH_LIMIT, 0, page); }
        if (resources == null || !seen.add(resources.getCOSObject())) { return; }
        var names = new ArrayList<COSName>(); resources.getXObjectNames().forEach(names::add); names.sort(Comparator.comparing(COSName::getName));
        for (var name : names) {
            var object = resources.getXObject(name);
            if (object instanceof PDImageXObject image) {
                var resource = unique.get(image.getCOSObject());
                if (resource == null) { resource = new Resource(image, new ArrayList<>()); unique.put(image.getCOSObject(), resource); ordered.add(resource); }
                if (!resource.pages().contains(page)) { resource.pages().add(page); }
            } else if (object instanceof PDFormXObject form) { collect(form.getResources(), page, unique, ordered, seen, depth + 1); }
        }
        var patterns = new ArrayList<COSName>(); resources.getPatternNames().forEach(patterns::add); patterns.sort(Comparator.comparing(COSName::getName));
        for (var name : patterns) { if (resources.getPattern(name) instanceof PDTilingPattern pattern) { collect(pattern.getResources(), page, unique, ordered, seen, depth + 1); } }
        for (var name : resources.getExtGStateNames()) {
            var state = resources.getExtGState(name);
            if (state.getSoftMask() != null && state.getSoftMask().getGroup() != null) { collect(state.getSoftMask().getGroup().getResources(), page, unique, ordered, seen, depth + 1); }
        }
    }
}
