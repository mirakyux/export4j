// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.ExternalDestination;
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
import org.jsoup.parser.Parser;

/** Offline HTML/CSS semantic subset. Accepts explicit source text and registered images only;
 * never executes scripts, fetches resources or asks a browser to paginate.
 * Immutable, thread-safe and owns no resources. Dimensions in the model are points.
 */
public final class HtmlImporter {
    /** Explicit offline media environment; never inferred from a browser or machine. */
    public enum Media { PRINT, SCREEN }
    final ProcessingPolicy policy;
    final ImportLimits limits;
    final DocumentMetadata metadata;
    final PageSettings pageSettings;
    final ParagraphFormat format;
    final Map<String, InlineImage> images;
    final Map<String, java.util.List<String>> families;
    final URI baseUri;
    final boolean layoutMode;
    final Media media;
    final com.mirakyux.export4j.model.PageSize viewport;
    public HtmlImporter() { this(new Builder()); }
    private HtmlImporter(Builder builder) {
        policy = builder.policy; limits = builder.limits; metadata = builder.metadata;
        pageSettings = builder.pageSettings; format = builder.format; images = Map.copyOf(builder.images);
        families = Map.copyOf(builder.families); baseUri = builder.baseUri;
        layoutMode = builder.layoutMode; media = builder.media; viewport = builder.viewport == null ? pageSettings.size() : builder.viewport;
    }
    public static Builder builder() { return new Builder(); }
    public Request read(String source) { return new Request(Objects.requireNonNull(source, "source"), this); }
    /** Starts an exact-source unified import/edit/export plan, using its shared policy for all stages. */
    public com.mirakyux.export4j.model.processing.DocumentConversion.Request conversion(String source) {
        Objects.requireNonNull(source, "source");
        return com.mirakyux.export4j.model.processing.DocumentConversion.from("HTML", shared -> {
            var builder = new Builder().policy(shared).limits(limits).metadata(metadata).pageSettings(pageSettings).paragraphFormat(format);
            builder.images.putAll(images); builder.families.putAll(families); builder.baseUri = baseUri;
            builder.layoutMode = layoutMode; builder.media = media; builder.viewport = viewport;
            var imported = builder.build().read(source).execute();
            return com.mirakyux.export4j.model.processing.DocumentConversion.Input.of(imported.document(), imported.report());
        }).policy(policy);
    }
    /** Mutable task-confined configuration. Built importers take immutable snapshots. */
    public static final class Builder {
        private ProcessingPolicy policy = ProcessingPolicy.STRICT;
        private ImportLimits limits = ImportLimits.DEFAULT;
        private DocumentMetadata metadata = DocumentMetadata.EMPTY;
        private PageSettings pageSettings = PageSettings.A4;
        private ParagraphFormat format = ParagraphFormat.of(12);
        private final Map<String, InlineImage> images = new HashMap<>();
        private final Map<String, java.util.List<String>> families = new HashMap<>();
        private URI baseUri;
        private boolean layoutMode;
        private Media media = Media.PRINT;
        private com.mirakyux.export4j.model.PageSize viewport;
        private Builder() { }
        public Builder policy(ProcessingPolicy value) { policy = Objects.requireNonNull(value, "policy"); return this; }
        public Builder limits(ImportLimits value) { limits = Objects.requireNonNull(value, "limits"); return this; }
        public Builder metadata(DocumentMetadata value) { metadata = Objects.requireNonNull(value, "metadata"); return this; }
        public Builder pageSettings(PageSettings value) { pageSettings = Objects.requireNonNull(value, "pageSettings"); return this; }
        public Builder paragraphFormat(ParagraphFormat value) { format = Objects.requireNonNull(value, "format"); return this; }
        /** Enables bounded CSS boxes, flex/grid and positioning, compiled to runtime layout components.
         * The default remains editable semantic flow. Component descendants are not a generic editable
         * document model; conversions without a layout representation require an explicit loss policy.
         * Physical fonts are supplied later by DocumentEngine, never by the importer.
         */
        public Builder layoutMode(boolean value) { layoutMode = value; return this; }
        public Builder media(Media value) { media = Objects.requireNonNull(value, "media"); return this; }
        /** Media-query viewport in points. Defaults to the caller's initial page size, independently
         * of @page rules; changing output pages never creates a responsive-style feedback loop.
         */
        public Builder viewport(com.mirakyux.export4j.model.PageSize value) { viewport = Objects.requireNonNull(value, "viewport"); return this; }
        /** Registers exact src text with explicit encoded bytes and point dimensions. No I/O occurs. */
        public Builder image(String source, InlineImage value) { images.put(key(source), Objects.requireNonNull(value, "image")); return this; }
        /** Maps one CSS family to a semantic font role; the output caller supplies its physical fonts.
         * Weight/style variants require separate registered roles in the output engine.
         */
        public Builder fontFamily(String family, String regular, String bold, String italic, String boldItalic) {
            families.put(key(family).toLowerCase(java.util.Locale.ROOT), java.util.List.of(key(regular), key(bold), key(italic), key(boldItalic))); return this;
        }
        /** Absolute HTTP(S) base for passive relative hyperlinks only. Never fetched. */
        public Builder baseUri(String value) {
            var uri = URI.create(new ExternalDestination(value).uri());
            if (!uri.getScheme().equalsIgnoreCase("http") && !uri.getScheme().equalsIgnoreCase("https")) { throw new IllegalArgumentException("HTML base must be HTTP(S)"); }
            baseUri = uri; return this;
        }
        public HtmlImporter build() { return new HtmlImporter(this); }
        private static String key(String value) {
            if (Objects.requireNonNull(value, "key").isBlank()) { throw new IllegalArgumentException("empty HTML resource key"); } return value;
        }
    }
    /** Per-source synchronous execution controls; source text is not included in diagnostics. */
    public static final class Request {
        private final String source;
        private final HtmlImporter importer;
        private ExecutionOptions execution = ExecutionOptions.DEFAULT;
        private Request(String source, HtmlImporter importer) { this.source = source; this.importer = importer; }
        public Request execution(ExecutionOptions value) { execution = Objects.requireNonNull(value, "execution"); return this; }
        public PreparedImport prepare() throws IOException {
            var snapshot = execution;
            return ExecutionContext.execute(snapshot, () -> {
                var state = new ImportSupport.State(importer.limits, importer.policy, "HTML");
                ImportSupport.input(source, state);
                var parser = Parser.htmlParser().setTrackPosition(true).setTrackErrors(1);
                var tree = parser.parseInput(source, ""); state.work(1);
                // Parser repairs are observable losses, never silent structural acceptance.
                var mapper = new HtmlImportMapper(importer, state);
                mapper.validate(tree);
                if (!parser.getErrors().isEmpty()) {
                    state.unsupported("HTML_REPAIR", HtmlImportMapper.location(tree, "", java.util.List.of()),
                            "HTML parser repaired input syntax", java.util.List.of("SOURCE_TREE_REPAIRED"), ProcessingPolicy.Treatment.DEGRADE);
                }
                var document = mapper.map(tree);
                return new PreparedImport(new ImportResult(document, new ProcessingReport(ProcessingReport.Phase.ACTUAL, state.issues)), snapshot);
            });
        }
        public ImportResult execute() throws IOException { return prepare().execute(); }
    }
}
