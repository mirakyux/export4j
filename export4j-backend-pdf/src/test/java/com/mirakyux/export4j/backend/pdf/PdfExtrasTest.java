// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;

class PdfExtrasTest {
    @Test
    void bookmarksLinksAndCommentsUseFinalPagesAndTopLeftCoordinates() throws IOException {
        var extras = new DocumentExtras(List.of(new DocumentExtras.Bookmark("报告", 1, 1, new Point(20.25, 30.5)),
                new DocumentExtras.Bookmark("Detail", 2, 1, new Point(20, 70))),
                List.of(new DocumentExtras.Link(0, new Rect(10, 20, 100, 18), 1, new Point(20.25, 30.5))),
                List.of(new DocumentExtras.Comment(1, new Rect(170, 40, 18, 18), "审阅者", "请检查此处。")), List.of());
        try (var pdf = render(extras)) {
            var outline = pdf.getDocumentCatalog().getDocumentOutline();
            var first = outline.getFirstChild();
            assertEquals("报告", first.getTitle());
            assertEquals("Detail", first.getFirstChild().getTitle());
            var target = assertInstanceOf(PDPageXYZDestination.class, first.getDestination());
            assertEquals(1, target.retrievePageNumber());
            assertEquals(20.25f, ((org.apache.pdfbox.cos.COSNumber) target.getCOSObject().getObject(2)).floatValue());
            assertEquals(269.5f, ((org.apache.pdfbox.cos.COSNumber) target.getCOSObject().getObject(3)).floatValue());
            var link = assertInstanceOf(PDAnnotationLink.class, pdf.getPage(0).getAnnotations().getFirst());
            assertEquals(262, link.getRectangle().getLowerLeftY());
            assertEquals(1, assertInstanceOf(PDPageXYZDestination.class, link.getDestination()).retrievePageNumber());
            var comment = assertInstanceOf(PDAnnotationText.class, pdf.getPage(1).getAnnotations().getFirst());
            assertEquals("审阅者", comment.getTitlePopup());
            assertEquals("请检查此处。", comment.getContents());
            assertNotNull(comment.getNormalAppearanceStream());
        }
    }

    @Test
    void canonicalFieldsShareWidgetsValuesDefaultsAndNonemptyAppearances() throws IOException {
        try (var pdf = render(fields())) {
            var form = pdf.getDocumentCatalog().getAcroForm();
            assertFalse(form.getNeedAppearances());
            assertEquals(4, form.getFields().size());
            var text = assertInstanceOf(PDTextField.class, form.getField("name"));
            assertEquals("Ada", text.getValue());
            assertEquals("Ada", text.getDefaultValue());
            assertFalse(text.isReadOnly());
            assertEquals("Line 1\nLine 2", assertInstanceOf(PDTextField.class, form.getField("notes")).getValue());
            assertTrue(assertInstanceOf(PDTextField.class, form.getField("notes")).isMultiline());
            assertTrue(assertInstanceOf(PDCheckBox.class, form.getField("accepted")).isChecked());
            assertFalse(assertInstanceOf(PDCheckBox.class, form.getField("other")).isChecked());
            for (var field : form.getFields()) {
                var widget = field.getWidgets().getFirst();
                assertSame(field.getCOSObject(), widget.getCOSObject());
                assertTrue(pdf.getPage(1).getAnnotations().stream().anyMatch(a -> a.getCOSObject() == widget.getCOSObject()));
                assertNotNull(widget.getPage());
                var normal = widget.getAppearance().getNormalAppearance();
                if (normal.isStream()) {
                    assertTrue(normal.getAppearanceStream().getCOSObject().getLength() > 0);
                } else {
                    assertEquals(2, normal.getSubDictionary().size());
                    assertTrue(normal.getSubDictionary().containsKey(widget.getAppearanceState()));
                    assertEquals(field.getCOSObject().getNameAsString(COSName.V), widget.getAppearanceState().getName());
                }
            }
        }
    }

