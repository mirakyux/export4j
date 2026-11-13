// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import com.mirakyux.export4j.font.FontFaceCache;
import com.mirakyux.export4j.font.FontSource;
import com.mirakyux.export4j.model.Document;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 防止单例持有旧引擎、缓存清理破坏在用字体，或provider误关闭调用方拥有的缓存。 */
class DocumentEngineProviderTest {
    private static final FontSource SOURCE = FontSource.resource(DocumentEngineProviderTest.class, "/fonts/LiberationSans-Regular.ttf");

    @Test void clearingCachePreservesActiveEngineAndNextUseReopensSource() throws IOException {
        var loads = new AtomicInteger();
        FontSource source = cache -> { loads.incrementAndGet(); return SOURCE.load(cache); };
        try (var cache = FontFaceCache.builder().build();
                var provider = DocumentEngineProvider.builder(List.of(source)).fontRole("bold", List.of(source)).fontCache(cache).build()) {
            var document = Document.builder().paragraph("Cache lifetime").build();
            provider.withEngine(engine -> {
                assertEquals(1, engine.layout(document).display().pages().size());
                provider.clearCache();
                assertEquals(0, provider.cacheStats().entries());
                assertEquals(1, engine.layout(document).display().pages().size());
                return null;
            });
            assertEquals(1, loads.get());
            int pages = provider.withEngine(engine -> engine.layout(document).display().pages().size());
            assertEquals(1, pages);
            assertEquals(2, loads.get());
            assertEquals(2, provider.cacheStats().loads());
        }
    }

    @Test void closingProviderDoesNotCloseBorrowedCacheAndFailureDoesNotPoisonNextCall() throws IOException {
        try (var cache = FontFaceCache.builder().build()) {
            var provider = DocumentEngineProvider.builder(List.of(SOURCE)).fontCache(cache).build();
            assertThrows(IOException.class, () -> provider.withEngine(engine -> { throw new IOException("output failed"); }));
            boolean usable = provider.withEngine(engine -> !engine.layout(Document.builder().paragraph("Still usable").build()).display().pages().isEmpty());
            assertTrue(usable);
            provider.close();
            assertThrows(IllegalStateException.class, () -> provider.withEngine(engine -> null));
            assertTrue(SOURCE.load(cache).glyph('A').isPresent());
        }
    }
}
