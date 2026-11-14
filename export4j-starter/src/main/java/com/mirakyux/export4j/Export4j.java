// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.docx.DocxFontFamilies;
import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.adapter.web.ImportLimits;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontFaceCache;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.FontSource;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.math.MathContent;
import com.mirakyux.export4j.math.MathOptions;
import com.mirakyux.export4j.math.MathTextFonts;
import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.Caption;
import com.mirakyux.export4j.model.CrossReference;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.model.Table;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reusable offline export entry point. Declare font families and global defaults once; create an
 * independent request for text, Markdown, HTML or a body XML template, then select PDF or DOCX.
 * No system fonts, external resources, macro execution or implicit representation fallback are used.
 * The instance owns a bounded idle-expiring font cache unless a caller-owned cache is supplied.
 * Parsed fonts, engines, formula text byte snapshots and documents exist only within each operation.
 * Calls may run concurrently if the supplied font sources and execution callbacks support it.
 */
public final class Export4j implements AutoCloseable {
    /** Actual representation changes for the selected output only; no document text is retained. */
    public record Result(int formulas, List<MathContent.Degradation> degradations) {
        public Result {
            if (formulas < 0) { throw new IllegalArgumentException("negative formula count"); }
            degradations = List.copyOf(degradations);
        }
    }

    private final FontFamily latin;
    private final FontFamily eastAsia;
    private final FontFamily complexScript;
    private final List<FontFamily> families;
    private final DocumentTheme theme;
    private final ContentOptions defaultDeclarations;
    private final PageSettings pageSettings;
    private final ExecutionOptions execution;
    private final ImportLimits importLimits;
    private final MathOptions mathOptions;
    private final int maxFormulas;
    private final List<MathContent.Fallback> fallbacks;
    private final Map<String, InlineImage> images;
    private final Map<String, String> fragments;
    private final DocumentLayoutLimits layoutLimits;
    private final ComponentLimits componentLimits;
    private final FontFaceCache cache;
    private final boolean ownsCache;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Export4j(Builder b) {
        latin = b.latin; eastAsia = b.eastAsia; complexScript = b.complexScript;
        var ordered = new ArrayList<FontFamily>();
        for (var family : List.of(latin, eastAsia, complexScript)) {
            if (!ordered.contains(family)) { ordered.add(family); }
        }
        for (var family : b.fallbackFonts) { if (!ordered.contains(family)) { ordered.add(family); } }
        families = List.copyOf(ordered);
        if (families.size() > 8) { throw new IllegalArgumentException("at most eight explicit font families are supported"); }
        var names = new HashSet<String>();
        for (var family : families) {
            if (!names.add(family.name().toLowerCase(Locale.ROOT))) { throw new IllegalArgumentException("duplicate font family name"); }
        }
        theme = b.theme;
        defaultDeclarations = ContentOptions.builder().formulas(true).build().overlay(b.defaults);
        pageSettings = b.pageSettings; execution = b.execution; importLimits = b.importLimits;
        mathOptions = b.mathOptions; maxFormulas = b.maxFormulas; fallbacks = List.copyOf(b.fallbacks);
        if (new HashSet<>(fallbacks).size() != fallbacks.size()) { throw new IllegalArgumentException("duplicate formula fallback"); }
        images = Map.copyOf(b.images); fragments = Map.copyOf(b.fragments);
        layoutLimits = b.layoutLimits; componentLimits = b.componentLimits;
        ownsCache = b.cache == null; cache = ownsCache ? FontFaceCache.builder().build() : b.cache;
    }

    /** The initial family supplies Latin and, until overridden, all Word script slots. */
    public static Builder builder(FontFamily latin) { return new Builder(latin); }
    public Request markdown(String source) { return request(source, ContentOptions.Format.MARKDOWN); }
    public Request html(String source) { return request(source, ContentOptions.Format.HTML); }
    /** Literal text; formula parsing is disabled unless explicitly re-enabled on this request. */
    public Request text(String source) {
        return request(source, ContentOptions.Format.TEXT).options(ContentOptions.builder().formulas(false).build());
    }
    /** Body XML template; regions, sections and notes retain the template's explicit rejection.
     * Data is borrowed until execution, when template binding snapshots it. No template is cached.
     */
    public Request xml(String source, Map<String, ?> data) {
        return xml(source, (Object) data);
    }
    /** Body XML template with a Map, record or JavaBean root, under the same body-only restriction.
     * Data is borrowed until each target executes and then snapshots through the same bounded
     * {@link com.mirakyux.export4j.model.DocumentTemplate#render(Object) template binding path}.
     * Properties come from record components or public getters, never fields. Getter code remains
     * trusted caller code and must return promptly; synchronous cancellation cannot interrupt it.
     */
    public Request xml(String source, Object data) {
        ensureOpen(); return new Request(source, Objects.requireNonNull(data, "data"), defaultDeclarations);
    }
    private Request request(String source, ContentOptions.Format format) {
        ensureOpen();
        return new Request(source, null, defaultDeclarations.overlay(ContentOptions.builder().format(format).build()));
    }
    public FontFaceCache.Stats cacheStats() { return cache.stats(); }
    public void clearCache() { ensureOpen(); cache.clear(); }
    /** Rejects new operations and clears owned cache references. Already loaded local fonts remain valid. */
    @Override public void close() { if (closed.compareAndSet(false, true) && ownsCache) { cache.close(); } }
    private void ensureOpen() { if (closed.get()) { throw new IllegalStateException("exporter is closed"); } }

