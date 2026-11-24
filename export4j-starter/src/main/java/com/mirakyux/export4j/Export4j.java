// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j;

import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.adapter.web.ImportLimits;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontFaceCache;
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
import com.mirakyux.export4j.model.DocumentTemplate;
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
import com.mirakyux.export4j.model.TextAppearance;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reusable offline export entry point. Declare font families and global defaults once; create an
 * independent request for text, Markdown, HTML or XML, then select PDF or DOCX.
 * The no-argument builder uses known local system fonts. An explicit initial family keeps all
 * default font selection under application control.
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
    private final List<FontFamily> fallbackFonts;
    private final SystemFonts systemFonts;
    private final Map<String, FontFamily> namedRoles;
    private final PdfBackend pdfBackend;
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
        systemFonts = b.systemFonts;
        fallbackFonts = List.copyOf(b.fallbackFonts);
        namedRoles = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(b.fontRoles));
        var declaredFamilies = new ArrayList<FontFamily>();
        addFamily(declaredFamilies, latin);
        addFamily(declaredFamilies, eastAsia);
        addFamily(declaredFamilies, complexScript);
        fallbackFonts.forEach(family -> addFamily(declaredFamilies, family));
        namedRoles.values().forEach(family -> addFamily(declaredFamilies, family));
        validateFamilies(declaredFamilies, namedRoles);
        var roleNames = new HashSet<String>(List.of("", "bold", "italic", "bolditalic"));
        for (var role : b.fontRoles.keySet()) {
            for (var variant : OperationFonts.variants(role)) {
                new TextAppearance(variant, 0);
                if (variant.toLowerCase(Locale.ROOT).startsWith("family_") || !roleNames.add(variant.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("font role conflicts with a reserved or declared role");
                }
            }
        }
        pdfBackend = b.pdfBackend;
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
    public static Builder builder(FontFamily latin) { return new Builder(Objects.requireNonNull(latin, "latin"), null); }

    /** Uses known physical font files installed on Windows, macOS or Linux. Fonts are resolved
     * during export under normal font and execution budgets. Missing local fonts or glyphs fail
     * PDF export. Use {@link #builder(FontFamily)} for reproducible fonts across hosts.
     */
    public static Builder builder() { return systemBuilder(SystemFonts.host()); }

    static Builder systemBuilder(SystemFonts systemFonts) { return new Builder(null, systemFonts); }

    private OperationFonts loadFonts() throws IOException {
        var system = systemFonts == null ? null : systemFonts.load(eastAsia, cache);
        var body = system == null ? latin : system.latin();
        var cjk = system == null ? eastAsia : system.eastAsia();
        var complex = complexScript == null ? body : complexScript;
        var defaults = new ArrayList<FontFamily>();
        addFamily(defaults, body);
        addFamily(defaults, cjk);
        addFamily(defaults, complex);
        fallbackFonts.forEach(family -> addFamily(defaults, family));
        var families = new ArrayList<>(defaults);
        namedRoles.values().forEach(family -> addFamily(families, family));
        if (system != null) { validateFamilies(families, namedRoles); }
        return OperationFonts.load(defaults, families, namedRoles, body, cjk, complex, cache);
    }

    private static void addFamily(List<FontFamily> families, FontFamily family) {
        if (family != null && !families.contains(family)) { families.add(family); }
    }

    private static void validateFamilies(List<FontFamily> families, Map<String, FontFamily> namedRoles) {
        if (families.size() > 8) { throw new IllegalArgumentException("at most eight font families are supported"); }
        var names = new HashSet<String>();
        for (var family : families) {
            if (!names.add(family.name().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("duplicate font family name: " + family.name());
            }
            for (var alias : namedRoles.entrySet()) {
                if (family.name().equalsIgnoreCase(alias.getKey()) && family != alias.getValue()) {
                    throw new IllegalArgumentException("font role alias conflicts with a physical family name: " + alias.getKey());
                }
            }
        }
    }
    public Request markdown(String source) { return request(source, ContentOptions.Format.MARKDOWN); }
    public Request html(String source) { return request(source, ContentOptions.Format.HTML); }
    /** Literal text; formula parsing is disabled unless explicitly re-enabled on this request. */
    public Request text(String source) {
        return request(source, ContentOptions.Format.TEXT).options(ContentOptions.builder().formulas(false).build());
    }
    /** XML template with sections and repeating page regions. PDF also supports notes.
     * Data is borrowed until execution, when template binding snapshots it. No template is cached.
     */
    public Request xml(String source, Map<String, ?> data) {
        return xml(source, (Object) data);
    }
    /** XML template with a Map, record or JavaBean root. DOCX rejects footnotes and endnotes.
     * Data is borrowed until each target executes and then snapshots through the same bounded
     * {@link com.mirakyux.export4j.model.DocumentTemplate#render(Object) template binding path}.
     * Properties come from record components or public getters, never fields. Getter code remains
     * trusted caller code and must return promptly; synchronous cancellation cannot interrupt it.
     */
    public Request xml(String source, Object data) {
        ensureOpen(); return new Request(source, Objects.requireNonNull(data, "data"), defaultDeclarations);
    }
    /** Prepares user-owned static XML syntax, including configured fragments, without loading fonts,
     * parsing source content, binding data or creating an output document. Execution revalidates
     * semantic declarations and resource keys under the current request configuration.
     */
    public XmlTemplate.Syntax prepareXml(String source) {
        ensureOpen();
        var xml = XmlTemplate.builder(); fragments.forEach(xml::fragment);
        return xml.build().prepare(source);
    }
    /** Executes user-owned syntax with fresh fonts, content session and bounded data snapshot. */
    public Request xml(XmlTemplate.Syntax syntax, Object data) {
        ensureOpen(); return new Request(Objects.requireNonNull(syntax, "syntax"), Objects.requireNonNull(data, "data"), defaultDeclarations);
    }
    public Request xml(XmlTemplate.Syntax syntax, Map<String, ?> data) { return xml(syntax, (Object) data); }
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
        private final SystemFonts systemFonts;
        private FontFamily eastAsia;
        private FontFamily complexScript;
        private final List<FontFamily> fallbackFonts = new ArrayList<>();
        private final Map<String, FontFamily> fontRoles = new LinkedHashMap<>();
        private PdfBackend pdfBackend = new PdfBackend();
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
        private Builder(FontFamily latin, SystemFonts systemFonts) {
            this.latin = latin; this.systemFonts = systemFonts; eastAsia = latin; complexScript = latin;
        }
        public Builder eastAsia(FontFamily value) { eastAsia = Objects.requireNonNull(value, "eastAsia"); return this; }
        public Builder complexScript(FontFamily value) { complexScript = Objects.requireNonNull(value, "complexScript"); return this; }
        public Builder fallbackFont(FontFamily value) { fallbackFonts.add(Objects.requireNonNull(value, "family")); return this; }
        /** Registers a stable business role and its explicitly declared physical variants. Variant
         * names append _bold, _italic and _boldItalic. Missing variants remain unavailable. These
         * families do not silently join the default missing-glyph fallback stack. Reserved/default,
         * family_ names, case-insensitive alias collisions and overlapping base/variant names are
         * rejected by build(). The role is also an explicit HTML CSS font-family alias; an alias may
         * share a physical family name only when both designate the same FontFamily value.
         */
        public Builder fontRole(String role, FontFamily value) {
            String name = new TextAppearance(Objects.requireNonNull(role, "role"), 0).fontRole();
            if (name.isEmpty() || fontRoles.putIfAbsent(name, Objects.requireNonNull(value, "family")) != null) {
                throw new IllegalArgumentException("font role must be nonempty and unique");
            }
            return this;
        }
        /** Borrows a reusable immutable PDF backend and its configured cache. This exporter never
         * closes the backend's caller-owned PdfFontCache. Sessions and PDF documents remain per output.
         */
        public Builder pdfBackend(PdfBackend value) { pdfBackend = Objects.requireNonNull(value, "pdfBackend"); return this; }
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
        private final XmlTemplate.Syntax syntax;
        private final Object data;
        private ContentOptions.Resolved options;
        private ContentOptions declarations;
        private ExecutionOptions controls = execution;
        private PageSettings page = pageSettings;
        private DocumentMetadata metadata;
        private final Map<String, InlineImage> resources = new LinkedHashMap<>(images);
        private Request(String source, Object data, ContentOptions declarations) {
            this.source = Objects.requireNonNull(source, "source"); syntax = null; this.data = data; this.declarations = declarations;
            options = declarations.resolve(ContentOptions.fromTheme(theme));
        }
        private Request(XmlTemplate.Syntax syntax, Object data, ContentOptions declarations) {
            source = null; this.syntax = syntax; this.data = data; this.declarations = declarations;
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
            var fonts = loadFonts();
            var regular = fonts.regular();
            // Confined to this operation: create at most one encoded snapshot, only when text is used.
            var formulaFonts = new MathTextFonts[1];
            var content = MathContent.builder().defaults(options).pageSettings(page)
                    .metadata(metadata == null ? DocumentMetadata.EMPTY : metadata)
                    .textFonts(MathTextFonts.deferred(() -> {
                        if (formulaFonts[0] == null) {
                            formulaFonts[0] = MathTextFonts.of(regular.stream().map(face -> face.data().bytes()).toArray(byte[][]::new));
                        }
                        return formulaFonts[0];
                    }))
                    .limits(importLimits).options(mathOptions)
                    .maxFormulas(maxFormulas).fallbacks(fallbacks);
            resources.forEach(content::image);
            fonts.content(content);
            ExecutionContext.progress("CONTENT", "", -1, 0, 1);
            var compiler = content.build();
            if (data != null) {
                MathContent.SectionedAuthor author = parser -> bindXml(fonts.checked(parser));
                if (pdf) {
                    var result = compiler.compileSectionedVisual(author);
                    var rendered = result.document();
                    var engine = fonts.engine().layoutLimits(layoutLimits).componentLimits(componentLimits).notes(rendered.notes());
                    engine.build().write(rendered.document(), pdfBackend, output);
                    return completed(new Result(result.formulas().size(), result.degradations()));
                }
                return writeDocx(compiler.compileSectionedDocx(author), fonts, output);
            }
            MathContent.Author author = parser -> {
                fonts.validate(options);
                return parser.read(source).execute().document();
            };
            if (pdf) {
                var result = compiler.compileVisual(author);
                var engine = fonts.engine().layoutLimits(layoutLimits).componentLimits(componentLimits);
                engine.build().write(result.document(), pdfBackend, output);
                return completed(new Result(result.formulas().size(), result.degradations()));
            }
            return writeDocx(compiler.compileDocx(author), fonts, output);
        }

        private DocumentTemplate.Rendered bindXml(com.mirakyux.export4j.model.SourceContentParser parser) {
            var xml = XmlTemplate.builder().theme(theme).pageSettings(page)
                    .contentOptions(declarations).contentParser(parser);
            // URI keys remain available to Markdown/HTML content through the shared parser.
            resources.forEach((key, image) -> {
                if (key.matches("[A-Za-z_][A-Za-z_0-9]{0,255}")) { xml.image(key, image.resource()); }
            });
            fragments.forEach(xml::fragment);
            var compiler = xml.build();
            var template = syntax == null ? compiler.compile(source) : compiler.compile(syntax);
            if (metadata != null) { template = template.toBuilder().metadata(metadata).build(); }
            return template.renderComplete(data);
        }

        private Result writeDocx(MathContent.DocxResult result, OperationFonts fonts, OutputStream output) throws IOException {
            result.document().semantic().body().sections().forEach(section -> {
                requireRoles(section.blocks(), fonts.roles());
                section.pageRegions().headers().forEach(band -> requireRoles(band.blocks(), fonts.roles()));
                section.pageRegions().footers().forEach(band -> requireRoles(band.blocks(), fonts.roles()));
            });
            fonts.word().build().writeMath(result.document(), output);
            return completed(new Result(result.formulas().size(), result.degradations()));
        }
    }

    private static Result completed(Result result) {
        ExecutionContext.progress("COMPLETE", "", -1, 1, 1); return result;
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
        if (!roles.containsKey(role)) {
            throw new IllegalArgumentException("undeclared font role '" + new TextAppearance(role, 0).fontRole() + "'");
        }
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
