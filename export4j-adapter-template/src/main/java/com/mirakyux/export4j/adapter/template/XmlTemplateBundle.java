// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Editable UTF-8 XML sources and explicit resources in an ordinary ZIP. No model serialization or
 * separate binding engine: compile delegates to XmlTemplate/DocumentTemplate. File conventions are
 * template.xml, fragments/name.xml, images/name.png|jpg|jpeg, fonts/name.ttf|otf|ttc|otc
 * and optional licenses/name.txt notices.
 * Immutable and reusable; builders/callbacks are task-confined. Caller streams are borrowed.
 * Source syntax is checked by compile, allowing drafts to be saved. Fonts are opaque caller bytes;
 * callers validate/load them explicitly and must hold redistribution rights. No implicit I/O occurs. */
public final class XmlTemplateBundle {
    /** Cooperative per-operation limits; text counts all XML/notice UTF-16 units, images count unique pixels.
     * ImageResource also enforces its per-image limits. These are not hard process-memory limits. */
    public record Limits(int maxEncodedBytes, int maxExpandedBytes, int maxEntries,
            int maxSourceUtf16, long maxImagePixels) {
        public static final Limits DEFAULT = new Limits(64 * 1024 * 1024, 128 * 1024 * 1024, 512, 1_000_000, 40_000_000);
        public Limits {
            if (maxEncodedBytes < 1 || maxExpandedBytes < 1 || maxEntries < 1 || maxSourceUtf16 < 1 || maxImagePixels < 1) {
                throw new IllegalArgumentException("invalid template bundle limits");
            }
        }
    }
    private final Map<String, byte[]> entries;
    private final String source;
    private final Map<String, String> fragments;
    private final Map<String, ImageResource> images;
    private final Map<String, String> licenses;
    private final Limits limits;
    private final ExecutionOptions execution;