    /** Task-confined builder. All options and collections are snapshotted by build(). */
    public static final class Builder {
        private final FontFamily latin;
        private FontFamily eastAsia;
        private FontFamily complexScript;
        private final List<FontFamily> fallbackFonts = new ArrayList<>();
        private DocumentTheme theme = DocumentTheme.builder().build();
        private ContentOptions defaults = ContentOptions.builder().build();
        private PageSettings pageSettings = PageSettings.A4;
        private ExecutionOptions execution = ExecutionOptions.DEFAULT;
        private ImportLimits importLimits = ImportLimits.DEFAULT;
        private MathOptions mathOptions = MathOptions.DEFAULT;
        private int maxFormulas = 1_000;
        private List<MathContent.Fallback> fallbacks = List.of();
        private final Map<String, InlineImage> images = new LinkedHashMap<>();
        private final Map<String, String> fragments = new LinkedHashMap<>();
        private DocumentLayoutLimits layoutLimits = DocumentLayoutLimits.DEFAULT;
        private ComponentLimits componentLimits = ComponentLimits.DEFAULT;
        private FontFaceCache cache;
        private Builder(FontFamily latin) {
            this.latin = Objects.requireNonNull(latin, "latin"); eastAsia = latin; complexScript = latin;
        }
        public Builder eastAsia(FontFamily value) { eastAsia = Objects.requireNonNull(value, "eastAsia"); return this; }
        public Builder complexScript(FontFamily value) { complexScript = Objects.requireNonNull(value, "complexScript"); return this; }
        public Builder fallbackFont(FontFamily value) { fallbackFonts.add(Objects.requireNonNull(value, "family")); return this; }
        public Builder theme(DocumentTheme value) { theme = Objects.requireNonNull(value, "theme"); return this; }
        public Builder defaults(ContentOptions value) { defaults = Objects.requireNonNull(value, "defaults"); return this; }
        public Builder pageSettings(PageSettings value) { pageSettings = Objects.requireNonNull(value, "pageSettings"); return this; }
        public Builder execution(ExecutionOptions value) { execution = Objects.requireNonNull(value, "execution"); return this; }
        public Builder importLimits(ImportLimits value) { importLimits = Objects.requireNonNull(value, "importLimits"); return this; }
        public Builder mathOptions(MathOptions value) { mathOptions = Objects.requireNonNull(value, "mathOptions"); return this; }
        public Builder maxFormulas(int value) { if (value < 1) { throw new IllegalArgumentException("formula limit must be positive"); } maxFormulas = value; return this; }
        /** Empty by default: unsupported formulas fail; IMAGE or TEXT require explicit selection. */
        public Builder formulaFallbacks(List<MathContent.Fallback> value) { fallbacks = List.copyOf(value); return this; }
        public Builder image(String key, InlineImage value) { images.put(key(key), Objects.requireNonNull(value, "image")); return this; }
        public Builder fragment(String key, String xml) { fragments.put(key(key), Objects.requireNonNull(xml, "xml")); return this; }
        public Builder layoutLimits(DocumentLayoutLimits value) { layoutLimits = Objects.requireNonNull(value, "layoutLimits"); return this; }
        public Builder componentLimits(ComponentLimits value) { componentLimits = Objects.requireNonNull(value, "componentLimits"); return this; }
        /** Borrowed cache: closing this exporter never closes a supplied cache. */
        public Builder fontCache(FontFaceCache value) { cache = Objects.requireNonNull(value, "cache"); return this; }
        public Export4j build() { return new Export4j(this); }
    }

