// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Set;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.mirakyux.export4j.model.ExecutionContext;

/** Session-confined compressed lossless image programs. Entries own only primitive metadata and
 * compressed byte arrays; no PDFBox object, document, source image or decoded pixels are retained.
 * Unknown dictionary properties or encoding structures simply leave the ordinary rendering path in use.
 */
final class PdfImagePrograms implements AutoCloseable {
    private static final int MAX_ENTRIES = 64;
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final Set<String> IMAGE_KEYS = Set.of("Type", "Subtype", "Width", "Height",
            "BitsPerComponent", "ColorSpace", "Filter", "Length", "DecodeParms", "SMask");
    private static final Set<String> PREDICTOR_KEYS = Set.of("Predictor", "Columns", "Colors", "BitsPerComponent");
    private final int maxEntries;
    private final long maxBytes;
    private final LinkedHashMap<String, Program> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final PdfJpegSource.Recognition jpeg = new PdfJpegSource.Recognition();
    private long compressedBytes;
    private boolean closed;

    PdfImagePrograms() { this(MAX_ENTRIES, MAX_BYTES); }

    PdfImagePrograms(int maxEntries, long maxBytes) {
        if (maxEntries <= 0 || maxEntries > MAX_ENTRIES || maxBytes <= 0 || maxBytes > MAX_BYTES) {
            throw new IllegalArgumentException("image program cache bounds exceed the supported limits");
        }
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
    }

    /** Every hit builds independent streams in the current page document. Null is an ordinary miss. */
    PDImageXObject create(PDDocument document, String id) throws IOException {
        requireOpen();
        ExecutionContext.checkpoint();
        var program = entries.get(id);
        return program == null ? null : program.create(document);
    }

    PDImageXObject createJpeg(PDDocument document, com.mirakyux.export4j.model.ImageResource source) throws IOException {
        requireOpen();
        return jpeg.create(document, source);
    }

    /** Accepts only complete, known LosslessFactory output, with an optional simple 8-bit gray SMask. */
    void remember(String id, PDImageXObject image) throws IOException {
        requireOpen();
        ExecutionContext.checkpoint();
        var stream = image.getCOSObject();
        var description = describe(stream, false);
        if (description == null) { return; }
        COSStream mask = null;
        Description maskDescription = null;
        if (stream.containsKey(COSName.SMASK)) {
            if (!(stream.getDictionaryObject(COSName.SMASK) instanceof COSStream candidate)) { return; }
            mask = candidate;
            maskDescription = describe(mask, true);
            if (maskDescription == null || maskDescription.width() != description.width()
                    || maskDescription.height() != description.height()) { return; }
        }
        long maskBytes = maskDescription == null ? 0 : maskDescription.length();
        if (description.length() > maxBytes || maskBytes > maxBytes - description.length()) { return; }
        long size = description.length() + maskBytes;
        var raster = new Raster(description, read(stream, description.length()));
        var alpha = maskDescription == null ? null : new Raster(maskDescription, read(mask, maskDescription.length()));
        var complete = new Program(raster, alpha, size);
        ExecutionContext.checkpoint();
        var previous = entries.remove(id);
        if (previous != null) { compressedBytes -= previous.size(); }
        while (entries.size() >= maxEntries || size > maxBytes - compressedBytes) {
            compressedBytes -= entries.pollFirstEntry().getValue().size();
        }
        entries.put(id, complete);
        compressedBytes += size;
    }

