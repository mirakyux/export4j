// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineVector;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.SourceContentParser;
import com.mirakyux.export4j.model.TextAppearance;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.commonmark.node.Node;
import org.jsoup.parser.Parser;

/** Offline source compilation with inherited defaults. Formats are explicit and mutually exclusive;
 * finished semantic blocks are never reparsed. Unsupported semantics fail strictly. Resources and
 * formula rendering are explicitly registered. Immutable importers are thread-safe; their sessions
 * are synchronous, task-confined and accumulate budgets across source chunks and template loops.
 * The parser-complexity reservation is a conservative input policy, not a hard memory sandbox.
 */
public final class ContentImporter implements SourceContentParser {
    private final ContentOptions.Resolved defaults;
    private final ImportLimits limits;
    private final int maxFormulas, maxFormulaUtf16, maxSegments;
    private final FormulaProcessor processor;
    private final Map<String, InlineImage> images;
    private final Map<String, InlineVector> vectors;
    private final Map<String, List<String>> families;
    private final DocumentMetadata metadata;
    private final PageSettings pageSettings;

    private ContentImporter(Builder builder) {
        defaults = builder.defaults; limits = builder.limits; processor = builder.processor;
        maxFormulas = builder.maxFormulas; maxFormulaUtf16 = builder.maxFormulaUtf16; maxSegments = builder.maxSegments;
        images = Map.copyOf(builder.images); vectors = Map.copyOf(builder.vectors); families = Map.copyOf(builder.families);
        metadata = builder.metadata; pageSettings = builder.pageSettings;
    }
    public static Builder builder() { return new Builder(); }
    @Override public Session openSession() { return new ContentSession(); }
    /** One-shot source request. String input always means source text, never a path. */
    public Request read(String source) { return read("content", source); }
    public Request read(String namespace, String source) { return new Request(namespace, source); }
    /** A literal template source, snapshotted with the importer's global defaults. */
    public DocumentTemplate.Source source(String namespace, String source, ContentOptions options) {
        return DocumentTemplate.Source.literal(namespace, source, Objects.requireNonNull(options, "options").resolve(defaults), this);
    }
    public DocumentTemplate.Source source(String namespace, String source) { return source(namespace, source, ContentOptions.builder().build()); }

    public static final class Builder {
        private ContentOptions.Resolved defaults = ContentOptions.defaults();
        private ImportLimits limits = ImportLimits.DEFAULT;
        private int maxFormulas = 1_000, maxFormulaUtf16 = 16_384, maxSegments = 1_000_000;
        private FormulaProcessor processor;
        private final Map<String, InlineImage> images = new HashMap<>();
        private final Map<String, InlineVector> vectors = new HashMap<>();
        private final Map<String, List<String>> families = new HashMap<>();
        private DocumentMetadata metadata = DocumentMetadata.EMPTY;
        private PageSettings pageSettings = PageSettings.A4;
        private Builder() { }
        public Builder defaults(ContentOptions value) { defaults = Objects.requireNonNull(value, "defaults").resolve(defaults); return this; }
        public Builder defaults(ContentOptions.Resolved value) { defaults = Objects.requireNonNull(value, "defaults"); return this; }
        public Builder limits(ImportLimits value) { limits = Objects.requireNonNull(value, "limits"); return this; }
        public Builder maxFormulas(int value) { maxFormulas = positive(value); return this; }
        public Builder maxFormulaUtf16(int value) { maxFormulaUtf16 = positive(value); return this; }
        public Builder maxVectorSegments(int value) { maxSegments = positive(value); return this; }
        public Builder formulaProcessor(FormulaProcessor value) { processor = Objects.requireNonNull(value, "processor"); return this; }
        public Builder metadata(DocumentMetadata value) { metadata = Objects.requireNonNull(value, "metadata"); return this; }
        public Builder pageSettings(PageSettings value) { pageSettings = Objects.requireNonNull(value, "pageSettings"); return this; }
        public Builder image(String destination, InlineImage value) {
            String key = key(destination); if (vectors.containsKey(key)) { throw new IllegalArgumentException("content resource key already registered as a vector"); }
            images.put(key, Objects.requireNonNull(value, "image")); return this;
        }
        public Builder vector(String destination, InlineVector value) {
            String key = key(destination); if (images.containsKey(key)) { throw new IllegalArgumentException("content resource key already registered as an image"); }
            vectors.put(key, Objects.requireNonNull(value, "vector")); return this;
        }
        /** Maps a nonblank CSS family to explicit font roles; an empty role selects default fonts. */
        public Builder fontFamily(String family, String regular, String bold, String italic, String boldItalic) {
            families.put(key(family).toLowerCase(java.util.Locale.ROOT), List.of(role(regular), role(bold), role(italic), role(boldItalic))); return this;
        }
        public ContentImporter build() { return new ContentImporter(this); }
        private static int positive(int value) { if (value <= 0) { throw new IllegalArgumentException("content limit must be positive"); } return value; }
        private static String key(String value) { if (Objects.requireNonNull(value, "key").isBlank()) { throw new IllegalArgumentException("empty content resource key"); } return value; }
        private static String role(String value) { return new TextAppearance(value, 0).fontRole(); }
    }

