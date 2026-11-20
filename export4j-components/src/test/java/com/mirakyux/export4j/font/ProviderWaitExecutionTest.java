// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font;

import com.mirakyux.export4j.components.DocumentEngineProvider;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Detects cancelled provider waits before shared parsing finishes or application code can run. */
class ProviderWaitExecutionTest {
    @TempDir Path directory;

    @Test @Timeout(value = 40, unit = TimeUnit.SECONDS)
    void builtInSourcesObserveCancellationInDefaultAndNamedFontStacks() throws Exception {
        byte[] bytes = font();
        Path file = directory.resolve("body.ttf");
        Files.write(file, bytes);
        var sources = List.of(FontSource.bytes(bytes), FontSource.file(file),
                FontSource.resource(ProviderWaitExecutionTest.class, "/fonts/LiberationSans-Regular.ttf"));
        int index = 0;
        for (var source : sources) {
            for (boolean named : List.of(false, true)) {
                waitingProvider(bytes, source, named, false, "cancel-" + index++);
            }
        }
    }

    @Test @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void defaultAndNamedFontStacksObserveDeadlineBeforeSharedOwnerFinishes() throws Exception {
        byte[] bytes = font();
        for (boolean named : List.of(false, true)) {
            waitingProvider(bytes, FontSource.bytes(bytes), named, true, "deadline-" + named);
        }
    }

    @Test
    void legacyCustomSourceCancellingOnReturnCannotStartApplicationOperation() throws IOException {
        var cancelled = new AtomicBoolean();
        var parsed = TrueTypeFontLoader.loadSnapshotUncached(font());
        FontSource source = cache -> { cancelled.set(true); return parsed; };
        assertApplicationSkipped(source, cancelled);
    }

    @Test
    void customCheckpointOverloadCannotBypassProviderFinalCheckpoint() throws IOException {
        var cancelled = new AtomicBoolean();
        var parsed = TrueTypeFontLoader.loadSnapshotUncached(font());
        FontSource source = new FontSource() {
            @Override public FontFace load(FontFaceCache cache) {
                throw new AssertionError("provider must call the checkpoint overload");
            }
            @Override public FontFace load(FontFaceCache cache, Runnable checkpoint) {
                // Trusted custom code may return without invoking the supplied checkpoint.
                cancelled.set(true);
                return parsed;
            }
        };
        assertApplicationSkipped(source, cancelled);
    }

    private void waitingProvider(byte[] bytes, FontSource source, boolean named, boolean timeout, String name) throws Exception {
        FontFace parsed = TrueTypeFontLoader.loadSnapshotUncached(bytes);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        var callback = new AtomicBoolean();
        var waitingThread = new AtomicReference<Thread>();
        var peerThread = new AtomicReference<Thread>();
        var workers = Executors.newFixedThreadPool(3);
        byte[] previous = {7, 8, 9};
        Path target = directory.resolve(name);
        Files.write(target, previous);
        try (var cache = new FontFaceCache(FontFaceCache.builder(), System::nanoTime,
                () -> new ScheduledThreadPoolExecutor(1), ignored -> {
                    entered.countDown();
                    try {
                        if (!release.await(12, TimeUnit.SECONDS)) { throw new IOException("controlled owner was not released"); }
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IOException("controlled owner interrupted", failure);
                    }
                    return parsed;
                })) {
            FontSource defaultSource = named ? ignored -> parsed : source;
            var builder = DocumentEngineProvider.builder(List.of(defaultSource)).fontCache(cache);
            if (named) { builder.fontRole("report", List.of(source)); }
            try (var provider = builder.build()) {
                var owner = workers.submit(() -> cache.load(bytes));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var peer = workers.submit(() -> {
                    peerThread.set(Thread.currentThread());
                    return cache.load(bytes);
                });
                awaitCacheWait(peerThread);
                var waiting = workers.submit(() -> {
                    waitingThread.set(Thread.currentThread());
                    var options = ExecutionOptions.builder().cancelled(cancelled::get)
                            .timeout(timeout ? Duration.ofSeconds(1) : Duration.ofSeconds(10)).build();
                    return ExecutionContext.execute(options, () -> provider.withEngine(engine -> {
                        callback.set(true);
                        Files.write(target, new byte[] {1});
                        return true;
                    }));
                });
                awaitCacheWait(waitingThread);
                if (!timeout) { cancelled.set(true); }
                var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> waiting.get(4, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof ExecutionException);
                assertEquals(timeout ? ExecutionException.Code.TIMEOUT : ExecutionException.Code.CANCELLED,
                        ((ExecutionException) failure.getCause()).code());
                assertFalse(callback.get(), "application code must not start after cancellation or deadline");
                assertFalse(owner.isDone(), "the provider must exit before shared parsing is released");
                assertFalse(peer.isDone(), "cancelling the provider must not complete another caller");
                assertArrayEquals(previous, Files.readAllBytes(target));
                release.countDown();
                assertSame(parsed, owner.get(5, TimeUnit.SECONDS));
                assertSame(parsed, peer.get(5, TimeUnit.SECONDS));
                assertSame(parsed, cache.load(bytes));
                int pages = provider.withEngine(engine -> engine.layout(
                        Document.builder().paragraph("Usable after cancelled provider wait").build()).display().pages().size());
                assertEquals(1, pages);
                assertEquals(1, cache.stats().loads());
            }
            assertSame(parsed, cache.load(bytes), "provider must not close its borrowed cache");
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void assertApplicationSkipped(FontSource source, AtomicBoolean cancelled) throws IOException {
        var callback = new AtomicBoolean();
        try (var provider = DocumentEngineProvider.builder(List.of(source)).build()) {
            var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(
                    ExecutionOptions.builder().cancelled(cancelled::get).build(), () -> provider.withEngine(engine -> {
                        callback.set(true);
                        return null;
                    })));
            assertEquals(ExecutionException.Code.CANCELLED, failure.code());
            assertFalse(callback.get());
        }
    }

    private static void awaitCacheWait(AtomicReference<Thread> reference) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            var thread = reference.get();
            if (thread != null && (thread.getState() == Thread.State.WAITING || thread.getState() == Thread.State.TIMED_WAITING)
                    && Arrays.stream(thread.getStackTrace()).anyMatch(frame -> frame.getClassName().equals("java.util.concurrent.CompletableFuture")
                            && frame.getMethodName().equals("get"))) { return; }
            assertTrue(System.nanoTime() < deadline, "provider must reach shared cache waiting before cancellation is tested");
            Thread.yield();
        }
    }

    private static byte[] font() throws IOException {
        try (var input = ProviderWaitExecutionTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            if (input == null) { throw new IOException("missing test font"); }
            return input.readAllBytes();
        }
    }
}
