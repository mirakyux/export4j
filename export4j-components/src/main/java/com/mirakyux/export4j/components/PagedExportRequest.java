// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.PageBackend;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.SectionedDocument;

/** Task-confined page export configuration. The immutable source is replayed for planning and output;
 * custom components must produce the same source, geometry and drawing on each pass. Completed page
 * layouts are released after synchronous backend acceptance. Fonts and stream destinations remain borrowed.
 */
public final class PagedExportRequest {
    private final SectionedDocument source;
    private final List<SectionTemplate> templates;
    private final Navigation navigation;
    private final List<Note> notes;
    private final FontGroup fonts;
    private final Map<String, FontGroup> roles;
    private PageBackend backend;
    private DocumentLayoutLimits limits = DocumentLayoutLimits.DEFAULT;
    private ComponentLimits componentLimits = ComponentLimits.DEFAULT;
    private ExecutionOptions options = ExecutionOptions.DEFAULT;
    private Consumer<ContextualDiagnostic> diagnostics = ignored -> { };

    PagedExportRequest(SectionedDocument source, List<SectionTemplate> templates, Navigation navigation, List<Note> notes,
            FontGroup fonts, Map<String, FontGroup> roles) {
        this.source = Objects.requireNonNull(source, "source");
        this.templates = List.copyOf(templates);
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.notes = List.copyOf(notes);
        this.fonts = Objects.requireNonNull(fonts, "fonts");
        this.roles = Map.copyOf(roles);
    }

    /** Selects a non-null borrowed backend. Each to() owns and closes the writer it opens; the backend remains borrowed. */
    public PagedExportRequest backend(PageBackend value) { backend = Objects.requireNonNull(value, "backend"); return this; }
    /** Sets non-null source, page and presentation-line budgets shared by all sections and generated content. */
    public PagedExportRequest limits(DocumentLayoutLimits value) { limits = Objects.requireNonNull(value, "limits"); return this; }
    /** Sets non-null composition and interaction budgets shared by all output pages. */
    public PagedExportRequest componentLimits(ComponentLimits value) { componentLimits = Objects.requireNonNull(value, "componentLimits"); return this; }
    /** Sets non-null synchronous cancellation, timeout, progress and byte controls, snapshotted when to() starts. */
    public PagedExportRequest options(ExecutionOptions value) { options = Objects.requireNonNull(value, "options"); return this; }
    /** Runs a non-null callback now on an independent options builder, then stores its immutable result.
     * The callback and this mutable request are confined to the calling thread; no builder is retained.
     */
    public PagedExportRequest options(Consumer<ExecutionOptions.Builder> configure) {
        var value = options.toBuilder();
        Objects.requireNonNull(configure, "configure").accept(value);
        return options(value.build());
    }
    /** Sets a non-null callback receiving each final-page diagnostic once on the calling thread.
     * Callback exceptions abort the export; callback changes to this request affect only a later to() call.
     */
    public PagedExportRequest diagnostics(Consumer<ContextualDiagnostic> value) {
        diagnostics = Objects.requireNonNull(value, "diagnostics"); return this;
    }

    /** Snapshots all request settings before invoking callbacks. The non-null stream remains caller-owned.
     * Failure may leave partial bytes; the writer is always closed. This mutable request is task-confined.
     */
    public PagedExportSummary to(OutputStream target) throws IOException {
        return write(snapshot(), Objects.requireNonNull(target, "target"));
    }

    /** Snapshots settings and atomically publishes to a non-null local path whose parent already exists.
     * Failure preserves the old destination and removes temporary output. Calls remain task-confined.
     */
    public PagedExportSummary to(Path target) throws IOException {
        var snapshot = snapshot();
        return DocumentEngine.writeAtomically(target, output -> write(snapshot, output));
    }

    /** Non-null local file destination with the same snapshot and atomic publication contract as to(Path). */
    public PagedExportSummary to(File target) throws IOException { return to(Objects.requireNonNull(target, "target").toPath()); }
    /** Non-null local filesystem path with the same snapshot and atomic publication contract as to(Path). */
    public PagedExportSummary to(String target) throws IOException { return to(Path.of(Objects.requireNonNull(target, "target"))); }

    private record Snapshot(PageBackend backend, DocumentLayoutLimits limits, ComponentLimits componentLimits,
            ExecutionOptions options, Consumer<ContextualDiagnostic> diagnostics) { }

    private Snapshot snapshot() {
        return new Snapshot(Objects.requireNonNull(backend, "backend must be configured"), limits, componentLimits, options, diagnostics);
    }

    private PagedExportSummary write(Snapshot snapshot, OutputStream target) throws IOException {
        return ExecutionContext.execute(snapshot.options(), () -> new PagedDocumentRenderer(snapshot.limits(), snapshot.componentLimits())
                .write(source, templates, navigation, notes, fonts, roles, snapshot.backend(), ExecutionContext.output(target), snapshot.diagnostics()));
    }
}
