// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Explicit model recovery from a standard file and an ordinary XML/resource ZIP companion,
 * or from the same ZIP embedded in an inert trailing HTML, Markdown or SVG comment.
 * The companion is bound to the exact standard-file bytes, including metadata. External resaves
 * conservatively invalidate it. No implicit file lookup, HTML execution, font loading or I/O
 * occurs. A digest detects accidental mismatch; it is not authentication of an untrusted author.
 * Immutable prepared values are reusable; request builders and trusted callbacks are task-confined.
 */
public final class XmlModelRecovery {
    public static final String FEATURE = "MODEL_RECOVERY";
    private static final int VERSION = 1;
    private XmlModelRecovery() { }

    /** Aggregate cooperative budgets, additional to adapter, XML binding and resource limits. */
    public record Limits(int maxDocumentBytes, int maxManifestBytes, int maxIssues, XmlTemplateBundle.Limits bundle) {
        public static final Limits DEFAULT = new Limits(64 * 1024 * 1024, 1024 * 1024, 10_000, XmlTemplateBundle.Limits.DEFAULT);
        public Limits {
            if (maxDocumentBytes < 1 || maxManifestBytes < 1 || maxIssues < 1) { throw new IllegalArgumentException("invalid recovery limits"); }
            Objects.requireNonNull(bundle, "bundle");
        }
    }
    /** Must read only the supplied current-file snapshot and return its actual import losses.
     * Retains the selected adapter's security, permission, budget and cancellation checks. */
    @FunctionalInterface public interface FallbackReader {
        DocumentConversion.Input read(byte[] current, ProcessingPolicy policy) throws IOException;
    }

    public static SaveRequest capture(DocumentConversion.Prepared conversion) { return new SaveRequest(Objects.requireNonNull(conversion, "conversion")); }
    public static OpenRequest open(String format, byte[] current) { return new OpenRequest(format, current); }
    /** Opens HTML, MARKDOWN or SVG with an optional trailing recovery comment. The caller explicitly
     * selects the format; no content sniffing, execution or file lookup occurs. Exact bytes outside
     * the comment must match. Missing/stale recovery needs DEGRADE and a current-file fallback;
     * corrupt/duplicate comments always fail. SVG has no built-in semantic fallback importer. */
    public static OpenRequest openEmbedded(String format, byte[] current) {
        String key = format(format);
        if (!supportsComment(key)) { throw new IllegalArgumentException("embedded recovery requires HTML, MARKDOWN or SVG"); }
        return new OpenRequest(key, current, true);
    }
    /** Opens an explicitly supplied HTML file with an optional inert trailing recovery comment.
     * All HTML bytes outside the exact comment must match the manifest. The total file is bounded;
     * malformed/duplicate envelopes cannot be ignored. Missing/stale recovery requires an explicit
     * current-HTML fallback and DEGRADE policy. No HTML rendering or script execution occurs. */
    public static OpenRequest openEmbeddedHtml(byte[] current) { return openEmbedded("HTML", current); }

    public static final class SaveRequest {
        private final DocumentConversion.Prepared conversion;
        private XmlTemplateBundle resources;
        private ProcessingPolicy policy = ProcessingPolicy.STRICT;
        private Limits limits = Limits.DEFAULT;
        private ExecutionOptions execution = ExecutionOptions.DEFAULT;
        private SaveRequest(DocumentConversion.Prepared conversion) { this.conversion = conversion; }
        /** Only explicit fonts/notices are retained; old source and unused images are excluded. */
        public SaveRequest resources(XmlTemplateBundle value) { resources = Objects.requireNonNull(value, "resources"); return this; }
        public SaveRequest policy(ProcessingPolicy value) { policy = Objects.requireNonNull(value, "policy"); return this; }
        public SaveRequest limits(Limits value) { limits = Objects.requireNonNull(value, "limits"); return this; }
        public SaveRequest execution(ExecutionOptions value) { execution = Objects.requireNonNull(value, "execution"); return this; }

