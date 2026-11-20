// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.Export4j;
import com.mirakyux.export4j.FontFamily;
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.web.ContentImporter;
import com.mirakyux.export4j.adapter.web.FormulaProcessor;
import com.mirakyux.export4j.adapter.web.ImportLimits;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontFaceCache;
import com.mirakyux.export4j.font.FontSource;
import com.mirakyux.export4j.math.MathContent;
import com.mirakyux.export4j.math.MathException;
import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.SourceContentParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exercises review fixes through published coordinates; independent tools inspect the final files. */
public final class ReviewFixConsumer {
    private static final String FORMULA = "\\notASupportedCommand{x}";
    private static final String MARKDOWN = "PREFIX $" + FORMULA + "$ SUFFIX";
    private static final String XML = "<document version='1' content-format='MARKDOWN'><content id='body' source='body'/></document>";
    private static final int RED = 0xcc3300;

    private ReviewFixConsumer() { }

    public static void main(String[] arguments) throws Exception {
        Path output = Files.createDirectories(Path.of(System.getProperty("consumer.outputDirectory", "build/output")).resolve("review-fixes"));
        var fonts = Path.of("../../assets/fonts");
        var defaults = ContentOptions.builder().fontRole("report").rgb(RED).build();
        var cases = new ArrayList<Map<String, Object>>();
        try (var exporter = Export4j.builder(family(fonts, "Liberation Sans", "LiberationSans"))
                .fontRole("report", family(fonts, "Liberation Serif", "LiberationSerif")).defaults(defaults)
                .formulaFallbacks(List.of(MathContent.Fallback.TEXT)).build()) {
            String lists = "- BULLET_ITEM\n\n1. NUMBER_ITEM";
            pair(exporter.markdown(lists), output, "list-markdown", false, "report", cases);
            pair(exporter.markdown(lists).options(ContentOptions.builder().rawHtml(true).build()),
                    output, "list-raw-html", false, "report", cases);
            pair(exporter.html("<ul><li>BULLET_ITEM</li></ul><ol><li>NUMBER_ITEM</li></ol>"),
                    output, "list-html", false, "report", cases);
            pair(exporter.markdown(MARKDOWN), output, "fallback-markdown", true, "report", cases);
            pair(exporter.html("<p>PREFIX <strong><em>$" + FORMULA + "$</em></strong> SUFFIX</p>"),
                    output, "fallback-html", true, "report_boldItalic", cases);
            pair(exporter.xml(XML, Map.of("body", MARKDOWN)), output, "fallback-xml", true, "report", cases);
            pair(exporter.xml(exporter.prepareXml(XML), Map.of("body", MARKDOWN)),
                    output, "fallback-prepared", true, "report", cases);
        }
        var failures = new ArrayList<Map<String, Object>>();
        try (var strict = Export4j.builder(family(fonts, "Liberation Sans", "LiberationSans")).build()) {
            for (boolean pdf : List.of(true, false)) {
                String name = "strict-" + (pdf ? "pdf" : "docx") + ".bin";
                Path target = output.resolve(name);
                Files.writeString(target, "old-strict-target");
                try {
                    var request = strict.markdown(MARKDOWN);
                    if (pdf) { request.pdf(target); } else { request.docx(target); }
                    throw new AssertionError("unsupported formula succeeded without an explicit candidate");
                } catch (MathException expected) {
                    require(Files.readString(target).equals("old-strict-target"), "strict formula failure replaced the old target");
                    failures.add(Map.of("file", name, "oldTargetPreserved", true, "reason", expected.reason().name()));
                }
            }
        }
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("schemaVersion", 1);
        manifest.put("runtimeLibraries", libraries());
        manifest.put("cases", cases);
        manifest.put("failures", failures);
        manifest.put("session", session());
        manifest.put("fontCheckpoint", fontCheckpoint(fonts));
        Files.writeString(output.resolve("manifest.json"), json(manifest) + "\n");
        System.out.println("Verified published review fixes and generated seven PDF/DOCX pairs in " + output);
    }

    private static FontFamily family(Path directory, String name, String stem) {
        return FontFamily.builder(name, FontSource.file(directory.resolve(stem + "-Regular.ttf")))
                .bold(FontSource.file(directory.resolve(stem + "-Bold.ttf")))
                .italic(FontSource.file(directory.resolve(stem + "-Italic.ttf")))
                .boldItalic(FontSource.file(directory.resolve(stem + "-BoldItalic.ttf"))).build();
    }

