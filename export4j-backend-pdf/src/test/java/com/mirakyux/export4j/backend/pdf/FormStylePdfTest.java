// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.FormStyle;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;

class FormStylePdfTest {
    private static final RgbColor INK = new RgbColor(0.1, 0.2, 0.3);
    private static final RgbColor PAPER = new RgbColor(0.8, 0.9, 1);
    private static final RgbColor EDGE = new RgbColor(0.4, 0.5, 0.6);
    private static FontData chinese;

    @BeforeAll
    static void fonts() throws IOException {
        try (var input = FormStylePdfTest.class.getResourceAsStream("/fonts/LXGWWenKaiTC-Regular.ttf")) {
            chinese = TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES).data();
        }
    }

    @Test
    void unspecifiedStylePreservesLegacyDictionariesAndEveryMarkAppearance() throws IOException {
        try (var pdf = render(List.of(
                new DocumentExtras.TextField(1, new Rect(20, 30, 120, 24), "text", "Ada", false),
                new DocumentExtras.ChoiceField(1, new Rect(20, 70, 120, 24), "choice", List.of("A", "B"), "B", List.of()),
                new DocumentExtras.CheckBox(1, new Rect(20, 110, 18, 18), "check", true),
                new DocumentExtras.RadioGroup(1, new Rect(20, 150, 60, 18), "radio", List.of("A", "B"), "B")))) {
            var form = pdf.getDocumentCatalog().getAcroForm();
            assertEquals("/Helv 10 Tf 0 g", form.getDefaultAppearance());
            assertFalse(form.getNeedAppearances());
            var text = assertInstanceOf(PDTextField.class, form.getField("text"));
            var choice = assertInstanceOf(PDComboBox.class, form.getField("choice"));
            assertEquals("/Helv 10 Tf 0 g", text.getDefaultAppearance());
            assertEquals("/Helv 10 Tf 0 g", choice.getDefaultAppearance());
            assertFalse(text.getCOSObject().containsKey(COSName.Q));
            assertFalse(choice.getCOSObject().containsKey(COSName.Q));
            assertEquals(0.7f, text.getWidgets().getFirst().getBorderStyle().getWidth());
            assertColor(RgbColor.WHITE, text.getWidgets().getFirst().getAppearanceCharacteristics().getBackground());
            assertColor(new RgbColor(0.6, 0.7, 0.8), text.getWidgets().getFirst().getAppearanceCharacteristics().getBorderColour());
            assertNull(choice.getWidgets().getFirst().getAppearanceCharacteristics());
            assertNull(choice.getWidgets().getFirst().getBorderStyle());
            var check = assertInstanceOf(PDCheckBox.class, form.getField("check"));
            assertLegacyMark(check.getWidgets().getFirst(), "Yes");
            var radio = assertInstanceOf(PDRadioButton.class, form.getField("radio"));
            for (int index = 0; index < radio.getWidgets().size(); index++) {
                assertLegacyMark(radio.getWidgets().get(index), index == 0 ? "A" : "B");
            }
            assertEquals("Ada", text.getDefaultValue());
            assertEquals(List.of("B"), choice.getDefaultValue());
            assertEquals("Yes", check.getCOSObject().getNameAsString(COSName.DV));
            assertEquals("B", radio.getCOSObject().getNameAsString(COSName.DV));
        }
    }

    @Test
    void textAndChoiceStylesReachDefaultAppearanceWidgetDictionariesAndPaint() throws IOException {
        var textStyle = FormStyle.builder().fontSize(14).color(INK).background(PAPER)
                .border(1.25, EDGE).alignment(FormStyle.Alignment.RIGHT).build();
        var choiceStyle = FormStyle.builder().fontSize(11).color(RgbColor.BLACK).noBackground()
                .border(0, RgbColor.BLACK).alignment(FormStyle.Alignment.CENTER).build();
        try (var pdf = render(List.of(
                new DocumentExtras.TextField(1, new Rect(20.25, 30.5, 120, 28), "text", "Ada", false, List.of(), textStyle),
                new DocumentExtras.ChoiceField(1, new Rect(20, 80, 120, 28), "choice", List.of("A", "B"), "B", List.of(), choiceStyle)))) {
            var form = pdf.getDocumentCatalog().getAcroForm();
            var text = assertInstanceOf(PDTextField.class, form.getField("text"));
            var choice = assertInstanceOf(PDComboBox.class, form.getField("choice"));
            assertDefaultAppearance(text.getDefaultAppearance(), 14, INK);
            assertDefaultAppearance(choice.getDefaultAppearance(), 11, RgbColor.BLACK);
            assertEquals(2, text.getQ());
            assertEquals(1, choice.getQ());
            var textWidget = text.getWidgets().getFirst();
            assertEquals(1.25, textWidget.getBorderStyle().getWidth());
            assertColor(PAPER, textWidget.getAppearanceCharacteristics().getBackground());
            assertColor(EDGE, textWidget.getAppearanceCharacteristics().getBorderColour());
            assertEquals(20.25, textWidget.getRectangle().getLowerLeftX());
            assertEquals(241.5, textWidget.getRectangle().getLowerLeftY());
            assertSame(pdf.getPage(1).getCOSObject(), textWidget.getPage().getCOSObject());
            assertTrue(pdf.getPage(1).getAnnotations().stream().anyMatch(annotation -> annotation.getCOSObject() == textWidget.getCOSObject()));
            var paint = operations(textWidget.getNormalAppearanceStream());
            assertPaintedTextColor(paint, INK);
            assertOperation(paint, "Tf", 14);
            var choiceWidget = choice.getWidgets().getFirst();
            assertEquals(0, choiceWidget.getBorderStyle().getWidth());
            assertNull(choiceWidget.getAppearanceCharacteristics().getBackground());
            assertNull(choiceWidget.getAppearanceCharacteristics().getBorderColour());
            assertTrue(operations(choiceWidget.getNormalAppearanceStream()).stream().anyMatch(op -> op.name().equals("Tj") || op.name().equals("TJ")));
            assertEquals(2, pdf.getPage(1).getAnnotations().size());
            assertTrue(pdf.getPage(0).getAnnotations().isEmpty());
        }
    }

    @Test
    void zeroWidthAndDisabledBordersPaintNoStrokeWhileRetainingExplicitTextAndBackground() throws IOException {
        for (var style : List.of(FormStyle.builder().fontSize(12).color(INK).background(PAPER)
                        .border(0, new RgbColor(0, 0, 1)).build(),
                FormStyle.builder().fontSize(12).color(INK).background(PAPER).noBorder().build())) {
            try (var pdf = render(List.of(
                    new DocumentExtras.TextField(0, new Rect(20, 20, 120, 24), "text", "Ada", false, List.of(), style),
                    new DocumentExtras.ChoiceField(0, new Rect(20, 70, 120, 24), "choice", List.of("Ada", "Grace"), "Ada", List.of(), style)))) {
                for (var field : pdf.getDocumentCatalog().getAcroForm().getFields()) {
                    var widget = field.getWidgets().getFirst();
                    assertEquals(0, widget.getBorderStyle().getWidth());
                    assertNull(widget.getAppearanceCharacteristics().getBorderColour());
                    assertColor(PAPER, widget.getAppearanceCharacteristics().getBackground());
                    var paint = operations(widget.getNormalAppearanceStream());
                    assertFalse(paint.stream().anyMatch(op -> List.of("S", "s", "B", "B*", "b", "b*").contains(op.name())),
                            "disabled border must not paint a stroke, including a zero-width hairline");
                    assertPaintColorAt(paint, PAPER, List.of("f", "F", "f*"));
                    assertPaintedTextColor(paint, INK);
                    assertOperation(paint, "Tf", 12);
                }
            }
        }
    }

    @Test
    void noBorderAndNoBackgroundKeepTheIndependentBlackMarkInAllOnStates() throws IOException {
        for (var style : List.of(FormStyle.builder().color(RgbColor.BLACK).noBackground().noBorder().build(),
                FormStyle.builder().color(RgbColor.BLACK).noBackground().border(0, EDGE).build())) {
            try (var pdf = render(List.of(new DocumentExtras.CheckBox(0, new Rect(20, 20, 18, 18), "check", false, style),
                    new DocumentExtras.RadioGroup(0, new Rect(20, 60, 60, 18), "radio", List.of("A", "B", "C"), "B", style)))) {
                var form = pdf.getDocumentCatalog().getAcroForm();
                var check = assertInstanceOf(PDCheckBox.class, form.getField("check"));
                assertUnborderedMark(check.getWidgets().getFirst(), "Yes");
                var radio = assertInstanceOf(PDRadioButton.class, form.getField("radio"));
                for (int index = 0; index < radio.getWidgets().size(); index++) {
                    assertUnborderedMark(radio.getWidgets().get(index), List.of("A", "B", "C").get(index));
                }
                assertEquals("Off", check.getValue());
                assertEquals("B", radio.getValue());
                check.check();
                radio.setValue("C");
                assertEquals("Yes", check.getWidgets().getFirst().getAppearanceState().getName());
                assertEquals("C", radio.getWidgets().get(2).getAppearanceState().getName());
            }
        }
    }

    @Test
    void textAndChoiceAlignmentChangesTheActualAppearanceTextPosition() throws IOException {
        var fields = new ArrayList<DocumentExtras.FormField>();
        int row = 0;
        for (var alignment : FormStyle.Alignment.values()) {
            var style = FormStyle.builder().fontSize(12).color(INK).noBackground().noBorder().alignment(alignment).build();
            fields.add(new DocumentExtras.TextField(0, new Rect(20, 20 + row * 40, 120, 24),
                    "text" + alignment.name(), "Ada", false, List.of(), style));
            fields.add(new DocumentExtras.ChoiceField(1, new Rect(20, 20 + row * 40, 120, 24),
                    "choice" + alignment.name(), List.of("Ada", "Grace"), "Ada", List.of(), style));
            row++;
        }
        try (var pdf = render(fields)) {
            var form = pdf.getDocumentCatalog().getAcroForm();
            for (var prefix : List.of("text", "choice")) {
                double left = textOriginX(operations(form.getField(prefix + "LEFT").getWidgets().getFirst().getNormalAppearanceStream()));
                double center = textOriginX(operations(form.getField(prefix + "CENTER").getWidgets().getFirst().getNormalAppearanceStream()));
                double right = textOriginX(operations(form.getField(prefix + "RIGHT").getWidgets().getFirst().getNormalAppearanceStream()));
                assertTrue(left < center);
                assertTrue(center < right);
                assertEquals((left + right) / 2, center, 0.001);
                assertTrue(right < 120);
            }
        }
    }

    @Test
    void selectedAndUnselectedStatesShareBackgroundAndBorderButUseIndependentMarkColor() throws IOException {
        var style = FormStyle.builder().background(PAPER).border(2, EDGE).color(INK).build();
        try (var pdf = render(List.of(new DocumentExtras.CheckBox(0, new Rect(20, 20, 24, 24), "check", true, style),
                new DocumentExtras.RadioGroup(0, new Rect(20, 70, 80, 24), "radio", List.of("A", "B"), "A", style)))) {
            for (var field : pdf.getDocumentCatalog().getAcroForm().getFields()) {
                for (var widget : field.getWidgets()) {
                    var states = widget.getAppearance().getNormalAppearance().getSubDictionary();
                    assertEquals(2, states.size());
                    for (var entry : states.entrySet()) {
                        var ops = operations(entry.getValue());
                        assertOperation(ops, "rg", PAPER.red(), PAPER.green(), PAPER.blue());
                        assertOperation(ops, "RG", EDGE.red(), EDGE.green(), EDGE.blue());
                        assertOperation(ops, "w", 2);
                        if (!entry.getKey().equals(COSName.Off)) {
                            assertOperation(ops, "RG", INK.red(), INK.green(), INK.blue());
                            assertOperation(ops, "w", 1);
                            assertEquals(2, count(ops, "S"));
                        } else { assertEquals(1, count(ops, "S")); }
                    }
                }
            }
        }
    }

    @Test
    void adaptiveBorderUsesEachRadioWidgetAndMarkStrokeRemainsIndependent() throws IOException {
        var style = FormStyle.builder().adaptiveBorder(3, EDGE).build();
        try (var pdf = render(List.of(new DocumentExtras.CheckBox(0, new Rect(20, 20, 18, 18), "check", true, style),
                new DocumentExtras.RadioGroup(0, new Rect(20, 70, 12, 20), "radio", List.of("A", "B", "C"), "A", style),
                new DocumentExtras.CheckBox(0, new Rect(20, 110, 18, 18), "capped", true,
                        FormStyle.builder().adaptiveBorder(0.25, EDGE).build())))) {
            var form = pdf.getDocumentCatalog().getAcroForm();
            var check = form.getField("check").getWidgets().getFirst();
            assertOperation(operations(state(check, "Off")), "w", 1.8);
            var checkedOps = operations(state(check, "Yes"));
            assertOperation(checkedOps, "w", 1.8);
            assertOperation(checkedOps, "w", 1);
            var radio = form.getField("radio");
            for (var widget : radio.getWidgets()) {
                assertEquals(4, widget.getRectangle().getWidth());
                assertEquals(4, widget.getRectangle().getHeight());
                assertOperation(operations(state(widget, "Off")), "w", 0.4);
            }
            assertOperation(operations(state(form.getField("capped").getWidgets().getFirst(), "Off")), "w", 0.25);
        }
    }

    @Test
    void styledUnicodeFieldsUseOneFullFontAndRemainEditableAfterSaveAndReopen() throws IOException {
        var style = FormStyle.builder().fontSize(12).color(INK).background(PAPER).border(1, EDGE)
                .alignment(FormStyle.Alignment.CENTER).build();
        var markStyle = FormStyle.builder().color(INK).noBorder().noBackground().build();
        var fields = List.<DocumentExtras.FormField>of(
                new DocumentExtras.TextField(1, new Rect(20, 20, 200, 28), "text", "中文姓名", false, List.of(chinese), style),
                new DocumentExtras.ChoiceField(1, new Rect(20, 70, 200, 28), "choice", List.of("草稿", "审核", "完成"), "审核", List.of(chinese), style),
                new DocumentExtras.CheckBox(1, new Rect(20, 120, 20, 20), "check", true, markStyle),
                new DocumentExtras.RadioGroup(1, new Rect(20, 160, 90, 20), "radio", List.of("draft", "final"), "draft", markStyle));
        var changed = new ByteArrayOutputStream();
        try (var pdf = render(fields)) {
            var form = pdf.getDocumentCatalog().getAcroForm();
            var text = assertInstanceOf(PDTextField.class, form.getField("text"));
            var choice = assertInstanceOf(PDComboBox.class, form.getField("choice"));
            var textResource = text.getDefaultAppearance().split(" ")[0];
            assertEquals(textResource, choice.getDefaultAppearance().split(" ")[0]);
            var font = form.getDefaultResources().getFont(COSName.getPDFName(textResource.substring(1)));
            assertTrue(font.isEmbedded());
            assertFalse(font.getName().matches("[A-Z]{6}\\+.*"));
            text.setValue("新增测试文字");
            choice.setValue("完成");
            assertInstanceOf(PDCheckBox.class, form.getField("check")).unCheck();
            assertInstanceOf(PDRadioButton.class, form.getField("radio")).setValue("final");
            pdf.save(changed);
        }
        try (var pdf = Loader.loadPDF(changed.toByteArray())) {
            var form = pdf.getDocumentCatalog().getAcroForm();
            var text = assertInstanceOf(PDTextField.class, form.getField("text"));
            var choice = assertInstanceOf(PDComboBox.class, form.getField("choice"));
            assertEquals("新增测试文字", text.getValue());
            assertEquals(List.of("完成"), choice.getValue());
            assertEquals("中文姓名", text.getDefaultValue());
            assertEquals(List.of("审核"), choice.getDefaultValue());
            assertEquals("Off", form.getField("check").getValueAsString());
            assertEquals("final", form.getField("radio").getValueAsString());
            assertDefaultAppearance(text.getDefaultAppearance(), 12, INK);
            assertDefaultAppearance(choice.getDefaultAppearance(), 12, INK);
            assertEquals(1, text.getQ());
            assertEquals(1, choice.getQ());
            assertColor(PAPER, text.getWidgets().getFirst().getAppearanceCharacteristics().getBackground());
            assertPaintedTextColor(operations(text.getWidgets().getFirst().getNormalAppearanceStream()), INK);
            assertEquals(5, pdf.getPage(1).getAnnotations().size());
            assertTrue(pdf.getPage(0).getAnnotations().isEmpty());
            assertFalse(form.getNeedAppearances());
            for (var field : form.getFields()) {
                assertFalse(field.isReadOnly());
                for (var widget : field.getWidgets()) {
                    assertSame(pdf.getPage(1).getCOSObject(), widget.getPage().getCOSObject());
                    assertTrue(pdf.getPage(1).getAnnotations().stream().anyMatch(annotation -> annotation.getCOSObject() == widget.getCOSObject()));
                    assertNotNull(widget.getAppearance().getNormalAppearance());
                    if (field instanceof PDRadioButton radio) {
                        assertSame(radio.getCOSObject(), widget.getCOSObject().getDictionaryObject(COSName.PARENT));
                    } else { assertSame(field.getCOSObject(), widget.getCOSObject()); }
                }
            }
        }
    }

    @Test
    void fixedBorderAtHalfTheActualWidgetSizeFailsWithoutWritingOrClosingBorrowedOutput() {
        var style = FormStyle.builder().border(2, EDGE).build();
        var bounds = new Rect(20, 20, 20, 4);
        for (var field : List.<DocumentExtras.FormField>of(
                new DocumentExtras.TextField(0, bounds, "text", "", false, List.of(), style),
                new DocumentExtras.ChoiceField(0, bounds, "choice", List.of("A"), "A", List.of(), style),
                new DocumentExtras.CheckBox(0, bounds, "check", false, style),
                new DocumentExtras.RadioGroup(0, new Rect(20, 20, 8, 20), "radio", List.of("A", "B"), "A", style))) {
            assertGeometryFailure(field);
        }
    }

    @Test
    void unrepresentableStyleValuesFailWithoutSilentlyRoundingThemToDefaults() {
        for (var style : List.of(FormStyle.builder().fontSize(1e-100).build(),
                FormStyle.builder().fontSize(0.00001).build(),
                FormStyle.builder().fontSize(Double.MAX_VALUE).build(),
                FormStyle.builder().color(new RgbColor(1e-100, 0, 0)).build(),
                FormStyle.builder().background(new RgbColor(0, 1e-100, 0)).build(),
                FormStyle.builder().border(1e-100, EDGE).build(),
                FormStyle.builder().border(0.00001, EDGE).build(),
                FormStyle.builder().adaptiveBorder(1e-100, EDGE).build())) {
            assertGeometryFailure(new DocumentExtras.TextField(0, new Rect(20, 20, 100, 24), "text", "A", false, List.of(), style));
        }
    }

    @Test
    void appearancePrecisionFloorRejectsTinyMarksAndAdaptiveBordersButAcceptsItsEndpoint() throws IOException {
        var tiny = new Rect(20, 20, 0.0005, 0.0005);
        assertGeometryFailure(new DocumentExtras.CheckBox(0, tiny, "check", false,
                FormStyle.builder().noBackground().noBorder().build()));
        assertGeometryFailure(new DocumentExtras.RadioGroup(0, new Rect(20, 20, 0.001, 0.0005), "radio",
                List.of("A", "B"), "A", FormStyle.DEFAULT));
        assertGeometryFailure(new DocumentExtras.TextField(0, tiny, "text", "", false, List.of(),
                FormStyle.builder().adaptiveBorder(1, EDGE).build()));
        var endpoint = FormStyle.builder().fontSize(0.0001).border(0.0001, EDGE).build();
        try (var pdf = render(List.of(new DocumentExtras.TextField(0, new Rect(20, 20, 100, 24), "text", "A", false, List.of(), endpoint)))) {
            var text = assertInstanceOf(PDTextField.class, pdf.getDocumentCatalog().getAcroForm().getField("text"));
            assertEquals(0.0001, text.getWidgets().getFirst().getBorderStyle().getWidth(), 0.0000000001);
            assertOperation(operations(text.getWidgets().getFirst().getNormalAppearanceStream()), "Tf", 0.0001);
        }
    }

    @Test
    void stylesPreserveInteractionAndFontBudgetsBeforeAnyBytesAreWritten() throws IOException {
        var style = FormStyle.builder().fontSize(12).color(INK).border(1, EDGE).build();
        var fields = List.<DocumentExtras.FormField>of(
                new DocumentExtras.TextField(0, new Rect(20, 20, 100, 24), "text", "中文", false, List.of(chinese), style),
                new DocumentExtras.ChoiceField(0, new Rect(20, 60, 100, 24), "choice", List.of("草稿", "完成"), "完成", List.of(chinese), style));
        var exact = PdfLimits.DEFAULT.toBuilder().maxFontBytes(chinese.byteSize()).maxInteractions(2).build();
        var output = new TrackingOutput();
        new PdfBackend(exact).write(document(fields), output);
        assertTrue(output.size() > 0);
        assertFalse(output.closed);
        var lowFont = exact.toBuilder().maxFontBytes(chinese.byteSize() - 1).build();
        var lowInteractions = exact.toBuilder().maxInteractions(1).build();
        var lowText = exact.toBuilder().maxInteractionCodePoints(1).build();
        for (var limits : List.of(lowFont, lowInteractions, lowText)) {
            var failedOutput = new TrackingOutput();
            assertEquals(PdfErrorCode.BUDGET_EXCEEDED,
                    assertThrows(PdfRenderException.class, () -> new PdfBackend(limits).write(document(fields), failedOutput)).code());
            assertEquals(0, failedOutput.size());
            assertFalse(failedOutput.closed);
        }
    }

    private static void assertLegacyMark(PDAnnotationWidget widget, String onName) throws IOException {
        var normal = widget.getAppearance().getNormalAppearance();
        assertEquals(2, normal.getSubDictionary().size());
        var off = operations(state(widget, "Off"));
        var on = operations(state(widget, onName));
        for (var ops : List.of(off, on)) {
            assertOperation(ops, "rg", 1, 1, 1);
            assertOperation(ops, "RG", 0.2, 0.3, 0.4);
            assertOperation(ops, "w", 1);
            assertEquals(1, count(ops, "f"));
        }
        assertEquals(1, count(off, "S"));
        assertEquals(2, count(on, "S"));
        assertEquals(1, count(on, "m"));
        assertEquals(2, count(on, "l"));
    }

    private static void assertUnborderedMark(PDAnnotationWidget widget, String onName) throws IOException {
        var off = operations(state(widget, "Off"));
        var on = operations(state(widget, onName));
        assertEquals(0, count(off, "S"));
        assertEquals(0, count(off, "f"));
        assertEquals(0, count(on, "f"));
        assertEquals(1, count(on, "S"));
        assertEquals(1, count(on, "m"));
        assertEquals(2, count(on, "l"));
        assertOperation(on, "RG", 0, 0, 0);
        assertOperation(on, "w", 1);
    }

    private static void assertGeometryFailure(DocumentExtras.FormField field) {
        var output = new TrackingOutput();
        var failure = assertThrows(PdfRenderException.class, () -> new PdfBackend().write(document(List.of(field)), output));
        assertEquals(PdfErrorCode.INVALID_GEOMETRY, failure.code());
        assertEquals(0, failure.pageIndex());
        assertEquals(0, output.size());
        assertFalse(output.closed);
    }

    private static void assertDefaultAppearance(String actual, double size, RgbColor color) {
        var parts = actual.trim().split("\\s+");
        assertEquals(7, parts.length);
        assertTrue(parts[0].startsWith("/"));
        assertEquals(size, Double.parseDouble(parts[1]), 0.000001);
        assertEquals("Tf", parts[2]);
        assertEquals(color.red(), Double.parseDouble(parts[3]), 0.000001);
        assertEquals(color.green(), Double.parseDouble(parts[4]), 0.000001);
        assertEquals(color.blue(), Double.parseDouble(parts[5]), 0.000001);
        assertEquals("rg", parts[6]);
        for (int index : new int[]{1, 3, 4, 5}) {
            assertFalse(parts[index].contains("E"));
            assertFalse(parts[index].contains("e"));
        }
    }

    private static void assertColor(RgbColor expected, PDColor actual) {
        assertNotNull(actual);
        var components = actual.getComponents();
        assertEquals(3, components.length);
        assertEquals(expected.red(), components[0], 0.000001);
        assertEquals(expected.green(), components[1], 0.000001);
        assertEquals(expected.blue(), components[2], 0.000001);
    }

    private static PDAppearanceStream state(PDAnnotationWidget widget, String name) {
        var result = widget.getAppearance().getNormalAppearance().getSubDictionary().get(COSName.getPDFName(name));
        assertNotNull(result);
        assertEquals(widget.getRectangle().getWidth(), result.getBBox().getWidth());
        assertEquals(widget.getRectangle().getHeight(), result.getBBox().getHeight());
        return result;
    }

    private static List<Operation> operations(PDAppearanceStream stream) throws IOException {
        assertNotNull(stream);
        var result = new ArrayList<Operation>();
        var operands = new ArrayList<COSBase>();
        for (var token : new PDFStreamParser(stream).parse()) {
            if (token instanceof Operator operator) {
                result.add(new Operation(operator.getName(), List.copyOf(operands)));
                operands.clear();
            } else { operands.add(assertInstanceOf(COSBase.class, token)); }
        }
        assertTrue(operands.isEmpty());
        return List.copyOf(result);
    }

    private static long count(List<Operation> operations, String name) {
        return operations.stream().filter(op -> op.name().equals(name)).count();
    }

    private static double textOriginX(List<Operation> operations) {
        double x = 0;
        for (var operation : operations) {
            if (operation.name().equals("Tm")) {
                x = assertInstanceOf(COSNumber.class, operation.operands().get(4)).floatValue();
            } else if (operation.name().equals("Td") || operation.name().equals("TD")) {
                x += assertInstanceOf(COSNumber.class, operation.operands().getFirst()).floatValue();
            } else if (operation.name().equals("Tj") || operation.name().equals("TJ")) { return x; }
        }
        throw new AssertionError("missing visible appearance text");
    }

    private static void assertPaintedTextColor(List<Operation> operations, RgbColor expected) {
        assertPaintColorAt(operations, expected, List.of("Tj", "TJ"));
    }

    private static void assertPaintColorAt(List<Operation> operations, RgbColor expected, List<String> paintNames) {
        var states = new ArrayDeque<PaintColor>();
        var current = new PaintColor("DeviceGray", List.of(0.0));
        boolean painted = false;
        for (var operation : operations) {
            switch (operation.name()) {
                case "q" -> states.push(current);
                case "Q" -> current = states.pop();
                case "cs" -> current = new PaintColor(assertInstanceOf(COSName.class,
                        operation.operands().getFirst()).getName(), List.of());
                case "rg", "g", "k", "sc", "scn" -> {
                    String space = switch (operation.name()) {
                        case "rg" -> "DeviceRGB";
                        case "g" -> "DeviceGray";
                        case "k" -> "DeviceCMYK";
                        default -> current.space();
                    };
                    var values = operation.operands().stream()
                            .map(value -> (double) assertInstanceOf(COSNumber.class, value).floatValue()).toList();
                    current = new PaintColor(space, values);
                }
                default -> { }
            }
            if (paintNames.contains(operation.name())) {
                painted = true;
                assertEquals("DeviceRGB", current.space());
                assertEquals(3, current.values().size());
                assertEquals(expected.red(), current.values().get(0), 0.000001);
                assertEquals(expected.green(), current.values().get(1), 0.000001);
                assertEquals(expected.blue(), current.values().get(2), 0.000001);
            }
        }
        assertTrue(painted, "missing expected appearance paint operation");
    }

    private static void assertOperation(List<Operation> operations, String name, double... expected) {
        assertTrue(operations.stream().anyMatch(op -> {
            if (!op.name().equals(name) || op.operands().size() < expected.length) { return false; }
            int start = op.operands().size() - expected.length;
            for (int index = 0; index < expected.length; index++) {
                if (!(op.operands().get(start + index) instanceof COSNumber number)
                        || Math.abs(number.floatValue() - expected[index]) > 0.000001) { return false; }
            }
            return true;
        }), "missing expected " + name + " operation");
    }

    private static DisplayDocument document(List<DocumentExtras.FormField> fields) {
        var page = new DisplayPage(new PageSize(300, 300), List.of());
        return new DisplayDocument(DocumentMetadata.EMPTY, List.of(page, page),
                new DocumentExtras(List.of(), List.of(), List.of(), fields));
    }

    private static PDDocument render(List<DocumentExtras.FormField> fields) throws IOException {
        var output = new TrackingOutput();
        new PdfBackend().write(document(fields), output);
        assertFalse(output.closed);
        return Loader.loadPDF(output.toByteArray());
    }

    private record Operation(String name, List<COSBase> operands) { }
    private record PaintColor(String space, List<Double> values) { }

    private static final class TrackingOutput extends ByteArrayOutputStream {
        private boolean closed;
        @Override public void close() { closed = true; }
    }
}
