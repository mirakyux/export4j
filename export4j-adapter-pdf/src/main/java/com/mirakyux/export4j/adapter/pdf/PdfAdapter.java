// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.multipdf.PDFCloneUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.encryption.SecurityHandler;
import org.apache.pdfbox.pdmodel.encryption.StandardSecurityHandler;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/** Offline PDF inspection, heuristic text extraction and static physical page composition.
 * Instances are immutable and reusable concurrently; builders are task confined. No API exposes PDFBox types.
 * Inputs are encoded snapshots and passwords are always explicit (use "" for no password).
 * The default static entry rejects interactive, signed, tagged, layered, archival and embedded-file input.
 * An explicit builder option admits validated passive layers/tags for reading and whole-document edits;
 * page composition remains strict because these document graphs require independent remapping.
 * Streams remain caller owned. Failed stream writes may leave partial bytes; discard those bytes.
 * Path output requires same-directory atomic replacement and never falls back to a non-atomic copy.
 * Cancellation is cooperative on the caller thread; the underlying parser and font decoder are not a sandbox.
 */
public final class PdfAdapter {
    public enum DiagnosticCode { DOCUMENT_METADATA_NOT_COPIED, ENCRYPTION_REMOVED, ACTUAL_TEXT_NOT_APPLIED }

    /** Context contains zero-based input/page indices and never contains source text or credentials. */
    public record Diagnostic(DiagnosticCode code, int inputIndex, int pageIndex) {
        public Diagnostic { Objects.requireNonNull(code, "code"); }
    }

    /** PDF-native unrotated box, in default user-space units, with a lower-left origin.
     * Multiply values by {@link Page#userUnit()} to obtain points. This is not Export4j's top-left layout coordinate system.
     */
    public record Box(double x, double y, double width, double height) { }

    /** One physical page. Text follows PDFBox's content-order heuristic, not the original semantic document order.
     * Outlined/scanned content has no ordinary glyph text; ActualText is reported but not substituted by this subset.
     */
    public record Page(int index, Box mediaBox, Box cropBox, int rotation, double userUnit, String text) {
        public Page {
            Objects.requireNonNull(mediaBox, "mediaBox");
            Objects.requireNonNull(cropBox, "cropBox");
            Objects.requireNonNull(text, "text");
        }
    }

    public record ReadResult(List<Page> pages, boolean encrypted, List<Diagnostic> diagnostics) {
        public ReadResult { pages = List.copyOf(pages); diagnostics = List.copyOf(diagnostics); }
    }

    /** A new document with copied page geometry/content/resources, and explicit document-level losses.
     * The result does not claim to preserve source bytes, signatures, encryption, conformance or document metadata.
     */
    public record WriteResult(int pageCount, long outputBytes, List<Diagnostic> diagnostics) {
        public WriteResult { diagnostics = List.copyOf(diagnostics); }
    }

    private final PdfLimits limits;
    private final boolean allowDecryptedCopy;
    private final boolean allowPassiveStructures;

    public PdfAdapter() { this(PdfLimits.DEFAULT, false, false); }

    private PdfAdapter(PdfLimits limits, boolean allowDecryptedCopy, boolean allowPassiveStructures) {
        this.limits = limits;
        this.allowDecryptedCopy = allowDecryptedCopy;
        this.allowPassiveStructures = allowPassiveStructures;
    }

    public static Builder builder() { return new Builder(); }

    PdfPreflight preflight(boolean interactive) { return new PdfPreflight(limits, interactive, allowPassiveStructures); }
    PdfPreflight preflightXfa() { return new PdfPreflight(limits, true, allowPassiveStructures, true); }

    /** Starts real, bounded content-stream replacement in the static page-text subset. */
    public PdfTextEditor editText(PdfInput input) { return new PdfTextEditor(this, Objects.requireNonNull(input, "input"), limits); }

    /** Edits selected image drawing instances without changing their source transformation or other uses. */
    public PdfImageEditor editImages(PdfInput input) { return new PdfImageEditor(this, Objects.requireNonNull(input, "input"), limits); }

    /** Reads or flattens the explicitly supported, passive XFA template/dataset subset. */
    public PdfXfaRequest xfa(PdfInput input) { return new PdfXfaRequest(this, Objects.requireNonNull(input, "input"), limits); }

