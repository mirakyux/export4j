// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.model.ExecutionContext;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Classic ZIP insertion preserving every original member byte and directory record. */
final class ZipRecoveryEnvelope {
    private static final String DOCX_PART = "customXml/export4j-recovery.xml";
    private static final String PNG_PART = "export4j/recovery.zip";
    private static final byte[] XML_START = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><export4j-recovery version=\"1\">".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] XML_END = "</export4j-recovery>".getBytes(StandardCharsets.US_ASCII);
    private ZipRecoveryEnvelope() { }

    private record Entry(String name, int offset, int size, int compressed, int method, long crc, int directoryOffset, int directorySize) { }
    private record Directory(int offset, int size, int end, int count, Entry recovery, List<Entry> entries) { }

    static byte[] encode(String format, byte[] document, byte[] companion, XmlModelRecovery.Limits limits) throws IOException {
        String name = name(format);
        var directory = inspect(document, name, limits);
        if (directory.recovery() != null) { throw new IOException("ZIP already contains recovery data"); }
        if (companion == null) { return document.clone(); }
        if (directory.count() >= limits.bundle().maxEntries()) { throw new IOException("embedded ZIP entry budget exceeded"); }
        byte[] payload = format.equals("DOCX") ? xml(companion) : companion;
        if (companion.length > limits.bundle().maxEncodedBytes()) { throw new IOException("recovery ZIP byte budget exceeded"); }
        byte[] entryName = name.getBytes(StandardCharsets.US_ASCII);
        long size = (long) document.length + 30 + entryName.length + payload.length + 46 + entryName.length;
        if (size > limits.maxDocumentBytes()) { throw new IOException("embedded ZIP byte budget exceeded"); }
        var crc = new CRC32(); crc.update(payload);
        byte[] local = new byte[30 + entryName.length]; var localHeader = little(local);
        localHeader.putInt(0x04034b50).putShort((short) 20).putShort((short) 0x0800).putShort((short) 0);
        localHeader.putInt(0).putInt((int) crc.getValue()).putInt(payload.length).putInt(payload.length);
        localHeader.putShort((short) entryName.length).putShort((short) 0).put(entryName);
        byte[] central = new byte[46 + entryName.length]; var centralHeader = little(central);
        centralHeader.putInt(0x02014b50).putShort((short) 20).putShort((short) 20);
        centralHeader.putShort((short) 0x0800).putShort((short) 0).putInt(0);
        centralHeader.putInt((int) crc.getValue()).putInt(payload.length).putInt(payload.length);
        centralHeader.putShort((short) entryName.length).putShort((short) 0).putShort((short) 0);
        centralHeader.putShort((short) 0).putShort((short) 0).putInt(0).putInt(directory.offset()).put(entryName);
        byte[] end = Arrays.copyOfRange(document, directory.end(), document.length);
        var footer = little(end);
        footer.putShort(8, (short) (directory.count() + 1)); footer.putShort(10, (short) (directory.count() + 1));
        footer.putInt(12, directory.size() + central.length);
        footer.putInt(16, directory.offset() + local.length + payload.length);
        var result = new ByteArrayOutputStream();
        result.write(document, 0, directory.offset()); result.write(local); result.write(payload);
        result.write(document, directory.offset(), directory.size()); result.write(central); result.write(end);
        return result.toByteArray();
    }

    static CommentRecoveryEnvelope.Parts decode(String format, byte[] current, XmlModelRecovery.Limits limits) throws IOException {
        var directory = inspect(current, name(format), limits);
        var recovery = directory.recovery();
        if (recovery == null) { return new CommentRecoveryEnvelope.Parts(current, null); }
        byte[] payload = payload(current, recovery, limits);
        byte[] companion = format.equals("DOCX") ? unxml(payload, limits.bundle().maxEncodedBytes()) : payload;
        if (companion.length > limits.bundle().maxEncodedBytes()) { throw new IOException("recovery ZIP byte budget exceeded"); }
        int local = recovery.offset() - 30 - recovery.name().length();
        int localSize = 30 + recovery.name().length() + recovery.size();
        // An external ZIP writer may retain but move the private member; that file is stale.
        if (recovery.method() != 0 || local != directory.offset() - localSize
                || recovery.directoryOffset() + recovery.directorySize() != directory.end()) {
            return new CommentRecoveryEnvelope.Parts(strip(current, recovery.name(), limits), companion);
        }
        int originalCentral = local;
        int originalSize = directory.size() - recovery.directorySize();
        byte[] end = Arrays.copyOfRange(current, directory.end(), current.length);
        var footer = little(end);
        footer.putShort(8, (short) (directory.count() - 1)); footer.putShort(10, (short) (directory.count() - 1));
        footer.putInt(12, originalSize); footer.putInt(16, originalCentral);
        var standard = new ByteArrayOutputStream();
        standard.write(current, 0, originalCentral);
        standard.write(current, directory.offset(), originalSize);
        standard.write(end);
        return new CommentRecoveryEnvelope.Parts(standard.toByteArray(), companion);
    }

    private static byte[] payload(byte[] bytes, Entry entry, XmlModelRecovery.Limits limits) throws IOException {
        byte[] content;
        if (entry.method() == 0) { content = Arrays.copyOfRange(bytes, entry.offset(), entry.offset() + entry.size()); }
        else {
            var inflater = new Inflater(true);
            try (var input = new InflaterInputStream(new ByteArrayInputStream(bytes, entry.offset(), entry.compressed()), inflater)) {
                var output = new ByteArrayOutputStream(); byte[] chunk = new byte[8192];
                for (int size; (size = input.read(chunk)) != -1;) {
                    ExecutionContext.checkpoint();
                    if (size > limits.maxDocumentBytes() - output.size()) { throw new IOException("recovery ZIP member budget exceeded"); }
                    output.write(chunk, 0, size);
                }
                if (!inflater.finished() || inflater.getBytesRead() != entry.compressed()) { throw new IOException("invalid compressed recovery member"); }
                content = output.toByteArray();
            } finally { inflater.end(); }
        }
        var crc = new CRC32(); crc.update(content);
        if (content.length != entry.size() || crc.getValue() != entry.crc()) { throw new IOException("recovery ZIP member CRC mismatch"); }
        return content;
    }

    private static byte[] strip(byte[] bytes, String reserved, XmlModelRecovery.Limits limits) throws IOException {
        var output = new ByteArrayOutputStream(); long expanded = 0;
        try (var input = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8);
                var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                ExecutionContext.checkpoint();
                if (!entry.getName().equals(reserved)) {
                    var copy = new ZipEntry(entry.getName()); copy.setTime(entry.getTime()); zip.putNextEntry(copy);
                }
                byte[] chunk = new byte[8192];
                for (int size; (size = input.read(chunk)) != -1;) {
                    ExecutionContext.checkpoint(); expanded += size;
                    if (expanded > limits.maxDocumentBytes()) { throw new IOException("ZIP rewrite budget exceeded"); }
                    if (!entry.getName().equals(reserved)) { zip.write(chunk, 0, size); }
                }
                if (!entry.getName().equals(reserved)) { zip.closeEntry(); }
                input.closeEntry();
            }
        }
        if (output.size() > limits.maxDocumentBytes()) { throw new IOException("ZIP fallback byte budget exceeded"); }
        return output.toByteArray();
    }

    private static String name(String format) {
        return switch (format) {
            case "DOCX" -> DOCX_PART;
            case "PNG_ZIP" -> PNG_PART;
            default -> throw new IllegalArgumentException("ZIP recovery requires DOCX or PNG_ZIP");
        };
    }

    private static Directory inspect(byte[] bytes, String reserved, XmlModelRecovery.Limits limits) throws IOException {
        if (bytes.length < 22 || bytes.length > limits.maxDocumentBytes()) { throw new IOException("invalid ZIP size"); }
        var input = little(bytes); int end = -1;
        for (int position = bytes.length - 22; position >= Math.max(0, bytes.length - 65557); position--) {
            ExecutionContext.checkpoint();
            if (input.getInt(position) == 0x06054b50 && position + 22 + u16(input, position + 20) == bytes.length) { end = position; break; }
        }
        if (end < 0 || u16(input, end + 4) != 0 || u16(input, end + 6) != 0
                || u16(input, end + 8) != u16(input, end + 10)) { throw new IOException("unsupported ZIP directory"); }
        int count = u16(input, end + 10); long centralSize = u32(input, end + 12), central = u32(input, end + 16);
        if (count >= 0xffff || count > limits.bundle().maxEntries() || central + centralSize != end) {
            throw new IOException("ZIP directory budget or bounds exceeded");
        }
        var entries = new java.util.ArrayList<Entry>(); var names = new HashSet<String>(); int position = (int) central;
        Entry recovery = null;
        for (int index = 0; index < count; index++) {
            ExecutionContext.checkpoint();
            if (end - position < 46 || input.getInt(position) != 0x02014b50) { throw new IOException("invalid ZIP directory entry"); }
            int flag = u16(input, position + 8), method = u16(input, position + 10);
            int nameLength = u16(input, position + 28), extra = u16(input, position + 30), comment = u16(input, position + 32);
            long next = (long) position + 46 + nameLength + extra + comment;
            long compressed = u32(input, position + 20), size = u32(input, position + 24), local = u32(input, position + 42);
            if (next > end || compressed == 0xffffffffL || size == 0xffffffffL || local == 0xffffffffL
                    || compressed > Integer.MAX_VALUE || size > limits.maxDocumentBytes() || local > central - 30
                    || (flag & ~0x080e) != 0 || method != 0 && method != 8 || u16(input, position + 34) != 0) {
                throw new IOException("unsupported ZIP entry");
            }
            String name = new String(bytes, position + 46, nameLength, StandardCharsets.UTF_8);
            if (!names.add(name)) { throw new IOException("duplicate ZIP member"); }
            int start = (int) local;
            if (input.getInt(start) != 0x04034b50 || u16(input, start + 6) != flag || u16(input, start + 8) != method
                    || u16(input, start + 26) != nameLength || start + 30L + nameLength + u16(input, start + 28) + compressed > central) {
                throw new IOException("ZIP local entry mismatch");
            }
            String localName = new String(bytes, start + 30, nameLength, StandardCharsets.UTF_8);
            if (!name.equals(localName)) { throw new IOException("ZIP local name mismatch"); }
            int data = start + 30 + nameLength + u16(input, start + 28);
            var entry = new Entry(name, data, (int) size, (int) compressed, method, u32(input, position + 16), position, (int) next - position);
            entries.add(entry);
            if (name.equals(reserved)) {
                if (method == 0 && compressed != size || (flag & 0x0008) == 0
                        && (u32(input, start + 14) != entry.crc() || u32(input, start + 18) != compressed || u32(input, start + 22) != size)) {
                    throw new IOException("invalid recovery ZIP member");
                }
                payload(bytes, entry, limits);
                recovery = entry;
            }
            position = (int) next;
        }
        if (position != end) { throw new IOException("ZIP directory entry count mismatch"); }
        if (reserved.equals(DOCX_PART) && !names.contains("[Content_Types].xml")) { throw new IOException("DOCX content types missing"); }
        return new Directory((int) central, (int) centralSize, end, count, recovery, List.copyOf(entries));
    }

    private static byte[] xml(byte[] companion) {
        var result = new ByteArrayOutputStream();
        result.writeBytes(XML_START); result.writeBytes(Base64.getEncoder().encode(companion)); result.writeBytes(XML_END);
        return result.toByteArray();
    }
    private static byte[] unxml(byte[] payload, int maxBytes) throws IOException {
        if (payload.length < XML_START.length + XML_END.length || !Arrays.equals(Arrays.copyOf(payload, XML_START.length), XML_START)
                || !Arrays.equals(Arrays.copyOfRange(payload, payload.length - XML_END.length, payload.length), XML_END)) {
            throw new IOException("invalid DOCX recovery XML");
        }
        int start = XML_START.length, end = payload.length - XML_END.length;
        if (end - start > ((long) maxBytes + 2) / 3 * 4 + 4) { throw new IOException("DOCX recovery encoding budget exceeded"); }
        try { return Base64.getDecoder().decode(Arrays.copyOfRange(payload, start, end)); }
        catch (IllegalArgumentException failure) { throw new IOException("invalid DOCX recovery Base64", failure); }
    }
    private static ByteBuffer little(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    private static int u16(ByteBuffer bytes, int offset) { return Short.toUnsignedInt(bytes.getShort(offset)); }
    private static long u32(ByteBuffer bytes, int offset) { return Integer.toUnsignedLong(bytes.getInt(offset)); }
}