        /** Captures the prepared edited candidate, never an earlier source model. If the target or XML
         * saving loses content, ERROR refuses recovery; WARN/IGNORE + DISCARD emits only the standard
         * document. Lost source content is never hidden in a companion. Preparation touches no path. */
        public Saved prepare() throws IOException {
            var strategy = policy; var budgets = limits; var control = execution; var assets = resources;
            return ExecutionContext.execute(control, () -> {
                var bytes = new ByteArrayOutputStream();
                var result = conversion.to(TemplateBundleIO.bounded(bytes, budgets.maxDocumentBytes()));
                String format = format(conversion.targetFormat());
                var issues = new ArrayList<ProcessingIssue>(); append(issues, result.report(), budgets);
                byte[] companion = null;
                if (conversion.exportReport().issues().stream().anyMatch(issue -> issue.treatment() != ProcessingPolicy.Treatment.PRESERVE)) {
                    unavailable(issues, strategy, format, "TARGET_EXPORT_LOSS", "RECOVERY_NOT_SAVED", "EXPORT", ProcessingPolicy.Treatment.DISCARD, budgets);
                } else {
                    var writer = XmlTemplateWriter.from(result.document()).notes(result.notes()).policy(strategy)
                            .bundleLimits(budgets.bundle()).execution(control);
                    if (assets != null) { writer.resources(assets); }
                    var saved = writer.prepare(); append(issues, new ProcessingReport(ProcessingReport.Phase.ACTUAL, saved.preview().issues()), budgets);
                    if (!saved.preview().losses().isEmpty()) {
                        unavailable(issues, strategy, format, "XML_SAVE_LOSS", "RECOVERY_NOT_SAVED", "EXPORT", ProcessingPolicy.Treatment.DISCARD, budgets);
                    } else {
                        var entries = TemplateBundleIO.unzip(saved.bundle().encode(), budgets.bundle());
                        var manifest = new RecoveryManifest(VERSION, format, bytes.size(), digest(bytes.toByteArray()), digest(entries), issues);
                        entries.put("recovery.xml", manifest.encode(budgets.maxManifestBytes()));
                        checkEntries(entries, budgets);
                        companion = TemplateBundleIO.zip(entries, budgets.bundle());
                    }
                }
                return new Saved(format, bytes.toByteArray(), companion, issues, control, budgets);
            });
        }
    }

    /** Exact prepared output and optional recovery ZIP. Each path is published atomically on its own;
     * the two paths are not a filesystem transaction. A mismatched pair always fails recovery. */
    public static final class Saved {
        private final String format;
        private final byte[] document;
        private final byte[] companion;
        private final List<ProcessingIssue> issues;
        private final ExecutionOptions execution;
        private final Limits limits;
        private Saved(String format, byte[] document, byte[] companion, List<ProcessingIssue> issues, ExecutionOptions execution, Limits limits) {
            this.format = format; this.document = document; this.companion = companion; this.issues = List.copyOf(issues); this.execution = execution; this.limits = limits;
        }
        public String format() { return format; }
        public byte[] documentBytes() { return document.clone(); }
        public Optional<byte[]> companionBytes() { return companion == null ? Optional.empty() : Optional.of(companion.clone()); }
        public ProcessingReport preview() { return new ProcessingReport(ProcessingReport.Phase.PREVIEW, issues); }
        /** Borrowed output, never closed/flushed. Physical write failure may leave partial bytes. */
        public ProcessingReport writeDocument(OutputStream output) throws IOException {
            Objects.requireNonNull(output, "output");
            return ExecutionContext.execute(execution, () -> { ExecutionContext.output(output).write(document); return actual(); });
        }
        public ProcessingReport writeDocument(Path output) throws IOException { TemplateBundleIO.publish(document, output, execution); return actual(); }
        public void writeCompanion(OutputStream output) throws IOException {
            Objects.requireNonNull(output, "output"); byte[] bytes = required();
            ExecutionContext.execute(execution, () -> { ExecutionContext.output(output).write(bytes); return null; });
        }
        public void writeCompanion(Path output) throws IOException { TemplateBundleIO.publish(required(), output, execution); }
        /** HTML, MARKDOWN or SVG only: defensive bytes with the ZIP in a trailing Base64 comment.
         * The total document budget includes the comment. Lossy captures return the plain standard
         * output with no hidden source. Plaintext resources provide no encryption/authentication. */
        public byte[] embeddedBytes() throws IOException {
            if (!supportsComment(format)) { throw new IllegalStateException("embedded recovery requires HTML, MARKDOWN or SVG output"); }
            return ExecutionContext.execute(execution, () -> CommentRecoveryEnvelope.encode(document, companion, limits.maxDocumentBytes()));
        }
        /** Borrowed output, never closed/flushed; preparation completes before the first write. */
        public ProcessingReport writeEmbedded(OutputStream output) throws IOException {
            Objects.requireNonNull(output, "output");
            byte[] bytes = embeddedBytes();
            return ExecutionContext.execute(execution, () -> { ExecutionContext.output(output).write(bytes); return actual(); });
        }
        /** Atomically publishes one standard file, retaining the old target on preparation/cancellation failure. */
        public ProcessingReport writeEmbedded(Path output) throws IOException {
            TemplateBundleIO.publish(embeddedBytes(), output, execution); return actual();
        }
        /** HTML-only convenience for {@link #embeddedBytes()}; retains the original format check. */
        public byte[] embeddedHtmlBytes() throws IOException { requireHtml(); return embeddedBytes(); }
        /** HTML-only borrowed-output convenience; never closes or flushes. */
        public ProcessingReport writeEmbeddedHtml(OutputStream output) throws IOException { requireHtml(); return writeEmbedded(output); }
        /** HTML-only atomic-path convenience. */
        public ProcessingReport writeEmbeddedHtml(Path output) throws IOException { requireHtml(); return writeEmbedded(output); }
        private void requireHtml() {
            if (!format.equals("HTML")) { throw new IllegalStateException("embedded HTML requires HTML output"); }
        }
        private byte[] required() { if (companion == null) { throw new IllegalStateException("model recovery was not saved"); } return companion; }
        private ProcessingReport actual() { return new ProcessingReport(ProcessingReport.Phase.ACTUAL, issues); }
    }

