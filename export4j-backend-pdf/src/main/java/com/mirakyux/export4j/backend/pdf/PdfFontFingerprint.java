// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import com.mirakyux.export4j.model.ExecutionContext;

/** Page-confined canonical resource graph hashes, including all properties, descendants and raw stream bytes. */
final class PdfFontFingerprint {
    @FunctionalInterface
    interface Visitor { void accept(COSBase compound) throws IOException; }

    private final Visitor visitor;
    private final IdentityHashMap<COSBase, byte[]> completed = new IdentityHashMap<>();
    private final IdentityHashMap<COSBase, Boolean> active = new IdentityHashMap<>();

    PdfFontFingerprint(Visitor visitor) { this.visitor = visitor; }

    /** Cyclic, deeply nested or unknown graphs are copied normally, without resource sharing. */
    String key(COSDictionary resource) throws IOException {
        byte[] result = hash(resource, 0);
        return result == null ? null : HexFormat.of().formatHex(result);
    }

    private byte[] hash(COSBase source, int depth) throws IOException {
        ExecutionContext.checkpoint();
        if (depth > 64) { return null; }
        COSBase value = source;
        for (int references = 0; value instanceof COSObject object; references++) {
            ExecutionContext.checkpoint();
            if (references == 64) { return null; }
            value = object.getObject();
        }
        boolean compound = value instanceof COSDictionary || value instanceof COSArray;
        if (compound) {
            visitor.accept(value);
            byte[] previous = completed.get(value);
            if (previous != null) { return previous; }
            if (active.put(value, Boolean.TRUE) != null) { return null; }
        }
        try {
            var digest = sha256();
            if (value == null || value instanceof COSNull) { digest.update((byte) 0); }
            else if (value instanceof COSBoolean flag) {
                digest.update((byte) 1); digest.update((byte) (flag.getValue() ? 1 : 0));
            } else if (value instanceof COSInteger integer) {
                digest.update((byte) 2); integer.writePDF(new DigestOutputStream(OutputStream.nullOutputStream(), digest));
            } else if (value instanceof COSFloat number) {
                digest.update((byte) 3); number.writePDF(new DigestOutputStream(OutputStream.nullOutputStream(), digest));
            } else if (value instanceof COSName name) {
                digest.update((byte) 4); name.writePDF(new DigestOutputStream(OutputStream.nullOutputStream(), digest));
            } else if (value instanceof COSString text) {
                digest.update((byte) 5); digest.update(text.getBytes());
            } else if (value instanceof COSArray array) {
                digest.update((byte) 6); size(digest, array.size());
                for (int i = 0; i < array.size(); i++) {
                    byte[] child = hash(array.get(i), depth + 1);
                    if (child == null) { return null; }
                    digest.update(child);
                }
            } else if (value instanceof COSDictionary dictionary) {
                digest.update((byte) (value instanceof COSStream ? 8 : 7));
                var keys = new ArrayList<>(dictionary.keySet());
                keys.sort(COSName::compareTo);
                size(digest, keys.size());
                for (var key : keys) {
                    byte[] name = hash(key, depth + 1);
                    if (name == null) { return null; }
                    digest.update(name);
                    byte[] child = hash(dictionary.getItem(key), depth + 1);
                    if (child == null) { return null; }
                    digest.update(child);
                }
                if (value instanceof COSStream stream) {
                    try (var input = stream.createRawInputStream()) {
                        byte[] buffer = new byte[16_384];
                        long copied = 0; int count;
                        while ((count = input.read(buffer)) != -1) {
                            ExecutionContext.checkpoint();
                            digest.update(buffer, 0, count); copied += count;
                        }
                        if (copied != stream.getLength()) { throw new IOException("PDF resource stream length changed while hashing"); }
                    }
                }
            } else { return null; }
            byte[] result = digest.digest();
            if (compound) { completed.put(value, result); }
            return result;
        } finally {
            if (compound) { active.remove(value); }
        }
    }

    private static void size(MessageDigest digest, int value) {
        for (int shift = 24; shift >= 0; shift -= 8) { digest.update((byte) (value >>> shift)); }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 is unavailable", failure); }
    }
}
