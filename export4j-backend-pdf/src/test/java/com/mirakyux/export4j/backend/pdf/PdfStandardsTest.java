// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mirakyux.export4j.graphics.AccessibilityTree;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;

/** Prevents false DocMDP approval after visible edits and silent loss of explicitly requested tagging. */
class PdfStandardsTest {
    @TempDir Path directory;
    @Test void certificationComparesEffectivePageContentAndMetadataDespiteValidHistoricalBytes() throws Exception {
        for (var permission : PdfSigner.CertificationPermission.values()) {
            Path certified = certified(permission), edited = directory.resolve(permission + "-page.pdf");
            try (var pdf = Loader.loadPDF(certified.toFile())) {
                try (var stream = new PDPageContentStream(pdf, pdf.getPage(0), PDPageContentStream.AppendMode.APPEND, true, true)) { stream.addRect(50, 50, 40, 40); stream.fill(); }
                pdf.getPage(0).getCOSObject().setNeedToBeUpdated(true); try (var output = Files.newOutputStream(edited)) { pdf.saveIncremental(output); }
            }
            var report = PdfDocMdp.verify(edited, PdfSignatureVerifier.Limits.DEFAULT);
            assertEquals(PdfDocMdp.Status.FORBIDDEN, report.status()); assertTrue(report.changes().contains("PAGE_CONTENT_OR_RESOURCES_CHANGED"));
        }
    }
    @Test void formFillingAndAnnotationPermissionsAreClassifiedIndependently() throws Exception {
        for (var permission : PdfSigner.CertificationPermission.values()) {
            Path certified = certified(permission), filled = directory.resolve(permission + "-filled.pdf"), annotated = directory.resolve(permission + "-annotated.pdf");
            try (var pdf = Loader.loadPDF(certified.toFile())) {
                var field = pdf.getDocumentCatalog().getAcroForm().getField("name"); field.setValue("Updated"); field.getCOSObject().setNeedToBeUpdated(true);
                try (var output = Files.newOutputStream(filled)) { pdf.saveIncremental(output); }
            }
            var report = PdfDocMdp.verify(filled, PdfSignatureVerifier.Limits.DEFAULT);
            assertEquals(permission == PdfSigner.CertificationPermission.NO_CHANGES ? PdfDocMdp.Status.FORBIDDEN : PdfDocMdp.Status.PERMITTED, report.status(), report.toString());
            assertTrue(report.changes().contains("FIELD_VALUES_OR_APPEARANCES_CHANGED"));
            try (var pdf = Loader.loadPDF(certified.toFile())) {
                var comment = new PDAnnotationText(); comment.setRectangle(new PDRectangle(60, 60, 20, 20)); comment.setContents("Added annotation"); pdf.getPage(0).getAnnotations().add(comment); pdf.getPage(0).getCOSObject().setNeedToBeUpdated(true);
                try (var output = Files.newOutputStream(annotated)) { pdf.saveIncremental(output); }
            }
            assertEquals(permission == PdfSigner.CertificationPermission.ANNOTATIONS_FORM_FILLING_AND_SIGNING ? PdfDocMdp.Status.PERMITTED : PdfDocMdp.Status.FORBIDDEN, PdfDocMdp.verify(annotated, PdfSignatureVerifier.Limits.DEFAULT).status());
        }
    }
    @Test void unsignedTailCannotMasqueradeAsPermittedChanges() throws Exception {
        Path certified = certified(PdfSigner.CertificationPermission.FORM_FILLING_AND_SIGNING); var bytes = Files.readAllBytes(certified);
        Path tail = directory.resolve("tail.pdf"); var output = new ByteArrayOutputStream(); output.write(bytes); output.write("\n% unsigned data\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); Files.write(tail, output.toByteArray());
        assertEquals(PdfDocMdp.Status.INDETERMINATE, PdfDocMdp.verify(tail, PdfSignatureVerifier.Limits.DEFAULT).status());
    }
    @Test void archivalEditionRejectsForbiddenTransparencyAndMissingRequiredAssociatedFiles() throws Exception {
        com.mirakyux.export4j.font.FontData font;
        try (var input = getClass().getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) { font = com.mirakyux.export4j.font.TrueTypeFontLoader.load(input, com.mirakyux.export4j.font.TrueTypeFontLoader.DEFAULT_MAX_BYTES).data(); }
        byte[] icc = java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB).getData();
        assertThrows(IllegalArgumentException.class, () -> new PdfArchival(PdfArchival.Profile.A4F, icc, font));
        var archival = new PdfArchival(PdfArchival.Profile.A1B, icc, font);
        var page = new DisplayListBuilder(PageSize.A4).drawImage(new com.mirakyux.export4j.graphics.RgbImage(1, 1, new byte[] {20, 30, 40}, new byte[] {100}), new Rect(10, 10, 20, 20)).build();
        var output = new ByteArrayOutputStream();
        assertThrows(PdfRenderException.class, () -> new PdfBackend(PdfLimits.DEFAULT, PdfOptions.DEFAULT.withArchival(archival)).write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(page)), output));
        assertEquals(0, output.size(), "forbidden A1 transparency wrote a partial result");
    }
    @Test void explicitTagTreeCannotDisappearAndMissingPaintBindingsFailAtConstruction() throws Exception {
        var page = new DisplayListBuilder(PageSize.A4).fillRect(new Rect(10, 10, 20, 20), RgbColor.BLACK).build();
        var node = new AccessibilityTree.Node("figure", AccessibilityTree.Role.FIGURE, "Black square", "", AccessibilityTree.Cell.NONE,
                List.of(new AccessibilityTree.CommandRange(0, 0, 1)), List.of());
        var tree = new AccessibilityTree("en-US", List.of(node), List.of()); var document = new DisplayDocument(new DocumentMetadata("Accessible", ""), List.of(page), DocumentExtras.EMPTY, tree);
        var output = new ByteArrayOutputStream(); assertThrows(PdfRenderException.class, () -> new PdfBackend().write(document, output)); assertEquals(0, output.size());
        assertThrows(IllegalArgumentException.class, () -> new DisplayDocument(document.metadata(), List.of(page), DocumentExtras.EMPTY,
                new AccessibilityTree("en-US", List.of(new AccessibilityTree.Node("empty", AccessibilityTree.Role.P, List.of(), List.of())), List.of())));
        var target = new PdfBackend(PdfLimits.DEFAULT, PdfOptions.DEFAULT.withAccessibility(PdfAccessibility.PDF_UA_1));
        assertThrows(PdfRenderException.class, () -> target.write(new DisplayDocument(document.metadata(), List.of(page)), output)); assertEquals(0, output.size());
        assertThrows(IOException.class, () -> target.open(document.metadata(), output));
    }
    private Path certified(PdfSigner.CertificationPermission permission) throws Exception {
        Path source = directory.resolve("source-" + permission + ".pdf"), target = directory.resolve("certified-" + permission + ".pdf");
        var page = new DisplayListBuilder(PageSize.A4).fillRect(new Rect(10, 10, 20, 20), RgbColor.BLACK).build();
        var extras = new DocumentExtras(List.of(), List.of(), List.of(), List.of(new DocumentExtras.TextField(0, new Rect(40, 100, 120, 24), "name", "Original", false)), List.of());
        try (var output = Files.newOutputStream(source)) { new PdfBackend().write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(page), extras), output); }
        PdfSigner.certify(source, target, new PdfSigner.Options("Fixture", "Structure comparison", Instant.parse("2026-01-01T00:00:00Z"), 4096), permission, stream -> { stream.transferTo(java.io.OutputStream.nullOutputStream()); return new byte[] {0x30, 3, 2, 1, 1}; });
        assertEquals(PdfDocMdp.Status.PERMITTED, PdfDocMdp.verify(target, PdfSignatureVerifier.Limits.DEFAULT).status()); return target;
    }
}
