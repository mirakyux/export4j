// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.Export4j;
import com.mirakyux.export4j.FontFamily;
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.pdf.PdfFontCache;
import com.mirakyux.export4j.backend.pdf.PdfLimits;
import com.mirakyux.export4j.backend.pdf.PdfOptions;
import com.mirakyux.export4j.backend.pdf.PdfRenderException;
import com.mirakyux.export4j.backend.pdf.PdfErrorCode;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.FontSource;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.RelativeWeights;
import com.mirakyux.export4j.model.Table;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Public published-API fixtures; independent validators inspect the generated PDF and standard OOXML. */
public final class ArchitectureConvergenceConsumer {
    private static final PageSettings PAGE = new PageSettings(new PageSize(360, 260), PageMargins.uniform(24));
    private static final String MARKDOWN = "ROLE_REGULAR **ROLE_BOLD** *ROLE_ITALIC* ***ROLE_BOTH***.";
    private static final String HTML = "<p>ROLE_REGULAR <strong>ROLE_BOLD</strong> <em>ROLE_ITALIC</em> <strong><em>ROLE_BOTH</em></strong>.</p>";
    private static final String XML = "<document version='1' content-format='MARKDOWN'><content id='role-body' source='body'/></document>";
    private static final Map<String, String> ROLE_MARKERS = Map.of("ROLE_REGULAR", "report", "ROLE_BOLD", "report_bold",
            "ROLE_ITALIC", "report_italic", "ROLE_BOTH", "report_boldItalic");

    private ArchitectureConvergenceConsumer() { }

