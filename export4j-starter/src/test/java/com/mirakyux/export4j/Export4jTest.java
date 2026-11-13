// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j;

import com.mirakyux.export4j.font.FontFaceCache;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.font.FontMetrics;
import com.mirakyux.export4j.font.FontSource;
import com.mirakyux.export4j.font.GlyphMetrics;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.math.MathException;
import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Configured-once export must retain output, resource and lifecycle contracts. */
class Export4jTest {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String M = "http://schemas.openxmlformats.org/officeDocument/2006/math";
    @TempDir Path directory;

    @Test void oneFontDeclarationServesAllSourceFormatsAndBothOutputs() throws Exception {
        var chinese = FontFamily.of("LXGW WenKai TC", source("LXGWWenKaiTC-Regular.ttf"));
        try (var exporter = Export4j.builder(latin()).eastAsia(chinese).build()) {
            String xml = """
                    <document version="1" content-format="MARKDOWN" formulas="true">
                      <content id="body" source="body"/>
                    </document>
                    """;
            for (String format : List.of("markdown", "html", "xml")) {
                Export4j.Request request = switch (format) {
                    case "markdown" -> exporter.markdown("English 中文 $x^2$ and $\\text{中}$.");
                    case "html" -> exporter.html("<p>English 中文 $x^2$ and $\\text{中}$.</p>");
                    default -> exporter.xml(xml, Map.of("body", "English 中文 $x^2$ and $\\text{中}$."));
                };
                var pdf = new ByteArrayOutputStream();
                assertEquals(2, request.pdf(pdf).formulas());
                String text = pdfText(pdf.toByteArray());
                assertTrue(text.contains("English 中文"), format + ": " + text);
                var docx = new ByteArrayOutputStream();
                assertEquals(2, request.docx(docx).formulas());
                var document = documentXml(docx.toByteArray());
                assertEquals(2, document.getElementsByTagNameNS(M, "oMath").getLength());
                var font = (Element) partXml(docx.toByteArray(), "word/styles.xml").getElementsByTagNameNS(W, "rFonts").item(0);
                assertEquals("Liberation Sans", font.getAttributeNS(W, "ascii"));
                assertEquals("LXGW WenKai TC", font.getAttributeNS(W, "eastAsia"));
            }
        }
    }

    @Test void docxDeclaresFontsWithoutRequiringTheirPhysicalGlyphCoverage() throws Exception {
        var regular = FontFamily.of("Liberation Sans", source("LiberationSans-Regular.ttf"));
        String xml = """
                <document version="1">
                  <p>中文正文</p>
                  <bullet marker="中">ascii</bullet>
                </document>
                """;
        try (var exporter = Export4j.builder(regular).build()) {
            var output = new ByteArrayOutputStream();
            exporter.xml(xml, Map.of()).docx(output);
            var document = documentXml(output.toByteArray());
            assertTrue(document.getDocumentElement().getTextContent().contains("中文正文"));
            assertTrue(document.getDocumentElement().getTextContent().contains("ascii"));
            var label = (Element) partXml(output.toByteArray(), "word/numbering.xml")
                    .getElementsByTagNameNS(W, "lvlText").item(0);
            assertEquals("中", label.getAttributeNS(W, "val"));
            var fonts = (Element) partXml(output.toByteArray(), "word/styles.xml")
                    .getElementsByTagNameNS(W, "rFonts").item(0);
            for (String slot : List.of("ascii", "hAnsi", "eastAsia", "cs")) {
                assertEquals("Liberation Sans", fonts.getAttributeNS(W, slot));
            }

            assertThrows(DocumentLayoutException.class,
                    () -> exporter.text("中文正文").pdf(new ByteArrayOutputStream()));
            assertThrows(DocumentLayoutException.class, () -> exporter.xml(
                    "<document version='1'><bullet marker='中'>ascii</bullet></document>", Map.of())
                    .pdf(new ByteArrayOutputStream()));
            assertThrows(IllegalArgumentException.class, () -> exporter.text("ascii")
                    .options(ContentOptions.builder().fontRole("undeclared").build())
                    .docx(new ByteArrayOutputStream()));
        }
    }

