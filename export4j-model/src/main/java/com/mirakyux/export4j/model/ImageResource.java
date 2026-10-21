// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Immutable encoded PNG/JPEG, validated by bounded JDK decoding. No file/URL access or open handles.
 * Factories copy bytes; byte access returns a copy. Metadata and identity are safe to share across tasks.
 */
public final class ImageResource {
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    public static final long MAX_PIXELS = 20_000_000;
    private final byte[] bytes;
    private final String id;
    private final String mediaType;
    private final int pixelWidth;
    private final int pixelHeight;

    private ImageResource(byte[] bytes, String mediaType, int width, int height) {
        this.bytes = bytes; this.mediaType = mediaType; pixelWidth = width; pixelHeight = height;
        try { id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 is required", failure); }
    }

    /** Fully validates one raster image. Animated/multi-image inputs, other formats and oversized rasters fail. */
    public static ImageResource load(byte[] encoded) throws IOException {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length == 0 || encoded.length > MAX_BYTES) { throw new IOException("encoded image byte budget exceeded"); }
        ExecutionContext.checkpoint();
        byte[] snapshot = encoded.clone();
        rejectAnimation(snapshot);
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(snapshot))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) { throw new IOException("unrecognized image format"); }
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg") && !format.equals("jpg")) { throw new IOException("only PNG/JPEG images are supported"); }
                reader.setInput(input, false, true);
                int width = reader.getWidth(0); int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) { throw new IOException("decoded image pixel budget exceeded"); }
                if (reader.getNumImages(true) != 1) { throw new IOException("multi-image resources are unsupported"); }
                var raster = reader.read(0);
                if (raster == null) { throw new IOException("image decode failed"); }
                raster.flush(); ExecutionContext.checkpoint();
                return new ImageResource(snapshot, format.equals("png") ? "image/png" : "image/jpeg", width, height);
            } finally { reader.dispose(); }
        }
    }

    private static void rejectAnimation(byte[] bytes) throws IOException {
        if (bytes.length < 8 || bytes[0] != (byte) 137 || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G') { return; }
        for (long offset = 8; offset + 12 <= bytes.length;) {
            int start = (int) offset;
            long length = Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(bytes, start, 4).getInt());
            if (length > bytes.length - offset - 12) { throw new IOException("truncated PNG chunk"); }
            if (bytes[start + 4] == 'a' && bytes[start + 5] == 'c' && bytes[start + 6] == 'T' && bytes[start + 7] == 'L') {
                throw new IOException("animated PNG resources are unsupported");
            }
            offset += length + 12;
        }
    }

    public String id() { return id; }
    public String mediaType() { return mediaType; }
    public int pixelWidth() { return pixelWidth; }
    public int pixelHeight() { return pixelHeight; }
    public int byteSize() { return bytes.length; }
    public byte[] bytes() { return bytes.clone(); }
    @Override public boolean equals(Object value) { return value instanceof ImageResource other && id.equals(other.id); }
    @Override public int hashCode() { return id.hashCode(); }
    @Override public String toString() { return "ImageResource[" + mediaType + ", " + pixelWidth + "x" + pixelHeight + ", " + id + "]"; }
}