    /** Exports explicitly selected passive form values as bounded FDF or XFDF. */
    public PdfFormDataRequest formData(PdfInput input) { return new PdfFormDataRequest(this, Objects.requireNonNull(input, "input"), limits); }

    /** Starts irreversible raster redaction into a fresh image-only PDF; source objects are not copied. */
    public PdfRedactor redact(PdfInput input) { return new PdfRedactor(this, Objects.requireNonNull(input, "input"), limits); }
    /** Rebuilds a supported content subset while deleting selected glyphs, pixels and path geometry.
     * Unsupported drawing structures reject the complete candidate. Original history and passive objects
     * are not copied. Regions use the rotated CropBox view in physical pt with a top-left origin.
     */
    public PdfSelectiveRedactor redactSelective(PdfInput input) { return new PdfSelectiveRedactor(this, Objects.requireNonNull(input, "input"), limits); }

    /** Starts explicit-loss geometric recovery into editable semantic blocks, without OCR. */
    public PdfSemanticImporter importDocument(PdfInput input) { return new PdfSemanticImporter(this, Objects.requireNonNull(input, "input"), limits); }

    /** Starts bounded, same-format editing of the validated static PDF subset. */
    public PdfPageEditor editPages(PdfInput input) { return new PdfPageEditor(this, Objects.requireNonNull(input, "input"), limits); }

    /** Same-format passive interactive-object editing; the strict static API keeps its old boundary. */
    public PdfObjectEditor editObjects(PdfInput input) { return new PdfObjectEditor(this, Objects.requireNonNull(input, "input"), limits); }

    /** Composes ordered page selections with passive objects and explicit source namespaces. */
    public PdfObjectComposer composeObjects() { return new PdfObjectComposer(this, limits); }

    /** Renders selected CropBox pages to bounded PNG/JPEG candidates; no OCR or implicit font fallback. */
    public PdfRasterRequest render(PdfSelection selection) { return new PdfRasterRequest(this, Objects.requireNonNull(selection, "selection"), limits); }

    /** Extracts unique image resources reachable from selected pages, including form/pattern resources. */
    public PdfImageExtractor extractImages(PdfSelection selection) { return new PdfImageExtractor(this, Objects.requireNonNull(selection, "selection"), limits); }

    /** Optimizes a validated passive PDF with separate lossless and opt-in image resampling paths. */
    public PdfOptimizer optimize(PdfInput input) { return new PdfOptimizer(this, Objects.requireNonNull(input, "input"), limits); }

    public PdfObjects.Snapshot readObjects(PdfInput input) throws IOException { return readObjects(input, ExecutionOptions.DEFAULT); }

    /** Reads a bounded projection of supported passive objects. Never executes actions or loads attachments. */
    public PdfObjects.Snapshot readObjects(PdfInput input, ExecutionOptions options) throws IOException {
        Objects.requireNonNull(input, "input");
        return ExecutionContext.execute(options, () -> {
            checkInputs(List.of(input));
            var budget = preflight(true);
            try (var document = open(input, budget)) {
                if (!document.getCurrentAccessPermission().canExtractContent()) { throw budget.failure(PdfOperationException.Code.PERMISSION_DENIED); }
                return PdfObjects.inspect(document, limits);
            }
        });
    }

