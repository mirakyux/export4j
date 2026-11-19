// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.pdf.PdfEncryption;
import com.mirakyux.export4j.backend.pdf.PdfFontCache;
import com.mirakyux.export4j.backend.pdf.PdfLimits;
import com.mirakyux.export4j.backend.pdf.PdfOptions;
import com.mirakyux.export4j.backend.pdf.PdfRenderException;
import com.mirakyux.export4j.adapter.web.ImportLimits;
import com.mirakyux.export4j.font.FontSource;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.DocumentTheme;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.w3c.dom.Element;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Export4jConfigurationTest {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    @Test void stableBusinessRolesProjectTheSameFamilyAcrossJavaXmlHtmlPdfAndWord() throws Exception {
        var serif = FontFamily.builder("Liberation Serif", source("LiberationSerif-Regular.ttf"))
                .bold(source("LiberationSerif-Bold.ttf")).build();
        try (var exporter = Export4j.builder(latin()).fontRole("report", serif).build()) {
            for (int index = 0; index < 3; index++) {
                var request = switch (index) {
                    case 0 -> exporter.text("Body").options(ContentOptions.builder().fontRole("report").build());
                    case 1 -> exporter.xml("<document version='1'><p><span role='report'>Body</span></p></document>", Map.of());
                    default -> exporter.html("<p style=\"font-family:report\">Body</p>");
                };
                var pdf = new ByteArrayOutputStream(); request.pdf(pdf);
                assertTrue(pdfFontNames(pdf.toByteArray()).stream().anyMatch(name -> name.contains("LiberationSerif")));
                var docx = new ByteArrayOutputStream(); request.docx(docx);
                assertRole(docx.toByteArray(), "report", "Liberation Serif", false);
            }
            var bold = new ByteArrayOutputStream();
            exporter.xml("<document version='1'><p><span role='report_bold'>Body</span></p></document>", Map.of()).docx(bold);
            assertRole(bold.toByteArray(), "report_bold", "Liberation Serif", true);
        }
    }

    @Test void roleAliasesDoNotAddImplicitFallbacksOrSynthesizeMissingVariants() throws Exception {
        var chinese = FontFamily.of("LXGW WenKai TC", source("LXGWWenKaiTC-Regular.ttf"));
        try (var exporter = Export4j.builder(latin()).fontRole("chinese", chinese).build()) {
            assertThrows(DocumentLayoutException.class, () -> exporter.text("中文").pdf(new ByteArrayOutputStream()));
            var output = new ByteArrayOutputStream();
            exporter.text("中文").options(ContentOptions.builder().fontRole("chinese").build()).pdf(output);
            assertTrue(pdfText(output.toByteArray()).contains("中文"));
            for (boolean pdf : List.of(true, false)) {
                var request = exporter.xml("<document version='1'><p><span role='chinese_bold'>Body</span></p></document>", Map.of());
                assertThrows(RuntimeException.class, () -> {
                    if (pdf) { request.pdf(new ByteArrayOutputStream()); } else { request.docx(new ByteArrayOutputStream()); }
                });
            }
        }
    }

    @Test void namedRolesRejectDefaultGeneratedCaseAndFamilyAliasConflicts() {
        for (String role : List.of("bold", "BoldItalic", "family_0", "family_custom")) {
            assertThrows(IllegalArgumentException.class, () -> Export4j.builder(latin()).fontRole(role, latin()).build());
        }
        var family = latin();
        assertThrows(IllegalArgumentException.class, () -> Export4j.builder(family).fontRole("report", family).fontRole("report_bold", family).build());
        assertThrows(IllegalArgumentException.class, () -> Export4j.builder(family).fontRole("report", family).fontRole("REPORT", family).build());
        var body = FontFamily.of("Body", source("LiberationSerif-Regular.ttf"));
        assertThrows(IllegalArgumentException.class, () -> Export4j.builder(family).fallbackFont(body).fontRole("Body", family).build());
    }

    @Test void customPdfOptionsLimitsAndBorrowedCacheAreUsedByTheActualBackend() throws Exception {
        try (var cache = PdfFontCache.builder().build()) {
            var protection = PdfEncryption.builder().ownerPassword("test-owner".toCharArray()).userPassword("test-user".toCharArray()).build();
            var backend = PdfBackend.builder().fontCache(cache).options(PdfOptions.storage(PdfOptions.Storage.TEMP_FILE).withEncryption(protection)).build();
            try (var exporter = Export4j.builder(latin()).pdfBackend(backend).build()) {
                for (int index = 0; index < 2; index++) {
                    var output = new ByteArrayOutputStream(); exporter.text("Protected").pdf(output);
                    try (var pdf = Loader.loadPDF(output.toByteArray(), "test-user")) { assertTrue(pdf.isEncrypted()); }
                }
                assertTrue(cache.stats().hits() >= 1);
            }
            assertFalse(cache.stats().closed());
            var restricted = PdfBackend.builder().fontCache(cache).limits(PdfLimits.builder().maxTextCodePoints(1).build()).build();
            try (var exporter = Export4j.builder(latin()).pdfBackend(restricted).build()) {
                assertThrows(PdfRenderException.class, () -> exporter.text("Over budget").pdf(new ByteArrayOutputStream()));
                exporter.text("Word uses its own output contract").docx(new ByteArrayOutputStream());
            }
        }
    }

    @Test void syntaxPreparationLoadsNoFontsAndRecompilesUnderEachCurrentConfiguration() throws Exception {
        var calls = new AtomicInteger();
        FontSource physical = cache -> { calls.incrementAndGet(); return source("LiberationSans-Regular.ttf").load(cache); };
        var family = FontFamily.of("Liberation Sans", physical);
        var builder = Export4j.builder(family).fontRole("body", family).fontRole("copy", family);
        var syntaxSource = "<document version='1'><p>${name}</p><content id='math' source='math'/></document>";
        try (var first = builder.build()) {
            var syntax = first.prepareXml(syntaxSource);
            assertEquals(0, calls.get());
            var data = new HashMap<String, Object>(); data.put("name", "First"); data.put("math", "$x^2$");
            var request = first.xml(syntax, data).options(ContentOptions.builder().format(ContentOptions.Format.MARKDOWN).build());
            var output = new ByteArrayOutputStream(); assertEquals(1, request.pdf(output).formulas());
            assertTrue(pdfText(output.toByteArray()).contains("First")); assertEquals(1, calls.get());
            data.put("name", "Second");
            output.reset(); request.docx(output); assertTrue(part(output.toByteArray(), "word/document.xml").getDocumentElement().getTextContent().contains("Second"));
            assertEquals(2, calls.get());
            try (var second = Export4j.builder(family).theme(DocumentTheme.builder().bodyFormat(p -> p.fontSize(17).lineHeight(25)).build()).build()) {
                output.reset(); second.xml(syntax, data).docx(output);
                var sizes = part(output.toByteArray(), "word/document.xml").getElementsByTagNameNS(W, "sz");
                assertEquals("34", ((Element) sizes.item(0)).getAttributeNS(W, "val"));
            }
        }
    }

    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void preparedSyntaxReusesOnlyStaticWorkAcrossConcurrentTargets() throws Exception {
        try (var exporter = Export4j.builder(latin()).build(); var executor = Executors.newFixedThreadPool(2)) {
            var syntax = exporter.prepareXml("<document version='1' content-format='MARKDOWN'><content id='body' source='body'/></document>");
            List<Callable<Integer>> tasks = List.of(
                    () -> exporter.xml(syntax, Map.of("body", "One $x^2$")).pdf(new ByteArrayOutputStream()).formulas(),
                    () -> exporter.xml(syntax, Map.of("body", "Two $x^2$ $y$")).docx(new ByteArrayOutputStream()).formulas());
            var futures = executor.invokeAll(tasks, 20, TimeUnit.SECONDS);
            assertEquals(1, futures.getFirst().get(1, TimeUnit.SECONDS));
            assertEquals(2, futures.getLast().get(1, TimeUnit.SECONDS));
        }
    }

    @Test void unknownSourceRolesCannotBeHiddenByEmphasisInEitherTargetOrXmlScopes() throws Exception {
        var family = FontFamily.builder("Liberation Sans", source("LiberationSans-Regular.ttf"))
                .bold(source("LiberationSans-Bold.ttf")).italic(source("LiberationSans-Italic.ttf"))
                .boldItalic(source("LiberationSans-BoldItalic.ttf")).build();
        try (var exporter = Export4j.builder(family).build()) {
            for (boolean pdf : List.of(true, false)) {
                for (int index = 0; index < 3; index++) {
                    var request = switch (index) {
                        case 0 -> exporter.markdown("**Hidden**").options(ContentOptions.builder().fontRole("missing").build());
                        case 1 -> exporter.html("<p><strong>Hidden</strong></p>").options(ContentOptions.builder().fontRole("missing").build());
                        default -> exporter.xml("<document version='1' content-format='MARKDOWN'><scope role='missing'><content id='body'>**Hidden**</content></scope></document>", Map.of());
                    };
                    var output = new ByteArrayOutputStream();
                    assertThrows(IllegalArgumentException.class, () -> {
                        if (pdf) { request.pdf(output); } else { request.docx(output); }
                    });
                    assertEquals(0, output.size());
                }
            }
            exporter.markdown("**Declared**").pdf(new ByteArrayOutputStream());
            exporter.markdown("**Declared**").docx(new ByteArrayOutputStream());
        }
    }

    @Test void richSourcesValidateTheirWholeRoleConfigurationWhileLiteralTextKeepsUnusedDomainsLazy() throws Exception {
        var theme = DocumentTheme.builder().headingFontRole(6, "missing_heading").build();
        try (var exporter = Export4j.builder(latin()).theme(theme).build()) {
            exporter.text("Literal").pdf(new ByteArrayOutputStream());
            exporter.xml("<document version='1'><p>Native XML</p></document>", Map.of()).docx(new ByteArrayOutputStream());
            assertThrows(IllegalArgumentException.class, () -> exporter.markdown("Body").pdf(new ByteArrayOutputStream()));
            assertThrows(IllegalArgumentException.class, () -> exporter.html("<p>Body</p>").docx(new ByteArrayOutputStream()));
            exporter.markdown("Body").options(ContentOptions.builder().fontRole("").build()).pdf(new ByteArrayOutputStream());
        }
    }

    @Test void checkedXmlParserLeasesKeepOneCumulativeBudgetAndAllowTheNextOperation() throws Exception {
        var defaults = ImportLimits.DEFAULT;
        var limits = new ImportLimits(10, defaults.maxLineUtf16(), defaults.maxNodes(), defaults.maxDepth(),
                defaults.maxTextUtf16(), defaults.maxTableSlots(), defaults.maxIssues(), defaults.maxWork());
        try (var exporter = Export4j.builder(latin()).importLimits(limits).build()) {
            for (boolean pdf : List.of(true, false)) {
                var request = exporter.xml("<document version='1'><content id='first' source='first'/><content id='second' source='second'/></document>",
                        Map.of("first", "123456", "second", "abcdef"));
                assertThrows(IllegalArgumentException.class, () -> {
                    if (pdf) { request.pdf(new ByteArrayOutputStream()); } else { request.docx(new ByteArrayOutputStream()); }
                });
                exporter.xml("<document version='1'><content id='first' source='first'/></document>", Map.of("first", "123456"))
                        .pdf(new ByteArrayOutputStream());
            }
        }
    }

    private static FontFamily latin() { return FontFamily.of("Liberation Sans", source("LiberationSans-Regular.ttf")); }
    private static FontSource source(String name) { return FontSource.resource(Export4jConfigurationTest.class, "/fonts/" + name); }
    private static String pdfText(byte[] bytes) throws Exception {
        try (var pdf = Loader.loadPDF(bytes)) { return new PDFTextStripper().getText(pdf); }
    }
    private static List<String> pdfFontNames(byte[] bytes) throws Exception {
        try (var pdf = Loader.loadPDF(bytes)) {
            var result = new java.util.ArrayList<String>(); var resources = pdf.getPage(0).getResources();
            for (var key : resources.getFontNames()) { result.add(resources.getFont(key).getName()); }
            return List.copyOf(result);
        }
    }
    private static org.w3c.dom.Document part(byte[] bytes, String name) throws Exception {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.getName().equals(name)) { continue; }
                var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                return factory.newDocumentBuilder().parse(new ByteArrayInputStream(zip.readAllBytes()));
            }
        }
        throw new AssertionError("Missing output part");
    }
    private static void assertRole(byte[] docx, String role, String family, boolean bold) throws Exception {
        var roles = part(docx, "word/document.xml").getElementsByTagNameNS(W, "rStyle");
        assertTrue(java.util.stream.IntStream.range(0, roles.getLength()).anyMatch(index -> role.equals(((Element) roles.item(index)).getAttributeNS(W, "val"))));
        var styles = part(docx, "word/styles.xml").getElementsByTagNameNS(W, "style");
        for (int index = 0; index < styles.getLength(); index++) {
            var style = (Element) styles.item(index);
            if (!role.equals(style.getAttributeNS(W, "styleId"))) { continue; }
            assertEquals(family, ((Element) style.getElementsByTagNameNS(W, "rFonts").item(0)).getAttributeNS(W, "ascii"));
            assertEquals(bold ? 1 : 0, style.getElementsByTagNameNS(W, "b").getLength()); return;
        }
        throw new AssertionError("Missing business font role");
    }
}
