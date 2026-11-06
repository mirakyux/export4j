// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;
import org.apache.fontbox.util.BoundingBox;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.core.GraphicsResources;
import com.mirakyux.export4j.model.ExecutionContext;

/** Session-owned finalized horizontal TrueType subsets. Entries contain immutable values and compressed
 * bytes, never PDFBox objects or complete source programs. Rebuilding an entry creates page-owned COS
 * objects; the page is still serialized normally, so its exact byte budget is enforced on every use.
 */
final class PdfFontSubsets implements AutoCloseable {
    private static final int MAX_ENTRIES = 64;
    private static final int MAX_GLYPHS = 8192;
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    private final LinkedHashMap<Key, Program> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Usage> page = new LinkedHashMap<>();
    private long bytes;

    private record Glyph(int codePoint, int glyphId) { }
    private record Key(String fontId, boolean subsetAllowed, List<Glyph> glyphs) { }
    private record Program(Node graph, Map<Integer, Float> widths, boolean embedded, long bytes) { }

    private static final class Usage {
        private final FontData data;
        private final TreeMap<Integer, Integer> glyphs = new TreeMap<>();
        private Key key;
        private PDType0Font pending;
        private boolean disabled;

        private Usage(FontData data) { this.data = data; }

        private void add(int codePoint, int glyphId) {
            if (disabled) { return; }
            Integer previous = glyphs.putIfAbsent(codePoint, glyphId);
            if (glyphId < 0 || glyphId > 0xffff || previous != null && previous != glyphId || glyphs.size() > MAX_GLYPHS) {
                disabled = true;
                glyphs.clear();
            }
        }

        private Key key() {
            if (key == null) {
                key = new Key(data.id(), data.subsetAllowed(), glyphs.entrySet().stream()
                        .map(entry -> new Glyph(entry.getKey(), entry.getValue())).toList());
            }
            return key;
        }
    }

    void begin(List<DisplayPage> pages) { begin(pages, null); }

    void begin(List<DisplayPage> pages, java.util.function.Supplier<FontData> logicalAnchor) {
        page.clear();
        boolean[] outlined = {false};
        for (var display : pages) {
            GraphicsResources.walk(display.commands(), command -> {
                if (command instanceof DrawCommand.OutlineText text && !text.logicalText().isEmpty()) { outlined[0] = true; }
                if (!(command instanceof DrawCommand.GlyphText text) || text.run().font().cffOutlines()
                        || !text.run().font().subsetAllowed()) { return; }
                var data = text.run().font();
                var usage = page.get(data.id());
                if (usage == null) {
                    if (page.size() == MAX_ENTRIES) { return; }
                    usage = new Usage(data);
                    page.put(data.id(), usage);
                }
                for (var glyph : text.run().glyphs()) {
                    ExecutionContext.checkpoint();
                    usage.add(glyph.codePoint(), glyph.glyphId());
                }
            });
        }
        // A logical outline anchor can share the caller's visible FontData. Its generated "x" is
        // outside the visible GlyphText declarations, so leave that font on the ordinary path.
        if (outlined[0] && logicalAnchor != null) { page.remove(logicalAnchor.get().id()); }
    }

    PDFont create(PDDocument document, FontData data) throws IOException {
        var usage = page.get(data.id());
        if (usage == null || usage.disabled) { return null; }
        var program = entries.get(usage.key());
        if (program == null) { return null; }
        ExecutionContext.checkpoint();
        var dictionary = (COSDictionary) rebuild(program.graph(), document, new IdentityHashMap<>());
        return new EncodedSubset(document, dictionary, usage.glyphs, program.widths(), program.embedded());
    }

    void loaded(FontData data, PDType0Font font) {
        var usage = page.get(data.id());
        if (usage != null && !usage.disabled) { usage.pending = font; }
    }

    /** Called after PDFBox has finalized every subset through the ordinary save path. */
    void remember() throws IOException {
        for (var usage : page.values()) {
            if (usage.pending == null || usage.disabled) { continue; }
            var dictionary = usage.pending.getCOSObject();
            if (!COSName.IDENTITY_H.equals(dictionary.getCOSName(COSName.ENCODING))) { continue; }
            var widths = new LinkedHashMap<Integer, Float>();
            boolean validWidths = true;
            for (int glyph : usage.glyphs.values()) {
                ExecutionContext.checkpoint();
                float width = usage.pending.getWidth(glyph);
                if (!Float.isFinite(width)) { validWidths = false; break; }
                widths.putIfAbsent(glyph, width);
            }
            if (!validWidths) { continue; }
            // Account for boxed keys/values and map storage as well as the existing Unicode/GID key.
            long metadataBytes = 128L + usage.glyphs.size() * 48L + widths.size() * 96L;
            var snapshot = new Snapshot(MAX_BYTES - metadataBytes);
            Node graph;
            try { graph = snapshot.capture(dictionary, 0); }
            catch (Uncacheable ignored) { continue; }
            long weight = snapshot.bytes + metadataBytes;
            var old = entries.remove(usage.key());
            if (old != null) { bytes -= old.bytes(); }
            while (entries.size() >= MAX_ENTRIES || weight > MAX_BYTES - bytes) {
                bytes -= entries.pollFirstEntry().getValue().bytes();
            }
            entries.put(usage.key(), new Program(graph, Map.copyOf(widths), usage.pending.isEmbedded(), weight));
            bytes += weight;
        }
    }