    PDDocument openForEdit(PdfInput input, PdfPreflight budget) throws IOException {
        checkInputs(List.of(input));
        var document = open(input, budget);
        try {
            if (document.isEncrypted()) {
                if (!allowDecryptedCopy) { throw budget.failure(PdfOperationException.Code.ENCRYPTED_COPY_REJECTED); }
                ownerAuthentication(document, budget);
                document.setAllSecurityToBeRemoved(true);
            }
            return document;
        } catch (IOException | RuntimeException | Error failure) {
            try { document.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** Task-confined configuration. Built adapters retain only immutable values. */
    public static final class Builder {
        private PdfLimits limits = PdfLimits.DEFAULT;
        private boolean allowDecryptedCopy;
        private boolean allowPassiveStructures;
        private Builder() { }
        public Builder limits(PdfLimits value) { limits = Objects.requireNonNull(value, "limits"); return this; }
        /** Explicitly permits an unencrypted derivative of encrypted input, with an owner password only.
         * The write result reports ENCRYPTION_REMOVED. The default is false.
         */
        public Builder allowDecryptedCopy(boolean value) { allowDecryptedCopy = value; return this; }
        /** Enables validated passive optional-content and tagged-PDF structures for reading,
         * rendering and edits that retain the complete original document. The default is false.
         * Page selection and composition still reject them because their document-level references
         * require a separate remapping contract. This does not enable actions, scripts or PDF/UA.
         */
        public Builder allowPassiveStructures(boolean value) { allowPassiveStructures = value; return this; }
        public PdfAdapter build() { return new PdfAdapter(limits, allowDecryptedCopy, allowPassiveStructures); }
    }

    /** Takes a defensive encoded snapshot after checking the per-input byte budget. Does not parse the PDF yet. */
    public PdfInput input(byte[] bytes, String password) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        password(password);
        ExecutionContext.checkpoint();
        if (bytes.length > limits.maxInputBytes()) { throw new PdfOperationException(PdfOperationException.Code.INPUT_LIMIT, -1, -1); }
        return new PdfInput(bytes.clone(), password);
    }

    /** Reads at most maxInputBytes + 1 bytes from a borrowed stream; never closes it, even on failure.
     * A blocking caller stream must implement its own I/O timeout.
     */
    public PdfInput input(InputStream source, String password) throws IOException {
        Objects.requireNonNull(source, "source");
        password(password);
        var bytes = new ByteArrayOutputStream(Math.min(limits.maxInputBytes(), 8192));
        var buffer = new byte[8192];
        try {
            while (true) {
                ExecutionContext.checkpoint();
                int wanted = (int) Math.min(buffer.length, (long) limits.maxInputBytes() + 1 - bytes.size());
                int read = source.read(buffer, 0, wanted);
                if (read == -1) { break; }
                if (read == 0) {
                    int value = source.read();
                    if (value == -1) { break; }
                    if (bytes.size() == limits.maxInputBytes()) {
                        throw new PdfOperationException(PdfOperationException.Code.INPUT_LIMIT, -1, -1);
                    }
                    bytes.write(value);
                } else {
                    if (read > limits.maxInputBytes() - bytes.size()) {
                        throw new PdfOperationException(PdfOperationException.Code.INPUT_LIMIT, -1, -1);
                    }
                    bytes.write(buffer, 0, read);
                }
            }
        } catch (PdfOperationException exception) { throw exception; }
        catch (IOException exception) { throw new PdfOperationException(PdfOperationException.Code.INPUT_IO, -1, -1, exception); }
        return new PdfInput(bytes.toByteArray(), password);
    }

    /** Reads an explicitly supplied local path and closes only the stream this method opens. No URL fetching occurs. */
    public PdfInput input(Path source, String password) throws IOException {
        Objects.requireNonNull(source, "source");
        password(password);
        try (var stream = Files.newInputStream(source)) { return input(stream, password); }
        catch (PdfOperationException exception) { throw exception; }
        catch (IOException exception) { throw new PdfOperationException(PdfOperationException.Code.INPUT_IO, -1, -1, exception); }
    }

    private static void password(String value) {
        Objects.requireNonNull(value, "password");
        if (value.length() > 1024) { throw new IllegalArgumentException("PDF password exceeds 1024 UTF-16 code units"); }
    }

    public ReadResult read(PdfInput input) throws IOException { return read(input, ExecutionOptions.DEFAULT); }

    /** Parses and preflights the entire input, then extracts each page. Encrypted inputs require extraction permission.
     * This supplies neither OCR nor structural reconstruction, and reports ActualText rather than assuming reader parity.
     */
    public ReadResult read(PdfInput input, ExecutionOptions options) throws IOException {
        Objects.requireNonNull(input, "input");
        return ExecutionContext.execute(options, () -> {
            var budget = preflight(false);
            checkInputs(List.of(input));
            try (var document = open(input, budget)) {
                if (!document.getCurrentAccessPermission().canExtractContent()) {
                    throw budget.failure(PdfOperationException.Code.PERMISSION_DENIED);
                }
                var pages = new ArrayList<Page>();
                var diagnostics = new ArrayList<Diagnostic>();
                var textBudget = new TextBudget(budget);
                for (int index = 0; index < document.getNumberOfPages(); index++) {
                    budget.pageIndex = index;
                    ExecutionContext.progress("PDF_EXTRACT", "", index, index, document.getNumberOfPages());
                    var page = document.getPage(index);
                    geometry(page, budget);
                    var writer = new TextWriter(textBudget);
                    var stripper = new Stripper(textBudget, diagnostics);
                    stripper.setStartPage(index + 1);
                    stripper.setEndPage(index + 1);
                    stripper.setLineSeparator("\n");
                    stripper.setShouldSeparateByBeads(false);
                    stripper.setSortByPosition(false);
                    try { stripper.writeText(document, writer); }
                    catch (IOException | UncheckedIOException exception) { throw normalized(exception, budget); }
                    pages.add(new Page(index, box(page.getMediaBox()), box(page.getCropBox()), page.getRotation(), page.getUserUnit(), writer.text()));
                }
                ExecutionContext.progress("PDF_EXTRACT", "", document.getNumberOfPages() - 1,
                        document.getNumberOfPages(), document.getNumberOfPages());
                return new ReadResult(pages, document.isEncrypted(), diagnostics);
            }
        });
    }

    public WriteResult copy(PdfSelection selection, OutputStream output) throws IOException {
        return merge(List.of(selection), output);
    }

    public WriteResult copy(PdfSelection selection, Path output) throws IOException {
        return merge(List.of(selection), output);
    }

    public WriteResult merge(List<PdfSelection> selections, OutputStream output) throws IOException {
        return merge(selections, output, ExecutionOptions.DEFAULT);
    }

    /** All inputs and selections are preflighted before any output bytes are written.
     * Output is borrowed and remains open. Mid-write I/O, cancellation or budget failure can leave partial bytes.
     */
    public WriteResult merge(List<PdfSelection> selections, OutputStream output, ExecutionOptions options) throws IOException {
        Objects.requireNonNull(output, "output");
        List<PdfSelection> snapshot = selections(selections);
        return ExecutionContext.execute(options, () -> compose(snapshot, output));
    }

    public WriteResult merge(List<PdfSelection> selections, Path output) throws IOException {
        return merge(selections, output, ExecutionOptions.DEFAULT);
    }

    /** Writes a sibling temporary file, closes it and then atomically replaces the target.
     * The parent directory must already exist. Pre-commit failure/cancellation preserves an existing target.
     * The final cooperative cancellation check occurs before the commit; this method creates no background task.
     */
    public WriteResult merge(List<PdfSelection> selections, Path output, ExecutionOptions options) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        List<PdfSelection> snapshot = selections(selections);
        Path absolute = output.toAbsolutePath();
        Path temporary;
        try { temporary = Files.createTempFile(absolute.getParent(), ".export4j-pdf-", ".tmp"); }
        catch (IOException exception) { throw new PdfOperationException(PdfOperationException.Code.OUTPUT_IO, -1, -1, exception); }
        Throwable failure = null;
        try {
            WriteResult result = ExecutionContext.execute(options, () -> {
                try (var stream = Files.newOutputStream(temporary)) { return compose(snapshot, stream); }
            });
            try { Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException exception) { throw new PdfOperationException(PdfOperationException.Code.OUTPUT_IO, -1, -1, exception); }
            return result;
        } catch (IOException | RuntimeException | Error exception) { failure = exception; throw exception; }
        finally {
            try { Files.deleteIfExists(temporary); }
            catch (IOException exception) {
                if (failure != null) { failure.addSuppressed(exception); }
                else { throw new PdfOperationException(PdfOperationException.Code.OUTPUT_IO, -1, -1, exception); }
            }
        }
    }

    private List<PdfSelection> selections(List<PdfSelection> values) throws IOException {
        Objects.requireNonNull(values, "selections");
        if (values.isEmpty()) { throw new IllegalArgumentException("PDF selections must not be empty"); }
        if (values.size() > limits.maxPages()) { throw new PdfOperationException(PdfOperationException.Code.PAGE_LIMIT, -1, -1); }
        return List.copyOf(values);
    }

    private void checkInputs(List<PdfInput> inputs) throws IOException {
        if (inputs.size() > limits.maxInputs()) { throw new PdfOperationException(PdfOperationException.Code.INPUT_LIMIT, -1, -1); }
        long total = 0;
        for (var input : inputs) {
            if (input.byteSize() > limits.maxInputBytes() || input.byteSize() > limits.maxTotalInputBytes() - total) {
                throw new PdfOperationException(PdfOperationException.Code.INPUT_LIMIT, -1, -1);
            }
            total += input.byteSize();
        }
    }

    private WriteResult compose(List<PdfSelection> selections, OutputStream output) throws IOException {
        var ordered = new ArrayList<PdfInput>();
        var unique = Collections.newSetFromMap(new IdentityHashMap<PdfInput, Boolean>());
        for (var selection : selections) { if (unique.add(selection.input())) { ordered.add(selection.input()); } }
        checkInputs(ordered);
        var budget = new PdfPreflight(limits);
        var diagnostics = new ArrayList<Diagnostic>();
        try (var inputs = new Documents(); var target = new PDDocument()) {
            for (int index = 0; index < ordered.size(); index++) {
                budget.inputIndex = index;
                budget.pageIndex = -1;
                ExecutionContext.progress("PDF_INSPECT", "", -1, index, ordered.size());
                var source = open(ordered.get(index), budget);
                inputs.add(ordered.get(index), source);
                target.setVersion(Math.max(target.getVersion(), source.getVersion()));
                if (source.isEncrypted()) {
                    if (!allowDecryptedCopy) { throw budget.failure(PdfOperationException.Code.ENCRYPTED_COPY_REJECTED); }
                    ownerAuthentication(source, budget);
                    diagnostics.add(new Diagnostic(DiagnosticCode.ENCRYPTION_REMOVED, index, -1));
                }
                var catalog = source.getDocumentCatalog().getCOSObject();
                if (source.getDocument().getTrailer().containsKey(COSName.INFO) || catalog.containsKey(COSName.METADATA)
                        || catalog.keySet().stream().anyMatch(key -> !Set.of(COSName.TYPE, COSName.PAGES, COSName.VERSION).contains(key))) {
                    diagnostics.add(new Diagnostic(DiagnosticCode.DOCUMENT_METADATA_NOT_COPIED, index, -1));
                }
            }
            int pageCount = 0;
            for (var selection : selections) {
                budget.inputIndex = ordered.indexOf(selection.input());
                budget.pageIndex = -1;
                var source = inputs.documents.get(selection.input());
                int added = selection.includesAllPages() ? source.getNumberOfPages() : selection.indices().size();
                if (added > limits.maxPages() - pageCount) { throw budget.failure(PdfOperationException.Code.PAGE_LIMIT); }
                if (!selection.includesAllPages()) {
                    for (int index : selection.indices()) {
                        if (index >= source.getNumberOfPages()) {
                            budget.pageIndex = index;
                            throw budget.failure(PdfOperationException.Code.INVALID_SELECTION);
                        }
                    }
                }
                pageCount += added;
            }
            var cloners = new IdentityHashMap<PdfInput, Cloner>();
            int completed = 0;
            for (var selection : selections) {
                var source = inputs.documents.get(selection.input());
                budget.inputIndex = ordered.indexOf(selection.input());
                var cloner = cloners.computeIfAbsent(selection.input(), ignored -> new Cloner(target));
                int count = selection.includesAllPages() ? source.getNumberOfPages() : selection.indices().size();
                for (int selected = 0; selected < count; selected++) {
                    int page = selection.includesAllPages() ? selected : selection.indices().get(selected);
                    budget.pageIndex = page;
                    ExecutionContext.progress("PDF_COPY", "", page, completed, pageCount);
                    var sourcePage = source.getPage(page);
                    geometry(sourcePage, budget);
                    copyPage(sourcePage, target, cloner);
                    completed++;
                }
            }
            ExecutionContext.progress("PDF_SAVE", "", -1, 0, 1);
            var bounded = new BoundedOutput(ExecutionContext.output(output), limits.maxOutputBytes(), budget);
            try { target.save(bounded); }
            catch (IOException | UncheckedIOException exception) {
                Throwable cause = exception instanceof UncheckedIOException unchecked ? unchecked.getCause() : exception;
                if (cause instanceof PdfOperationException failure) { throw failure; }
                throw new PdfOperationException(PdfOperationException.Code.OUTPUT_IO, budget.inputIndex, budget.pageIndex, cause);
            }
            ExecutionContext.progress("PDF_SAVE", "", -1, 1, 1);
            return new WriteResult(pageCount, bounded.count, diagnostics);
        }
    }

    private static void ownerAuthentication(PDDocument document, PdfPreflight budget) throws IOException {
        SecurityHandler<?> handler = document.getEncryption().getSecurityHandler();
        if (!(handler instanceof StandardSecurityHandler)) {
            throw budget.failure(PdfOperationException.Code.UNSUPPORTED_FEATURE);
        }
        var permission = document.getCurrentAccessPermission();
        // PDFBox 3.0.8 locks every authenticated user permission object, even when all P bits are set.
        // Its standard owner branch alone leaves this private Java state unlocked; no PDF key controls it.
        // isOwnerPermission() by itself only checks permission bits and does not identify the password.
        if (permission.isReadOnly() || !permission.isOwnerPermission()) {
            throw budget.failure(PdfOperationException.Code.PERMISSION_DENIED);
        }
    }

    PDDocument open(PdfInput input, PdfPreflight budget) throws IOException {
        if (input.byteSize() > limits.maxInputBytes() || input.byteSize() > limits.maxTotalInputBytes()) { throw budget.failure(PdfOperationException.Code.INPUT_LIMIT); }
        byte[] bytes = input.bytes();
        if (bytes.length < 8 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F' || bytes[4] != '-') {
            throw budget.failure(PdfOperationException.Code.INVALID_PDF);
        }
        ExecutionContext.checkpoint();
        var reader = new RandomAccessReadBuffer(bytes);
        PDDocument document = null;
        try {
            document = new PdfPreflight.Parser(reader, input.password(), budget).parse(false);
            if (!Float.isFinite(document.getVersion()) || document.getVersion() < 1 || document.getVersion() > 2) {
                throw budget.failure(PdfOperationException.Code.UNSUPPORTED_FEATURE);
            }
            budget.inspect(document);
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                budget.pageIndex = index;
                geometry(document.getPage(index), budget);
            }
            budget.pageIndex = -1;
            return document;
        } catch (IOException | RuntimeException exception) {
            try { if (document == null) { reader.close(); } else { document.close(); } }
            catch (IOException closeFailure) { exception.addSuppressed(closeFailure); }
            if (exception instanceof ExecutionException execution) { throw execution; }
            throw normalized(exception, budget);
        }
    }

