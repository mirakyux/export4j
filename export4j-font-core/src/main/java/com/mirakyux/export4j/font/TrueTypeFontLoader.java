// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font;

import com.mirakyux.export4j.font.spi.FontProgramParser;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Eager, bounded static TrueType/CFF loader. Parser resources close before an immutable face is returned.
 * Successful loads share a content-addressed cache of at most 16 faces and 64 MiB of source bytes,
 * with five-minute idle expiration. Source-byte weight is not a total heap limit. An automatic
 * cleanup task removes expired references even if no further load occurs; retained caller faces remain valid.
 * Use an explicit {@link FontFaceCache} for different bounds or to disable retention.
 * The default parser is supplied by the {@code export4j-font-fontbox} runtime artifact.
 */
public final class TrueTypeFontLoader {
    public static final int DEFAULT_MAX_BYTES = 32 * 1024 * 1024;
    private static final FontFaceCache CACHE = FontFaceCache.builder().build();

    private TrueTypeFontLoader() {
    }

    /** Removes references owned by the shared cache without invalidating faces retained by callers. */
    public static void clearCache() { CACHE.clear(); }

    /** Returns shared-cache occupancy and cumulative counters; reading statistics does not trigger cleanup. */
    public static FontFaceCache.Stats cacheStats() { return CACHE.stats(); }

    /** Loads caller-owned bytes under the default size limit; never scans system fonts. */
    public static FontFace load(byte[] bytes) throws IOException {
        return load(bytes, DEFAULT_MAX_BYTES);
    }

    /** Reads a borrowed stream under the default byte limit; never closes it. */
    public static FontFace load(InputStream input) throws IOException { return load(input, DEFAULT_MAX_BYTES); }

    /** Loads an explicit local file under the default byte limit; closes the opened stream. */
    public static FontFace load(Path source) throws IOException { return load(source, DEFAULT_MAX_BYTES); }

    /** Loads an explicit local file; reads at most maxBytes+1 bytes and closes the opened stream. */
    public static FontFace load(Path source, int maxBytes) throws IOException {
        Objects.requireNonNull(source, "source"); requireLimit(maxBytes);
        try (var input = Files.newInputStream(source)) { return load(input, maxBytes); }
    }

    /** Local filesystem path, with the same bounded loading and ownership as load(Path). */
    public static FontFace load(String source) throws IOException { return load(Path.of(Objects.requireNonNull(source, "source"))); }
    /** Local filesystem path with an explicit encoded byte limit. */
    public static FontFace load(String source, int maxBytes) throws IOException { return load(Path.of(Objects.requireNonNull(source, "source")), maxBytes); }
    /** Local file, with the same bounded loading and ownership as load(Path). */
    public static FontFace load(File source) throws IOException { return load(Objects.requireNonNull(source, "source").toPath()); }
    /** Local file with an explicit encoded byte limit. */
    public static FontFace load(File source, int maxBytes) throws IOException { return load(Objects.requireNonNull(source, "source").toPath(), maxBytes); }

    /** Reads at most maxBytes+1 bytes. The borrowed input stream is never closed. */
    public static FontFace load(InputStream input, int maxBytes) throws IOException {
        Objects.requireNonNull(input, "input");
        requireLimit(maxBytes);
        return load(input.readNBytes(maxBytes + 1), maxBytes);
    }

    public static FontFace load(byte[] bytes, int maxBytes) throws IOException {
        return CACHE.load(bytes, maxBytes);
    }

    /** Parses only a loader-private, immutable snapshot. Never call with caller-owned bytes. */
    static FontFace loadSnapshotUncached(byte[] snapshot) throws IOException {
        var parser = ParserHolder.SELECTION.parser();
        if (parser == null) {
            throw new FontLoadException(FontLoadException.Code.PARSER_UNAVAILABLE,
                    ParserHolder.SELECTION.message(), ParserHolder.SELECTION.cause());
        }
        var metadata = parser.parse(ByteBuffer.wrap(snapshot).asReadOnlyBuffer());
        return new LoadedFace(FontData.takeOwnership(snapshot, metadata.subsetAllowed()),
                metadata.metrics(), metadata.mappings());
    }

    private static final class ParserHolder {
        private static final ParserSelection SELECTION = discover();

        private static ParserSelection discover() {
            try {
                var providers = ServiceLoader.load(FontProgramParser.class, TrueTypeFontLoader.class.getClassLoader())
                        .stream().toList();
                if (providers.isEmpty()) {
                    return new ParserSelection(null, "font parser unavailable: add export4j-font-fontbox", null);
                }
                if (providers.size() != 1) {
                    return new ParserSelection(null, "multiple font parser providers are available", null);
                }
                return new ParserSelection(providers.getFirst().get(), null, null);
            } catch (ServiceConfigurationError | LinkageError failure) {
                return new ParserSelection(null, "font parser provider could not be initialized", failure);
            }
        }
    }

    private record ParserSelection(FontProgramParser parser, String message, Throwable cause) { }

    private static void requireLimit(int maxBytes) {
        if (maxBytes <= 0 || maxBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxBytes must be positive and less than Integer.MAX_VALUE");
        }
    }

    private record LoadedFace(FontData data, FontMetrics metrics,
            Map<Integer, GlyphMetrics> mappings) implements FontFace {
        @Override
        public Optional<GlyphMetrics> glyph(int codePoint) {
            return Optional.ofNullable(mappings.get(codePoint));
        }
    }
}
