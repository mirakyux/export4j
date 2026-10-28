// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDChoice;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.pdmodel.interactive.form.PDVariableText;

/** Shared measurements and drawing for concrete field appearances. No implicit clipping or auto-size. */
final class PdfFieldAppearance {
    record Run(String text, PDType0Font font, double size, String color, boolean underline) { }
    private record Glyph(String text, Run run, double width) { }
    private record Line(List<Glyph> glyphs, double width, double ascent, double descent) { double height() { return ascent - descent; } }
    private PdfFieldAppearance() { }
    static boolean fits(PDVariableText field, List<Run> runs, PdfLimits limits) throws IOException {
        for (var widget : field.getWidgets()) { if (layout(field, widget, runs, limits) == null) { return false; } } return true;
    }
    static void paint(PDDocument document, PDVariableText field, List<Run> runs, PdfLimits limits) throws IOException {
        long encoded = 0;
        for (var widget : field.getWidgets()) {
            ExecutionContext.checkpoint(); var lines = layout(field, widget, runs, limits);
            if (lines == null) { throw new IllegalArgumentException("PDF field value exceeds its appearance box"); }
            var rectangle = widget.getRectangle(); double w = rectangle.getWidth(), h = rectangle.getHeight(), inset = inset(widget);
            var resources = new PDResources(); var appearance = new PDAppearanceStream(document); appearance.setResources(resources); appearance.setBBox(new PDRectangle((float) w, (float) h));
            var commands = new StringBuilder("q\n"); var characteristics = widget.getAppearanceCharacteristics();
            if (characteristics != null && characteristics.getBackground() != null) { commands.append(color(characteristics.getBackground().getComponents(), false)).append("0 0 ").append(n(w)).append(' ').append(n(h)).append(" re f\n"); }
            double border = border(widget);
            if (border > 0 && characteristics != null && characteristics.getBorderColour() != null) { commands.append(color(characteristics.getBorderColour().getComponents(), true)).append(n(border)).append(" w\n").append(n(border / 2)).append(' ').append(n(border / 2)).append(' ').append(n(w - border)).append(' ').append(n(h - border)).append(" re S\n"); }
            commands.append(n(inset)).append(' ').append(n(inset)).append(' ').append(n(w - inset * 2)).append(' ').append(n(h - inset * 2)).append(" re W n\n");
            double y = h - inset;
            boolean multiline = field instanceof PDTextField text && text.isMultiline() || field instanceof PDChoice choice && !choice.isCombo();
            if (!multiline && lines.size() == 1) { y = (h + lines.getFirst().height()) / 2; }
            int lineIndex = 0;
            for (var line : lines) {
                y -= line.ascent(); double x = inset;
                boolean comb = field instanceof PDTextField text && text.isComb();
                if (!comb && field.getQ() == 1) { x = (w - line.width()) / 2; } else if (!comb && field.getQ() == 2) { x = w - inset - line.width(); }
                boolean selected = field instanceof PDChoice choice && !choice.isCombo() && choice.getValue().contains(choice.getOptionsExportValues().get(lineIndex));
                if (selected) { commands.append("0.15 0.35 0.65 rg\n").append(n(inset)).append(' ').append(n(y + line.descent())).append(' ').append(n(w - inset * 2)).append(' ').append(n(line.height() * 1.2)).append(" re f\n"); }
                double cellWidth = comb ? (w - inset * 2) / ((PDTextField) field).getMaxLen() : 0;
                int glyphIndex = 0;
                for (var glyph : line.glyphs()) {
                    ExecutionContext.checkpoint(); var run = glyph.run(); var fontName = resources.add(run.font()); double origin = comb ? inset + cellWidth * glyphIndex + (cellWidth - glyph.width()) / 2 : x;
                    if(commands.length()+512>limits.maxDecodedStreamBytes()){throw new PdfOperationException(PdfOperationException.Code.DECODED_STREAM_LIMIT,0,-1);}
                    commands.append("BT\n/").append(fontName.getName()).append(' ').append(n(run.size())).append(" Tf\n").append(selected ? "1 g\n" : run.color()).append('\n').append("1 0 0 1 ").append(n(origin)).append(' ').append(n(y)).append(" Tm\n<").append(HexFormat.of().formatHex(run.font().encode(glyph.text()))).append("> Tj\nET\n");
                    if (run.underline()) { commands.append(selected ? "1 G\n" : stroke(run.color())).append('\n').append(n(Math.max(.3, run.size() / 18))).append(" w\n").append(n(origin)).append(' ').append(n(y - run.size() / 10)).append(" m\n").append(n(origin + glyph.width())).append(' ').append(n(y - run.size() / 10)).append(" l S\n"); }
                    x += glyph.width(); glyphIndex++;
                }
                y += line.descent() - line.height() * .2; lineIndex++;
            }
            commands.append("Q\n"); byte[] bytes = commands.toString().getBytes(StandardCharsets.US_ASCII); encoded += bytes.length;
            if (bytes.length > limits.maxDecodedStreamBytes() || encoded > limits.maxDecodedBytes()) { throw new PdfOperationException(PdfOperationException.Code.DECODED_STREAM_LIMIT, 0, -1); }
            try (var output = appearance.getCOSObject().createOutputStream(COSName.FLATE_DECODE)) { output.write(bytes); }
            var original=widget.getAppearance();var dictionary = original==null?new PDAppearanceDictionary():new PDAppearanceDictionary(new COSDictionary(original.getCOSObject()));
            dictionary.setNormalAppearance(appearance);
            if(original!=null){for(var key:List.of(COSName.R,COSName.D)){var old=original.getCOSObject().getDictionaryObject(key);if(old instanceof COSStream){dictionary.getCOSObject().setItem(key,appearance);}else if(old instanceof COSDictionary states){var replacements=new COSDictionary();for(var state:states.keySet()){if(!(states.getDictionaryObject(state)instanceof COSStream)){throw PdfObjects.invalid();}replacements.setItem(state,appearance);}dictionary.getCOSObject().setItem(key,replacements);}else if(old!=null){throw PdfObjects.invalid();}}}
            widget.setAppearance(dictionary); widget.getCOSObject().removeItem(COSName.AS);
        }
        field.getAcroForm().setNeedAppearances(false);
    }
    private static List<Line> layout(PDVariableText field, PDAnnotationWidget widget, List<Run> runs, PdfLimits limits) throws IOException {
        var rectangle = widget.getRectangle(); var characteristics = widget.getAppearanceCharacteristics();
        if (characteristics != null && characteristics.getRotation() != 0 || field.getQ() < 0 || field.getQ() > 2) { return null; }
        double inset = inset(widget), available = rectangle.getWidth() - inset * 2, height = rectangle.getHeight() - inset * 2;
        if (available <= 0 || height <= 0) { return null; }
        boolean comb = field instanceof PDTextField text && text.isComb(); boolean multiline = field instanceof PDTextField text && text.isMultiline();
        boolean listBox = field instanceof PDChoice choice && !choice.isCombo();
        if (comb && (((PDTextField) field).getMaxLen() <= 0 || multiline)) { throw PdfObjects.invalid(); }
        if (field instanceof PDChoice choice && !choice.isCombo()) {
            var first = runs.getFirst(); var values = new ArrayList<Run>();
            for (String option : choice.getOptionsDisplayValues()) { values.add(new Run(option + "\n", first.font(), first.size(), first.color(), false)); }
            runs = values; multiline = true;
        }
        var lines = new ArrayList<Line>(); var glyphs = new ArrayList<Glyph>(); double width = 0, ascent = 0, descent = 0; long work = 0;
        for (var run : runs) {
            var descriptor = run.font().getFontDescriptor(); double a = descriptor.getAscent() / 1000d * run.size(), d = descriptor.getDescent() / 1000d * run.size();
            if (!Double.isFinite(a) || !Double.isFinite(d) || a <= d) { throw PdfObjects.invalid(); }
            for (int offset = 0; offset < run.text().length();) {
                ExecutionContext.checkpoint(); if (++work > limits.maxContentOperators()) { throw new PdfOperationException(PdfOperationException.Code.OPERATOR_LIMIT, 0, -1); }
                int cp = run.text().codePointAt(offset); offset += Character.charCount(cp);
                if (cp == '\r') { if (offset < run.text().length() && run.text().charAt(offset) == '\n') { offset++; } cp = '\n'; }
                if (cp == '\n') { if (!multiline) { return null; } lines.add(new Line(List.copyOf(glyphs), width, Math.max(ascent, a), Math.min(descent, d))); glyphs.clear(); width=0;ascent=0;descent=0;continue; }
                String text = new String(Character.toChars(cp)); double advance = run.font().getStringWidth(text) / 1000d * run.size();
                if (advance > available || comb && advance > available / ((PDTextField) field).getMaxLen()) { return null; }
                if (!comb && width + advance > available && !glyphs.isEmpty()) {
                    if (!multiline || listBox) { return null; } lines.add(new Line(List.copyOf(glyphs), width, ascent, descent)); glyphs.clear(); width=0;ascent=0;descent=0;
                }
                glyphs.add(new Glyph(text, run, advance)); width += advance; ascent=Math.max(ascent,a);descent=Math.min(descent,d);
            }
        }
        if (!glyphs.isEmpty() || lines.isEmpty()) { var run=runs.getFirst(); lines.add(new Line(List.copyOf(glyphs),width,ascent==0?run.size():ascent,descent)); }
        double total = lines.stream().mapToDouble(Line::height).sum() * 1.2;
        if (total > height || comb && glyphs.size() > ((PDTextField) field).getMaxLen()) { return null; }
        return lines;
    }
    static double inset(PDAnnotationWidget widget) throws IOException { return 2 + border(widget) * 2; }
    private static double border(PDAnnotationWidget widget) throws IOException {
        double value=widget.getBorderStyle()==null?1:widget.getBorderStyle().getWidth(); var legacy=widget.getBorder();
        if (widget.getBorderStyle()==null && legacy!=null && legacy.size()>=3) { if (!(legacy.getObject(2) instanceof COSNumber number)) { throw PdfObjects.invalid(); } value=number.floatValue(); }
        if (!Double.isFinite(value) || value<0) { throw PdfObjects.invalid(); } return value;
    }
    private static String color(float[] components, boolean stroke) throws IOException {
        if (components.length!=1 && components.length!=3 && components.length!=4) { throw PdfObjects.unsupported(); }
        var value=new StringBuilder(); for (float component:components) { if (!Float.isFinite(component)||component<0||component>1) { throw PdfObjects.invalid(); } value.append(n(component)).append(' '); }
        return value.append(components.length==1?(stroke?"G\n":"g\n"):components.length==3?(stroke?"RG\n":"rg\n"):(stroke?"K\n":"k\n")).toString();
    }
    private static String stroke(String color) { return color.replace(" rg", " RG").replace(" g", " G").replace(" k", " K"); }
    private static String n(double value) { return PdfPassiveAnnotations.n(value); }
}
