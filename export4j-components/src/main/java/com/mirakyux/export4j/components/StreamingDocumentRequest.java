// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.PageBackend;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.BlockSource;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageSettings;

/** Task-confined incremental ordinary-document output. Each to() owns a fresh source session and page writer;
 * fonts, backend and stream destinations remain borrowed. The single-pass form requires an exact positive
 * page total. Explicit replay opens a stable snapshot twice and retains compact proofs rather than source blocks.
 * Maximum source blocks/keep chains and global source IDs still require bounded memory.
 */
public final class StreamingDocumentRequest {
    private final PageSettings settings;
    private final BlockSource.Factory source;
    private final FontGroup fonts;
    private final Map<String, FontGroup> roles;
    private final PageTemplate template;
    private final boolean replay;
    private DocumentMetadata metadata = DocumentMetadata.EMPTY;
    private PageBackend backend;
    private int expectedPages;
    private DocumentLayoutLimits limits = DocumentLayoutLimits.DEFAULT;
    private ComponentLimits componentLimits = ComponentLimits.DEFAULT;
    private ExecutionOptions options = ExecutionOptions.DEFAULT;
    private Consumer<ContextualDiagnostic> diagnostics = ignored -> { };

    StreamingDocumentRequest(PageSettings settings, BlockSource.Factory source, FontGroup fonts,
            Map<String, FontGroup> roles, PageTemplate template, boolean replay) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.source = Objects.requireNonNull(source, "source");
        this.fonts = Objects.requireNonNull(fonts, "fonts");
        this.roles = Map.copyOf(roles);
        this.template = Objects.requireNonNull(template, "template");
        this.replay = replay;
    }

    /** Supplies non-null immutable document metadata; defaults to EMPTY. */
    public StreamingDocumentRequest metadata(DocumentMetadata value) { metadata = Objects.requireNonNull(value, "metadata"); return this; }
    /** Borrows a non-null backend; the request closes each page writer it opens. */
    public StreamingDocumentRequest backend(PageBackend value) { backend = Objects.requireNonNull(value, "backend"); return this; }
    /** Declares an exact positive physical page total, verified before writer.finish. Required for the
     * single-pass form and optional as an additional assertion for explicit replay. No implicit replay occurs.
     */
    public StreamingDocumentRequest expectedPages(int value) {
        if (value <= 0) { throw new IllegalArgumentException("expected pages must be positive"); }
        expectedPages = value; return this;
    }
    /** Sets non-null source, structure, text, line and page budgets accumulated over the complete stream. */
    public StreamingDocumentRequest limits(DocumentLayoutLimits value) { limits = Objects.requireNonNull(value, "limits"); return this; }
    /** Sets non-null page composition, drawing and interaction budgets accumulated over all pages. */
    public StreamingDocumentRequest componentLimits(ComponentLimits value) { componentLimits = Objects.requireNonNull(value, "componentLimits"); return this; }
    /** Sets synchronous execution controls; to() snapshots the settings before invoking any callback. */
    public StreamingDocumentRequest options(ExecutionOptions value) { options = Objects.requireNonNull(value, "options"); return this; }
    /** Configures a new options builder synchronously; the mutable builder and configuration callback are not retained. */
    public StreamingDocumentRequest options(Consumer<ExecutionOptions.Builder> configure) {
        var value = options.toBuilder();
        Objects.requireNonNull(configure, "configure").accept(value);
        return options(value.build());
    }
    /** Receives final-output diagnostics once on the calling thread. Exceptions abort output. */
    public StreamingDocumentRequest diagnostics(Consumer<ContextualDiagnostic> value) {
        diagnostics = Objects.requireNonNull(value, "diagnostics"); return this;
    }

    /** Writes synchronously to a borrowed non-null stream. Late source/replay/budget failures can leave
     * partial bytes. Sources and writers are closed on failure; the destination remains open.
     */
    public PagedExportSummary to(OutputStream target) throws IOException {
        var snapshot = snapshot();
        return write(snapshot, Objects.requireNonNull(target, "target"));
    }
    /** Atomically publishes a local target whose parent exists. Any source, close, replay or output failure
     * preserves the previous destination and removes the temporary output. Configuration is snapshotted first.
     */
    public PagedExportSummary to(Path target) throws IOException {
        var snapshot = snapshot();
        return DocumentEngine.writeAtomically(target, output -> write(snapshot, output));
    }
    /** Non-null local file target with the same snapshot and atomic publication contract as to(Path). */
    public PagedExportSummary to(File target) throws IOException { return to(Objects.requireNonNull(target, "target").toPath()); }
    /** Non-null local filesystem target with the same snapshot and atomic publication contract as to(Path). */
    public PagedExportSummary to(String target) throws IOException { return to(Path.of(Objects.requireNonNull(target, "target"))); }

    private record Snapshot(DocumentMetadata metadata, PageBackend backend, int expectedPages,
            DocumentLayoutLimits limits, ComponentLimits componentLimits, ExecutionOptions options,
            Consumer<ContextualDiagnostic> diagnostics) { }

    private Snapshot snapshot() {
        if (!replay && expectedPages == 0) { throw new IllegalStateException("single-pass ordinary blocks require exact expectedPages"); }
        if (expectedPages > limits.maxPages() || expectedPages > 100_000) {
            throw new IllegalArgumentException("expected page total exceeds the page budget");
        }
        return new Snapshot(metadata, Objects.requireNonNull(backend, "backend must be configured"), expectedPages,
                limits, componentLimits, options, diagnostics);
    }

    private PagedExportSummary write(Snapshot snapshot, OutputStream target) throws IOException {
        return ExecutionContext.execute(snapshot.options(), () -> new StreamingDocumentRenderer(snapshot.limits(), snapshot.componentLimits())
                .write(settings, source, fonts, roles, template, snapshot.metadata(), snapshot.backend(),
                        ExecutionContext.output(target), snapshot.expectedPages(), replay, snapshot.diagnostics()));
    }
}
