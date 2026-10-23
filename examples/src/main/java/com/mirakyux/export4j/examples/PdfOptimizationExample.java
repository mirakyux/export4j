// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Separates lossless stream/resource optimization from explicit image quality reduction. */
public final class PdfOptimizationExample {
    private PdfOptimizationExample() { }
    public static void main(String[] args) throws IOException {
        var output = Path.of("examples/build/pdf-optimization"); Files.createDirectories(output);
        var adapter = new PdfAdapter();
        var source = adapter.input(Path.of("examples/build/pdf-page-editing/edited.pdf"), "");
        var lossless = adapter.optimize(source).prepare(); lossless.to(output.resolve("lossless.pdf"));
        var policy = ProcessingPolicy.builder().feature("IMAGE_RESAMPLING", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
                .feature("JPEG_ENCODING", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
        var sampled = adapter.optimize(source).downsampleImages(15, 10).policy(policy).prepare(); sampled.to(output.resolve("sampled.pdf"));
        var jpeg = adapter.optimize(source).downsampleImages(15, 10).jpegQuality(.9).policy(policy).prepare(); jpeg.to(output.resolve("jpeg.pdf"));
        var objects = adapter.optimize(adapter.input(Path.of("examples/build/pdf-object-editing/edited.pdf"), "")).prepare();
        objects.to(output.resolve("objects.pdf"));
        Files.writeString(output.resolve("statistics.tsv"), "mode\tinput\toutput\tcompressed\tdeduplicated\tresampled\tlosses\n"
                + row("lossless", lossless) + row("sampled", sampled) + row("jpeg", jpeg) + row("objects", objects));
    }
    private static String row(String name, com.mirakyux.export4j.adapter.pdf.PreparedPdfOptimization value) {
        var s = value.statistics(); return name + "\t" + s.inputBytes() + "\t" + s.outputBytes() + "\t" + s.recompressedStreams()
                + "\t" + s.deduplicatedResources() + "\t" + s.resampledImages() + "\t" + value.preview().losses().size() + "\n";
    }
}