    @Test void eachOperationReopensAnIdenticalFontSourceOnlyOnceAcrossVariantsAndFormulaText() throws Exception {
        var calls = new AtomicInteger();
        FontSource regular = cache -> { calls.incrementAndGet(); return source("LiberationSans-Regular.ttf").load(cache); };
        // Reusing a source in explicitly declared roles must not reopen it in the same operation.
        var family = FontFamily.builder("Liberation Sans", regular).bold(regular).italic(regular).boldItalic(regular).build();
        try (var exporter = Export4j.builder(family).eastAsia(family).build()) {
            exporter.markdown("**Body** $\\text{Body}$").pdf(new ByteArrayOutputStream());
            assertEquals(1, calls.get());
            assertEquals(1, exporter.cacheStats().loads());
            exporter.markdown("**Body** $\\text{Body}$").docx(new ByteArrayOutputStream());
            assertEquals(2, calls.get());
            assertEquals(1, exporter.cacheStats().loads());
            assertTrue(exporter.cacheStats().hits() >= 1);
        }
    }

    @Test void chineseFormulaTextUsesTheSameExplicitSourcesAsChineseBodyText() throws Exception {
        var latinCalls = new AtomicInteger();
        var chineseCalls = new AtomicInteger();
        FontSource latin = cache -> { latinCalls.incrementAndGet(); return source("LiberationSans-Regular.ttf").load(cache); };
        FontSource chinese = cache -> { chineseCalls.incrementAndGet(); return source("LXGWWenKaiTC-Regular.ttf").load(cache); };
        try (var exporter = Export4j.builder(FontFamily.of("Liberation Sans", latin))
                .eastAsia(FontFamily.of("LXGW WenKai TC", chinese)).build()) {
            var output = new ByteArrayOutputStream();
            assertEquals(1, exporter.markdown("中文正文 $\\textrm{中}$.").pdf(output).formulas());
            assertTrue(pdfText(output.toByteArray()).contains("中文正文"));
            assertEquals(1, latinCalls.get());
            assertEquals(1, chineseCalls.get());
            assertEquals(2, exporter.cacheStats().loads());
        }
    }

    @Test void formulaTextByteSnapshotsArePreparedOncePerOperation() throws Exception {
        var snapshots = new AtomicInteger();
        FontSource source = cache -> {
            var delegate = source("LiberationSans-Regular.ttf").load(cache);
            return new FontFace() {
                @Override public FontData data() { snapshots.incrementAndGet(); return delegate.data(); }
                @Override public FontMetrics metrics() { return delegate.metrics(); }
                @Override public Optional<GlyphMetrics> glyph(int codePoint) { return delegate.glyph(codePoint); }
            };
        };
        try (var exporter = Export4j.builder(FontFamily.of("Liberation Sans", source)).build()) {
            exporter.markdown("$\\text{One}$ and $\\textrm{Two}$.").docx(new ByteArrayOutputStream());
            assertEquals(1, snapshots.get());
            exporter.markdown("$\\text{Three}$ and $\\textrm{Four}$.").docx(new ByteArrayOutputStream());
            assertEquals(2, snapshots.get());
            exporter.markdown("Only $x^2$.").docx(new ByteArrayOutputStream());
            assertEquals(2, snapshots.get());
        }
    }

