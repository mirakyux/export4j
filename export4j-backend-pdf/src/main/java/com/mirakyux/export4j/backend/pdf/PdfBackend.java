// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.util.Matrix;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DocumentBackend;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.FontFamily;
import com.mirakyux.export4j.graphics.PageBackend;
import com.mirakyux.export4j.graphics.PageWriter;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.text.GlyphRun;
import com.mirakyux.export4j.text.PositionedGlyph;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;

/**
 * Stateless PDFBox output backend, reusable concurrently with independent output streams.
 * Supports opaque RGB, standard-font Latin text and premeasured embedded TrueType glyph runs.
 * Performs no shaping or automatic layout. Output streams remain caller-owned.
 * Page sessions consume each DisplayPage immediately and do not retain it. PDFBox's
 * document, page tree and COS objects still grow with the number of converted pages;
 * temporary stream storage is not a constant-memory PDF writer.
 */
public final class PdfBackend implements DocumentBackend, PageBackend {
    private static final double MAX_PAGE_POINTS = 14_400;
    private final PdfLimits limits;
    private final PdfOptions options;

    /** Task-confined backend configuration; resulting backend owns no caller resources. */
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private PdfLimits limits = PdfLimits.DEFAULT;
        private PdfOptions options = PdfOptions.DEFAULT;
        private Builder() { }
        public Builder limits(PdfLimits value) { limits = Objects.requireNonNull(value, "limits"); return this; }
        public Builder options(PdfOptions value) { options = Objects.requireNonNull(value, "options"); return this; }
        public Builder options(java.util.function.Consumer<PdfOptions.Builder> configure) {
            var value = PdfOptions.builder().storage(options.storage());
            options.encryption().ifPresent(value::encryption); options.archival().ifPresent(value::archival);
            configure.accept(value); return options(value.build());
        }
        public PdfBackend build() { return new PdfBackend(limits, options); }
    }

    public PdfBackend() {
        this(PdfLimits.DEFAULT);
    }

    public PdfBackend(PdfLimits limits) {
        this(limits, PdfOptions.DEFAULT);
    }

    public PdfBackend(PdfLimits limits, PdfOptions options) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public void write(DisplayDocument document, OutputStream output) throws IOException {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(output, "output");
        preflight(document);
        try (PDDocument pdf = createDocument(document.metadata())) {
            if (options.archival().isPresent()) { options.archival().orElseThrow().configure(pdf, document.metadata(), document.extras()); }
            Map<FontFamily, PDType1Font> fonts = new EnumMap<>(FontFamily.class);
            Map<String, PDType0Font> embedded = new HashMap<>();
            for (int pageIndex = 0; pageIndex < document.pages().size(); pageIndex++) {
                com.mirakyux.export4j.model.ExecutionContext.progress("PDF_PAGE", "", pageIndex, pageIndex, document.pages().size());
                renderPage(pdf, document.pages().get(pageIndex), pageIndex, fonts, embedded);
            }
            PdfExtrasWriter.write(pdf, document.extras());
            if (options.encryption().isPresent()) {
                var encryption = options.encryption().orElseThrow();
                var permissions = new org.apache.pdfbox.pdmodel.encryption.AccessPermission();
                var requested = encryption.permissions();
                permissions.setCanPrint(requested.print());
                permissions.setCanPrintFaithful(requested.print());
                permissions.setCanModify(requested.modify());
                permissions.setCanExtractContent(requested.extract());
                permissions.setCanModifyAnnotations(requested.annotations());
                permissions.setCanFillInForm(requested.forms());
                permissions.setCanAssembleDocument(requested.assemble());
                var policy = new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(encryption.ownerPassword(), encryption.userPassword(), permissions);
                policy.setEncryptionKeyLength(256);
                policy.setPreferAES(true);
                pdf.protect(policy);
            }
            // Explicitly shield borrowed streams from implementation-specific close behavior.
            pdf.save(new BorrowedOutputStream(output));
        } catch (PdfRenderException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new PdfRenderException(PdfErrorCode.OUTPUT_FAILED, -1, -1,
                    "PDF output failed", failure);
        }
    }

    /**
     * Opens a caller-thread PDF session without interactions. Encryption and archival
     * options are currently rejected before allocating PDF resources. Pages are converted
     * synchronously; PDF bytes are saved only by explicit finish. Output remains borrowed.
     *
     * @throws IllegalArgumentException if encryption or archival options are configured
     */
    @Override
    public PageWriter open(DocumentMetadata metadata, OutputStream output) throws IOException {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(output, "output");
        if (options.encryption().isPresent() || options.archival().isPresent()) {
            throw new IllegalArgumentException("page-at-a-time PDF output does not support encryption or archival options");
        }
        ExecutionContext.checkpoint();
        var budget = new BudgetState(DocumentExtras.EMPTY);
        return new PdfPageWriter(createDocument(metadata), output, budget);
    }

    private PDDocument createDocument(DocumentMetadata metadata) {
        var pdf = new PDDocument(options.storage() == PdfOptions.Storage.TEMP_FILE
                ? org.apache.pdfbox.io.IOUtils.createTempFileOnlyStreamCache() : org.apache.pdfbox.io.IOUtils.createMemoryOnlyStreamCache());
        pdf.getDocumentInformation().setTitle(metadata.title());
        pdf.getDocumentInformation().setAuthor(metadata.author());
        pdf.getDocumentInformation().setCreator("Export4j");
        return pdf;
    }

    private void preflight(DisplayDocument document) throws PdfRenderException {
        if (document.pages().size() > limits.maxPages()) {
            throw budget(-1, -1, "page budget exceeded");
        }
        var state = new BudgetState(document.extras());
        for (var page : document.pages()) { state.accept(page); }
    }

    /** Per-session cumulative counters; holds no DisplayPage or command references. */
    private final class BudgetState {
        private int pages;
        private long commandCount;
        private long imagePixels;
        private long textCodePoints;
        private long pathSegments;
        private final Set<String> fontIds = new HashSet<>();
        private long fontBytes;

        private BudgetState(DocumentExtras extras) throws PdfRenderException {
            fontBytes = PdfExtrasWriter.preflight(extras, limits, fontIds);
        }

        private void accept(DisplayPage page) throws PdfRenderException {
            ExecutionContext.checkpoint();
            int pageIndex = pages;
            if (pages >= limits.maxPages()) { throw budget(pageIndex, -1, "page budget exceeded"); }
            if (page.size().width() > MAX_PAGE_POINTS || page.size().height() > MAX_PAGE_POINTS) {
                throw new PdfRenderException(PdfErrorCode.INVALID_GEOMETRY, pageIndex, -1,
                        "PDF page dimensions must not exceed 14400 pt");
            }
            checkedFloat(page.size().width(), pageIndex, -1);
            checkedFloat(page.size().height(), pageIndex, -1);
            if (page.commands().size() > limits.maxCommands() - commandCount) {
                throw budget(pageIndex, -1, "command budget exceeded");
            }
            commandCount += page.commands().size();
            int depth = 0;
            for (int index = 0; index < page.commands().size(); index++) {
                ExecutionContext.checkpoint();
                DrawCommand command = page.commands().get(index);
                if (command instanceof DrawCommand.Save && ++depth > limits.maxStateDepth()) {
                    throw budget(pageIndex, index, "graphics state depth exceeded");
                } else if (command instanceof DrawCommand.Restore) {
                    depth--;
                } else if (command instanceof DrawCommand.Image image) {
                    long pixels = (long) image.image().width() * image.image().height();
                    if (pixels > limits.maxImagePixels() - imagePixels) {
                        throw budget(pageIndex, index, "image pixel budget exceeded");
                    }
                    imagePixels += pixels;
                } else if (command instanceof DrawCommand.GlyphText text) {
                    int count = text.run().glyphs().size();
                    if (count > limits.maxTextCodePoints() - textCodePoints) {
                        throw budget(pageIndex, index, "text code point budget exceeded");
                    }
                    textCodePoints += count;
                    FontData data = text.run().font();
                    if (fontIds.add(data.id())) {
                        if (data.byteSize() > limits.maxFontBytes() - fontBytes) {
                            throw budget(pageIndex, index, "font byte budget exceeded");
                        }
                        fontBytes += data.byteSize();
                    }
                } else if (command instanceof DrawCommand.Text text) {
                    int count = text.text().codePointCount(0, text.text().length());
                    if (count > limits.maxTextCodePoints() - textCodePoints) {
                        throw budget(pageIndex, index, "text code point budget exceeded");
                    }
                    textCodePoints += count;
                } else if (command instanceof DrawCommand.FillPath fill) {
                    pathSegments = countSegments(fill.path(), pathSegments, pageIndex, index);
                } else if (command instanceof DrawCommand.StrokePath stroke) {
                    pathSegments = countSegments(stroke.path(), pathSegments, pageIndex, index);
                } else if (command instanceof DrawCommand.OutlineText text) {
                    int count = text.logicalText().codePointCount(0, text.logicalText().length());
                    if (count > limits.maxTextCodePoints() - textCodePoints) { throw budget(pageIndex, index, "text code point budget exceeded"); }
                    textCodePoints += count;
                    for (var path : text.paths()) {
                        ExecutionContext.checkpoint();
                        pathSegments = countSegments(path, pathSegments, pageIndex, index);
                    }
                }
            }
            pages++;
        }
    }

    private enum WriterState { OPEN, FINISHED, FAILED, CLOSED }

    private final class PdfPageWriter implements PageWriter {
        private final Thread owner = Thread.currentThread();
        private final PDDocument pdf;
        private final OutputStream output;
        private final BudgetState budget;
        private final Map<FontFamily, PDType1Font> fonts = new EnumMap<>(FontFamily.class);
        private final Map<String, PDType0Font> embedded = new HashMap<>();
        private WriterState state = WriterState.OPEN;

        private PdfPageWriter(PDDocument pdf, OutputStream output, BudgetState budget) {
            this.pdf = pdf;
            this.output = output;
            this.budget = budget;
        }

        @Override
        public void accept(DisplayPage page) throws IOException {
            try {
                requireOpen();
                Objects.requireNonNull(page, "page");
                ExecutionContext.checkpoint();
                int pageIndex = budget.pages;
                budget.accept(page);
                ExecutionContext.progress("PDF_PAGE", "", pageIndex, pageIndex, -1);
                renderPage(pdf, page, pageIndex, fonts, embedded);
                ExecutionContext.checkpoint();
            } catch (IOException failure) {
                fail();
                if (failure instanceof PdfRenderException pdfFailure) { throw pdfFailure; }
                throw new PdfRenderException(PdfErrorCode.OUTPUT_FAILED, budget.pages - 1, -1,
                        "PDF page output failed", failure);
            } catch (RuntimeException | Error failure) {
                fail();
                throw failure;
            }
        }

        @Override
        public void finish() throws IOException {
            try {
                requireOpen();
                if (budget.pages == 0) { throw new IllegalStateException("PDF output requires at least one page"); }
                ExecutionContext.checkpoint();
                pdf.save(new BorrowedOutputStream(output));
                ExecutionContext.checkpoint();
                state = WriterState.FINISHED;
            } catch (IOException failure) {
                fail();
                if (failure instanceof PdfRenderException pdfFailure) { throw pdfFailure; }
                throw new PdfRenderException(PdfErrorCode.OUTPUT_FAILED, -1, -1,
                        "PDF output failed", failure);
            } catch (RuntimeException | Error failure) {
                fail();
                throw failure;
            }
        }

        @Override
        public void close() throws IOException {
            requireOwner();
            if (state == WriterState.CLOSED) { return; }
            state = WriterState.CLOSED;
            try {
                pdf.close();
            } catch (IOException failure) {
                throw new PdfRenderException(PdfErrorCode.OUTPUT_FAILED, -1, -1,
                        "PDF resource cleanup failed", failure);
            } finally {
                fonts.clear();
                embedded.clear();
                budget.fontIds.clear();
            }
        }

        private void requireOpen() {
            requireOwner();
            if (state != WriterState.OPEN) { throw new IllegalStateException("PDF page session is terminal"); }
        }

        private void requireOwner() {
            if (Thread.currentThread() != owner) { throw new IllegalStateException("PDF page session belongs to its opening thread"); }
        }

        private void fail() {
            if (state == WriterState.OPEN) { state = WriterState.FAILED; }
        }
    }

    private long countSegments(VectorPath path, long count, int page, int index)
            throws PdfRenderException {
        if (path.segments().size() > limits.maxPathSegments() - count) {
            throw budget(page, index, "path segment budget exceeded");
        }
        return count + path.segments().size();
    }

    private void renderPage(PDDocument pdf, DisplayPage source, int pageIndex,
            Map<FontFamily, PDType1Font> fonts, Map<String, PDType0Font> embedded) throws IOException {
        PDPage page = new PDPage(new PDRectangle(
                checkedFloat(source.size().width(), pageIndex, -1),
                checkedFloat(source.size().height(), pageIndex, -1)));
        pdf.addPage(page);
        try (PDPageContentStream stream = new PDPageContentStream(pdf, page)) {
            stream.transform(new Matrix(1, 0, 0, -1, 0,
                    checkedFloat(source.size().height(), pageIndex, -1)));
            for (int index = 0; index < source.commands().size(); index++) {
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                try {
                    renderCommand(pdf, stream, source.commands().get(index),
                            fonts, embedded, pageIndex, index);
                } catch (PdfRenderException failure) {
                    throw failure;
                } catch (IOException failure) {
                    throw new PdfRenderException(PdfErrorCode.OUTPUT_FAILED, pageIndex, index,
                            "PDF drawing operation failed", failure);
                }
            }
        }
    }

    private void renderCommand(PDDocument pdf, PDPageContentStream stream, DrawCommand command,
            Map<FontFamily, PDType1Font> fonts, Map<String, PDType0Font> embedded,
            int page, int index) throws IOException {
        switch (command) {
            case DrawCommand.OutlineText text -> {
                var properties = new org.apache.pdfbox.cos.COSDictionary();
                properties.setString(org.apache.pdfbox.cos.COSName.ACTUAL_TEXT, text.logicalText());
                stream.beginMarkedContent(org.apache.pdfbox.cos.COSName.getPDFName("Span"),
                        org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList.create(properties));
                if (!text.logicalText().isEmpty()) {
                    // A non-painting text anchor gives readers a selectable region for ActualText over outlines.
                    var bounds = text.logicalBounds();
                    org.apache.pdfbox.pdmodel.font.PDFont anchorFont;
                    if (options.archival().isPresent()) {
                        var data = options.archival().orElseThrow().anchor();
                        var loaded = embedded.get(data.id());
                        if (loaded == null) { loaded = PDType0Font.load(pdf, new ByteArrayInputStream(data.bytes()), data.subsetAllowed()); embedded.put(data.id(), loaded); }
                        anchorFont = loaded;
                    } else { anchorFont = fonts.computeIfAbsent(FontFamily.SANS_SERIF, PdfBackend::font); }
                    stream.saveGraphicsState();
                    stream.beginText();
                    // A small anchor avoids tall invisible glyph boxes interleaving adjacent logical lines.
                    stream.setFont(anchorFont, 1);
                    stream.setRenderingMode(org.apache.pdfbox.pdmodel.graphics.state.RenderingMode.NEITHER);
                    stream.setHorizontalScaling(checkedFloat(bounds.width() * 200, page, index));
                    stream.setTextMatrix(new Matrix(1, 0, 0, -1, checkedFloat(bounds.x(), page, index), checkedFloat(bounds.y() + bounds.height() * 0.8, page, index)));
                    stream.showText("x");
                    stream.endText();
                    stream.restoreGraphicsState();
                }
                fillColor(stream, text.color());
                for (var path : text.paths()) {
                    ExecutionContext.checkpoint();
                    addPath(stream, path, page, index);
                    stream.fill();
                }
                stream.endMarkedContent();
            }
            case DrawCommand.GlyphText text -> renderGlyphs(pdf, stream, text, embedded, page, index);
            case DrawCommand.FillRect fill -> {
                fillColor(stream, fill.color());
                addRect(stream, fill.bounds(), page, index);
                stream.fill();
            }
            case DrawCommand.FillPath fill -> {
                fillColor(stream, fill.color());
                addPath(stream, fill.path(), page, index);
                stream.fill();
            }
            case DrawCommand.StrokePath stroke -> {
                RgbColor color = stroke.color();
                stream.setStrokingColor((float) color.red(), (float) color.green(), (float) color.blue());
                stream.setLineWidth(checkedFloat(stroke.width(), page, index));
                addPath(stream, stroke.path(), page, index);
                stream.stroke();
            }
            case DrawCommand.Text text -> {
                if (options.archival().isPresent()) { throw new PdfRenderException(PdfErrorCode.UNSUPPORTED_TEXT, page, index, "PDF/A requires explicit embedded fonts or outlined text"); }
                PDType1Font font = fonts.computeIfAbsent(text.style().family(), PdfBackend::font);
                try {
                    font.encode(text.text());
                } catch (IllegalArgumentException failure) {
                    // PDFBox's message may contain source text, so do not propagate it.
                    throw new PdfRenderException(PdfErrorCode.UNSUPPORTED_TEXT, page, index,
                            "text is not supported by the selected standard font");
                }
                float fontSize = checkedFloat(text.style().fontSize(), page, index);
                float baselineX = checkedFloat(text.baseline().x(), page, index);
                float baselineY = checkedFloat(text.baseline().y(), page, index);
                stream.beginText();
                stream.setFont(font, fontSize);
                fillColor(stream, text.style().color());
                // Undo the page Y flip for glyphs, while keeping the logical baseline position.
                stream.setTextMatrix(new Matrix(1, 0, 0, -1, baselineX, baselineY));
                stream.showText(text.text());
                stream.endText();
            }
            case DrawCommand.Image image -> {
                Rect bounds = image.bounds();
                stream.drawImage(LosslessFactory.createFromImage(pdf, toBufferedImage(image.image())),
                        new Matrix(checkedFloat(bounds.width(), page, index), 0, 0,
                                -checkedFloat(bounds.height(), page, index),
                                checkedFloat(bounds.x(), page, index),
                                checkedFloat(bounds.y() + bounds.height(), page, index)));
            }
            case DrawCommand.Save ignored -> stream.saveGraphicsState();
            case DrawCommand.Restore ignored -> stream.restoreGraphicsState();
            case DrawCommand.Transform transform -> {
                AffineTransform m = transform.matrix();
                stream.transform(new Matrix(checkedFloat(m.a(), page, index),
                        checkedFloat(m.b(), page, index), checkedFloat(m.c(), page, index),
                        checkedFloat(m.d(), page, index), checkedFloat(m.e(), page, index),
                        checkedFloat(m.f(), page, index)));
            }
            case DrawCommand.Clip clip -> {
                addRect(stream, clip.bounds(), page, index);
                stream.clip();
            }
        }
    }

    private static void renderGlyphs(PDDocument pdf, PDPageContentStream stream, DrawCommand.GlyphText text,
            Map<String, PDType0Font> fonts, int page, int index) throws IOException {
        GlyphRun run = text.run();
        PDType0Font font = fonts.get(run.font().id());
        if (font == null) {
            try {
                font = PDType0Font.load(pdf, new ByteArrayInputStream(run.font().bytes()),
                        run.font().subsetAllowed());
            } catch (IOException | IllegalArgumentException failure) {
                throw new PdfRenderException(PdfErrorCode.INVALID_FONT, page, index,
                        "embedded font loading failed", failure);
            }
            fonts.put(run.font().id(), font);
        }
        float fontSize = checkedFloat(run.fontSize(), page, index);
        float baseline = checkedFloat(text.baseline().y(), page, index);
        var positioned = new java.util.ArrayList<Object>();
        double cursor = text.baseline().x();
        for (int i = 0; i < run.glyphs().size(); i++) {
            ExecutionContext.checkpoint();
            PositionedGlyph glyph = run.glyphs().get(i);
            checkedFloat(cursor, page, index);
            byte[] encoded;
            try {
                encoded = font.encode(glyph.text());
            } catch (IllegalArgumentException failure) {
                throw new PdfRenderException(PdfErrorCode.UNSUPPORTED_TEXT, page, index,
                        "embedded font does not support a requested glyph");
            }
            int glyphId = encoded.length == 2
                    ? (Byte.toUnsignedInt(encoded[0]) << 8) | Byte.toUnsignedInt(encoded[1]) : -1;
            if (glyphId != glyph.glyphId()) {
                throw new PdfRenderException(PdfErrorCode.INVALID_FONT, page, index,
                        "embedded glyph mapping differs from the measured font mapping");
            }
            cursor += glyph.advance();
            positioned.add(glyph.text());
            if (i + 1 < run.glyphs().size()) {
                checkedFloat(cursor, page, index);
                // TJ advances are relative. Rounding two absolute cursors first adds origin-dependent spacing noise.
                positioned.add(checkedFloat(font.getStringWidth(glyph.text()) - glyph.advance() * 1000 / fontSize, page, index));
            }
        }
        stream.beginText();
        stream.setFont(font, fontSize);
        fillColor(stream, text.color());
        stream.setTextMatrix(new Matrix(1, 0, 0, -1, checkedFloat(text.baseline().x(), page, index), baseline));
        if (!positioned.isEmpty()) { stream.showTextWithPositioning(positioned.toArray()); }
        stream.endText();
    }

    private static void fillColor(PDPageContentStream stream, RgbColor color) throws IOException {
        stream.setNonStrokingColor((float) color.red(), (float) color.green(), (float) color.blue());
    }

    private static void addRect(PDPageContentStream stream, Rect rect, int page, int index)
            throws IOException {
        stream.addRect(checkedFloat(rect.x(), page, index), checkedFloat(rect.y(), page, index),
                checkedFloat(rect.width(), page, index), checkedFloat(rect.height(), page, index));
    }

    private static void addPath(PDPageContentStream stream, VectorPath path, int page, int index)
            throws IOException {
        for (VectorPath.Segment segment : path.segments()) {
            ExecutionContext.checkpoint();
            switch (segment) {
                case VectorPath.MoveTo move -> stream.moveTo(
                        x(move.point(), page, index), y(move.point(), page, index));
                case VectorPath.LineTo line -> stream.lineTo(
                        x(line.point(), page, index), y(line.point(), page, index));
                case VectorPath.CurveTo curve -> stream.curveTo(
                        x(curve.control1(), page, index), y(curve.control1(), page, index),
                        x(curve.control2(), page, index), y(curve.control2(), page, index),
                        x(curve.end(), page, index), y(curve.end(), page, index));
                case VectorPath.Close ignored -> stream.closePath();
            }
        }
    }

    private static float x(Point point, int page, int index) throws PdfRenderException {
        return checkedFloat(point.x(), page, index);
    }

    private static float y(Point point, int page, int index) throws PdfRenderException {
        return checkedFloat(point.y(), page, index);
    }

    static float checkedFloat(double value, int page, int index) throws PdfRenderException {
        float result = (float) value;
        if (!Float.isFinite(result) || (value != 0 && result == 0)) {
            throw new PdfRenderException(PdfErrorCode.INVALID_GEOMETRY, page, index,
                    "geometry cannot be represented at PDF numeric precision");
        }
        return result;
    }

    private static PdfRenderException budget(int page, int index, String message) {
        return new PdfRenderException(PdfErrorCode.BUDGET_EXCEEDED, page, index, message);
    }

    private static PDType1Font font(FontFamily family) {
        return new PDType1Font(switch (family) {
            case SANS_SERIF -> Standard14Fonts.FontName.HELVETICA;
            case SERIF -> Standard14Fonts.FontName.TIMES_ROMAN;
            case MONOSPACE -> Standard14Fonts.FontName.COURIER;
        });
    }

    private static BufferedImage toBufferedImage(RgbImage image) {
        byte[] alpha = image.alpha();
        BufferedImage result = new BufferedImage(image.width(), image.height(),
                alpha.length == 0 ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        byte[] pixels = image.pixels();
        int offset = 0;
        for (int y = 0; y < image.height(); y++) {
            ExecutionContext.checkpoint();
            for (int x = 0; x < image.width(); x++) {
                int rgb = (Byte.toUnsignedInt(pixels[offset]) << 16)
                        | (Byte.toUnsignedInt(pixels[offset + 1]) << 8)
                        | Byte.toUnsignedInt(pixels[offset + 2]);
                int opacity = alpha.length == 0 ? 255 : Byte.toUnsignedInt(alpha[y * image.width() + x]);
                result.setRGB(x, y, opacity << 24 | rgb);
                offset += 3;
            }
        }
        return result;
    }

    private static final class BorrowedOutputStream extends FilterOutputStream {
        private BorrowedOutputStream(OutputStream output) {
            super(output);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
        }

        @Override
        public void close() {
            // Ownership remains with the caller; even an exceptional PDF save cannot close it.
        }
    }
}
