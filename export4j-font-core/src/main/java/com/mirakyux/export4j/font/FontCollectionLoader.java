// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Objects;

/** Bounded TTC/OTC member extraction into a standalone sfnt, followed by normal eager font validation.
 * Member index is zero based and mandatory; never silently chooses a face. Borrowed streams remain open.
 */
public final class FontCollectionLoader {
    private FontCollectionLoader() { }

    public static FontFace load(byte[] collection, int member) throws IOException {
        return load(collection, member, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
    }

    public static FontFace load(InputStream collection, int member, int maxBytes) throws IOException {
        requireLimit(maxBytes);
        return load(Objects.requireNonNull(collection, "collection").readNBytes(maxBytes + 1), member, maxBytes);
    }

    public static FontFace load(byte[] collection, int member, int maxBytes) throws IOException {
        Objects.requireNonNull(collection, "collection");
        requireLimit(maxBytes);
        if (collection.length > maxBytes) { throw new FontLoadException(FontLoadException.Code.BUDGET_EXCEEDED, "font collection byte budget exceeded"); }
        var input = ByteBuffer.wrap(collection.clone()).order(ByteOrder.BIG_ENDIAN);
        try {
            if (input.remaining() < 12 || input.getInt(0) != 0x74746366) { throw invalid(); }
            int version = input.getInt(4);
            long members = unsigned(input.getInt(8));
            if ((version != 0x00010000 && version != 0x00020000) || members < 1 || members > 1024
                    || 12 + members * 4 > input.capacity() || member < 0 || member >= members) { throw invalid(); }
            int base = offset(input.getInt(12 + member * 4), input.capacity(), 12);
            int signature = input.getInt(base);
            int tables = Short.toUnsignedInt(input.getShort(base + 4));
            if ((signature != 0x00010000 && signature != 0x4f54544f) || tables < 1 || tables > 4096
                    || (long) base + 12 + tables * 16L > input.capacity()) { throw invalid(); }
            int length = 12 + tables * 16;
            var tags = new HashSet<Integer>();
            for (int i = 0; i < tables; i++) {
                int record = base + 12 + i * 16;
                if (!tags.add(input.getInt(record))) { throw invalid(); }
                long size = unsigned(input.getInt(record + 12));
                offset(input.getInt(record + 8), input.capacity(), size);
                long next = length + ((size + 3) & ~3L);
                if (next > maxBytes) { throw new FontLoadException(FontLoadException.Code.BUDGET_EXCEEDED, "extracted font byte budget exceeded"); }
                length = (int) next;
            }
            var output = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
            output.putInt(signature).putShort((short) tables);
            int power = Integer.highestOneBit(tables);
            output.putShort((short) (power * 16)).putShort((short) Integer.numberOfTrailingZeros(power))
                    .putShort((short) (tables * 16 - power * 16));
            int cursor = 12 + tables * 16;
            int head = -1;
            for (int i = 0; i < tables; i++) {
                int record = base + 12 + i * 16;
                int tag = input.getInt(record);
                int size = input.getInt(record + 12);
                int from = input.getInt(record + 8);
                System.arraycopy(input.array(), from, output.array(), cursor, size);
                if (tag == 0x68656164) {
                    if (size < 12) { throw invalid(); }
                    head = cursor;
                    output.putInt(cursor + 8, 0);
                }
                int directory = 12 + i * 16;
                output.putInt(directory, tag).putInt(directory + 4, checksum(output, cursor, (size + 3) & ~3))
                        .putInt(directory + 8, cursor).putInt(directory + 12, size);
                cursor += (size + 3) & ~3;
            }
            if (head < 0) { throw invalid(); }
            output.putInt(head + 8, 0xb1b0afba - checksum(output, 0, length));
            return TrueTypeFontLoader.load(output.array(), maxBytes);
        } catch (IndexOutOfBoundsException | IllegalArgumentException failure) {
            throw new FontLoadException(FontLoadException.Code.INVALID_FONT, "invalid font collection directory", failure);
        }
    }

    private static int checksum(ByteBuffer buffer, int start, int length) {
        int sum = 0;
        for (int i = start; i < start + length; i += 4) { sum += buffer.getInt(i); }
        return sum;
    }
    private static int offset(int value, int limit, long size) throws FontLoadException {
        long offset = unsigned(value);
        if (size < 0 || offset + size > limit) { throw invalid(); }
        return (int) offset;
    }
    private static long unsigned(int value) { return Integer.toUnsignedLong(value); }
    private static FontLoadException invalid() { return new FontLoadException(FontLoadException.Code.INVALID_FONT, "invalid collection or member index"); }
    private static void requireLimit(int value) {
        if (value <= 0 || value == Integer.MAX_VALUE) { throw new IllegalArgumentException("invalid font byte limit"); }
    }
}