    /** Independent mutable request; never share a request between threads. Source strings are content,
     * never filenames or URLs. Streams remain caller-owned and may contain a prefix on failure.
     * Path output requires an existing parent and atomically preserves old output on precommit failure.
     */
    public final class Request {
        private final String source;
        private final Object data;
        private ContentOptions.Resolved options;
        private ContentOptions declarations;
        private ExecutionOptions controls = execution;
        private PageSettings page = pageSettings;
        private DocumentMetadata metadata = DocumentMetadata.EMPTY;
        private final Map<String, InlineImage> resources = new LinkedHashMap<>(images);
        private Request(String source, Object data, ContentOptions declarations) {
            this.source = Objects.requireNonNull(source, "source"); this.data = data; this.declarations = declarations;
            options = declarations.resolve(ContentOptions.fromTheme(theme));
        }
        public Request options(ContentOptions value) {
            declarations = declarations.overlay(Objects.requireNonNull(value, "options"));
            options = declarations.resolve(ContentOptions.fromTheme(theme)); return this;
        }
        public Request execution(ExecutionOptions value) { controls = Objects.requireNonNull(value, "execution"); return this; }
        public Request pageSettings(PageSettings value) { page = Objects.requireNonNull(value, "pageSettings"); return this; }
        public Request metadata(DocumentMetadata value) { metadata = Objects.requireNonNull(value, "metadata"); return this; }
        public Request image(String key, InlineImage value) { resources.put(key(key), Objects.requireNonNull(value, "image")); return this; }
        public Result pdf(Path output) throws IOException { return publish(output, true); }
        public Result docx(Path output) throws IOException { return publish(output, false); }
        public Result pdf(OutputStream output) throws IOException { return write(output, true); }
        public Result docx(OutputStream output) throws IOException { return write(output, false); }

