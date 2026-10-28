// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.raster;

import java.awt.image.BufferedImage;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DocumentBackend;
import com.mirakyux.export4j.graphics.FontFamily;
import com.mirakyux.export4j.graphics.core.StaticBackendSupport;
import com.mirakyux.export4j.model.DocumentMetadata;

/** PNG ZIP backend (page-00001.png etc.), plus single-page PNG API. Rejects interactive extras.
 * Resolution is explicit; at most 100 million output pixels per call. Caller owns streams. */
public final class RasterBackend implements DocumentBackend {
    private final double dpi;
    private final Map<FontFamily, FontData> standardFonts;

    /** Task-confined configuration; fonts are explicit borrowed resources. Default resolution is 96 DPI. */
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private double dpi = 96;
        private final Map<FontFamily, FontData> standardFonts = new java.util.EnumMap<>(FontFamily.class);
        private Builder() { }
        public Builder dpi(double value) { dpi = value; return this; }
        public Builder standardFont(FontFamily family, FontData data) {
            standardFonts.put(Objects.requireNonNull(family, "family"), Objects.requireNonNull(data, "data")); return this;
        }
        public RasterBackend build() { return new RasterBackend(dpi, standardFonts); }
    }

    public RasterBackend(double dpi) { this(dpi, Map.of()); }
    public RasterBackend(double dpi, Map<FontFamily, FontData> standardFonts) {
        if (!Double.isFinite(dpi) || dpi < 1 || dpi > 1200) { throw new IllegalArgumentException("DPI must be in [1,1200]"); }
        this.dpi = dpi;
        this.standardFonts = Map.copyOf(standardFonts);
    }

    @Override
    public void write(DisplayDocument document, OutputStream output) throws IOException {
        Objects.requireNonNull(output, "output");
        write(document, output, com.mirakyux.export4j.model.processing.ProcessingPolicy.STRICT);
    }

    public com.mirakyux.export4j.model.processing.ProcessingReport write(DisplayDocument document, OutputStream output,
            com.mirakyux.export4j.model.processing.ProcessingPolicy policy) throws IOException {
        Objects.requireNonNull(output, "output");
        var pages = prepare(document);
        var issues = com.mirakyux.export4j.graphics.core.GraphicsResources.colorIssues(pages, "PNG", Objects.requireNonNull(policy, "policy"));
        try (var zip = new ZipOutputStream(new Borrowed(output))) {
            for (int index = 0; index < pages.size(); index++) {
                com.mirakyux.export4j.model.ExecutionContext.progress("PNG_PAGE", "", index, index, pages.size());
                var entry = new ZipEntry(String.format(Locale.ROOT, "page-%05d.png", index + 1));
                entry.setTime(0);
                zip.putNextEntry(entry);
                var image = render(pages.get(index));
                try { writePng(image, zip); } finally { image.flush(); }
                zip.closeEntry();
            }
        }
        return new com.mirakyux.export4j.model.processing.ProcessingReport(com.mirakyux.export4j.model.processing.ProcessingReport.Phase.ACTUAL, issues);
    }

    public void writePage(DisplayPage page, OutputStream output) throws IOException {
        Objects.requireNonNull(output, "output");
        var pages = prepare(new DisplayDocument(DocumentMetadata.EMPTY, List.of(page)));
        com.mirakyux.export4j.graphics.core.GraphicsResources.colorIssues(pages, "PNG", com.mirakyux.export4j.model.processing.ProcessingPolicy.STRICT);
        var image = render(pages.getFirst());
        try { writePng(image, output); } finally { image.flush(); }
    }

    public com.mirakyux.export4j.model.processing.ProcessingReport writePage(DisplayPage page, OutputStream output,
            com.mirakyux.export4j.model.processing.ProcessingPolicy policy) throws IOException {
        var pages = prepare(new DisplayDocument(DocumentMetadata.EMPTY, List.of(page)));
        var issues = com.mirakyux.export4j.graphics.core.GraphicsResources.colorIssues(pages, "PNG", Objects.requireNonNull(policy, "policy"));
        var image = render(pages.getFirst());
        try { writePng(image, Objects.requireNonNull(output, "output")); } finally { image.flush(); }
        return new com.mirakyux.export4j.model.processing.ProcessingReport(com.mirakyux.export4j.model.processing.ProcessingReport.Phase.ACTUAL, issues);
    }

    /** A reusable backend for exactly one laid-out page as a standard PNG, using this DPI/fonts.
     * Rejects multiple pages, interactive extras and pixel overflow before writing any bytes.
     * Output is borrowed and is neither closed nor flushed. No pagination occurs in this backend. */
    public DocumentBackend singlePage() {
        return (document, output) -> {
            Objects.requireNonNull(document, "document"); Objects.requireNonNull(output, "output");
            if (document.pages().size() != 1) { throw new IllegalArgumentException("single PNG requires exactly one page"); }
            com.mirakyux.export4j.model.ExecutionContext.progress("PNG_PAGE", "", 0, 0, 1);
            var prepared = prepare(document);
            com.mirakyux.export4j.graphics.core.GraphicsResources.colorIssues(prepared, "PNG", com.mirakyux.export4j.model.processing.ProcessingPolicy.STRICT);
            var image = render(prepared.getFirst());
            try { writePng(image, new Borrowed(output)); } finally { image.flush(); }
        };
    }

    private List<DisplayPage> prepare(DisplayDocument document) {
        if (!document.accessibility().children().isEmpty()) { throw new IllegalArgumentException("PNG output does not preserve the explicit accessibility tree"); }
        long pixels = 0;
        for (var page : document.pages()) {
            double w = Math.ceil(page.size().width() * dpi / 72);
            double h = Math.ceil(page.size().height() * dpi / 72);
            if (w > Integer.MAX_VALUE || h > Integer.MAX_VALUE || w * h > 100_000_000 - pixels) { throw new IllegalArgumentException("raster output pixel budget exceeded"); }
            pixels += (long) w * (long) h;
        }
        return StaticBackendSupport.outlines(document, standardFonts);
    }

    private BufferedImage render(DisplayPage page) {
        return com.mirakyux.export4j.graphics.core.PrecisionRaster.render(page.commands(), page.size().width(), page.size().height(), dpi / 72, true);
    }

    private static void writePng(BufferedImage image, OutputStream output) throws IOException {
        if (!ImageIO.write(image, "png", output)) { throw new IOException("PNG writer unavailable"); }
    }

    private static final class Borrowed extends FilterOutputStream {
        Borrowed(OutputStream output) { super(output); }
        @Override public void write(byte[] bytes, int start, int length) throws IOException { out.write(bytes, start, length); }
        @Override public void close() { }
        @Override public void flush() { }
    }
}
