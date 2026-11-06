// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import java.awt.Font;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ExplicitPhysicalFontCacheTest {
    @Test void identicalProgramsReuseOneBaseFontAcrossDistinctFontDataInstances() throws IOException {
        var first = data("LiberationSans-Regular.ttf");
        var second = data("LiberationSans-Regular.ttf");
        var cache = new ExplicitPhysicalFontCache(4, 64L * 1024 * 1024);
        assertNotSame(first, second);
        assertEquals(first.id(), second.id());
        assertSame(cache.get(first), cache.get(second));
    }

    @Test void entryLimitEvictsTheLeastRecentlyUsedProgram() throws IOException {
        var first = data("LiberationSans-Regular.ttf");
        var second = data("SourceSans3-Regular.otf");
        var third = data("NotoNaskhArabic-Regular.ttf");
        var cache = new ExplicitPhysicalFontCache(2, 64L * 1024 * 1024);
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
        var cache = new ExplicitPhysicalFontCache(4, budget);
        Font firstFont = cache.get(first);
        Font secondFont = cache.get(second);
        assertSame(secondFont, cache.get(second));
        assertNotSame(firstFont, cache.get(first));
    }

    @Test void oversizedProgramsLoadWithoutBecomingResident() throws IOException {
        var program = data("LiberationSans-Regular.ttf");
        var cache = new ExplicitPhysicalFontCache(4, program.byteSize() - 1L);
        assertNotSame(cache.get(program), cache.get(program));
    }

    @Test @Timeout(30)
    void concurrentMissesReuseOneLoadedBaseFont() throws Exception {
        var program = data("LiberationSans-Regular.ttf");
        var cache = new ExplicitPhysicalFontCache(4, 64L * 1024 * 1024);
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

    private static FontData data(String name) throws IOException {
        try (var input = ExplicitPhysicalFontCacheTest.class.getResourceAsStream("/fonts/" + name)) {
            return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES).data();
        }
    }
}
