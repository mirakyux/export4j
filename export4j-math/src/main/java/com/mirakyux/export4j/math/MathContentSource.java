// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math;

import com.mirakyux.export4j.adapter.web.ContentImporter;
import com.mirakyux.export4j.adapter.web.ImportLimits;
import com.mirakyux.export4j.adapter.web.ImportResult;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.SourceContentParser;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Task-confined source compiler borrowed by one synchronous {@link MathContent.Author} callback.
 * Requests and Java/XML templates share one parser session and its cumulative input, syntax/work,
 * text, table and vector budgets. Closing a template lease does not close that compilation session.
 * The owning compilation closes this source on success, failure or cancellation. Requests, leases
 * and parser operations then reject further use. No file/network access or global state is added.
 */
public final class MathContentSource implements SourceContentParser, AutoCloseable {
    private Thread owner = Thread.currentThread();
    private final ContentOptions.Resolved defaults;
    private final ImportLimits limits;
    private final DocumentMetadata metadata;
    private final PageSettings pageSettings;
    private final Set<String> namespaces = new HashSet<>();
    private SourceContentParser.Session session;
    private long nextNamespace;

    MathContentSource(ContentImporter importer, ContentOptions.Resolved defaults, ImportLimits limits,
            DocumentMetadata metadata, PageSettings pageSettings) {
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.pageSettings = Objects.requireNonNull(pageSettings, "pageSettings");
        session = Objects.requireNonNull(importer, "importer").openSession();
    }

    /** Creates a request with a unique namespace; source is text, never a file path. */
    public Request read(String source) {
        ensureOpen();
        String namespace;
        do { namespace = "math-content-" + nextNamespace++; } while (namespaces.contains(namespace));
        return read(namespace, source);
    }

    /** Creates a request with an explicit namespace, which must be unique in this compilation.
     * A request executes once; reserved namespaces, including unexecuted requests, are bounded by
     * {@link ImportLimits#maxNodes()}. Reserving a namespace does not consume parsing budgets yet. */
    public Request read(String namespace, String source) {
        ensureOpen();
        String name = namespace(namespace);
        Objects.requireNonNull(source, "source");
        reserve(name);
        return new Request(name, source);
    }

    /** Creates a literal Java-template source whose parsing uses this compilation's shared session. */
    public DocumentTemplate.Source source(String namespace, String source, ContentOptions options) {
        ensureOpen();
        return DocumentTemplate.Source.literal(namespace(namespace), Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(options, "options").resolve(defaults), this);
    }

    /** Creates a literal source using the compilation's complete default snapshot. */
    public DocumentTemplate.Source source(String namespace, String source) {
        return source(namespace, source, ContentOptions.builder().build());
    }

    /** Opens a task-confined lease over the same cumulative parser. Closing it only closes the lease. */
    @Override public Session openSession() {
        ensureOpen();
        return new Lease();
    }

    /** Mutable task-confined request; options inherit independently from the compiler snapshot. */
    public final class Request {
        private final String namespace;
        private final String source;
        private ContentOptions.Resolved options = defaults;
        private ExecutionOptions execution = ExecutionOptions.DEFAULT;
        private boolean executed;

        private Request(String namespace, String source) { this.namespace = namespace; this.source = source; }

        public Request options(ContentOptions value) {
            ensureOpen();
            options = Objects.requireNonNull(value, "options").resolve(options);
            return this;
        }

        public Request execution(ExecutionOptions value) {
            ensureOpen();
            execution = Objects.requireNonNull(value, "execution");
            return this;
        }

        /** Parses synchronously once and returns a final semantic document with an actual strict report. */
        public ImportResult execute() throws IOException {
            ensureOpen();
            if (executed) { throw new IllegalStateException("content request was already executed"); }
            executed = true;
            var resolved = options;
            var controls = execution;
            return ExecutionContext.execute(controls, () -> {
                ensureOpen();
                var remaining = new Remaining(limits.maxNodes(), limits.maxNodes(), limits.maxTableSlots(),
                        limits.maxTextUtf16(), limits.maxDepth());
                var blocks = session.parse(namespace, source, resolved, remaining);
                return new ImportResult(new Document(metadata, pageSettings, blocks),
                        new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of()));
            });
        }
    }

    private final class Lease implements Session {
        private boolean closed;

        @Override public List<Block> parse(String namespace, String source, ContentOptions.Resolved options, Remaining remaining) {
            ensureOpen();
            if (closed) { throw new IllegalStateException("content parser lease is closed"); }
            String name = namespace(namespace);
            Objects.requireNonNull(source, "source"); Objects.requireNonNull(options, "options"); Objects.requireNonNull(remaining, "remaining");
            reserve(name);
            return session.parse(name, source, options, remaining);
        }

        @Override public void close() {
            if (closed) { return; }
            ensureOwner(); closed = true;
        }
    }

    private void reserve(String namespace) {
        if (namespaces.contains(namespace)) { throw new IllegalArgumentException("content source namespace already used"); }
        if (namespaces.size() >= limits.maxNodes()) { throw new IllegalArgumentException("content source namespace limit exceeded"); }
        namespaces.add(namespace);
    }

    private static String namespace(String value) {
        if (Objects.requireNonNull(value, "namespace").isBlank() || value.length() > 1024
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid content source namespace");
        }
        return value;
    }

    private void ensureOwner() {
        var currentOwner = owner;
        if (currentOwner != null && Thread.currentThread() != currentOwner) { throw new IllegalStateException("content compiler is confined to its authoring task"); }
    }

    private void ensureOpen() {
        if (session == null) { throw new IllegalStateException("content compiler is closed"); }
        ensureOwner();
    }

    /** Releases the compilation session, creating-thread reference and namespace state.
     * Repeated close is harmless, including cleanup after the creating task has ended. */
    @Override public void close() {
        var current = session;
        if (current == null) { return; }
        ensureOwner();
        session = null;
        owner = null;
        namespaces.clear();
        current.close();
    }
}
