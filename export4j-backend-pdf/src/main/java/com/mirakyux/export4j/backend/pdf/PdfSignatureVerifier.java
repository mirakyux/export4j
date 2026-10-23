// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import com.mirakyux.export4j.model.ExecutionContext;

/**
 * Offline detached PDF signature integrity verification through an explicit CMS verifier.
 * Each signature covers one exact byte prefix except its own hexadecimal Contents string. A valid
 * historical signature does not authenticate unsigned appended bytes, document semantics, permitted
 * changes, signer identity, certificate trust, revocation, timestamps or PAdES. No network or keys are used.
 * Calls are synchronous and reusable concurrently when the callback is; the PDF parser is not a sandbox.
 */
public final class PdfSignatureVerifier {
    private PdfSignatureVerifier() { }

    /** Positive byte/count budgets. CMS includes reserved zero padding; cumulative signed bytes count
     * each revision independently. Input is snapshotted before parsing or invoking the callback.
     */
    public record Limits(int maxInputBytes, int maxSignatures, int maxCmsBytes, long maxSignedBytes) {
        public static final Limits DEFAULT = new Limits(32 * 1024 * 1024, 64, 1_048_576, 128L * 1024 * 1024);
        public Limits {
            if (maxInputBytes <= 0 || maxInputBytes > Integer.MAX_VALUE - 8 || maxSignatures <= 0
                    || maxSignatures > 10_000 || maxCmsBytes <= 0 || maxCmsBytes > maxInputBytes || maxSignedBytes <= 0) {
                throw new IllegalArgumentException("invalid signature verification budgets");
            }
        }
    }

    @FunctionalInterface
    public interface CmsVerifier {
        /**
         * Verifies DER detached CMS against the exact borrowed ByteRange stream. Return true only after
         * successful cryptographic verification and consuming all signed bytes; false denotes integrity
         * failure. Certificate/trust policy belongs to the callback and is not inferred by this API.
         * The CMS array is an independent snapshot. The stream expires when the callback returns.
         */
        boolean verify(InputStream signedContent, byte[] cms) throws IOException;
    }

    /** Zero-based verification order follows ascending signed-revision byte length. Integers are bytes.
     * integrityValid is solely the supplied verifier's result for those bytes, not a trust assertion.
     */
    public record Signature(int index, long revisionBytes, long contentsOffset, long contentsEnd,
            int cmsBytes, boolean coversCurrentFile, boolean integrityValid) { }

    /** Immutable report. An unsigned PDF has no signatures and both aggregate flags are false.
     * The coverage flag describes bytes only and does not validate later changes against DocMDP.
     */
    public record Report(long inputBytes, List<Signature> signatures) {
        public Report { signatures = List.copyOf(signatures); }
        public boolean allSignedBytesValid() { return !signatures.isEmpty() && signatures.stream().allMatch(Signature::integrityValid); }
        public boolean currentFileCovered() { return signatures.stream().anyMatch(Signature::coversCurrentFile); }
    }

