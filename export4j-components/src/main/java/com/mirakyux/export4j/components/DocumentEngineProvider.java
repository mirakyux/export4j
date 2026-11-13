// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontFaceCache;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.FontSource;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.model.ExecutionContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 适合长期应用单例的显式字体来源及短生命周期引擎入口。
 * 本对象不缓存 FontFace、FontGroup 或 DocumentEngine；默认拥有一个有界、自动过期的字体缓存。
 * 操作同步执行，可并发调用，调用结束后仅缓存继续持有字体。返回的布局或调用方自行保存的
 * 引擎仍可能持有字体；本类不会使这些对象失效，也不会触发 GC。
 */
public final class DocumentEngineProvider implements AutoCloseable {
    @FunctionalInterface
    public interface Operation<T> { T execute(DocumentEngine engine) throws IOException; }

    private final List<FontSource> defaults;
    private final Map<String, List<FontSource>> roles;
    private final FontFaceCache cache;
    private final boolean ownsCache;
    private final DocumentLayoutLimits layoutLimits;
    private final ComponentLimits componentLimits;
    private final AtomicBoolean closed = new AtomicBoolean();

    private DocumentEngineProvider(Builder builder) {
        defaults = builder.defaults; roles = Map.copyOf(builder.roles);
        ownsCache = builder.cache == null; cache = ownsCache ? FontFaceCache.builder().build() : builder.cache;
        layoutLimits = builder.layoutLimits; componentLimits = builder.componentLimits;
    }
    public static Builder builder(List<FontSource> defaults) { return new Builder(defaults); }

    /** 关闭后不接受新操作；已进入的操作持有独立不可变字体，不会被缓存清理或关闭破坏。 */
    public <T> T withEngine(Operation<T> operation) throws IOException {
        Objects.requireNonNull(operation, "operation");
        if (closed.get()) { throw new IllegalStateException("engine provider is closed"); }
        ExecutionContext.checkpoint();
        var loaded = new IdentityHashMap<FontSource, FontFace>();
        var builder = DocumentEngine.builder(group(defaults, loaded)).layoutLimits(layoutLimits).componentLimits(componentLimits);
        for (var entry : roles.entrySet()) { builder.fontRole(entry.getKey(), group(entry.getValue(), loaded)); }
        if (closed.get()) { throw new IllegalStateException("engine provider is closed"); }
        return operation.execute(builder.build());
    }

    public FontFaceCache.Stats cacheStats() { return cache.stats(); }
    /** 清除缓存引用；不会清除操作或调用方持有的字体。 */
    public void clearCache() { cache.clear(); }
    @Override public void close() { if (closed.compareAndSet(false, true) && ownsCache) { cache.close(); } }

    private FontGroup group(List<FontSource> sources, IdentityHashMap<FontSource, FontFace> loaded) throws IOException {
        var faces = new ArrayList<FontFace>();
        for (var source : sources) {
            ExecutionContext.checkpoint();
            var face = loaded.get(source);
            if (face == null) { face = Objects.requireNonNull(source.load(cache), "font source result"); loaded.put(source, face); }
            faces.add(face);
        }
        return FontGroup.strict(faces);
    }

    public static final class Builder {
        private final List<FontSource> defaults;
        private final Map<String, List<FontSource>> roles = new LinkedHashMap<>();
        private FontFaceCache cache;
        private DocumentLayoutLimits layoutLimits = DocumentLayoutLimits.DEFAULT;
        private ComponentLimits componentLimits = ComponentLimits.DEFAULT;
        private Builder(List<FontSource> defaults) { this.defaults = sources(defaults); }
        public Builder fontRole(String name, List<FontSource> sources) {
            if (Objects.requireNonNull(name, "name").isBlank()) { throw new IllegalArgumentException("empty font role"); }
            if (roles.putIfAbsent(name, sources(sources)) != null) { throw new IllegalArgumentException("duplicate font role"); }
            return this;
        }
        /** 借用显式缓存，provider.close 不关闭它。调用方负责其生命周期。 */
        public Builder fontCache(FontFaceCache value) { cache = Objects.requireNonNull(value, "cache"); return this; }
        public Builder layoutLimits(DocumentLayoutLimits value) { layoutLimits = Objects.requireNonNull(value, "layoutLimits"); return this; }
        public Builder componentLimits(ComponentLimits value) { componentLimits = Objects.requireNonNull(value, "componentLimits"); return this; }
        public DocumentEngineProvider build() { return new DocumentEngineProvider(this); }
        private static List<FontSource> sources(List<FontSource> value) {
            var copy = List.copyOf(value);
            if (copy.isEmpty()) { throw new IllegalArgumentException("font source stack is empty"); }
            return copy;
        }
    }
}
