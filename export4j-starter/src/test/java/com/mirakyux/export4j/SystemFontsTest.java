// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j;

import com.mirakyux.export4j.model.ContentOptions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