    /**
     * Verifies all signature fields in one bounded encoded snapshot. Supports unencrypted, hexadecimal
     * Contents and adbe.pkcs7.detached / ETSI.CAdES.detached signatures. Malformed geometry, non-DER data,
     * nonzero reservation padding, unsupported encodings or exhausted budgets fail before any callback.
     * At most 10,000 field-tree nodes are traversed, with cycles and aliased signature values rejected.
     * Results are returned only after every callback and cancellation checkpoint succeeds; no output is written.
     */
    public static Report verify(Path source, Limits limits, CmsVerifier verifier) throws IOException {
        Objects.requireNonNull(source, "source"); Objects.requireNonNull(limits, "limits"); Objects.requireNonNull(verifier, "verifier");
        ExecutionContext.checkpoint();
        byte[] encoded;
        try (var input = Files.newInputStream(source)) {
            encoded = input.readNBytes(limits.maxInputBytes() + 1);
        }
        if (encoded.length > limits.maxInputBytes()) { throw new IOException("signature verification input budget exceeded"); }
        ExecutionContext.checkpoint();
        var prepared = new ArrayList<Prepared>();
        try (var document = Loader.loadPDF(encoded)) {
            if (document.isEncrypted()) { throw new IOException("encrypted signature verification is unsupported"); }
            var catalog = document.getDocumentCatalog().getCOSObject();
            var form = catalog.getDictionaryObject(COSName.ACRO_FORM);
            if (form != null) {
                if (!(form instanceof COSDictionary dictionary)) { throw new IOException("malformed signature form dictionary"); }
                prepareFields(dictionary, encoded, limits, prepared);
            }
        }
        prepared.sort(Comparator.comparingInt(Prepared::end));
        long signedBytes = 0;
        int previous = -1;
        for (var signature : prepared) {
            if (signature.end() == previous) { throw new IOException("multiple signatures in the same revision are unsupported"); }
            previous = signature.end();
            long count = (long) signature.first() + signature.end() - signature.second();
            if (count > limits.maxSignedBytes() - signedBytes) { throw new IOException("cumulative signature byte budget exceeded"); }
            signedBytes += count;
        }
        var result = new ArrayList<Signature>();
        for (var signature : prepared) {
            ExecutionContext.progress("VERIFY_SIGNATURE", "", -1, result.size(), prepared.size());
            boolean valid;
            try (var input = new SignedBytes(encoded, signature)) {
                valid = verifier.verify(input, signature.cms().clone());
                if (valid && !input.consumed()) { throw new IOException("CMS verifier accepted without consuming all signed bytes"); }
            }
            ExecutionContext.checkpoint();
            result.add(new Signature(result.size(), signature.end(), signature.first(), signature.second(),
                    signature.cms().length, signature.end() == encoded.length, valid));
        }
        ExecutionContext.progress("VERIFY_SIGNATURE", "", -1, result.size(), prepared.size());
        return new Report(encoded.length, result);
    }

    private record Field(COSDictionary dictionary, COSName inheritedType) { }
    private record Prepared(int first, int second, int end, byte[] cms) { }

    private static void prepareFields(COSDictionary form, byte[] encoded, Limits limits, List<Prepared> prepared) throws IOException {
        var roots = form.getDictionaryObject(COSName.FIELDS);
        if (roots == null) { return; }
        if (!(roots instanceof COSArray fields)) { throw new IOException("malformed signature field tree"); }
        var pending = new ArrayDeque<Field>();
        appendFields(fields, null, pending);
        var seen = Collections.newSetFromMap(new IdentityHashMap<COSDictionary, Boolean>());
        var values = Collections.newSetFromMap(new IdentityHashMap<COSDictionary, Boolean>());
        while (!pending.isEmpty()) {
            ExecutionContext.checkpoint();
            var field = pending.removeFirst();
            var dictionary = field.dictionary();
            if (!seen.add(dictionary) || seen.size() > 10_000) { throw new IOException("cyclic, aliased or excessive signature field tree"); }
            COSName type = dictionary.getCOSName(COSName.FT, field.inheritedType());
            var value = dictionary.getDictionaryObject(COSName.V);
            if (COSName.SIG.equals(type) && value != null) {
                if (!(value instanceof COSDictionary signature) || !values.add(signature)) { throw new IOException("malformed or aliased signature value"); }
                if (prepared.size() >= limits.maxSignatures()) { throw new IOException("signature count budget exceeded"); }
                prepared.add(prepare(signature, encoded, limits));
            }
            var children = dictionary.getDictionaryObject(COSName.KIDS);
            if (children != null) {
                if (!(children instanceof COSArray kids)) { throw new IOException("malformed signature field children"); }
                appendFields(kids, type, pending);
            }
        }
    }

    private static void appendFields(COSArray fields, COSName inherited, ArrayDeque<Field> pending) throws IOException {
        if (fields.size() > 10_000 - pending.size()) { throw new IOException("signature field queue budget exceeded"); }
        for (int index = 0; index < fields.size(); index++) {
            ExecutionContext.checkpoint();
            if (!(fields.getObject(index) instanceof COSDictionary field)) { throw new IOException("malformed signature field entry"); }
            pending.addLast(new Field(field, inherited));
        }
    }

