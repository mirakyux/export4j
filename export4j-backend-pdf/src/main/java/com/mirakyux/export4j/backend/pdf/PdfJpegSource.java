// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.io.IOException;
import java.util.Objects;
import java.util.LinkedHashMap;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ImageResource;

/** Conservative passthrough of previously decoded, validated three-component JFIF resources.
 * Grayscale, ICC, Adobe, nonstandard component identities and unknown marker structures use the existing pixel path.
 * This recognizer never substitutes for ImageResource's full image validation and never catches decode failures.
 */
final class PdfJpegSource {
    private PdfJpegSource() { }

    static PDImageXObject create(PDDocument document, ImageResource source) throws IOException {
        return create(document, source, null);
    }

    /** Only recognition decisions survive a page, keyed by the immutable source's content identity.
     * Neither encoded image bytes, decoded pixels nor PDFBox objects are retained.
     */
    static final class Recognition {
        private final LinkedHashMap<Key, Boolean> entries = new LinkedHashMap<>(16, 0.75f, true);
        private record Key(String id, int width, int height) { }
        PDImageXObject create(PDDocument document, ImageResource source) throws IOException {
            return PdfJpegSource.create(document, source, this);
        }
        private boolean supports(ImageResource source, byte[] bytes) {
            var key = new Key(source.id(), source.pixelWidth(), source.pixelHeight());
            Boolean result = entries.get(key);
            if (result == null) {
                result = PdfJpegSource.supports(bytes, key.width(), key.height());
                if (entries.size() == 256) { entries.pollFirstEntry(); }
                entries.put(key, result);
            }
            return result;
        }
        void clear() { entries.clear(); }
    }

    private static PDImageXObject create(PDDocument document, ImageResource source, Recognition recognition) throws IOException {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(source, "source");
        if (!source.mediaType().equals("image/jpeg")) { return null; }
        byte[] encoded = source.bytes();
        if (!(recognition == null ? supports(encoded, source.pixelWidth(), source.pixelHeight())
                : recognition.supports(source, encoded))) { return null; }
        ExecutionContext.checkpoint();
        // JPEGFactory retains the encoded DCT data; no additional lossy image encoding occurs.
        return JPEGFactory.createFromByteArray(document, encoded);
    }

