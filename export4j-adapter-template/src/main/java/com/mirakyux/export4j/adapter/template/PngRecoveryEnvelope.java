// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.model.ExecutionContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

/** PNG framing only, without pixel decompression. exJM is private, ancillary and unsafe to copy. */
final class PngRecoveryEnvelope {
    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final byte[] TYPE = "exJM".getBytes(StandardCharsets.US_ASCII);
    private PngRecoveryEnvelope() { }
    private record Framing(int end, int recovery, int recoverySize, int chunks) { }

    static byte[] encode(byte[] document, byte[] companion, XmlModelRecovery.Limits limits) throws IOException {
        var framing = inspect(document, limits);
        if (framing.recovery() >= 0) { throw new IOException("PNG already contains recovery data"); }
        long size = document.length + (companion == null ? 0L : companion.length + 12L);
        if (size > limits.maxDocumentBytes()) { throw new IOException("embedded PNG byte budget exceeded"); }
        if (companion == null) { return document.clone(); }
        if (framing.chunks() >= limits.bundle().maxEntries()) { throw new IOException("embedded PNG chunk budget exceeded"); }
        var bytes = new ByteArrayOutputStream(); var output = TemplateBundleIO.bounded(bytes, limits.maxDocumentBytes());
        output.write(document, 0, framing.end());
        output.write(ByteBuffer.allocate(4).putInt(companion.length).array()); output.write(TYPE);
        var crc = new CRC32(); crc.update(TYPE);
        for (int offset = 0; offset < companion.length;) {
            ExecutionContext.checkpoint(); int length = Math.min(8192, companion.length - offset);
            output.write(companion, offset, length); crc.update(companion, offset, length);
            offset += length;
        }
        output.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
        output.write(document, framing.end(), document.length - framing.end());
        return bytes.toByteArray();
    }

    static CommentRecoveryEnvelope.Parts decode(byte[] document, XmlModelRecovery.Limits limits) throws IOException {
        var framing = inspect(document, limits);
        if (framing.recovery() < 0) { return new CommentRecoveryEnvelope.Parts(document, null); }
        if (framing.recoverySize() > limits.bundle().maxEncodedBytes()) { throw new IOException("PNG recovery ZIP byte budget exceeded"); }
        int start = framing.recovery(), end = start + framing.recoverySize() + 12;
        byte[] standard = new byte[document.length - framing.recoverySize() - 12];
        System.arraycopy(document, 0, standard, 0, start);
        System.arraycopy(document, end, standard, start, document.length - end);
        return new CommentRecoveryEnvelope.Parts(standard, Arrays.copyOfRange(document, start + 8, end - 4));
    }

    private static Framing inspect(byte[] bytes, XmlModelRecovery.Limits limits) throws IOException {
        if (bytes.length < 8 || bytes.length > limits.maxDocumentBytes() || !Arrays.equals(SIGNATURE, Arrays.copyOf(bytes, 8))) {
            throw new IOException("invalid PNG signature or byte budget");
        }
        var data = ByteBuffer.wrap(bytes); int offset = 8, count = 0, recovery = -1, recoverySize = 0, color = -1;
        boolean image = false, imageEnded = false, palette = false;
        while (offset < bytes.length) {
            ExecutionContext.checkpoint();
            if (++count > limits.bundle().maxEntries() || bytes.length - offset < 12) { throw new IOException("PNG chunk budget or framing exceeded"); }
            int length = data.getInt(offset); long next = (long) offset + length + 12;
            if (length < 0 || next > bytes.length) { throw new IOException("invalid PNG chunk length"); }
            for (int index = 0; index < 4; index++) {
                int value = bytes[offset + 4 + index] & 255;
                if (!(value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z')) { throw new IOException("invalid PNG chunk type"); }
            }
            if ((bytes[offset + 6] & 32) != 0) { throw new IOException("invalid PNG reserved type bit"); }
            var crc = new CRC32();
            for (int position = offset + 4; position < next - 4;) {
                ExecutionContext.checkpoint(); int size = Math.min(8192, (int) next - 4 - position);
                crc.update(bytes, position, size); position += size;
            }
            if (crc.getValue() != Integer.toUnsignedLong(data.getInt((int) next - 4))) { throw new IOException("PNG chunk CRC mismatch"); }
            String type = new String(bytes, offset + 4, 4, StandardCharsets.US_ASCII);
            if (count == 1 && !type.equals("IHDR")) { throw new IOException("PNG header must be first"); }
            if (recovery >= 0 && !type.equals("IEND")) { throw new IOException("PNG recovery must immediately precede IEND"); }
            switch (type) {
                case "IHDR" -> {
                    if (count != 1 || length != 13) { throw new IOException("invalid or repeated PNG header"); }
                    int width = data.getInt(offset + 8), height = data.getInt(offset + 12);
                    int depth = bytes[offset + 16] & 255; color = bytes[offset + 17] & 255;
                    boolean validDepth = switch (color) {
                        case 0 -> depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16;
                        case 2, 4, 6 -> depth == 8 || depth == 16;
                        case 3 -> depth == 1 || depth == 2 || depth == 4 || depth == 8;
                        default -> false;
                    };
                    if (width < 1 || height < 1 || (long) width * height > limits.bundle().maxImagePixels()
                            || !validDepth || bytes[offset + 18] != 0 || bytes[offset + 19] != 0
                            || (bytes[offset + 20] & 255) > 1) { throw new IOException("unsupported PNG header or pixel budget"); }
                }
                case "PLTE" -> {
                    if (palette || image || color == 0 || color == 4 || length == 0 || length > 768 || length % 3 != 0) { throw new IOException("invalid PNG palette"); }
                    palette = true;
                }
                case "IDAT" -> {
                    if (imageEnded || color == 3 && !palette) { throw new IOException("invalid PNG image chunk order"); }
                    image = true;
                }
                case "exJM" -> {
                    if (!image || length == 0 || length > limits.bundle().maxEncodedBytes()) { throw new IOException("invalid PNG recovery payload or budget"); }
                    recovery = offset; recoverySize = length;
                }
                case "IEND" -> {
                    if (!image || length != 0 || next != bytes.length) { throw new IOException("invalid PNG end or trailing data"); }
                    return new Framing(offset, recovery, recoverySize, count);
                }
                default -> {
                    if ((bytes[offset + 4] & 32) == 0 || type.equals("acTL") || type.equals("fcTL") || type.equals("fdAT")) {
                        throw new IOException("unsupported PNG critical chunk or animation");
                    }
                }
            }
            if (image && !type.equals("IDAT")) { imageEnded = true; }
            offset = (int) next;
        }
        throw new IOException("PNG end chunk is missing");
    }
}
