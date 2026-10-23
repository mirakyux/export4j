// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/** Task-confined full-rewrite optimization. Lossless operations preserve decoded stream bytes and
 * deduplicate only byte-identical simple images and TrueType program streams with equal metadata.
 * Image resampling is opt-in, bounded by pixel dimensions, and always reports its quality loss.
 */
public final class PdfOptimizer {
    private static final Set<String> IMAGE_KEYS = Set.of("Type", "Subtype", "Length", "Width", "Height", "BitsPerComponent", "ColorSpace", "Filter", "DecodeParms", "Interpolate");
    private static final Set<String> FONT_KEYS = Set.of("Length", "Length1", "Filter", "DecodeParms");
    private final PdfAdapter adapter;
    private final PdfInput input;
    private final PdfLimits limits;
    private boolean recompress = true;
    private boolean deduplicate = true;
    private int imageWidth;
    private int imageHeight;
    private float jpegQuality;
    private ProcessingPolicy policy = ProcessingPolicy.STRICT;
    private ExecutionOptions execution = ExecutionOptions.DEFAULT;
    PdfOptimizer(PdfAdapter adapter, PdfInput input, PdfLimits limits) { this.adapter = adapter; this.input = input; this.limits = limits; }
    public PdfOptimizer recompressStreams(boolean value) { recompress = value; return this; }
    public PdfOptimizer deduplicateResources(boolean value) { deduplicate = value; return this; }
    /** Maximum encoded image dimensions, preserving aspect ratio. Does not infer page display DPI. */
    public PdfOptimizer downsampleImages(int width, int height) {
        if (width <= 0 || height <= 0 || (long) width * height > limits.maxImagePixels()) { throw new IllegalArgumentException("invalid image sampling bounds"); }
        imageWidth = width; imageHeight = height; return this;
    }
    /** Optional JPEG for resampled images only; zero/default keeps lossless encoding of new pixels. */
    public PdfOptimizer jpegQuality(double value) { if (!Double.isFinite(value) || value <= 0 || value > 1) { throw new IllegalArgumentException("invalid JPEG quality"); } jpegQuality = (float) value; return this; }
    public PdfOptimizer policy(ProcessingPolicy value) { policy = Objects.requireNonNull(value, "policy"); return this; }
    public PdfOptimizer execution(ExecutionOptions value) { execution = Objects.requireNonNull(value, "execution"); return this; }
    public PreparedPdfOptimization prepare() throws IOException {
        var strategy = policy; var control = execution; boolean compress = recompress; boolean dedupe = deduplicate;
        int width = imageWidth; int height = imageHeight; float quality = jpegQuality;
        if (quality > 0 && width == 0) { throw new IllegalArgumentException("JPEG optimization requires explicit downsampling bounds"); }
        return ExecutionContext.execute(control, () -> {
            var budget = new PdfPreflight(limits, true);
            try (var document = adapter.openForEdit(input, budget)) {
                PdfObjects.inspect(document, limits); var state = new State(document, strategy, compress, dedupe, width, height, quality);
                if (document.isEncrypted()) { state.loss("ENCRYPTION_REMOVED", ProcessingPolicy.Treatment.DEGRADE, "OUTPUT_IS_UNENCRYPTED"); }
                state.findMasks(document.getDocument().getTrailer(), Collections.newSetFromMap(new IdentityHashMap<>()));
                state.walk(document.getDocument().getTrailer(), "");
                new PdfPreflight(limits, true).inspect(document); PdfObjects.inspect(document, limits);
                var output = PreparedPdf.encode(document, state.compressed + state.deduplicated + state.resampled, state.issues, limits, control);
                return new PreparedPdfOptimization(output, new PreparedPdfOptimization.Statistics(input.byteSize(), output.outputBytes(),
                        state.compressed, state.deduplicated, state.resampled, state.pixelsBefore, state.pixelsAfter));
            }
        });
    }
    private final class State {
        final PDDocument document; final ProcessingPolicy strategy; final boolean compress; final boolean dedupe;
        final int width; final int height; final float quality;
        final List<ProcessingIssue> issues = new ArrayList<>();
        final Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        final IdentityHashMap<COSStream, COSStream> replacements = new IdentityHashMap<>();
        final Map<String, COSStream> identical = new HashMap<>();
        final Set<COSBase> masks = Collections.newSetFromMap(new IdentityHashMap<>());
        int compressed; int deduplicated; int resampled; long pixelsBefore; long pixelsAfter;
        State(PDDocument document, ProcessingPolicy strategy, boolean compress, boolean dedupe, int width, int height, float quality) {
            this.document = document; this.strategy = strategy; this.compress = compress; this.dedupe = dedupe; this.width = width; this.height = height; this.quality = quality;
        }
        COSBase walk(COSBase value, String key) throws IOException {
            ExecutionContext.checkpoint();
            if (value instanceof COSObject object) {
                var original = object.getObject(); var updated = walk(original, key);
                return original == updated ? object : new COSObject(updated);
            }
            if (value instanceof COSStream stream && replacements.containsKey(stream)) { return replacements.get(stream); }
            if (value == null || !visited.add(value)) { return value; }
            if (value instanceof COSDictionary dictionary) {
                for (var entry : new ArrayList<>(dictionary.entrySet())) { dictionary.setItem(entry.getKey(), walk(entry.getValue(), entry.getKey().getName())); }
            } else if (value instanceof COSArray array) {
                for (int index = 0; index < array.size(); index++) { array.set(index, walk(array.get(index), key)); }
            }
            if (!(value instanceof COSStream stream)) { return value; }
            COSStream result = stream;
            if (width > 0 && "Image".equals(stream.getNameAsString(COSName.SUBTYPE))) { result = resample(stream); }
            if (compress && recompress(result)) { compressed++; }
            if (dedupe) {
                String fingerprint = fingerprint(result, key);
                if (fingerprint != null) {
                    var previous = identical.putIfAbsent(fingerprint, result);
                    if (previous != null && previous != result && java.util.Arrays.equals(raw(previous), raw(result))) { result = previous; deduplicated++; }
                }
            }
            replacements.put(stream, result); return result;
        }
        void findMasks(COSBase value, Set<COSBase> seen) {
            ExecutionContext.checkpoint();
            if (value instanceof COSObject object) { findMasks(object.getObject(), seen); return; }
            if (value == null || !seen.add(value)) { return; }
            if (value instanceof COSDictionary dictionary) {
                for (var entry : dictionary.entrySet()) {
                    if (entry.getKey().equals(COSName.SMASK) || entry.getKey().equals(COSName.MASK)) {
                        masks.add(dictionary.getDictionaryObject(entry.getKey()));
                    }
                    findMasks(entry.getValue(), seen);
                }
            } else if (value instanceof COSArray array) { for (var item : array) { findMasks(item, seen); } }
        }
        COSStream resample(COSStream stream) throws IOException {
            var image = new PDImageXObject(new org.apache.pdfbox.pdmodel.common.PDStream(stream), null);
            if (image.getWidth() <= width && image.getHeight() <= height) { return stream; }
            if (masks.contains(stream) || !simpleImage(stream) || image.isStencil() || image.getBitsPerComponent() != 8) {
                loss("IMAGE_RESAMPLING_FORMAT", ProcessingPolicy.Treatment.SKIP_MATCH, "IMAGE_RETAINED_WITHOUT_RESAMPLING"); return stream;
            }
            loss("IMAGE_RESAMPLING", ProcessingPolicy.Treatment.DEGRADE, "IMAGE_SPATIAL_RESOLUTION_REDUCED");
            if (quality > 0) { loss("JPEG_ENCODING", ProcessingPolicy.Treatment.DEGRADE, "LOSSY_JPEG_PIXELS"); }
            double scale = Math.min((double) width / image.getWidth(), (double) height / image.getHeight());
            int targetWidth = Math.max(1, (int) Math.floor(image.getWidth() * scale)); int targetHeight = Math.max(1, (int) Math.floor(image.getHeight() * scale));
            var source = image.getImage(); var target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            try {
                var graphics = target.createGraphics();
                try { graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC); graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null); } finally { graphics.dispose(); }
                var replacement = quality > 0 ? JPEGFactory.createFromImage(document, target, quality) : LosslessFactory.createFromImage(document, target);
                // Interpolate is a viewing hint belonging to the source image, not the resampler.
                if (stream.containsKey(COSName.INTERPOLATE)) { replacement.getCOSObject().setItem(COSName.INTERPOLATE, stream.getItem(COSName.INTERPOLATE)); }
                resampled++; pixelsBefore += (long) image.getWidth() * image.getHeight(); pixelsAfter += (long) targetWidth * targetHeight;
                return replacement.getCOSObject();
            } finally { source.flush(); target.flush(); }
        }
        void loss(String feature, ProcessingPolicy.Treatment treatment, String effect) { PdfImageSupport.loss(strategy, issues, "PDF", feature, "OPTIMIZE", treatment, effect, -1); }
    }
    private boolean recompress(COSStream stream) throws IOException {
        COSBase filter = stream.getDictionaryObject(COSName.FILTER);
        if (filter instanceof COSArray array) { if (array.size() != 1) { return false; } filter = array.getObject(0); }
        boolean flate = COSName.FLATE_DECODE.equals(filter) || COSName.getPDFName("Fl").equals(filter);
        if (filter != null && !flate) { return false; }
        byte[] original = raw(stream); var output = new CompressionCandidate(original.length);
        try (var source = flate ? new InflaterInputStream(new ByteArrayInputStream(original)) : new ByteArrayInputStream(original);
                var compressor = new DeflaterOutputStream(output)) {
            var buffer = new byte[8192]; int count;
            while ((count = source.read(buffer)) != -1) { ExecutionContext.checkpoint(); compressor.write(buffer, 0, count); }
        } catch (CandidateNotSmaller exception) { return false; }
        byte[] encoded = output.data();
        if (encoded.length >= original.length) { return false; }
        try (var target = stream.createRawOutputStream()) { target.write(encoded); }
        if (filter == null) { stream.setItem(COSName.FILTER, COSName.FLATE_DECODE); }
        return true;
    }
    private static final class CandidateNotSmaller extends IOException {
        private static final long serialVersionUID = 1L;
    }
    /** Abandons only a candidate proven no smaller; input validation already bounded inflation. */
    private static final class CompressionCandidate extends java.io.OutputStream {
        private final int limit;
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        CompressionCandidate(int limit) { this.limit = limit; }
        @Override public void write(int value) throws IOException {
            ExecutionContext.checkpoint();
            if (bytes.size() >= limit) { throw new CandidateNotSmaller(); }
            bytes.write(value);
        }
        @Override public void write(byte[] value, int offset, int length) throws IOException {
            ExecutionContext.checkpoint();
            if (length > limit - bytes.size()) { throw new CandidateNotSmaller(); }
            bytes.write(value, offset, length);
        }
        byte[] data() { return bytes.toByteArray(); }
    }
    private static boolean simpleImage(COSStream stream) {
        for (var key : stream.keySet()) { if (!IMAGE_KEYS.contains(key.getName())) { return false; } }
        var color = stream.getDictionaryObject(COSName.COLORSPACE);
        return COSName.DEVICERGB.equals(color) || COSName.DEVICEGRAY.equals(color);
    }
    private static String fingerprint(COSStream stream, String ownerKey) throws IOException {
        boolean image = "Image".equals(stream.getNameAsString(COSName.SUBTYPE));
        if (image && !simpleImage(stream) || !image && !ownerKey.equals("FontFile2")) { return null; }
        var allowed = image ? IMAGE_KEYS : FONT_KEYS;
        for (var key : stream.keySet()) { if (!allowed.contains(key.getName())) { return null; } }
        var signature = new StringBuilder(image ? "IMAGE" : "FONT");
        for (var key : stream.keySet().stream().filter(key -> !key.equals(COSName.LENGTH)).sorted(java.util.Comparator.comparing(COSName::getName)).toList()) {
            signature.append('|').append(key.getName()).append('=');
            if (!primitive(stream.getDictionaryObject(key), signature, 0)) { return null; }
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256"); digest.update(signature.toString().getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0); digest.update(raw(stream));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("required SHA-256 unavailable", exception); }
    }
    private static boolean primitive(COSBase value, StringBuilder output, int depth) {
        if (depth > 8) { return false; }
        if (value instanceof COSDictionary dictionary && !(value instanceof COSStream)) {
            output.append('{');
            for (var key : dictionary.keySet().stream().sorted(java.util.Comparator.comparing(COSName::getName)).toList()) {
                output.append(key.getName().length()).append(':').append(key.getName()).append('='); if (!primitive(dictionary.getDictionaryObject(key), output, depth + 1)) { return false; } output.append(';');
            }
            output.append('}'); return true;
        }
        if (value instanceof COSArray array) {
            output.append('['); for (int index = 0; index < array.size(); index++) { if (!primitive(array.getObject(index), output, depth + 1)) { return false; } output.append(';'); } output.append(']'); return true;
        }
        if (value instanceof COSName name) { output.append('/').append(name.getName().length()).append(':').append(name.getName()); return true; }
        if (value instanceof org.apache.pdfbox.cos.COSNumber || value instanceof org.apache.pdfbox.cos.COSBoolean || value instanceof org.apache.pdfbox.cos.COSNull) {
            output.append(value); return true;
        }
        return false;
    }
    private static byte[] raw(COSStream stream) throws IOException { try (var input = stream.createRawInputStream()) { return input.readAllBytes(); } }
}
