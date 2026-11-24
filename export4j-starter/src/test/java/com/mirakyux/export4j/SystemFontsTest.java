// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j;

import com.mirakyux.export4j.font.FontSource;
import com.mirakyux.export4j.math.MathContent;
import com.mirakyux.export4j.model.ContentOptions;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemFontsTest {
    @TempDir Path fonts;

    @Test void usesInstalledCandidatesAndTheirPhysicalVariants() throws Exception {
        Files.writeString(fonts.resolve("arial.ttf"), "not a supported font");
        copy("LiberationSans-Regular.ttf", "segoeui.ttf");
        copy("LiberationSans-Bold.ttf", "segoeuib.ttf");
        copy("LXGWWenKaiTC-Regular.ttf", "simhei.ttf");
        var pdf = new ByteArrayOutputStream();
        try (var exporter = Export4j.systemBuilder(SystemFonts.windows(fonts)).build()) {
            exporter.markdown("**Bold** 中文").pdf(pdf);
        }
        try (var document = Loader.loadPDF(pdf.toByteArray())) {
            assertEquals("Bold 中文", new PDFTextStripper().getText(document).strip());
            var resources = document.getPage(0).getResources();
            for (var key : resources.getFontNames()) { assertTrue(resources.getFont(key).isEmbedded()); }
        }
    }

    @Test void missingCandidatesPreserveExistingOutput() throws Exception {
        Path target = Files.writeString(fonts.resolve("report.pdf"), "old target");
        try (var exporter = Export4j.systemBuilder(SystemFonts.windows(fonts)).build()) {
            var failure = assertThrows(IOException.class, () -> exporter.text("Report").pdf(target));
            assertTrue(failure.getMessage().contains("Export4j.builder(FontFamily)"));
        }
        assertEquals("old target", Files.readString(target));
        try (var entries = Files.list(fonts)) { assertEquals(1, entries.count()); }
    }

    @Test void docxDeclaresDefaultFamiliesAndNativeStylesWithNoInstalledFonts() throws Exception {
        try (var exporter = Export4j.systemBuilder(SystemFonts.linux(fonts)).build()) {
            var output = new ByteArrayOutputStream();
            assertEquals(1, exporter.markdown("**Report** $x^2$").docx(output).formulas());
            String document = part(output, "word/document.xml");
            assertTrue(document.contains(">Report<"));
            assertTrue(document.contains("<m:oMath>") || document.contains("<m:oMath "), document);
            String styles = part(output, "word/styles.xml");
            assertTrue(styles.contains("w:ascii=\"Liberation Sans\""));
            assertTrue(styles.contains("w:eastAsia=\"Noto Sans CJK SC\""));
            assertTrue(styles.contains("w:styleId=\"bold\""));
            assertEquals(0, exporter.cacheStats().loads());
        }
    }

    @Test void docxOpensOnlyTheRegularPhysicalSourcesWhenFormulaTextNeedsThem() throws Exception {
        var opens = new AtomicInteger();
        FontSource unavailable = cache -> {
            opens.incrementAndGet();
            throw new IOException("font is unavailable");
        };
        var body = FontFamily.of("Report Latin", unavailable);
        var cjk = FontFamily.of("Report Chinese", unavailable);
        var complex = FontFamily.of("Report Complex", unavailable);
        var accent = FontFamily.builder("Report Accent", unavailable).bold(unavailable).build();
        try (var exporter = Export4j.builder(body).eastAsia(cjk).complexScript(complex).fontRole("accent", accent)
                .formulaFallbacks(List.of(MathContent.Fallback.TEXT)).build()) {
            var output = new ByteArrayOutputStream();
            exporter.html("<p style='font-family: accent'><strong>Report</strong></p>").docx(output);
            String styles = part(output, "word/styles.xml");
            assertTrue(styles.contains("w:ascii=\"Report Latin\""));
            assertTrue(styles.contains("w:eastAsia=\"Report Chinese\""));
            assertTrue(styles.contains("w:cs=\"Report Complex\""));
            assertTrue(styles.contains("w:styleId=\"accent_bold\""));
            assertTrue(styles.contains("w:ascii=\"Report Accent\""));
            assertEquals(0, opens.get());

            Path target = Files.writeString(fonts.resolve("old.docx"), "previous output");
            var failure = assertThrows(IOException.class, () -> exporter.markdown("$\\text{Report}$").docx(target));
            assertEquals("font is unavailable", failure.getMessage());
            assertEquals("previous output", Files.readString(target));
            assertEquals(1, opens.get());
        }
    }

    @Test void selectsKnownMacAndLinuxDirectories() throws Exception {
        var mac = fonts.resolve("mac");
        var linux = fonts.resolve("linux");
        copy("LiberationSans-Regular.ttf", "mac/Supplemental/Arial.ttf");
        copy("LiberationSans-Regular.ttf", "linux/truetype/liberation2/LiberationSans-Regular.ttf");
        try (var macExporter = Export4j.systemBuilder(SystemFonts.mac(mac)).build();
                var linuxExporter = Export4j.systemBuilder(SystemFonts.linux(linux)).build()) {
            for (var exporter : new Export4j[] {macExporter, linuxExporter}) {
                var pdf = new ByteArrayOutputStream();
                exporter.text("Report").pdf(pdf);
                try (var document = Loader.loadPDF(pdf.toByteArray())) {
                    assertEquals("Report", new PDFTextStripper().getText(document).strip());
                }
            }
        }
    }

    @Test void undeclaredRoleIdentifiesItsSetting() throws Exception {
        copy("LiberationSans-Regular.ttf", "arial.ttf");
        try (var exporter = Export4j.systemBuilder(SystemFonts.windows(fonts)).build()) {
            var failure = assertThrows(IllegalArgumentException.class, () -> exporter.markdown("Report")
                    .options(ContentOptions.builder().fontRole("report_body").build()).pdf(new ByteArrayOutputStream()));
            assertEquals("undeclared font role 'report_body' for body", failure.getMessage());
        }
    }

    private void copy(String resource, String name) throws IOException {
        try (var input = SystemFontsTest.class.getResourceAsStream("/fonts/" + resource)) {
            Path target = fonts.resolve(name);
            Files.createDirectories(target.getParent());
            Files.copy(input, target);
        }
    }

    private static String part(ByteArrayOutputStream docx, String name) throws IOException {
        try (var parts = new ZipInputStream(new ByteArrayInputStream(docx.toByteArray()))) {
            for (var part = parts.getNextEntry(); part != null; part = parts.getNextEntry()) {
                if (name.equals(part.getName())) { return new String(parts.readAllBytes(), StandardCharsets.UTF_8); }
            }
        }
        throw new AssertionError("missing DOCX part " + name);
    }
}