    @Test void undeclaredPhysicalVariantsFailInBothTargetsInsteadOfRelyingOnWordSynthesis() throws Exception {
        var regular = FontFamily.of("Liberation Sans", source("LiberationSans-Regular.ttf"));
        try (var exporter = Export4j.builder(regular).build()) {
            for (String markdown : List.of("**Bold**", "*Italic*", "***Both***")) {
                var pdf = new ByteArrayOutputStream();
                var word = new ByteArrayOutputStream();
                assertThrows(RuntimeException.class, () -> exporter.markdown(markdown).pdf(pdf));
                assertThrows(RuntimeException.class, () -> exporter.markdown(markdown).docx(word));
                assertEquals(0, pdf.size());
                assertEquals(0, word.size());
            }
        }
    }

    @Test void changedFontFilesAreRevalidatedAndClearOnlyRemovesCacheReferences() throws Exception {
        Path file = directory.resolve("body.ttf");
        Files.write(file, fontBytes("LiberationSans-Regular.ttf"));
        var exporter = Export4j.builder(FontFamily.of("Fixture", FontSource.file(file))).build();
        try (exporter) {
            var first = new ByteArrayOutputStream();
            exporter.text("Before").pdf(first);
            String regularName = pdfFontName(first.toByteArray());
            Files.write(file, fontBytes("LiberationSans-Bold.ttf"));
            var second = new ByteArrayOutputStream();
            exporter.text("After").pdf(second);
            assertNotEquals(regularName, pdfFontName(second.toByteArray()));
            assertEquals(2, exporter.cacheStats().loads());
            exporter.clearCache();
            assertEquals(0, exporter.cacheStats().entries());
            exporter.text("Reloaded").pdf(new ByteArrayOutputStream());
            assertEquals(3, exporter.cacheStats().loads());
        }
        assertEquals(0, exporter.cacheStats().entries());
        assertThrows(IllegalStateException.class, () -> exporter.text("closed"));
        assertThrows(IllegalStateException.class, exporter::clearCache);
    }

    @Test void closingExporterKeepsBorrowedCacheUsableAndRejectsPreviouslyCreatedRequests() throws Exception {
        try (var cache = FontFaceCache.builder().build()) {
            var exporter = Export4j.builder(latin()).fontCache(cache).build();
            var pending = exporter.text("Pending");
            exporter.text("Initial").docx(new ByteArrayOutputStream());
            exporter.close();
            assertThrows(IllegalStateException.class, () -> pending.pdf(new ByteArrayOutputStream()));
            assertTrue(source("LiberationSans-Regular.ttf").load(cache).glyph('A').isPresent());
            exporter.close();
        }
    }