    public static void main(String[] args) throws Exception {
        Path output = Files.createDirectories(Path.of(System.getProperty("consumer.outputDirectory", "build/output"))
                .resolve("architecture-convergence"));
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("schemaVersion", 1);
        manifest.put("runtimeLibraries", runtimeLibraries());
        var fonts = Path.of("../../assets/fonts");
        var sans = family(fonts, "Liberation Sans", "LiberationSans");
        var serif = family(fonts, "Liberation Serif", "LiberationSerif");
        var face = TrueTypeFontLoader.load(Files.readAllBytes(fonts.resolve("LiberationSans-Regular.ttf")));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(face))).build();
        var cache = PdfFontCache.builder().maxEntries(12).maxSourceBytes(32L * 1024 * 1024).build();
        var backendFacts = new LinkedHashMap<String, Object>();
        backendFacts.put("storage", "TEMP_FILE");
        backendFacts.put("before", cacheStats(cache.stats()));
        try (cache) {
            var backend = PdfBackend.builder().options(PdfOptions.storage(PdfOptions.Storage.TEMP_FILE)).fontCache(cache).build();
            manifest.put("fractionPairs", fractions(output, engine, backend));
            manifest.put("flowPair", flow(output, engine, backend));
            backendFacts.put("beforeStarter", cacheStats(cache.stats()));
            long starterParses = cache.stats().parses();
            var failures = new ArrayList<Map<String, Object>>();
            manifest.put("roleCases", roles(output, sans, serif, backend, failures));
            require(cache.stats().parses() >= starterParses + 4, "starter did not use the supplied backend cache for its four physical Serif variants");
            backendFacts.put("afterExporterClose", cacheStats(cache.stats()));
            require(!cache.stats().closed() && cache.stats().borrowedEntries() == 0, "exporter closed its borrowed PDF cache");
            long hits = cache.stats().hits();
            try (var reused = Export4j.builder(sans).pdfBackend(backend).build()) {
                reused.text("BORROWED_CACHE_REUSE").pdf(output.resolve("cache-reuse.pdf"));
            }
            require(cache.stats().hits() > hits && !cache.stats().closed(), "borrowed backend cache was not reused after exporter close");
            backendFacts.put("afterReuse", cacheStats(cache.stats()));
            backendBudget(output, sans, cache, failures);
            manifest.put("failures", failures);
        }
        backendFacts.put("afterOwnerClose", cacheStats(cache.stats()));
        manifest.put("backend", backendFacts);
        var sources = Files.createDirectories(output.resolve("source"));
        Files.writeString(sources.resolve("role.md"), MARKDOWN);
        Files.writeString(sources.resolve("role.html"), HTML);
        Files.writeString(sources.resolve("role.xml"), XML);
        manifest.put("sourceFiles", List.of("source/role.md", "source/role.html", "source/role.xml"));
        Files.writeString(output.resolve("manifest.json"), json(manifest) + "\n");
        System.out.println("Generated architecture convergence fixtures in " + output);
    }

    private static FontFamily family(Path fonts, String name, String stem) {
        return FontFamily.builder(name, FontSource.file(fonts.resolve(stem + "-Regular.ttf")))
                .bold(FontSource.file(fonts.resolve(stem + "-Bold.ttf")))
                .italic(FontSource.file(fonts.resolve(stem + "-Italic.ttf")))
                .boldItalic(FontSource.file(fonts.resolve(stem + "-BoldItalic.ttf"))).build();
    }

    private static List<Map<String, Object>> fractions(Path output, DocumentEngine engine, PdfBackend backend) throws Exception {
        var result = new ArrayList<Map<String, Object>>();
        Path directory = Files.createDirectories(output.resolve("fractions"));
        for (String shape : List.of("single", "double", "mixed")) {
            int fractionalColumns = shape.equals("single") ? 1 : 2;
            boolean fixed = shape.equals("mixed");
            var cases = new ArrayList<Map<String, Object>>();
            var markers = new ArrayList<String>();
            int count = fractionalColumns + (fixed ? 1 : 0);
            String[] header = new String[count];
            String[] body = new String[count];
            for (int index = 0; index < count; index++) {
                String prefix = shape.substring(0, 1).toUpperCase(Locale.ROOT);
                header[index] = prefix + "H" + index;
                body[index] = prefix + "B" + index;
                markers.add(header[index]); markers.add(body[index]);
            }
            for (boolean huge : List.of(false, true)) {
                var columns = new ArrayList<ColumnWidth>();
                if (fixed) { columns.add(new ColumnWidth.Fixed(40)); }
                for (int index = 0; index < fractionalColumns; index++) { columns.add(new ColumnWidth.Fraction(huge ? 1e308 : 1)); }
                var table = Table.builder("fraction-table").columns(columns.toArray(ColumnWidth[]::new))
                        .cellFormat(CellFormat.of(10)).header(header).row(body).build();
                var source = new Document(DocumentMetadata.EMPTY, PAGE, List.of(table));
                String name = shape + (huge ? "-huge" : "-normal");
                engine.export(source).backend(backend).to(directory.resolve(name + ".pdf"));
                new DocxAdapter().write(source, directory.resolve(name + ".docx"));
                cases.add(Map.of("scale", huge ? "1e308" : "1", "pdf", "fractions/" + name + ".pdf",
                        "docx", "fractions/" + name + ".docx"));
            }
            var expected = new ArrayList<Double>();
            if (fixed) { expected.add(40.0); }
            for (int index = 0; index < fractionalColumns; index++) { expected.add((PAGE.contentWidth() - (fixed ? 40 : 0)) / fractionalColumns); }
            result.add(Map.of("shape", shape, "sources", cases, "expectedGridPoints", expected, "markers", markers));
        }
        return result;
    }

    private static Map<String, Object> flow(Path output, DocumentEngine engine, PdfBackend backend) throws Exception {
        var blocks = new ArrayList<Block>();
        var markers = new ArrayList<String>();
        blocks.add(new Heading("flow-heading", "FLOW_HEADING", 1)); markers.add("FLOW_HEADING");
        for (int index = 0; index < 28; index++) {
            String marker = String.format(Locale.ROOT, "FLOW%03d", index);
            markers.add(marker);
            blocks.add(new Paragraph("flow-" + index, marker + " Shared semantic preparation keeps each paragraph in source order.", ParagraphFormat.of(10)));
        }
        blocks.add(Table.builder("flow-table").columns(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1))
                .header("FLOW_TABLEHEAD", "FLOW_VALUEHEAD").row("FLOW_TABLEBODY", "FLOW_VALUEBODY").build());
        markers.addAll(List.of("FLOW_TABLEHEAD", "FLOW_VALUEHEAD", "FLOW_TABLEBODY", "FLOW_VALUEBODY"));
        var source = new Document(DocumentMetadata.EMPTY, PAGE, blocks);
        var full = engine.export(source).backend(backend).to(output.resolve("flow-full.pdf"));
        var paged = engine.exportPaged(source).backend(backend).to(output.resolve("flow-paged.pdf"));
        int pages = full.display().pages().size();
        require(pages > 1 && paged.pages() == pages, "full and paged fixture page counts differ");
        return Map.of("full", "flow-full.pdf", "paged", "flow-paged.pdf", "pages", pages, "markers", markers);
    }

    private static List<Map<String, Object>> roles(Path output, FontFamily sans, FontFamily serif,
            PdfBackend backend, List<Map<String, Object>> failures) throws Exception {
        Path directory = Files.createDirectories(output.resolve("roles"));
        var result = new ArrayList<Map<String, Object>>();
        try (var exporter = Export4j.builder(sans).fontRole("report", serif).pdfBackend(backend)
                .defaults(ContentOptions.builder().fontRole("report").formulas(false).build()).build()) {
            long before = exporter.cacheStats().loads();
            var syntax = exporter.prepareXml(XML);
            require(exporter.cacheStats().loads() == before, "preparing syntax loaded physical fonts");
            rolePair(exporter.markdown(MARKDOWN), directory, "markdown", result);
            rolePair(exporter.html(HTML), directory, "html", result);
            rolePair(exporter.xml(XML, Map.of("body", MARKDOWN)), directory, "xml", result);
            rolePair(exporter.xml(syntax, Map.of("body", MARKDOWN)), directory, "prepared-syntax", result);
            for (boolean pdf : List.of(true, false)) {
                String name = pdf ? "limited-pdf.bin" : "limited-docx.bin";
                Path target = output.resolve(name);
                byte[] previous = "old-target-must-survive".getBytes(StandardCharsets.UTF_8);
                Files.write(target, previous);
                try {
                    var request = exporter.text("OUTPUT_BUDGET_MARKER").execution(ExecutionOptions.builder().maxOutputBytes(32).build());
                    if (pdf) { request.pdf(target); } else { request.docx(target); }
                    throw new IllegalStateException("output byte budget was ignored");
                } catch (ExecutionException expected) {
                    require(expected.code() == ExecutionException.Code.OUTPUT_LIMIT, "unexpected output failure code");
                    require(Arrays.equals(previous, Files.readAllBytes(target)), "budget failure replaced the old target");
                    failures.add(Map.of("file", name, "target", pdf ? "pdf" : "docx", "expectedCode", "OUTPUT_LIMIT", "oldTargetPreserved", true));
                }
            }
        }
        return result;
    }

    private static void rolePair(Export4j.Request request, Path output, String name, List<Map<String, Object>> cases) throws Exception {
        require(request.pdf(output.resolve(name + ".pdf")).degradations().isEmpty(), "unexpected PDF degradation");
        require(request.docx(output.resolve(name + ".docx")).degradations().isEmpty(), "unexpected DOCX degradation");
        cases.add(Map.of("format", name, "pdf", "roles/" + name + ".pdf", "docx", "roles/" + name + ".docx",
                "markers", List.of("ROLE_REGULAR", "ROLE_BOLD", "ROLE_ITALIC", "ROLE_BOTH"),
                "expectedFontRoles", ROLE_MARKERS, "expectedFontFamily", "Liberation Serif"));
    }

    private static void backendBudget(Path output, FontFamily sans, PdfFontCache cache, List<Map<String, Object>> failures) throws Exception {
        var limited = PdfBackend.builder().options(PdfOptions.storage(PdfOptions.Storage.TEMP_FILE)).fontCache(cache)
                .limits(PdfLimits.builder().maxTextCodePoints(1).build()).build();
        Path target = output.resolve("limited-backend.bin");
        Files.writeString(target, "old-backend-target");
        try (var exporter = Export4j.builder(sans).pdfBackend(limited).build()) {
            try {
                exporter.text("BACKEND_BUDGET_MARKER").pdf(target);
                throw new IllegalStateException("custom PDF backend budget was ignored");
            } catch (PdfRenderException expected) {
                require(expected.code() == PdfErrorCode.BUDGET_EXCEEDED, "unexpected backend failure code");
                require(Files.readString(target).equals("old-backend-target"), "backend failure replaced the old target");
                failures.add(Map.of("file", "limited-backend.bin", "target", "backend", "expectedCode", "BUDGET_EXCEEDED", "oldTargetPreserved", true));
            }
        }
    }

    private static Map<String, Object> cacheStats(PdfFontCache.Stats stats) {
        return Map.of("hits", stats.hits(), "parses", stats.parses(), "entries", stats.entries(),
                "borrowedEntries", stats.borrowedEntries(), "closed", stats.closed(), "cleanupFailures", stats.cleanupFailures());
    }

    private static List<Map<String, String>> runtimeLibraries() throws Exception {
        var result = new ArrayList<Map<String, String>>();
        for (var type : List.of(Export4j.class, XmlTemplate.Syntax.class, RelativeWeights.class, DocumentEngine.class,
                PdfBackend.class, PdfFontCache.class, DocxAdapter.class, FontSource.class)) {
            Path jar = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            require(Files.isRegularFile(jar) && jar.getFileName().toString().endsWith(".jar"), "library was not loaded from a published JAR");
            require(Collections.list(type.getClassLoader().getResources(type.getName().replace('.', '/') + ".class")).size() == 1,
                    "library has duplicate runtime providers");
            result.add(Map.of("requiredClass", type.getName(), "jar", jar.getFileName().toString(),
                    "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)))));
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }

    private static String json(Object value) throws IOException {
        if (value instanceof Map<?, ?> map) {
            var fields = new ArrayList<String>();
            for (var entry : map.entrySet()) { fields.add(json(entry.getKey().toString()) + ":" + json(entry.getValue())); }
            return "{" + String.join(",", fields) + "}";
        }
        if (value instanceof Iterable<?> list) {
            var values = new ArrayList<String>();
            for (var element : list) { values.add(json(element)); }
            return "[" + String.join(",", values) + "]";
        }
        if (value instanceof Number || value instanceof Boolean) { return value.toString(); }
        if (value instanceof String text) { return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""; }
        throw new IOException("unsupported manifest value");
    }
}