    private static IOException normalized(Exception exception, PdfPreflight budget) {
        Throwable cause = exception instanceof UncheckedIOException unchecked ? unchecked.getCause() : exception;
        if (cause instanceof PdfOperationException failure) { return failure; }
        return new PdfOperationException(cause instanceof InvalidPasswordException ? PdfOperationException.Code.PASSWORD
                : PdfOperationException.Code.INVALID_PDF, budget.inputIndex, budget.pageIndex, cause);
    }

    private static void geometry(PDPage page, PdfPreflight budget) throws IOException {
        COSBase media = PDPageTree.getInheritableAttribute(page.getCOSObject(), COSName.MEDIA_BOX);
        COSBase crop = PDPageTree.getInheritableAttribute(page.getCOSObject(), COSName.CROP_BOX);
        boxArray(media, budget);
        if (crop != null) { boxArray(crop, budget); }
        var rotation = PDPageTree.getInheritableAttribute(page.getCOSObject(), COSName.ROTATE);
        if (rotation != null && (!(rotation instanceof COSInteger number) || number.longValue() < Integer.MIN_VALUE
                || number.longValue() > Integer.MAX_VALUE || number.longValue() % 90 != 0)) {
            throw budget.failure(PdfOperationException.Code.INVALID_PDF);
        }
        validBox(page.getMediaBox(), budget);
        validBox(page.getCropBox(), budget);
        var rawUnit = page.getCOSObject().getDictionaryObject(COSName.USER_UNIT);
        if (rawUnit != null && !(rawUnit instanceof COSNumber)) { throw budget.failure(PdfOperationException.Code.INVALID_PDF); }
        double unit = rawUnit instanceof COSNumber number ? number.floatValue() : 1;
        if (!Double.isFinite(unit) || unit <= 0 || unit > 75_000) { throw budget.failure(PdfOperationException.Code.INVALID_PDF); }
    }

