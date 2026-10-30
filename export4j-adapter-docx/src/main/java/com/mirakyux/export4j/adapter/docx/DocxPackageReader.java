// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import com.mirakyux.export4j.model.ExecutionContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipInputStream;

/** Validates the complete bounded OPC ZIP before interpreting its parts; never extracts files. */
final class DocxPackageReader {
    private record Entry(long size, long compressed, long crc, int method) { }
    private DocxPackageReader() { }

    static Map<String, byte[]> read(InputStream input, DocxLimits limits) throws IOException {
        byte[] encoded = encoded(input, limits.maxInputBytes());
        var directory = directory(encoded, limits);
        var result = new LinkedHashMap<String, byte[]>(); var seen = new HashSet<String>();
        long expanded = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(encoded), StandardCharsets.UTF_8)) {
            for (var item = zip.getNextEntry(); item != null; item = zip.getNextEntry()) {
                ExecutionContext.checkpoint();
                var expected = directory.get(item.getName());
                if (expected == null || !seen.add(item.getName())) { throw new IOException("inconsistent DOCX ZIP entry"); }
                var content = new ByteArrayOutputStream(); var buffer = new byte[8192];
                for (int count; (count = zip.read(buffer)) != -1;) {
                    ExecutionContext.checkpoint();
                    if (count > limits.maxEntryBytes() - content.size() || count > limits.maxExpandedBytes() - expanded) {
                        throw new IOException("DOCX expanded byte budget exceeded");
                    }
                    content.write(buffer, 0, count); expanded += count;
                }
                if (expected.size() != content.size() || expected.compressed() != item.getCompressedSize()
                        || expected.crc() != item.getCrc() || expected.method() != item.getMethod()) {
                    throw new IOException("DOCX ZIP directory differs from content");
                }
                if (item.isDirectory() && content.size() != 0) { throw new IOException("nonempty DOCX ZIP directory"); }
                result.put(item.getName(), content.toByteArray());
            }
        } catch (IllegalArgumentException failure) { throw new IOException("invalid DOCX ZIP encoding", failure); }
        if (!seen.equals(directory.keySet())) { throw new IOException("incomplete DOCX ZIP"); }
        return result;
    }

    private static byte[] encoded(InputStream input, long maximum) throws IOException {
        var result = new ByteArrayOutputStream(); var buffer = new byte[8192];
        for (;;) {
            ExecutionContext.checkpoint(); int count = input.read(buffer);
            if (count < 0) { return result.toByteArray(); }
            if (count == 0) {
                int value = input.read(); if (value < 0) { return result.toByteArray(); }
                buffer[0] = (byte) value; count = 1;
            }
            if (count > maximum - result.size() || count > Integer.MAX_VALUE - 8 - result.size()) {
                throw new IOException("DOCX compressed input budget exceeded");
            }
            result.write(buffer, 0, count);
        }
    }

    /** Classic single-disk ZIP, STORED/DEFLATED and standard data descriptors. ZIP64,
     * encryption, unreferenced local data and inconsistent directory views are rejected. */
    private static Map<String, Entry> directory(byte[] encoded, DocxLimits limits) throws IOException {
        if (encoded.length < 22) { throw new IOException("incomplete DOCX ZIP directory"); }
        var data = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN); int end = -1;
        for (int offset = encoded.length - 22; offset >= Math.max(0, encoded.length - 65557); offset--) {
            if (data.getInt(offset) == 0x06054b50 && offset + 22 + u16(data, offset + 20) == encoded.length) { end = offset; break; }
        }
        if (end < 0 || u16(data, end + 4) != 0 || u16(data, end + 6) != 0 || u16(data, end + 8) != u16(data, end + 10)) {
            throw new IOException("invalid DOCX ZIP directory");
        }
        int count = u16(data, end + 10); long start = u32(data, end + 16);
        if (count == 0 || count == 65535 || count > limits.maxEntries() || start + u32(data, end + 12) != end) {
            throw new IOException("DOCX ZIP directory budget or bounds exceeded");
        }
        var entries = new LinkedHashMap<String, Entry>(); var ranges = new TreeMap<Integer, Integer>();
        int offset = (int) start; long expanded = 0;
        while (offset < end) {
            ExecutionContext.checkpoint();
            if (end - offset < 46 || data.getInt(offset) != 0x02014b50) { throw new IOException("invalid DOCX ZIP entry"); }
            int flags = u16(data, offset + 8), method = u16(data, offset + 10);
            int nameSize = u16(data, offset + 28), extraSize = u16(data, offset + 30), commentSize = u16(data, offset + 32);
            long next = (long) offset + 46 + nameSize + extraSize + commentSize;
            long compressed = u32(data, offset + 20), size = u32(data, offset + 24), local = u32(data, offset + 42);
            if (next > end || u16(data, offset + 6) > 20 || u16(data, offset + 34) != 0 || (flags & ~0x080e) != 0
                    || method != 0 && method != 8 || method == 0 && (flags & 14) != 0
                    || local + 30 > start || compressed == 0xffffffffL || size == 0xffffffffL) {
                throw new IOException("unsupported or truncated DOCX ZIP entry");
            }
            String name;
            try { name = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(encoded, offset + 46, nameSize)).toString(); }
            catch (CharacterCodingException failure) { throw new IOException("invalid DOCX ZIP entry encoding", failure); }
            path(name);
            if (size > limits.maxEntryBytes() || size > limits.maxExpandedBytes() - expanded || entries.size() >= limits.maxEntries()) {
                throw new IOException("DOCX expanded byte or entry budget exceeded");
            }
            expanded += size;
            var entry = new Entry(size, compressed, u32(data, offset + 16), method);
            if (entries.putIfAbsent(name, entry) != null) { throw new IOException("duplicate DOCX ZIP entry"); }
            int localOffset = (int) local;
            if (data.getInt(localOffset) != 0x04034b50 || u16(data, localOffset + 4) > 20
                    || u16(data, localOffset + 6) != flags || u16(data, localOffset + 8) != method
                    || u16(data, localOffset + 26) != nameSize) { throw new IOException("inconsistent DOCX ZIP local header"); }
            long content = local + 30 + nameSize + u16(data, localOffset + 28), contentEnd = content + compressed;
            if (contentEnd > start || !Arrays.equals(encoded, offset + 46, offset + 46 + nameSize,
                    encoded, localOffset + 30, localOffset + 30 + nameSize)) {
                throw new IOException("invalid DOCX ZIP local bounds or name");
            }
            long rangeEnd = contentEnd;
            if ((flags & 8) != 0) {
                if (rangeEnd + 12 > start) { throw new IOException("incomplete DOCX ZIP descriptor"); }
                int descriptor = (int) rangeEnd;
                if (data.getInt(descriptor) == 0x08074b50) { descriptor += 4; }
                rangeEnd = (long) descriptor + 12;
                if (rangeEnd > start || u32(data, descriptor) != entry.crc()
                        || u32(data, descriptor + 4) != compressed || u32(data, descriptor + 8) != size) {
                    throw new IOException("inconsistent DOCX ZIP descriptor");
                }
            } else if (u32(data, localOffset + 14) != entry.crc() || u32(data, localOffset + 18) != compressed
                    || u32(data, localOffset + 22) != size) { throw new IOException("inconsistent DOCX ZIP local sizes or CRC"); }
            if (ranges.putIfAbsent(localOffset, (int) rangeEnd) != null) { throw new IOException("duplicate DOCX ZIP local entry"); }
            offset = (int) next;
        }
        if (entries.size() != count) { throw new IOException("DOCX ZIP entry count mismatch"); }
        int previousEnd = 0;
        for (var range : ranges.entrySet()) {
            if (range.getKey() != previousEnd) { throw new IOException("overlapping or unreferenced DOCX ZIP content"); }
            previousEnd = range.getValue();
        }
        if (previousEnd != start) { throw new IOException("unreferenced DOCX ZIP content"); }
        return entries;
    }

    private static void path(String name) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":") || name.indexOf(0) >= 0
                || List.of(name.split("/", -1)).stream().anyMatch(part -> part.equals(".") || part.equals(".."))) {
            throw new IOException("unsafe DOCX package path");
        }
    }
    private static int u16(ByteBuffer data, int offset) { return Short.toUnsignedInt(data.getShort(offset)); }
    private static long u32(ByteBuffer data, int offset) { return Integer.toUnsignedLong(data.getInt(offset)); }
}
