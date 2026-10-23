// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.multipdf.PDFCloneUtility;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.form.PDButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;

/** Task-confined passive-object page composition. Each source appears once; its selected pages may
 * be reordered but not duplicated. Explicit unique namespaces prefix root field and attachment names.
 * Removed navigation targets and excluded field widgets require real, reported treatments.
 */
public final class PdfObjectComposer {
    private record Source(PdfSelection selection, String namespace) { }
    private final PdfAdapter adapter;
    private final PdfLimits limits;
    private final List<Source> sources = new ArrayList<>();
    private ProcessingPolicy policy = ProcessingPolicy.STRICT;
    private ExecutionOptions execution = ExecutionOptions.DEFAULT;
    PdfObjectComposer(PdfAdapter adapter, PdfLimits limits) { this.adapter = adapter; this.limits = limits; }
    public PdfObjectComposer policy(ProcessingPolicy value) { policy = Objects.requireNonNull(value, "policy"); return this; }
    public PdfObjectComposer execution(ExecutionOptions value) { execution = Objects.requireNonNull(value, "execution"); return this; }
    /** Namespaces are ASCII identifiers, 1..40 characters; no automatic collision renaming occurs. */
    public PdfObjectComposer add(PdfSelection selection, String namespace) {
        Objects.requireNonNull(selection, "selection");
        if (!Objects.requireNonNull(namespace, "namespace").matches("[A-Za-z][A-Za-z0-9_]{0,39}")) { throw new IllegalArgumentException("invalid PDF source namespace"); }
        if (sources.size() >= limits.maxInputs()) { throw new IllegalArgumentException("PDF input count exceeded"); }
        if (sources.stream().anyMatch(source -> source.selection().input() == selection.input() || source.namespace().equals(namespace))) {
            throw new IllegalArgumentException("PDF source and namespace must each be unique");
        }
        sources.add(new Source(selection, namespace)); return this;
    }
    /** Fully validates every source and the composed target before encoding exact output. */
    public PreparedPdf prepare() throws IOException {
        var requests = List.copyOf(sources); var strategy = policy; var control = execution;
        if (requests.isEmpty()) { throw new IllegalArgumentException("PDF composition needs a source"); }
        long totalBytes = 0;
        for (var source : requests) {
            int bytes = source.selection().input().byteSize();
            if (bytes > limits.maxInputBytes() || bytes > limits.maxTotalInputBytes() - totalBytes) { throw new PdfOperationException(PdfOperationException.Code.INPUT_LIMIT, -1, -1); }
            totalBytes += bytes;
        }
        return ExecutionContext.execute(control, () -> {
            var issues = new ArrayList<ProcessingIssue>(); var budget = new PdfPreflight(limits, true);
            try (var opened = new Sources(); var target = new NoRepairDocument()) {
                for (int index = 0; index < requests.size(); index++) {
                    budget.inputIndex = index;
                    var document = adapter.openForEdit(requests.get(index).selection().input(), budget); opened.add(document);
                    PdfObjects.inspect(document, limits);
                    if (document.isEncrypted()) { issue(strategy, issues, index, "ENCRYPTION_REMOVED", ProcessingPolicy.Treatment.DEGRADE, "OUTPUT_IS_UNENCRYPTED"); }
                }
                var merger = new PDFMergerUtility(); merger.setIgnoreAcroFormErrors(false);
                int pages = 0;
                for (int index = 0; index < requests.size(); index++) {
                    ExecutionContext.checkpoint(); var source = requests.get(index); var document = opened.documents.get(index);
                    var selected = selected(document, source.selection());
                    if (selected.size() > limits.maxPages() - pages) { throw new PdfOperationException(PdfOperationException.Code.PAGE_LIMIT, index, -1); }
                    trim(document, selected, strategy, issues, index);
                    namespace(document, source.namespace());
                    var sourceCatalog = document.getDocumentCatalog().getCOSObject();
                    var cloner = new Cloner(target);
                    if (index == 0) {
                        target.setDocumentInformation(new PDDocumentInformation(cloner.cloneForNewDocument(document.getDocumentInformation().getCOSObject())));
                    } else if (document.getDocumentInformation().getCOSObject().size() != 0 || sourceCatalog.keySet().stream().anyMatch(PdfObjectComposer::setting)) {
                        issue(strategy, issues, index, "DOCUMENT_METADATA", ProcessingPolicy.Treatment.DISCARD, "LATER_SOURCE_DOCUMENT_SETTINGS_REMOVED");
                    }
                    document.setDocumentInformation(new PDDocumentInformation());
                    // The merge library owns page/field/destination remapping. Catalog settings follow an explicit first-source rule.
                    var settings = new COSDictionary();
                    for (var key : new ArrayList<>(sourceCatalog.keySet())) {
                        if (setting(key)) { if (index == 0) { settings.setItem(key, cloner.cloneForNewDocument(sourceCatalog.getItem(key))); } sourceCatalog.removeItem(key); }
                    }
                    merger.appendDocument(target, new NoRepairDocument(document));
                    for (var entry : settings.entrySet()) { target.getDocumentCatalog().getCOSObject().setItem(entry.getKey(), entry.getValue()); }
                    pages += selected.size();
                }
                var verification = new PdfPreflight(limits, true); verification.inspect(target); PdfObjects.inspect(target, limits);
                return PreparedPdf.encode(target, pages, issues, limits, control);
            }
        });
    }
    private static boolean setting(COSName key) {
        return !Set.of(COSName.TYPE, COSName.PAGES, COSName.VERSION, COSName.ACRO_FORM, COSName.OUTLINES, COSName.NAMES).contains(key);
    }
    private static List<PDPage> selected(PDDocument document, PdfSelection selection) throws IOException {
        var result = new ArrayList<PDPage>(); var seen = new HashSet<Integer>();
        int count = selection.includesAllPages() ? document.getNumberOfPages() : selection.indices().size();
        for (int offset = 0; offset < count; offset++) {
            int page = selection.includesAllPages() ? offset : selection.indices().get(offset);
            if (page < 0 || page >= document.getNumberOfPages()) { throw new PdfOperationException(PdfOperationException.Code.INVALID_SELECTION, 0, page); }
            if (!seen.add(page)) { throw new IllegalArgumentException("passive object composition cannot duplicate a source page"); }
            result.add(document.getPage(page));
        }
        if (result.isEmpty()) { throw new IllegalArgumentException("PDF page selection is empty"); }
        return result;
    }
    private void trim(PDDocument document, List<PDPage> selected, ProcessingPolicy strategy, List<ProcessingIssue> issues, int input) throws IOException {
        Set<COSDictionary> selectedPages = Collections.newSetFromMap(new IdentityHashMap<>());
        var widgets = Collections.newSetFromMap(new IdentityHashMap<COSDictionary, Boolean>());
        for (var page : selected) {
            selectedPages.add(page.getCOSObject());
            for (var annotation : page.getAnnotations()) { widgets.add(annotation.getCOSObject()); }
        }
        for (var page : selected) {
            var annotations = page.getAnnotations();
            for (int index = annotations.size() - 1; index >= 0; index--) {
                if (!(annotations.get(index) instanceof PDAnnotationLink link)) { continue; }
                var destination = link.getAction() instanceof PDActionGoTo action ? action.getDestination() : link.getDestination();
                if (destination == null) { continue; }
                int target = PdfObjects.destination(document, destination);
                if (!selectedPages.contains(document.getPage(target).getCOSObject())) {
                    issue(strategy, issues, input, "LINK_TARGET", ProcessingPolicy.Treatment.DISCARD, "LINK_TO_EXCLUDED_PAGE_REMOVED"); annotations.remove(index);
                } else { ((PDPageDestination) destination).setPage(document.getPage(target)); }
            }
        }
        var outline = document.getDocumentCatalog().getDocumentOutline();
        if (outline != null) { trimOutline(document, outline.getFirstChild(), selectedPages, strategy, issues, input); }
        var form = document.getDocumentCatalog().getAcroForm(null);
        if (form != null) {
            var roots = form.getCOSObject().getCOSArray(COSName.FIELDS); var retained = new COSArray();
            for (int index = 0; index < roots.size(); index++) {
                var root = (COSDictionary) roots.getObject(index);
                if (trimField(root, widgets, strategy, issues, input)) { retained.add(root); }
            }
            form.getCOSObject().setItem(COSName.FIELDS, retained);
            for (var field : form.getFieldTree()) {
                if (field instanceof PDButton button) {
                    String value = button.getValue(); String defaultValue = button.getDefaultValue();
                    if (value != null && !value.isEmpty() && !value.equals("Off") && !button.getOnValues().contains(value)) {
                        issue(strategy, issues, input, "FIELD_SELECTION_VALUE", ProcessingPolicy.Treatment.DEGRADE, "EXCLUDED_BUTTON_VALUE_RESET_TO_OFF"); button.setValue("Off");
                    }
                    if (defaultValue != null && !defaultValue.isEmpty() && !defaultValue.equals("Off") && !button.getOnValues().contains(defaultValue)) {
                        issue(strategy, issues, input, "FIELD_SELECTION_VALUE", ProcessingPolicy.Treatment.DEGRADE, "EXCLUDED_BUTTON_DEFAULT_RESET_TO_OFF"); button.setDefaultValue("Off");
                    }
                }
            }
            if (retained.size() == 0) { document.getDocumentCatalog().getCOSObject().removeItem(COSName.ACRO_FORM); }
        }
        // Resolve inherited geometry/resources before rebuilding the source's page tree in selected order.
        for (var page : selected) { page.setMediaBox(page.getMediaBox()); page.setCropBox(page.getCropBox()); page.setRotation(page.getRotation()); page.setResources(page.getResources()); }
        var root = document.getPages().getCOSObject(); var kids = new COSArray();
        for (var page : selected) { page.getCOSObject().setItem(COSName.PARENT, root); kids.add(page.getCOSObject()); }
        root.setItem(COSName.KIDS, kids); root.setInt(COSName.COUNT, selected.size());
    }
    private static void trimOutline(PDDocument document, PDOutlineItem item, Set<COSDictionary> pages,
            ProcessingPolicy strategy, List<ProcessingIssue> issues, int input) throws IOException {
        for (; item != null; item = item.getNextSibling()) {
            ExecutionContext.checkpoint();
            var destination = item.getAction() instanceof PDActionGoTo action ? action.getDestination() : item.getDestination();
            if (destination != null) {
                int target = PdfObjects.destination(document, destination);
                if (!pages.contains(document.getPage(target).getCOSObject())) {
                    issue(strategy, issues, input, "BOOKMARK_TARGET", ProcessingPolicy.Treatment.DEGRADE, "BOOKMARK_BECOMES_GROUP_WITHOUT_TARGET");
                    item.getCOSObject().removeItem(COSName.DEST); item.getCOSObject().removeItem(COSName.A);
                } else { ((PDPageDestination) destination).setPage(document.getPage(target)); }
            }
            trimOutline(document, item.getFirstChild(), pages, strategy, issues, input);
        }
    }
    private static boolean trimField(COSDictionary field, Set<COSDictionary> widgets, ProcessingPolicy strategy, List<ProcessingIssue> issues, int input) {
        ExecutionContext.checkpoint();
        if ("Widget".equals(field.getNameAsString(COSName.SUBTYPE))) {
            if (widgets.contains(field)) { return true; }
            issue(strategy, issues, input, "FIELD_WIDGET_SELECTION", ProcessingPolicy.Treatment.DISCARD, "EXCLUDED_FIELD_WIDGET_REMOVED"); return false;
        }
        var kids = field.getCOSArray(COSName.KIDS);
        if (kids == null) { issue(strategy, issues, input, "FIELD_WIDGET_SELECTION", ProcessingPolicy.Treatment.DISCARD, "FIELD_WITHOUT_SELECTED_WIDGET_REMOVED"); return false; }
        var retained = new COSArray();
        for (int index = 0; index < kids.size(); index++) {
            var child = (COSDictionary) kids.getObject(index); if (trimField(child, widgets, strategy, issues, input)) { retained.add(child); }
        }
        field.setItem(COSName.KIDS, retained); return retained.size() != 0;
    }
    private void namespace(PDDocument document, String namespace) throws IOException {
        var form = document.getDocumentCatalog().getAcroForm(null);
        if (form != null) { for (var field : form.getFields()) { field.setPartialName(namespace + "_" + field.getPartialName()); } }
        var files = PdfObjects.files(document, limits);
        if (!files.isEmpty()) {
            var renamed = new TreeMap<String, PDComplexFileSpecification>();
            for (var entry : files.entrySet()) {
                String name = namespace + "_" + entry.getKey(); var file = entry.getValue(); file.setFile(name); file.setFileUnicode(name); renamed.put(name, file);
            }
            var names = new PDDocumentNameDictionary(document.getDocumentCatalog()); var tree = new PDEmbeddedFilesNameTreeNode(); tree.setNames(renamed); names.setEmbeddedFiles(tree); document.getDocumentCatalog().setNames(names);
        }
    }
    private static void issue(ProcessingPolicy strategy, List<ProcessingIssue> issues, int input, String feature, ProcessingPolicy.Treatment treatment, String effect) {
        var rule = strategy.resolve("PDF", feature, "COMPOSE");
        var issue = new ProcessingIssue("EXPORT", "PDF", "PDF", feature, "COMPOSE",
                new ProcessingIssue.Location("", "PDF_INPUT_" + input, "", List.of(), -1, -1),
                "page composition requires the declared treatment", rule.action(), rule.treatment(), List.of(effect));
        if (rule.action() == ProcessingPolicy.Action.ERROR) { throw new ProcessingException(issue); }
        if (rule.treatment() != treatment) { throw new IllegalArgumentException("unsupported PDF composition treatment"); }
        issues.add(issue);
    }
    private static final class Cloner extends PDFCloneUtility { Cloner(PDDocument target) { super(target); } }
    /** PDFBox's default getAcroForm() repairs resources and may regenerate appearances using system
     * fonts. Composition must clone the validated original. A source view borrows its COS document;
     * font handles created through that view are registered with the owning source for cleanup.
     */
    private static final class NoRepairDocument extends PDDocument {
        private final PDDocument owner;
        private PDDocumentCatalog catalog;
        NoRepairDocument() { owner = null; }
        NoRepairDocument(PDDocument owner) { super(owner.getDocument(), null, owner.getCurrentAccessPermission()); this.owner = owner; }
        @Override public PDDocumentCatalog getDocumentCatalog() {
            if (catalog == null) { catalog = new NoRepairCatalog(this, super.getDocumentCatalog().getCOSObject()); }
            return catalog;
        }
        @Override public void registerTrueTypeFontForClosing(org.apache.fontbox.ttf.TrueTypeFont font) {
            if (owner == null) { super.registerTrueTypeFontForClosing(font); } else { owner.registerTrueTypeFontForClosing(font); }
        }
        @Override public void close() throws IOException { if (owner == null) { super.close(); } }
    }
    private static final class NoRepairCatalog extends PDDocumentCatalog {
        NoRepairCatalog(PDDocument document, COSDictionary dictionary) { super(document, dictionary); }
        @Override public PDAcroForm getAcroForm() { return super.getAcroForm(null); }
    }
    private static final class Sources implements AutoCloseable {
        final List<PDDocument> documents = new ArrayList<>();
        void add(PDDocument document) { documents.add(document); }
        @Override public void close() throws IOException {
            IOException failure = null;
            for (int index = documents.size() - 1; index >= 0; index--) {
                try { documents.get(index).close(); } catch (IOException exception) { if (failure == null) { failure = exception; } else { failure.addSuppressed(exception); } }
            }
            if (failure != null) { throw failure; }
        }
    }
}
