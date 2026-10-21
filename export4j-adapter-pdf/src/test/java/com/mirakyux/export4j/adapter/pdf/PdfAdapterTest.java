// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfAdapterTest {
    @TempDir Path directory;

    @Test void readsObjectStreamsAndPhysicalGeometry() throws IOException {
        var adapter = new PdfAdapter();
        var input = adapter.input(pages("FIRST", "SECOND"), "");
        var result = adapter.read(input);
        assertEquals(2, result.pages().size());
        assertEquals("FIRST", result.pages().getFirst().text().strip());
        assertEquals("SECOND", result.pages().get(1).text().strip());
        assertEquals(240, result.pages().getFirst().mediaBox().width());
        assertEquals(241, result.pages().get(1).mediaBox().width());
        assertFalse(result.encrypted());
        assertThrows(UnsupportedOperationException.class, () -> result.pages().clear());
    }

    @Test void encodedInputAndSelectionTakeDefensiveSnapshots() throws IOException {
        var bytes = pages("SNAPSHOT");
        var adapter = new PdfAdapter();
        var input = adapter.input(bytes, "");
        Arrays.fill(bytes, (byte) 0);
        int[] order = {0};
        var selection = input.pages(order);
        order[0] = 100;
        assertEquals(List.of(0), selection.indices());
        assertEquals("SNAPSHOT", adapter.read(input).pages().getFirst().text().strip());
        assertThrows(UnsupportedOperationException.class, () -> selection.indices().clear());
        assertThrows(IllegalArgumentException.class, input::pages);
        assertThrows(IllegalArgumentException.class, () -> input.pages(-1));
    }

    @Test void copiesOrderedAndDeliberatelyRepeatedPages() throws IOException {
        var adapter = new PdfAdapter();
        var input = adapter.input(pages("FIRST", "SECOND", "THIRD"), "");
        var output = new BorrowedOutput();
        var result = adapter.copy(input.pages(2, 0, 2), output);
        assertEquals(3, result.pageCount());
        assertEquals(output.size(), result.outputBytes());
        assertFalse(output.closed);
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            assertEquals(3, pdf.getNumberOfPages());
            assertEquals(242, pdf.getPage(0).getMediaBox().getWidth());
            assertEquals(242, pdf.getPage(2).getMediaBox().getWidth());
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.indexOf("THIRD") < text.indexOf("FIRST"));
            assertEquals(2, text.split("THIRD", -1).length - 1);
        }
    }

    @Test void mergesIndependentDocumentsWithoutClosingBorrowedOutput() throws IOException {
        var adapter = new PdfAdapter();
        var first = adapter.input(pages("ALPHA", "BETA"), "");
        var second = adapter.input(pages("GAMMA"), "");
        var output = new BorrowedOutput();
        adapter.merge(List.of(first.pages(1), second.allPages(), first.pages(0)), output);
        var result = adapter.read(adapter.input(output.toByteArray(), ""));
        assertEquals(List.of("BETA", "GAMMA", "ALPHA"), result.pages().stream().map(page -> page.text().strip()).toList());
        assertFalse(output.closed);
    }

    @Test void composedDocumentKeepsHighestSourcePdfVersion() throws IOException {
        byte[] source;
        try (var pdf = Loader.loadPDF(pages("VERSION"))) {
            pdf.setVersion(2);
            source = saved(pdf);
        }
        var adapter = new PdfAdapter();
        var output = new ByteArrayOutputStream();
        adapter.merge(List.of(adapter.input(pages("OLD"), "").allPages(), adapter.input(source, "").allPages()), output);
        try (var pdf = Loader.loadPDF(output.toByteArray())) { assertEquals(2, pdf.getVersion()); }
    }

    @Test void inheritedResourcesBoxesRotationAndUserUnitSurviveDetachedCopy() throws IOException {
        byte[] source;
        try (var pdf = Loader.loadPDF(pages("INHERITED"))) {
            var page = pdf.getPage(0);
            var parent = pdf.getPages().getCOSObject();
            parent.setItem(COSName.RESOURCES, page.getResources());
            parent.setItem(COSName.MEDIA_BOX, new PDRectangle(10, 20, 240, 300));
            parent.setItem(COSName.CROP_BOX, new PDRectangle(20, 30, 200, 250));
            parent.setInt(COSName.ROTATE, 90);
            page.getCOSObject().removeItem(COSName.RESOURCES);
            page.getCOSObject().removeItem(COSName.MEDIA_BOX);
            page.getCOSObject().removeItem(COSName.CROP_BOX);
            page.getCOSObject().removeItem(COSName.ROTATE);
            page.setUserUnit(2);
            source = saved(pdf);
        }
        var adapter = new PdfAdapter();
        var output = new ByteArrayOutputStream();
        adapter.copy(adapter.input(source, "").allPages(), output);
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            assertEquals("INHERITED", new PDFTextStripper().getText(pdf).replaceAll("\\s+", ""));
            assertEquals(10, pdf.getPage(0).getMediaBox().getLowerLeftX());
            assertEquals(20, pdf.getPage(0).getCropBox().getLowerLeftX());
            assertEquals(90, pdf.getPage(0).getRotation());
            assertEquals(2, pdf.getPage(0).getUserUnit());
            assertNotNull(pdf.getPage(0).getResources());
        }
    }

    @Test void preservesFlateAndDctImageResourcesWithoutRasterizing() throws IOException {
        byte[] source;
        try (var pdf = new PDDocument()) {
            var page = new PDPage(new PDRectangle(240, 300));
            pdf.addPage(page);
            var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, 0xFF3300);
            try (var content = new PDPageContentStream(pdf, page)) {
                content.drawImage(LosslessFactory.createFromImage(pdf, image), 20, 20, 40, 40);
                content.drawImage(JPEGFactory.createFromImage(pdf, image), 80, 20, 40, 40);
            }
            source = saved(pdf);
        }
        var adapter = new PdfAdapter();
        var output = new ByteArrayOutputStream();
        adapter.copy(adapter.input(source, "").allPages(), output);
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            int count = 0;
            for (var ignored : pdf.getPage(0).getResources().getXObjectNames()) { count++; }
            assertEquals(2, count);
        }
        var tiny = adapter(PdfLimits.DEFAULT.toBuilder().maxImagePixels(3).build());
        assertCode(PdfOperationException.Code.IMAGE_LIMIT, () -> tiny.read(tiny.input(source, "")));
    }

    @Test void borrowedInputIsNeverClosedAndConsumesOnlyLimitPlusOne() throws IOException {
        var adapter = adapter(PdfLimits.DEFAULT.toBuilder().maxInputBytes(4).build());
        var source = new BorrowedInput(new byte[100]);
        assertCode(PdfOperationException.Code.INPUT_LIMIT, () -> adapter.input(source, ""));
        assertEquals(5, source.position);
        assertFalse(source.closed);
    }

    @Test void borrowedInputRemainsOpenOnIoFailure() {
        var closed = new AtomicBoolean();
        var source = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("private-source-path"); }
            @Override public void close() { closed.set(true); }
        };
        var failure = assertThrows(PdfOperationException.class, () -> new PdfAdapter().input(source, ""));
        assertEquals(PdfOperationException.Code.INPUT_IO, failure.code());
        assertFalse(failure.getMessage().contains("private-source-path"));
        assertFalse(closed.get());
    }

    @Test void pathInputOwnsAndReleasesItsFileHandle() throws IOException {
        var path = directory.resolve("source.pdf");
        Files.write(path, pages("LOCAL"));
        var input = new PdfAdapter().input(path, "");
        Files.delete(path);
        assertEquals("LOCAL", new PdfAdapter().read(input).pages().getFirst().text().strip());
    }

    @Test void pathOutputAtomicallyReplacesAndLeavesNoTemporaryFiles() throws IOException {
        var target = directory.resolve("result.pdf");
        Files.writeString(target, "existing");
        var adapter = new PdfAdapter();
        adapter.copy(adapter.input(pages("REPLACED"), "").allPages(), target);
        assertEquals("REPLACED", adapter.read(adapter.input(target, "")).pages().getFirst().text().strip());
        assertNoTemporaryFiles();
    }

    @Test void invalidSelectionWritesNoBytesAndPreservesPathTarget() throws IOException {
        var adapter = new PdfAdapter();
        var input = adapter.input(pages("ONLY"), "");
        var bytes = new ByteArrayOutputStream();
        assertCode(PdfOperationException.Code.INVALID_SELECTION, () -> adapter.copy(input.pages(1), bytes));
        assertEquals(0, bytes.size());
        var target = directory.resolve("keep.pdf");
        Files.writeString(target, "keep");
        assertCode(PdfOperationException.Code.INVALID_SELECTION, () -> adapter.copy(input.pages(1), target));
        assertEquals("keep", Files.readString(target));
        assertNoTemporaryFiles();
    }

    @Test void cancellationDuringCopyPreservesTargetAndRestoresExecutionScope() throws IOException {
        var adapter = new PdfAdapter();
        var input = adapter.input(pages("A", "B"), "");
        var target = directory.resolve("keep.pdf");
        Files.writeString(target, "keep");
        var cancelled = new AtomicBoolean();
        var options = ExecutionOptions.builder().cancelled(cancelled::get).progress(progress -> {
            if (progress.stage().equals("PDF_COPY")) { cancelled.set(true); }
        }).build();
        var failure = assertThrows(ExecutionException.class, () -> adapter.merge(List.of(input.allPages()), target, options));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        assertEquals("keep", Files.readString(target));
        assertNoTemporaryFiles();
        ExecutionContext.checkpoint();
        assertEquals(2, adapter.read(input).pages().size());
    }

    @Test void outputBudgetFailurePreservesTargetAndCleansTemporaryFile() throws IOException {
        var adapter = adapter(PdfLimits.DEFAULT.toBuilder().maxOutputBytes(16).build());
        var input = adapter.input(pages("OUTPUT"), "");
        var target = directory.resolve("keep.pdf");
        Files.writeString(target, "keep");
        assertCode(PdfOperationException.Code.OUTPUT_LIMIT, () -> adapter.copy(input.allPages(), target));
        assertEquals("keep", Files.readString(target));
        assertNoTemporaryFiles();
    }

    @Test void enclosingExecutionOutputBudgetIsObservedAndStreamRemainsBorrowed() throws IOException {
        var adapter = new PdfAdapter();
        var input = adapter.input(pages("OUTPUT"), "");
        var stream = new BorrowedOutput();
        var failure = assertThrows(ExecutionException.class, () -> adapter.merge(List.of(input.allPages()), stream,
                ExecutionOptions.builder().maxOutputBytes(16).build()));
        assertEquals(ExecutionException.Code.OUTPUT_LIMIT, failure.code());
        assertFalse(stream.closed);
    }

    @Test void malformedInputHasStableContextWithoutSourceOrPassword() throws IOException {
        var adapter = new PdfAdapter();
        var input = adapter.input("%PDF-private-content".getBytes(StandardCharsets.US_ASCII), "private-password");
        var failure = assertThrows(PdfOperationException.class, () -> adapter.read(input));
        assertEquals(PdfOperationException.Code.INVALID_PDF, failure.code());
        assertEquals(0, failure.inputIndex());
        assertFalse(failure.getMessage().contains("private-content"));
        assertFalse(failure.getMessage().contains("private-password"));
        assertFalse(input.toString().contains("private-password"));
    }

    @Test void damagedCrossReferenceIsRejectedRatherThanRepaired() throws IOException {
        var bytes = pages("DAMAGE");
        String source = new String(bytes, StandardCharsets.ISO_8859_1);
        int offset = source.lastIndexOf("startxref");
        byte[] damaged = Arrays.copyOf(bytes, offset);
        var adapter = new PdfAdapter();
        assertCode(PdfOperationException.Code.INVALID_PDF, () -> adapter.read(adapter.input(damaged, "")));
    }

    @Test void encryptedInputUsesExplicitPasswordAndExtractionPermissions() throws IOException {
        byte[] bytes = encrypted(false);
        var adapter = new PdfAdapter();
        assertCode(PdfOperationException.Code.PASSWORD, () -> adapter.read(adapter.input(bytes, "wrong-secret")));
        assertCode(PdfOperationException.Code.PERMISSION_DENIED, () -> adapter.read(adapter.input(bytes, "reader-secret")));
        assertEquals("ENCRYPTED", adapter.read(adapter.input(bytes, "owner-secret")).pages().getFirst().text().strip());
    }

    @Test void encryptedCopyRequiresOptInAndOwnerPasswordAndReportsDecryption() throws IOException {
        byte[] bytes = encrypted(true);
        var adapter = new PdfAdapter();
        assertCode(PdfOperationException.Code.ENCRYPTED_COPY_REJECTED,
                () -> adapter.copy(adapter.input(bytes, "owner-secret").allPages(), new ByteArrayOutputStream()));
        var permitted = PdfAdapter.builder().allowDecryptedCopy(true).build();
        assertCode(PdfOperationException.Code.PERMISSION_DENIED,
                () -> permitted.copy(permitted.input(bytes, "reader-secret").allPages(), new ByteArrayOutputStream()));
        var output = new ByteArrayOutputStream();
        var result = permitted.copy(permitted.input(bytes, "owner-secret").allPages(), output);
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.code() == PdfAdapter.DiagnosticCode.ENCRYPTION_REMOVED));
        try (var pdf = Loader.loadPDF(output.toByteArray())) { assertFalse(pdf.isEncrypted()); }
    }

    @Test void ownerAuthenticationDistinguishesUnrestrictedReaderAcrossStandardEncryptionModes() throws IOException {
        var adapter = PdfAdapter.builder().allowDecryptedCopy(true).build();
        int[] lengths = {40, 128, 128, 256};
        boolean[] aes = {false, false, true, true};
        int[] revisions = {3, 3, 4, 6};
        for (int index = 0; index < lengths.length; index++) {
            byte[] bytes = encrypted(true, "owner-secret", "reader-secret", lengths[index], aes[index]);
            try (var document = Loader.loadPDF(bytes, "reader-secret")) {
                assertEquals(revisions[index], document.getEncryption().getRevision());
                // All P flags are deliberately true. Input dictionary data cannot unlock this Java state.
                assertTrue(document.getCurrentAccessPermission().isOwnerPermission());
                assertTrue(document.getCurrentAccessPermission().isReadOnly());
            }
            assertCode(PdfOperationException.Code.PERMISSION_DENIED,
                    () -> adapter.copy(adapter.input(bytes, "reader-secret").allPages(), new ByteArrayOutputStream()));
            var output = new ByteArrayOutputStream();
            adapter.copy(adapter.input(bytes, "owner-secret").allPages(), output);
            assertEquals("ENCRYPTED", adapter.read(adapter.input(output.toByteArray(), "")).pages().getFirst().text().strip());
        }
    }

    @Test void ownerAuthenticationDistinguishesReaderForRevisionTwo() throws IOException {
        var adapter = PdfAdapter.builder().allowDecryptedCopy(true).build();
        var permission = new AccessPermission();
        permission.setCanFillInForm(false);
        permission.setCanExtractForAccessibility(false);
        permission.setCanAssembleDocument(false);
        permission.setCanPrintFaithful(false);
        byte[] bytes = encrypted(permission, "owner-secret", "reader-secret", 40, false);
        try (var document = Loader.loadPDF(bytes, "reader-secret")) {
            assertEquals(2, document.getEncryption().getRevision());
        }
        assertCode(PdfOperationException.Code.PERMISSION_DENIED,
                () -> adapter.copy(adapter.input(bytes, "reader-secret").allPages(), new ByteArrayOutputStream()));
        var output = new ByteArrayOutputStream();
        adapter.copy(adapter.input(bytes, "owner-secret").allPages(), output);
        assertEquals("ENCRYPTED", adapter.read(adapter.input(output.toByteArray(), "")).pages().getFirst().text().strip());
    }

    @Test void ownerAuthenticationUsesStandardHandlerUnicodePasswordNormalization() throws IOException {
        var adapter = PdfAdapter.builder().allowDecryptedCopy(true).build();
        byte[] bytes = encrypted(true, "所有者\u00AD\u00A0密碼", "讀者\u00AD\u00A0密碼", 256, true);
        assertCode(PdfOperationException.Code.PERMISSION_DENIED,
                () -> adapter.copy(adapter.input(bytes, "讀者 密碼").allPages(), new ByteArrayOutputStream()));
        for (String password : List.of("所有者\u00AD\u00A0密碼", "所有者 密碼")) {
            var output = new ByteArrayOutputStream();
            adapter.copy(adapter.input(bytes, password).allPages(), output);
            assertEquals("ENCRYPTED", adapter.read(adapter.input(output.toByteArray(), "")).pages().getFirst().text().strip());
        }
    }

    @Test void annotationsFormsActionsSignaturesAttachmentsAndDocumentSemanticsReject() throws IOException {
        var adapter = new PdfAdapter();
        for (String key : List.of("OpenAction", "Outlines", "Names", "Perms", "StructTreeRoot", "OCProperties", "OutputIntents")) {
            byte[] bytes;
            try (var pdf = Loader.loadPDF(pages("STATIC"))) {
                pdf.getDocumentCatalog().getCOSObject().setItem(key, new COSDictionary());
                bytes = saved(pdf);
            }
            byte[] source = bytes;
            assertCode(PdfOperationException.Code.UNSUPPORTED_FEATURE, () -> adapter.read(adapter.input(source, "")));
        }
        byte[] annotated;
        try (var pdf = Loader.loadPDF(pages("STATIC"))) {
            var annotation = new PDAnnotationText();
            annotation.setRectangle(new PDRectangle(10, 10, 20, 20));
            pdf.getPage(0).setAnnotations(List.of(annotation));
            annotated = saved(pdf);
        }
        assertCode(PdfOperationException.Code.UNSUPPORTED_FEATURE, () -> adapter.read(adapter.input(annotated, "")));
        byte[] form;
        try (var pdf = Loader.loadPDF(pages("STATIC"))) {
            pdf.getDocumentCatalog().setAcroForm(new PDAcroForm(pdf));
            form = saved(pdf);
        }
        assertCode(PdfOperationException.Code.UNSUPPORTED_FEATURE, () -> adapter.read(adapter.input(form, "")));
        byte[] signed;
        try (var pdf = Loader.loadPDF(pages("STATIC"))) {
            var signature = new COSDictionary();
            signature.setName(COSName.TYPE, "Sig");
            signature.setItem(COSName.getPDFName("ByteRange"), new COSArray());
            pdf.getPage(0).getCOSObject().setItem("PrivateSignature", signature);
            signed = saved(pdf);
        }
        assertCode(PdfOperationException.Code.UNSUPPORTED_FEATURE, () -> adapter.read(adapter.input(signed, "")));
    }

    @Test void documentMetadataLossIsAnExplicitWriteDiagnostic() throws IOException {
        byte[] bytes;
        try (var pdf = Loader.loadPDF(pages("META"))) {
            pdf.getDocumentInformation().setTitle("source title");
            bytes = saved(pdf);
        }
        var adapter = new PdfAdapter();
        var result = adapter.copy(adapter.input(bytes, "").allPages(), new ByteArrayOutputStream());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.code() == PdfAdapter.DiagnosticCode.DOCUMENT_METADATA_NOT_COPIED));
    }

    @Test void actualTextAnchorsAreReportedRatherThanClaimedAsLogicalExtraction() throws IOException {
        String content = "/Span << /ActualText <FEFF06450631062D06280627> >> BDC\n"
                + "/Span << /ActualText (nested) >> BDC\nBT /F1 12 Tf 3 Tr 20 50 Td (x) Tj ET\nEMC EMC\n";
        var adapter = new PdfAdapter();
        var input = adapter.input(raw(content), "");
        var result = adapter.read(input);
        assertEquals("x", result.pages().getFirst().text().strip());
        assertEquals(List.of(new PdfAdapter.Diagnostic(PdfAdapter.DiagnosticCode.ACTUAL_TEXT_NOT_APPLIED, 0, 0)), result.diagnostics());
        var output = new ByteArrayOutputStream();
        adapter.copy(input.allPages(), output);
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            String copied = new String(pdf.getPage(0).getContents().readAllBytes(), StandardCharsets.US_ASCII);
            assertTrue(copied.contains("/ActualText <FEFF06450631062D06280627>"));
        }
    }

    @Test void decodedStreamBudgetStopsCompressedExpansionBeforeTextProcessing() throws IOException {
        byte[] bytes = raw("%" + "A".repeat(80_000) + "\n");
        var adapter = adapter(PdfLimits.DEFAULT.toBuilder().maxDecodedStreamBytes(4096).build());
        assertCode(PdfOperationException.Code.DECODED_STREAM_LIMIT, () -> adapter.read(adapter.input(bytes, "")));
    }

    @Test void aggregateDecodedBudgetCountsDistinctStreams() throws IOException {
        byte[] bytes = raw("%" + "A".repeat(2000) + "\n");
        var adapter = adapter(PdfLimits.DEFAULT.toBuilder().maxDecodedBytes(3500).build());
        var first = adapter.input(bytes, "");
        var second = adapter.input(bytes, "");
        assertCode(PdfOperationException.Code.DECODED_STREAM_LIMIT,
                () -> adapter.merge(List.of(first.allPages(), second.allPages()), new ByteArrayOutputStream()));
    }

    @Test void multipleAndUnknownFiltersAreRejectedBeforeDecode() throws IOException {
        for (boolean multiple : List.of(false, true)) {
            byte[] source;
            try (var pdf = Loader.loadPDF(raw("q Q\n"))) {
                var stream = pdf.getPage(0).getContentStreams().next().getCOSObject();
                if (multiple) {
                    var filters = new COSArray();
                    filters.add(COSName.FLATE_DECODE);
                    filters.add(COSName.FLATE_DECODE);
                    stream.setItem(COSName.FILTER, filters);
                } else { stream.setItem(COSName.FILTER, COSName.getPDFName("PrivateFilter")); }
                source = saved(pdf);
            }
            var adapter = new PdfAdapter();
            assertCode(PdfOperationException.Code.UNSUPPORTED_FEATURE, () -> adapter.read(adapter.input(source, "")));
        }
    }

    @Test void textBudgetBoundsGlyphAccumulationBeforePageStringConstruction() throws IOException {
        var adapter = adapter(PdfLimits.DEFAULT.toBuilder().maxTextCharacters(4).build());
        var input = adapter.input(pages("TOO LONG"), "");
        assertCode(PdfOperationException.Code.TEXT_LIMIT, () -> adapter.read(input));
    }

    @Test void contentOperatorBudgetAppliesToCopyWithoutWritingBytes() throws IOException {
        var adapter = adapter(PdfLimits.DEFAULT.toBuilder().maxContentOperators(3).build());
        var input = adapter.input(raw("q Q q Q\n"), "");
        var output = new ByteArrayOutputStream();
        assertCode(PdfOperationException.Code.OPERATOR_LIMIT, () -> adapter.copy(input.allPages(), output));
        assertEquals(0, output.size());
    }

    @Test void graphicsDepthAndMalformedBalanceFailBeforeCopy() throws IOException {
        var adapter = adapter(PdfLimits.DEFAULT.toBuilder().maxDepth(16).build());
        var nested = adapter.input(raw("q ".repeat(17) + "Q ".repeat(17)), "");
        assertCode(PdfOperationException.Code.DEPTH_LIMIT, () -> adapter.copy(nested.allPages(), new ByteArrayOutputStream()));
        var unbalanced = adapter.input(raw("q\n"), "");
        assertCode(PdfOperationException.Code.INVALID_PDF, () -> adapter.read(unbalanced));
    }

    @Test void graphicsStateCanSpanPageContentStreamArray() throws IOException {
        byte[] bytes;
        try (var pdf = Loader.loadPDF(raw("q\n"))) {
            var ending = new PDStream(pdf, new ByteArrayInputStream("Q\n".getBytes(StandardCharsets.US_ASCII)), COSName.FLATE_DECODE);
            pdf.getPage(0).setContents(List.of(pdf.getPage(0).getContentStreams().next(), ending));
            bytes = saved(pdf);
        }
        var adapter = new PdfAdapter();
        assertEquals(1, adapter.read(adapter.input(bytes, "")).pages().size());
    }

    @Test void inlineImagesAreExplicitlyUnsupported() throws IOException {
        var adapter = new PdfAdapter();
        var input = adapter.input(raw("q BI /W 1 /H 1 /BPC 8 /CS /RGB ID abc EI Q\n"), "");
        assertCode(PdfOperationException.Code.UNSUPPORTED_FEATURE, () -> adapter.read(input));
    }

    @Test void sourceAndOutputPageBudgetsAreIndependentCumulativeCounts() throws IOException {
        var adapter = adapter(PdfLimits.DEFAULT.toBuilder().maxPages(2).build());
        var three = adapter.input(pages("A", "B", "C"), "");
        assertCode(PdfOperationException.Code.PAGE_LIMIT, () -> adapter.read(three));
        var one = adapter.input(pages("A"), "");
        assertCode(PdfOperationException.Code.PAGE_LIMIT, () -> adapter.copy(one.pages(0, 0, 0), new ByteArrayOutputStream()));
        var two = adapter.input(pages("B", "C"), "");
        assertCode(PdfOperationException.Code.PAGE_LIMIT,
                () -> adapter.merge(List.of(one.allPages(), two.allPages()), new ByteArrayOutputStream()));
    }

    @Test void aggregateInputsAndResourcesAreBudgeted() throws IOException {
        var bytes = pages("BUDGET");
        var adapter = adapter(PdfLimits.DEFAULT.toBuilder().maxTotalInputBytes(bytes.length).build());
        var first = adapter.input(bytes, "");
        var second = adapter.input(bytes, "");
        assertCode(PdfOperationException.Code.INPUT_LIMIT,
                () -> adapter.merge(List.of(first.allPages(), second.allPages()), new ByteArrayOutputStream()));
        var tiny = adapter(PdfLimits.DEFAULT.toBuilder().maxObjects(2).build());
        assertCode(PdfOperationException.Code.OBJECT_LIMIT, () -> tiny.read(tiny.input(bytes, "")));
        byte[] manyResources;
        try (var pdf = Loader.loadPDF(bytes)) {
            pdf.getPage(0).getResources().put(COSName.getPDFName("Other"), new PDType1Font(Standard14Fonts.FontName.COURIER));
            manyResources = saved(pdf);
        }
        var resourceLimit = adapter(PdfLimits.DEFAULT.toBuilder().maxResources(1).build());
        assertCode(PdfOperationException.Code.RESOURCE_LIMIT, () -> resourceLimit.read(resourceLimit.input(manyResources, "")));
    }

    @Test void invalidRotationIsRejected() throws IOException {
        byte[] invalid;
        try (var pdf = Loader.loadPDF(pages("ROTATE"))) {
            pdf.getPage(0).getCOSObject().setInt(COSName.ROTATE, 45);
            invalid = saved(pdf);
        }
        var adapter = new PdfAdapter();
        assertCode(PdfOperationException.Code.INVALID_PDF, () -> adapter.read(adapter.input(invalid, "")));
    }

    @Test void nonnumericPageBoxCoordinatesAreRejectedBeforePdfboxDefaultsThem() throws IOException {
        for (var key : List.of(COSName.MEDIA_BOX, COSName.CROP_BOX)) {
            byte[] bytes;
            try (var pdf = Loader.loadPDF(pages("BOX"))) {
                var box = new COSArray();
                box.add(COSName.getPDFName("Bogus"));
                box.add(COSName.getPDFName("Bogus"));
                box.add(org.apache.pdfbox.cos.COSInteger.get(200));
                box.add(org.apache.pdfbox.cos.COSInteger.get(300));
                pdf.getPage(0).getCOSObject().setItem(key, box);
                bytes = saved(pdf);
            }
            var adapter = new PdfAdapter();
            assertCode(PdfOperationException.Code.INVALID_PDF, () -> adapter.read(adapter.input(bytes, "")));
        }
    }

    @Test void deeplyNestedAndFlatContentArraysFailBeforeTokenAllocationCompletes() throws IOException {
        var adapter = adapter(PdfLimits.DEFAULT.toBuilder().maxDepth(32).maxObjects(500).build());
        var nested = adapter.input(raw("[".repeat(501) + "0" + "]".repeat(501) + " TJ\n"), "");
        assertCode(PdfOperationException.Code.DEPTH_LIMIT, () -> adapter.copy(nested.allPages(), new ByteArrayOutputStream()));
        var flat = adapter.input(raw("[" + "0 ".repeat(2000) + "] TJ\n"), "");
        assertCode(PdfOperationException.Code.OBJECT_LIMIT, () -> adapter.read(flat));
    }

    @Test void unterminatedArraysDictionariesAndStringsNeverBecomeSuccessfulEof() throws IOException {
        var adapter = new PdfAdapter();
        for (String syntax : List.of("[0", "<< /Key 0", "(unfinished", "<FEF", "[(valid) >>]")) {
            var input = adapter.input(raw(syntax), "");
            assertCode(PdfOperationException.Code.INVALID_PDF, () -> adapter.copy(input.allPages(), new ByteArrayOutputStream()));
        }
    }

    @Test void lexicalGuardAllowsEscapesHexAndCommentDelimitersInsideStrings() throws IOException {
        var adapter = new PdfAdapter();
        var input = adapter.input(raw("BT /F1 12 Tf 20 50 Td (\\(x\\) [ < > % \\123) Tj ET\n% [ << (\n"), "");
        assertTrue(adapter.read(input).pages().getFirst().text().contains("(x) [ < > % S"));
    }

    @Test void unknownAndOversizedOperatorsRejectBeforePdfboxGlobalOperatorCache() throws IOException {
        var adapter = new PdfAdapter();
        for (String value : List.of("UnregisteredPrivateOperator\n", "BX ArbitraryUnknownOperation EX\n", "X".repeat(1000))) {
            var input = adapter.input(raw(value), "");
            assertCode(PdfOperationException.Code.UNSUPPORTED_FEATURE, () -> adapter.copy(input.allPages(), new ByteArrayOutputStream()));
        }
    }

    @Test void limitsAndMandatoryParametersValidateAtTheBoundary() {
        assertThrows(IllegalArgumentException.class, () -> PdfLimits.builder().maxDepth(257).build());
        assertThrows(IllegalArgumentException.class, () -> PdfLimits.builder().maxOutputBytes(0).build());
        assertThrows(IllegalArgumentException.class, () -> new PdfAdapter().input(new byte[0], "x".repeat(1025)));
        assertThrows(NullPointerException.class, () -> new PdfAdapter().input(new byte[0], null));
        assertThrows(IllegalArgumentException.class, () -> new PdfAdapter().merge(List.of(), new ByteArrayOutputStream()));
    }

    private void assertNoTemporaryFiles() throws IOException {
        try (var entries = Files.list(directory)) {
            assertTrue(entries.noneMatch(path -> path.getFileName().toString().startsWith(".export4j-pdf-")));
        }
    }

    private static PdfAdapter adapter(PdfLimits limits) { return PdfAdapter.builder().limits(limits).build(); }

    private static void assertCode(PdfOperationException.Code code, IoOperation operation) {
        var failure = assertThrows(PdfOperationException.class, operation::run);
        assertEquals(code, failure.code(), failure::getMessage);
    }

    @FunctionalInterface private interface IoOperation { void run() throws IOException; }

    private static byte[] pages(String... texts) throws IOException {
        try (var pdf = new PDDocument()) {
            for (int index = 0; index < texts.length; index++) {
                var page = new PDPage(new PDRectangle(240 + index, 300));
                pdf.addPage(page);
                try (var content = new PDPageContentStream(pdf, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(20, 240);
                    content.showText(texts[index]);
                    content.endText();
                }
            }
            return saved(pdf);
        }
    }

    private static byte[] raw(String commands) throws IOException {
        try (var pdf = new PDDocument()) {
            var page = new PDPage(new PDRectangle(240, 300));
            pdf.addPage(page);
            var resources = new PDResources();
            resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            page.setResources(resources);
            page.setContents(new PDStream(pdf, new ByteArrayInputStream(commands.getBytes(StandardCharsets.US_ASCII)), COSName.FLATE_DECODE));
            return saved(pdf);
        }
    }

    private static byte[] encrypted(boolean extraction) throws IOException {
        return encrypted(extraction, "owner-secret", "reader-secret", 256, true);
    }

    private static byte[] encrypted(boolean extraction, String owner, String reader, int keyLength, boolean aes) throws IOException {
        var permission = new AccessPermission();
        permission.setCanExtractContent(extraction);
        return encrypted(permission, owner, reader, keyLength, aes);
    }

    private static byte[] encrypted(AccessPermission permission, String owner, String reader, int keyLength, boolean aes) throws IOException {
        try (var pdf = Loader.loadPDF(pages("ENCRYPTED"))) {
            var protection = new StandardProtectionPolicy(owner, reader, permission);
            protection.setEncryptionKeyLength(keyLength);
            protection.setPreferAES(aes);
            pdf.protect(protection);
            return saved(pdf);
        }
    }

    private static byte[] saved(PDDocument pdf) throws IOException {
        var output = new ByteArrayOutputStream();
        pdf.save(output);
        return output.toByteArray();
    }

    private static final class BorrowedInput extends InputStream {
        private final byte[] data;
        private int position;
        private boolean closed;
        BorrowedInput(byte[] data) { this.data = data; }
        @Override public int read() { return position == data.length ? -1 : data[position++] & 255; }
        @Override public int read(byte[] buffer, int offset, int length) {
            if (position == data.length) { return -1; }
            int count = Math.min(length, data.length - position);
            System.arraycopy(data, position, buffer, offset, count);
            position += count;
            return count;
        }
        @Override public void close() { closed = true; }
    }

    private static final class BorrowedOutput extends ByteArrayOutputStream {
        private boolean closed;
        @Override public void close() { closed = true; }
    }
}
