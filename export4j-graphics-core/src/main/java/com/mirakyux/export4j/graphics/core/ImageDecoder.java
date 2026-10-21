// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import com.mirakyux.export4j.graphics.RgbImage;

/** Bounded local PNG/JPEG decoding. No files, URLs, disk cache or implicit remote access. Thread-safe. */
public final class ImageDecoder {
    private ImageDecoder() {
    }

    /** Checks encoded bytes and declared pixel dimensions before decompression; arrays are copied into the result. */
    public static RgbImage decode(byte[] encoded, long maxBytes, long maxPixels) throws IOException {
        Objects.requireNonNull(encoded, "encoded");
        if (maxBytes <= 0 || maxPixels <= 0 || encoded.length > maxBytes) {
            throw new IllegalArgumentException("encoded image budget exceeded or invalid limits");
        }
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(encoded))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new IOException("unrecognized image format");
            }
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg") && !format.equals("jpg")) {
                    throw new IOException("only PNG and JPEG are supported");
                }
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long count = (long) width * height;
                if (width <= 0 || height <= 0 || count > maxPixels || count > Integer.MAX_VALUE / 3) {
                    throw new IOException("decoded image pixel budget exceeded");
                }
                var image = reader.read(0);
                byte[] rgb = new byte[(int) count * 3];
                byte[] alpha = image.getColorModel().hasAlpha() ? new byte[(int) count] : new byte[0];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int argb = image.getRGB(x, y);
                        int pixel = y * width + x;
                        rgb[pixel * 3] = (byte) (argb >> 16);
                        rgb[pixel * 3 + 1] = (byte) (argb >> 8);
                        rgb[pixel * 3 + 2] = (byte) argb;
                        if (alpha.length > 0) {
                            alpha[pixel] = (byte) (argb >>> 24);
                        }
                    }
                }
                return new RgbImage(width, height, rgb, alpha);
            } finally {
                reader.dispose();
            }
        }
    }
}
