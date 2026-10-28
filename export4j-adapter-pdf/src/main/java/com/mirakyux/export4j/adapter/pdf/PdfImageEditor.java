// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/** Task-confined replacement of individual image Do invocations. Indices are zero based and
 * follow actual depth-first drawing order, including repeated and nested Forms. Replacement
 * pixels occupy the original image's unit square; original transformations and clipping remain.
 * A changed Form invocation receives a private copy, isolating all other uses and pages.
 */
public final class PdfImageEditor {
    /** One actual drawing, rather than one shared resource. Pixel sizes describe the current source. */
    public record Instance(int pageIndex, int occurrence, int pixelWidth, int pixelHeight, boolean masked) { }
    private record Edit(int page, int occurrence, ImageResource image) { }
    private record Candidate(List<Object> tokens, PDResources resources, boolean changed) { }
    private final PdfAdapter adapter;
    private final PdfInput input;
    private final PdfLimits limits;
    private final List<Edit> edits = new ArrayList<>();
    private ProcessingPolicy policy = ProcessingPolicy.STRICT;
    private ExecutionOptions execution = ExecutionOptions.DEFAULT;

    PdfImageEditor(PdfAdapter adapter, PdfInput input, PdfLimits limits) { this.adapter = adapter; this.input = input; this.limits = limits; }

    /** Snapshots validated PNG/JPEG pixels. An image's own alpha replaces the source alpha;
     * discarding a source mask requires IMAGE_MASK / DISCARD. The occurrence is stable for
     * successive replacements because no drawing invocation is inserted or removed.
     */
    public PdfImageEditor replace(int pageIndex, int occurrence, ImageResource image) {
        if (pageIndex < 0 || occurrence < 0) { throw new IllegalArgumentException("image selection must be nonnegative"); }
        if (edits.size() >= limits.maxObjects()) { throw new IllegalArgumentException("image edit budget exceeded"); }
        edits.add(new Edit(pageIndex, occurrence, Objects.requireNonNull(image, "image"))); return this;
    }
    public PdfImageEditor policy(ProcessingPolicy value) { policy = Objects.requireNonNull(value, "policy"); return this; }
    public PdfImageEditor execution(ExecutionOptions value) { execution = Objects.requireNonNull(value, "execution"); return this; }

    /** Returns immutable actual-use metadata, without decoding image pixels or changing the input. */
    public List<Instance> instances() throws IOException {
        var control = execution;
        return ExecutionContext.execute(control, () -> {
            try (var document = adapter.openForEdit(input, adapter.preflight(false))) {
                var state = new State(document, ProcessingPolicy.STRICT);
                var found = new ArrayList<Instance>();
                for (int index = 0; index < document.getNumberOfPages(); index++) {
                    state.occurrence = 0; state.pageIndex = index; state.found = found;
                    var page = document.getPage(index);
                    state.walk(page.getResources(), state.forms.page(page), null, 0, false);
                }
                return List.copyOf(found);
            }
        });
    }

    /** Prepares and validates the entire candidate before any caller output is changed. */
    public PreparedPdf prepare() throws IOException {
        var operations = List.copyOf(edits); var strategy = policy; var control = execution;
        return ExecutionContext.execute(control, () -> {
            try (var document = adapter.openForEdit(input, adapter.preflight(false))) {
                var state = new State(document, strategy);
                if (document.isEncrypted()) { state.loss("ENCRYPTION_REMOVED", ProcessingPolicy.Treatment.DEGRADE, "OUTPUT_IS_UNENCRYPTED"); }
                int changes = 0;
                for (var edit : operations) {
                    if (edit.page() >= document.getNumberOfPages()) { throw new PdfOperationException(PdfOperationException.Code.INVALID_SELECTION, 0, edit.page()); }
                    state.pageIndex = edit.page(); state.occurrence = 0; state.selected = false;
                    state.reserve(edit.image());
                    var page = document.getPage(edit.page());
                    var candidate = state.walk(page.getResources(), state.forms.page(page), edit, 0, false);
                    if (!state.selected) { throw new PdfOperationException(PdfOperationException.Code.INVALID_SELECTION, 0, edit.page()); }
                    if (candidate.changed()) { page.setContents(state.encode(candidate.tokens())); page.setResources(candidate.resources()); changes++; }
                }
                adapter.preflight(false).inspect(document);
                return PreparedPdf.encode(document, changes, state.issues, limits, control);
            }
        });
    }

