// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.raster;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
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
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.FontFamily;
import com.mirakyux.export4j.graphics.RgbColor;
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
        var pages = prepare(document);
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
    }

    public void writePage(DisplayPage page, OutputStream output) throws IOException {
        Objects.requireNonNull(output, "output");
        var pages = prepare(new DisplayDocument(DocumentMetadata.EMPTY, List.of(page)));
        var image = render(pages.getFirst());
        try { writePng(image, output); } finally { image.flush(); }
    }

    private List<DisplayPage> prepare(DisplayDocument document) {
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
        var image = new BufferedImage((int) Math.ceil(page.size().width() * dpi / 72), (int) Math.ceil(page.size().height() * dpi / 72), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        var saved = new ArrayDeque<Graphics2D>();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.scale(dpi / 72, dpi / 72);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            for (var command : page.commands()) {
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                switch (command) {
                    case DrawCommand.Save ignored -> { saved.push(graphics); graphics = (Graphics2D) graphics.create(); }
                    case DrawCommand.Restore ignored -> { graphics.dispose(); graphics = saved.pop(); }
                    case DrawCommand.Transform t -> { var m = t.matrix(); graphics.transform(new java.awt.geom.AffineTransform(m.a(), m.b(), m.c(), m.d(), m.e(), m.f())); }
                    case DrawCommand.Clip c -> { var r = c.bounds(); graphics.clip(new Rectangle2D.Double(r.x(), r.y(), r.width(), r.height())); }
                    case DrawCommand.FillRect f -> { var r = f.bounds(); graphics.setColor(color(f.color())); graphics.fill(new Rectangle2D.Double(r.x(), r.y(), r.width(), r.height())); }
                    case DrawCommand.FillPath f -> { graphics.setColor(color(f.color())); graphics.fill(StaticBackendSupport.path(f.path())); }
                    case DrawCommand.StrokePath s -> { graphics.setColor(color(s.color())); graphics.setStroke(new BasicStroke((float) s.width(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)); graphics.draw(StaticBackendSupport.path(s.path())); }
                    case DrawCommand.OutlineText t -> { graphics.setColor(color(t.color())); for (var p : t.paths()) { graphics.fill(StaticBackendSupport.path(p)); } }
                    case DrawCommand.Image i -> {
                        var r = i.bounds();
                        var transform = new java.awt.geom.AffineTransform();
                        transform.translate(r.x(), r.y());
                        transform.scale(r.width() / i.image().width(), r.height() / i.image().height());
                        graphics.drawImage(StaticBackendSupport.image(i.image()), transform, null);
                    }
                    case DrawCommand.Text ignored -> throw new IllegalStateException("text must be converted before output");
                    case DrawCommand.GlyphText ignored -> throw new IllegalStateException("glyphs must be converted before output");
                }
            }
        } finally {
            graphics.dispose();
            saved.forEach(Graphics2D::dispose);
        }
        return image;
    }

    private static Color color(RgbColor c) { return new Color((float) c.red(), (float) c.green(), (float) c.blue()); }
    private static void writePng(BufferedImage image, OutputStream output) throws IOException {
        if (!ImageIO.write(image, "png", output)) { throw new IOException("PNG writer unavailable"); }
    }

    private static final class Borrowed extends FilterOutputStream {
        Borrowed(OutputStream output) { super(output); }
        @Override public void write(byte[] bytes, int start, int length) throws IOException { out.write(bytes, start, length); }
        @Override public void close() { }
    }
}
