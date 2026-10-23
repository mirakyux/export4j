// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfObjectEditorTest {
    @TempDir Path directory;
    private static final PdfAdapter.Box BOX = new PdfAdapter.Box(15, 160, 80, 20);

    @Test void readsFourFieldKindsAndFillsValuesWithConcreteEditableAppearances() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(source(), ""); var before = adapter.readObjects(input);
        assertEquals(List.of("TEXT", "CHOICE", "CHECKBOX", "RADIO"), before.fields().stream().map(PdfObjects.Field::type).toList());
        var font = font(); var prepared = adapter.editObjects(input).fillText("text", "READY", font, 10).fillChoice("choice", "B", font, 10).setChecked("check", true).selectRadio("radio", "Two").prepare();
        var output = new ByteArrayOutputStream(); var result = prepared.to(output); assertEquals(4, result.changes()); assertTrue(result.report().issues().isEmpty());
        var after = adapter.readObjects(adapter.input(output.toByteArray(), "")); assertEquals(List.of("READY", "[B]", "Yes", "Two"), after.fields().stream().map(PdfObjects.Field::value).toList());
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            var form = pdf.getDocumentCatalog().getAcroForm(null); assertFalse(form.getNeedAppearances());
            for (var field : form.getFieldTree()) { for (var widget : field.getWidgets()) { assertNotNull(widget.getNormalAppearanceStream()); } }
            assertEquals(10, Float.parseFloat(form.getField("text").getCOSObject().getString(COSName.DA).split(" ")[1]));
        }
        assertEquals("OLD", adapter.readObjects(input).fields().getFirst().value());
    }
    @Test void metadataCommentsLinksBookmarksAndAttachmentEditsRetainPageContent() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(source(), "");
        byte[] data = "opaque attachment".getBytes(StandardCharsets.UTF_8);
        var request = adapter.editObjects(input).metadata("Title", "READY-TITLE").addComment(0, BOX, "COMMENT-OLD")
                .addLink(0, BOX, "https://example.org/docs").addPageLink(0, BOX, 1)
                .bookmarks(List.of(new PdfObjects.Bookmark("ROOT", 0, List.of(new PdfObjects.Bookmark("CHILD", 1, List.of())))))
                .attachment("note.txt", data, "text/plain");
        data[0] = 0;
        var output = new ByteArrayOutputStream(); request.prepare().to(output);
        var result = adapter.readObjects(adapter.input(output.toByteArray(), ""));
        assertEquals("READY-TITLE", result.metadata().get("Title")); assertEquals(1, result.bookmarks().getFirst().children().getFirst().page());
        assertEquals("COMMENT-OLD", result.annotations().stream().filter(value -> value.type().equals("TEXT")).findFirst().orElseThrow().text());
        assertTrue(result.annotations().stream().anyMatch(value -> value.uri().equals("https://example.org/docs")));
        assertTrue(result.annotations().stream().anyMatch(value -> value.targetPage() == 1));
        assertEquals("opaque attachment", new String(result.attachments().getFirst().bytes(), StandardCharsets.UTF_8));
        var leaked = result.attachments().getFirst().bytes(); leaked[0] = 0; assertEquals('o', result.attachments().getFirst().bytes()[0]);
        var edited = new ByteArrayOutputStream(); adapter.editObjects(adapter.input(output.toByteArray(), "")).updateComment(0, 5, "COMMENT-READY").removeAttachment("note.txt").prepare().to(edited);
        var state = adapter.readObjects(adapter.input(edited.toByteArray(), "")); assertTrue(state.attachments().isEmpty());
        assertTrue(state.annotations().stream().anyMatch(value -> value.text().equals("COMMENT-READY")));
        try (var before = Loader.loadPDF(source()); var after = Loader.loadPDF(edited.toByteArray())) {
            assertArrayEquals(before.getPage(0).getContents().readAllBytes(), after.getPage(0).getContents().readAllBytes());
            assertTrue(new PDFTextStripper().getText(after).contains("SOURCE-BODY"));
        }
    }
    @Test void flatteningRequiresExplicitLossAndPreservesVisibleFieldValues() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(source(), "");
        assertThrows(ProcessingException.class, () -> adapter.editObjects(input).flattenForms().prepare());
        var policy = ProcessingPolicy.builder().feature("FORMS", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DISCARD).build();
        var prepared = adapter.editObjects(input).policy(policy).fillText("text", "READY", font(), 10).flattenForms().prepare();
        assertEquals(1, prepared.preview().losses().size()); assertTrue(prepared.preview().warnings().isEmpty());
        var bytes = new ByteArrayOutputStream(); prepared.to(bytes);
        var after = adapter.readObjects(adapter.input(bytes.toByteArray(), "")); assertTrue(after.fields().isEmpty()); assertTrue(after.annotations().isEmpty());
        try (var pdf = Loader.loadPDF(bytes.toByteArray())) { assertTrue(new PDFTextStripper().getText(pdf).contains("READY")); }
    }
    @Test void unsupportedFieldEditCanSkipButCannotSilentlyDiscardAndReadonlyFails() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(source(), "");
        assertThrows(ProcessingException.class, () -> adapter.editObjects(input).fillText("check", "X", font(), 10).prepare());
        var policy = ProcessingPolicy.builder().feature("FIELD_FORMAT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.SKIP_MATCH).build();
        var prepared = adapter.editObjects(input).policy(policy).fillText("check", "X", font(), 10).prepare();
        var out = new ByteArrayOutputStream(); assertEquals(0, prepared.to(out).changes()); assertEquals(1, prepared.preview().issues().size());
        try (var pdf = Loader.loadPDF(source())) {
            pdf.getDocumentCatalog().getAcroForm(null).getField("text").setReadOnly(true);
            var locked = adapter.input(save(pdf), "");
            assertEquals(PdfOperationException.Code.PERMISSION_DENIED, assertThrows(PdfOperationException.class,
                    () -> adapter.editObjects(locked).policy(policy).fillText("text", "READY", font(), 10).prepare()).code());
        }
    }
    @Test void widgetCannotBeRemovedIndependentlyAndInvalidOptionsOrBoxesFail() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(source(), "");
        assertThrows(ProcessingException.class, () -> adapter.editObjects(input).removeAnnotation(0, 0).prepare());
        assertThrows(IllegalArgumentException.class, () -> adapter.editObjects(input).fillChoice("choice", "MISSING", font(), 10).prepare());
        assertThrows(IllegalArgumentException.class, () -> adapter.editObjects(input).selectRadio("radio", "MISSING").prepare());
        assertThrows(IllegalArgumentException.class, () -> adapter.editObjects(input).fillText("text", "X".repeat(100), font(), 10).prepare());
        assertThrows(IllegalArgumentException.class, () -> adapter.editObjects(input).addLink(0, BOX, "file:///secret"));
        assertThrows(IllegalArgumentException.class, () -> adapter.editObjects(input).attachment("../note.txt", new byte[0], "text/plain"));
        assertThrows(IllegalArgumentException.class, () -> adapter.editObjects(input).metadata("Title", "\uD800"));
    }
    @Test void staleXmpMustBeExplicitlyRemovedAndIgnoreKeepsActualLoss() throws Exception {
        var adapter = new PdfAdapter(); byte[] original;
        try (var pdf = Loader.loadPDF(source())) {
            var metadata = new PDMetadata(pdf); metadata.importXMPMetadata("<xmp>OLD</xmp>".getBytes(StandardCharsets.UTF_8)); pdf.getDocumentCatalog().setMetadata(metadata); original = save(pdf);
        }
        var input = adapter.input(original, ""); assertThrows(ProcessingException.class, () -> adapter.editObjects(input).metadata("Title", "NEW").prepare());
        var policy = ProcessingPolicy.builder().feature("XMP_METADATA", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DISCARD).build();
        var prepared = adapter.editObjects(input).policy(policy).metadata("Title", "NEW").prepare(); assertEquals(1, prepared.preview().losses().size());
        var output = new ByteArrayOutputStream(); prepared.to(output);
        try (var pdf = Loader.loadPDF(output.toByteArray())) { assertFalse(pdf.getDocumentCatalog().getCOSObject().containsKey(COSName.METADATA)); assertEquals("NEW", pdf.getDocumentInformation().getTitle()); }
    }
    @Test void scriptsSignaturesDanglingWidgetsAndCyclicFieldsAreNeverIgnored() throws Exception {
        var adapter = new PdfAdapter();
        try (var pdf = Loader.loadPDF(source())) {
            pdf.getDocumentCatalog().setOpenAction(new PDActionJavaScript("malicious"));
            assertThrows(PdfOperationException.class, () -> adapter.editObjects(adapter.input(save(pdf), "")).prepare());
            pdf.getDocumentCatalog().getCOSObject().removeItem(COSName.OPEN_ACTION);
            var field = pdf.getDocumentCatalog().getAcroForm(null).getField("text"); field.getCOSObject().setName(COSName.FT, "Sig");
            assertThrows(PdfOperationException.class, () -> adapter.readObjects(adapter.input(save(pdf), "")));
            field.getCOSObject().setName(COSName.FT, "Tx");
            var kids = new COSArray(); kids.add(field.getCOSObject()); field.getCOSObject().setItem(COSName.KIDS, kids);
            assertThrows(PdfOperationException.class, () -> adapter.readObjects(adapter.input(save(pdf), "")));
        }
        try (var pdf = Loader.loadPDF(source())) {
            pdf.getDocumentCatalog().getAcroForm(null).getFields().removeFirst();
            assertThrows(PdfOperationException.class, () -> adapter.readObjects(adapter.input(save(pdf), "")));
        }
    }
    @Test void objectAndAttachmentBudgetsCancellationAndSnapshotAreBounded() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(source(), ""); var cancelled = new AtomicBoolean();
        var request = adapter.editObjects(input).execution(ExecutionOptions.builder().cancelled(cancelled::get).build()).metadata("Title", "SNAPSHOT");
        var prepared = request.prepare(); request.metadata("Title", "LATER"); var output = new ByteArrayOutputStream(); prepared.to(output);
        assertEquals("SNAPSHOT", adapter.readObjects(adapter.input(output.toByteArray(), "")).metadata().get("Title"));
        var target = directory.resolve("output.pdf"); Files.writeString(target, "OLD"); cancelled.set(true);
        assertThrows(ExecutionException.class, () -> prepared.to(target)); assertEquals("OLD", Files.readString(target));
        var small = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxTextCharacters(2).build()).build();
        assertThrows(IOException.class, () -> small.readObjects(input));
    }
    @Test void fillingPreservesForegroundColorAndUnknownTextStateCanOnlyBeSkipped() throws Exception {
        var adapter = new PdfAdapter(); byte[] original;
        try (var pdf = Loader.loadPDF(source())) {
            var field = (PDTextField) pdf.getDocumentCatalog().getAcroForm(null).getField("text");
            field.setDefaultAppearance("/Helv 10 Tf 0.1 0.2 0.6 rg"); field.setValue("OLD"); original = save(pdf);
        }
        var out = new ByteArrayOutputStream(); adapter.editObjects(adapter.input(original, "")).fillText("text", "READY", font(), 10).prepare().to(out);
        try (var pdf = Loader.loadPDF(out.toByteArray())) {
            String appearance = pdf.getDocumentCatalog().getAcroForm(null).getField("text").getCOSObject().getString(COSName.DA);
            String[] tokens = appearance.split(" "); assertEquals("rg", tokens[tokens.length - 1]);
            assertEquals(.1, Double.parseDouble(tokens[3]), .000001); assertEquals(.6, Double.parseDouble(tokens[5]), .000001);
        }
        try (var pdf = Loader.loadPDF(source())) {
            pdf.getDocumentCatalog().getAcroForm(null).getField("text").getCOSObject().setString(COSName.DA, "/Helv 10 Tf 80 Tz 0 g");
            var input = adapter.input(save(pdf), "");
            assertThrows(ProcessingException.class, () -> adapter.editObjects(input).fillText("text", "READY", font(), 10).prepare());
            var policy = ProcessingPolicy.builder().feature("FIELD_FORMAT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.SKIP_MATCH).build();
            var preserved = new ByteArrayOutputStream(); adapter.editObjects(input).policy(policy).fillText("text", "READY", font(), 10).prepare().to(preserved);
            assertEquals("OLD", adapter.readObjects(adapter.input(preserved.toByteArray(), "")).fields().getFirst().value());
        }
    }
    @Test void malformedNameContainersAndUnknownActionsAreRejectedBeforeAnyEdit() throws Exception {
        var adapter = new PdfAdapter();
        try (var pdf = Loader.loadPDF(source())) {
            pdf.getDocumentCatalog().getCOSObject().setInt(COSName.NAMES, 7);
            assertThrows(PdfOperationException.class, () -> adapter.editObjects(adapter.input(save(pdf), "")).metadata("Title", "NEW").prepare());
        }
        try (var pdf = Loader.loadPDF(source())) {
            var action = new COSDictionary(); action.setName(COSName.S, "UnknownAction");
            pdf.getPage(0).getAnnotations().getFirst().getCOSObject().setItem(COSName.A, action);
            assertThrows(PdfOperationException.class, () -> adapter.readObjects(adapter.input(save(pdf), "")));
        }
    }
    @Test void malformedAppearanceCannotBeIgnoredAndThickBordersLimitCapacity() throws Exception {
        var adapter = new PdfAdapter();
        var skip = ProcessingPolicy.builder().feature("FIELD_FORMAT", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.SKIP_MATCH).build();
        try (var pdf = Loader.loadPDF(source())) {
            var field = (PDTextField) pdf.getDocumentCatalog().getAcroForm(null).getField("text");
            field.getCOSObject().setString(COSName.DA, "/Helv 10 Tf 80 Tz [");
            var input = adapter.input(save(pdf), "");
            assertEquals(PdfOperationException.Code.INVALID_PDF, assertThrows(PdfOperationException.class,
                    () -> adapter.editObjects(input).policy(skip).fillText("text", "READY", font(), 10).prepare()).code());
        }
        try (var pdf = Loader.loadPDF(source())) {
            var field = (PDTextField) pdf.getDocumentCatalog().getAcroForm(null).getField("text");
            var border = new org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary(); border.setWidth(6);
            field.getWidgets().getFirst().setBorderStyle(border);
            var input = adapter.input(save(pdf), "");
            assertThrows(IllegalArgumentException.class, () -> adapter.editObjects(input).fillText("text", "READY", font(), 10).prepare());
        }
    }
    static PdfFont font() throws IOException { return PdfFont.of(Files.readAllBytes(Path.of("../assets/fonts/LiberationSans-Regular.ttf"))); }
    static byte[] source() throws IOException {
        try (var pdf = new PDDocument()) {
            var page = new PDPage(new PDRectangle(300, 260)); pdf.addPage(page); pdf.addPage(new PDPage(new PDRectangle(300, 260)));
            try (var content = new PDPageContentStream(pdf, page)) { content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12); content.newLineAtOffset(20, 220); content.showText("SOURCE-BODY"); content.endText(); }
            var form = new PDAcroForm(pdf); pdf.getDocumentCatalog().setAcroForm(form); form.setDefaultResources(new PDResources()); form.getDefaultResources().put(COSName.getPDFName("Helv"), new PDType1Font(Standard14Fonts.FontName.HELVETICA)); form.setDefaultAppearance("/Helv 10 Tf 0 g"); form.setNeedAppearances(false);
            var text = new PDTextField(form); text.setPartialName("text"); widget(form, page, text, 20, 180, 140, 24); text.setValue("OLD");
            var choice = new PDComboBox(form); choice.setPartialName("choice"); choice.setOptions(List.of("A", "B")); widget(form, page, choice, 20, 140, 140, 24); choice.setValue("A");
            var check = new PDCheckBox(form); check.setPartialName("check"); widget(form, page, check, 20, 100, 18, 18); states(pdf, check.getWidgets().getFirst(), "Yes"); check.unCheck();
            var radio = new PDRadioButton(form); radio.setPartialName("radio"); var one = new PDAnnotationWidget(); var two = new PDAnnotationWidget();
            one.setParent(radio); two.setParent(radio); one.setRectangle(new PDRectangle(20, 60, 18, 18)); two.setRectangle(new PDRectangle(55, 60, 18, 18)); one.setPage(page); two.setPage(page);
            states(pdf, one, "One"); states(pdf, two, "Two"); radio.setWidgets(List.of(one, two)); form.getFields().add(radio); page.getAnnotations().addAll(List.of(one, two)); radio.setValue("One");
            return save(pdf);
        }
    }
    private static void widget(PDAcroForm form, PDPage page, PDTerminalField field, float x, float y, float w, float h) throws IOException {
        var widget = field.getWidgets().getFirst(); widget.setRectangle(new PDRectangle(x, y, w, h)); widget.setPage(page); widget.setPrinted(true); form.getFields().add(field); page.getAnnotations().add(widget);
    }
    private static void states(PDDocument pdf, PDAnnotationWidget widget, String on) throws IOException {
        var states = new COSDictionary();
        for (String state : List.of("Off", on)) {
            var appearance = new PDAppearanceStream(pdf); appearance.setBBox(new PDRectangle(18, 18)); appearance.setResources(new PDResources());
            try (var output = appearance.getContentStream().createOutputStream()) { output.write((state.equals("Off") ? "0 G 1 1 16 16 re S\n" : "0 g 4 4 10 10 re f\n").getBytes(StandardCharsets.US_ASCII)); }
            states.setItem(COSName.getPDFName(state), appearance);
        }
        var dictionary = new PDAppearanceDictionary(); dictionary.getCOSObject().setItem(COSName.N, states); widget.setAppearance(dictionary); widget.setAppearanceState("Off");
    }
    private static byte[] save(PDDocument pdf) throws IOException { var out = new ByteArrayOutputStream(); pdf.save(out); return out.toByteArray(); }
}