    static boolean supports(byte[] encoded, int width, int height) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length < 4 || encoded.length > ImageResource.MAX_BYTES || width <= 0 || height <= 0
                || (encoded[0] & 255) != 0xff || (encoded[1] & 255) != 0xd8) { return false; }
        return new Header(encoded, width, height).accept();
    }

    private static final class Header {
        private final byte[] data;
        private final int width;
        private final int height;
        private final int[] componentQuantization = new int[3];
        private int position = 2;
        private int frame;
        private int components;
        private int quantizationTables;
        private int dcTables;
        private int acTables;
        private int firstScanComponents;
        private int restartInterval;
        private int nextRestart;
        private boolean jfif;
        private boolean scan;
        private boolean entropy;

        private Header(byte[] data, int width, int height) {
            this.data = data; this.width = width; this.height = height;
        }

        private boolean accept() {
            while (position < data.length) {
                ExecutionContext.checkpoint();
                int marker = marker();
                if (marker < 0 || !jfif && marker != 0xe0) { return false; }
                if (marker == 0xd9) {
                    return frame != 0 && scan && firstScanComponents == (1 << components) - 1 && position == data.length;
                }
                if (marker == 0xd8 || marker == 0x01 || marker >= 0xd0 && marker <= 0xd7
                        || data.length - position < 2) { return false; }
                int length = unsignedShort(position); position += 2;
                if (length < 2 || length - 2 > data.length - position) { return false; }
                int end = position + length - 2;
                boolean supported = switch (marker) {
                    case 0xe0 -> jfif(end);
                    case 0xc0, 0xc1, 0xc2 -> frame(marker, end);
                    case 0xdb -> quantization(end);
                    case 0xc4 -> huffman(end);
                    case 0xdd -> restart(end);
                    case 0xda -> scan(end);
                    case 0xfe -> true; // Comments have no image/color interpretation.
                    default -> false; // Includes all other APP markers, ICC APP2 and Adobe APP14.
                };
                if (!supported) { return false; }
                position = end;
                if (marker == 0xda) { entropy = true; nextRestart = 0; }
            }
            return false;
        }

        /** Reads marker boundaries, including all scans, so later color declarations cannot be missed. */
        private int marker() {
            if (!entropy) {
                if (take() != 0xff) { return -1; }
                while (position < data.length && (data[position] & 255) == 0xff) { take(); }
                return position == data.length ? -1 : take();
            }
            while (position < data.length) {
                // Entropy bytes only need an FF search. Bound each uninterrupted scan so cancellation
                // remains synchronous without calling the scalar marker reader for every data byte.
                int end = Math.min(data.length, position + 16_384);
                while (position < end && data[position] != (byte) 0xff) { position++; }
                ExecutionContext.checkpoint();
                if (position == data.length) { return -1; }
                if (position == end) { continue; }
                position++;
                int fills = 1;
                while (position < data.length && (data[position] & 255) == 0xff) { take(); fills++; }
                if (position == data.length) { return -1; }
                int marker = take();
                if (marker == 0) {
                    if (fills != 1) { return -1; }
                    continue;
                }
                if (marker >= 0xd0 && marker <= 0xd7) {
                    if (restartInterval == 0 || marker != 0xd0 + nextRestart) { return -1; }
                    nextRestart = (nextRestart + 1) & 7;
                    continue;
                }
                entropy = false;
                return marker;
            }
            return -1;
        }

        private int take() {
            if ((position & 0x3fff) == 0) { ExecutionContext.checkpoint(); }
            return data[position++] & 255;
        }

        private int unsignedShort(int at) { return (data[at] & 255) << 8 | data[at + 1] & 255; }

        private boolean jfif(int end) {
            if (jfif || end - position < 14 || data[position] != 'J' || data[position + 1] != 'F'
                    || data[position + 2] != 'I' || data[position + 3] != 'F' || data[position + 4] != 0
                    || (data[position + 5] & 255) != 1 || (data[position + 6] & 255) > 2
                    || (data[position + 7] & 255) > 2 || unsignedShort(position + 8) == 0
                    || unsignedShort(position + 10) == 0) { return false; }
            int thumbnailWidth = data[position + 12] & 255;
            int thumbnailHeight = data[position + 13] & 255;
            if (end - position != 14 + 3 * thumbnailWidth * thumbnailHeight) { return false; }
            jfif = true;
            return true;
        }

        private boolean frame(int marker, int end) {
            if (frame != 0 || end - position < 6 || (data[position] & 255) != 8
                    || unsignedShort(position + 1) != height || unsignedShort(position + 3) != width) { return false; }
            int count = data[position + 5] & 255;
            if (count != 3 || end - position != 6 + 3 * count) { return false; }
            int blocks = 0;
            for (int component = 0; component < count; component++) {
                int at = position + 6 + 3 * component;
                int sampling = data[at + 1] & 255;
                int horizontal = sampling >>> 4;
                int vertical = sampling & 15;
                int table = data[at + 2] & 255;
                if ((data[at] & 255) != component + 1 || horizontal < 1 || horizontal > 4
                        || vertical < 1 || vertical > 4 || table > 3
                        || component > 0 && sampling != 0x11) { return false; }
                blocks += horizontal * vertical;
                componentQuantization[component] = table;
            }
            if (blocks > 10) { return false; }
            components = count; frame = marker;
            return true;
        }

        private boolean quantization(int end) {
            if (position == end) { return false; }
            int at = position;
            while (at < end) {
                ExecutionContext.checkpoint();
                int table = data[at++] & 255;
                if (table > 3 || end - at < 64) { return false; } // Only 8-bit tables are admitted.
                for (int value = 0; value < 64; value++) { if (data[at++] == 0) { return false; } }
                quantizationTables |= 1 << table;
            }
            return at == end;
        }

        private boolean huffman(int end) {
            if (position == end) { return false; }
            int at = position;
            while (at < end) {
                ExecutionContext.checkpoint();
                if (end - at < 17) { return false; }
                int table = data[at++] & 255;
                int kind = table >>> 4;
                int number = table & 15;
                if (kind > 1 || number > 3) { return false; }
                int codes = 0;
                int available = 1;
                for (int length = 0; length < 16; length++) {
                    int count = data[at++] & 255;
                    codes += count; available = 2 * available - count;
                    if (available < 0) { return false; }
                }
                if (codes == 0 || codes > 256 || end - at < codes) { return false; }
                for (int code = 0; code < codes; code++) {
                    int value = data[at++] & 255;
                    if (kind == 0 ? value > 11 : (value & 15) > 10) { return false; }
                }
                if (kind == 0) { dcTables |= 1 << number; } else { acTables |= 1 << number; }
            }
            return at == end;
        }

        private boolean restart(int end) {
            if (end - position != 2) { return false; }
            restartInterval = unsignedShort(position);
            return true;
        }

        private boolean scan(int end) {
            if (frame == 0 || end - position < 6) { return false; }
            int count = data[position] & 255;
            if (count < 1 || count > components || end - position != 4 + 2 * count) { return false; }
            int tail = position + 1 + 2 * count;
            int first = data[tail] & 255;
            int last = data[tail + 1] & 255;
            int approximation = data[tail + 2] & 255;
            int high = approximation >>> 4;
            int low = approximation & 15;
            if (frame != 0xc2 && (first != 0 || last != 63 || approximation != 0)) { return false; }
            if (frame == 0xc2 && (first > last || last > 63 || high > 13 || low > 13
                    || high != 0 && high != low + 1 || first == 0 && last != 0 || first > 0 && count != 1)) { return false; }
            int included = 0;
            for (int component = 0; component < count; component++) {
                int at = position + 1 + 2 * component;
                int identity = (data[at] & 255) - 1;
                int tables = data[at + 1] & 255;
                int dc = tables >>> 4;
                int ac = tables & 15;
                if (identity < 0 || identity >= components || (included & 1 << identity) != 0 || dc > 3 || ac > 3
                        || (quantizationTables & 1 << componentQuantization[identity]) == 0) { return false; }
                included |= 1 << identity;
                if (frame != 0xc2) {
                    if ((dcTables & 1 << dc) == 0 || (acTables & 1 << ac) == 0) { return false; }
                } else if (first == 0) {
                    if (ac != 0 || high == 0 && (dcTables & 1 << dc) == 0) { return false; }
                } else if (dc != 0 || (acTables & 1 << ac) == 0) { return false; }
            }
            if (frame != 0xc2 || first == 0 && high == 0) {
                if ((firstScanComponents & included) != 0) { return false; }
                firstScanComponents |= included;
            }
            scan = true;
            return true;
        }
    }
}
