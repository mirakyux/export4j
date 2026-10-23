// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;

final class PdfImageSupport {
    private PdfImageSupport() { }
    static final class Bytes extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final long limit;
        Bytes(long limit) { this.limit = Math.min(limit, Integer.MAX_VALUE - 8L); }
        private void reserve(int count) throws IOException {
            ExecutionContext.checkpoint();
            if (count > limit - bytes.size()) { throw new PdfOperationException(PdfOperationException.Code.OUTPUT_LIMIT, 0, -1); }
        }
        @Override public void write(int value) throws IOException { reserve(1); bytes.write(value); }
        @Override public void write(byte[] value, int offset, int count) throws IOException { Objects.checkFromIndexSize(offset, count, value.length); reserve(count); bytes.write(value, offset, count); }
        byte[] data() { return bytes.toByteArray(); }
    }
    static byte[] encode(BufferedImage image, String format, float quality, long limit) throws IOException {
        return encode(image, format, quality, limit, 0);
    }
    static byte[] encode(BufferedImage image, String format, float quality, long limit, double dpi) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName(format);
        if (!writers.hasNext()) { throw new IOException("required raster encoder is unavailable"); }
        var writer = writers.next(); var bytes = new Bytes(limit);
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output); var parameters = writer.getDefaultWriteParam();
            if (format.equals("jpeg")) { parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); parameters.setCompressionQuality(quality); }
            var metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), parameters);
            if (dpi > 0) {
                if (format.equals("png")) {
                    var root = new IIOMetadataNode("javax_imageio_png_1.0"); var density = new IIOMetadataNode("pHYs");
                    String perMeter = Long.toString(Math.round(dpi / .0254)); density.setAttribute("pixelsPerUnitXAxis", perMeter); density.setAttribute("pixelsPerUnitYAxis", perMeter); density.setAttribute("unitSpecifier", "meter");
                    root.appendChild(density); metadata.mergeTree("javax_imageio_png_1.0", root);
                } else {
                    var root = (IIOMetadataNode) metadata.getAsTree("javax_imageio_jpeg_image_1.0");
                    var density = (IIOMetadataNode) root.getElementsByTagName("app0JFIF").item(0);
                    if (density == null) { throw new IOException("JPEG encoder does not expose density metadata"); }
                    density.setAttribute("resUnits", "1"); density.setAttribute("Xdensity", Long.toString(Math.round(dpi))); density.setAttribute("Ydensity", Long.toString(Math.round(dpi)));
                    metadata.setFromTree("javax_imageio_jpeg_image_1.0", root);
                }
            }
            writer.write(null, new IIOImage(image, null, metadata), parameters); output.flush();
            return bytes.data();
        } catch (IOException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof PdfOperationException failure) { throw failure; }
            }
            throw exception;
        } finally { writer.dispose(); }
    }
    static void write(byte[] bytes, OutputStream output, ExecutionOptions execution) throws IOException {
        Objects.requireNonNull(output, "output");
        ExecutionContext.execute(execution, () -> { ExecutionContext.output(output).write(bytes); return null; });
    }
    static void write(byte[] bytes, Path output, ExecutionOptions execution) throws IOException {
        Path target = Objects.requireNonNull(output, "output").toAbsolutePath();
        Path temporary = Files.createTempFile(target.getParent(), ".export4j-raster-", ".tmp");
        try {
            ExecutionContext.execute(execution, () -> {
                try (var stream = Files.newOutputStream(temporary)) { ExecutionContext.output(stream).write(bytes); }
                ExecutionContext.checkpoint(); Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); return null;
            });
        } catch (IOException | RuntimeException | Error failure) {
            try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { failure.addSuppressed(cleanup); } throw failure;
        }
    }
    static void loss(ProcessingPolicy policy, List<ProcessingIssue> issues, String format, String feature, String operation,
            ProcessingPolicy.Treatment treatment, String effect, int page) {
        var rule = policy.resolve(format, feature, operation);
        var issue = new ProcessingIssue("EXPORT", "PDF", format, feature, operation,
                new ProcessingIssue.Location("", "PDF_PAGE_" + page, "", List.of(), -1, -1),
                "PDF processing requires the declared treatment", rule.action(), rule.treatment(), List.of(effect));
        if (rule.action() == ProcessingPolicy.Action.ERROR) { throw new ProcessingException(issue); }
        if (rule.treatment() != treatment) { throw new IllegalArgumentException("unsupported PDF processing treatment"); }
        issues.add(issue);
    }
}
