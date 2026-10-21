// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

import java.util.Objects;

/**
 * Immutable, opaque RGB raster, row-major from top-left, three unsigned bytes per pixel.
 * Input and returned arrays are copied. No file decoding, remote access or resources are owned.
 */
public final class RgbImage {
    private final int width;
    private final int height;
    private final byte[] pixels;
    private final byte[] alpha;

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
        this.pixels = pixels.clone();
        Objects.requireNonNull(alpha, "alpha");
        if (alpha.length != 0 && alpha.length != pixelCount) {
            throw new IllegalArgumentException("alpha byte count must equal width * height");
        }
        this.alpha = alpha.clone();
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Returns an independent copy of the RGB bytes. */
    public byte[] pixels() {
        return pixels.clone();
    }

    public byte[] alpha() {
        return alpha.clone();
    }
}
