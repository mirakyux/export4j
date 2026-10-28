// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.function.Supplier;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.DocumentBackend;
import com.mirakyux.export4j.graphics.PageBackend;
import com.mirakyux.export4j.layout.core.DocumentPainter;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.layout.core.StreamingTableLayouter;
import com.mirakyux.export4j.layout.core.StreamingTableLimits;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.PageDocument;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableRowSource;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import com.mirakyux.export4j.text.TextDiagnostic;
import java.util.function.Consumer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Immutable, reusable orchestration facade. Supplied custom components/backends own their thread-safety contract. */
public final class DocumentEngine {
    private final FontGroup fonts;
    private final Map<String, FontGroup> roles;
    private final PageTemplate pageTemplate;
    private final Navigation navigation;
    private final List<Note> notes;

    private DocumentEngine(Builder builder) {
        fonts = Objects.requireNonNull(builder.fonts, "fonts");
        roles = Map.copyOf(builder.roles);
        pageTemplate = builder.pageTemplate;
        navigation = builder.navigation;
        notes = List.copyOf(builder.notes);
    }

    public static Builder builder(FontGroup fonts) { return new Builder(fonts); }

    public static final class Builder {
        private final FontGroup fonts;
        private final Map<String, FontGroup> roles = new HashMap<>();
        private PageTemplate pageTemplate = PageTemplate.EMPTY;
        private Navigation navigation = Navigation.EMPTY;
        private List<Note> notes = List.of();

        private Builder(FontGroup fonts) { this.fonts = Objects.requireNonNull(fonts, "fonts"); }

        /** Default template for every section; explicit layout overloads retain their own settings. */
        public Builder pageTemplate(PageTemplate value) { pageTemplate = Objects.requireNonNull(value, "pageTemplate"); return this; }
        public Builder pageTemplate(java.util.function.Consumer<PageTemplate.Builder> configure) {
            var value = pageTemplate.toBuilder(); configure.accept(value); return pageTemplate(value.build());
        }
        public Builder navigation(Navigation value) { navigation = Objects.requireNonNull(value, "navigation"); return this; }
        public Builder notes(List<Note> values) { notes = List.copyOf(values); return this; }

        public Builder fontRole(String name, FontGroup group) {
            if (Objects.requireNonNull(name, "name").isBlank()) { throw new IllegalArgumentException("font role is blank"); }
            if (roles.putIfAbsent(name, Objects.requireNonNull(group, "group")) != null) {
                throw new IllegalArgumentException("duplicate font role");
            }
            return this;
        }

        public DocumentEngine build() { return new DocumentEngine(this); }
    }

    public ComponentDocumentLayout layout(Document document) {
        return layout(new SectionedDocument(document.metadata(), List.of(new Section("document", document.pageSettings(), document.blocks(), 0))),
                List.of(SectionTemplate.uniform(pageTemplate)), navigation, notes);
    }

    /** Uses the document's semantic header/footer declarations. A configured Java pageTemplate conflicts
     * and is rejected; use PageRegionTemplates.from() with explicit renderer templates to combine them.
     */
    public ComponentDocumentLayout layout(PageDocument document) {
        Objects.requireNonNull(document, "document");
        if (!pageTemplate.equals(PageTemplate.EMPTY)) {
            throw new IllegalStateException("page document conflicts with configured page template");
        }
        var body = document.document();
        return layout(new SectionedDocument(body.metadata(), List.of(new Section("document", body.pageSettings(), body.blocks(), 0))),
                List.of(PageRegionTemplates.sectionFrom(document.pageRegions())), navigation, notes);
    }

    public ComponentDocumentLayout layout(SectionedDocument document, List<SectionTemplate> templates, Navigation navigation, List<Note> notes) {
        return new ComponentDocumentRenderer().layout(document, templates, navigation, fonts, roles, notes);
    }

