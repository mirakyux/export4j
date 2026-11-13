// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FontFaceCacheTest {
    @TempDir Path directory;

    @Test
    void defaultLoaderSharesContentAndClearKeepsReturnedFacesUsable() throws IOException {
        TrueTypeFontLoader.clearCache();
        try {
            byte[] bytes = bytes("LiberationSans-Regular.ttf");
            FontFace first = TrueTypeFontLoader.load(bytes);
            assertSame(first, TrueTypeFontLoader.load(bytes.clone()));
            assertEquals(1, TrueTypeFontLoader.cacheStats().entries());
            TrueTypeFontLoader.clearCache();
            assertEquals(0, TrueTypeFontLoader.cacheStats().entries());
            assertTrue(first.glyph('A').isPresent());
            assertNotSame(first, TrueTypeFontLoader.load(bytes));
        } finally { TrueTypeFontLoader.clearCache(); }
    }

    @Test
    void sourceSnapshotsAndOverriddenStreamArraysCannotChangeCachedContent() throws IOException {
        byte[] original = bytes("LiberationSans-Regular.ttf");
        byte[] external = original.clone();
        class Input extends ByteArrayInputStream {
            boolean closed;
            Input() { super(external); }
            @Override public byte[] readNBytes(int length) { return external; }
            @Override public void close() { closed = true; }
        }
        try (var cache = FontFaceCache.builder().build()) {
            var input = new Input();
            FontFace face = cache.load(input);
            Arrays.fill(external, (byte) 0);
            Arrays.fill(face.data().bytes(), (byte) 0);
            assertFalse(input.closed);
            assertArrayEquals(original, face.data().bytes());
            assertSame(face, cache.load(original));
            assertEquals(1, cache.stats().loads());
        }
    }

    @Test
    void tighterPerCallBudgetsStillApplyToHitsAndBorrowedStreams() throws IOException {
        byte[] bytes = bytes("LiberationSans-Regular.ttf");
        class Input extends ByteArrayInputStream {
            boolean closed;
            Input() { super(bytes); }
            @Override public void close() { closed = true; }
        }
        try (var cache = FontFaceCache.builder().build()) {
            cache.load(bytes);
            assertEquals(FontLoadException.Code.BUDGET_EXCEEDED,
                    assertThrows(FontLoadException.class, () -> cache.load(bytes, bytes.length - 1)).code());
            var input = new Input();
            assertEquals(FontLoadException.Code.BUDGET_EXCEEDED,
                    assertThrows(FontLoadException.class, () -> cache.load(input, 32)).code());
            assertEquals(33, bytes.length - input.available());
            assertFalse(input.closed);
            assertEquals(1, cache.stats().loads());
            assertEquals(0, cache.stats().hits());
        }
    }

    @Test
    void explicitFileReplacementIsReadAndContentValidatedOnEveryCall() throws IOException {
        Path file = directory.resolve("font.ttf");
        Files.write(file, bytes("LiberationSans-Regular.ttf"));
        try (var cache = FontFaceCache.builder().build()) {
            FontFace regular = cache.load(file);
            assertSame(regular, cache.load(file));
            Files.write(file, bytes("LiberationSans-Bold.ttf"));
            FontFace bold = cache.load(file);
            assertNotEquals(regular.data().id(), bold.data().id());
            assertNotSame(regular, bold);
            assertEquals(2, cache.stats().loads());
            Files.move(file, directory.resolve("renamed.ttf"));
        }
    }

    @Test
    void expiredReferencesAreRemovedByScheduledWorkWithoutAnotherLoad() throws IOException {
        var clock = new AtomicLong();
        var schedulers = new ArrayList<ManualScheduler>();
        try (var cache = controlled(clock, schedulers)) {
            FontFace first = cache.load(bytes("LiberationSans-Regular.ttf"));
            clock.set(90);
            assertSame(first, cache.load(first.data().bytes()));
            clock.set(100);
            schedulers.getFirst().fireNext();
            assertEquals(1, cache.stats().entries());
            assertEquals(0, cache.stats().expirations());
            clock.set(190);
            schedulers.getFirst().fireNext();
            assertEquals(0, cache.stats().entries());
            assertEquals(0, cache.stats().sourceBytes());
            assertEquals(1, cache.stats().expirations());
            assertTrue(schedulers.getFirst().isShutdown());
            assertTrue(first.glyph('A').isPresent());
            assertNotSame(first, cache.load(first.data().bytes()));
            assertEquals(2, schedulers.size());
        }
    }

    @Test
    void realCleanupRunsWithoutARequestAndStopsItsExecutor() throws Exception {
        var cleaned = new CountDownLatch(1);
        var executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "font-face-cleanup-test");
            thread.setDaemon(true);
            thread.setContextClassLoader(null);
            return thread;
        }) {
            @Override protected void afterExecute(Runnable task, Throwable failure) {
                super.afterExecute(task, failure);
                cleaned.countDown();
            }
        };
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        try (var cache = new FontFaceCache(FontFaceCache.builder().idleTimeout(Duration.ofMillis(50)),
                System::nanoTime, () -> executor)) {
            FontFace face = cache.load(bytes("LiberationSans-Regular.ttf"));
            assertTrue(cleaned.await(5, TimeUnit.SECONDS));
            assertEquals(0, cache.stats().entries());
            assertEquals(1, cache.stats().expirations());
            assertTrue(executor.isShutdown());
            assertTrue(face.glyph('A').isPresent());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentRequestsForTheSameContentShareOneSuccessfulParse() throws Exception {
        byte[] font = bytes("LiberationSans-Regular.ttf");
        var ready = new CountDownLatch(4);
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(4);
        try (var cache = FontFaceCache.builder().build()) {
            var futures = new ArrayList<java.util.concurrent.Future<FontFace>>();
            for (int index = 0; index < 4; index++) {
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) { throw new AssertionError("start not signalled"); }
                    return cache.load(font);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            FontFace face = futures.getFirst().get(10, TimeUnit.SECONDS);
            for (var future : futures) { assertSame(face, future.get(10, TimeUnit.SECONDS)); }
            assertEquals(1, cache.stats().loads());
            assertEquals(3, cache.stats().hits());
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cleanupThreadDoesNotInheritTheLoadingThreadsApplicationContext() throws IOException {
        var inherited = new AtomicInteger();
        var context = new InheritableThreadLocal<Object>() {
            @Override protected Object childValue(Object value) {
                inherited.incrementAndGet();
                return value;
            }
        };
        context.set(new Object());
        try (var cache = FontFaceCache.builder().build()) {
            cache.load(bytes("LiberationSans-Regular.ttf"));
            assertEquals(0, inherited.get());
        } finally { context.remove(); }
    }

    @Test
    void leastRecentlyUsedFacesAreEvictedUnderEntryAndSourceBudgets() throws IOException {
        byte[] regular = bytes("LiberationSans-Regular.ttf");
        byte[] bold = bytes("LiberationSans-Bold.ttf");
        try (var cache = FontFaceCache.builder().maxEntries(2).build()) {
            FontFace first = cache.load(regular);
            FontFace second = cache.load(bold);
            assertSame(first, cache.load(regular));
            cache.load(bytes("LiberationSans-Italic.ttf"));
            assertSame(first, cache.load(regular));
            assertNotSame(second, cache.load(bold));
            assertEquals(2, cache.stats().entries());
            assertEquals(2, cache.stats().evictions());
        }
        long budget = Math.max(regular.length, bold.length);
        try (var cache = FontFaceCache.builder().maxSourceBytes(budget).build()) {
            FontFace first = cache.load(regular);
            cache.load(bold);
            assertNotSame(first, cache.load(regular));
            assertEquals(1, cache.stats().entries());
            assertTrue(cache.stats().sourceBytes() <= budget);
        }
    }

    @Test
    void oversizedAndDisabledCachesReturnFacesWithoutRetainingThem() throws IOException {
        byte[] font = bytes("LiberationSans-Regular.ttf");
        for (var builder : List.of(FontFaceCache.builder().maxSourceBytes(font.length - 1L),
                FontFaceCache.builder().enabled(false))) {
            try (var cache = builder.build()) {
                assertNotSame(cache.load(font), cache.load(font));
                assertEquals(0, cache.stats().entries());
                assertEquals(0, cache.stats().sourceBytes());
                assertEquals(2, cache.stats().loads());
            }
        }
    }

    @Test
    void failedParsesAreNotRetainedAndDoNotStartCleanup() {
        var schedulers = new ArrayList<ManualScheduler>();
        try (var cache = controlled(new AtomicLong(), schedulers)) {
            for (int index = 0; index < 2; index++) {
                assertEquals(FontLoadException.Code.INVALID_FONT,
                        assertThrows(FontLoadException.class, () -> cache.load(new byte[12])).code());
            }
            assertEquals(2, cache.stats().misses());
            assertEquals(0, cache.stats().loads());
            assertEquals(0, cache.stats().entries());
            assertTrue(schedulers.isEmpty());
        }
    }

    @Test
    void clearAndCloseCancelCleanupWithoutInvalidatingReturnedFaces() throws IOException {
        var schedulers = new ArrayList<ManualScheduler>();
        var cache = controlled(new AtomicLong(), schedulers);
        try {
            FontFace face = cache.load(bytes("LiberationSans-Regular.ttf"));
            ManualTask pending = schedulers.getFirst().tasks.getFirst();
            cache.clear();
            assertTrue(pending.isCancelled());
            assertTrue(schedulers.getFirst().isShutdown());
            assertEquals(0, cache.stats().entries());
            assertTrue(face.glyph('A').isPresent());
            cache.load(face.data().bytes());
            cache.close();
            cache.close();
            assertTrue(schedulers.getLast().isShutdown());
            assertThrows(IllegalStateException.class, () -> cache.load(face.data().bytes()));
            assertThrows(IllegalStateException.class,
                    () -> cache.load(new ByteArrayInputStream(face.data().bytes())));
            assertThrows(IllegalStateException.class, () -> cache.load(directory.resolve("absent.ttf")));
            assertTrue(face.glyph('A').isPresent());
        } finally { cache.close(); }
    }

    @Test
    void invalidConfigurationAndInputLimitsFailBeforeReading() {
        assertThrows(IllegalArgumentException.class, () -> FontFaceCache.builder().maxEntries(0));
        assertThrows(IllegalArgumentException.class, () -> FontFaceCache.builder().maxSourceBytes(0));
        assertThrows(IllegalArgumentException.class, () -> FontFaceCache.builder().idleTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> FontFaceCache.builder().idleTimeout(Duration.ofSeconds(Long.MAX_VALUE)));
        try (var cache = FontFaceCache.builder().build()) {
            assertThrows(IllegalArgumentException.class, () -> cache.load(directory.resolve("absent.ttf"), 0));
            assertThrows(IllegalArgumentException.class, () -> cache.load(new byte[12], Integer.MAX_VALUE));
        }
    }

    private static FontFaceCache controlled(AtomicLong clock, List<ManualScheduler> schedulers) {
        return new FontFaceCache(FontFaceCache.builder().idleTimeout(Duration.ofNanos(100)), clock::get,
                () -> { var scheduler = new ManualScheduler(); schedulers.add(scheduler); return scheduler; });
    }

    private static byte[] bytes(String name) throws IOException {
        try (var input = FontFaceCacheTest.class.getResourceAsStream("/fonts/" + name)) {
            if (input == null) { throw new IOException("Missing test font"); }
            return input.readAllBytes();
        }
    }

    private static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        private final List<ManualTask> tasks = new ArrayList<>();

        private ManualScheduler() { super(1); }

        @Override public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
            var scheduled = new ManualTask(task, unit.toNanos(delay));
            tasks.add(scheduled);
            return scheduled;
        }

        private void fireNext() { tasks.removeFirst().run(); }
    }

    private static final class ManualTask extends FutureTask<Void> implements ScheduledFuture<Void> {
        private final long delay;

        private ManualTask(Runnable task, long delay) { super(task, null); this.delay = delay; }
        @Override public long getDelay(TimeUnit unit) { return unit.convert(delay, TimeUnit.NANOSECONDS); }
        @Override public int compareTo(Delayed other) {
            return Long.compare(delay, other.getDelay(TimeUnit.NANOSECONDS));
        }
    }
}
