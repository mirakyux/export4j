// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.io.ByteArrayInputStream;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceCharacteristicsDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceEntry;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.pdmodel.interactive.form.PDVariableText;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.FormStyle;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;

/** Internal conversion of resolved interactions; never makes layout decisions or installs scripts. */
final class PdfExtrasWriter {
    private PdfExtrasWriter() {
    }

    static long preflight(DocumentExtras extras, PdfLimits limits, java.util.Set<String> fontIds) throws PdfRenderException {
        long count = extras.bookmarks().size() + (long) extras.links().size()
                + extras.comments().size() + extras.fields().size() + extras.externalLinks().size();
        long text = 0;
        long fontBytes = 0;
        for (var link : extras.externalLinks()) { text += codePoints(link.destination().uri()); }
        for (var bookmark : extras.bookmarks()) {
            text += codePoints(bookmark.title());
        }
        for (var comment : extras.comments()) {
            text += codePoints(comment.author()) + codePoints(comment.text());
        }
        for (var field : extras.fields()) {
            text += codePoints(field.name());
            if (field instanceof DocumentExtras.TextField value) {
                text += codePoints(value.value());
            }
            if (field instanceof DocumentExtras.ChoiceField choice) {
                for (String option : choice.options()) {
                    text += codePoints(option);
                }
            }
            if (field instanceof DocumentExtras.RadioGroup radio) {
                count += radio.options().size();
                for (String option : radio.options()) {
                    text += codePoints(option);
                }
            }
            var fonts = switch (field) {
                case DocumentExtras.TextField value -> value.fonts();
                case DocumentExtras.ChoiceField choice -> choice.fonts();
                default -> List.<com.mirakyux.export4j.font.FontData>of();
            };
            for (var font : fonts) {
                if (font.cffOutlines()) {
                    throw new PdfRenderException(PdfErrorCode.UNSUPPORTED_TEXT, field.pageIndex(), -1,
                            "editable PDF fields require a TrueType font; CFF body text uses outlines");
                }
                if (fontIds.add(font.id())) {
                    fontBytes += font.byteSize();
                }
            }
        }
        if (count > limits.maxInteractions() || text > limits.maxInteractionCodePoints() || fontBytes > limits.maxFontBytes()) {
            throw new PdfRenderException(PdfErrorCode.BUDGET_EXCEEDED, -1, -1,
                    "PDF interaction budget exceeded");
        }
        return fontBytes;
    }

    private static long codePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    static void write(PDDocument pdf, DocumentExtras extras) throws IOException {
        for (var link : extras.externalLinks()) {
            var page = pdf.getPage(link.pageIndex());
            var annotation = new PDAnnotationLink();
            annotation.setRectangle(rectangle(pdf, link.pageIndex(), link.bounds()));
            var action = new org.apache.pdfbox.pdmodel.interactive.action.PDActionURI();
            action.setURI(link.destination().uri());
            annotation.setAction(action);
            var border = new COSArray(); border.add(org.apache.pdfbox.cos.COSInteger.ZERO); border.add(org.apache.pdfbox.cos.COSInteger.ZERO); border.add(org.apache.pdfbox.cos.COSInteger.ZERO);
            annotation.setBorder(border);
            page.getAnnotations().add(annotation);
        }
        if (!extras.bookmarks().isEmpty()) {
            var outline = new PDDocumentOutline();
            pdf.getDocumentCatalog().setDocumentOutline(outline);
            List<PDOutlineNode> parents = new ArrayList<>();
            parents.add(outline);
            for (var bookmark : extras.bookmarks()) {
                while (parents.size() > bookmark.level()) {
                    parents.removeLast();
                }
                var item = new PDOutlineItem();
                item.setTitle(bookmark.title());
                item.setDestination(destination(pdf, bookmark.pageIndex(), bookmark.destination()));
                parents.getLast().addLast(item);
                parents.add(item);
            }
            outline.openNode();
        }
        for (var link : extras.links()) {
            var annotation = new PDAnnotationLink();
            annotation.setRectangle(rectangle(pdf, link.pageIndex(), link.bounds()));
            var border = new PDBorderStyleDictionary();
            border.setWidth(0);
            annotation.setBorderStyle(border);
            annotation.setDestination(destination(pdf, link.targetPageIndex(), link.destination()));
            pdf.getPage(link.pageIndex()).getAnnotations().add(annotation);
        }
        for (var comment : extras.comments()) {
            var annotation = new PDAnnotationText();
            annotation.setRectangle(rectangle(pdf, comment.pageIndex(), comment.bounds()));
            annotation.setContents(comment.text());
            annotation.setTitlePopup(comment.author());
            annotation.setName(PDAnnotationText.NAME_COMMENT);
            annotation.setOpen(false);
            annotation.setPage(pdf.getPage(comment.pageIndex()));
            annotation.constructAppearances(pdf);
            pdf.getPage(comment.pageIndex()).getAnnotations().add(annotation);
        }
        if (!extras.fields().isEmpty()) {
            forms(pdf, extras.fields());
        }
    }

