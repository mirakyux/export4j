// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** File publication shared by the direct DOCX conveniences. */
final class DocxFileIO {
    private DocxFileIO() { }

    @FunctionalInterface
    interface Writer { void write(OutputStream output) throws IOException; }

    static void write(OutputStream output, ExecutionOptions options, Writer writer) throws IOException {
        Objects.requireNonNull(output, "output"); Objects.requireNonNull(options, "options");
        Objects.requireNonNull(writer, "writer");
        ExecutionContext.execute(options, () -> { writer.write(ExecutionContext.output(output)); return null; });
    }

    static void publish(Path output, ExecutionOptions options, Writer writer) throws IOException {
        Path target = Objects.requireNonNull(output, "output").toAbsolutePath();
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(writer, "writer");
        Path parent = target.getParent();
        if (parent == null) { throw new IOException("DOCX output requires a parent directory"); }
        Path temporary = Files.createTempFile(parent, ".export4j-docx-", ".tmp");
        try {
            ExecutionContext.execute(options, () -> {
                try (var stream = new BufferedOutputStream(Files.newOutputStream(temporary))) {
                    writer.write(ExecutionContext.output(stream));
                }
                return null;
            });
            // The scope's final cancellation/deadline check precedes the atomic commit.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException | Error failure) {
            try { Files.deleteIfExists(temporary); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
}