    private static void boxArray(COSBase value, PdfPreflight budget) throws IOException {
        if (!(value instanceof COSArray array) || array.size() != 4) { throw budget.failure(PdfOperationException.Code.INVALID_PDF); }
        for (int index = 0; index < 4; index++) {
            if (!(array.getObject(index) instanceof COSNumber number) || !Float.isFinite(number.floatValue())) {
                throw budget.failure(PdfOperationException.Code.INVALID_PDF);
            }
        }
    }

    private static void validBox(PDRectangle box, PdfPreflight budget) throws IOException {
        double[] values = {box.getLowerLeftX(), box.getLowerLeftY(), box.getWidth(), box.getHeight()};
        for (double value : values) {
            if (!Double.isFinite(value) || Math.abs(value) > 1_000_000) { throw budget.failure(PdfOperationException.Code.INVALID_PDF); }
        }
        if (box.getWidth() <= 0 || box.getHeight() <= 0) { throw budget.failure(PdfOperationException.Code.INVALID_PDF); }
    }

    private static Box box(PDRectangle value) {
        return new Box(value.getLowerLeftX(), value.getLowerLeftY(), value.getWidth(), value.getHeight());
    }

    private static void copyPage(PDPage source, PDDocument target, Cloner cloner) throws IOException {
        var detached = new COSDictionary(source.getCOSObject());
        detached.removeItem(COSName.PARENT);
        var page = new PDPage(cloner.cloneForNewDocument(detached));
        page.setMediaBox(new PDRectangle(cloner.cloneForNewDocument(source.getMediaBox().getCOSArray())));
        var crop = PDPageTree.getInheritableAttribute(source.getCOSObject(), COSName.CROP_BOX);
        page.setCropBox(new PDRectangle(cloner.cloneForNewDocument(crop instanceof COSArray array ? array : source.getMediaBox().getCOSArray())));
        page.setRotation(source.getRotation());
        if (source.getResources() != null) { page.setResources(new PDResources(cloner.cloneForNewDocument(source.getResources().getCOSObject()))); }
        target.addPage(page);
    }

