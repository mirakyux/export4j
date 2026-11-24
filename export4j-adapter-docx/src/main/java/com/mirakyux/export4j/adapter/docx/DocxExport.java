// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ExecutionOptions;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Objects;

/** Reusable semantic Word export with font-family declarations and native object line spacing. */
public final class DocxExport {
    private final DocxAdapter adapter;

    private DocxExport(DocxAdapter.Builder builder) {
        adapter = builder.lineSpacing(DocxAdapter.LineSpacing.OBJECTS_AT_LEAST).build();
    }

    public static DocxExport create() { return new DocxExport(DocxAdapter.builder()); }

    public static DocxExport withFonts(DocxFontFamilies fonts) {
        return new DocxExport(DocxAdapter.builder().defaultFonts(Objects.requireNonNull(fonts, "fonts")));
    }

    /** Writes to an existing parent directory and replaces the target atomically. */
    public void write(Document document, Path output) throws IOException { adapter.write(document, output); }

    /** Leaves the caller's stream open. */
    public void write(Document document, OutputStream output) throws IOException {
        adapter.write(document, output, ExecutionOptions.DEFAULT);
    }
}
