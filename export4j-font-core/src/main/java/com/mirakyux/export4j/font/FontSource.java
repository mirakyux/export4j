// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 显式、可重开的字体来源，不持有解析后的字体。实现必须适合调用方的并发方式。
 * 加载产生的 FontFace 由调用方持有，缓存只按其自身的容量和空闲策略保留引用。
 */
@FunctionalInterface
public interface FontSource {
    FontFace load(FontFaceCache cache) throws IOException;

    /**
     * Loads synchronously with a non-retained cooperative caller checkpoint. Built-in byte, file and
     * resource sources propagate it into cache waiting. This compatibility default checks before and
     * after a custom source only; a custom implementation must override this method and pass the
     * checkpoint into cache.load(..., checkpoint) to check its internal waits. Caller code and I/O
     * remain trusted synchronous code, not preemptible work. Null arguments throw NullPointerException.
     */
    default FontFace load(FontFaceCache cache, Runnable checkpoint) throws IOException {
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(checkpoint, "checkpoint").run();
        FontFace face = load(cache);
        checkpoint.run();
        return face;
    }

    /** 每次读取明确的文件并由缓存按内容校验；不按路径或 mtime 猜测内容未变。 */
    static FontSource file(Path file) {
        var source = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        return new FontSource() {
            @Override public FontFace load(FontFaceCache cache) throws IOException { return cache.load(source); }
            @Override public FontFace load(FontFaceCache cache, Runnable checkpoint) throws IOException {
                return cache.load(source, checkpoint);
            }
        };
    }

    /** 保存编码字节的私有快照，不保存 FontFace。来源本身的编码字节随来源对象存活。 */
    static FontSource bytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > TrueTypeFontLoader.DEFAULT_MAX_BYTES) {
            throw new IllegalArgumentException("font source byte limit exceeded");
        }
        return new SnapshotFontSource(bytes);
    }

    /** 从明确的类资源重新打开并关闭本次拥有的流；不扫描类路径或系统字体。 */
    static FontSource resource(Class<?> owner, String name) {
        Objects.requireNonNull(owner, "owner"); Objects.requireNonNull(name, "name");
        if (name.isBlank()) { throw new IllegalArgumentException("empty font resource name"); }
        return new FontSource() {
            @Override public FontFace load(FontFaceCache cache) throws IOException {
                try (var input = owner.getResourceAsStream(name)) {
                    if (input == null) { throw new IOException("explicit font resource is missing"); }
                    return cache.load(input);
                }
            }
            @Override public FontFace load(FontFaceCache cache, Runnable checkpoint) throws IOException {
                Objects.requireNonNull(cache, "cache");
                Objects.requireNonNull(checkpoint, "checkpoint").run();
                try (var input = owner.getResourceAsStream(name)) {
                    if (input == null) { throw new IOException("explicit font resource is missing"); }
                    return cache.load(input, checkpoint);
                }
            }
        };
    }
}