    private static PDPageXYZDestination destination(PDDocument pdf, int pageIndex, Point point)
            throws PdfRenderException {
        var result = new PDPageXYZDestination();
        result.setPage(pdf.getPage(pageIndex));
        result.setZoom(0);
        COSArray coordinates = result.getCOSObject();
        coordinates.set(2, new COSFloat(PdfBackend.checkedFloat(point.x(), pageIndex, -1)));
        coordinates.set(3, new COSFloat(PdfBackend.checkedFloat(
                pdf.getPage(pageIndex).getMediaBox().getHeight() - point.y(), pageIndex, -1)));
        return result;
    }

    private static PDRectangle rectangle(PDDocument pdf, int pageIndex, Rect bounds) throws PdfRenderException {
        var result = new PDRectangle(PdfBackend.checkedFloat(bounds.x(), pageIndex, -1),
                PdfBackend.checkedFloat(pdf.getPage(pageIndex).getMediaBox().getHeight()
                        - bounds.y() - bounds.height(), pageIndex, -1),
                PdfBackend.checkedFloat(bounds.width(), pageIndex, -1),
                PdfBackend.checkedFloat(bounds.height(), pageIndex, -1));
        if (result.getWidth() <= 0 || result.getHeight() <= 0) {
            throw new PdfRenderException(PdfErrorCode.INVALID_GEOMETRY, pageIndex, -1,
                    "interaction rectangle collapses at PDF numeric precision");
        }
        return result;
    }