    /** Uses each section's model regions when present, otherwise the configured Java template.
     * Model regions and a nonempty configured Java template conflict rather than silently overwriting.
     */
    public ComponentDocumentLayout layout(SectionedDocument document) {
        boolean hasRegions = document.sections().stream().anyMatch(section -> !section.pageRegions().equals(com.mirakyux.export4j.model.PageRegions.EMPTY));
        if (hasRegions && !pageTemplate.equals(PageTemplate.EMPTY)) {
            throw new IllegalStateException("section regions conflict with configured page template");
        }
        return layout(document, document.sections().stream().map(section -> hasRegions
                ? PageRegionTemplates.sectionFrom(section.pageRegions()) : SectionTemplate.uniform(pageTemplate)).toList(), navigation, notes);
    }

    /** Creates a task-confined export request; no layout or I/O occurs until to() is called. */
    public ExportRequest export(Document document) {
        Objects.requireNonNull(document, "document");
        return new ExportRequest(() -> layout(document));
    }

    /** Synchronous export of body and page regions; no layout or I/O until to() is called. */
    public ExportRequest export(PageDocument document) {
        Objects.requireNonNull(document, "document");
        return new ExportRequest(() -> layout(document));
    }

    public ExportRequest export(SectionedDocument document) {
        Objects.requireNonNull(document, "document");
        return new ExportRequest(() -> layout(document));
    }

    /** Explicit layout/backend conversion target. format is the caller's actual target key (e.g. PDF),
     * used for shared loss policy resolution. Backend/thread-safety ownership remains with the caller.
     * Notes come from conversion input; preconfigured notes are rejected to avoid hidden source data.
     * Encoding and missing-glyph loss checks finish before conversion publishes a final destination.
     */
    public DocumentConversion.Target conversionTarget(String format, DocumentBackend backend) {
        Objects.requireNonNull(backend, "backend");
        if (!notes.isEmpty()) { throw new IllegalStateException("conversion notes must be supplied in conversion input"); }
        return new DocumentConversion.Target(format, (document, sourceNotes, policy, output) -> {
            var builder = DocumentEngine.builder(fonts).pageTemplate(pageTemplate).navigation(navigation).notes(sourceNotes);
            roles.forEach(builder::fontRole);
            var rendered = builder.build().export(document).backend(backend).to(output);
            var issues = new ArrayList<ProcessingIssue>();
            for (var diagnostic : rendered.contextualDiagnostics()) {
                var text = diagnostic.diagnostic();
                if (text.code() != TextDiagnostic.Code.MISSING_GLYPH_REPLACED) { continue; }
                var rule = policy.resolve(format, "MISSING_GLYPH_REPLACED", "EXPORT");
                var issue = new ProcessingIssue("EXPORT", "MODEL", format, "MISSING_GLYPH_REPLACED", "EXPORT",
                        new ProcessingIssue.Location("", diagnostic.region(), diagnostic.blockId(),
                                diagnostic.sourcePath(), text.sourceStart(), text.sourceEnd()),
                        "source glyph replaced by explicitly configured placeholder", rule.action(), rule.treatment(), List.of("SOURCE_GLYPH_REPLACED"));
                if (rule.action() == ProcessingPolicy.Action.ERROR) {
                    throw new ProcessingException(issue);
                }
                if (rule.treatment() != ProcessingPolicy.Treatment.DEGRADE) {
                    throw new IllegalArgumentException("glyph replacement only supports DEGRADE");
                }
                if (issues.size() >= 10_000) { throw new IllegalArgumentException("layout conversion report budget exceeded"); }
                issues.add(issue);
            }
            return new ProcessingReport(ProcessingReport.Phase.ACTUAL, issues);
        });
    }