    void releasePage() { page.clear(); }
    @Override public void close() { page.clear(); entries.clear(); bytes = 0; }

    /** PDF CIDs retain original glyph IDs, while CIDToGIDMap selects glyphs in the finalized subset.
     * The key includes every original Unicode/GID pair. The normal renderer still checks each encoded
     * CID against its measured glyph ID; widths and ToUnicode come from the captured finalized graph.
     */
    private static final class EncodedSubset extends PDFont {
        private final PDDocument document;
        private final Map<Integer, Integer> glyphs;
        private final Map<Integer, Float> widths;
        private final boolean embedded;
        private PDType0Font reader;

        private EncodedSubset(PDDocument document, COSDictionary dictionary, Map<Integer, Integer> glyphs,
                Map<Integer, Float> widths, boolean embedded) {
            super(dictionary);
            this.document = document;
            this.glyphs = glyphs;
            this.widths = widths;
            this.embedded = embedded;
        }
        @Override protected byte[] encode(int unicode) {
            Integer glyph = glyphs.get(unicode);
            if (glyph == null) { throw new IllegalArgumentException("code point is outside the finalized font subset"); }
            return new byte[] {(byte) (glyph >>> 8), (byte) glyph.intValue()};
        }

        @Override public float getWidth(int code) throws IOException {
            Float width = widths.get(code);
            return width == null ? reader().getWidth(code) : width;
        }

        @Override public String getName() { return dict.getNameAsString(COSName.BASE_FONT); }
        @Override public boolean isEmbedded() { return embedded; }
        @Override public boolean isVertical() { return false; }
        @Override public boolean willBeSubset() { return false; }
        @Override public void addToSubset(int codePoint) {
            throw new IllegalStateException("the font subset is already finalized");
        }
        @Override public void subset() {
            throw new IllegalStateException("the font subset is already finalized");
        }
        @Override protected float getStandard14Width(int code) {
            throw new UnsupportedOperationException("a finalized embedded font is not a standard font");
        }

        // Positioned output only needs the exact finalized widths and Unicode/GID encoding above.
        // Other PDFont operations retain their ordinary meaning through a page-owned lazy reader.
        private PDType0Font reader() throws IOException {
            ExecutionContext.checkpoint();
            if (reader == null) {
                var loaded = new PDType0Font(dict);
                if (loaded.getDescendantFont() instanceof PDCIDFontType2 descendant
                        && descendant.getTrueTypeFont() != null) {
                    document.registerTrueTypeFontForClosing(descendant.getTrueTypeFont());
                }
                reader = loaded;
            }
            return reader;
        }

        private PDType0Font uncheckedReader() {
            try { return reader(); }
            catch (IOException failure) { throw new UncheckedIOException("finalized font reading failed", failure); }
        }

        @Override public PDFontDescriptor getFontDescriptor() { return uncheckedReader().getFontDescriptor(); }
        @Override public Matrix getFontMatrix() { return uncheckedReader().getFontMatrix(); }
        @Override public BoundingBox getBoundingBox() throws IOException { return reader().getBoundingBox(); }
        @Override @Deprecated public float getHeight(int code) throws IOException { return reader().getHeight(code); }
        @Override public boolean hasExplicitWidth(int code) throws IOException { return reader().hasExplicitWidth(code); }
        @Override public float getWidthFromFont(int code) throws IOException { return reader().getWidthFromFont(code); }
        @Override public float getAverageFontWidth() { return uncheckedReader().getAverageFontWidth(); }
        @Override public Vector getPositionVector(int code) { return uncheckedReader().getPositionVector(code); }
        @Override public int readCode(InputStream input) throws IOException { return reader().readCode(input); }
        @Override public boolean isDamaged() { return uncheckedReader().isDamaged(); }
        @Override public String toUnicode(int code) {
            String unicode = super.toUnicode(code);
            return unicode == null ? uncheckedReader().toUnicode(code) : unicode;
        }
    }

    private sealed interface Node { }
    private record Primitive(int kind, Object value) implements Node { }
    private record Sequence(List<Node> values, boolean direct) implements Node { }
    private record Property(String name, Node value) { }
    private record Dictionary(List<Property> values, byte[] stream, boolean direct) implements Node { }

