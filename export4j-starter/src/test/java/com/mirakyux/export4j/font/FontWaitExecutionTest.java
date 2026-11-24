// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font;

import com.mirakyux.export4j.Export4j;
import com.mirakyux.export4j.FontFamily;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import java.io.ByteArrayOutputStream;
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

/** Uses the font module's existing parser test seam; no parsing thread or class replacement is added. */
class FontWaitExecutionTest {
    @TempDir Path directory;

    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void builtInSourcesObserveCancellationWhileTheirSharedOwnerIsStillParsing() throws Exception {
        byte[] font = font();
        Path file = directory.resolve("body.ttf"); Files.write(file, font);
        List<FontSource> sources = List.of(FontSource.bytes(font), FontSource.file(file),
                FontSource.resource(FontWaitExecutionTest.class, "/fonts/LiberationSans-Regular.ttf"));
        int index = 0;
        for (var source : sources) {
            for (boolean pdf : List.of(true, false)) {
                verifyWaitingRequest(font, source, false, pdf, "cancel-" + index++);
            }
        }
    }

    @Test @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void bothTargetsObserveTheExecutionDeadlineBeforeTheSharedOwnerCompletes() throws Exception {
        byte[] font = font();
        for (boolean pdf : List.of(true, false)) {
            verifyWaitingRequest(font, FontSource.bytes(font), true, pdf, "timeout-" + pdf);
        }
    }

    private void verifyWaitingRequest(byte[] font, FontSource source, boolean timeout, boolean pdf, String name) throws Exception {
        FontFace parsed = TrueTypeFontLoader.loadSnapshotUncached(font);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var cancelled = new AtomicBoolean(); var waitingThread = new AtomicReference<Thread>();
        var workers = Executors.newFixedThreadPool(2);
        byte[] previous = {7, 8, 9};
        Path target = directory.resolve(name); Files.write(target, previous);
        try (var cache = new FontFaceCache(FontFaceCache.builder(), System::nanoTime,
                () -> new ScheduledThreadPoolExecutor(1), ignored -> {
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) { throw new IOException("controlled owner was not released"); }
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt(); throw new IOException("controlled owner interrupted", failure);
                    }
                    return parsed;
                }); var exporter = Export4j.builder(FontFamily.of("Liberation Sans", source)).fontCache(cache).build()) {
            var owner = workers.submit(() -> cache.load(font));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var waiting = workers.submit(() -> {
                waitingThread.set(Thread.currentThread());
                var options = ExecutionOptions.builder().cancelled(cancelled::get)
                        .timeout(timeout ? Duration.ofSeconds(1) : Duration.ofSeconds(10)).build();
                var request = waitingRequest(exporter, pdf).execution(options);
                if (pdf) { request.pdf(target); } else { request.docx(target); }
                return null;
            });
            awaitCacheWait(waitingThread);
            if (!timeout) { cancelled.set(true); }
            var failed = assertThrows(java.util.concurrent.ExecutionException.class, () -> waiting.get(4, TimeUnit.SECONDS));
            assertTrue(failed.getCause() instanceof ExecutionException);
            assertEquals(timeout ? ExecutionException.Code.TIMEOUT : ExecutionException.Code.CANCELLED,
                    ((ExecutionException) failed.getCause()).code());
            assertFalse(owner.isDone(), "the waiting request must finish before the shared owner is released");
            assertArrayEquals(previous, Files.readAllBytes(target));
            assertEquals(0, cache.stats().loads());
            try (var files = Files.list(directory)) {
                assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".export4j-")));
            }
            release.countDown();
            assertSame(parsed, owner.get(5, TimeUnit.SECONDS));
            assertSame(parsed, cache.load(font));
            var output = new ByteArrayOutputStream();
            if (pdf) { waitingRequest(exporter, true).pdf(output); }
            else { waitingRequest(exporter, false).docx(output); }
            assertTrue(output.size() > 0); assertEquals(1, cache.stats().loads());
        } finally {
            release.countDown(); workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static Export4j.Request waitingRequest(Export4j exporter, boolean pdf) {
        // Literal formula text is the DOCX path that reads physical fonts.
        return pdf ? exporter.text("Controlled cache wait") : exporter.markdown("$x+\\text{cache}$");
    }

    private static void awaitCacheWait(AtomicReference<Thread> reference) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            var thread = reference.get();
            if (thread != null && (thread.getState() == Thread.State.WAITING || thread.getState() == Thread.State.TIMED_WAITING)
                    && Arrays.stream(thread.getStackTrace()).anyMatch(frame -> frame.getClassName().equals("java.util.concurrent.CompletableFuture")
                            && frame.getMethodName().equals("get"))) { return; }
            assertTrue(System.nanoTime() < deadline, "request must reach shared cache waiting before testing cancellation");
            Thread.yield();
        }
    }

    private static byte[] font() throws IOException {
        try (var input = FontWaitExecutionTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            if (input == null) { throw new IOException("missing test font"); }
            return input.readAllBytes();
        }
    }
}
