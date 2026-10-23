// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/** Offline CommonMark/GFM semantic import, independent of Markdown templates. Never reads URLs/files.
 * Only explicitly registered images are available. Unknown model semantics default to ERROR.
 * Immutable, thread-safe and owns no resources. Third-party parser types do not enter the public API.
 */
public final class MarkdownImporter {
    final ProcessingPolicy policy;
    final ImportLimits limits;
    final Map<String, InlineImage> images;
    final DocumentMetadata metadata;
    final PageSettings pageSettings;
    final ParagraphFormat format;
    final String strongRole;
    final String emphasisRole;
    final String combinedRole;
    final String codeRole;
    final URI baseUri;
    public MarkdownImporter() { this(new Builder()); }
    private MarkdownImporter(Builder builder) {
        policy = builder.policy; limits = builder.limits; images = Map.copyOf(builder.images); metadata = builder.metadata;
        pageSettings = builder.pageSettings; format = builder.format; strongRole = builder.strongRole;
        emphasisRole = builder.emphasisRole; combinedRole = builder.combinedRole; codeRole = builder.codeRole; baseUri = builder.baseUri;
    }
    public Request read(String source) { return new Request(Objects.requireNonNull(source, "source"), this); }
    /** Starts a unified import/edit/export plan. Source and resource configuration are snapshotted;
     * the plan's shared policy controls both import and target losses.
     */
    public com.mirakyux.export4j.model.processing.DocumentConversion.Request conversion(String source) {
        Objects.requireNonNull(source, "source");
        return com.mirakyux.export4j.model.processing.DocumentConversion.from("MARKDOWN", shared -> {
            var builder = new Builder().policy(shared).limits(limits).metadata(metadata).pageSettings(pageSettings)
                    .paragraphFormat(format).fontRoles(strongRole, emphasisRole, combinedRole, codeRole);
            builder.images.putAll(images); builder.baseUri = baseUri;
            var imported = builder.build().read(source).execute();
            return com.mirakyux.export4j.model.processing.DocumentConversion.Input.of(imported.document(), imported.report());
        }).policy(policy);
    }
    public static Builder builder() { return new Builder(); }
    /** Mutable task-confined builder. Font roles select caller-registered physical styles during later output. */
    public static final class Builder {
        private ProcessingPolicy policy = ProcessingPolicy.STRICT;
        private ImportLimits limits = ImportLimits.DEFAULT;
        private final Map<String, InlineImage> images = new HashMap<>();
        private DocumentMetadata metadata = DocumentMetadata.EMPTY;
        private PageSettings pageSettings = PageSettings.A4;
        private ParagraphFormat format = ParagraphFormat.of(12);
        private String strongRole = "bold";
        private String emphasisRole = "italic";
        private String combinedRole = "boldItalic";
        private String codeRole = "code";
        private URI baseUri;
        private Builder() { }
        public Builder policy(ProcessingPolicy value) { policy = Objects.requireNonNull(value, "policy"); return this; }
        public Builder limits(ImportLimits value) { limits = Objects.requireNonNull(value, "limits"); return this; }
        public Builder metadata(DocumentMetadata value) { metadata = Objects.requireNonNull(value, "metadata"); return this; }
        public Builder pageSettings(PageSettings value) { pageSettings = Objects.requireNonNull(value, "pageSettings"); return this; }
        public Builder paragraphFormat(ParagraphFormat value) { format = Objects.requireNonNull(value, "format"); return this; }
        public Builder image(String destination, InlineImage value) { images.put(key(destination), Objects.requireNonNull(value, "image")); return this; }
        public Builder fontRoles(String strong, String emphasis, String combined, String code) {
            strongRole = key(strong); emphasisRole = key(emphasis); combinedRole = key(combined); codeRole = key(code); return this;
        }
        /** Explicit absolute HTTP(S) base for passive relative hyperlinks; never fetched. */
        public Builder baseUri(String value) {
            var destination = new com.mirakyux.export4j.model.ExternalDestination(value);
            var uri = URI.create(destination.uri());
            if (!uri.getScheme().equalsIgnoreCase("http") && !uri.getScheme().equalsIgnoreCase("https")) {
                throw new IllegalArgumentException("Markdown base must be HTTP(S)");
            }
            baseUri = uri; return this;
        }
        public MarkdownImporter build() { return new MarkdownImporter(this); }
        private static String key(String value) {
            if (Objects.requireNonNull(value, "key").isBlank()) { throw new IllegalArgumentException("empty import key"); } return value;
        }
    }
    /** Per-input execution controls. Source text is an immutable snapshot and is never included in reports. */
    public static final class Request {
        private final String source;
        private final MarkdownImporter importer;
        private ExecutionOptions execution = ExecutionOptions.DEFAULT;
        private Request(String source, MarkdownImporter importer) { this.source = source; this.importer = importer; }
        public Request execution(ExecutionOptions value) { execution = Objects.requireNonNull(value, "execution"); return this; }
        public PreparedImport prepare() throws IOException {
            var snapshot = execution;
            return ExecutionContext.execute(snapshot, () -> {
                var state = new ImportSupport.State(importer.limits, importer.policy, "MARKDOWN");
                ImportSupport.input(source, state);
                var parser = Parser.builder().extensions(java.util.List.of(TablesExtension.create(), StrikethroughExtension.create()))
                        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build();
                var tree = parser.parse(source); state.work(1);
                var document = new MarkdownImportMapper(importer, state).map(tree);
                return new PreparedImport(new ImportResult(document, new ProcessingReport(ProcessingReport.Phase.ACTUAL, state.issues)), snapshot);
            });
        }
        public ImportResult execute() throws IOException { return prepare().execute(); }
    }
}
