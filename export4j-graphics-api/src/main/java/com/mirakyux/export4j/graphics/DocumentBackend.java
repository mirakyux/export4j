// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

import java.io.IOException;
import java.io.OutputStream;

/** Open extension point for output implementations; no specific file-format library types. */
public interface DocumentBackend {
    /**
     * Writes a non-null immutable document to a non-null, caller-owned stream.
     * The stream is never closed. A failed write may leave partial bytes; the caller discards them.
     *
     * @throws IOException if output fails or a backend cannot represent the requested operations
     */
    void write(DisplayDocument document, OutputStream output) throws IOException;

    /** Controlled write; supplied output remains caller-owned even on cancellation or budget exhaustion. */
    default void write(DisplayDocument document, OutputStream output, com.mirakyux.export4j.model.ExecutionOptions options) throws IOException {
        com.mirakyux.export4j.model.ExecutionContext.execute(options, () -> {
            write(document, com.mirakyux.export4j.model.ExecutionContext.output(output));
            return null;
        });
    }
}
