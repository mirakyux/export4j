// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

import java.io.IOException;
import java.io.OutputStream;
import com.mirakyux.export4j.model.DocumentMetadata;

/**
 * Opens synchronous output sessions that consume prelaid-out pages in document order.
 * This SPI has no default implementation that collects pages into a complete document.
 * Backends declare their own thread-safety and retained-resource limits; page-at-a-time
 * consumption alone does not guarantee constant backend memory or immediate output bytes.
 */
public interface PageBackend {
    /**
     * Opens a session owned by the caller, with non-null metadata and a borrowed output stream.
     * The caller explicitly finishes successful output and always closes the writer, normally
     * with try-with-resources. The output stream is never closed, including on failure.
     * The returned writer is confined to the thread opening it. Opening performs no layout
     * and starts no background work. A backend may reject unsupported options before opening.
     *
     * @param metadata immutable document metadata
     * @param output caller-owned destination; failed output may contain partial bytes
     * @return a new independent session that has accepted no pages
     * @throws IOException if backend resources cannot be opened
     */
    PageWriter open(DocumentMetadata metadata, OutputStream output) throws IOException;
}
