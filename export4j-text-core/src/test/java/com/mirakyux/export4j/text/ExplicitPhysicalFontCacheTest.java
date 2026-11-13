// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.mirakyux.export4j.font.FontFaceCache;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import java.awt.Font;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ExplicitPhysicalFontCacheTest {
    private final ArrayList<ExplicitPhysicalFontCache> caches = new ArrayList<>();

    private ExplicitPhysicalFontCache cache(int maxEntries, long maxSourceBytes) {
        var cache = new ExplicitPhysicalFontCache(maxEntries, maxSourceBytes);
        caches.add(cache);
        return cache;
    }

    @AfterEach void releaseCaches() { caches.forEach(ExplicitPhysicalFontCache::close); }

    @Test void identicalProgramsReuseOneBaseFontAcrossDistinctFontDataInstances() throws IOException {
        var first = data("LiberationSans-Regular.ttf");
        var second = data("LiberationSans-Regular.ttf");
        var cache = cache(4, 64L * 1024 * 1024);
        assertNotSame(first, second);
        assertEquals(first.id(), second.id());
        assertSame(cache.get(first), cache.get(second));
    }

    @Test void entryLimitEvictsTheLeastRecentlyUsedProgram() throws IOException {
        var first = data("LiberationSans-Regular.ttf");
        var second = data("SourceSans3-Regular.otf");
        var third = data("NotoNaskhArabic-Regular.ttf");
        var cache = cache(2, 64L * 1024 * 1024);
        Font firstFont = cache.get(first);
        Font secondFont = cache.get(second);
        assertSame(firstFont, cache.get(first));
        Font thirdFont = cache.get(third);
        assertSame(firstFont, cache.get(first));
        assertSame(thirdFont, cache.get(third));
        assertNotSame(secondFont, cache.get(second));
    }

    @Test void sourceByteLimitEvictsEvenWhenThereAreSpareEntries() throws IOException {
        var first = data("LiberationSans-Regular.ttf");
        var second = data("SourceSans3-Regular.otf");
        long budget = Math.max(first.byteSize(), second.byteSize());
        var cache = cache(4, budget);
        Font firstFont = cache.get(first);
        Font secondFont = cache.get(second);
        assertSame(secondFont, cache.get(second));
        assertNotSame(firstFont, cache.get(first));
    }

    @Test void oversizedProgramsLoadWithoutBecomingResident() throws IOException {
        var program = data("LiberationSans-Regular.ttf");
        var cache = cache(4, program.byteSize() - 1L);
        assertNotSame(cache.get(program), cache.get(program));
    }

    @Test @Timeout(30)
    void concurrentMissesReuseOneLoadedBaseFont() throws Exception {
        var program = data("LiberationSans-Regular.ttf");
        var cache = cache(4, 64L * 1024 * 1024);
        int workers = 8;
        var start = new CyclicBarrier(workers);
        var results = new ArrayList<Future<Font>>();
        try (var executor = Executors.newFixedThreadPool(workers)) {
            for (int worker = 0; worker < workers; worker++) {
                results.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return cache.get(program);
                }));
            }
            Font shared = results.getFirst().get(15, TimeUnit.SECONDS);
            for (var result : results) { assertSame(shared, result.get(15, TimeUnit.SECONDS)); }
        }
    }

    @Test void idleEvictionUsesLastAccessAndLeavesReturnedFontsUsable() throws IOException {
        var clock = new AtomicLong();
        var program = data("LiberationSans-Regular.ttf");
        try (var cache = new ExplicitPhysicalFontCache(4, 64L * 1024 * 1024,
                Duration.ofMinutes(1), clock::get)) {
            Font first = cache.get(program);
            clock.set(Duration.ofSeconds(40).toNanos());
            assertSame(first, cache.get(program));
            clock.set(Duration.ofSeconds(70).toNanos());
            cache.cleanUp();
            assertEquals(1, cache.entryCount());
            clock.set(Duration.ofSeconds(100).toNanos());
            cache.cleanUp();
            assertEquals(0, cache.entryCount());
            assertTrue(first.canDisplay('A'));
            assertNotSame(first, cache.get(program));
        }
    }

    @Test @Timeout(10) void idleFontsExpireWithoutAnotherLoad() throws Exception {
        try (var cache = new ExplicitPhysicalFontCache(4, 64L * 1024 * 1024,
                Duration.ofMillis(40), System::nanoTime);
                var observer = Executors.newSingleThreadScheduledExecutor()) {
            cache.get(data("LiberationSans-Regular.ttf"));
            var expired = new CountDownLatch(1);
            observer.scheduleAtFixedRate(() -> {
                if (cache.entryCount() == 0) { expired.countDown(); }
            }, 0, 5, TimeUnit.MILLISECONDS);
            assertTrue(expired.await(5, TimeUnit.SECONDS), "idle entries must expire without a cache operation");
        }
    }

    private static FontData data(String name) throws IOException {
        try (var cache = FontFaceCache.builder().enabled(false).build();
                var input = ExplicitPhysicalFontCacheTest.class.getResourceAsStream("/fonts/" + name)) {
            return cache.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES).data();
        }
    }
}
