// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.ExecutionContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Explicit, bounded UTF-8 sources. Readers are never used to discover linked resources. */
final class TextImportIO {
    private TextImportIO() { }

    @FunctionalInterface
    interface Source { String read() throws IOException; }

    static String read(Path source, ImportLimits limits) throws IOException {
        Objects.requireNonNull(source, "source");
        ExecutionContext.checkpoint();
        try (var input = Files.newInputStream(source)) { return read(input, limits); }
    }

    static String read(InputStream source, ImportLimits limits) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        // A valid UTF-8 encoding needs at most three bytes per UTF-16 unit.
        // Bound decoder read-ahead as well as the decoded text, without closing the borrowed input.
        long maxBytes = 3L * limits.maxInputUtf16();
        var bounded = new InputStream() {
            private long count;
            @Override public int read() throws IOException {
                ExecutionContext.checkpoint();
                int value = source.read();
                if (value >= 0 && ++count > maxBytes) { throw new IllegalArgumentException("import input byte budget exceeded"); }
                return value;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, bytes.length);
                if (length == 0) { return 0; }
                ExecutionContext.checkpoint();
                int allowed = (int) Math.min(length, maxBytes - count + 1);
                int read = source.read(bytes, offset, allowed);
                if (read > 0) {
                    count += read;
                    if (count > maxBytes) { throw new IllegalArgumentException("import input byte budget exceeded"); }
                }
                return read;
            }
        };
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        var reader = new InputStreamReader(bounded, decoder);
        var text = new StringBuilder();
        var buffer = new char[8192];
        while (true) {
            ExecutionContext.checkpoint();
            int remaining = limits.maxInputUtf16() - text.length();
            int count = reader.read(buffer, 0, (int) Math.min(buffer.length, (long) remaining + 1));
            if (count < 0) { return text.toString(); }
            if (count == 0) { throw new IOException("UTF-8 source made no progress"); }
            if (count > remaining) { throw new IllegalArgumentException("import input budget exceeded"); }
            text.append(buffer, 0, count);
        }
    }
}