    private static void forms(PDDocument pdf, List<DocumentExtras.FormField> fields) throws IOException {
        var form = new PDAcroForm(pdf);
        var resources = new PDResources();
        resources.put(COSName.getPDFName("Helv"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
        form.setDefaultResources(resources);
        form.setDefaultAppearance("/Helv 10 Tf 0 g");
        form.setNeedAppearances(false);
        pdf.getDocumentCatalog().setAcroForm(form);
        var embedded = new HashMap<String, COSName>();
        for (var source : fields) {
            if (source instanceof DocumentExtras.RadioGroup radio) {
                radio(pdf, form, radio);
                continue;
            }
            PDTerminalField field = switch (source) {
                case DocumentExtras.TextField ignored -> new PDTextField(form);
                case DocumentExtras.ChoiceField ignored -> new PDComboBox(form);
                default -> new PDCheckBox(form);
            };
            field.setPartialName(source.name());
            var widget = field.getWidgets().getFirst();
            widget.setRectangle(rectangle(pdf, source.pageIndex(), source.bounds()));
            PDPage page = pdf.getPage(source.pageIndex());
            widget.setPage(page);
            widget.setPrinted(true);
            // A single-widget terminal field is one canonical dictionary in both trees.
            form.getFields().add(field);
            page.getAnnotations().add(widget);
            switch (source) {
                case DocumentExtras.TextField value -> {
                    var text = (PDTextField) field;
                    variableStyle(text, appearanceFont(pdf, resources, embedded, value.fonts()), value.style(), value.pageIndex());
                    var border = new PDBorderStyleDictionary();
                    border.setWidth(value.style().isExplicit(FormStyle.Property.BORDER) ? 0
                            : legacyStroke(widget.getRectangle(), 0.7f, value.pageIndex()));
                    widget.setBorderStyle(border);
                    var appearance = new PDAppearanceCharacteristicsDictionary(new COSDictionary());
                    appearance.setBackground(new PDColor(new float[] {1, 1, 1}, PDDeviceRGB.INSTANCE));
                    appearance.setBorderColour(new PDColor(new float[] {0.6f, 0.7f, 0.8f}, PDDeviceRGB.INSTANCE));
                    widget.setAppearanceCharacteristics(appearance);
                    widgetStyle(widget, value.style(), value.pageIndex());
                    text.setMultiline(value.multiline());
                    text.setDefaultValue(value.value());
                    text.setValue(value.value());
                }
                case DocumentExtras.CheckBox value -> {
                    var check = (PDCheckBox) field;
                    widgetStyle(widget, value.style(), value.pageIndex());
                    checkAppearance(pdf, widget, value.style(), value.pageIndex());
                    if (value.checked()) {
                        check.check();
                    } else {
                        check.unCheck();
                    }
                    check.getCOSObject().setItem(COSName.DV, COSName.getPDFName(value.checked() ? "Yes" : "Off"));
                }
                case DocumentExtras.ChoiceField value -> {
                    var choice = (PDComboBox) field;
                    variableStyle(choice, appearanceFont(pdf, resources, embedded, value.fonts()), value.style(), value.pageIndex());
                    widgetStyle(widget, value.style(), value.pageIndex());
                    choice.setOptions(value.options());
                    choice.setDefaultValue(value.value());
                    choice.setValue(value.value());
                }
                case DocumentExtras.RadioGroup ignored -> throw new IllegalStateException("radio handled separately");
            }
        }
    }

    private static void variableStyle(PDVariableText field, String font, FormStyle style, int page)
            throws PdfRenderException {
        String size = style.isExplicit(FormStyle.Property.FONT_SIZE)
                ? number(appearanceLength(style.fontSize(), page), page) : "10";
        String foreground = "0 g";
        if (style.isExplicit(FormStyle.Property.COLOR)) {
            var color = style.color();
            foreground = number(color.red(), page) + " " + number(color.green(), page)
                    + " " + number(color.blue(), page) + " rg";
        }
        field.setDefaultAppearance(font + " " + size + " Tf " + foreground);
        if (style.isExplicit(FormStyle.Property.ALIGNMENT)) {
            field.setQ(switch (style.alignment()) {
                case LEFT -> PDVariableText.QUADDING_LEFT;
                case CENTER -> PDVariableText.QUADDING_CENTERED;
                case RIGHT -> PDVariableText.QUADDING_RIGHT;
            });
        }
    }

    /** Applies only declared widget properties, preserving absent MK/BS on legacy choices/marks. */
    private static void widgetStyle(PDAnnotationWidget widget, FormStyle style, int page) throws PdfRenderException {
        boolean background = style.isExplicit(FormStyle.Property.BACKGROUND);
        boolean border = style.isExplicit(FormStyle.Property.BORDER);
        if (!background && !border) { return; }
        var appearance = widget.getAppearanceCharacteristics();
        if (appearance == null) { appearance = new PDAppearanceCharacteristicsDictionary(new COSDictionary()); }
        if (background) {
            if (style.backgroundEnabled()) {
                appearance.setBackground(color(style.background(), page));
            } else {
                appearance.getCOSObject().removeItem(COSName.BG);
            }
        }
        if (border) {
            var dictionary = new PDBorderStyleDictionary();
            float width = borderWidth(style, widget.getRectangle(), page);
            dictionary.setWidth(width);
            widget.setBorderStyle(dictionary);
            // PDFBox treats MK/BC as a one-point border unless BS/W is strictly positive.
            // Keep explicit BS/W=0, but omit BC so zero-width borders remain invisible.
            if (width == 0) {
                appearance.getCOSObject().removeItem(COSName.BC);
            } else {
                appearance.setBorderColour(color(style.borderColor(), page));
            }
        }
        widget.setAppearanceCharacteristics(appearance);
    }

    private static float borderWidth(FormStyle style, PDRectangle rect, int page) throws PdfRenderException {
        if (style.borderMode() == FormStyle.BorderMode.NONE || style.borderWidth() == 0) { return 0; }
        double side = Math.min(rect.getWidth(), rect.getHeight());
        double value = style.borderMode() == FormStyle.BorderMode.ADAPTIVE
                ? Math.min(style.borderWidth(), side / 10) : style.borderWidth();
        float resolved = appearanceLength(value, page);
        if (value * 2 >= side || (double) resolved * 2 >= side) {
            throw new PdfRenderException(PdfErrorCode.INVALID_GEOMETRY, page, -1,
                    "form border must leave a positive inner widget rectangle");
        }
        return resolved;
    }

    private static float legacyStroke(PDRectangle rect, float maximum, int page) throws PdfRenderException {
        float value = Math.min(maximum, Math.min(rect.getWidth(), rect.getHeight()) / 10);
        return appearanceLength(value, page);
    }

    /** PDFBox's appearance content streams retain four fractional decimal digits. */
    private static float appearanceLength(double value, int page) throws PdfRenderException {
        float resolved = PdfBackend.checkedFloat(value, page, -1);
        if (resolved < 0.0001f) {
            throw new PdfRenderException(PdfErrorCode.INVALID_GEOMETRY, page, -1,
                    "positive form appearance dimensions must be at least 0.0001 points at PDF precision");
        }
        return resolved;
    }

    private static PDColor color(RgbColor value, int page) throws PdfRenderException {
        return new PDColor(channels(value, page), PDDeviceRGB.INSTANCE);
    }

    private static float[] channels(RgbColor value, int page) throws PdfRenderException {
        return new float[] {PdfBackend.checkedFloat(value.red(), page, -1),
                PdfBackend.checkedFloat(value.green(), page, -1), PdfBackend.checkedFloat(value.blue(), page, -1)};
    }

    /** PDF numbers cannot use Java's scientific notation. Serialize the same checked float used by AP. */
    private static String number(double value, int page) throws PdfRenderException {
        float resolved = PdfBackend.checkedFloat(value, page, -1);
        return new BigDecimal(Float.toString(resolved)).stripTrailingZeros().toPlainString();
    }

    private static String appearanceFont(PDDocument pdf, PDResources resources, HashMap<String, COSName> embedded,
            List<com.mirakyux.export4j.font.FontData> fonts) throws IOException {
        if (fonts.isEmpty()) {
            return "/Helv";
        }
        var data = fonts.getFirst();
        var name = embedded.get(data.id());
        if (name == null) {
            // Full embedding is essential: a field may be edited to characters absent from its initial value.
            PDFont font = PDType0Font.load(pdf, new ByteArrayInputStream(data.bytes()), false);
            name = COSName.getPDFName("FormFont" + embedded.size());
            resources.put(name, font);
            embedded.put(data.id(), name);
        }
        return "/" + name.getName();
    }

    private static void radio(PDDocument pdf, PDAcroForm form, DocumentExtras.RadioGroup source) throws IOException {
        var radio = new PDRadioButton(form);
        radio.setPartialName(source.name());
        List<PDAnnotationWidget> widgets = new ArrayList<>();
        double step = source.bounds().width() / source.options().size();
        double side = Math.min(step, source.bounds().height());
        for (int index = 0; index < source.options().size(); index++) {
            var widget = new PDAnnotationWidget();
            widget.setParent(radio);
            widget.setPage(pdf.getPage(source.pageIndex()));
            widget.setPrinted(true);
            widget.setRectangle(rectangle(pdf, source.pageIndex(), new Rect(source.bounds().x() + index * step,
                    source.bounds().y(), side, side)));
            widgetStyle(widget, source.style(), source.pageIndex());
            var states = new COSDictionary();
            states.setItem(COSName.Off, checkStream(pdf, widget.getRectangle(), false, source.style(), source.pageIndex()));
            states.setItem(COSName.getPDFName(source.options().get(index)),
                    checkStream(pdf, widget.getRectangle(), true, source.style(), source.pageIndex()));
            var appearance = new PDAppearanceDictionary();
            appearance.setNormalAppearance(new PDAppearanceEntry(states));
            widget.setAppearance(appearance);
            widgets.add(widget);
            pdf.getPage(source.pageIndex()).getAnnotations().add(widget);
        }
        radio.setWidgets(widgets);
        form.getFields().add(radio);
        radio.setValue(source.value());
        radio.getCOSObject().setItem(COSName.DV, COSName.getPDFName(source.value()));
    }

    private static void checkAppearance(PDDocument pdf, PDAnnotationWidget widget, FormStyle style, int page) throws IOException {
        var states = new COSDictionary();
        states.setItem(COSName.Off, checkStream(pdf, widget.getRectangle(), false, style, page));
        states.setItem(COSName.getPDFName("Yes"), checkStream(pdf, widget.getRectangle(), true, style, page));
        var appearance = new PDAppearanceDictionary();
        appearance.setNormalAppearance(new PDAppearanceEntry(states));
        widget.setAppearance(appearance);
    }

    private static PDAppearanceStream checkStream(PDDocument pdf, PDRectangle rect, boolean checked,
            FormStyle style, int page) throws IOException {
        var stream = new PDAppearanceStream(pdf);
        float width = rect.getWidth();
        float height = rect.getHeight();
        stream.setBBox(new PDRectangle(width, height));
        stream.setResources(new PDResources());
        try (var canvas = new PDPageContentStream(pdf, stream)) {
            boolean background = !style.isExplicit(FormStyle.Property.BACKGROUND) || style.backgroundEnabled();
            if (background) {
                float[] fill = style.isExplicit(FormStyle.Property.BACKGROUND)
                        ? channels(style.background(), page) : new float[] {1, 1, 1};
                canvas.setNonStrokingColor(fill[0], fill[1], fill[2]);
                canvas.addRect(0, 0, width, height);
                canvas.fill();
            }
            float border = style.isExplicit(FormStyle.Property.BORDER)
                    ? borderWidth(style, rect, page) : legacyStroke(rect, 1, page);
            if (border > 0) {
                float[] stroke = style.isExplicit(FormStyle.Property.BORDER)
                        ? channels(style.borderColor(), page) : new float[] {0.2f, 0.3f, 0.4f};
                canvas.setStrokingColor(stroke[0], stroke[1], stroke[2]);
                canvas.setLineWidth(border);
                float insetX = Math.max(width * 0.05f, border / 2);
                float insetY = Math.max(height * 0.05f, border / 2);
                // Keep the legacy 90% rectangle bit-for-bit unless a fixed stroke needs more inset.
                canvas.addRect(insetX, insetY, insetX == width * 0.05f ? width * 0.9f : width - 2 * insetX,
                        insetY == height * 0.05f ? height * 0.9f : height - 2 * insetY);
                canvas.stroke();
            }
            if (checked) {
                if (border == 0 || style.isExplicit(FormStyle.Property.COLOR) || style.isExplicit(FormStyle.Property.BORDER)) {
                    float[] mark = style.isExplicit(FormStyle.Property.COLOR)
                            ? channels(style.color(), page) : new float[] {0.2f, 0.3f, 0.4f};
                    canvas.setStrokingColor(mark[0], mark[1], mark[2]);
                    canvas.setLineWidth(legacyStroke(rect, 1, page));
                }
                canvas.moveTo(width * 0.2f, height * 0.5f);
                canvas.lineTo(width * 0.42f, height * 0.25f);
                canvas.lineTo(width * 0.82f, height * 0.8f);
                canvas.stroke();
            }
        }
        return stream;
    }
}