    private final class State {
        final PDDocument document; final ProcessingPolicy strategy;
        final PdfTextForms forms = new PdfTextForms(limits);
        final List<ProcessingIssue> issues = new ArrayList<>();
        List<Instance> found;
        long bytes = input.byteSize(), pixels;
        int occurrence, pageIndex;
        boolean selected;
        State(PDDocument document, ProcessingPolicy strategy) { this.document = document; this.strategy = strategy; }
        void reserve(ImageResource image) throws IOException {
            if (image.byteSize() > limits.maxTotalInputBytes() - bytes) { throw new PdfOperationException(PdfOperationException.Code.INPUT_LIMIT, 0, pageIndex); }
            long size = (long) image.pixelWidth() * image.pixelHeight();
            if (size > limits.maxImagePixels() - pixels) { throw new PdfOperationException(PdfOperationException.Code.IMAGE_LIMIT, 0, pageIndex); }
            bytes += image.byteSize(); pixels += size;
        }
        void loss(String feature, ProcessingPolicy.Treatment treatment, String effect) {
            PdfImageSupport.loss(strategy, issues, "PDF", feature, "REPLACE_IMAGE", treatment, effect, pageIndex);
        }
        Candidate walk(PDResources source, List<Object> tokens, Edit edit, int depth, boolean tagged) throws IOException {
            if (depth > limits.maxDepth()) { throw new PdfOperationException(PdfOperationException.Code.DEPTH_LIMIT, 0, pageIndex); }
            var resources = copy(source); var result = new ArrayList<Object>(); var operands = new ArrayList<COSBase>();
            boolean changed = false; int stackDepth = 0;
            for (var token : tokens) {
                ExecutionContext.checkpoint();
                if (!(token instanceof Operator operator)) { operands.add((COSBase) token); continue; }
                forms.operator(true);
                if (operator.getName().equals("q")) {
                    if (!operands.isEmpty()) { throw PdfObjects.invalid(); }
                    if (depth + ++stackDepth > limits.maxDepth()) { throw new PdfOperationException(PdfOperationException.Code.DEPTH_LIMIT, 0, pageIndex); }
                } else if (operator.getName().equals("Q")) {
                    if (!operands.isEmpty() || --stackDepth < 0) { throw PdfObjects.invalid(); }
                }
                if (operator.getName().equals("Do")) {
                    var form = forms.form(source, operands); var name = (COSName) operands.getFirst();
                    if (form != null) {
                        forms.supported(form); // Validate raw geometry even when no replacement selects it.
                        forms.enter(form, depth + stackDepth + 1);
                        Candidate child;
                        try { child = walk(forms.resources(form, source), forms.tokens(form), edit, depth + stackDepth + 1,
                                tagged || form.getCOSObject().containsKey(COSName.STRUCT_PARENTS) || form.getCOSObject().containsKey(COSName.STRUCT_PARENT)); }
                        finally { forms.leave(form); }
                        if (child.changed()) {
                            var clone = new PDFormXObject(encode(child.tokens()).getCOSObject());
                            for (var entry : form.getCOSObject().entrySet()) {
                                if (!Set.of(COSName.LENGTH, COSName.FILTER, COSName.DECODE_PARMS, COSName.RESOURCES).contains(entry.getKey())) { clone.getCOSObject().setItem(entry.getKey(), entry.getValue()); }
                            }
                            clone.setResources(child.resources()); operands.set(0, resources.add(clone)); changed = true;
                        }
                    } else {
                        if (!(source.getXObject(name) instanceof PDImageXObject image)) { throw PdfObjects.invalid(); }
                        boolean masked = image.isStencil() || image.getCOSObject().containsKey(COSName.MASK) || image.getCOSObject().containsKey(COSName.SMASK);
                        int index = occurrence++;
                        if (index >= limits.maxObjects()) { throw new PdfOperationException(PdfOperationException.Code.OBJECT_LIMIT, 0, pageIndex); }
                        if (found != null) {
                            if (found.size() >= limits.maxObjects()) { throw new PdfOperationException(PdfOperationException.Code.OBJECT_LIMIT, 0, pageIndex); }
                            found.add(new Instance(pageIndex, index, image.getWidth(), image.getHeight(), masked));
                        }
                        if (edit != null && index == edit.occurrence()) {
                            selected = true;
                            if (tagged || image.getCOSObject().containsKey(COSName.STRUCT_PARENT)) {
                                loss("IMAGE_STRUCTURE", ProcessingPolicy.Treatment.SKIP_MATCH, "TAGGED_IMAGE_INVOCATION_UNCHANGED");
                            } else {
                                if (masked) { loss("IMAGE_MASK", ProcessingPolicy.Treatment.DISCARD, "SOURCE_MASK_REPLACED_BY_EXPLICIT_IMAGE_ALPHA"); }
                                var replacement = PDImageXObject.createFromByteArray(document, edit.image().bytes(), "explicit-instance");
                                if (image.getCOSObject().containsKey(COSName.OC)) {
                                    replacement.getCOSObject().setItem(COSName.OC, image.getCOSObject().getItem(COSName.OC));
                                }
                                operands.set(0, resources.add(replacement)); changed = true;
                            }
                        }
                    }
                }
                result.addAll(operands); result.add(operator); operands.clear();
            }
            if (!operands.isEmpty() || stackDepth != 0) { throw PdfObjects.invalid(); }
            return new Candidate(List.copyOf(result), resources, changed);
        }
        PDStream encode(List<Object> tokens) throws IOException {
            var bytes = new PdfImageSupport.Bytes(limits.maxDecodedStreamBytes()); new ContentStreamWriter(bytes).writeTokens(tokens);
            var stream = new PDStream(document);
            try (var output = stream.createOutputStream(COSName.FLATE_DECODE)) { output.write(bytes.data()); }
            return stream;
        }
    }
    private static PDResources copy(PDResources source) {
        var dictionary = source == null ? new COSDictionary() : new COSDictionary(source.getCOSObject());
        var xobjects = dictionary.getCOSDictionary(COSName.XOBJECT);
        if (xobjects != null) { dictionary.setItem(COSName.XOBJECT, new COSDictionary(xobjects)); }
        return new PDResources(dictionary);
    }
}
