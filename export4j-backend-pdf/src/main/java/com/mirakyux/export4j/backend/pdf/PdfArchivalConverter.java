// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;

/** Offline existing-PDF conversion into an explicitly selected PDF/A edition by opaque RGB rasterization.
 * Searchable text, vectors, tags, interaction, attachments and source metadata are discarded and reported.
 * Rendering accepts static embedded TrueType/Type0 fonts, solid graphics and ordinary RGB/Gray images;
 * unsupported resources, signatures and active content fail irrespective of the loss policy.
 * Final conformance requires independent validation. No network or system-font fallback is used.
 */
public final class PdfArchivalConverter {
    private PdfArchivalConverter() { }

    /** Per-conversion finite budgets; DPI is explicit and metadata belongs to the new document. */
    public record Options(PdfArchival archival, DocumentMetadata metadata, int dpi, int maxInputBytes,
                          int maxPages, long maxPixels, long maxDecodedBytes, long maxOperators, long maxOutputBytes) {
        public Options {
            Objects.requireNonNull(archival, "archival"); Objects.requireNonNull(metadata, "metadata");
            if (dpi < 36 || dpi > 600 || maxInputBytes <= 0 || maxInputBytes > 256 * 1024 * 1024 || maxPages <= 0
                    || maxPixels <= 0 || maxPixels > 100_000_000 || maxDecodedBytes <= 0 || maxOperators <= 0 || maxOutputBytes <= 0) { throw new IllegalArgumentException("invalid archival conversion budgets"); }
        }
        public static Options defaults(PdfArchival archival, DocumentMetadata metadata) {
            return new Options(archival, metadata, 144, 32 * 1024 * 1024, 100, 25_000_000, 64L * 1024 * 1024, 1_000_000, 64L * 1024 * 1024);
        }
    }