    private static void pair(Export4j.Request request, Path output, String name, boolean fallback,
            String formulaRole, List<Map<String, Object>> cases) throws Exception {
        var pdf = request.pdf(output.resolve(name + ".pdf"));
        var docx = request.docx(output.resolve(name + ".docx"));
        for (var result : List.of(pdf, docx)) {
            require(result.degradations().size() == (fallback ? 1 : 0), "unexpected representation loss count");
            if (fallback) {
                require(result.degradations().getFirst().representation() == MathContent.Fallback.TEXT,
                        "explicit formula text candidate was not used");
            }
        }
        cases.add(Map.of("name", name, "pdf", name + ".pdf", "docx", name + ".docx", "fallback", fallback,
                "fontRole", formulaRole, "fontFamily", "Liberation Serif", "rgb", RED,
                "markers", fallback ? List.of("PREFIX", "$" + FORMULA + "$", "SUFFIX") : List.of("BULLET_ITEM", "NUMBER_ITEM")));
    }

    private static Map<String, Object> session() {
        var importer = ContentImporter.builder().limits(new ImportLimits(4096, 4096, 4, 8, 4096, 4, 4, 4096)).build();
        var exhausted = new SourceContentParser.Remaining(4, 0, 4, 4096, 8);
        var room = new SourceContentParser.Remaining(4, 4, 4, 4096, 8);
        int rejected = 0;
        try (var session = importer.openSession()) {
            for (int index = 0; index < 128; index++) {
                try { session.parse("retry-" + index, "Body", ContentOptions.defaults(), exhausted); }
                catch (IllegalArgumentException expected) { rejected++; }
            }
            var blocks = session.parse("retry-0", "Body", ContentOptions.defaults(), room);
            require(rejected == 128 && blocks.size() == 1, "preflight failures retained namespaces or reset cumulative input");
            try {
                session.parse("retry-0", "Body", ContentOptions.defaults(), room);
                throw new AssertionError("successful source namespace was accepted twice");
            } catch (IllegalArgumentException expected) {
                return Map.of("preflightFailures", rejected, "sameNameRetrySucceeded", true, "successfulDuplicateRejected", true);
            }
        }
    }

    private static Map<String, Object> fontCheckpoint(Path fonts) throws IOException {
        var source = FontSource.bytes(Files.readAllBytes(fonts.resolve("LiberationSans-Regular.ttf")));
        try (var cache = FontFaceCache.builder().build()) {
            var face = source.load(cache);
            int[] checkpoints = {0};
            require(source.load(cache, () -> checkpoints[0]++) == face, "checkpoint source lost cache identity");
            require(checkpoints[0] > 0, "published source did not observe the caller checkpoint");
            try {
                source.load(cache, () -> { throw new Stop(); });
                throw new AssertionError("checkpoint failure was swallowed");
            } catch (Stop expected) {
                require(source.load(cache) == face, "cancelled use invalidated another caller's font");
            }
            return Map.of("checkpointCalls", checkpoints[0], "failurePropagated", true, "faceStillUsable", true);
        }
    }

    private static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static List<Map<String, String>> libraries() throws Exception {
        var result = new ArrayList<Map<String, String>>();
        for (var type : List.of(Export4j.class, ContentImporter.class, MathContent.class, FontFaceCache.class,
                FontSource.class, FormulaProcessor.class, DocxAdapter.class, DocumentEngine.class)) {
            Path jar = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            require(Files.isRegularFile(jar) && jar.getFileName().toString().endsWith(".jar"), "consumer loaded workspace classes");
            require(Collections.list(type.getClassLoader().getResources(type.getName().replace('.', '/') + ".class")).size() == 1,
                    "duplicate class provider");
            result.add(Map.of("requiredClass", type.getName(), "jar", jar.getFileName().toString(),
                    "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)))));
        }
        return result;
    }

    private static void require(boolean value, String message) {
        if (!value) { throw new AssertionError(message); }
    }

    private static String json(Object value) {
        if (value instanceof Map<?, ?> map) {
            var values = new ArrayList<String>();
            map.forEach((key, item) -> values.add(json(key.toString()) + ":" + json(item)));
            return "{" + String.join(",", values) + "}";
        }
        if (value instanceof Iterable<?> items) {
            var values = new ArrayList<String>(); items.forEach(item -> values.add(json(item)));
            return "[" + String.join(",", values) + "]";
        }
        if (value instanceof Number || value instanceof Boolean) { return value.toString(); }
        if (value instanceof String text) {
            return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
        }
        throw new IllegalArgumentException("unsupported manifest value");
    }
}