    @Test void alreadyLoadedFontsRemainUsableWhenExporterClosesDuringOutput() throws Exception {
        var exporter = Export4j.builder(latin()).build();
        var closed = new AtomicBoolean();
        var output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int offset, int length) {
                if (closed.compareAndSet(false, true)) { exporter.close(); }
                super.write(bytes, offset, length);
            }
        };
        try (exporter) { exporter.text("In flight").pdf(output); }
        assertTrue(closed.get());
        assertTrue(pdfText(output.toByteArray()).contains("In flight"));
        assertThrows(IllegalStateException.class, () -> exporter.text("next"));
    }

    @Test void pdfOnlyDoesNotInheritNativeWordFormulaRestrictions() throws Exception {
        try (var exporter = Export4j.builder(latin()).build()) {
            var pdf = new ByteArrayOutputStream();
            var result = exporter.markdown("Mean $\\bar{x}$.").pdf(pdf);
            assertEquals(1, result.formulas());
            assertTrue(result.degradations().isEmpty());
            assertTrue(pdfText(pdf.toByteArray()).contains("Mean"));
            assertThrows(MathException.class, () -> exporter.markdown("Mean $\\bar{x}$.").docx(new ByteArrayOutputStream()));
        }
    }

    @Test void requestAndXmlScopeOverridesDoNotChangeGlobalThemeDefaults() throws Exception {
        var theme = DocumentTheme.builder().rgb(0x223344).bodyFormat(p -> p.fontSize(10).lineHeight(22)).build();
        try (var exporter = Export4j.builder(latin()).theme(theme).build()) {
            var changed = new ByteArrayOutputStream();
            exporter.text("Changed").options(ContentOptions.builder().rgb(0xAABBCC).fontSize(11).build()).docx(changed);
            assertRun(changed.toByteArray(), "Changed", "AABBCC", "22");
            var inherited = new ByteArrayOutputStream();
            exporter.text("Inherited").docx(inherited);
            assertRun(inherited.toByteArray(), "Inherited", "223344", "20");
            var scoped = new ByteArrayOutputStream();
            exporter.xml("""
                    <document version="1">
                      <scope color="#445566"><content id="scoped">Scoped</content></scope>
                      <content id="restored">Restored</content>
                    </document>
                    """, Map.of()).docx(scoped);
            assertRun(scoped.toByteArray(), "Scoped", "445566", "20");
            assertRun(scoped.toByteArray(), "Restored", "223344", "20");
        }
    }

    @Test void xmlRetainsDistinctThemeDomainsInsteadOfFlatteningBodyDefaults() throws Exception {
        var theme = DocumentTheme.builder().rgb(0x111111).bodyRgb(0x223344)
                .headingRgb(1, 0xAABBCC).cellRgb(0x445566)
                .bodyFormat(p -> p.fontSize(10).lineHeight(22))
                .headingFormat(1, p -> p.fontSize(18).lineHeight(28))
                .cellFormat(c -> c.fontSize(9).lineHeight(20)).build();
        try (var exporter = Export4j.builder(latin()).theme(theme).build()) {
            var output = new ByteArrayOutputStream();
            exporter.xml("""
                    <document version="1">
                      <heading level="1">Heading</heading><p>Body</p>
                      <table><columns><column weight="1"/></columns><row><cell>Cell</cell></row></table>
                    </document>
                    """, Map.of()).docx(output);
            assertRun(output.toByteArray(), "Heading", "AABBCC", "36");
            assertRun(output.toByteArray(), "Body", "223344", "20");
            assertRun(output.toByteArray(), "Cell", "445566", "18");
        }
    }

    @Test void explicitCssFontFamilySelectsThatPhysicalFamilyInBothTargets() throws Exception {
        var serif = FontFamily.of("Liberation Serif", source("LiberationSerif-Regular.ttf"));
        try (var exporter = Export4j.builder(latin()).fallbackFont(serif).build()) {
            String source = "<p>Sans.</p><p style=\"font-family:'Liberation Serif'\">Serif.</p>";
            var pdf = new ByteArrayOutputStream();
            exporter.html(source).pdf(pdf);
            try (var document = Loader.loadPDF(pdf.toByteArray())) {
                var names = new ArrayList<String>();
                var resources = document.getPage(0).getResources();
                for (var name : resources.getFontNames()) { names.add(resources.getFont(name).getName()); }
                assertTrue(names.stream().anyMatch(name -> name.contains("LiberationSans")), names.toString());
                assertTrue(names.stream().anyMatch(name -> name.contains("LiberationSerif")), names.toString());
            }
            var word = new ByteArrayOutputStream();
            exporter.html(source).docx(word);
            var run = run(word.toByteArray(), "Serif.");
            var style = (Element) run.getElementsByTagNameNS(W, "rStyle").item(0);
            String role = style.getAttributeNS(W, "val");
            var styles = partXml(word.toByteArray(), "word/styles.xml").getElementsByTagNameNS(W, "style");
            boolean found = false;
            for (int index = 0; index < styles.getLength(); index++) {
                var item = (Element) styles.item(index);
                if (!item.getAttributeNS(W, "styleId").equals(role)) { continue; }
                var fonts = (Element) item.getElementsByTagNameNS(W, "rFonts").item(0);
                assertEquals("Liberation Serif", fonts.getAttributeNS(W, "ascii"));
                found = true;
            }
            assertTrue(found, "The emitted semantic font role must have a Word family declaration");
        }
    }

    @Test void resourcesAreExplicitAndRequestImagesDoNotLeakIntoOtherRequests() throws Exception {
        var badge = badge();
        try (var exporter = Export4j.builder(latin()).image("sample:global", badge).build()) {
            var pdf = new ByteArrayOutputStream();
            exporter.markdown("![Badge](sample:global)").pdf(pdf);
            try (var document = Loader.loadPDF(pdf.toByteArray())) {
                assertTrue(document.getPage(0).getResources().getXObjectNames().iterator().hasNext());
            }
            String xml = """
                    <document version="1" content-format="MARKDOWN">
                      <content id="image"><![CDATA[![Badge](sample:global)]]></content>
                    </document>
                    """;
            var xmlWord = new ByteArrayOutputStream();
            exporter.xml(xml, Map.of()).docx(xmlWord);
            assertEquals(1, zip(xmlWord.toByteArray()).keySet().stream().filter(name -> name.startsWith("word/media/")).count());
            var xmlPdf = new ByteArrayOutputStream();
            exporter.xml(xml, Map.of()).pdf(xmlPdf);
            try (var document = Loader.loadPDF(xmlPdf.toByteArray())) {
                assertTrue(document.getPage(0).getResources().getXObjectNames().iterator().hasNext());
            }
            var word = new ByteArrayOutputStream();
            exporter.html("<p><img src='sample:local' alt='Badge'/></p>")
                    .image("sample:local", badge).docx(word);
            assertEquals(1, zip(word.toByteArray()).keySet().stream().filter(name -> name.startsWith("word/media/")).count());
            assertThrows(RuntimeException.class,
                    () -> exporter.markdown("![Missing](sample:local)").pdf(new ByteArrayOutputStream()));
            assertThrows(RuntimeException.class,
                    () -> exporter.html("<script>active()</script>").pdf(new ByteArrayOutputStream()));
        }
    }

    @Test void failureCancellationAndByteLimitsPreserveOldPathsAndRemoveTemporaryFiles() throws Exception {
        try (var exporter = Export4j.builder(latin()).build()) {
            Path target = directory.resolve("output.pdf");
            byte[] old = "old destination".getBytes(StandardCharsets.UTF_8);
            Files.write(target, old);
            assertThrows(MathException.class, () -> exporter.markdown("$\\unknown{x}$").pdf(target));
            assertArrayEquals(old, Files.readAllBytes(target));
            var cancelled = ExecutionOptions.builder().cancelled(() -> true).build();
            assertThrows(ExecutionException.class, () -> exporter.text("Cancelled").execution(cancelled).pdf(target));
            assertArrayEquals(old, Files.readAllBytes(target));
            var capped = ExecutionOptions.builder().maxOutputBytes(32).build();
            assertThrows(ExecutionException.class, () -> exporter.text("Capped").execution(capped).pdf(target));
            assertArrayEquals(old, Files.readAllBytes(target));
            assertThrows(ExecutionException.class, () -> exporter.text("Capped").execution(capped).docx(target));
            assertArrayEquals(old, Files.readAllBytes(target));
            try (var files = Files.list(directory)) { assertEquals(List.of(target), files.toList()); }
        }
    }

    @Test void cancellationFromFinalProgressPreservesTheOldDestination() throws Exception {
        Path target = directory.resolve("output.pdf");
        byte[] old = "original".getBytes(StandardCharsets.UTF_8);
        Files.write(target, old);
        var cancelled = new AtomicBoolean();
        var execution = ExecutionOptions.builder().cancelled(cancelled::get)
                .progress(progress -> { if (progress.stage().equals("COMPLETE")) { cancelled.set(true); } }).build();
        try (var exporter = Export4j.builder(latin()).build()) {
            assertEquals(ExecutionException.Code.CANCELLED, assertThrows(ExecutionException.class,
                    () -> exporter.text("New output").execution(execution).pdf(target)).code());
        }
        assertTrue(cancelled.get());
        assertArrayEquals(old, Files.readAllBytes(target));
        try (var files = Files.list(directory)) { assertEquals(List.of(target), files.toList()); }
    }

    @Test void suppliedStreamsRemainOpenOnSuccessAndFailure() throws Exception {
        class Borrowed extends ByteArrayOutputStream {
            boolean closed;
            @Override public void close() { closed = true; }
        }
        try (var exporter = Export4j.builder(latin()).build()) {
            var pdf = new Borrowed();
            exporter.text("Borrowed PDF").pdf(pdf);
            assertFalse(pdf.closed);
            var docx = new Borrowed();
            exporter.text("Borrowed DOCX").docx(docx);
            assertFalse(docx.closed);
            var failed = new Borrowed();
            assertThrows(ExecutionException.class, () -> exporter.text("Limited")
                    .execution(ExecutionOptions.builder().maxOutputBytes(16).build()).pdf(failed));
            assertFalse(failed.closed);
            assertTrue(pdfText(pdf.toByteArray()).contains("Borrowed PDF"));
            assertTrue(zip(docx.toByteArray()).containsKey("word/document.xml"));
        }
    }

    @Test void xmlRegionsSectionsAndNotesFailRatherThanBeingDiscarded() throws Exception {
        var bodies = List.of(
                "<header height='30'><p>Header</p></header><p>Body</p>",
                "<section id='one'><p>Section</p></section>",
                "<notes><note id='n1' paragraph='body' kind='FOOTNOTE'>Note</note></notes><p id='body'>Body</p>");
        try (var exporter = Export4j.builder(latin()).build()) {
            for (String body : bodies) {
                var request = exporter.xml("<document version='1'>" + body + "</document>", Map.of());
                assertEquals(DocumentTemplate.BindingException.Code.UNSUPPORTED_BLOCK,
                        assertThrows(DocumentTemplate.BindingException.class, () -> request.pdf(new ByteArrayOutputStream())).code());
                assertEquals(DocumentTemplate.BindingException.Code.UNSUPPORTED_BLOCK,
                        assertThrows(DocumentTemplate.BindingException.class, () -> request.docx(new ByteArrayOutputStream())).code());
            }
        }
    }

    @Test void plainTextDoesNotRequireSubsetPermissionFromTheOptionalFormulaTextPath() throws Exception {
        byte[] noSubset = fontBytes("LiberationSans-Regular.ttf");
        var sfnt = ByteBuffer.wrap(noSubset);
        boolean patched = false;
        for (int index = 0, count = Short.toUnsignedInt(sfnt.getShort(4)); index < count; index++) {
            int entry = 12 + index * 16;
            if (new String(noSubset, entry, 4, StandardCharsets.US_ASCII).equals("OS/2")) {
                int flags = sfnt.getInt(entry + 8) + 8;
                noSubset[flags] = 1; noSubset[flags + 1] = 0;
                patched = true;
                break;
            }
        }
        assertTrue(patched);
        try (var exporter = Export4j.builder(FontFamily.of("Full embedding", FontSource.bytes(noSubset))).build()) {
            var pdf = new ByteArrayOutputStream();
            exporter.text("Plain content").pdf(pdf);
            assertTrue(pdfText(pdf.toByteArray()).contains("Plain content"));
            var docx = new ByteArrayOutputStream();
            exporter.text("Plain content").docx(docx);
            assertTrue(zip(docx.toByteArray()).containsKey("word/document.xml"));
            assertThrows(MathException.class, () -> exporter.markdown("$\\text{Body}$").pdf(new ByteArrayOutputStream()));
        }
    }

    @Test @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void concurrentRequestsDoNotShareMutableCompilationOrOutputState() throws Exception {
        try (var exporter = Export4j.builder(latin()).build(); var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Callable<byte[]>>();
            for (int index = 0; index < 8; index++) {
                final int item = index;
                tasks.add(() -> {
                    var output = new ByteArrayOutputStream();
                    var request = exporter.markdown("Item " + item + " $x^2$.");
                    var result = item % 2 == 0 ? request.pdf(output) : request.docx(output);
                    assertEquals(1, result.formulas());
                    assertTrue(result.degradations().isEmpty());
                    return output.toByteArray();
                });
            }
            var futures = executor.invokeAll(tasks, 30, TimeUnit.SECONDS);
            for (int index = 0; index < futures.size(); index++) {
                byte[] output = futures.get(index).get(1, TimeUnit.SECONDS);
                if (index % 2 == 0) { assertTrue(pdfText(output).contains("Item " + index)); }
                else {
                    var xml = documentXml(output);
                    assertEquals(1, xml.getElementsByTagNameNS(M, "oMath").getLength());
                    assertTrue(xml.getDocumentElement().getTextContent().contains("Item " + index));
                }
            }
        }
    }

    private static FontFamily latin() {
        return FontFamily.builder("Liberation Sans", source("LiberationSans-Regular.ttf"))
                .bold(source("LiberationSans-Bold.ttf")).italic(source("LiberationSans-Italic.ttf"))
                .boldItalic(source("LiberationSans-BoldItalic.ttf")).build();
    }

    private static FontSource source(String name) { return FontSource.resource(Export4jTest.class, "/fonts/" + name); }

    private static byte[] fontBytes(String name) throws IOException {
        try (var input = Export4jTest.class.getResourceAsStream("/fonts/" + name)) {
            if (input == null) { throw new IOException("Missing test font"); }
            return input.readAllBytes();
        }
    }

    private static String pdfText(byte[] bytes) throws IOException {
        try (var document = Loader.loadPDF(bytes)) {
            assertEquals(1, document.getNumberOfPages());
            return new PDFTextStripper().getText(document);
        }
    }

    private static String pdfFontName(byte[] bytes) throws IOException {
        try (var document = Loader.loadPDF(bytes)) {
            var resources = document.getPage(0).getResources();
            return resources.getFont(resources.getFontNames().iterator().next()).getName();
        }
    }

    private static Map<String, byte[]> zip(byte[] bytes) throws IOException {
        var result = new HashMap<String, byte[]>();
        try (var input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                result.put(entry.getName(), input.readAllBytes());
            }
        }
        return result;
    }

    private static org.w3c.dom.Document documentXml(byte[] bytes) throws Exception {
        return partXml(bytes, "word/document.xml");
    }

    private static org.w3c.dom.Document partXml(byte[] bytes, String part) throws Exception {
        byte[] xml = zip(bytes).get(part);
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static void assertRun(byte[] docx, String text, String rgb, String size) throws Exception {
        var run = run(docx, text);
        var color = (Element) run.getElementsByTagNameNS(W, "color").item(0);
        var fontSize = (Element) run.getElementsByTagNameNS(W, "sz").item(0);
        assertEquals(rgb, color.getAttributeNS(W, "val"));
        assertEquals(size, fontSize.getAttributeNS(W, "val"));
    }

    private static Element run(byte[] docx, String text) throws Exception {
        var runs = documentXml(docx).getElementsByTagNameNS(W, "r");
        for (int index = 0; index < runs.getLength(); index++) {
            var run = (Element) runs.item(index);
            if (!run.getTextContent().equals(text)) { continue; }
            return run;
        }
        throw new AssertionError("Expected text run is absent: " + text);
    }

    private static InlineImage badge() throws IOException {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream();
        try {
            image.setRGB(0, 0, 0x224466);
            assertTrue(ImageIO.write(image, "png", output));
        } finally { image.flush(); }
        return InlineImage.atWidth(ImageResource.load(output.toByteArray()), 12, "Badge");
    }
}