    /** Creates a single-pass table export with supported rich text, images, passive links and page fields,
     * using this engine's explicit font-role snapshot.
     * Body rows are opened only by to(), on the caller thread.
     * Configured page regions, navigation and notes are unsupported and rejected here.
     * The factory transfers ownership of its newly opened source to this export; borrowed external
     * connections remain the source implementation's responsibility. A reusable request needs a fresh source per call.
     */
    public StreamingExportRequest export(StreamingTable table, TableRowSource.Factory source) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(source, "source");
        if (!pageTemplate.equals(PageTemplate.EMPTY) || !navigation.equals(Navigation.EMPTY)
                || !notes.isEmpty()) {
            throw new IllegalStateException("streaming tables do not support configured page regions, navigation or notes");
        }
        return new StreamingExportRequest(table, source, fonts, roles);
    }

    /** Explicit stable-source replay with bounded semantic groups, prefix column probing,
     * final total pages, future targets, navigation and declared group footnotes. Every source
     * pass is owned and closed before the next pass. Stream failures may leave partial bytes;
     * Path output preserves the previous target on every failed planning or writing pass.
     */
    public StreamingReplayRequest exportReplay(StreamingTable table, TableRowSource.Factory source) {
        if (!pageTemplate.equals(PageTemplate.EMPTY) || !navigation.equals(Navigation.EMPTY) || !notes.isEmpty()) {
            throw new IllegalStateException("replay output uses its explicit StreamingReplayPlan");
        }
        return new StreamingReplayRequest(table, source, fonts, roles);
    }

    /** One-pass bounded semantic cells and splitting row groups. PAGE uses actual physical pages;
     * PAGES needs an exact expectedPages declaration. Future references require exportReplay.
     * The source is opened once and is closed before successful writer.finish.
     */
    public StreamingReplayRequest exportSemantic(StreamingTable table, TableRowSource.Factory source) {
        if (!pageTemplate.equals(PageTemplate.EMPTY) || !navigation.equals(Navigation.EMPTY) || !notes.isEmpty()) {
            throw new IllegalStateException("single-pass semantic output requires explicit table-only settings");
        }
        return new StreamingReplayRequest(table, source, fonts, roles, false);
    }

    /** Task-confined synchronous export. Keeps no full-document layout, display list or warning list.
     * The selected backend may retain document state; PDFBox still retains its PDF page tree.
     */
    public static final class StreamingExportRequest {
        private final StreamingTable table;
        private final TableRowSource.Factory source;
        private final FontGroup fonts;
        private final Map<String, FontGroup> roles;
        private PageBackend backend;
        private StreamingTableLimits limits = StreamingTableLimits.DEFAULT;
        private Consumer<ContextualDiagnostic> diagnostics = ignored -> { };
        private ExecutionOptions options = ExecutionOptions.DEFAULT;

        private StreamingExportRequest(StreamingTable table, TableRowSource.Factory source, FontGroup fonts,
                Map<String, FontGroup> roles) {
            this.table = table; this.source = source; this.fonts = fonts;
            this.roles = roles;
        }
        public StreamingExportRequest backend(PageBackend value) { backend = Objects.requireNonNull(value, "backend"); return this; }
        public StreamingExportRequest limits(StreamingTableLimits value) { limits = Objects.requireNonNull(value, "limits"); return this; }
        public StreamingExportRequest limits(Consumer<StreamingTableLimits.Builder> configure) {
            var value = limits.toBuilder(); configure.accept(value); return limits(value.build());
        }
        /** Warnings are delivered synchronously with cell-local UTF-16 offsets. Callback failure aborts export. */
        public StreamingExportRequest diagnostics(Consumer<ContextualDiagnostic> value) {
            diagnostics = Objects.requireNonNull(value, "diagnostics"); return this;
        }
        public StreamingExportRequest options(ExecutionOptions value) { options = Objects.requireNonNull(value, "options"); return this; }
        public StreamingExportRequest options(Consumer<ExecutionOptions.Builder> configure) {
            var value = options.toBuilder(); configure.accept(value); return options(value.build());
        }
        /** Caller owns output; failure can leave partial bytes. Success includes closing the owned row source and writer. */
        public StreamingExportSummary to(OutputStream output) throws IOException {
            Objects.requireNonNull(output, "output");
            var selectedBackend = Objects.requireNonNull(backend, "backend must be configured");
            var selectedLimits = limits;
            var selectedDiagnostics = diagnostics;
            var selectedOptions = options;
            return ExecutionContext.execute(selectedOptions, () -> {
                var counted = new CountingOutput(output);
                long[] commands = {0};
                StreamingTableLayouter.Summary result;
                try (var writer = Objects.requireNonNull(selectedBackend.open(table.metadata(), ExecutionContext.output(counted)), "page writer")) {
                    ExecutionContext.checkpoint();
                    try (var rows = Objects.requireNonNull(source.open(), "row source")) {
                        ExecutionContext.checkpoint();
                        result = new StreamingTableLayouter().layout(table, rows, fonts, roles, new StreamingTableLayouter.PageConsumer() {
                            @Override public void accept(com.mirakyux.export4j.layout.LayoutPage page) throws IOException {
                                accept(page, List.of());
                            }
                            @Override public void accept(com.mirakyux.export4j.layout.LayoutPage page,
                                    List<com.mirakyux.export4j.graphics.DocumentExtras.ExternalLink> links) throws IOException {
                                ExecutionContext.checkpoint();
                                var display = DocumentPainter.paintPage(page);
                                commands[0] = Math.addExact(commands[0], display.commands().size());
                                writer.accept(display, links);
                                ExecutionContext.checkpoint();
                            }
                        }, diagnostic -> selectedDiagnostics.accept(new ContextualDiagnostic(diagnostic.blockId(),
                                diagnostic.pageIndex(), diagnostic.region(), diagnostic.rowIndex(),
                                diagnostic.columnIndex(), diagnostic.diagnostic())), selectedLimits);
                    }
                    ExecutionContext.progress("OUTPUT", table.id(), result.pages() - 1, 0, 1);
                    writer.finish();
                }
                ExecutionContext.progress("COMPLETE", table.id(), result.pages() - 1, 1, 1);
                return new StreamingExportSummary(result.bodyRows(), result.pages(), result.sourceCells(),
                        result.sourceCodePoints(), result.outputLines(), commands[0], result.diagnostics(), counted.count);
            });
        }
        /** Same-directory atomic replacement. Every failure preserves the old target and cleans up temporary bytes. */
        public StreamingExportSummary to(Path target) throws IOException {
            Objects.requireNonNull(backend, "backend must be configured");
            return writeAtomically(target, this::to);
        }
    }

    private static final class CountingOutput extends OutputStream {
        private final OutputStream target;
        private long count;
        private CountingOutput(OutputStream target) { this.target = target; }
        @Override public void write(int value) throws IOException { target.write(value); count++; }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            target.write(bytes, offset, length); count = Math.addExact(count, length);
        }
        @Override public void flush() throws IOException { target.flush(); }
        @Override public void close() { /* Borrowed stream. */ }
    }

    /** Explicit backend, optional execution controls, borrowed stream or atomic path destination. */
    public static final class ExportRequest {
        private final Supplier<ComponentDocumentLayout> layout;
        private DocumentBackend backend;
        private ExecutionOptions options = ExecutionOptions.DEFAULT;
        private ExportRequest(Supplier<ComponentDocumentLayout> layout) { this.layout = layout; }
        public ExportRequest backend(DocumentBackend value) { backend = Objects.requireNonNull(value, "backend"); return this; }
        public ExportRequest options(ExecutionOptions value) { options = Objects.requireNonNull(value, "options"); return this; }
        public ExportRequest options(java.util.function.Consumer<ExecutionOptions.Builder> configure) {
            var value = options.toBuilder(); configure.accept(value); return options(value.build());
        }
        /** Caller owns the stream; a failure can leave partial bytes. */
        public ComponentDocumentLayout to(OutputStream output) throws IOException {
            return writeLayout(layout, Objects.requireNonNull(backend, "backend must be configured"), output, options);
        }
        /** Parent directory must exist. Failure preserves the old destination and removes temporary bytes. */
        public ComponentDocumentLayout to(Path target) throws IOException {
            Objects.requireNonNull(backend, "backend must be configured");
            return writeAtomically(target, this::to);
        }
    }

    /** Layout completes before output begins. Caller owns output and chooses a backend explicitly. */
    public ComponentDocumentLayout write(Document document, DocumentBackend backend, OutputStream output) throws IOException {
        return write(document, backend, output, ExecutionOptions.DEFAULT);
    }

    /** One timeout covers layout and output. Cancellation is cooperative; failed stream output may be partial. */
    public ComponentDocumentLayout write(Document document, DocumentBackend backend, OutputStream output, ExecutionOptions options) throws IOException {
        return writeLayout(() -> layout(document), backend, output, options);
    }

    /** Sectioned output with configured default page regions/navigation; caller owns the stream. */
    public ComponentDocumentLayout write(SectionedDocument document, DocumentBackend backend, OutputStream output) throws IOException {
        return write(document, backend, output, ExecutionOptions.DEFAULT);
    }

    public ComponentDocumentLayout write(SectionedDocument document, DocumentBackend backend, OutputStream output, ExecutionOptions options) throws IOException {
        return writeLayout(() -> layout(document), backend, output, options);
    }

    private static ComponentDocumentLayout writeLayout(Supplier<ComponentDocumentLayout> layout, DocumentBackend backend,
            OutputStream output, ExecutionOptions options) throws IOException {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(output, "output");
        return ExecutionContext.execute(options, () -> {
            ExecutionContext.progress("LAYOUT", "", -1, 0, 1);
            var result = layout.get();
            ExecutionContext.progress("OUTPUT", "", -1, 0, result.display().pages().size());
            backend.write(result.display(), ExecutionContext.output(output));
            ExecutionContext.progress("COMPLETE", "", -1, 1, 1);
            return result;
        });
    }

    /** Atomic path output with default execution options (no timeout/byte cap). Parent directory must already exist. */
    public ComponentDocumentLayout write(Document document, DocumentBackend backend, Path target) throws IOException {
        return writeAtomically(document, backend, target, ExecutionOptions.DEFAULT);
    }

    /** Atomic path output with caller-supplied execution limits. */
    public ComponentDocumentLayout write(Document document, DocumentBackend backend, Path target, ExecutionOptions options) throws IOException {
        return writeAtomically(document, backend, target, options);
    }

    public ComponentDocumentLayout writeAtomically(Document document, DocumentBackend backend, Path target) throws IOException {
        return writeAtomically(document, backend, target, ExecutionOptions.DEFAULT);
    }

    /** Atomic sectioned output with configured default page regions/navigation; parent directory must exist. */
    public ComponentDocumentLayout write(SectionedDocument document, DocumentBackend backend, Path target) throws IOException {
        return write(document, backend, target, ExecutionOptions.DEFAULT);
    }

    public ComponentDocumentLayout write(SectionedDocument document, DocumentBackend backend, Path target, ExecutionOptions options) throws IOException {
        return writeAtomically(target, output -> write(document, backend, output, options));
    }

    /** Writes a sibling temporary file, then atomically replaces target after successful completion.
     * Requires filesystem atomic-move support. Failure/cancellation preserves the existing target and deletes the temporary file.
     */
    public ComponentDocumentLayout writeAtomically(Document document, DocumentBackend backend, Path target, ExecutionOptions options) throws IOException {
        return writeAtomically(target, output -> write(document, backend, output, options));
    }

    @FunctionalInterface
    interface Export<T> { T write(OutputStream output) throws IOException; }

    static <T> T writeAtomically(Path target, Export<T> export) throws IOException {
        Path absolute = Objects.requireNonNull(target, "target").toAbsolutePath();
        Path temporary = Files.createTempFile(absolute.getParent(), ".export4j-", ".tmp");
        try {
            T result;
            try (var output = Files.newOutputStream(temporary)) { result = export.write(output); }
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            try { Files.deleteIfExists(temporary); }
            catch (IOException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
}
