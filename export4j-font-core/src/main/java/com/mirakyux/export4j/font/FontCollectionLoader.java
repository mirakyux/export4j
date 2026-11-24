// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;

/** Bounded TTC/OTC member extraction into a standalone sfnt, followed by normal eager font validation.
 * Member index is zero based and mandatory; never silently chooses a face. Borrowed streams remain open.
 */
public final class FontCollectionLoader {
    private static final Runnable NO_CHECKPOINT = () -> { };

    private FontCollectionLoader() { }

    public static FontFace load(byte[] collection, int member) throws IOException {
        return load(collection, member, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
    }

    /** Explicit zero-based member under the default byte limit; the borrowed stream remains open. */
    public static FontFace load(InputStream collection, int member) throws IOException {
        return load(collection, member, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
    }

    /** Loads an explicit local collection and closes its stream; member is zero based. */
    public static FontFace load(Path collection, int member) throws IOException {
        return load(collection, member, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
    }

    /** Bounded local-file loading with an explicit zero-based member; closes the opened stream. */
    public static FontFace load(Path collection, int member, int maxBytes) throws IOException {
        Objects.requireNonNull(collection, "collection"); requireLimit(maxBytes);
        try (var input = Files.newInputStream(collection)) { return load(input, member, maxBytes); }
    }

    /** Local filesystem path; never selects a collection member implicitly. */
    public static FontFace load(String collection, int member) throws IOException {
        return load(Path.of(Objects.requireNonNull(collection, "collection")), member);
    }
    /** Local filesystem path with an explicit encoded byte limit. */
    public static FontFace load(String collection, int member, int maxBytes) throws IOException {
        return load(Path.of(Objects.requireNonNull(collection, "collection")), member, maxBytes);
    }
    /** Local file; never selects a collection member implicitly. */
    public static FontFace load(File collection, int member) throws IOException {
        return load(Objects.requireNonNull(collection, "collection").toPath(), member);
    }
    /** Local file with an explicit encoded byte limit. */
    public static FontFace load(File collection, int member, int maxBytes) throws IOException {
        return load(Objects.requireNonNull(collection, "collection").toPath(), member, maxBytes);
    }

    public static FontFace load(InputStream collection, int member, int maxBytes) throws IOException {
        requireLimit(maxBytes);
        return load(Objects.requireNonNull(collection, "collection").readNBytes(maxBytes + 1), member, maxBytes);
    }

    public static FontFace load(byte[] collection, int member, int maxBytes) throws IOException {
        Objects.requireNonNull(collection, "collection");
        requireLimit(maxBytes);
        requireCollectionBytes(collection, maxBytes);
        return TrueTypeFontLoader.load(extract(collection.clone(), member, null, maxBytes, NO_CHECKPOINT), maxBytes);
    }

    // A local source owns the input snapshot and uses the caller's cache for the extracted face.
    static byte[] extract(Path collection, int member, String postScriptName, Runnable checkpoint) throws IOException {
        int maxBytes = TrueTypeFontLoader.DEFAULT_MAX_BYTES;
        checkpoint.run();
        byte[] snapshot;
        try (var input = Files.newInputStream(collection)) {
            snapshot = input.readNBytes(maxBytes + 1);
        }
        checkpoint.run();
        return extract(snapshot, member, postScriptName, maxBytes, checkpoint);
    }

    private static byte[] extract(byte[] snapshot, int member, String postScriptName, int maxBytes, Runnable checkpoint)
            throws FontLoadException {
        requireCollectionBytes(snapshot, maxBytes);
        var input = ByteBuffer.wrap(snapshot).order(ByteOrder.BIG_ENDIAN);
        try {
            if (input.remaining() < 12 || input.getInt(0) != 0x74746366) { throw invalid(); }
            int version = input.getInt(4);
            long members = unsigned(input.getInt(8));
            if ((version != 0x00010000 && version != 0x00020000) || members < 1 || members > 1024
                    || 12 + members * 4 > input.capacity()) { throw invalid(); }
            if (postScriptName != null) { member = findMember(input, (int) members, postScriptName, checkpoint); }
            if (member < 0 || member >= members) { throw invalid(); }
            int base = memberOffset(input, member);
            int signature = input.getInt(base);
            if (signature != 0x00010000 && signature != 0x4f54544f) { throw invalid(); }
            int tables = tableCount(input, base);
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
                checkpoint.run();
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
            return output.array();
        } catch (IndexOutOfBoundsException failure) {
            throw new FontLoadException(FontLoadException.Code.INVALID_FONT, "invalid font collection directory", failure);
        }
    }

    private static int findMember(ByteBuffer input, int members, String postScriptName, Runnable checkpoint)
            throws FontLoadException {
        byte[] unicode = postScriptName.getBytes(StandardCharsets.UTF_16BE);
        byte[] mac = postScriptName.getBytes(StandardCharsets.US_ASCII);
        int match = -1;
        for (int member = 0; member < members; member++) {
            checkpoint.run();
            int base = memberOffset(input, member);
            int tables = tableCount(input, base);
            for (int i = 0; i < tables; i++) {
                int entry = base + 12 + i * 16;
                if (input.getInt(entry) != 0x6e616d65) { continue; } // name
                int length = offset(input.getInt(entry + 12), input.capacity(), 0);
                int start = offset(input.getInt(entry + 8), input.capacity(), length);
                if (!hasPostScriptName(input, start, length, unicode, mac, checkpoint)) { break; }
                if (match != -1) { throw new FontLoadException(FontLoadException.Code.INVALID_FONT,
                        "more than one collection member has the requested PostScript name"); }
                match = member;
                break;
            }
        }
        if (match == -1) { throw new FontLoadException(FontLoadException.Code.INVALID_FONT,
                "font collection has no member with the requested PostScript name"); }
        return match;
    }

    private static boolean hasPostScriptName(ByteBuffer input, int start, int length, byte[] unicode, byte[] mac,
            Runnable checkpoint) throws FontLoadException {
        if (length < 6) { throw invalid(); }
        int format = Short.toUnsignedInt(input.getShort(start));
        int count = Short.toUnsignedInt(input.getShort(start + 2));
        int storage = Short.toUnsignedInt(input.getShort(start + 4));
        if ((format != 0 && format != 1) || storage < 6 + count * 12 || storage > length) { throw invalid(); }
        for (int i = 0; i < count; i++) {
            if ((i & 255) == 0) { checkpoint.run(); }
            int entry = start + 6 + i * 12;
            if (input.getShort(entry + 6) != 6) { continue; } // PostScript name
            int platform = Short.toUnsignedInt(input.getShort(entry));
            int encoding = Short.toUnsignedInt(input.getShort(entry + 2));
            byte[] wanted;
            if (platform == 0 || platform == 3 && (encoding == 0 || encoding == 1 || encoding == 10)) { wanted = unicode; }
            else if (platform == 1 && encoding == 0) { wanted = mac; }
            else { continue; }
            int bytes = Short.toUnsignedInt(input.getShort(entry + 8));
            int relative = storage + Short.toUnsignedInt(input.getShort(entry + 10));
            if (relative > length - bytes) { throw invalid(); }
            int at = start + relative;
            if (bytes == wanted.length && Arrays.equals(input.array(), at, at + bytes, wanted, 0, bytes)) { return true; }
        }
        return false;
    }

    private static int memberOffset(ByteBuffer input, int member) throws FontLoadException {
        return offset(input.getInt(12 + member * 4), input.capacity(), 12);
    }

    private static int tableCount(ByteBuffer input, int base) throws FontLoadException {
        int tables = Short.toUnsignedInt(input.getShort(base + 4));
        if (tables < 1 || tables > 4096 || (long) base + 12 + tables * 16L > input.capacity()) { throw invalid(); }
        return tables;
    }

    private static void requireCollectionBytes(byte[] collection, int maxBytes) throws FontLoadException {
        if (collection.length > maxBytes) {
            throw new FontLoadException(FontLoadException.Code.BUDGET_EXCEEDED, "font collection byte budget exceeded");
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