    @Test
    void fieldsRemainEditableAfterSaveAndReopen() throws IOException {
        byte[] changed;
        try (var pdf = render(fields())) {
            var form = pdf.getDocumentCatalog().getAcroForm();
            ((PDTextField) form.getField("name")).setValue("Grace");
            ((PDCheckBox) form.getField("accepted")).unCheck();
            var output = new ByteArrayOutputStream();
            pdf.save(output);
            changed = output.toByteArray();
        }
        try (var pdf = Loader.loadPDF(changed)) {
            var form = pdf.getDocumentCatalog().getAcroForm();
            assertEquals("Grace", form.getField("name").getValueAsString());
            assertEquals("Off", form.getField("accepted").getValueAsString());
            assertNotNull(form.getField("name").getWidgets().getFirst().getNormalAppearanceStream());
            assertEquals(4, pdf.getPage(1).getAnnotations().size());
        }
    }

    @Test
    void interactionBudgetsFailBeforeWritingAndDoNotCloseBorrowedStream() {
        var limits = new PdfLimits(10, 100, 10, 1000, 1000, 1000, 1000, 1, 1000);
        var output = new TrackingOutput();
        var failure = assertThrows(PdfRenderException.class, () -> new PdfBackend(limits).write(document(fields()), output));
        assertEquals(PdfErrorCode.BUDGET_EXCEEDED, failure.code());
        assertEquals(0, output.size());
        assertFalse(output.closed);
        var textLimits = new PdfLimits(10, 100, 10, 1000, 1000, 1000, 1000, 10, 1);
        assertThrows(PdfRenderException.class, () -> new PdfBackend(textLimits).write(document(fields()), output));
        assertEquals(0, output.size());
    }

    @Test
    void collapsedInteractionRectangleFailsWithoutPartialOutput() {
        var extras = new DocumentExtras(List.of(), List.of(), List.of(), List.of(
                new DocumentExtras.CheckBox(0, new Rect(100, 100, 1e-40, 10), "tiny", false)));
        var output = new ByteArrayOutputStream();
        assertEquals(PdfErrorCode.INVALID_GEOMETRY,
                assertThrows(PdfRenderException.class, () -> new PdfBackend().write(document(extras), output)).code());
        assertEquals(0, output.size());
    }

    @Test
    void invalidFormNamesValuesAndDuplicateFieldsFailExplicitly() {
        assertThrows(IllegalArgumentException.class, () -> new DocumentExtras.TextField(0, new Rect(0, 0, 10, 10), "a.b", "", false));
        assertThrows(IllegalArgumentException.class, () -> new DocumentExtras.TextField(0, new Rect(0, 0, 10, 10), "name", "中文", false));
        assertThrows(IllegalArgumentException.class, () -> new DocumentExtras.TextField(0, new Rect(0, 0, 10, 10), "name", "a\nb", false));
        var field = new DocumentExtras.CheckBox(0, new Rect(0, 0, 10, 10), "check", false);
        assertThrows(IllegalArgumentException.class, () -> document(new DocumentExtras(List.of(), List.of(), List.of(), List.of(field, field))));
        assertThrows(IllegalArgumentException.class, () -> document(new DocumentExtras(List.of(), List.of(),
                List.of(new DocumentExtras.Comment(2, new Rect(0, 0, 10, 10), "", "text")), List.of())));
    }

    private static DocumentExtras fields() {
        return new DocumentExtras(List.of(), List.of(), List.of(), List.of(
                new DocumentExtras.TextField(1, new Rect(20, 30, 120, 24), "name", "Ada", false),
                new DocumentExtras.TextField(1, new Rect(20, 70, 140, 50), "notes", "Line 1\nLine 2", true),
                new DocumentExtras.CheckBox(1, new Rect(20, 150, 18, 18), "accepted", true),
                new DocumentExtras.CheckBox(1, new Rect(50, 150, 18, 18), "other", false)));
    }

    private static DisplayDocument document(DocumentExtras extras) {
        var page = new DisplayPage(new PageSize(200, 300), List.of());
        return new DisplayDocument(DocumentMetadata.EMPTY, List.of(page, page), extras);
    }

    private static PDDocument render(DocumentExtras extras) throws IOException {
        var output = new TrackingOutput();
        new PdfBackend().write(document(extras), output);
        assertFalse(output.closed);
        return Loader.loadPDF(output.toByteArray());
    }

    private static final class TrackingOutput extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }
}
