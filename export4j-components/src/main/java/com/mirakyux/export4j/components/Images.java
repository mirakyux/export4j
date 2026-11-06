// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ImageResource;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Explicit, bounded local PNG/JPEG loading for authoring. All decoding and pixel validation
 * remain in ImageResource; this helper never fetches URLs or changes image geometry.
 * File streams are owned and closed; supplied streams remain borrowed, including on failure.
 */
public final class Images {
    public static final int DEFAULT_MAX_BYTES = ImageResource.MAX_BYTES;

    private Images() { }

    /** Loads an explicit local file now under the default encoded byte limit. */
    public static ImageResource load(Path source) throws IOException { return load(source, DEFAULT_MAX_BYTES); }

    /** Loads a local file with a positive byte limit no larger than ImageResource.MAX_BYTES. */
    public static ImageResource load(Path source, int maxBytes) throws IOException {
        Objects.requireNonNull(source, "source");
        requireLimit(maxBytes);
        ExecutionContext.checkpoint();
        try (var input = Files.newInputStream(source)) { return load(input, maxBytes); }
    }

    /** A local filesystem path, never a URL. The opened stream is closed before return. */
    public static ImageResource load(String source) throws IOException {
        return load(Path.of(Objects.requireNonNull(source, "source")));
    }

    /** A local filesystem path with an explicit encoded byte limit. */
    public static ImageResource load(String source, int maxBytes) throws IOException {
        return load(Path.of(Objects.requireNonNull(source, "source")), maxBytes);
    }

    /** Explicit local file under the default byte limit. */
    public static ImageResource load(File source) throws IOException {
        return load(Objects.requireNonNull(source, "source").toPath());
    }

    /** Explicit local file with an encoded byte limit. */
    public static ImageResource load(File source, int maxBytes) throws IOException {
        return load(Objects.requireNonNull(source, "source").toPath(), maxBytes);
    }

    /** Reads a borrowed stream now under the default byte limit, without closing it. */
    public static ImageResource load(InputStream source) throws IOException { return load(source, DEFAULT_MAX_BYTES); }

    /** Reads at most maxBytes+1 bytes, observing the current execution scope between chunks.
     * Oversize inputs fail before decoding; the borrowed stream is never closed or rewound.
     */
    public static ImageResource load(InputStream source, int maxBytes) throws IOException {
        Objects.requireNonNull(source, "source");
        requireLimit(maxBytes);
        var bytes = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[Math.min(maxBytes + 1, 8192)];
        int remaining = maxBytes + 1;
        while (remaining > 0) {
            ExecutionContext.checkpoint();
            int count = source.read(buffer, 0, Math.min(buffer.length, remaining));
            if (count < 0) { break; }
            if (count == 0) {
                int value = source.read();
                if (value < 0) { break; }
                bytes.write(value);
                remaining--;
            } else {
                bytes.write(buffer, 0, count);
                remaining -= count;
            }
        }
        ExecutionContext.checkpoint();
        if (bytes.size() > maxBytes) { throw new IOException("encoded image byte budget exceeded"); }
        return ImageResource.load(bytes.toByteArray());
    }

    private static void requireLimit(int maxBytes) {
        if (maxBytes <= 0 || maxBytes > ImageResource.MAX_BYTES) {
            throw new IllegalArgumentException("image byte limit must be positive and no greater than ImageResource.MAX_BYTES");
        }
    }
}
