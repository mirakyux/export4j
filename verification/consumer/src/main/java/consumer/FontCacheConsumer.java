// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.pdf.PdfFontCache;
import com.mirakyux.export4j.backend.pdf.PdfLimits;
import com.mirakyux.export4j.backend.pdf.PdfSpoolBackend;
import com.mirakyux.export4j.backend.pdf.PdfSpoolLimits;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontFaceCache;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ParagraphFormat;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Published-coordinate acceptance and a separately invoked, fixed-input allocation measurement. */
public final class FontCacheConsumer {
    private static final String TEXT = "FONT-CACHE 中文字体缓存重复导出，English 0123456789.";

    private FontCacheConsumer() { }

    public static void main(String[] args) throws Exception {
        var output = Files.createDirectories(Path.of(System.getProperty("consumer.outputDirectory", "build/output"))
                .resolve(args.length == 0 ? "font-cache" : "font-cache-benchmark-" + args[0]));
        byte[] bytes = Files.readAllBytes(Path.of("../../assets/fonts/ZCOOLXiaoWei-Regular.ttf"));
        require(Path.of(FontFaceCache.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .getFileName().toString().endsWith(".jar"), "font caching must be consumed from a published JAR");
        if (args.length > 0) { benchmark(Boolean.parseBoolean(args[0]), bytes, output); return; }

        TrueTypeFontLoader.clearCache();
        FontFace defaultFace = TrueTypeFontLoader.load(bytes);
        require(defaultFace == TrueTypeFontLoader.load(bytes), "default loader did not reuse a face");
        require(TrueTypeFontLoader.cacheStats().entries() == 1, "default loader is not actually retaining faces");
        PdfFontCache.clearDefault();
        DisplayDocument defaultDisplay = display(defaultFace);
        new PdfBackend().write(defaultDisplay, new ByteArrayOutputStream());
        long defaultParses = PdfFontCache.defaultStats().parses();
        long defaultHits = PdfFontCache.defaultStats().hits();
        new PdfBackend().write(defaultDisplay, new ByteArrayOutputStream());
        require(PdfFontCache.defaultStats().parses() == defaultParses
                && PdfFontCache.defaultStats().hits() > defaultHits, "default PDF program reuse is not enabled");

        var report = new Properties();
        try (var faces = FontFaceCache.builder().idleTimeout(Duration.ofSeconds(1)).build();
                var programs = PdfFontCache.builder().idleTimeout(Duration.ofSeconds(1)).build();
                var disabled = PdfFontCache.builder().enabled(false).build()) {
            FontFace face = faces.load(bytes);
            require(face == faces.load(bytes), "custom loader did not reuse a face");
            DisplayDocument display = display(face);
            var backend = PdfBackend.builder().fontCache(programs).build();
            write(backend, display, output.resolve("cold.pdf"));
            long firstParses = programs.stats().parses();
            write(backend, display, output.resolve("warm.pdf"));
            require(programs.stats().parses() == firstParses && programs.stats().hits() > 0,
                    "warm export reparsed a retained font");
            await(() -> faces.stats().entries() == 0 && programs.stats().entries() == 0,
                    "idle caches did not release automatically");
            require(faces.stats().expirations() > 0 && programs.stats().expirations() > 0,
                    "passive observations did not record autonomous expiration");
            require(face.glyph('中').isPresent(), "expiration invalidated a caller-owned face");
            require(faces.load(bytes) != face, "expired immutable face was retained");
            write(backend, display, output.resolve("expired.pdf"));
            require(programs.stats().parses() > firstParses, "expired PDF font was not independently reloaded");
            write(PdfBackend.builder().fontCache(disabled).build(), display, output.resolve("disabled.pdf"));
            require(disabled.stats().entries() == 0, "disabled pool retained a program");
            try (var stream = Files.newOutputStream(output.resolve("page-session.pdf"));
                    var writer = backend.open(DocumentMetadata.EMPTY, stream)) {
                for (var page : display.pages()) { writer.accept(page); }
                programs.clear();
                writer.finish();
            }
            require(programs.stats().entries() == 0, "clear did not retire the active page-session program");
            try (var stream = Files.newOutputStream(output.resolve("spool.pdf"));
                    var writer = new PdfSpoolBackend(output, PdfLimits.DEFAULT, PdfSpoolLimits.DEFAULT, programs)
                            .open(DocumentMetadata.EMPTY, stream)) {
                for (var page : display.pages()) { writer.accept(page); }
                writer.finish();
            }
            report.setProperty("faceStats", faces.stats().toString());
            report.setProperty("pdfStats", programs.stats().toString());
        }
        report.setProperty("defaultLoaderEnabled", "true");
        report.setProperty("defaultPdfEnabled", "true");
        report.setProperty("passiveAutomaticExpiry", "true");
        report.setProperty("activeClearSafe", "true");
        report.setProperty("callerFaceSurvivesExpiry", "true");
        report.setProperty("expectedText", TEXT);
        report.setProperty("pagesPerPdf", "1");
        try (var stream = Files.newOutputStream(output.resolve("consumer-report.xml"))) {
            report.storeToXML(stream, "Published font cache lifetime acceptance");
        }
        TrueTypeFontLoader.clearCache();
        PdfFontCache.clearDefault();
        System.out.println("Published font caches: default reuse, idle release, disabled, page and spool paths passed");
    }

    private static DisplayDocument display(FontFace face) {
        var document = Document.builder().paragraph(TEXT,
                ParagraphFormat.builder().fontSize(14).lineHeight(24).build()).build();
        return DocumentEngine.builder(FontGroup.strict(List.of(face))).build().layout(document).display();
    }

    private static void write(PdfBackend backend, DisplayDocument display, Path target) throws Exception {
        try (var stream = Files.newOutputStream(target)) { backend.write(display, stream); }
    }

    private static void await(BooleanSupplier condition, String message) throws InterruptedException {
        var done = new CountDownLatch(1);
        try (var observer = Executors.newSingleThreadScheduledExecutor()) {
            observer.scheduleAtFixedRate(() -> {
                if (condition.getAsBoolean()) { done.countDown(); }
            }, 0, 10, TimeUnit.MILLISECONDS);
            require(done.await(10, TimeUnit.SECONDS), message);
        }
    }

    private static void benchmark(boolean enabled, byte[] bytes, Path output) throws Exception {
        var bean = ManagementFactory.getThreadMXBean();
        var allocation = bean instanceof com.sun.management.ThreadMXBean value ? value : null;
        if (allocation != null && !allocation.isThreadAllocatedMemorySupported()) { allocation = null; }
        if (allocation != null && allocation.isThreadAllocatedMemorySupported()) {
            allocation.setThreadAllocatedMemoryEnabled(true);
        }
        var nanos = new ArrayList<Long>();
        var allocated = new ArrayList<Long>();
        try (var faces = FontFaceCache.builder().enabled(enabled).build();
                var programs = PdfFontCache.builder().enabled(enabled).build()) {
            var backend = PdfBackend.builder().fontCache(programs).build();
            // First use is recorded separately; all five later exports repeat exactly the same work.
            for (int iteration = 0; iteration < 6; iteration++) {
                long beforeBytes = allocation == null ? -1 : allocation.getThreadAllocatedBytes(Thread.currentThread().threadId());
                long start = System.nanoTime();
                FontFace face = faces.load(bytes);
                write(backend, display(face), output.resolve("sample-" + iteration + ".pdf"));
                nanos.add(System.nanoTime() - start);
                allocated.add(allocation == null ? -1
                        : allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) - beforeBytes);
            }
            var report = new Properties();
            report.setProperty("enabled", Boolean.toString(enabled));
            report.setProperty("fontBytes", Integer.toString(bytes.length));
            report.setProperty("fontSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            report.setProperty("nanos", nanos.toString());
            report.setProperty("mainThreadAllocatedBytes", allocated.toString());
            report.setProperty("faceStats", faces.stats().toString());
            report.setProperty("pdfStats", programs.stats().toString());
            report.setProperty("measurement", "Six complete exports; first cold, five repeated; no forced GC or output quality change");
            try (var stream = Files.newOutputStream(output.resolve("measurement.xml"))) {
                report.storeToXML(stream, "Fixed-input cache measurement; not an RSS or peak-heap measurement");
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