    private static Prepared prepare(COSDictionary signature, byte[] encoded, Limits limits) throws IOException {
        String filter = signature.getNameAsString(COSName.SUB_FILTER);
        if (!"adbe.pkcs7.detached".equals(filter) && !"ETSI.CAdES.detached".equals(filter)) {
            throw new IOException("unsupported signature subfilter");
        }
        if (!(signature.getDictionaryObject(COSName.BYTERANGE) instanceof COSArray ranges) || ranges.size() != 4) {
            throw new IOException("signature must cover two byte ranges");
        }
        int[] range = new int[4];
        for (int index = 0; index < range.length; index++) {
            if (!(ranges.getObject(index) instanceof COSInteger integer) || integer.longValue() < 0 || integer.longValue() > encoded.length) {
                throw new IOException("signature byte range must contain bounded integers");
            }
            range[index] = (int) integer.longValue();
        }
        long end = (long) range[2] + range[3];
        if (range[0] != 0 || range[1] <= 0 || range[2] <= range[1] + 1L || range[3] <= 0 || end > encoded.length
                || encoded[range[1]] != '<' || encoded[range[2] - 1] != '>') {
            throw new IOException("signature does not cover a revision except hexadecimal Contents");
        }
        if (!(signature.getDictionaryObject(COSName.CONTENTS) instanceof COSString value) || value.getBytes().length > limits.maxCmsBytes()) {
            throw new IOException("signature contents budget exceeded or malformed");
        }
        var raw = new ByteArrayOutputStream();
        int high = -1;
        for (int offset = range[1] + 1; offset < range[2] - 1; offset++) {
            ExecutionContext.checkpoint();
            int b = encoded[offset] & 255;
            if (b == 0 || b == 9 || b == 10 || b == 12 || b == 13 || b == 32) { continue; }
            int digit = b >= '0' && b <= '9' ? b - '0' : b >= 'a' && b <= 'f' ? b - 'a' + 10 : b >= 'A' && b <= 'F' ? b - 'A' + 10 : -1;
            if (digit < 0) { throw new IOException("signature gap contains data outside hexadecimal Contents"); }
            if (high < 0) { high = digit; }
            else {
                if (raw.size() >= limits.maxCmsBytes()) { throw new IOException("signature contents budget exceeded"); }
                raw.write(high * 16 + digit); high = -1;
            }
        }
        if (high >= 0) {
            if (raw.size() >= limits.maxCmsBytes()) { throw new IOException("signature contents budget exceeded"); }
            raw.write(high * 16);
        }
        byte[] padded = raw.toByteArray();
        if (!Arrays.equals(padded, value.getBytes())) { throw new IOException("signature gap does not match its Contents value"); }
        int length = derLength(padded);
        for (int index = length; index < padded.length; index++) {
            if (padded[index] != 0) { throw new IOException("nonzero signature reservation padding"); }
        }
        return new Prepared(range[1], range[2], (int) end, Arrays.copyOf(padded, length));
    }

    private static int derLength(byte[] value) throws IOException {
        if (value.length < 2 || value[0] != 0x30) { throw new IOException("signature must contain a DER sequence"); }
        int count = value[1] & 255;
        int header = 2;
        long length = count;
        if (count >= 128) {
            count &= 127;
            if (count == 0 || count > 4 || value.length < 2 + count || value[2] == 0) { throw new IOException("malformed DER signature length"); }
            header += count; length = 0;
            for (int index = 0; index < count; index++) { length = length * 256 + (value[2 + index] & 255); }
            if (length < 128) { throw new IOException("nonminimal DER signature length"); }
        }
        if (length <= 0 || header + length > value.length) { throw new IOException("truncated DER signature"); }
        return (int) (header + length);
    }

    private static final class SignedBytes extends InputStream {
        private final byte[] encoded;
        private final Prepared signature;
        private int position;
        private boolean closed;
        private SignedBytes(byte[] encoded, Prepared signature) { this.encoded = encoded; this.signature = signature; }
        private boolean consumed() { return position == signature.end(); }
        @Override public int read() throws IOException {
            if (closed) { throw new IOException("signature byte stream has expired"); }
            ExecutionContext.checkpoint();
            if (position == signature.first()) { position = signature.second(); }
            return position == signature.end() ? -1 : encoded[position++] & 255;
        }
        @Override public int read(byte[] target, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (closed) { throw new IOException("signature byte stream has expired"); }
            ExecutionContext.checkpoint();
            if (length == 0) { return 0; }
            if (position == signature.first()) { position = signature.second(); }
            if (position == signature.end()) { return -1; }
            int end = position < signature.first() ? signature.first() : signature.end();
            int count = Math.min(length, end - position);
            System.arraycopy(encoded, position, target, offset, count); position += count;
            return count;
        }
        @Override public void close() { closed = true; }
    }
}