    private static final class Cloner extends PDFCloneUtility {
        Cloner(PDDocument target) { super(target); }
    }

    private static final class Documents implements AutoCloseable {
        private final Map<PdfInput, PDDocument> documents = new IdentityHashMap<>();
        private final List<PDDocument> ordered = new ArrayList<>();
        void add(PdfInput input, PDDocument document) { documents.put(input, document); ordered.add(document); }
        @Override public void close() throws IOException {
            IOException failure = null;
            for (int index = ordered.size() - 1; index >= 0; index--) {
                try { ordered.get(index).close(); }
                catch (IOException exception) { if (failure == null) { failure = exception; } else { failure.addSuppressed(exception); } }
            }
            if (failure != null) { throw failure; }
        }
    }

    private static final class BoundedOutput extends OutputStream {
        private final OutputStream target;
        private final long limit;
        private final PdfPreflight budget;
        private long count;
        BoundedOutput(OutputStream target, long limit, PdfPreflight budget) { this.target = target; this.limit = limit; this.budget = budget; }
        private void reserve(int length) throws IOException {
            ExecutionContext.checkpoint();
            if (length > limit - count) { throw budget.failure(PdfOperationException.Code.OUTPUT_LIMIT); }
            count += length;
        }
        @Override public void write(int value) throws IOException { reserve(1); target.write(value); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            reserve(length);
            target.write(bytes, offset, length);
        }
        @Override public void flush() throws IOException { ExecutionContext.checkpoint(); target.flush(); }
        @Override public void close() { /* Caller owns the destination. */ }
    }

