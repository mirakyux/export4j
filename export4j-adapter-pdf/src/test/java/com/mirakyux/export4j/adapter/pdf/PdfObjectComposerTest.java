// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfObjectComposerTest {
    @TempDir Path directory;
    private static final PdfAdapter.Box BOX = new PdfAdapter.Box(15, 200, 40, 15);
    @Test void reordersPagesAndRemapsFieldsLinksAndNestedBookmarks() throws Exception {
        var adapter = new PdfAdapter(); var input = fixture(adapter, "A");
        var output = new ByteArrayOutputStream();
        var prepared = adapter.composeObjects().add(input.pages(1, 0), "first").prepare(); prepared.to(output);
        var result = adapter.readObjects(adapter.input(output.toByteArray(), ""));
        assertEquals(2, result.pageCount()); assertTrue(result.fields().stream().allMatch(field -> field.name().startsWith("first_")));
        assertEquals(1, result.bookmarks().getFirst().page()); assertEquals(0, result.bookmarks().getFirst().children().getFirst().page());
        assertTrue(result.annotations().stream().anyMatch(annotation -> annotation.page() == 1 && annotation.targetPage() == 0));
        assertEquals("first_note.txt", result.attachments().getFirst().name());
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            var form = pdf.getDocumentCatalog().getAcroForm(null);
            try (var source = Loader.loadPDF(input.bytes())) {
                assertEquals(source.getDocumentCatalog().getAcroForm(null).getDefaultResources().getCOSObject().keySet(), form.getDefaultResources().getCOSObject().keySet());
                assertEquals(source.getDocumentCatalog().getAcroForm(null).getDefaultResources().getCOSObject().getCOSDictionary(COSName.FONT).keySet(),
                        form.getDefaultResources().getCOSObject().getCOSDictionary(COSName.FONT).keySet());
            }
            assertNotNull(form.getField("first_text").getWidgets().getFirst().getNormalAppearanceStream());
            assertEquals(pdf.getPage(1).getCOSObject(), form.getField("first_text").getWidgets().getFirst().getPage().getCOSObject());
            var stripper = new PDFTextStripper(); stripper.setStartPage(1); stripper.setEndPage(1); assertTrue(stripper.getText(pdf).isBlank());
            stripper.setStartPage(2); stripper.setEndPage(2); assertTrue(stripper.getText(pdf).contains("SOURCE-BODY"));
        }
    }
    @Test void mergesSourcesWithExplicitNamespacesAndFirstSourceMetadata() throws Exception {
        var adapter = new PdfAdapter(); var first = fixture(adapter, "FIRST"); var second = fixture(adapter, "SECOND");
        assertThrows(ProcessingException.class, () -> adapter.composeObjects().add(first.allPages(), "a").add(second.allPages(), "b").prepare());
        var policy = ProcessingPolicy.builder().feature("DOCUMENT_METADATA", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DISCARD).build();
        var prepared = adapter.composeObjects().policy(policy).add(first.allPages(), "a").add(second.pages(1, 0), "b").prepare();
        var output = new ByteArrayOutputStream(); prepared.to(output); var result = adapter.readObjects(adapter.input(output.toByteArray(), ""));
        assertEquals(4, result.pageCount()); assertEquals(8, result.fields().size()); assertEquals("FIRST", result.metadata().get("Title"));
        assertEquals(List.of("a_note.txt", "b_note.txt"), result.attachments().stream().map(PdfObjects.Attachment::name).toList());
        assertEquals(2, result.bookmarks().size()); assertEquals(3, result.bookmarks().get(1).page());
        assertTrue(result.annotations().stream().anyMatch(annotation -> annotation.page() == 3 && annotation.targetPage() == 2));
        assertEquals(1, prepared.preview().losses().size()); assertTrue(prepared.preview().warnings().isEmpty());
        var filled = adapter.editObjects(adapter.input(output.toByteArray(), "")).fillText("b_text", "READY", PdfObjectEditorTest.font(), 10).prepare();
        var after = new ByteArrayOutputStream(); filled.to(after);
        assertEquals("READY", adapter.readObjects(adapter.input(after.toByteArray(), "")).fields().stream().filter(field -> field.name().equals("b_text")).findFirst().orElseThrow().value());
    }
    @Test void excludedDestinationsRequireActualLinkDiscardAndBookmarkDegrade() throws Exception {
        var adapter = new PdfAdapter(); var input = fixture(adapter, "A");
        assertThrows(ProcessingException.class, () -> adapter.composeObjects().add(input.pages(0), "a").prepare());
        var policy = ProcessingPolicy.builder().feature("LINK_TARGET", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
                .feature("BOOKMARK_TARGET", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE).build();
        var prepared = adapter.composeObjects().policy(policy).add(input.pages(0), "a").prepare(); var out = new ByteArrayOutputStream(); prepared.to(out);
        var result = adapter.readObjects(adapter.input(out.toByteArray(), "")); assertEquals(4, result.fields().size());
        assertEquals(-1, result.bookmarks().getFirst().children().getFirst().page()); assertTrue(result.annotations().stream().noneMatch(annotation -> annotation.targetPage() >= 0));
        assertEquals(2, prepared.preview().losses().size());
    }
    @Test void selectingAwayWidgetsReportsRemovalAndDoesNotRetainOrphanFields() throws Exception {
        var adapter = new PdfAdapter(); var input = fixture(adapter, "A");
        var policy = ProcessingPolicy.builder().feature("BOOKMARK_TARGET", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
                .feature("FIELD_WIDGET_SELECTION", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD).build();
        var prepared = adapter.composeObjects().policy(policy).add(input.pages(1), "a").prepare(); var out = new ByteArrayOutputStream(); prepared.to(out);
        var result = adapter.readObjects(adapter.input(out.toByteArray(), "")); assertTrue(result.fields().isEmpty()); assertTrue(result.annotations().isEmpty());
        assertEquals(-1, result.bookmarks().getFirst().page()); assertEquals(0, result.bookmarks().getFirst().children().getFirst().page());
        assertEquals(5, prepared.preview().issues().stream().filter(issue -> issue.feature().equals("FIELD_WIDGET_SELECTION")).count());
    }
    @Test void duplicatesCollisionsPageBudgetsAndCancelledCommitFail() throws Exception {
        var adapter = new PdfAdapter(); var first = fixture(adapter, "A"); var second = fixture(adapter, "B");
        assertThrows(IllegalArgumentException.class, () -> adapter.composeObjects().add(first.allPages(), "a").add(first.allPages(), "b"));
        assertThrows(IllegalArgumentException.class, () -> adapter.composeObjects().add(first.allPages(), "a").add(second.allPages(), "a"));
        assertThrows(IllegalArgumentException.class, () -> adapter.composeObjects().add(first.pages(0, 0), "a").prepare());
        assertThrows(PdfOperationException.class, () -> adapter.composeObjects().add(first.pages(9), "a").prepare());
        var bounded = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxPages(1).build()).build();
        assertThrows(PdfOperationException.class, () -> bounded.composeObjects().add(first.allPages(), "a").prepare());
        var cancel = new AtomicBoolean(); var prepared = adapter.composeObjects().execution(ExecutionOptions.builder().cancelled(cancel::get).build()).add(first.allPages(), "a").prepare();
        var path = directory.resolve("atomic.pdf"); Files.writeString(path, "OLD"); cancel.set(true);
        assertThrows(ExecutionException.class, () -> prepared.to(path)); assertEquals("OLD", Files.readString(path));
        try (var pdf = Loader.loadPDF(first.bytes())) { assertEquals("text", pdf.getDocumentCatalog().getAcroForm(null).getFields().getFirst().getPartialName()); }
    }
    @Test void oldStaticCompositionStillRejectsInteractiveInput() throws Exception {
        var adapter = new PdfAdapter(); var input = fixture(adapter, "A");
        assertThrows(PdfOperationException.class, () -> adapter.copy(input.allPages(), new ByteArrayOutputStream()));
        try (var pdf = Loader.loadPDF(input.bytes())) {
            pdf.getDocumentCatalog().getAcroForm(null).getCOSObject().setInt(COSName.SIG_FLAGS, 3);
            var output = new ByteArrayOutputStream(); pdf.save(output);
            var signed = adapter.input(output.toByteArray(), "");
            assertThrows(PdfOperationException.class, () -> adapter.composeObjects().add(signed.allPages(), "a").prepare());
        }
    }
    @Test void partiallySelectedRadioGroupRetainsOnlyOwnedWidgetsAndExplicitlyResetsAbsentValue() throws Exception {
        var adapter = new PdfAdapter(); byte[] bytes;
        try (var pdf = Loader.loadPDF(PdfObjectEditorTest.source())) {
            var field = (org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton) pdf.getDocumentCatalog().getAcroForm(null).getField("radio");
            var widget = field.getWidgets().get(1);
            pdf.getPage(0).setAnnotations(pdf.getPage(0).getAnnotations().stream().filter(annotation -> annotation.getCOSObject() != widget.getCOSObject()).toList());
            widget.setPage(pdf.getPage(1)); pdf.getPage(1).getAnnotations().add(widget);
            var out = new ByteArrayOutputStream(); pdf.save(out); bytes = out.toByteArray();
        }
        var input = adapter.input(bytes, "");
        var policy = ProcessingPolicy.builder().feature("FIELD_WIDGET_SELECTION", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD).build();
        assertThrows(ProcessingException.class, () -> adapter.composeObjects().policy(policy).add(input.pages(1), "a").prepare());
        var allowReset = ProcessingPolicy.builder().feature("FIELD_WIDGET_SELECTION", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
                .feature("FIELD_SELECTION_VALUE", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
        var out = new ByteArrayOutputStream(); adapter.composeObjects().policy(allowReset).add(input.pages(1), "a").prepare().to(out);
        var result = adapter.readObjects(adapter.input(out.toByteArray(), "")); assertEquals(1, result.fields().size()); assertEquals("Off", result.fields().getFirst().value());
        assertEquals(List.of("Two"), result.fields().getFirst().options()); assertEquals(1, result.annotations().size());
    }
    @Test void compositionDoesNotRepairNeedAppearancesOrRegenerateSourceAppearance() throws Exception {
        var adapter = new PdfAdapter(); byte[] bytes; byte[] appearance;
        try (var pdf = Loader.loadPDF(PdfObjectEditorTest.source())) {
            var form = pdf.getDocumentCatalog().getAcroForm(null); form.setNeedAppearances(true);
            appearance = form.getField("text").getWidgets().getFirst().getNormalAppearanceStream().getContents().readAllBytes();
            var out = new ByteArrayOutputStream(); pdf.save(out); bytes = out.toByteArray();
        }
        var output = new ByteArrayOutputStream(); adapter.composeObjects().add(adapter.input(bytes, "").allPages(), "a").prepare().to(output);
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            var form = pdf.getDocumentCatalog().getAcroForm(null); assertTrue(form.getNeedAppearances());
            org.junit.jupiter.api.Assertions.assertArrayEquals(appearance, form.getField("a_text").getWidgets().getFirst().getNormalAppearanceStream().getContents().readAllBytes());
        }
    }
    @Test void ownerDecryptionStillRequiresPermissionOptInAndExplicitLoss() throws Exception {
        byte[] encrypted;
        try (var pdf = Loader.loadPDF(PdfObjectEditorTest.source())) {
            pdf.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner", "reader", new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            var out = new ByteArrayOutputStream(); pdf.save(out); encrypted = out.toByteArray();
        }
        var strict = new PdfAdapter(); assertThrows(PdfOperationException.class, () -> strict.composeObjects().add(strict.input(encrypted, "owner").allPages(), "a").prepare());
        var adapter = PdfAdapter.builder().allowDecryptedCopy(true).build(); var policy = ProcessingPolicy.builder().feature("ENCRYPTION_REMOVED", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
        assertThrows(PdfOperationException.class, () -> adapter.composeObjects().policy(policy).add(adapter.input(encrypted, "reader").allPages(), "a").prepare());
        assertThrows(ProcessingException.class, () -> adapter.composeObjects().add(adapter.input(encrypted, "owner").allPages(), "a").prepare());
        var prepared = adapter.composeObjects().policy(policy).add(adapter.input(encrypted, "owner").allPages(), "a").prepare(); var out = new ByteArrayOutputStream(); prepared.to(out);
        try (var pdf = Loader.loadPDF(out.toByteArray())) { assertTrue(!pdf.isEncrypted()); assertEquals(4, pdf.getDocumentCatalog().getAcroForm(null).getFields().size()); }
        assertEquals("ENCRYPTION_REMOVED", prepared.preview().losses().getFirst().feature());
    }
    private static PdfInput fixture(PdfAdapter adapter, String title) throws Exception {
        var out = new ByteArrayOutputStream();
        adapter.editObjects(adapter.input(PdfObjectEditorTest.source(), "")).metadata("Title", title).addPageLink(0, BOX, 1)
                .bookmarks(List.of(new PdfObjects.Bookmark("ROOT", 0, List.of(new PdfObjects.Bookmark("CHILD", 1, List.of())))))
                .attachment("note.txt", new byte[] {1, 2, 3}, "text/plain").prepare().to(out);
        return adapter.input(out.toByteArray(), "");
    }
}
