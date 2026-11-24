// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.pdf;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngineProvider;
import com.mirakyux.export4j.font.FontSource;
import com.mirakyux.export4j.model.Document;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Reuses explicit font sources and a bounded cache across semantic PDF exports. */
public final class PdfExport implements AutoCloseable {
    private final DocumentEngineProvider provider;
    private final PdfBackend backend;

    private PdfExport(DocumentEngineProvider provider, PdfBackend backend) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    /** Creates an exporter owning its provider. Font sources are ordered by fallback priority. */
    public static PdfExport withFonts(FontSource... defaults) {
        return configured(DocumentEngineProvider.builder(List.of(defaults)), new PdfBackend());
    }

    /** Builds an owned provider and borrows the supplied immutable backend configuration. */
    public static PdfExport configured(DocumentEngineProvider.Builder fonts, PdfBackend backend) {
        Objects.requireNonNull(backend, "backend");
        return new PdfExport(Objects.requireNonNull(fonts, "fonts").build(), backend);
    }

    /** Writes to an existing parent directory and replaces the target atomically. */
    public void write(Document document, Path output) throws IOException {
        provider.withEngine(engine -> engine.write(document, backend, output));
    }

    /** Leaves the caller's stream open. */
    public void write(Document document, OutputStream output) throws IOException {
        provider.withEngine(engine -> engine.write(document, backend, output));
    }

    @Override public void close() { provider.close(); }
}
