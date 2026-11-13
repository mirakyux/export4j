// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.RecordComponent;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.IccColor;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.text.PositionedGlyph;

/** Canonical SHA-256 replay proofs. Only an 8-KiB serialization buffer and source-model digests are retained. */
final class PagedReplayDigest {
    private static final ClassValue<RecordComponent[]> COMPONENTS = new ClassValue<>() {
        @Override protected RecordComponent[] computeValue(Class<?> type) { return type.getRecordComponents(); }
    };
    private final MessageDigest digest;
    private final DataOutputStream output;
    private final Map<Table, byte[]> sources;
    private final boolean sourceDeclarations;

    private PagedReplayDigest(Map<Table, byte[]> sources) {
        this(sources, false);
    }

    private PagedReplayDigest(Map<Table, byte[]> sources, boolean sourceDeclarations) {
        this.sources = sources;
        this.sourceDeclarations = sourceDeclarations;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 is required", failure); }
        output = new DataOutputStream(new BufferedOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest), 8192));
    }

    static byte[] layout(LayoutPage page, Map<Table, byte[]> sources) throws IOException {
        var proof = new PagedReplayDigest(sources);
        proof.encode(page, 0);
        return proof.finish();
    }

    static byte[] page(DisplayPage page, List<DocumentExtras.Link> links, List<DocumentExtras.ExternalLink> external,
            List<ContextualDiagnostic> diagnostics) throws IOException {
        var proof = new PagedReplayDigest(null);
        proof.encode(page, 0);
        proof.encode(links, 0);
        proof.encode(external, 0);
        proof.encode(diagnostics, 0);
        return proof.finish();
    }

    /** Incremental canonical raw source proof. Opaque component instances are represented by stable
     * block declarations; their measured source/geometry/commands must additionally use layout proofs.
     * No block or table identity cache is retained between add calls.
     */
    static final class Source {
        private final PagedReplayDigest proof = new PagedReplayDigest(null, true);
        void add(Block block) throws IOException { proof.encode(block, 0); }
        byte[] finish() throws IOException { return proof.finish(); }
    }

    private byte[] finish() throws IOException { output.flush(); return digest.digest(); }

    private void string(String value) throws IOException {
        output.writeInt(value.length());
        for (int index = 0; index < value.length(); index++) {
            if ((index & 1023) == 0) { ExecutionContext.checkpoint(); }
            output.writeChar(value.charAt(index));
        }
    }

    private void encode(Object value, int depth) throws IOException {
        ExecutionContext.checkpoint();
        if (depth > 128) { throw new IOException("page replay digest nesting budget exceeded"); }
        if (value == null) { output.writeByte(0); return; }
        // Common geometry and glyph values occur millions of times. Fixed tags retain exact
        // canonical values without reflective calls, boxing or repeating field/type names.
        if (value instanceof PositionedGlyph glyph) {
            output.writeByte(1); output.writeInt(glyph.codePoint()); output.writeInt(glyph.glyphId());
            output.writeDouble(glyph.advance()); return;
        }
        if (value instanceof Point point) {
            output.writeByte(2); output.writeDouble(point.x()); output.writeDouble(point.y()); return;
        }
        if (value instanceof Rect rect) {
            output.writeByte(3); output.writeDouble(rect.x()); output.writeDouble(rect.y());
            output.writeDouble(rect.width()); output.writeDouble(rect.height()); return;
        }
        if (value instanceof RgbColor color) {
            output.writeByte(4); output.writeDouble(color.red()); output.writeDouble(color.green());
            output.writeDouble(color.blue()); return;
        }
        output.writeByte(127);
        if (value instanceof List<?> list) {
            string("list"); output.writeInt(list.size());
            for (var entry : list) { encode(entry, depth + 1); }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            string("map"); output.writeInt(map.size());
            if (map.keySet().stream().anyMatch(key -> !(key instanceof String))) { throw new IOException("page replay digest requires string map keys"); }
            for (var key : map.keySet().stream().map(String.class::cast).sorted().toList()) {
                string(key); encode(map.get(key), depth + 1);
            }
            return;
        }
        if (value instanceof FontFace face) {
            string("font-face"); string(face.data().id()); encode(face.metrics(), depth + 1); return;
        }
        string(value.getClass().getName());
        if (value instanceof String text) { string(text); }
        else if (value instanceof Double number) { output.writeLong(Double.doubleToLongBits(number)); }
        else if (value instanceof Float number) { output.writeInt(Float.floatToIntBits(number)); }
        else if (value instanceof Long number) { output.writeLong(number); }
        else if (value instanceof Integer number) { output.writeInt(number); }
        else if (value instanceof Short number) { output.writeShort(number); }
        else if (value instanceof Byte number) { output.writeByte(number); }
        else if (value instanceof Boolean flag) { output.writeBoolean(flag); }
        else if (value instanceof Character character) { output.writeChar(character); }
        else if (value instanceof Enum<?> enumeration) { string(enumeration.name()); }
        else if (value instanceof ImageResource image) { string(image.id()); string(image.mediaType()); }
        else if (value instanceof com.mirakyux.export4j.model.VectorResource vector) { string(vector.id()); }
        else if (value instanceof RgbImage image) { string(image.id()); output.writeInt(image.width()); output.writeInt(image.height()); }
        else if (value instanceof FontData font) { string(font.id()); output.writeBoolean(font.subsetAllowed()); }
        else if (value instanceof IccColor color) { string(color.id()); encode(color.components(), depth + 1); }
        else if (value instanceof Table table && sources != null) {
            byte[] cached = sources.get(table);
            if (cached == null) {
                var source = new PagedReplayDigest(null);
                source.encode(table, depth + 1);
                cached = source.finish();
                sources.put(table, cached);
            }
            output.writeInt(cached.length); output.write(cached);
        } else if (sourceDeclarations && value instanceof ComponentBlock block) {
            encode(block.id(), depth + 1); encode(block.spaceBefore(), depth + 1); encode(block.spaceAfter(), depth + 1);
        } else if (value instanceof ComponentLayout layout) {
            output.writeDouble(layout.width()); output.writeDouble(layout.height()); output.writeInt(layout.textLines());
            encode(layout.staticCommands(), depth + 1); encode(layout.diagnostics(), depth + 1); encode(layout.semanticPlacements(), depth + 1);
        } else if (value instanceof TableCell cell) {
            encode(cell.format(), depth + 1); output.writeInt(cell.columnSpan()); output.writeInt(cell.rowSpan()); output.writeBoolean(cell.hasBlocks());
            if (cell.hasBlocks()) { encode(cell.blocks(), depth + 1); }
            else {
                encode(cell.text(), depth + 1); encode(cell.inlines(), depth + 1);
                encode(cell.images(), depth + 1); encode(cell.fields(), depth + 1); encode(cell.vectors(), depth + 1);
            }
        } else if (value.getClass().isRecord() && value.getClass().getPackageName().startsWith("com.mirakyux.export4j.")) {
            var components = COMPONENTS.get(value.getClass());
            output.writeInt(components.length);
            try {
                for (var component : components) { string(component.getName()); encode(component.getAccessor().invoke(value), depth + 1); }
            } catch (ReflectiveOperationException failure) { throw new IOException("page replay digest cannot read an immutable value", failure); }
        } else { throw new IOException("unsupported page replay digest value"); }
    }
}