    private static final class TextBudget {
        private final PdfPreflight preflight;
        private long glyphCharacters;
        private long outputCharacters;
        private long operators;
        TextBudget(PdfPreflight preflight) { this.preflight = preflight; }
        void glyphs(int count) throws IOException {
            ExecutionContext.checkpoint();
            if (count > preflight.limits.maxTextCharacters() - glyphCharacters) { throw preflight.failure(PdfOperationException.Code.TEXT_LIMIT); }
            glyphCharacters += count;
        }
        void text(int count) throws IOException {
            ExecutionContext.checkpoint();
            if (count > preflight.limits.maxTextCharacters() - outputCharacters) { throw preflight.failure(PdfOperationException.Code.TEXT_LIMIT); }
            outputCharacters += count;
        }
    }

    private static final class TextWriter extends Writer {
        private final TextBudget budget;
        private final StringBuilder value = new StringBuilder();
        TextWriter(TextBudget budget) { this.budget = budget; }
        String text() { return value.toString(); }
        @Override public void write(char[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            budget.text(length);
            value.append(buffer, offset, length);
        }
        @Override public void flush() { }
        @Override public void close() { }
    }

    private static final class Stripper extends PDFTextStripper {
        private final TextBudget budget;
        private final List<Diagnostic> diagnostics;
        private final Set<COSBase> forms = Collections.newSetFromMap(new IdentityHashMap<>());
        private final java.util.ArrayDeque<Boolean> visibility = new java.util.ArrayDeque<>();
        private boolean visible = true;
        private int graphicsDepth;
        private boolean actualText;
        Stripper(TextBudget budget, List<Diagnostic> diagnostics) { this.budget = budget; this.diagnostics = diagnostics; }
        @Override protected void processTextPosition(TextPosition position) {
            try { budget.glyphs(position.getUnicode() == null ? 0 : position.getUnicode().length()); }
            catch (IOException exception) { throw new UncheckedIOException(exception); }
            if (visible) { super.processTextPosition(position); }
        }
        @Override protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            ExecutionContext.checkpoint();
            if (++budget.operators > budget.preflight.limits.maxContentOperators()) {
                throw budget.preflight.failure(PdfOperationException.Code.OPERATOR_LIMIT);
            }
            if (operator.getName().equals("q")) {
                if (forms.size() + graphicsDepth + 1 > budget.preflight.limits.maxDepth()) { throw budget.preflight.failure(PdfOperationException.Code.DEPTH_LIMIT); }
                graphicsDepth++;
            } else if (operator.getName().equals("Q")) { graphicsDepth--; }
            if (operator.getName().equals("BI")) { throw budget.preflight.failure(PdfOperationException.Code.UNSUPPORTED_FEATURE); }
            super.processOperator(operator, operands);
        }
        @Override protected void operatorException(Operator operator, List<COSBase> operands, IOException exception) throws IOException { throw exception; }
        @Override public void showForm(PDFormXObject form) throws IOException {
            boolean parent = visible;
            int parentDepth = enter(form);
            try { super.showForm(form); }
            finally { visible = parent; graphicsDepth = parentDepth; forms.remove(form.getCOSObject()); }
        }
        @Override public void showTransparencyGroup(PDTransparencyGroup group) throws IOException {
            boolean parent = visible;
            int parentDepth = enter(group);
            try { super.showTransparencyGroup(group); }
            finally { visible = parent; graphicsDepth = parentDepth; forms.remove(group.getCOSObject()); }
        }
        private int enter(PDFormXObject form) throws IOException {
            ExecutionContext.checkpoint();
            if (forms.contains(form.getCOSObject())) { throw budget.preflight.failure(PdfOperationException.Code.INVALID_PDF); }
            if (forms.size() + graphicsDepth + 1 > budget.preflight.limits.maxDepth()) { throw budget.preflight.failure(PdfOperationException.Code.DEPTH_LIMIT); }
            var structures = budget.preflight.structures();
            if (structures != null && form.getCOSObject().containsKey(COSName.OC)) {
                visible &= structures.visible(form.getCOSObject().getCOSDictionary(COSName.OC));
            }
            forms.add(form.getCOSObject()); return graphicsDepth;
        }
        @Override public void beginMarkedContentSequence(COSName tag, COSDictionary properties) {
            if (visibility.size() >= budget.preflight.limits.maxDepth()) {
                throw new UncheckedIOException(budget.preflight.failure(PdfOperationException.Code.DEPTH_LIMIT));
            }
            visibility.push(visible);
            var structures = budget.preflight.structures();
            if (COSName.OC.equals(tag) && structures != null) {
                try { visible &= structures.visible(properties); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
            }
            if (!actualText && properties != null && properties.containsKey(COSName.ACTUAL_TEXT)) {
                actualText = true;
                diagnostics.add(new Diagnostic(DiagnosticCode.ACTUAL_TEXT_NOT_APPLIED, budget.preflight.inputIndex, budget.preflight.pageIndex));
            }
            // The first subset intentionally exposes ordinary glyph extraction. Do not enter
            // PDFTextStripper's separate ActualText substitution state for untrusted logical values.
        }
        @Override public void endMarkedContentSequence() {
            if (visibility.isEmpty()) { throw new UncheckedIOException(budget.preflight.failure(PdfOperationException.Code.INVALID_PDF)); }
            visible = visibility.pop();
        }
    }
}
