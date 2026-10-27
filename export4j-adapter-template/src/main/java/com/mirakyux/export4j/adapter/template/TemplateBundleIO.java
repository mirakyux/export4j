// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Bounded ordinary ZIP I/O; never extracts entries to the filesystem. */
final class TemplateBundleIO {
    private TemplateBundleIO() { }

    static void fileName(String name) throws IOException {
        if (!name.equals("template.xml")
                && !name.matches("fragments/[A-Za-z_][A-Za-z_0-9-]{0,63}\\.xml")
                && !name.matches("images/[A-Za-z_][A-Za-z_0-9]{0,255}\\.(png|jpg|jpeg)")
                && !name.matches("fonts/[A-Za-z_][A-Za-z_0-9-]{0,63}\\.(ttf|otf|ttc|otc)")
                && !name.matches("licenses/[A-Za-z_][A-Za-z_0-9-]{0,63}\\.txt")) {
            throw new IOException("unsupported template bundle entry name");
        }
    }

    private static boolean directory(String name) {
        return name.equals("fragments/") || name.equals("images/") || name.equals("fonts/") || name.equals("licenses/");
    }

    static byte[] zip(Map<String, byte[]> entries, XmlTemplateBundle.Limits limits) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bounded(bytes, limits.maxEncodedBytes()), StandardCharsets.UTF_8)) {
            for (var item : new TreeMap<>(entries).entrySet()) {
                ExecutionContext.checkpoint();
                var header = new ZipEntry(item.getKey()); header.setTime(0);
                zip.putNextEntry(header);
                byte[] content = item.getValue();
                for (int offset = 0; offset < content.length; offset += 8192) {
                    ExecutionContext.checkpoint(); zip.write(content, offset, Math.min(8192, content.length - offset));
                }
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    static Map<String, byte[]> unzip(byte[] encoded, XmlTemplateBundle.Limits limits) throws IOException {
        return unzip(encoded, limits, false);
    }

    /** The recovery companion adds exactly one manifest to the ordinary template ZIP namespace. */
    static Map<String, byte[]> unzip(byte[] encoded, XmlTemplateBundle.Limits limits, boolean recovery) throws IOException {
        var central = centralDirectory(encoded, limits, recovery);
        var seen = new HashSet<String>(); var files = new LinkedHashMap<String, byte[]>(); int expanded = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(encoded), StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                ExecutionContext.checkpoint(); String name = entry.getName();
                var expected = central.get(name);
                if (expected == null || !seen.add(name)) { throw new IOException("inconsistent template ZIP entry"); }
                byte[] content = read(zip, limits.maxExpandedBytes() - expanded); expanded += content.length;
                var crc = new CRC32(); crc.update(content);
                if (expected.size() != content.length || expected.crc() != crc.getValue()
                        || expected.compressed() != entry.getCompressedSize() || expected.method() != entry.getMethod()) {
                    throw new IOException("template ZIP directory does not match content");
                }
                if (directory(name)) {
                    if (content.length != 0) { throw new IOException("nonempty template ZIP directory"); }
                } else { files.put(name, content); }
                zip.closeEntry();
            }
        } catch (IllegalArgumentException failure) { throw new IOException("invalid template ZIP encoding", failure); }
        if (!seen.equals(central.keySet())) { throw new IOException("incomplete template ZIP"); }
        return files;
    }

    private record Entry(long size, long compressed, long crc, int method) { }

    /** Checks the directory as well as the local streams: ZipInputStream alone accepts missing end records.
     * Single-disk classic ZIP, STORED/DEFLATED, optional directory entries/comments/extras are supported.
     * ZIP64, encrypted and trailing data are rejected; normal template budgets are well below ZIP64 sizes. */
    private static Map<String, Entry> centralDirectory(byte[] encoded, XmlTemplateBundle.Limits limits, boolean recovery) throws IOException {
        if (encoded.length < 22 || encoded.length > limits.maxEncodedBytes()) { throw new IOException("invalid template ZIP size"); }
        var data = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN); int end = -1;
        for (int pos = encoded.length - 22; pos >= Math.max(0, encoded.length - 65557); pos--) {
            if (data.getInt(pos) == 0x06054b50 && pos + 22 + u16(data, pos + 20) == encoded.length) { end = pos; break; }
        }
        if (end < 0 || u16(data, end + 4) != 0 || u16(data, end + 6) != 0
                || u16(data, end + 8) != u16(data, end + 10)) { throw new IOException("invalid template ZIP directory"); }
        int count = u16(data, end + 10); long size = u32(data, end + 12); long start = u32(data, end + 16);
        if (count == 65535 || count > limits.maxEntries() || start + size != end) {
            throw new IOException("template ZIP directory budget or bounds exceeded");
        }
        var entries = new LinkedHashMap<String, Entry>(); int offset = (int) start; long expanded = 0;
        var localRanges = new TreeMap<Integer, Integer>();
        while (offset < end) {
            ExecutionContext.checkpoint();
            if (end - offset < 46 || data.getInt(offset) != 0x02014b50) { throw new IOException("invalid template ZIP entry"); }
            int flags = u16(data, offset + 8); int method = u16(data, offset + 10);
            int nameSize = u16(data, offset + 28); int extraSize = u16(data, offset + 30); int commentSize = u16(data, offset + 32);
            long next = (long) offset + 46 + nameSize + extraSize + commentSize;
            long compressed = u32(data, offset + 20); long unpacked = u32(data, offset + 24); long local = u32(data, offset + 42);
            if (next > end || u16(data, offset + 6) > 20 || u16(data, offset + 34) != 0
                    || (flags & ~0x080e) != 0 || method != 0 && method != 8
                    || local + 30 > start || compressed == 0xffffffffL || unpacked == 0xffffffffL) {
                throw new IOException("unsupported or truncated template ZIP entry");
            }
            String name = new String(encoded, offset + 46, nameSize, StandardCharsets.US_ASCII);
            if (!directory(name) && !(recovery && name.equals("recovery.xml"))) { fileName(name); }
            expanded += unpacked;
            if (expanded > limits.maxExpandedBytes() || entries.size() >= limits.maxEntries()
                    || entries.putIfAbsent(name, new Entry(unpacked, compressed, u32(data, offset + 16), method)) != null) {
                throw new IOException("duplicate or excessive template ZIP entry");
            }
            int localOffset = (int) local;
            if (data.getInt(localOffset) != 0x04034b50 || u16(data, localOffset + 4) > 20 || u16(data, localOffset + 6) != flags
                    || u16(data, localOffset + 8) != method || u16(data, localOffset + 26) != nameSize) {
                throw new IOException("inconsistent template ZIP local header");
            }
            long content = local + 30 + nameSize + u16(data, localOffset + 28); long contentEnd = content + compressed;
            if (contentEnd > start || !name.equals(new String(encoded, localOffset + 30, nameSize, StandardCharsets.US_ASCII))
                    || localRanges.putIfAbsent(localOffset, (int) contentEnd) != null) {
                throw new IOException("invalid template ZIP local bounds");
            }
            offset = (int) next;
        }
        if (entries.size() != count) { throw new IOException("template ZIP entry count mismatch"); }
        int previousEnd = 0;
        for (var range : localRanges.entrySet()) {
            if (range.getKey() < previousEnd) { throw new IOException("overlapping template ZIP entries"); }
            previousEnd = range.getValue();
        }
        return entries;
    }

    private static int u16(ByteBuffer bytes, int offset) { return Short.toUnsignedInt(bytes.getShort(offset)); }
    private static long u32(ByteBuffer bytes, int offset) { return Integer.toUnsignedLong(bytes.getInt(offset)); }

    static java.io.OutputStream bounded(ByteArrayOutputStream target, int maximum) {
        return new java.io.OutputStream() {
            private void reserve(int size) throws IOException {
                ExecutionContext.checkpoint();
                if (size > maximum - target.size()) { throw new IOException("template bundle byte budget exceeded"); }
            }
            @Override public void write(int value) throws IOException { reserve(1); target.write(value); }
            @Override public void write(byte[] value, int offset, int size) throws IOException {
                Objects.checkFromIndexSize(offset, size, value.length); reserve(size); target.write(value, offset, size);
            }
        };
    }

    static byte[] read(InputStream input, int maximum) throws IOException {
        var bytes = new ByteArrayOutputStream(); var output = bounded(bytes, maximum); var chunk = new byte[8192];
        for (;;) {
            ExecutionContext.checkpoint(); int count = input.read(chunk);
            if (count < 0) { break; }
            if (count == 0) { int value = input.read(); if (value < 0) { break; } output.write(value); }
            else { output.write(chunk, 0, count); }
        }
        return bytes.toByteArray();
    }

    static void publish(byte[] bytes, Path output, ExecutionOptions execution) throws IOException {
        Path target = Objects.requireNonNull(output, "output").toAbsolutePath();
        Path temporary = Files.createTempFile(target.getParent(), ".export4j-template-", ".tmp"); Throwable failure = null;
        try {
            ExecutionContext.execute(execution, () -> {
                try (var stream = Files.newOutputStream(temporary)) { ExecutionContext.output(stream).write(bytes); }
                return null;
            });
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException | Error exception) { failure = exception; throw exception; }
        finally {
            try { Files.deleteIfExists(temporary); }
            catch (IOException exception) { if (failure == null) { throw exception; } failure.addSuppressed(exception); }
        }
    }
}
