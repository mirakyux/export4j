// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Immutable exact image candidates and ZIP. Image names are generated, never source filenames.
 * Encoded bytes are bounded independently of decoded pixels. Concurrent writes to distinct targets are safe.
 */
public final class PreparedPdfImages {
    public static final class Image {
        private final String name;
        private final String mediaType;
        private final int width;
        private final int height;
        private final List<Integer> sourcePages;
        private final byte[] bytes;
        private final ExecutionOptions execution;
        Image(String name, String mediaType, int width, int height, List<Integer> sourcePages, byte[] bytes, ExecutionOptions execution) {
            this.name = name; this.mediaType = mediaType; this.width = width; this.height = height;
            this.sourcePages = List.copyOf(sourcePages); this.bytes = bytes; this.execution = execution;
        }
        public String name() { return name; }
        public String mediaType() { return mediaType; }
        public int width() { return width; }
        public int height() { return height; }
        public List<Integer> sourcePages() { return sourcePages; }
        public int byteSize() { return bytes.length; }
        public byte[] bytes() { return bytes.clone(); }
        /** Borrowed stream remains open and is not flushed. */
        public void to(OutputStream output) throws IOException { PdfImageSupport.write(bytes, output, execution); }
        /** Same-directory atomic file replacement. */
        public void to(Path output) throws IOException { PdfImageSupport.write(bytes, output, execution); }
    }
    public record Result(int imageCount, long outputBytes, ProcessingReport report) { }
    private final List<Image> images;
    private final byte[] zip;
    private final ProcessingReport report;
    private final ExecutionOptions execution;
    PreparedPdfImages(List<Image> images, List<ProcessingIssue> issues, PdfLimits limits, ExecutionOptions execution) throws IOException {
        this.images = List.copyOf(images); this.execution = execution;
        report = new ProcessingReport(ProcessingReport.Phase.ACTUAL, issues);
        var output = new PdfImageSupport.Bytes(limits.maxOutputBytes());
        try (var archive = new ZipOutputStream(output)) {
            for (var image : images) {
                var entry = new ZipEntry(image.name()); entry.setTime(0); archive.putNextEntry(entry); archive.write(image.bytes); archive.closeEntry();
            }
        }
        zip = output.data();
    }
    public List<Image> images() { return images; }
    public ProcessingReport preview() { return new ProcessingReport(ProcessingReport.Phase.PREVIEW, report.issues()); }
    public Result toZip(OutputStream output) throws IOException { PdfImageSupport.write(zip, output, execution); return new Result(images.size(), zip.length, report); }
    public Result toZip(Path output) throws IOException { PdfImageSupport.write(zip, output, execution); return new Result(images.size(), zip.length, report); }
}