    private static Description describe(COSStream stream, boolean mask) {
        for (var key : stream.keySet()) {
            if (!IMAGE_KEYS.contains(key.getName()) || mask && key.equals(COSName.SMASK)) { return null; }
        }
        if (!COSName.XOBJECT.equals(stream.getDictionaryObject(COSName.TYPE))
                || !COSName.IMAGE.equals(stream.getDictionaryObject(COSName.SUBTYPE))
                || !COSName.FLATE_DECODE.equals(stream.getDictionaryObject(COSName.FILTER))
                || integer(stream, COSName.BITS_PER_COMPONENT) != 8) { return null; }
        int width = integer(stream, COSName.WIDTH), height = integer(stream, COSName.HEIGHT);
        int length = integer(stream, COSName.LENGTH);
        if (width <= 0 || height <= 0 || length < 0) { return null; }
        var colorSpace = stream.getDictionaryObject(COSName.COLORSPACE);
        boolean gray = COSName.DEVICEGRAY.equals(colorSpace);
        if (!gray && (mask || !COSName.DEVICERGB.equals(colorSpace))) { return null; }
        boolean predictor = stream.containsKey(COSName.DECODE_PARMS);
        if (predictor) {
            if (!(stream.getDictionaryObject(COSName.DECODE_PARMS) instanceof COSDictionary parameters)
                    || parameters.size() != PREDICTOR_KEYS.size()) { return null; }
            for (var key : parameters.keySet()) { if (!PREDICTOR_KEYS.contains(key.getName())) { return null; } }
            if (integer(parameters, COSName.PREDICTOR) != 15 || integer(parameters, COSName.COLUMNS) != width
                    || integer(parameters, COSName.COLORS) != (gray ? 1 : 3)
                    || integer(parameters, COSName.BITS_PER_COMPONENT) != 8) { return null; }
        }
        return new Description(width, height, gray, predictor, length);
    }

    private static int integer(COSDictionary dictionary, COSName key) {
        if (!(dictionary.getDictionaryObject(key) instanceof COSInteger value)) { return -1; }
        long number = value.longValue();
        return number >= 0 && number <= Integer.MAX_VALUE ? (int) number : -1;
    }

    private static byte[] read(COSStream stream, int length) throws IOException {
        var bytes = new byte[length];
        try (var input = stream.createRawInputStream()) {
            for (int offset = 0; offset < bytes.length;) {
                ExecutionContext.checkpoint();
                int count = input.readNBytes(bytes, offset, Math.min(16_384, bytes.length - offset));
                if (count == 0) { throw new IOException("lossless image stream ended before its declared length"); }
                offset += count;
            }
            if (input.read() != -1) { throw new IOException("lossless image stream exceeds its declared length"); }
        }
        return bytes;
    }

    private void requireOpen() {
        if (closed) { throw new IllegalStateException("PDF image program cache is closed"); }
    }

    @Override public void close() { closed = true; entries.clear(); jpeg.clear(); compressedBytes = 0; }

    private record Description(int width, int height, boolean gray, boolean predictor, int length) { }

    /** Arrays are freshly read, exclusively owned and never returned to callers. */
    private record Raster(Description description, byte[] bytes) {
        private PDImageXObject create(PDDocument document) throws IOException {
            ExecutionContext.checkpoint();
            var image = new PDImageXObject(document, new ByteArrayInputStream(bytes), COSName.FLATE_DECODE,
                    description.width(), description.height(), 8,
                    description.gray() ? PDDeviceGray.INSTANCE : PDDeviceRGB.INSTANCE);
            if (description.predictor()) {
                var parameters = new COSDictionary();
                parameters.setInt(COSName.BITS_PER_COMPONENT, 8);
                parameters.setInt(COSName.PREDICTOR, 15);
                parameters.setInt(COSName.COLUMNS, description.width());
                parameters.setInt(COSName.COLORS, description.gray() ? 1 : 3);
                image.getCOSObject().setItem(COSName.DECODE_PARMS, parameters);
            }
            return image;
        }
    }

    private record Program(Raster image, Raster alpha, long size) {
        private PDImageXObject create(PDDocument document) throws IOException {
            var created = image.create(document);
            if (alpha != null) { created.getCOSObject().setItem(COSName.SMASK, alpha.create(document)); }
            ExecutionContext.checkpoint();
            return created;
        }
    }
}
