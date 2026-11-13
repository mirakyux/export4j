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

    /** 每次读取明确的文件并由缓存按内容校验；不按路径或 mtime 猜测内容未变。 */
    static FontSource file(Path file) {
        var source = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        return cache -> cache.load(source);
    }

    /** 保存编码字节的私有快照，不保存 FontFace。来源本身的编码字节随来源对象存活。 */
    static FontSource bytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > TrueTypeFontLoader.DEFAULT_MAX_BYTES) {
            throw new IllegalArgumentException("font source byte limit exceeded");
        }
        byte[] snapshot = bytes.clone();
        return cache -> cache.load(snapshot);
    }

    /** 从明确的类资源重新打开并关闭本次拥有的流；不扫描类路径或系统字体。 */
    static FontSource resource(Class<?> owner, String name) {
        Objects.requireNonNull(owner, "owner"); Objects.requireNonNull(name, "name");
        if (name.isBlank()) { throw new IllegalArgumentException("empty font resource name"); }
        return cache -> {
            try (var input = owner.getResourceAsStream(name)) {
                if (input == null) { throw new IOException("explicit font resource is missing"); }
                return cache.load(input);
            }
        };
    }
}