    public static final class OpenRequest {
        private final String format;
        private final byte[] current;
        private final boolean embedded;
        private byte[] companion;
        private FallbackReader fallback;
        private ProcessingPolicy policy = ProcessingPolicy.STRICT;
        private Limits limits = Limits.DEFAULT;
        private ExecutionOptions execution = ExecutionOptions.DEFAULT;
        private OpenRequest(String format, byte[] current) {
            this(format, current, false);
        }
        private OpenRequest(String format, byte[] current, boolean embedded) {
            this.format = format(format); this.current = Objects.requireNonNull(current, "current").clone();
            this.embedded = embedded;
        }
        public OpenRequest companion(byte[] value) {
            if (embedded) { throw new IllegalStateException("embedded recovery cannot also use a companion"); }
            companion = Objects.requireNonNull(value, "companion").clone(); return this;
        }
        public OpenRequest fallback(FallbackReader value) { fallback = Objects.requireNonNull(value, "fallback"); return this; }
        public OpenRequest policy(ProcessingPolicy value) { policy = Objects.requireNonNull(value, "policy"); return this; }
        public OpenRequest limits(Limits value) { limits = Objects.requireNonNull(value, "limits"); return this; }
        public OpenRequest execution(ExecutionOptions value) { execution = Objects.requireNonNull(value, "execution"); return this; }
        public Opened prepare() throws IOException { return snapshot().read(policy); }
        /** Snapshots all recovery/fallback configuration; the conversion's shared policy controls import. */
        public DocumentConversion.Request conversion() {
            var snapshot = snapshot();
            return DocumentConversion.from(format, shared -> {
                var result = snapshot.read(shared);
                return new DocumentConversion.Input(result.document(), result.notes(), result.report());
            }).policy(policy).execution(execution);
        }
        private Opening snapshot() { return new Opening(format, current, companion, fallback, limits, execution, embedded); }
    }

