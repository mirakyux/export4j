// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics.core;

import java.util.Objects;
import java.util.LinkedHashMap;
import com.mirakyux.export4j.graphics.DrawingSurface;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ImageResource;
import java.util.List;
import com.mirakyux.export4j.text.GlyphRun;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.TextLine;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.AffineTransform;
import java.util.ArrayList;

/** Converts immutable text layout to drawing commands without measuring or changing line breaks. */
public final class TextLayoutPainter {
    private TextLayoutPainter() {
    }

    /**
     * Task-confined LRU image-resource cache; never share an instance between concurrent tasks.
     * Retention is bounded by entry count and estimated decoded RGB plus alpha bytes (four per pixel).
     * Images exceeding the byte bound are drawn without retention. Encoded resources are already
     * validated immutable snapshots; RGB decoding remains deferred until a backend needs pixels.
     * Discard this cache after its painting task, or call {@link #clear()} to release its entries.
     * Emitted drawing commands independently retain the image resources they reference.
     */
    public static final class ImageCache {
        private static final int DEFAULT_MAX_ENTRIES = 128;
        private static final long DEFAULT_MAX_DECODED_BYTES = 64L * 1024 * 1024;
        private final int maxEntries;
        private final long maxDecodedBytes;
        private final LinkedHashMap<String, CachedImage> images = new LinkedHashMap<>(16, 0.75f, true);
        private long decodedBytes;

        /** Creates an empty cache retaining at most 128 entries and 64 MiB of estimated decoded bytes. */
        public ImageCache() {
            this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_DECODED_BYTES);
        }

        /** Creates an empty cache with positive entry and estimated decoded-byte limits. */
        public ImageCache(int maxEntries, long maxDecodedBytes) {
            if (maxEntries <= 0 || maxDecodedBytes <= 0) {
                throw new IllegalArgumentException("image cache limits must be positive");
            }
            this.maxEntries = maxEntries;
            this.maxDecodedBytes = maxDecodedBytes;
        }

        /** Releases cache-owned references; previously emitted commands retain their images. */
        public void clear() {
            images.clear();
            decodedBytes = 0;
        }

        private RgbImage image(ImageResource resource) {
            ExecutionContext.checkpoint();
            var cached = images.get(resource.id());
            if (cached != null) {
                return cached.image();
            }
            var image = RgbImage.from(resource);
            long estimate = 4L * resource.pixelWidth() * resource.pixelHeight();
            if (estimate > maxDecodedBytes) {
                return image;
            }
            while (images.size() >= maxEntries || decodedBytes > maxDecodedBytes - estimate) {
                var evicted = images.pollFirstEntry();
                decodedBytes -= evicted.getValue().decodedBytes();
            }
            images.put(resource.id(), new CachedImage(image, estimate));
            decodedBytes += estimate;
            return image;
        }

        private record CachedImage(RgbImage image, long decodedBytes) { }
    }

    /** Draws relative to a top-left origin in points using an independent task-local image cache. */
    public static void draw(DrawingSurface surface, ParagraphLayout layout, Point origin, RgbColor color) {
        draw(surface, layout, origin, color, new ImageCache());
    }

    /**
     * Draws relative to a top-left origin in points without measuring or changing line breaks.
     * All arguments must be non-null; the caller owns the task-confined cache and drawing surface.
     * Repeated resources reuse image identity, while every placement still emits its drawing command.
     */
    public static void draw(DrawingSurface surface, ParagraphLayout layout, Point origin, RgbColor color,
            ImageCache images) {
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(images, "images");
        for (TextLine line : layout.lines()) {
            ExecutionContext.checkpoint();
            if (!line.fields().isEmpty()) { throw new IllegalArgumentException("inline fields must be resolved before painting"); }
            double x = origin.x() + line.offsetX();
            for (var box : line.images()) {
                var item = box.image();
                var image = images.image(item.resource());
                var bounds = new Rect(x + box.x(), origin.y() + line.baselineY() + item.baselineShift() - item.height(), item.width(), item.height());
                if (item.rotation() != 0) { surface.save().transform(AffineTransform.fittedRotation(bounds, item.rotation())); }
                surface.drawImage(image, bounds);
                if (item.rotation() != 0) { surface.restore(); }
            }
            if (!line.outlines().isEmpty()) {
                double baseline = origin.y() + line.baselineY();
                String text = line.outlines().stream().map(com.mirakyux.export4j.text.TextOutline::logicalText)
                        .collect(java.util.stream.Collectors.joining());
                double height = layout.height() / layout.lines().size();
                surface.drawOutlinedText(text, List.of(), color, new Rect(x,
                        baseline - layout.lines().getFirst().baselineY(), Math.max(1, line.width()), height));
                for (var outline : line.outlines()) {
                    var ink = ink(outline.rgb(), color);
                    List<VectorPath.Segment> segments = new ArrayList<>();
                    for (var segment : outline.segments()) {
                        var c = segment.coordinates();
                        segments.add(switch (segment.operation()) {
                            case MOVE -> new VectorPath.MoveTo(new Point(x + c.get(0), baseline + c.get(1)));
                            case LINE -> new VectorPath.LineTo(new Point(x + c.get(0), baseline + c.get(1)));
                            case CURVE -> new VectorPath.CurveTo(new Point(x + c.get(0), baseline + c.get(1)),
                                    new Point(x + c.get(2), baseline + c.get(3)), new Point(x + c.get(4), baseline + c.get(5)));
                            case CLOSE -> new VectorPath.Close();
                        });
                    }
                    if (!segments.isEmpty()) { surface.fillPath(new VectorPath(segments), ink); }
                }
            }
            for (GlyphRun run : line.runs()) {
                var ink = ink(run.rgb(), color);
                double baseline = origin.y() + line.baselineY() + run.baselineShift();
                surface.drawGlyphRun(run, new Point(x, baseline), ink);
                if (run.underline()) {
                    line(surface, x, baseline + run.fontSize() * 0.1, run.width(), ink, run.fontSize());
                }
                if (run.strikeThrough()) {
                    line(surface, x, baseline - run.fontSize() * 0.3, run.width(), ink, run.fontSize());
                }
                x += run.width();
            }
        }
    }

    private static RgbColor ink(int rgb, RgbColor fallback) {
        if (rgb < 0) { return fallback; }
        if (rgb == 0) { return RgbColor.BLACK; }
        if (rgb == 0xffffff) { return RgbColor.WHITE; }
        return new RgbColor((rgb >> 16 & 255) / 255.0, (rgb >> 8 & 255) / 255.0, (rgb & 255) / 255.0);
    }

    private static void line(DrawingSurface surface, double x, double y, double width, RgbColor color, double size) {
        surface.strokePath(new VectorPath(List.of(new VectorPath.MoveTo(new Point(x, y)),
                new VectorPath.LineTo(new Point(x + width, y)))), color, Math.max(0.3, size / 18));
    }
}