    private static final class Uncacheable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;
        private Uncacheable() { super(null, null, false, false); }
    }

    private static final class Snapshot {
        private final long maximum;
        private final IdentityHashMap<COSBase, Node> completed = new IdentityHashMap<>();
        private final IdentityHashMap<COSBase, Boolean> active = new IdentityHashMap<>();
        private long bytes;
        private Snapshot(long maximum) { this.maximum = maximum; }

        private void charge(long value) {
            if (value < 0 || value > maximum - bytes) { throw new Uncacheable(); }
            bytes += value;
        }

        private Node capture(COSBase raw, int depth) throws IOException {
            ExecutionContext.checkpoint();
            if (depth > 64) { throw new Uncacheable(); }
            COSBase value = raw;
            for (int references = 0; value instanceof COSObject object; references++) {
                if (references == 64) { throw new Uncacheable(); }
                value = object.getObject();
            }
            var old = completed.get(value);
            if (old != null) { return old; }
            if (active.put(value, Boolean.TRUE) != null) { throw new Uncacheable(); }
            charge(64);
            try {
                Node result;
                if (value == null || value instanceof COSNull) { result = new Primitive(0, null); }
                else if (value instanceof COSBoolean flag) { result = new Primitive(1, flag.getValue()); }
                else if (value instanceof COSInteger number) { result = new Primitive(2, number.longValue()); }
                else if (value instanceof COSFloat number) {
                    var encoded = new ByteArrayOutputStream(); number.writePDF(encoded);
                    String decimal = encoded.toString(StandardCharsets.US_ASCII);
                    charge(decimal.length() * 2L); result = new Primitive(3, decimal);
                } else if (value instanceof COSName name) {
                    charge(name.getName().length() * 2L); result = new Primitive(4, name.getName());
                } else if (value instanceof COSString text) {
                    var encoded = text.getBytes(); charge(encoded.length);
                    result = new Primitive(text.getForceHexForm() ? 6 : 5, encoded.clone());
                } else if (value instanceof COSArray array) {
                    charge(array.size() * 8L);
                    var values = new ArrayList<Node>(array.size());
                    for (int index = 0; index < array.size(); index++) { values.add(capture(array.get(index), depth + 1)); }
                    result = new Sequence(List.copyOf(values), array.isDirect());
                } else if (value instanceof COSDictionary dictionary) {
                    var values = new ArrayList<Property>();
                    for (var entry : dictionary.entrySet()) {
                        if (value instanceof COSStream && COSName.LENGTH.equals(entry.getKey())) { continue; }
                        charge(32L + entry.getKey().getName().length() * 2L);
                        values.add(new Property(entry.getKey().getName(), capture(entry.getValue(), depth + 1)));
                    }
                    byte[] stream = null;
                    if (value instanceof COSStream source) {
                        long length = source.getLength(); charge(length);
                        if (length > Integer.MAX_VALUE) { throw new Uncacheable(); }
                        stream = new byte[(int) length];
                        try (var input = source.createRawInputStream()) {
                            for (int offset = 0; offset < stream.length;) {
                                ExecutionContext.checkpoint();
                                int count = input.readNBytes(stream, offset, Math.min(16_384, stream.length - offset));
                                if (count == 0) { throw new IOException("finalized font stream ended before its declared length"); }
                                offset += count;
                            }
                            if (input.read() != -1) { throw new IOException("finalized font stream exceeds its declared length"); }
                        }
                    }
                    result = new Dictionary(List.copyOf(values), stream, dictionary.isDirect());
                } else { throw new Uncacheable(); }
                completed.put(value, result);
                return result;
            } finally { active.remove(value); }
        }
    }

    private static COSBase rebuild(Node node, PDDocument document, IdentityHashMap<Node, COSBase> complete) throws IOException {
        ExecutionContext.checkpoint();
        var previous = complete.get(node);
        if (previous != null) { return previous; }
        COSBase result;
        if (node instanceof Primitive primitive) {
            result = switch (primitive.kind()) {
                case 0 -> COSNull.NULL;
                case 1 -> COSBoolean.getBoolean((Boolean) primitive.value());
                case 2 -> COSInteger.get((Long) primitive.value());
                case 3 -> new COSFloat((String) primitive.value());
                case 4 -> COSName.getPDFName((String) primitive.value());
                case 5, 6 -> new COSString(((byte[]) primitive.value()).clone(), primitive.kind() == 6);
                default -> throw new IOException("unsupported finalized font primitive");
            };
        } else if (node instanceof Sequence sequence) {
            var value = new COSArray(); value.setDirect(sequence.direct());
            for (var child : sequence.values()) { value.add(rebuild(child, document, complete)); }
            result = value;
        } else if (node instanceof Dictionary dictionary) {
            COSDictionary value = dictionary.stream() == null ? new COSDictionary() : document.getDocument().createCOSStream();
            value.setDirect(dictionary.direct());
            for (var property : dictionary.values()) { value.setItem(COSName.getPDFName(property.name()), rebuild(property.value(), document, complete)); }
            if (value instanceof COSStream stream) {
                try (var output = stream.createRawOutputStream()) {
                    byte[] data = dictionary.stream();
                    for (int offset = 0; offset < data.length; offset += 16_384) {
                        ExecutionContext.checkpoint(); output.write(data, offset, Math.min(16_384, data.length - offset));
                    }
                }
            }
            result = value;
        } else { throw new IOException("unsupported finalized font graph"); }
        complete.put(node, result);
        return result;
    }
}
