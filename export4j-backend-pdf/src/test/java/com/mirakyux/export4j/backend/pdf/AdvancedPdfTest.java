// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;

class AdvancedPdfTest {
    @Test
    void formAndBodyFontsShareOneDeduplicatedBudgetBeforeOutput() throws IOException {
        com.mirakyux.export4j.font.FontFace latin;
        com.mirakyux.export4j.font.FontFace chinese;
        try (var input = AdvancedPdfTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            latin = TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
        try (var input = AdvancedPdfTest.class.getResourceAsStream("/fonts/LXGWWenKaiTC-Regular.ttf")) {
            chinese = TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
        var builder = new DisplayListBuilder(new PageSize(300, 200));
        var text = new com.mirakyux.export4j.text.ParagraphLayouter().layout("A", List.of(latin), 100,
                com.mirakyux.export4j.text.ParagraphStyle.of(10));
        com.mirakyux.export4j.graphics.core.TextLayoutPainter.draw(builder, text,
                new com.mirakyux.export4j.graphics.Point(10, 10), com.mirakyux.export4j.graphics.RgbColor.BLACK);
        var extras = new DocumentExtras(List.of(), List.of(), List.of(), List.of(
                new DocumentExtras.TextField(0, new Rect(10, 40, 100, 24), "name", "中", false, List.of(chinese.data()))));
        var defaults = PdfLimits.DEFAULT;
        var limits = new PdfLimits(defaults.maxPages(), defaults.maxCommands(), defaults.maxStateDepth(),
                defaults.maxImagePixels(), defaults.maxTextCodePoints(), defaults.maxPathSegments(), chinese.data().byteSize());
        var bytes = new ByteArrayOutputStream();
        var failure = assertThrows(PdfRenderException.class, () -> new PdfBackend(limits).write(
                new DisplayDocument(DocumentMetadata.EMPTY, List.of(builder.build()), extras), bytes));
        assertEquals(PdfErrorCode.BUDGET_EXCEEDED, failure.code());
        assertEquals(0, bytes.size());

        var shared = new DocumentExtras(List.of(), List.of(), List.of(), List.of(
                new DocumentExtras.TextField(0, new Rect(10, 40, 100, 24), "name", "B", false, List.of(latin.data()))));
        var single = new PdfLimits(defaults.maxPages(), defaults.maxCommands(), defaults.maxStateDepth(),
                defaults.maxImagePixels(), defaults.maxTextCodePoints(), defaults.maxPathSegments(), latin.data().byteSize());
        new PdfBackend(single).write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(builder.build()), shared), bytes);
        assertTrue(bytes.size() > 0);
    }

    @Test
    void unicodeFieldsEmbedFullFontAndRemainEditableAfterSaveAndReopen() throws IOException {
        com.mirakyux.export4j.font.FontData font;
        try (var input = AdvancedPdfTest.class.getResourceAsStream("/fonts/LXGWWenKaiTC-Regular.ttf")) {
            font = TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES).data();
        }
        var extras = new DocumentExtras(List.of(), List.of(), List.of(), List.of(
                new DocumentExtras.TextField(0, new Rect(20, 20, 200, 24), "name", "中文姓名", false, List.of(font)),
                new DocumentExtras.ChoiceField(0, new Rect(20, 60, 200, 24), "choice", List.of("草稿", "审核", "完成"), "审核", List.of(font)),
                new DocumentExtras.RadioGroup(0, new Rect(20, 100, 160, 20), "mode", List.of("draft", "final"), "draft")));
        var bytes = new ByteArrayOutputStream();
        new PdfBackend().write(new DisplayDocument(new DocumentMetadata("Forms", "Export4j"),
                List.of(new DisplayPage(new PageSize(300, 200), List.of())), extras), bytes);
        var changed = new ByteArrayOutputStream();
        try (var pdf = Loader.loadPDF(bytes.toByteArray())) {
            var form = pdf.getDocumentCatalog().getAcroForm();
            assertFalse(form.getNeedAppearances());
            var text = (PDTextField) form.getField("name");
            assertEquals("中文姓名", text.getValue());
            assertNotNull(text.getWidgets().getFirst().getNormalAppearanceStream());
            String resourceName = text.getDefaultAppearance().split(" ")[0].substring(1);
            var embedded = form.getDefaultResources().getFont(COSName.getPDFName(resourceName));
            assertFalse(embedded.getName().matches("[A-Z]{6}\\+.*"));
            text.setValue("新增测试文字");
            var choice = (PDComboBox) form.getField("choice");
            assertEquals("审核", choice.getValue().getFirst());
            choice.setValue("完成");
            var radio = (PDRadioButton) form.getField("mode");
            assertEquals(2, radio.getWidgets().size());
            assertEquals("draft", radio.getValue());
            for (var widget : radio.getWidgets()) {
                assertSame(radio.getCOSObject(), widget.getCOSObject().getDictionaryObject(COSName.PARENT));
                assertNotNull(widget.getAppearance().getNormalAppearance());
            }
            radio.setValue("final");
            pdf.save(changed);
        }
        try (var pdf = Loader.loadPDF(changed.toByteArray())) {
            var form = pdf.getDocumentCatalog().getAcroForm();
            assertEquals("新增测试文字", ((PDTextField) form.getField("name")).getValue());
            assertEquals("完成", ((PDComboBox) form.getField("choice")).getValue().getFirst());
            assertEquals("final", ((PDRadioButton) form.getField("mode")).getValue());
            assertEquals(4, pdf.getPage(0).getAnnotations().size());
            assertTrue(form.getFields().stream().noneMatch(org.apache.pdfbox.pdmodel.interactive.form.PDField::isReadOnly));
        }
    }

    @Test
    void transparentRasterProducesPdfSoftMask() throws IOException {
        var drawing = new DisplayListBuilder(new PageSize(100, 100));
        drawing.drawImage(new RgbImage(1, 1, new byte[] {(byte) 255, 0, 0}, new byte[] {(byte) 128}), new Rect(10, 10, 50, 50));
        var bytes = new ByteArrayOutputStream();
        new PdfBackend().write(new DisplayDocument(new DocumentMetadata("Alpha", "Export4j"), List.of(drawing.build())), bytes);
        try (var pdf = Loader.loadPDF(bytes.toByteArray())) {
            var resources = pdf.getPage(0).getResources();
            var image = (org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject) resources.getXObject(resources.getXObjectNames().iterator().next());
            assertNotNull(image.getSoftMask());
            assertEquals(128, image.getSoftMask().getImage().getRaster().getSample(0, 0, 0));
        }
    }
}
