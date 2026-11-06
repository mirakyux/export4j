// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ImageResource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Immutable RGB raster with optional straight alpha, row-major from top-left, three unsigned bytes per pixel.
 * Input and returned arrays are copied. A validated PNG/JPEG source can retain its encoding;
 * RGB/alpha samples then decode once on demand, using only the supplied memory snapshot.
 * Thread-safe; no file/URL access, disk cache or open resources are retained.
 */
public final class RgbImage {
    private final int width;
    private final int height;
    private final ImageResource source;
    private volatile Samples samples;
    private volatile String id;

    private record Samples(byte[] pixels, byte[] alpha) { }

    public RgbImage(int width, int height, byte[] pixels) {
        this(width, height, pixels, new byte[0]);
    }

    /** Optional one-byte-per-pixel straight alpha, or an empty array for opaque images. */
    public RgbImage(int width, int height, byte[] pixels, byte[] alpha) {
        Objects.requireNonNull(pixels, "pixels");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("image dimensions must be positive");
        }
        long pixelCount = (long) width * height;
        if (pixelCount > Integer.MAX_VALUE / 3 || pixelCount * 3 != pixels.length) {
            throw new IllegalArgumentException("RGB byte count must equal width * height * 3");
        }
        this.width = width;
        this.height = height;
        Objects.requireNonNull(alpha, "alpha");
        if (alpha.length != 0 && alpha.length != pixelCount) {
            throw new IllegalArgumentException("alpha byte count must equal width * height");
        }
        this.source = null;
        this.samples = new Samples(pixels.clone(), alpha.clone());
    }

    private RgbImage(ImageResource source) {
        this.source = Objects.requireNonNull(source, "source");
        width = source.pixelWidth();
        height = source.pixelHeight();
        id = "encoded:" + source.id();
    }

    /** Retains an already validated immutable PNG/JPEG. No decoding or resource ownership transfer.
     * Pixel access remains equivalent to bounded JDK decoding and always returns independent arrays. */
    public static RgbImage from(ImageResource source) { return new RgbImage(source); }

    /** Optional original immutable encoded snapshot, suitable for lossless backend preservation. */
    public Optional<ImageResource> encodedSource() { return Optional.ofNullable(source); }

    /** Stable SHA-256 identity of the source encoding or dimensions/RGB/alpha samples.
     * Different encodings of the same visible image need not have the same identity. */
    public String id() {
        String value = id;
        if (value != null) { return value; }
        synchronized (this) {
            if (id == null) {
                var digest = digest();
                integer(digest, width); integer(digest, height);
                update(digest, samples.pixels());
                integer(digest, samples.alpha().length);
                update(digest, samples.alpha());
                id = "rgb:" + HexFormat.of().formatHex(digest.digest());
            }
            return id;
        }
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Returns an independent copy of the RGB bytes. */
    public byte[] pixels() {
        return samples().pixels().clone();
    }

    public byte[] alpha() {
        // JPEG has no alpha; inspecting opacity must not force RGB expansion.
        if (source != null && source.mediaType().equals("image/jpeg")) { return new byte[0]; }
        return samples().alpha().clone();
    }

    private Samples samples() {
        Samples value = samples;
        if (value != null) { return value; }
        synchronized (this) {
            if (samples == null) { samples = decode(); }
            return samples;
        }
    }

    private Samples decode() {
        ExecutionContext.checkpoint();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(source.bytes()))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) { throw new IOException("validated raster has no decoder"); }
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (reader.getWidth(0) != width || reader.getHeight(0) != height) {
                    throw new IOException("validated raster dimensions changed");
                }
                var image = reader.read(0);
                try {
                    int count = Math.multiplyExact(width, height);
                    byte[] rgb = new byte[Math.multiplyExact(count, 3)];
                    byte[] alpha = image.getColorModel().hasAlpha() ? new byte[count] : new byte[0];
                    int[] row = new int[width];
                    for (int y = 0; y < height; y++) {
                        ExecutionContext.checkpoint();
                        image.getRGB(0, y, width, 1, row, 0, width);
                        for (int x = 0; x < width; x++) {
                            int argb = row[x], pixel = y * width + x;
                            rgb[pixel * 3] = (byte) (argb >> 16);
                            rgb[pixel * 3 + 1] = (byte) (argb >> 8);
                            rgb[pixel * 3 + 2] = (byte) argb;
                            if (alpha.length != 0) { alpha[pixel] = (byte) (argb >>> 24); }
                        }
                    }
                    return new Samples(rgb, alpha);
                } finally { image.flush(); }
            } finally { reader.dispose(); }
        } catch (IOException failure) {
            throw new IllegalStateException("validated raster cannot be decoded", failure);
        }
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 is required", failure); }
    }

    private static void integer(MessageDigest digest, int value) {
        for (int shift = 24; shift >= 0; shift -= 8) { digest.update((byte) (value >>> shift)); }
    }

    private static void update(MessageDigest digest, byte[] bytes) {
        for (int offset = 0; offset < bytes.length;) {
            ExecutionContext.checkpoint();
            int length = Math.min(16_384, bytes.length - offset);
            digest.update(bytes, offset, length); offset += length;
        }
    }
}
