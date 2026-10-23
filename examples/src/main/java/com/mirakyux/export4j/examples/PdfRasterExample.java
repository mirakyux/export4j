// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfRasterRequest;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Renders existing edited and interactive PDFs and extracts reachable image resources. */
public final class PdfRasterExample {
    private PdfRasterExample() { }
    public static void main(String[] args) throws IOException {
        var output = Path.of("examples/build/pdf-raster"); Files.createDirectories(output);
        var adapter = new PdfAdapter(); var source = adapter.input(Path.of("examples/build/pdf-page-editing/edited.pdf"), "");
        var policy = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
                .feature("FORMS", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
                .feature("COMMENTS", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
                .feature("JPEG_ENCODING", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
        var png = adapter.render(source.allPages()).dpi(144).policy(policy).prepare(); png.toZip(output.resolve("pages.zip"));
        for (var image : png.images()) { image.to(output.resolve(image.name())); }
        var jpeg = adapter.render(source.allPages()).dpi(144).format(PdfRasterRequest.Format.JPEG).background(0xEEF0F5).jpegQuality(.9).policy(policy).prepare();
        jpeg.toZip(output.resolve("jpeg-pages.zip")); for (var image : jpeg.images()) { image.to(output.resolve(image.name())); }
        var resources = adapter.extractImages(source.allPages()).prepare(); resources.toZip(output.resolve("images.zip"));
        for (var image : resources.images()) { image.to(output.resolve(image.name())); }
        var forms = adapter.render(adapter.input(Path.of("examples/build/pdf-object-editing/edited.pdf"), "").allPages()).dpi(144).policy(policy).prepare();
        forms.toZip(output.resolve("forms.zip")); for (var image : forms.images()) { image.to(output.resolve("form-" + image.name())); }
        Files.writeString(output.resolve("losses.tsv"), java.util.stream.Stream.concat(jpeg.preview().issues().stream(), forms.preview().issues().stream())
                .map(issue -> issue.feature() + "\t" + issue.effects().getFirst()).collect(java.util.stream.Collectors.joining("\n")) + "\n");
    }
}
