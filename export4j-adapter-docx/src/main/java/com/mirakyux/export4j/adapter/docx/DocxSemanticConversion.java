// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Native import/edit/rebuild or explicit native-to-portable conversion. Prepared objects hold only
 * the typed document and one bounded newly generated encoding; publication never reruns callbacks.
 */
public final class DocxSemanticConversion {
    private DocxSemanticConversion() { }
    static Request request(DocxAdapter adapter, DocxLimits limits, byte[] input) {
        Objects.requireNonNull(input, "input"); if (input.length > limits.maxInputBytes()) { throw new IllegalArgumentException("native input byte budget exceeded"); }
        return new Request(adapter, limits, input.clone());
    }
    public static final class Request {
        private final DocxAdapter adapter; private final DocxLimits limits; private final byte[] input;
        private final List<Consumer<DocxSemanticEditor.Request>> edits = new ArrayList<>();
        private ProcessingPolicy policy = ProcessingPolicy.STRICT; private ExecutionOptions execution = ExecutionOptions.DEFAULT;
        private Request(DocxAdapter adapter, DocxLimits limits, byte[] input) { this.adapter = adapter; this.limits = limits; this.input = input; }
        public Request policy(ProcessingPolicy value) { policy = Objects.requireNonNull(value, "policy"); return this; }
        public Request execution(ExecutionOptions value) { execution = Objects.requireNonNull(value, "execution"); return this; }
        public Request edit(Consumer<DocxSemanticEditor.Request> value) { if (edits.size() >= 1000) { throw new IllegalArgumentException("native callback budget exceeded"); } edits.add(Objects.requireNonNull(value, "edit")); return this; }
        public Prepared prepare() throws IOException {
            var policySnapshot = policy; var callbacks = List.copyOf(edits); var options = execution;
            return ExecutionContext.execute(options, () -> {
                var imported = adapter.readSemantic(new ByteArrayInputStream(input), DocxAdapter.Policy.ALLOW_LOSS);
                var issues = importIssues(imported.diagnostics(), policySnapshot); var editing = imported.document().edit().policy(policySnapshot);
                for (var callback : callbacks) { ExecutionContext.checkpoint(); callback.accept(editing); }
                var edited = editing.execute(); issues.addAll(edited.report().issues()); if (issues.size() > 10_000) { throw new IllegalArgumentException("native conversion report budget exceeded"); }
                byte[] encoded = DocxSemanticWriter.write(adapter, limits, edited.document());
                return new Prepared(edited.document(), encoded, new ProcessingReport(ProcessingReport.Phase.ACTUAL, issues), options);
            });
        }
        /** Runs native edits once during the eventual generic conversion's prepare, preserving all issues. */
        public DocumentConversion.Request project() {
            var policySnapshot = policy; var callbacks = List.copyOf(edits); var options = execution;
            return DocumentConversion.from("DOCX", genericPolicy -> {
                var imported = adapter.readSemantic(new ByteArrayInputStream(input), DocxAdapter.Policy.ALLOW_LOSS); var issues = importIssues(imported.diagnostics(), genericPolicy);
                var editing = imported.document().edit().policy(genericPolicy); for (var callback : callbacks) { callback.accept(editing); }
                var edited = editing.execute(); issues.addAll(edited.report().issues()); var projected = DocxSemanticProjection.project(edited.document(), genericPolicy); issues.addAll(projected.report().issues());
                return new DocumentConversion.Input(projected.document(), projected.notes(), new ProcessingReport(ProcessingReport.Phase.ACTUAL, issues));
            }).policy(policySnapshot).execution(options);
        }
    }
    public static final class Prepared {
        private final DocxSemanticDocument document; private final byte[] encoded; private final ProcessingReport report; private final ExecutionOptions execution;
        private Prepared(DocxSemanticDocument document, byte[] encoded, ProcessingReport report, ExecutionOptions execution) { this.document = document; this.encoded = encoded; this.report = report; this.execution = execution; }
        public DocxSemanticDocument document() { return document; }
        public ProcessingReport report() { return report; }
        public ProcessingReport preview() { return new ProcessingReport(ProcessingReport.Phase.PREVIEW, report.issues()); }
        public long bytes() { return encoded.length; }
        public void writeTo(OutputStream output) throws IOException { Objects.requireNonNull(output, "output"); ExecutionContext.execute(execution, () -> { ExecutionContext.output(output).write(encoded); return null; }); }
        public void writeTo(Path target) throws IOException {
            Objects.requireNonNull(target, "target"); ExecutionContext.execute(execution, () -> {
                var absolute = target.toAbsolutePath(); var parent = absolute.getParent(); if (parent == null || !Files.isDirectory(parent)) { throw new IOException("native output parent directory missing"); }
                var temporary = Files.createTempFile(parent, ".export4j-native-", ".tmp");
                try { try (var output = Files.newOutputStream(temporary)) { ExecutionContext.output(output).write(encoded); } ExecutionContext.checkpoint(); Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                finally { Files.deleteIfExists(temporary); } return null;
            });
        }
        public DocumentConversion.Request project() { return DocumentConversion.from("DOCX", policy -> { var projected = DocxSemanticProjection.project(document, policy); var issues = new ArrayList<>(report.issues()); issues.addAll(projected.report().issues()); return new DocumentConversion.Input(projected.document(), projected.notes(), new ProcessingReport(ProcessingReport.Phase.ACTUAL, issues)); }); }
    }
    static ArrayList<ProcessingIssue> importIssues(List<DocxAdapter.Diagnostic> diagnostics, ProcessingPolicy policy) {
        var issues = new ArrayList<ProcessingIssue>();
        for (var diagnostic : diagnostics) {
            boolean graph = diagnostic.part().equals("word/styles.xml") && diagnostic.element().equals("styleGraph");
            DocxSemanticSupport.issue(issues, policy, "IMPORT", graph ? "STYLE_GRAPH" : "OOXML_MARKUP", null,
                    graph ? "named style identity and inheritance graph are materialized into concrete formatting" : "unsupported OOXML structure requires semantic simplification",
                    graph ? "NAMED_STYLE_IDENTITY_AND_INHERITANCE_GRAPH_REMOVED_CONCRETE_FORMATTING_RETAINED" : "UNMODELED_OOXML_REMOVED_OR_FLATTENED", ProcessingPolicy.Treatment.DEGRADE);
        }
        return issues;
    }
}
