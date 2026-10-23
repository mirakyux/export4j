// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfFont;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Real stream edits and separate irreversible raster redaction with explicit quality losses. */
public final class PdfContentEditingExample {
    private PdfContentEditingExample() { }
    public static void main(String[] args) throws IOException {
        var output = Path.of("examples/build/pdf-content-editing"); Files.createDirectories(output);
        var face = AuthoringExample.face("LiberationSans-Regular.ttf"); var engine = DocumentEngine.builder(FontGroup.strict(List.of(face))).build();
        var source = Document.builder().pageSettings(page -> page.size(260, 160).margins(20))
                .paragraph("SECRET KEEP").paragraph("Visible outside the selected region.").build();
        engine.export(source).backend(new PdfBackend()).to(output.resolve("source.pdf"));
        var adapter = new PdfAdapter(); var input = adapter.input(output.resolve("source.pdf"), "");
        var replacePolicy = ProcessingPolicy.builder().feature("TEXT_FONT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
                .feature("TEXT_SPACING", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
        var replacement = adapter.editText(input).replace(0, "SECRET", "PUBLIC", PdfFont.of(face.data().bytes())).policy(replacePolicy).prepare();
        replacement.to(output.resolve("replaced.pdf"));
        adapter.editObjects(input).metadata("Title", "SECRET-METADATA").attachment("sensitive.txt", "SECRET-ATTACHMENT".getBytes(StandardCharsets.UTF_8), "text/plain")
                .prepare().to(output.resolve("private.pdf"));
        var redactPolicy = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
                .feature("RASTERIZATION", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
                .feature("PAGE_GEOMETRY", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
        var redaction = adapter.redact(adapter.input(output.resolve("private.pdf"), "")).area(0, 18, 18, 52, 20).dpi(144).policy(redactPolicy).prepare();
        redaction.to(output.resolve("redacted.pdf"));
        adapter.redact(adapter.input(Path.of("examples/build/pdf-page-editing/edited.pdf"), ""))
                .area(0, 208, 128, 64, 44).policy(redactPolicy).prepare().to(output.resolve("image-redacted.pdf"));
        Files.writeString(output.resolve("losses.tsv"), java.util.stream.Stream.concat(replacement.preview().issues().stream(), redaction.preview().issues().stream())
                .map(issue -> issue.operation() + "\t" + issue.feature() + "\t" + issue.effects().getFirst()).collect(java.util.stream.Collectors.joining("\n")) + "\n");
    }
}