    /** Atomic path output, including in-place conversion. The target changes only after complete output closes. */
    public static ProcessingReport convert(Path source, Path target, Options options, ProcessingPolicy policy) throws IOException {
        Objects.requireNonNull(target, "target");
        var absolute = target.toAbsolutePath(); var temporary = Files.createTempFile(absolute.getParent(), ".export4j-archive-", ".pdf");
        try {
            ProcessingReport report;
            try (var output = Files.newOutputStream(temporary)) { report = convert(source, output, options, policy); }
            ExecutionContext.checkpoint(); Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); return report;
        } catch (IOException | RuntimeException | Error failure) {
            try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { failure.addSuppressed(cleanup); } throw failure;
        }
    }

    /** Borrowed output remains open. Discard any bytes on failure. IGNORE still returns the actual losses. */
    public static ProcessingReport convert(Path source, OutputStream output, Options options, ProcessingPolicy policy) throws IOException {
        Objects.requireNonNull(source, "source"); Objects.requireNonNull(output, "output"); Objects.requireNonNull(options, "options"); Objects.requireNonNull(policy, "policy");
        ExecutionContext.checkpoint(); byte[] bytes;
        try (var input = Files.newInputStream(source)) { bytes = input.readNBytes(options.maxInputBytes() + 1); }
        if (bytes.length > options.maxInputBytes()) { throw new IOException("archival conversion input budget exceeded"); }
        var rule = policy.resolve("PDF", "PDF_ARCHIVAL_RASTERIZATION", "CONVERT_PDF_A");
        var issue = new ProcessingIssue("CONVERT", "PDF", "PDF_A", "PDF_ARCHIVAL_RASTERIZATION", "CONVERT_PDF_A",
                new ProcessingIssue.Location("", "DOCUMENT", "", List.of(), -1, -1), "OPAQUE_RGB_RECONSTRUCTION", rule.action(), ProcessingPolicy.Treatment.DEGRADE,
                List.of("SEARCHABLE_TEXT_LOST", "VECTOR_EDITING_LOST", "TAGS_LOST", "INTERACTION_LOST", "SOURCE_ATTACHMENTS_LOST", "SOURCE_METADATA_LOST", "RESOLUTION_LIMITED"));
        if (rule.action() == ProcessingPolicy.Action.ERROR || rule.treatment() != ProcessingPolicy.Treatment.DEGRADE) { throw new ProcessingException(issue); }
        try (var input = Loader.loadPDF(bytes); var pdf = new PDDocument()) {
            check(input, options);
            pdf.getDocumentInformation().setTitle(options.metadata().title()); pdf.getDocumentInformation().setAuthor(options.metadata().author()); pdf.getDocumentInformation().setCreator("Export4j");
            options.archival().configure(pdf, options.metadata(), DocumentExtras.EMPTY);
            var renderer = new CheckedRenderer(input, options.maxOperators(), options.maxPixels()); long pixels = 0;
            for (int index = 0; index < input.getNumberOfPages(); index++) {
                ExecutionContext.progress("PDF_A_CONVERT", "", index, index, input.getNumberOfPages());
                var original = input.getPage(index); var box = original.getCropBox(); boolean rotated = original.getRotation() % 180 != 0;
                float width = rotated ? box.getHeight() : box.getWidth(); float height = rotated ? box.getWidth() : box.getHeight();
                long w = (long) Math.floor(width * options.dpi() / 72d), h = (long) Math.floor(height * options.dpi() / 72d);
                if (w <= 0 || h <= 0 || w > Integer.MAX_VALUE || h > Integer.MAX_VALUE || w > (options.maxPixels() - pixels) / h) { throw new IOException("archival conversion pixel budget exceeded"); }
                pixels += w * h;
                var raster = renderer.renderImageWithDPI(index, options.dpi(), ImageType.RGB);
                try {
                    var page = new PDPage(new PDRectangle(width, height)); pdf.addPage(page);
                    var image = LosslessFactory.createFromImage(pdf, raster);
                    try (var stream = new PDPageContentStream(pdf, page)) { stream.drawImage(image, 0, 0, width, height); }
                } finally { raster.flush(); }
            }
            var bounded = new OutputStream() {
                private long count;
                @Override public void write(int value) throws IOException { check(1); output.write(value); }
                @Override public void write(byte[] value, int offset, int length) throws IOException { check(length); output.write(value, offset, length); }
                private void check(int length) throws IOException { ExecutionContext.checkpoint(); if (length > options.maxOutputBytes() - count) { throw new IOException("archival conversion output budget exceeded"); } count += length; }
            };
            if (options.archival().target() == PdfArchival.Profile.A1B) { pdf.save(bounded, org.apache.pdfbox.pdfwriter.compress.CompressParameters.NO_COMPRESSION); } else { pdf.save(bounded); }
        }
        ExecutionContext.checkpoint(); return new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of(issue));
    }

    private static void check(PDDocument pdf, Options options) throws IOException {
        if (pdf.isEncrypted() || !pdf.getSignatureDictionaries().isEmpty()) { throw new IOException("archival conversion rejects encrypted or signed input"); }
        if (pdf.getNumberOfPages() <= 0 || pdf.getNumberOfPages() > options.maxPages()) { throw new IOException("archival conversion page budget exceeded"); }
        var catalog = pdf.getDocumentCatalog().getCOSObject();
        if (catalog.containsKey(COSName.OPEN_ACTION) || catalog.containsKey(COSName.AA) || catalog.containsKey(COSName.ACRO_FORM) || catalog.containsKey(COSName.OCPROPERTIES)) { throw new IOException("archival conversion requires static input without forms or layers"); }
        var pending = new ArrayDeque<COSBase>(); pending.add(catalog);
        Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>()); long decoded = 0;
        while (!pending.isEmpty()) {
            ExecutionContext.checkpoint(); var value = pending.removeFirst(); if (value instanceof COSObject reference) { value = reference.getObject(); }
            if (value == null || !visited.add(value)) { continue; }
            if (visited.size() > 100_000) { throw new IOException("archival conversion object budget exceeded"); }
            if (value instanceof COSDictionary dictionary) {
                String action = dictionary.getNameAsString(COSName.S, "");
                if (List.of("JavaScript", "Launch", "SubmitForm", "ImportData", "GoToR", "Rendition", "Movie", "Sound").contains(action)
                        || dictionary.containsKey(COSName.JS) || dictionary.containsKey(COSName.XFA) || dictionary.containsKey(COSName.AA)
                        || dictionary.containsKey(COSName.getPDFName("RichMediaContent"))) { throw new IOException("active source content is unsupported"); }
                pending.addAll(dictionary.getValues());
            } else if (value instanceof COSArray array) { for (var item : array) { pending.add(item); } }
            if (value instanceof COSStream stream) {
                try (var data = stream.createInputStream()) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = data.read(buffer)) != -1) { ExecutionContext.checkpoint(); if (count > options.maxDecodedBytes() - decoded) { throw new IOException("archival conversion decoded byte budget exceeded"); } decoded += count; }
                }
            }
        }
        for (var page : pdf.getPages()) {
            if (!Float.isFinite(page.getCropBox().getWidth()) || !Float.isFinite(page.getCropBox().getHeight()) || page.getCropBox().getWidth() <= 0 || page.getCropBox().getHeight() <= 0 || page.getCropBox().getWidth() > 14_400 || page.getCropBox().getHeight() > 14_400) { throw new IOException("invalid archival source page geometry"); }
        }
    }

    private static final class CheckedRenderer extends PDFRenderer {
        private long operators; private final long maximum; private long sourcePixels; private final long maxSourcePixels;
        CheckedRenderer(PDDocument pdf, long maximum, long maxSourcePixels) { super(pdf); this.maximum = maximum; this.maxSourcePixels = maxSourcePixels; setSubsamplingAllowed(false); setAnnotationsFilter(annotation -> false); }
        @Override protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
            return new PageDrawer(parameters) {
                private int depth;
                private int stateDepth;
                private final Set<COSStream> active = Collections.newSetFromMap(new IdentityHashMap<>());
                @Override protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
                    ExecutionContext.checkpoint(); if (++operators > maximum) { throw new IOException("archival conversion operator budget exceeded"); }
                    if (operator.getName().equals("q") && ++stateDepth + depth > 64 || operator.getName().equals("Q") && --stateDepth < 0) { throw new IOException("archival conversion graphics-state budget exceeded"); }
                    if (operator.getName().equals("gs") || operator.getName().equals("sh") || operator.getName().equals("SCN") || operator.getName().equals("scn")) { throw new IOException("archival raster conversion does not accept transparency, shading or patterns"); }
                    super.processOperator(operator, operands);
                }
                @Override protected void showText(byte[] encoded) throws IOException { if (getGraphicsState().getTextState().getFont() == null) { throw new IOException("archival text has no explicit font"); } super.showText(encoded); }
                @Override public void drawImage(org.apache.pdfbox.pdmodel.graphics.image.PDImage image) throws IOException {
                    long count = (long) image.getWidth() * image.getHeight();
                    if (count <= 0 || count > maxSourcePixels - sourcePixels || image.isStencil() || image.getBitsPerComponent() != 8) { throw new IOException("archival source image exceeds its supported pixel/sample budget"); }
                    sourcePixels += count; String color = image.getColorSpace().getName();
                    if (!color.equals("DeviceRGB") && !color.equals("DeviceGray") || image instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject object && (object.getMask() != null || object.getSoftMask() != null)) { throw new IOException("archival converter requires opaque RGB or Gray source images"); }
                    super.drawImage(image);
                }
                @Override protected void processTransparencyGroup(org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup group) throws IOException { throw new IOException("archival converter does not accept transparency groups"); }
                @Override protected void showFontGlyph(Matrix matrix, PDFont font, int code, Vector displacement) throws IOException {
                    if (!font.isEmbedded() || font.isDamaged() || !(font instanceof PDType0Font type0 && type0.hasGlyph(code) || font instanceof PDTrueTypeFont trueType && trueType.hasGlyph(code))) { throw new IOException("archival source glyph requires a valid embedded TrueType program"); }
                    super.showFontGlyph(matrix, font, code, displacement);
                }
                @Override protected void showType3Glyph(Matrix matrix, PDType3Font font, int code, Vector displacement) throws IOException { throw new IOException("archival converter does not accept Type3 fonts"); }
                @Override public void showForm(PDFormXObject form) throws IOException {
                    if (++depth > 32 || !active.add(form.getCOSObject()) || form.getCOSObject().containsKey(COSName.GROUP)) { throw new IOException("archival source form is recursive or unsupported"); }
                    try { super.showForm(form); } finally { active.remove(form.getCOSObject()); depth--; }
                }
                @Override protected void operatorException(Operator operator, List<COSBase> operands, IOException failure) throws IOException { throw failure; }
            };
        }
    }
}
