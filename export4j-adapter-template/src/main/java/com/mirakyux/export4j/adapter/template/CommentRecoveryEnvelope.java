// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.model.ExecutionContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/** An inert trailing HTML/XML comment, also valid in CommonMark. Binds all bytes outside it. */
final class CommentRecoveryEnvelope {
    private static final byte[] RESERVED = "<!--export4j-model-recovery".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] START = "\n<!--export4j-model-recovery\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] END = "\n-->\n".getBytes(StandardCharsets.US_ASCII);
    private CommentRecoveryEnvelope() { }

    record Parts(byte[] document, byte[] companion) { }

    static byte[] encode(byte[] document, byte[] companion, int maximum) throws IOException {
        if (locate(document) >= 0) { throw new IOException("document contains a reserved recovery envelope"); }
        long size = document.length + (companion == null ? 0L : START.length + 4L * ((companion.length + 2L) / 3) + END.length);
        if (size > maximum) { throw new IOException("embedded document byte budget exceeded"); }
        if (companion == null) { return document.clone(); }
        var bytes = new ByteArrayOutputStream();
        var output = TemplateBundleIO.bounded(bytes, maximum);
        output.write(document); output.write(START);
        try (var encoded = Base64.getEncoder().wrap(output)) {
            for (int offset = 0; offset < companion.length; offset += 8192) {
                ExecutionContext.checkpoint(); encoded.write(companion, offset, Math.min(8192, companion.length - offset));
            }
        }
        output.write(END);
        return bytes.toByteArray();
    }

    static Parts decode(byte[] html, XmlModelRecovery.Limits limits) throws IOException {
        if (html.length > limits.maxDocumentBytes()) { throw new IOException("embedded document byte budget exceeded"); }
        int marker = locate(html);
        if (marker < 0) { return new Parts(html, null); }
        int start = marker - 1;
        if (start < 0 || !matches(html, start, START) || !matches(html, html.length - END.length, END)) {
            throw new IOException("malformed recovery comment envelope");
        }
        int payload = start + START.length, length = html.length - END.length - payload;
        if (length < 4 || length % 4 != 0 || length > 4L * ((limits.bundle().maxEncodedBytes() + 2L) / 3)) {
            throw new IOException("invalid recovery comment payload size");
        }
        int padding = html[payload + length - 1] == '=' ? (html[payload + length - 2] == '=' ? 2 : 1) : 0;
        if (length / 4L * 3 - padding > limits.bundle().maxEncodedBytes()) { throw new IOException("recovery ZIP byte budget exceeded"); }
        for (int index = 0; index < length; index++) {
            if ((index & 8191) == 0) { ExecutionContext.checkpoint(); }
            int value = html[payload + index] & 255;
            boolean alphabet = value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'
                    || value >= '0' && value <= '9' || value == '+' || value == '/';
            if (!alphabet && !(value == '=' && index >= length - padding)) { throw new IOException("invalid recovery comment base64"); }
        }
        byte[] companion;
        try { companion = Base64.getDecoder().decode(Arrays.copyOfRange(html, payload, payload + length)); }
        catch (IllegalArgumentException failure) { throw new IOException("invalid recovery comment base64", failure); }
        ExecutionContext.checkpoint();
        return new Parts(Arrays.copyOf(html, start), companion);
    }

    private static int locate(byte[] bytes) throws IOException {
        int found = -1;
        for (int index = 0; index <= bytes.length - RESERVED.length; index++) {
            if ((index & 8191) == 0) { ExecutionContext.checkpoint(); }
            if (bytes[index] == '<' && matches(bytes, index, RESERVED)) {
                if (found >= 0) { throw new IOException("multiple recovery comment envelopes"); }
                found = index;
            }
        }
        return found;
    }

    private static boolean matches(byte[] bytes, int offset, byte[] expected) {
        if (offset < 0 || offset > bytes.length - expected.length) { return false; }
        for (int index = 0; index < expected.length; index++) { if (bytes[offset + index] != expected[index]) { return false; } }
        return true;
    }
}
