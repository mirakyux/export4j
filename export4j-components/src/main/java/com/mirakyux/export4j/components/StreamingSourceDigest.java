// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.TableCell;

/** Canonical, length-delimited semantic hashing, with no full row serialization buffer. */
final class StreamingSourceDigest {
    private final MessageDigest digest;
    private final DataOutputStream output;
    private long bytes;
    StreamingSourceDigest() { this(Long.MAX_VALUE); }
    StreamingSourceDigest(long maximum) {
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
        output = new DataOutputStream(new DigestOutputStream(new OutputStream() {
            @Override public void write(int value) throws IOException {
                if (bytes == maximum) { throw new IOException("streaming probe byte budget exceeded"); } bytes++;
            }
            @Override public void write(byte[] value, int start, int length) throws IOException {
                if (length > maximum - bytes) { throw new IOException("streaming probe byte budget exceeded"); } bytes += length;
            }
        }, digest));
    }
    long bytes() { return bytes; }
    byte[] finish() { return digest.digest(); }
    void add(Object value) throws IOException { encode(value, 0); }
    private void string(String value) throws IOException {
        output.writeInt(value.length());
        for (int index = 0; index < value.length(); index++) {
            if ((index & 1023) == 0) { ExecutionContext.checkpoint(); }
            output.writeChar(value.charAt(index));
        }
    }
    private void encode(Object value, int depth) throws IOException {
        ExecutionContext.checkpoint();
        if (depth > 128) { throw new IOException("streaming digest nesting budget exceeded"); }
        if (value == null) { output.writeByte(0); return; }
        string(value instanceof List<?> ? "list" : value.getClass().getName());
        if (value instanceof String text) { string(text); }
        else if (value instanceof Number number) { string(number.toString()); }
        else if (value instanceof Boolean flag) { output.writeBoolean(flag); }
        else if (value instanceof Enum<?> enumeration) { string(enumeration.name()); }
        else if (value instanceof ImageResource image) { string(image.id()); string(image.mediaType()); }
        else if (value instanceof List<?> list) {
            // Implementation class names are deliberately excluded: equivalent immutable lists hash identically.
            output.writeInt(list.size()); for (var entry : list) { encode(entry, depth + 1); }
        } else if (value instanceof TableCell cell) {
            encode(cell.format(), depth + 1); output.writeInt(cell.columnSpan()); output.writeInt(cell.rowSpan()); output.writeBoolean(cell.hasBlocks());
            if (cell.hasBlocks()) { encode(cell.blocks(), depth + 1); }
            else { encode(cell.text(), depth + 1); encode(cell.inlines(), depth + 1); encode(cell.images(), depth + 1); encode(cell.fields(), depth + 1); }
        } else if (value.getClass().isRecord() && value.getClass().getPackageName().equals("com.mirakyux.export4j.model")) {
            try {
                for (var component : value.getClass().getRecordComponents()) { string(component.getName()); encode(component.getAccessor().invoke(value), depth + 1); }
            } catch (ReflectiveOperationException failure) { throw new IOException("streaming semantic digest failed", failure); }
        } else { throw new IOException("unsupported streaming digest value"); }
    }
}