        private Result publish(Path output, boolean pdf) throws IOException {
            ensureOpen();
            Path target = Objects.requireNonNull(output, "output").toAbsolutePath().normalize();
            if (target.getParent() == null) { throw new IOException("output requires a parent directory"); }
            Path temporary = Files.createTempFile(target.getParent(), ".export4j-", ".tmp");
            try {
                var result = ExecutionContext.execute(controls, () -> {
                    try (var stream = new BufferedOutputStream(Files.newOutputStream(temporary))) {
                        return encode(ExecutionContext.output(stream), pdf);
                    }
                });
                // The execution scope's final checkpoint must succeed before replacing the destination.
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return result;
            } catch (IOException | RuntimeException | Error failure) {
                try { Files.deleteIfExists(temporary); } catch (IOException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        private Result write(OutputStream output, boolean pdf) throws IOException {
            ensureOpen(); Objects.requireNonNull(output, "output");
            return ExecutionContext.execute(controls, () -> encode(ExecutionContext.output(output), pdf));
        }
        private Result encode(OutputStream output, boolean pdf) throws IOException {
            ensureOpen();
            ExecutionContext.progress("FONTS", "", -1, 0, 1);
            var loaded = new IdentityHashMap<FontSource, FontFace>();
            var roles = new LinkedHashMap<String, List<FontFace>>();
            for (int index = 0; index < families.size(); index++) {
                var family = families.get(index);
                for (var variant : family.variants().entrySet()) {
                    ExecutionContext.checkpoint();
                    var face = loaded.get(variant.getValue());
                    if (face == null) {
                        face = Objects.requireNonNull(variant.getValue().load(cache), "font source result");
                        loaded.put(variant.getValue(), face);
                    }
                    var faces = roles.computeIfAbsent(variant.getKey(), ignored -> new ArrayList<>());
                    if (!faces.contains(face)) { faces.add(face); }
                    roles.put(familyRole(index, variant.getKey()), List.of(face));
                }
            }
            var regular = roles.get("");
            // Confined to this operation: create at most one encoded snapshot, only when text is used.
            var formulaFonts = new MathTextFonts[1];
            var content = MathContent.builder().defaults(options).pageSettings(page).metadata(metadata)
                    .textFonts(MathTextFonts.deferred(() -> {
                        if (formulaFonts[0] == null) {
                            formulaFonts[0] = MathTextFonts.of(regular.stream().map(face -> face.data().bytes()).toArray(byte[][]::new));
                        }
                        return formulaFonts[0];
                    }))
                    .limits(importLimits).options(mathOptions)
                    .maxFormulas(maxFormulas).fallbacks(fallbacks);
            resources.forEach(content::image);
            for (int index = 0; index < families.size(); index++) {
                content.fontFamily(families.get(index).name(), familyRole(index, ""), familyRole(index, "bold"),
                        familyRole(index, "italic"), familyRole(index, "boldItalic"));
            }
            MathContent.Author author = parser -> {
                if (data == null) { return parser.read(source).execute().document(); }
                var xml = XmlTemplate.builder().theme(theme).pageSettings(page)
                        .contentOptions(declarations).contentParser(parser);
                // Content source keys may be URIs. Direct XML image references use identifiers only;
                // URI keys remain available to Markdown/HTML content through the shared parser.
                resources.forEach((key, image) -> {
                    if (key.matches("[A-Za-z_][A-Za-z_0-9]{0,255}")) { xml.image(key, image.resource()); }
                });
                fragments.forEach(xml::fragment);
                return xml.build().compile(source).render(data);
            };
            ExecutionContext.progress("CONTENT", "", -1, 0, 1);
            if (pdf) {
                var result = content.build().compileVisual(author);
                var engine = DocumentEngine.builder(FontGroup.strict(regular)).layoutLimits(layoutLimits).componentLimits(componentLimits);
                roles.forEach((role, faces) -> { if (!role.isEmpty()) { engine.fontRole(role, FontGroup.strict(faces)); } });
                engine.build().write(result.document(), new PdfBackend(), output);
                return completed(new Result(result.formulas().size(), result.degradations()));
            }
            var result = content.build().compileDocx(author);
            result.document().semantic().body().sections().forEach(section -> requireRoles(section.blocks(), roles));
            var wordFonts = new DocxFontFamilies(latin.name(), latin.name(), eastAsia.name(), complexScript.name());
            var word = DocxAdapter.builder().defaultFonts(wordFonts).lineSpacing(DocxAdapter.LineSpacing.OBJECTS_AT_LEAST);
            for (int index = 0; index < families.size(); index++) {
                var family = families.get(index);
                for (var variant : family.variants().keySet()) {
                    word.fontRole(familyRole(index, variant), DocxFontFamilies.uniform(family.name()),
                            variant.equals("bold") || variant.equals("boldItalic"),
                            variant.equals("italic") || variant.equals("boldItalic"));
                }
            }
            word.build().writeMath(result.document(), output);
            return completed(new Result(result.formulas().size(), result.degradations()));
        }
    }

    private static Result completed(Result result) {
        ExecutionContext.progress("COMPLETE", "", -1, 1, 1); return result;
    }

    private static String familyRole(int index, String variant) {
        return "family_" + index + (variant.isEmpty() ? "" : "_" + variant);
    }

    private static void requireRoles(List<Block> blocks, Map<String, List<FontFace>> roles) {
        SemanticTree.walk(blocks, visit -> {
            var block = visit.block();
            if (block instanceof Paragraph paragraph) { requireRolesInContent(paragraph.content(), roles); }
            else if (block instanceof Heading heading) { requireRolesInContent(heading.paragraph().content(), roles); }
            else if (block instanceof BulletItem item) { requireRolesInContent(item.paragraph().content(), roles); }
            else if (block instanceof NumberedItem item) { requireRolesInContent(item.paragraph().content(), roles); }
            else if (block instanceof Caption caption) { requireRole(caption.appearance().fontRole(), roles); }
            else if (block instanceof CrossReference reference) { requireRole(reference.appearance().fontRole(), roles); }
            if (block instanceof Table table) {
                for (var row : table.rows()) {
                    for (var cell : row.cells()) {
                        if (!cell.hasBlocks()) {
                            cell.inlines().forEach(text -> requireText(text, roles));
                            cell.fields().forEach(field -> requireRole(field.field().style().fontRole(), roles));
                        }
                    }
                }
            }
        });
    }

    private static void requireRolesInContent(List<InlineContent> content, Map<String, List<FontFace>> roles) {
        for (var item : content) {
            if (item instanceof InlineText text) { requireText(text, roles); }
            else if (item instanceof InlineField field) { requireRole(field.style().fontRole(), roles); }
        }
    }

    private static void requireRole(String role, Map<String, List<FontFace>> roles) {
        if (!roles.containsKey(role)) { throw new IllegalArgumentException("text requires an undeclared physical font role"); }
    }

    private static void requireText(InlineText text, Map<String, List<FontFace>> roles) {
        // DOCX declares the font family; the reader resolves installed fonts and glyph coverage.
        requireRole(text.style().fontRole(), roles);
    }

    private static String key(String value) {
        if (Objects.requireNonNull(value, "key").isBlank()) { throw new IllegalArgumentException("empty resource key"); }
        return value;
    }

}