    public final class Request {
        private final String namespace, source;
        private ContentOptions.Resolved options = defaults;
        private ExecutionOptions execution = ExecutionOptions.DEFAULT;
        private Request(String namespace, String source) { this.namespace = namespace(namespace); this.source = Objects.requireNonNull(source, "source"); }
        public Request options(ContentOptions value) { options = Objects.requireNonNull(value, "options").resolve(options); return this; }
        public Request execution(ExecutionOptions value) { execution = Objects.requireNonNull(value, "execution"); return this; }
        public ImportResult execute() throws IOException {
            var resolved = options; var controls = execution;
            return ExecutionContext.execute(controls, () -> {
                try (var session = openSession()) {
                    var blocks = session.parse(namespace, source, resolved, new Remaining(limits.maxNodes(), limits.maxNodes(), limits.maxTableSlots(), limits.maxTextUtf16(), limits.maxDepth()));
                    return new ImportResult(new Document(metadata, pageSettings, blocks), new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of()));
                }
            });
        }
    }

    private final class ContentSession implements Session {
        private final ContentBudget budget = new ContentBudget(limits, maxFormulas, maxFormulaUtf16, maxSegments);
        private final Set<String> namespaces = new HashSet<>();
        private boolean closed, parsing;
        @Override public List<Block> parse(String namespace, String source, ContentOptions.Resolved options, Remaining remaining) {
            if (closed || parsing) { throw new IllegalStateException("content session is closed or reentered"); }
            String prefix = namespace(namespace); Objects.requireNonNull(source, "source"); Objects.requireNonNull(options, "options"); Objects.requireNonNull(remaining, "remaining");
            if (!namespaces.add(prefix)) { throw new IllegalArgumentException("content source namespace already used"); }
            if (options.formulas() && processor == null) { throw new IllegalStateException("formula parsing requires an explicit FormulaProcessor"); }
            if (remaining.maxBlocks() == 0 || remaining.maxVisitedNodes() == 0 || remaining.maxDepth() == 0) { throw new IllegalArgumentException("content output allowance exhausted"); }
            parsing = true;
            try {
                budget.input(source.length());
                var context = new ContentImportContext(budget, options, remaining, prefix, source, processor);
                var state = new ImportSupport.State(limits, ProcessingPolicy.STRICT, options.format().name(), context);
                ImportSupport.input(source, state);
                return switch (options.format()) {
                    case TEXT -> List.of(Paragraph.mixed(state.id(), context.text(source, context.style(options.paragraphFormat().fontSize()), "", state, true), options.paragraphFormat()));
                    case MARKDOWN -> markdown(source, state, context);
                    case HTML -> html(source, state, context);
                    case INHERIT -> throw new IllegalArgumentException("content format was not resolved");
                };
            } finally { parsing = false; }
        }
        @Override public void close() { closed = true; namespaces.clear(); }

        private List<Block> markdown(String source, ImportSupport.State state, ContentImportContext context) {
            Node original = markdownTree(source, state, context);
            budget.work(Math.multiplyExact(2L, source.length()));
            var spans = FormulaSyntax.markdown(source, original, context.options.formulas() ? budget.remainingFormulas() : limits.maxNodes(),
                    context.options.formulas() ? maxFormulaUtf16 : source.length(), !context.options.tables(), context.options.rawHtml());
            if (context.options.formulas()) { budget.formulas(spans.size()); }
            var builder = MarkdownImporter.builder().paragraphFormat(context.options.paragraphFormat()).cellFormat(context.options.cellFormat()).limits(limits).metadata(metadata).pageSettings(pageSettings);
            images.forEach(builder::image); vectors.forEach(builder::vector);
            String prepared = source;
            if (!spans.isEmpty()) {
                String markerPrefix = "EXPORT4JCONTENT";
                while (markerCollision(source, original, markerPrefix)) { markerPrefix += "Z"; }
                context.literalPrefix = markerPrefix;
                long rewriteAllowance = Math.addExact(source.length(), Math.multiplyExact((long) spans.size(), markerPrefix.length() + 64L));
                if (rewriteAllowance > limits.maxInputUtf16()) { throw new IllegalArgumentException("content rewritten input budget exceeded"); }
                budget.work(rewriteAllowance);
                var text = new StringBuilder(); int cursor = 0, index = 0;
                for (var span : spans) {
                    text.append(source, cursor, span.start()); int replacementStart = text.length();
                    String marker = markerPrefix + index++ + "END";
                    if (context.options.formulas()) {
                        // Render only after the mapper resolves the actual heading, cell or CSS
                        // host. The same placeholders serve plain and raw-HTML Markdown.
                        context.formulaMarkers.put(marker, span); text.append(marker);
                    } else { context.literals.put(marker, source.substring(span.start(), span.end())); text.append(marker); }
                    context.replacements.add(new ContentImportContext.Replacement(span.start(), span.end(), replacementStart, text.length()));
                    cursor = span.end();
                }
                text.append(source, cursor, source.length()); prepared = text.toString();
            }
            var tree = spans.isEmpty() ? original : markdownTree(prepared, state, context);
            if (context.options.rawHtml()) {
                var rendered = MixedMarkdownRenderer.render(tree, prepared, budget, !context.options.tables());
                context.htmlSourceRanges = rendered.ranges(); context.markdownFormulasScanned = true;
                return html(rendered.html(), state, context);
            }
            return new MarkdownImportMapper(builder.build(), state).map(tree).blocks();
        }
        private Node markdownTree(String source, ImportSupport.State state, ContentImportContext context) {
            try (var reservation = budget.reserve(source.length(), context.remaining)) {
                var tree = FormulaSyntax.parser().parse(source); long count = 0;
                record Frame(Node node, int depth) { }
                var pending = new ArrayDeque<Frame>(); pending.push(new Frame(tree, 0));
                while (!pending.isEmpty()) {
                    var frame = pending.pop(); state.work(1); reservation.visited(++count);
                    if (frame.depth() > Math.min(limits.maxDepth(), context.remaining.maxDepth())) { throw new IllegalArgumentException("content Markdown depth exceeded"); }
                    for (var child = frame.node().getLastChild(); child != null; child = child.getPrevious()) { pending.push(new Frame(child, frame.depth() + 1)); }
                }
                reservation.actual(count); return tree;
            }
        }
        private boolean markerCollision(String source, Node root, String marker) {
            budget.work(source.length());
            if (source.contains(marker)) { return true; }
            for (String key : images.keySet()) { budget.work(key.length()); if (key.contains(marker)) { return true; } }
            for (String key : vectors.keySet()) { budget.work(key.length()); if (key.contains(marker)) { return true; } }
            var pending = new ArrayDeque<Node>(); pending.push(root);
            while (!pending.isEmpty()) {
                var node = pending.pop(); budget.work(1);
                String decoded = switch (node) {
                    case org.commonmark.node.Text text -> text.getLiteral();
                    case org.commonmark.node.Code code -> code.getLiteral();
                    case org.commonmark.node.Image image -> image.getDestination();
                    case org.commonmark.node.Link link -> link.getDestination();
                    case org.commonmark.node.LinkReferenceDefinition definition -> definition.getDestination();
                    case org.commonmark.node.HtmlInline html -> org.jsoup.parser.Parser.unescapeEntities(html.getLiteral(), false);
                    case org.commonmark.node.HtmlBlock html -> org.jsoup.parser.Parser.unescapeEntities(html.getLiteral(), false);
                    default -> "";
                };
                budget.work(decoded.length());
                if (decoded.contains(marker)) { return true; }
                for (var child = node.getLastChild(); child != null; child = child.getPrevious()) { pending.push(child); }
            }
            return false;
        }
        private List<Block> html(String source, ImportSupport.State state, ContentImportContext context) {
            if (!context.markdownFormulasScanned) { source = protectHtmlFormulas(source, context); }
            try (var reservation = budget.reserve(source.length(), context.remaining)) {
                var parser = Parser.htmlParser().setTrackPosition(true).setTrackErrors(1);
                var tree = parser.parseInput(source, ""); long count = 0;
                record Frame(org.jsoup.nodes.Node node, int depth) { }
                var pending = new ArrayDeque<Frame>(); pending.push(new Frame(tree, 0));
                while (!pending.isEmpty()) {
                    var frame = pending.pop(); state.work(1); reservation.visited(++count);
                    if (frame.depth() > Math.min(limits.maxDepth(), context.remaining.maxDepth())) { throw new IllegalArgumentException("content HTML depth exceeded"); }
                    for (int i = frame.node().childNodeSize() - 1; i >= 0; i--) { pending.push(new Frame(frame.node().childNode(i), frame.depth() + 1)); }
                }
                reservation.actual(count);
                var builder = HtmlImporter.builder().paragraphFormat(context.options.paragraphFormat()).limits(limits).metadata(metadata).pageSettings(pageSettings);
                images.forEach(builder::image); vectors.forEach(builder::vector);
                families.forEach((family, roles) -> builder.fontFamily(family, roles.get(0), roles.get(1), roles.get(2), roles.get(3)));
                var mapper = new HtmlImportMapper(builder.build(), state); mapper.validate(tree);
                if (!parser.getErrors().isEmpty()) {
                    state.unsupported("HTML_REPAIR", HtmlImportMapper.location(tree, "", List.of()), "HTML parser repaired input syntax", List.of("SOURCE_TREE_REPAIRED"), ProcessingPolicy.Treatment.DEGRADE);
                }
                return mapper.map(tree).blocks();
            }
        }
        private String protectHtmlFormulas(String source, ContentImportContext context) {
            budget.work(source.length());
            var spans = FormulaSyntax.html(source, context.options.formulas() ? budget.remainingFormulas() : limits.maxNodes(),
                    context.options.formulas() ? maxFormulaUtf16 : source.length(), !context.options.tables());
            if (context.options.formulas()) { budget.formulas(spans.size()); }
            context.markdownFormulasScanned = true;
            if (spans.isEmpty()) { return source; }
            String prefix = "EXPORT4JCONTENT";
            String decoded = org.jsoup.parser.Parser.unescapeEntities(source, false);
            while (source.contains(prefix) || decoded.contains(prefix) || resourceCollision(prefix)) { budget.work(source.length()); prefix += "Z"; }
            context.literalPrefix = prefix;
            var result = new StringBuilder(); int cursor = 0, index = 0;
            for (var span : spans) {
                result.append(source, cursor, span.start()); int start = result.length();
                String marker = prefix + index++ + "END";
                result.append(marker);
                if (context.options.formulas()) { context.formulaMarkers.put(marker, span); }
                else { context.literals.put(marker, org.jsoup.parser.Parser.unescapeEntities(source.substring(span.start(), span.end()), false)); }
                context.replacements.add(new ContentImportContext.Replacement(span.start(), span.end(), start, result.length()));
                cursor = span.end();
            }
            result.append(source, cursor, source.length()); budget.work(result.length());
            return result.toString();
        }
        private boolean resourceCollision(String prefix) {
            for (String key : images.keySet()) { budget.work(key.length()); if (key.contains(prefix)) { return true; } }
            for (String key : vectors.keySet()) { budget.work(key.length()); if (key.contains(prefix)) { return true; } }
            return false;
        }
    }
    private static String namespace(String value) {
        if (Objects.requireNonNull(value, "namespace").isBlank() || value.length() > 1024 || value.codePoints().anyMatch(Character::isISOControl)) { throw new IllegalArgumentException("invalid content source namespace"); }
        return value;
    }
}