    /** recovered=false means actual restricted import of the current file; resources is then empty.
     * Historical actual diagnostics retain their original actions even under a later strict policy. */
    public record Opened(SectionedDocument document, List<Note> notes, Optional<XmlTemplateBundle> resources,
            boolean recovered, ProcessingReport report) {
        public Opened {
            Objects.requireNonNull(document, "document"); notes = List.copyOf(notes); Objects.requireNonNull(resources, "resources");
            Objects.requireNonNull(report, "report");
        }
    }
    private record Opening(String format, byte[] current, byte[] companion, FallbackReader fallback, Limits limits, ExecutionOptions execution, boolean embedded) {
        private Opened read(ProcessingPolicy policy) throws IOException {
            return ExecutionContext.execute(execution, () -> {
                if (current.length > limits.maxDocumentBytes()) { throw new IOException("recovery document byte budget exceeded"); }
                var parts = embedded ? CommentRecoveryEnvelope.decode(current, limits) : new CommentRecoveryEnvelope.Parts(current, companion);
                byte[] standard = parts.document(), archive = parts.companion();
                String problem = "MISSING_COMPANION";
                if (archive != null) {
                    var entries = TemplateBundleIO.unzip(archive, limits.bundle(), true);
                    byte[] header = entries.remove("recovery.xml");
                    if (header == null) { throw new IOException("recovery manifest is missing"); }
                    var manifest = RecoveryManifest.read(header, limits.maxManifestBytes(), limits.maxIssues());
                    if (!manifest.modelDigest().equals(digest(entries))) { throw new IOException("recovery model/resource digest mismatch"); }
                    var bundle = XmlTemplateBundle.read(TemplateBundleIO.zip(entries, limits.bundle()), limits.bundle(), execution);
                    // Even stale companions must be well formed and safe; policy cannot bypass parsing.
                    var bound = bundle.compile().renderComplete(Map.of());
                    if (manifest.version() != VERSION) { problem = "UNSUPPORTED_RECOVERY_VERSION"; }
                    else if (!manifest.format().equals(format)) { problem = "FORMAT_MISMATCH"; }
                    else if (manifest.length() != standard.length || !manifest.digest().equals(digest(standard))) { problem = "STALE_OR_CONFLICTING_COMPANION"; }
                    else {
                        return new Opened(bound.document(), bound.notes(), Optional.of(bundle), true,
                                new ProcessingReport(ProcessingReport.Phase.ACTUAL, manifest.issues()));
                    }
                }
                var issues = new ArrayList<ProcessingIssue>();
                unavailable(issues, policy, format, problem, "CURRENT_FILE_RESTRICTED_IMPORT", "IMPORT", ProcessingPolicy.Treatment.DEGRADE, limits);
                if (fallback == null) { throw new IllegalArgumentException("current-file recovery fallback must be explicitly supplied"); }
                var imported = Objects.requireNonNull(fallback.read(standard.clone(), policy), "fallback result");
                append(issues, imported.report(), limits);
                return new Opened(imported.document(), imported.notes(), Optional.empty(), false,
                        new ProcessingReport(ProcessingReport.Phase.ACTUAL, issues));
            });
        }
    }

    private static void unavailable(List<ProcessingIssue> issues, ProcessingPolicy policy, String format, String reason,
            String effect, String stage, ProcessingPolicy.Treatment treatment, Limits limits) {
        var rule = policy.resolve(format, FEATURE, stage);
        var issue = new ProcessingIssue(stage, stage.equals("IMPORT") ? format : "MODEL", stage.equals("IMPORT") ? "MODEL" : format,
                FEATURE, stage, new ProcessingIssue.Location("", "DOCUMENT", "", List.of(), -1, -1), reason,
                rule.action(), rule.treatment(), List.of(effect));
        if (rule.action() == ProcessingPolicy.Action.ERROR || rule.treatment() != treatment) { throw new ProcessingException(issue); }
        append(issues, new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of(issue)), limits);
    }
    private static void append(List<ProcessingIssue> issues, ProcessingReport report, Limits limits) {
        if (report.phase() != ProcessingReport.Phase.ACTUAL) { throw new IllegalArgumentException("recovery requires actual stage diagnostics"); }
        if (report.issues().size() > limits.maxIssues() - issues.size()) { throw new IllegalArgumentException("recovery issue budget exceeded"); }
        issues.addAll(report.issues()); ExecutionContext.checkpoint();
    }
    private static void checkEntries(Map<String, byte[]> entries, Limits limits) throws IOException {
        long total = 0;
        if (entries.size() > limits.bundle().maxEntries()) { throw new IOException("recovery entry budget exceeded"); }
        for (var value : entries.values()) { total += value.length; }
        if (total > limits.bundle().maxExpandedBytes()) { throw new IOException("recovery expanded byte budget exceeded"); }
    }
    private static boolean supportsComment(String format) { return format.equals("HTML") || format.equals("MARKDOWN") || format.equals("SVG"); }
    private static String format(String value) {
        if (!Objects.requireNonNull(value, "format").matches("[A-Z][A-Z0-9_]{0,63}")) { throw new IllegalArgumentException("invalid recovery format key"); }
        return value;
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
    }
    private static String digest(byte[] bytes) {
        var digest = sha256(); update(digest, bytes); return HexFormat.of().formatHex(digest.digest());
    }
    private static String digest(Map<String, byte[]> entries) {
        var digest = sha256();
        for (var entry : new TreeMap<>(entries).entrySet()) {
            byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8); byte[] bytes = entry.getValue();
            digest.update(ByteBuffer.allocate(8).putInt(name.length).putInt(bytes.length).array());
            update(digest, name); update(digest, bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void update(MessageDigest digest, byte[] bytes) {
        for (int offset = 0; offset < bytes.length; offset += 8192) {
            ExecutionContext.checkpoint(); digest.update(bytes, offset, Math.min(8192, bytes.length - offset));
        }
    }
}
