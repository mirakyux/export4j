// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.ExternalDestination;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.pdmodel.interactive.form.PDVariableText;

/** Same-format, task-confined editor for the explicitly validated passive object subset.
 * Object rectangles and fixed form font sizes use native unrotated PDF user-space units.
 * Existing page content is retained. No actions execute, no external files are fetched.
 */
public final class PdfObjectEditor {
    @FunctionalInterface private interface Edit { boolean apply(State state) throws IOException; }
    private final PdfAdapter adapter; private final PdfInput input; private final PdfLimits limits;
    private final List<Edit> edits = new ArrayList<>();
    private ExecutionOptions execution = ExecutionOptions.DEFAULT;
    private ProcessingPolicy policy = ProcessingPolicy.STRICT;
    private long snapshotBytes;
    private long snapshotText;
    PdfObjectEditor(PdfAdapter adapter, PdfInput input, PdfLimits limits) { this.adapter = adapter; this.input = input; this.limits = limits; }
    public PdfObjectEditor execution(ExecutionOptions value) { execution = Objects.requireNonNull(value, "execution"); return this; }
    public PdfObjectEditor policy(ProcessingPolicy value) { policy = Objects.requireNonNull(value, "policy"); return this; }
    /** Updates one standard Info key. Existing XMP must be explicitly discarded to avoid stale values. */
    public PdfObjectEditor metadata(String key, String value) {
        if (!Set.of("Title", "Author", "Subject", "Keywords", "Creator", "Producer").contains(key)) { throw new IllegalArgumentException("unsupported PDF metadata key"); }
        text(value);
        return add(state -> {
            var catalog = state.document.getDocumentCatalog();
            if (catalog.getMetadata() != null) { state.loss("XMP_METADATA", "SET_METADATA", ProcessingPolicy.Treatment.DISCARD, "XMP_METADATA_REMOVED"); catalog.setMetadata(null); }
            state.document.getDocumentInformation().setCustomMetadataValue(key, value); return true;
        });
    }
    public PdfObjectEditor addComment(int page, PdfAdapter.Box rectangle, String contents) {
        box(rectangle); text(contents); pageIndex(page);
        return add(state -> {
            var target = state.page(page); var note = new PDAnnotationText(); note.setRectangle(nativeBox(rectangle)); note.setContents(contents); note.setPage(target);
            note.constructAppearances(state.document); target.getAnnotations().add(note); return true;
        });
    }
    /** Index is in the current page's annotation array, after all preceding edits. */
    public PdfObjectEditor updateComment(int page, int index, String contents) {
        text(contents); pageIndex(page); if (index < 0) { throw new IllegalArgumentException("negative annotation index"); }
        return add(state -> {
            var values = state.page(page).getAnnotations(); if (index >= values.size()) { throw PdfObjects.invalid(); }
            if (!(values.get(index) instanceof PDAnnotationText note)) { return state.skip("ANNOTATION_TYPE", "UPDATE_COMMENT", "ANNOTATION_UNCHANGED"); }
            note.setContents(contents); note.constructAppearances(state.document); return true;
        });
    }
    /** Widgets are managed through their field, never removed independently. */
    public PdfObjectEditor removeAnnotation(int page, int index) {
        pageIndex(page); if (index < 0) { throw new IllegalArgumentException("negative annotation index"); }
        return add(state -> {
            var values = state.page(page).getAnnotations(); if (index >= values.size()) { throw PdfObjects.invalid(); }
            if (values.get(index) instanceof PDAnnotationWidget) { return state.skip("FIELD_WIDGET", "REMOVE_ANNOTATION", "FIELD_AND_WIDGET_PRESERVED"); }
            values.remove(index); return true;
        });
    }
    public PdfObjectEditor addLink(int page, PdfAdapter.Box rectangle, String uri) {
        new ExternalDestination(uri); box(rectangle); pageIndex(page);
        return add(state -> {
            var link = new PDAnnotationLink(); link.setRectangle(nativeBox(rectangle)); link.setPage(state.page(page));
            var action = new PDActionURI(); action.setURI(uri); link.setAction(action); state.page(page).getAnnotations().add(link); return true;
        });
    }
    public PdfObjectEditor addPageLink(int page, PdfAdapter.Box rectangle, int targetPage) {
        box(rectangle); pageIndex(page); pageIndex(targetPage);
        return add(state -> {
            var destination = new PDPageFitDestination(); destination.setPage(state.page(targetPage));
            var link = new PDAnnotationLink(); link.setRectangle(nativeBox(rectangle)); link.setPage(state.page(page)); link.setDestination(destination); state.page(page).getAnnotations().add(link); return true;
        });
    }
    /** Replaces the entire outline with page-fit bookmarks; existing outline formatting is not inherited. */
    public PdfObjectEditor bookmarks(List<PdfObjects.Bookmark> value) {
        var bookmarks = List.copyOf(value); long[] count = {0}; validateBookmarks(bookmarks, 0, count);
        return add(state -> { var outline = new PDDocumentOutline(); appendBookmarks(state, outline, bookmarks); state.document.getDocumentCatalog().setDocumentOutline(outline); return true; });
    }
    /** Adds/replaces an opaque attachment by a logical filename. Bytes are snapshotted, never executed. */
    public PdfObjectEditor attachment(String name, byte[] value, String mediaType) {
        filename(name); text(name); Objects.requireNonNull(value, "value");
        if (!Objects.requireNonNull(mediaType, "mediaType").matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+")) { throw new IllegalArgumentException("invalid attachment media type"); }
        if (value.length > limits.maxInputBytes() || value.length > limits.maxTotalInputBytes() - snapshotBytes) { throw new IllegalArgumentException("PDF attachment byte budget exceeded"); }
        byte[] bytes = value.clone(); snapshotBytes += bytes.length;
        return add(state -> {
            var values = PdfObjects.files(state.document, limits); var file = new PDEmbeddedFile(state.document, new ByteArrayInputStream(bytes), COSName.FLATE_DECODE);
            file.setSubtype(mediaType); file.setSize(bytes.length); var specification = new PDComplexFileSpecification(); specification.setFile(name); specification.setFileUnicode(name);
            specification.setEmbeddedFile(file); specification.setEmbeddedFileUnicode(file); values.put(name, specification); files(state.document, values); return true;
        });
    }
    public PdfObjectEditor removeAttachment(String name) {
        text(name);
        return add(state -> { var values = PdfObjects.files(state.document, limits); if (values.remove(name) == null) { throw new IllegalArgumentException("attachment not found"); } files(state.document, values); return true; });
    }
    /** Single-line plain text only, fixed font size in native user units. Full font embedding preserves editability. */
    public PdfObjectEditor fillText(String name, String value, PdfFont font, double size) {
        text(name); text(value); Objects.requireNonNull(font, "font"); size(size);
        return add(state -> {
            var field = state.field(name);
            if (!(field instanceof PDTextField target) || target.isMultiline() || target.isPassword() || target.isFileSelect() || target.isComb()
                    || target.getCOSObject().containsKey(COSName.RV) || target.getCOSObject().containsKey(COSName.DS)) { return state.skip("FIELD_FORMAT", "FILL_TEXT", "FIELD_UNCHANGED"); }
            if (target.getMaxLen() > 0 && value.codePointCount(0, value.length()) > target.getMaxLen()) { throw new IllegalArgumentException("PDF field maximum length exceeded"); }
            if (!state.variable(target, value, font, size)) { return false; } target.setValue(value); return true;
        });
    }
    public PdfObjectEditor fillChoice(String name, String exportValue, PdfFont font, double size) {
        text(name); text(exportValue); Objects.requireNonNull(font, "font"); size(size);
        return add(state -> {
            var field = state.field(name);
            if (!(field instanceof PDComboBox target) || target.isMultiSelect()) { return state.skip("FIELD_FORMAT", "FILL_CHOICE", "FIELD_UNCHANGED"); }
            int index = target.getOptionsExportValues().indexOf(exportValue); if (index < 0) { throw new IllegalArgumentException("unknown PDF choice option"); }
            if (!state.variable(target, target.getOptionsDisplayValues().get(index), font, size)) { return false; } target.setValue(exportValue); return true;
        });
    }
    public PdfObjectEditor setChecked(String name, boolean checked) {
        text(name);
        return add(state -> {
            if (!(state.field(name) instanceof PDCheckBox target)) { return state.skip("FIELD_FORMAT", "SET_CHECKED", "FIELD_UNCHANGED"); }
            if (checked) { target.check(); } else { target.unCheck(); } return true;
        });
    }
    public PdfObjectEditor selectRadio(String name, String value) {
        text(name); text(value);
        return add(state -> {
            if (!(state.field(name) instanceof PDRadioButton target)) { return state.skip("FIELD_FORMAT", "SELECT_RADIO", "FIELD_UNCHANGED"); }
            if (!target.getOnValues().contains(value)) { throw new IllegalArgumentException("unknown PDF radio option"); }
            target.setValue(value); return true;
        });
    }
    /** Flattens all fields only when each widget has a concrete current normal appearance.
     * This intentionally removes editability and requires FORMS/FLATTEN_FORMS/DISCARD.
     */
    public PdfObjectEditor flattenForms() {
        return add(state -> {
            var form = state.document.getDocumentCatalog().getAcroForm(null); if (form == null || form.getFields().isEmpty()) { return false; }
            if (form.getNeedAppearances()) { throw PdfObjects.unsupported(); }
            for (var field : form.getFieldTree()) {
                if (field.isReadOnly()) { throw new PdfOperationException(PdfOperationException.Code.PERMISSION_DENIED, 0, -1); }
                for (var widget : field.getWidgets()) { if (widget.getNormalAppearanceStream() == null) { throw PdfObjects.unsupported(); } }
            }
            state.loss("FORMS", "FLATTEN_FORMS", ProcessingPolicy.Treatment.DISCARD, "FORM_EDITABILITY_REMOVED");
            form.flatten(); return true;
        });
    }
    private PdfObjectEditor add(Edit value) {
        if (edits.size() >= Math.min(10_000, limits.maxObjects())) { throw new IllegalArgumentException("PDF object edit operation budget exceeded"); }
        edits.add(value); return this;
    }
    public PreparedPdf prepare() throws IOException {
        var operations = List.copyOf(edits); var control = execution; var strategy = policy;
        return ExecutionContext.execute(control, () -> {
            var budget = adapter.preflight(true);
            try (var document = adapter.openForEdit(input, budget)) {
                PdfObjects.inspect(document, limits);
                var state = new State(document, strategy); int changes = 0;
                if (document.isEncrypted()) { state.loss("ENCRYPTION_REMOVED", "PREPARE", ProcessingPolicy.Treatment.DEGRADE, "OUTPUT_IS_UNENCRYPTED"); }
                for (var edit : operations) { ExecutionContext.checkpoint(); if (edit.apply(state)) { changes++; } }
                adapter.preflight(true).inspect(document);
                PdfObjects.inspect(document, limits);
                return PreparedPdf.encode(document, changes, state.issues, limits, control);
            }
        });
    }
    private final class State {
        final PDDocument document; final ProcessingPolicy strategy;
        final List<ProcessingIssue> issues = new ArrayList<>(); final Map<PdfFont, PDType0Font> fonts = new IdentityHashMap<>();
        long fontBytes;
        State(PDDocument document, ProcessingPolicy strategy) { this.document = document; this.strategy = strategy; }
        org.apache.pdfbox.pdmodel.PDPage page(int index) throws IOException { if (index >= document.getNumberOfPages()) { throw new PdfOperationException(PdfOperationException.Code.INVALID_SELECTION, 0, index); } return document.getPage(index); }
        PDField field(String name) throws IOException {
            var form = document.getDocumentCatalog().getAcroForm(null); var field = form == null ? null : form.getField(name);
            if (field == null) { throw new IllegalArgumentException("PDF field not found"); }
            if (field.isReadOnly()) { throw new PdfOperationException(PdfOperationException.Code.PERMISSION_DENIED, 0, -1); }
            return field;
        }
        boolean variable(PDVariableText field, String value, PdfFont font, double size) throws IOException {
            if (!PdfPageEditor.simpleText(value)) { return skip("TEXT_SHAPING", "FILL_VALUE", "FIELD_UNCHANGED"); }
            String appearance = field.getDefaultAppearance();
            if (appearance == null) { appearance = field.getAcroForm().getDefaultAppearance(); }
            String color = appearanceColor(appearance);
            if (color == null) { return skip("FIELD_FORMAT", "FILL_VALUE", "FIELD_UNCHANGED"); }
            var actual = fonts.get(font);
            if (actual == null) {
                if (font.bytes().length > limits.maxTotalInputBytes() - snapshotBytes - fontBytes) { throw new PdfOperationException(PdfOperationException.Code.INPUT_LIMIT, 0, -1); }
                fontBytes += font.bytes().length; actual = PDType0Font.load(document, new ByteArrayInputStream(font.bytes()), false); fonts.put(font, actual);
            }
            double width = actual.getStringWidth(value) / 1000 * size;
            double height = (actual.getFontDescriptor().getAscent() - actual.getFontDescriptor().getDescent()) / 1000 * size;
            for (var widget : field.getWidgets()) {
                var rectangle = widget.getRectangle();
                var characteristics = widget.getAppearanceCharacteristics();
                if (characteristics != null && characteristics.getRotation() != 0) { return skip("FIELD_FORMAT", "FILL_VALUE", "FIELD_UNCHANGED"); }
                double border = widget.getBorderStyle() == null ? 1 : widget.getBorderStyle().getWidth();
                var legacyBorder = widget.getBorder();
                if (widget.getBorderStyle() == null && legacyBorder != null && legacyBorder.size() >= 3) {
                    if (!(legacyBorder.getObject(2) instanceof COSNumber number)) { throw PdfObjects.invalid(); }
                    border = number.floatValue();
                }
                if (!Double.isFinite(border) || border < 0) { throw PdfObjects.invalid(); }
                double inset = 2 + border * 2;
                if (width > rectangle.getWidth() - inset * 2 || height > rectangle.getHeight() - inset * 2) { throw new IllegalArgumentException("PDF field value exceeds its fixed appearance box"); }
            }
            var form = field.getAcroForm(); var resources = form.getDefaultResources(); if (resources == null) { resources = new PDResources(); form.setDefaultResources(resources); }
            COSName key = resources.add(actual); field.setDefaultAppearance("/" + key.getName() + " " + BigDecimal.valueOf(size).toPlainString() + " Tf " + color);
            // Every edited field gets a concrete appearance; unedited fields retain their existing state.
            return true;
        }
        boolean skip(String feature, String operation, String effect) { issue(feature, operation, ProcessingPolicy.Treatment.SKIP_MATCH, effect); return false; }
        void loss(String feature, String operation, ProcessingPolicy.Treatment treatment, String effect) { issue(feature, operation, treatment, effect); }
        void issue(String feature, String operation, ProcessingPolicy.Treatment treatment, String effect) {
            var rule = strategy.resolve("PDF", feature, operation);
            var issue = new ProcessingIssue("EDIT", "PDF", "PDF", feature, operation, new ProcessingIssue.Location("", "PDF_OBJECTS", "", List.of(), -1, -1),
                    "explicit object operation requires the declared treatment", rule.action(), rule.treatment(), List.of(effect));
            if (rule.action() == ProcessingPolicy.Action.ERROR) { throw new ProcessingException(issue); }
            if (rule.treatment() != treatment) { throw new IllegalArgumentException("unsupported PDF object treatment"); }
            issues.add(issue);
        }
    }
    /** Only font plus gray/RGB/CMYK color DA is supported; do not silently reset other text state. */
    private static String appearanceColor(String source) throws IOException {
        if (source == null || source.length() > 65_536) { return null; }
        var parser = new PDFStreamParser(source.getBytes(StandardCharsets.ISO_8859_1));
        try {
            String color = "0 g"; boolean font = false; var operands = new ArrayList<Object>(); Object token;
            while ((token = parser.parseNextToken()) != null) {
                ExecutionContext.checkpoint();
                if (!(token instanceof Operator operator)) { if (operands.size() >= 4) { throw PdfObjects.invalid(); } operands.add(token); continue; }
                if (operator.getName().equals("Tf")) {
                    if (font || operands.size() != 2 || !(operands.getFirst() instanceof COSName) || !(operands.get(1) instanceof COSNumber size)
                            || !Float.isFinite(size.floatValue()) || size.floatValue() < 0) { throw PdfObjects.invalid(); } font = true;
                } else {
                    int count = switch (operator.getName()) { case "g" -> 1; case "rg" -> 3; case "k" -> 4; default -> 0; };
                    if (count == 0) { return null; }
                    if (operands.size() != count) { throw PdfObjects.invalid(); }
                    var value = new StringBuilder();
                    for (var operand : operands) {
                        if (!(operand instanceof COSNumber number) || !Float.isFinite(number.floatValue()) || number.floatValue() < 0 || number.floatValue() > 1) { throw PdfObjects.invalid(); }
                        value.append(BigDecimal.valueOf(number.floatValue()).toPlainString()).append(' ');
                    }
                    color = value.append(operator.getName()).toString();
                }
                operands.clear();
            }
            if (!operands.isEmpty()) { throw PdfObjects.invalid(); } return font ? color : null;
        } finally { parser.close(); }
    }
    private static void files(PDDocument document, Map<String, PDComplexFileSpecification> files) {
        var catalog = document.getDocumentCatalog(); var names = catalog.getNames(); if (names == null) { names = new PDDocumentNameDictionary(catalog); }
        if (files.isEmpty()) { names.getCOSObject().removeItem(COSName.EMBEDDED_FILES); }
        else { var tree = new PDEmbeddedFilesNameTreeNode(); tree.setNames(files); names.setEmbeddedFiles(tree); }
        if (names.getCOSObject().size() == 0) { catalog.setNames(null); } else { catalog.setNames(names); }
    }
    private void validateBookmarks(List<PdfObjects.Bookmark> bookmarks, int depth, long[] count) {
        if (depth > limits.maxDepth()) { throw new IllegalArgumentException("PDF bookmark depth budget exceeded"); }
        for (var bookmark : bookmarks) { ExecutionContext.checkpoint(); if (++count[0] > limits.maxObjects()) { throw new IllegalArgumentException("PDF bookmark budget exceeded"); } text(bookmark.title()); if (bookmark.page() < -1) { throw new IllegalArgumentException("invalid bookmark page"); } validateBookmarks(bookmark.children(), depth + 1, count); }
    }
    private static void appendBookmarks(State state, PDOutlineNode parent, List<PdfObjects.Bookmark> bookmarks) throws IOException {
        for (var bookmark : bookmarks) { ExecutionContext.checkpoint(); var item = new PDOutlineItem(); item.setTitle(bookmark.title()); if (bookmark.page() >= 0) { item.setDestination(state.page(bookmark.page())); } parent.addLast(item); appendBookmarks(state, item, bookmark.children()); }
    }
    private void text(String text) {
        Objects.requireNonNull(text, "text"); if (text.length() > limits.maxTextCharacters()) { throw new IllegalArgumentException("PDF object text budget exceeded"); }
        for (int index = 0; index < text.length(); index++) { char c = text.charAt(index); if (Character.isHighSurrogate(c)) { if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) { throw new IllegalArgumentException("invalid Unicode"); } } else if (Character.isLowSurrogate(c) || Character.isISOControl(c)) { throw new IllegalArgumentException("invalid PDF object text"); } }
        if (text.length() > limits.maxTextCharacters() - snapshotText) { throw new IllegalArgumentException("PDF cumulative edit text budget exceeded"); }
        snapshotText += text.length();
    }
    private static void filename(String name) { if (!Objects.requireNonNull(name, "name").matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}") || name.contains("..")) { throw new IllegalArgumentException("attachment requires a plain logical filename"); } }
    private static void pageIndex(int page) { if (page < 0) { throw new IllegalArgumentException("negative page index"); } }
    private static void size(double value) { if (!Double.isFinite(value) || value <= 0 || value > 10_000) { throw new IllegalArgumentException("invalid PDF field font size"); } }
    private static void box(PdfAdapter.Box value) {
        Objects.requireNonNull(value, "rectangle");
        if (!Double.isFinite(value.x()) || !Double.isFinite(value.y()) || !Double.isFinite(value.width()) || !Double.isFinite(value.height())
                || Math.abs(value.x()) > 1_000_000 || Math.abs(value.y()) > 1_000_000 || value.width() <= 0 || value.height() <= 0 || value.width() > 1_000_000 || value.height() > 1_000_000) { throw new IllegalArgumentException("invalid PDF object rectangle"); }
    }
    private static PDRectangle nativeBox(PdfAdapter.Box value) { return new PDRectangle((float) value.x(), (float) value.y(), (float) value.width(), (float) value.height()); }
}
