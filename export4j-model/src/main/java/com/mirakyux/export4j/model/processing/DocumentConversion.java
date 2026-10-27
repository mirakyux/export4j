// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model.processing;

import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.edit.DocumentEditor;
import com.mirakyux.export4j.model.edit.EditLimits;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Synchronous import/edit/export orchestration without dependencies on file adapters or layout.
 * Standard adapters provide real sources/targets. Custom callbacks are trusted caller code and must
 * report all losses, obey checkpoints and respect stream ownership. No background work occurs.
 */
public final class DocumentConversion {
    private DocumentConversion() { }
    /** Called once by prepare(). Source implementations own any streams they open. */
    @FunctionalInterface public interface Reader { Input read(ProcessingPolicy policy) throws IOException; }
    /** Called once by prepare(). Output is borrowed, bounded, nonclosing and not a final destination.
     * Returns actual export losses including those hidden from warning presentation.
     */
    @FunctionalInterface public interface Writer {
        ProcessingReport write(SectionedDocument document, List<Note> notes, ProcessingPolicy policy, OutputStream output) throws IOException;
    }
    public record Input(SectionedDocument document, List<Note> notes, ProcessingReport report) {
        public Input {
            Objects.requireNonNull(document, "document"); notes = List.copyOf(notes); actual(report);
        }
        public static Input of(Document document, ProcessingReport report) {
            Objects.requireNonNull(document, "document");
            return new Input(new SectionedDocument(document.metadata(), List.of(new Section("document", document.pageSettings(), document.blocks(), 0))), List.of(), report);
        }
    }
    public record Target(String format, Writer writer) {
        public Target { key(format); Objects.requireNonNull(writer, "writer"); }
    }
    /** Positive aggregate budgets. Output bytes bound the prepared encoding; this is not a hard RSS cap.
     * Edit operations also obey EditLimits, import/target adapters retain their own budgets.
     */
    public record Limits(int maxEditCallbacks, int maxIssues, int maxOutputBytes) {
        public static final Limits DEFAULT = new Limits(1000, 10_000, 64 * 1024 * 1024);
        public Limits { if (maxEditCallbacks <= 0 || maxIssues <= 0 || maxOutputBytes <= 0) { throw new IllegalArgumentException("invalid conversion limits"); } }
    }
    public record Result(SectionedDocument document, List<Note> notes, List<DocumentEditor.Change> changes,
            ProcessingReport report, long bytesWritten) {
        public Result {
            Objects.requireNonNull(document, "document"); notes = List.copyOf(notes); changes = List.copyOf(changes); actual(report);
            if (bytesWritten < 0) { throw new IllegalArgumentException("negative conversion byte count"); }
        }
    }
    public static Request from(String sourceFormat, Reader reader) { return new Request(key(sourceFormat), Objects.requireNonNull(reader, "reader")); }
    public static Request from(Document source) {
        Objects.requireNonNull(source, "source");
        return from("MODEL", ignored -> Input.of(source, new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of())));
    }
    public static Request from(SectionedDocument source, List<Note> notes) {
        var snapshot = new Input(source, notes, new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of()));
        return from("MODEL", ignored -> snapshot);
    }
    /** Mutable task-confined plan. prepare snapshots all configuration and calls source, edits and target once. */
    public static final class Request {
        private final String sourceFormat;
        private final Reader reader;
        private final List<Consumer<DocumentEditor.Request<SectionedDocument>>> edits = new ArrayList<>();
        private ProcessingPolicy policy = ProcessingPolicy.STRICT;
        private Limits limits = Limits.DEFAULT;
        private EditLimits editLimits = EditLimits.DEFAULT;
        private ExecutionOptions execution = ExecutionOptions.DEFAULT;
        private Request(String sourceFormat, Reader reader) { this.sourceFormat = sourceFormat; this.reader = reader; }
        public Request policy(ProcessingPolicy value) { policy = Objects.requireNonNull(value, "policy"); return this; }
        public Request limits(Limits value) { limits = Objects.requireNonNull(value, "limits"); return this; }
        public Request editLimits(EditLimits value) { editLimits = Objects.requireNonNull(value, "editLimits"); return this; }
        public Request execution(ExecutionOptions value) { execution = Objects.requireNonNull(value, "execution"); return this; }
        /** Callback runs synchronously once during prepare; retaining/mutating it later cannot affect Prepared. */
        public Request edit(Consumer<DocumentEditor.Request<SectionedDocument>> value) {
            if (edits.size() >= limits.maxEditCallbacks()) { throw new IllegalArgumentException("conversion edit callback budget exceeded"); }
            edits.add(Objects.requireNonNull(value, "edit")); return this;
        }
        public Prepared prepare(Target target) throws IOException {
            Objects.requireNonNull(target, "target");
            var policySnapshot = policy; var limitSnapshot = limits; var editLimitSnapshot = editLimits;
            var executionSnapshot = execution; var editSnapshot = List.copyOf(edits);
            return ExecutionContext.execute(executionSnapshot, () -> {
                if (editSnapshot.size() > limitSnapshot.maxEditCallbacks()) { throw new IllegalArgumentException("conversion edit callback budget exceeded"); }
                ExecutionContext.progress("IMPORT", "", -1, 0, 3);
                var imported = Objects.requireNonNull(reader.read(policySnapshot), "import result");
                var issues = new ArrayList<ProcessingIssue>(); append(issues, imported.report(), limitSnapshot);
                ExecutionContext.progress("EDIT", "", -1, 1, 3);
                var editing = DocumentEditor.edit(imported.document()).notes(imported.notes()).policy(policySnapshot).limits(editLimitSnapshot);
                for (var edit : editSnapshot) { ExecutionContext.checkpoint(); edit.accept(editing); }
                var edited = editing.execute(); append(issues, edited.report(), limitSnapshot);
                ExecutionContext.progress("EXPORT", "", -1, 2, 3);
                var bytes = new ByteArrayOutputStream();
                var bounded = new OutputStream() {
                    private void reserve(int count) {
                        ExecutionContext.checkpoint();
                        if (count > limitSnapshot.maxOutputBytes() - bytes.size()) { throw new IllegalArgumentException("conversion encoding byte budget exceeded"); }
                    }
                    @Override public void write(int value) { reserve(1); bytes.write(value); }
                    @Override public void write(byte[] value, int offset, int count) { Objects.checkFromIndexSize(offset, count, value.length); reserve(count); bytes.write(value, offset, count); }
                    @Override public void close() { /* Borrowed. */ }
                };
                var exported = target.writer().write(edited.document(), edited.notes(), policySnapshot, ExecutionContext.output(bounded));
                append(issues, exported, limitSnapshot);
                ExecutionContext.progress("PREPARED", "", -1, 3, 3);
                var result = new Result(edited.document(), edited.notes(), edited.changes(),
                        new ProcessingReport(ProcessingReport.Phase.ACTUAL, issues), bytes.size());
                return new Prepared(sourceFormat, target.format(), bytes.toByteArray(), result, exported, executionSnapshot);
            });
        }
    }
    /** Exact, bounded encoding and model snapshot. Preflight includes target losses even in untouched
     * content. Publication never reruns parsing, editing or encoding. Each I/O call observes cancellation.
     */
    public static final class Prepared {
        private final String sourceFormat;
        private final String targetFormat;
        private final byte[] bytes;
        private final Result result;
        private final ProcessingReport exportReport;
        private final ExecutionOptions execution;
        private Prepared(String sourceFormat, String targetFormat, byte[] bytes, Result result, ProcessingReport exportReport, ExecutionOptions execution) {
            this.sourceFormat = sourceFormat; this.targetFormat = targetFormat; this.bytes = bytes; this.result = result; this.exportReport = exportReport; this.execution = execution;
        }
        public String sourceFormat() { return sourceFormat; }
        public String targetFormat() { return targetFormat; }
        public SectionedDocument candidate() { return result.document(); }
        public ProcessingReport preview() { return new ProcessingReport(ProcessingReport.Phase.PREVIEW, result.report().issues()); }
        /** Actual diagnostics of this target encoding only, excluding inherited import/edit history.
         * This does not imply successful publication to a final destination. */
        public ProcessingReport exportReport() { return exportReport; }
        /** Borrowed stream is not flushed/closed. Physical I/O failure may leave partial stream bytes. */
        public Result to(OutputStream output) throws IOException {
            Objects.requireNonNull(output, "output");
            return ExecutionContext.execute(execution, () -> { ExecutionContext.output(output).write(bytes); return result; });
        }
        /** Same-directory temporary and atomic replacement; failed writes preserve the old target.
         * No non-atomic copy fallback. Parent directory must already exist.
         */
        public Result to(Path output) throws IOException {
            Path target = Objects.requireNonNull(output, "output").toAbsolutePath();
            Path temporary = Files.createTempFile(target.getParent(), ".export4j-conversion-", ".tmp"); Throwable failure = null;
            try {
                Result completed = ExecutionContext.execute(execution, () -> {
                    try (var stream = Files.newOutputStream(temporary)) { return to(stream); }
                });
                // The scope's final cancellation/timeout check precedes the commit point.
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return completed;
            } catch (IOException | RuntimeException | Error exception) { failure = exception; throw exception; }
            finally {
                try { Files.deleteIfExists(temporary); }
                catch (IOException exception) { if (failure == null) { throw exception; } failure.addSuppressed(exception); }
            }
        }
    }
    private static void append(List<ProcessingIssue> destination, ProcessingReport report, Limits limits) {
        actual(report);
        if (report.issues().size() > limits.maxIssues() - destination.size()) { throw new IllegalArgumentException("conversion report budget exceeded"); }
        destination.addAll(report.issues()); ExecutionContext.checkpoint();
    }
    private static void actual(ProcessingReport report) {
        if (Objects.requireNonNull(report, "report").phase() != ProcessingReport.Phase.ACTUAL) { throw new IllegalArgumentException("conversion stage must return actual report"); }
    }
    private static String key(String value) {
        if (Objects.requireNonNull(value, "format").isBlank()) { throw new IllegalArgumentException("empty conversion format"); } return value;
    }
}