    private XmlTemplateBundle(Builder builder) throws IOException {
        limits = builder.limits; execution = builder.execution;
        var snapshot = new LinkedHashMap<String, byte[]>();
        var fragmentSources = new HashMap<String, String>(); var pictures = new HashMap<String, ImageResource>();
        var notices = new HashMap<String, String>();
        var seenImages = new java.util.HashSet<String>(); long expanded = 0; long text = 0; long pixels = 0;
        if (builder.entries.size() > limits.maxEntries()) { throw new IOException("template bundle entry budget exceeded"); }
        for (var item : builder.entries.entrySet()) {
            ExecutionContext.checkpoint(); String name = item.getKey(); TemplateBundleIO.fileName(name);
            byte[] bytes = item.getValue(); expanded += bytes.length;
            if (expanded > limits.maxExpandedBytes()) { throw new IOException("template bundle expansion budget exceeded"); }
            if (name.endsWith(".xml") || name.startsWith("licenses/")) {
                String xml = decode(bytes); text += xml.length();
                if (text > limits.maxSourceUtf16()) { throw new IOException("template bundle source budget exceeded"); }
                if (name.startsWith("fragments/")) { fragmentSources.put(stem(name), xml); }
                if (name.startsWith("licenses/")) { notices.put(name.substring(9), xml); }
            } else if (name.startsWith("images/")) {
                var image = ImageResource.load(bytes);
                if (!name.endsWith(".png") && !image.mediaType().equals("image/jpeg")
                        || name.endsWith(".png") && !image.mediaType().equals("image/png")) {
                    throw new IOException("template bundle image extension does not match content");
                }
                if (pictures.putIfAbsent(stem(name), image) != null) { throw new IOException("ambiguous template image name"); }
                if (seenImages.add(image.id())) { pixels += (long) image.pixelWidth() * image.pixelHeight(); }
                if (pixels > limits.maxImagePixels()) { throw new IOException("template bundle pixel budget exceeded"); }
            } else if (bytes.length == 0 || bytes.length > 32 * 1024 * 1024) {
                throw new IOException("invalid template font byte count");
            }
            snapshot.put(name, bytes.clone());
        }
        if (!snapshot.containsKey("template.xml")) { throw new IOException("template.xml is missing"); }
        entries = Map.copyOf(snapshot); source = decode(entries.get("template.xml"));
        fragments = Map.copyOf(fragmentSources); images = Map.copyOf(pictures);
        licenses = Map.copyOf(notices);
    }
    public static Builder builder(String source) { return new Builder().source(source); }
    /** An independent editable copy. Changing source/resources never changes this bundle. */
    public Builder toBuilder() {
        var result = new Builder().limits(limits).execution(execution);
        entries.forEach((name, bytes) -> result.entries.put(name, bytes.clone())); return result;
    }
    public String source() { return source; }
    public Map<String, String> fragments() { return fragments; }
    public Map<String, ImageResource> images() { return images; }
    /** Optional original copyright/license texts; never interpreted as template markup. */
    public Map<String, String> licenses() { return licenses; }
    /** Original filenames to owned byte copies. No font parsing, role inference or collection selection. */
    public Map<String, byte[]> fonts() {
        var result = new LinkedHashMap<String, byte[]>();
        entries.forEach((name, bytes) -> { if (name.startsWith("fonts/")) { result.put(name.substring(6), bytes.clone()); } });
        return Map.copyOf(result);
    }
    public DocumentTemplate compile() throws IOException { return compile(ignored -> { }); }
    /** Reopens a self-contained static source for the shared conversion pipeline. Sources requiring
     * data must instead be explicitly compiled/rendered with that data before conversion. */
    public DocumentConversion.Request conversion() {
        return DocumentConversion.from(XmlTemplateWriter.FORMAT, ignored -> {
            var bound = compile().renderComplete(Map.of());
            return new DocumentConversion.Input(bound.document(), bound.notes(),
                    new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of()));
        });
    }
    /** Applies trusted runtime defaults to a fresh compiler, then compiles the retained XML sources.
     * Runtime defaults are not written into the ZIP; persist portable formatting in XML itself.
     * Compilation/binding diagnostics retain existing TemplateException/BindingException contracts. */
    public DocumentTemplate compile(Consumer<XmlTemplate.Builder> configure) throws IOException {
        Objects.requireNonNull(configure, "configure");
        return ExecutionContext.execute(execution, () -> {
            var compiler = XmlTemplate.builder(); fragments.forEach(compiler::fragment); images.forEach(compiler::image);
            configure.accept(compiler); return compiler.build().compile(source);
        });
    }
    public static XmlTemplateBundle read(byte[] bytes) throws IOException { return read(bytes, Limits.DEFAULT, ExecutionOptions.DEFAULT); }
    public static XmlTemplateBundle read(byte[] bytes, Limits limits, ExecutionOptions execution) throws IOException {
        Objects.requireNonNull(bytes, "bytes"); Objects.requireNonNull(limits, "limits"); Objects.requireNonNull(execution, "execution");
        if (bytes.length > limits.maxEncodedBytes()) { throw new IOException("template bundle encoded budget exceeded"); }
        byte[] snapshot = bytes.clone();
        return ExecutionContext.execute(execution, () -> {
            var builder = new Builder().limits(limits).execution(execution);
            builder.entries.putAll(TemplateBundleIO.unzip(snapshot, limits)); return builder.build();
        });
    }
    public static XmlTemplateBundle read(InputStream input) throws IOException { return read(input, Limits.DEFAULT, ExecutionOptions.DEFAULT); }
    public static XmlTemplateBundle read(InputStream input, Limits limits, ExecutionOptions execution) throws IOException {
        Objects.requireNonNull(input, "input"); Objects.requireNonNull(limits, "limits"); Objects.requireNonNull(execution, "execution");
        return ExecutionContext.execute(execution, () -> read(TemplateBundleIO.read(input, limits.maxEncodedBytes()), limits, execution));
    }
    /** Deterministic ZIP encoding; does not compile drafts or rewrite XML/resource bytes. */
    public byte[] encode() throws IOException {
        return ExecutionContext.execute(execution, () -> TemplateBundleIO.zip(entries, limits));
    }
    /** Borrowed stream, not closed/flushed; physical write failures can leave partial bytes. */
    public void write(OutputStream output) throws IOException {
        Objects.requireNonNull(output, "output"); byte[] bytes = encode();
        ExecutionContext.execute(execution, () -> { ExecutionContext.output(output).write(bytes); return null; });
    }
    /** Parent directory must exist. Same-directory temporary and atomic replacement; no copy fallback.
     * Failure/cancellation before publication preserves the old target and removes the temporary. */
    public void write(Path output) throws IOException { TemplateBundleIO.publish(encode(), output, execution); }
    private static String stem(String name) { return name.substring(name.indexOf('/') + 1, name.lastIndexOf('.')); }
    private static String decode(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) { throw new IOException("template source must be UTF-8"); }
    }
    private static byte[] encode(String source) {
        try {
            var buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(Objects.requireNonNull(source, "source")));
            var bytes = new byte[buffer.remaining()]; buffer.get(bytes); return bytes;
        } catch (CharacterCodingException failure) { throw new IllegalArgumentException("invalid template source Unicode"); }
    }
    /** Task-confined source/resource editor; named setters replace the selected entry. Removing a
     * referenced asset is permitted for drafts but compile subsequently fails until it is supplied. */
    public static final class Builder {
        private final Map<String, byte[]> entries = new LinkedHashMap<>();
        private Limits limits = Limits.DEFAULT;
        private ExecutionOptions execution = ExecutionOptions.DEFAULT;
        private Builder() { }
        public Builder source(String value) { entries.put("template.xml", encode(value)); return this; }
        public Builder fragment(String name, String value) {
            if (!Objects.requireNonNull(name, "name").matches("[A-Za-z_][A-Za-z_0-9-]{0,63}")) { throw new IllegalArgumentException("invalid fragment name"); }
            entries.put("fragments/" + name + ".xml", encode(value)); return this;
        }
        public Builder image(String name, ImageResource image) {
            if (!Objects.requireNonNull(name, "name").matches("[A-Za-z_][A-Za-z_0-9]{0,255}")) { throw new IllegalArgumentException("invalid image name"); }
            Objects.requireNonNull(image, "image");
            entries.keySet().removeIf(path -> path.startsWith("images/") && stem(path).equals(name));
            entries.put("images/" + name + (image.mediaType().equals("image/png") ? ".png" : ".jpg"), image.bytes()); return this;
        }
        public Builder font(String filename, byte[] bytes) {
            Objects.requireNonNull(filename, "filename");
            if (!filename.matches("[A-Za-z_][A-Za-z_0-9-]{0,63}\\.(ttf|otf|ttc|otc)")) { throw new IllegalArgumentException("invalid font filename"); }
            entries.put("fonts/" + filename, Objects.requireNonNull(bytes, "bytes").clone()); return this;
        }
        public Builder license(String filename, String text) {
            if (!Objects.requireNonNull(filename, "filename").matches("[A-Za-z_][A-Za-z_0-9-]{0,63}\\.txt")) {
                throw new IllegalArgumentException("invalid license filename");
            }
            entries.put("licenses/" + filename, encode(text)); return this;
        }
        /** Removes one exact package entry; paths cannot escape the ZIP namespace. */
        public Builder remove(String path) {
            Objects.requireNonNull(path, "path");
            try { TemplateBundleIO.fileName(path); } catch (IOException failure) { throw new IllegalArgumentException("invalid template entry name"); }
            entries.remove(path); return this;
        }
        public Builder limits(Limits value) { limits = Objects.requireNonNull(value, "limits"); return this; }
        public Builder execution(ExecutionOptions value) { execution = Objects.requireNonNull(value, "execution"); return this; }
        public XmlTemplateBundle build() throws IOException {
            return ExecutionContext.execute(execution, () -> new XmlTemplateBundle(this));
        }
    }
}
