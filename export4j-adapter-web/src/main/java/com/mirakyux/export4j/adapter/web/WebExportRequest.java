// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageDocument;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable synchronous export request. Semantic validation and UTF-8 assembly precede output.
 * Streams are borrowed and never closed/flushed. Path output uses a same-directory temporary and atomic
 * replacement without copy fallback. Failed path exports preserve the old target and remove the temporary.
 * Safe for concurrent calls when supplied execution callbacks are safe. Memory is bounded, not streaming.
 */
public final class WebExportRequest {
    private final SectionedDocument document;
    private final boolean html;
    private final ExportPolicy policy;
    private final ExportLimits limits;
    private final Map<String, FontStyle> fonts;
    private final ExecutionOptions execution;

    WebExportRequest(SectionedDocument document, boolean html, ExportPolicy policy, ExportLimits limits,
            Map<String, FontStyle> fonts, ExecutionOptions execution) {
        this.document = Objects.requireNonNull(document, "document"); this.html = html;
        this.policy = policy; this.limits = limits; this.fonts = Map.copyOf(fonts); this.execution = execution;
    }
    static SectionedDocument source(Document document, PageRegions regions) {
        Objects.requireNonNull(document, "document");
        return new SectionedDocument(document.metadata(), List.of(new Section("document", document.pageSettings(), document.blocks(), 0, regions)));
    }
    static SectionedDocument source(PageDocument document) {
        Objects.requireNonNull(document, "document"); return source(document.document(), document.pageRegions());
    }
    /** Returns a new request; nested caller execution scopes are also observed. */
    public WebExportRequest execution(ExecutionOptions value) {
        return new WebExportRequest(document, html, policy, limits, fonts, Objects.requireNonNull(value, "execution"));
    }
    /** Writes UTF-8 after all semantic/policy/size checks; physical stream I/O can still fail partially. */
    public ExportResult to(OutputStream output) throws IOException {
        Objects.requireNonNull(output, "output");
        return ExecutionContext.execute(execution, () -> {
            var rendered = new WebRenderer(document, html, policy, limits, fonts).render();
            ExecutionContext.output(output).write(rendered.bytes());
            return rendered.result();
        });
    }
    /** Atomically replaces an existing file only after successful rendering, writing and execution checks. */
    public ExportResult to(Path output) throws IOException {
        Path target = Objects.requireNonNull(output, "output").toAbsolutePath();
        Path temporary = Files.createTempFile(target.getParent(), ".export4j-web-", ".tmp");
        Throwable failure = null;
        try {
            ExportResult result;
            try (var stream = Files.newOutputStream(temporary)) { result = to(stream); }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return result;
        } catch (IOException | RuntimeException | Error exception) { failure = exception; throw exception; }
        finally {
            try { Files.deleteIfExists(temporary); }
            catch (IOException exception) { if (failure != null) { failure.addSuppressed(exception); } else { throw exception; } }
        }
    }
}
