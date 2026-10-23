// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;

/** Task-confined static-page editor. Operations run in declaration order, with no reflow.
 * Page indices are zero based. Coordinates use physical points from the current unrotated media box's
 * top-left, before /Rotate; crop changes visibility, never erases hidden source content.
 */
public final class PdfPageEditor {
    public enum Layer { UNDER_CONTENT, OVER_CONTENT }
    public record Rectangle(double x, double y, double width, double height) {
        public Rectangle { nonnegative(x); nonnegative(y); positive(width); positive(height); }
    }
    /** Baseline stamp style. Simple horizontal text only; no shaping, wrapping or missing-glyph substitution. */
    public record TextStyle(PdfFont font, double size, int rgb, double opacity) {
        public TextStyle {
            Objects.requireNonNull(font, "font"); positive(size); color(rgb);
            if (!Double.isFinite(opacity) || opacity <= 0 || opacity > 1) { throw new IllegalArgumentException("invalid PDF stamp opacity"); }
        }
    }
    private interface Edit { int page(); }
    private record Rotate(int page, int degrees) implements Edit { }
    private record Crop(int page, Rectangle rectangle) implements Edit { }
    private record Scale(int page, double factor) implements Edit { }
    private record Text(int page, String text, double x, double baseline, TextStyle style, Layer layer) implements Edit { }
    private record Image(int page, ImageResource image, Rectangle rectangle, Layer layer) implements Edit { }
    private final PdfAdapter adapter;
    private final PdfInput input;
    private final PdfLimits limits;
    private final List<Edit> edits = new ArrayList<>();
    private ProcessingPolicy policy = ProcessingPolicy.STRICT;
    private ExecutionOptions execution = ExecutionOptions.DEFAULT;
    PdfPageEditor(PdfAdapter adapter, PdfInput input, PdfLimits limits) { this.adapter = adapter; this.input = input; this.limits = limits; }
    public PdfPageEditor policy(ProcessingPolicy value) { policy = Objects.requireNonNull(value, "policy"); return this; }
    public PdfPageEditor execution(ExecutionOptions value) { execution = Objects.requireNonNull(value, "execution"); return this; }
    /** Adds a clockwise multiple of 90 degrees to the page's viewer rotation. */
    public PdfPageEditor rotate(int page, int degrees) {
        if (degrees % 90 != 0) { throw new IllegalArgumentException("PDF rotation must be a multiple of 90"); }
        return add(new Rotate(page, Math.floorMod(degrees, 360)));
    }
    /** Explicitly hides the area outside this crop; the source remains recoverable. */
    public PdfPageEditor crop(int page, Rectangle rectangle) { return add(new Crop(page, Objects.requireNonNull(rectangle, "rectangle"))); }
    /** Uniformly scales every page box and the existing content around native PDF coordinate (0,0).
     * UserUnit and viewer rotation remain unchanged; subsequent operations use the new media box.
     */
    public PdfPageEditor scale(int page, double factor) { positive(factor); return add(new Scale(page, factor)); }
    public PdfPageEditor text(int page, String text, double x, double baseline, TextStyle style, Layer layer) {
        Objects.requireNonNull(text, "text");
        if (text.length() > limits.maxTextCharacters()) { throw new IllegalArgumentException("PDF stamp text budget exceeded"); }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) { if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) { throw new IllegalArgumentException("invalid Unicode"); } }
            else if (Character.isLowSurrogate(c) || Character.isISOControl(c)) { throw new IllegalArgumentException("invalid PDF stamp text"); }
        }
        nonnegative(x); nonnegative(baseline);
        return add(new Text(page, text, x, baseline, Objects.requireNonNull(style, "style"), Objects.requireNonNull(layer, "layer")));
    }
    public PdfPageEditor image(int page, ImageResource image, Rectangle rectangle, Layer layer) {
        return add(new Image(page, Objects.requireNonNull(image, "image"), Objects.requireNonNull(rectangle, "rectangle"), Objects.requireNonNull(layer, "layer")));
    }
    private PdfPageEditor add(Edit edit) {
        if (edit.page() < 0) { throw new IllegalArgumentException("negative page index"); }
        if (edits.size() >= Math.min(limits.maxObjects(), 10_000)) { throw new IllegalArgumentException("PDF edit operation budget exceeded"); }
        edits.add(edit); return this;
    }
    public PreparedPdf prepare() throws IOException {
        var operations = List.copyOf(edits); var control = execution; var strategy = policy;
        return ExecutionContext.execute(control, () -> {
            var budget = new PdfPreflight(limits); var issues = new ArrayList<ProcessingIssue>();
            try (var document = adapter.openForEdit(input, budget)) {
                if (document.isEncrypted()) { issue(strategy, issues, "ENCRYPTION_REMOVED", "PREPARE", -1, "OUTPUT_IS_UNENCRYPTED"); }
                Map<PdfFont, PDType0Font> fonts = new IdentityHashMap<>();
                long text = 0; long pixels = 0; long resourceBytes = 0;
                for (var operation : operations) {
                    ExecutionContext.checkpoint();
                    if (operation.page() >= document.getNumberOfPages()) { throw new PdfOperationException(PdfOperationException.Code.INVALID_SELECTION, 0, operation.page()); }
                    var page = document.getPage(operation.page());
                    if (operation instanceof Text stamp) {
                        if (!simpleText(stamp.text())) {
                            var rule = strategy.resolve("PDF", "TEXT_SHAPING", "STAMP_TEXT");
                            var unsupported = new ProcessingIssue("EDIT", "PDF", "PDF", "TEXT_SHAPING", "STAMP_TEXT",
                                    new ProcessingIssue.Location("", "PAGE:" + stamp.page(), "", List.of(), -1, -1),
                                    "stamp text requires shaping outside the simple horizontal subset", rule.action(), rule.treatment(), List.of("STAMP_NOT_ADDED"));
                            if (rule.action() == ProcessingPolicy.Action.ERROR) { throw new ProcessingException(unsupported); }
                            if (rule.treatment() != ProcessingPolicy.Treatment.SKIP_MATCH) { throw new IllegalArgumentException("text shaping only supports skipping this stamp"); }
                            issues.add(unsupported); continue;
                        }
                        text += stamp.text().length();
                        if (text > limits.maxTextCharacters()) { throw new PdfOperationException(PdfOperationException.Code.TEXT_LIMIT, 0, stamp.page()); }
                        if (!fonts.containsKey(stamp.style().font())) {
                            resourceBytes += stamp.style().font().bytes().length;
                            if (resourceBytes > limits.maxTotalInputBytes()) { throw budget.failure(PdfOperationException.Code.INPUT_LIMIT); }
                            fonts.put(stamp.style().font(), PDType0Font.load(document, new ByteArrayInputStream(stamp.style().font().bytes()), stamp.style().font().subsetAllowed()));
                        }
                        text(document, page, stamp, fonts.get(stamp.style().font()));
                    } else if (operation instanceof Image stamp) {
                        pixels += (long) stamp.image().pixelWidth() * stamp.image().pixelHeight();
                        resourceBytes += stamp.image().byteSize();
                        if (pixels > limits.maxImagePixels()) { throw budget.failure(PdfOperationException.Code.IMAGE_LIMIT); }
                        if (resourceBytes > limits.maxTotalInputBytes()) { throw budget.failure(PdfOperationException.Code.INPUT_LIMIT); }
                        image(document, page, stamp);
                    } else if (operation instanceof Rotate rotation) { page.setRotation((page.getRotation() + rotation.degrees()) % 360); }
                    else if (operation instanceof Crop crop) {
                        inside(page, crop.rectangle());
                        issue(strategy, issues, "PAGE_CROP", "CROP", crop.page(), "OUTSIDE_CROP_HIDDEN_NOT_REMOVED");
                        page.setCropBox(nativeBox(page, crop.rectangle()));
                    } else if (operation instanceof Scale scale) { scale(document, page, scale.factor()); }
                }
                return PreparedPdf.encode(document, operations.size() - (int) issues.stream().filter(item -> item.feature().equals("TEXT_SHAPING")).count(), issues, limits, control);
            }
        });
    }
    private static void issue(ProcessingPolicy policy, List<ProcessingIssue> issues, String feature, String operation, int page, String effect) {
        var rule = policy.resolve("PDF", feature, operation);
        var issue = new ProcessingIssue("EDIT", "PDF", "PDF", feature, operation,
                new ProcessingIssue.Location("", "PAGE:" + page, "", List.of(), -1, -1),
                "explicit PDF transformation changes visibility or protection", rule.action(), rule.treatment(), List.of(effect));
        if (rule.action() == ProcessingPolicy.Action.ERROR) { throw new ProcessingException(issue); }
        if (rule.treatment() != ProcessingPolicy.Treatment.DEGRADE) { throw new IllegalArgumentException("unsupported PDF transformation treatment"); }
        issues.add(issue);
    }
    private static void text(PDDocument document, PDPage page, Text stamp, PDType0Font font) throws IOException {
        double width = font.getStringWidth(stamp.text()) / 1000 * stamp.style().size();
        double ascent = Math.max(0, font.getFontDescriptor().getAscent()) / 1000 * stamp.style().size();
        double descent = Math.max(0, -font.getFontDescriptor().getDescent()) / 1000 * stamp.style().size();
        inside(page, new Rectangle(stamp.x(), stamp.baseline() - ascent, Math.max(.0001, width), Math.max(.0001, ascent + descent)));
        try (var content = stream(document, page, stamp.layer())) {
            var state = new PDExtendedGraphicsState(); state.setNonStrokingAlphaConstant((float) stamp.style().opacity()); content.setGraphicsStateParameters(state);
            rgb(content, stamp.style().rgb()); content.beginText(); content.setFont(font, (float) (stamp.style().size() / page.getUserUnit()));
            var media = page.getMediaBox();
            content.newLineAtOffset((float) (media.getLowerLeftX() + stamp.x() / page.getUserUnit()), (float) (media.getUpperRightY() - stamp.baseline() / page.getUserUnit()));
            content.showText(stamp.text()); content.endText();
        }
    }
    private static void image(PDDocument document, PDPage page, Image stamp) throws IOException {
        inside(page, stamp.rectangle());
        var box = nativeBox(page, stamp.rectangle());
        try (var content = stream(document, page, stamp.layer())) {
            var image = PDImageXObject.createFromByteArray(document, stamp.image().bytes(), "explicit-stamp");
            content.drawImage(image, box.getLowerLeftX(), box.getLowerLeftY(), box.getWidth(), box.getHeight());
        }
    }
    private static PDPageContentStream stream(PDDocument document, PDPage page, Layer layer) throws IOException {
        return new PDPageContentStream(document, page, layer == Layer.OVER_CONTENT ? PDPageContentStream.AppendMode.APPEND : PDPageContentStream.AppendMode.PREPEND, true, true);
    }
    private static void scale(PDDocument document, PDPage page, double factor) throws IOException {
        var media = scaled(page.getMediaBox(), factor); var crop = scaled(page.getCropBox(), factor);
        var bleed = scaled(page.getBleedBox(), factor); var trim = scaled(page.getTrimBox(), factor); var art = scaled(page.getArtBox(), factor);
        var streams = new ArrayList<PDStream>();
        var prefix = new PDStream(document);
        try (var output = prefix.createOutputStream()) {
            output.write(("q\n" + java.math.BigDecimal.valueOf(factor).toPlainString() + " 0 0 "
                    + java.math.BigDecimal.valueOf(factor).toPlainString() + " 0 0 cm\n").getBytes(StandardCharsets.US_ASCII));
        }
        streams.add(prefix); page.getContentStreams().forEachRemaining(streams::add);
        var suffix = new PDStream(document);
        try (var output = suffix.createOutputStream()) { output.write("\nQ\n".getBytes(StandardCharsets.US_ASCII)); }
        streams.add(suffix); page.setContents(streams);
        page.setMediaBox(media); page.setCropBox(crop); page.setBleedBox(bleed); page.setTrimBox(trim); page.setArtBox(art);
    }
    private static PDRectangle scaled(PDRectangle source, double factor) {
        double x = source.getLowerLeftX() * factor, y = source.getLowerLeftY() * factor, w = source.getWidth() * factor, h = source.getHeight() * factor;
        if (Math.abs(x) > 1_000_000 || Math.abs(y) > 1_000_000 || w > 1_000_000 || h > 1_000_000 || (float) w <= 0 || (float) h <= 0) { throw new IllegalArgumentException("scaled PDF box is outside supported dimensions"); }
        return new PDRectangle((float) x, (float) y, (float) w, (float) h);
    }
    private static PDRectangle nativeBox(PDPage page, Rectangle rectangle) {
        var media = page.getMediaBox(); double unit = page.getUserUnit();
        return new PDRectangle((float) (media.getLowerLeftX() + rectangle.x() / unit), (float) (media.getUpperRightY() - (rectangle.y() + rectangle.height()) / unit),
                (float) (rectangle.width() / unit), (float) (rectangle.height() / unit));
    }
    private static void inside(PDPage page, Rectangle rectangle) {
        if (rectangle.x() + rectangle.width() > page.getMediaBox().getWidth() * page.getUserUnit()
                || rectangle.y() + rectangle.height() > page.getMediaBox().getHeight() * page.getUserUnit()) { throw new IllegalArgumentException("PDF edit rectangle exceeds media box"); }
    }
    private static void rgb(PDPageContentStream content, int value) throws IOException { content.setNonStrokingColor(((value >> 16) & 255) / 255f, ((value >> 8) & 255) / 255f, (value & 255) / 255f); }
    private static void color(int value) { if ((value & 0xff000000) != 0) { throw new IllegalArgumentException("invalid RGB color"); } }
    static boolean simpleText(String text) {
        return text.codePoints().allMatch(codePoint -> {
            int type = Character.getType(codePoint);
            if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK || type == Character.FORMAT) { return false; }
            return switch (Character.UnicodeScript.of(codePoint)) {
                case LATIN, GREEK, CYRILLIC, HAN, HIRAGANA, KATAKANA, HANGUL, COMMON -> true;
                default -> false;
            };
        });
    }
    private static void nonnegative(double value) { if (!Double.isFinite(value) || value < 0 || value > 1_000_000) { throw new IllegalArgumentException("invalid PDF coordinate"); } }
    private static void positive(double value) { if (!Double.isFinite(value) || value <= 0 || value > 1_000_000) { throw new IllegalArgumentException("invalid PDF dimension"); } }
}
