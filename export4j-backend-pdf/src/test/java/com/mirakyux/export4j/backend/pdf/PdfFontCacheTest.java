// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.GlyphRun;
import com.mirakyux.export4j.text.SimpleTextShaper;

class PdfFontCacheTest {
    @TempDir Path directory;

    @Test
    void separateExportsReuseAProgramAndKeepSubsetsAndUnicodeIndependent() throws IOException {
        var face = font("LiberationSans-Regular.ttf");
        try (var cache = PdfFontCache.builder().build()) {
            for (var text : List.of("AB", "CD", "éÅ", "AB")) {
                var backend = PdfBackend.builder().fontCache(cache).build();
                var output = new ByteArrayOutputStream();
                backend.write(document(face, text), output);
                assertEquals(text, text(output));
            }
            assertEquals(1, cache.stats().parses());
            assertEquals(3, cache.stats().hits());
            assertEquals(1, cache.stats().entries());
            assertEquals(0, cache.stats().borrowedEntries());
        }
    }

    @Test
    void ordinaryPageSessionsAndSpoolSessionsUseTheSameCallerOwnedPool() throws IOException {
        var face = font("LiberationSans-Regular.ttf");
        try (var cache = PdfFontCache.builder().build()) {
            for (int index = 0; index < 2; index++) {
                var output = new ByteArrayOutputStream();
                try (var writer = PdfBackend.builder().fontCache(cache).build().open(DocumentMetadata.EMPTY, output)) {
                    writer.accept(page(face, "AB"));
                    writer.accept(page(face, "CD"));
                    assertEquals(1, cache.stats().borrowedEntries());
                    writer.finish();
                }
                assertEquals("AB\nCD", text(output));
            }
            var output = new ByteArrayOutputStream();
            var spool = new PdfSpoolBackend(directory, PdfLimits.DEFAULT, PdfSpoolLimits.DEFAULT, cache);
            try (var writer = spool.open(DocumentMetadata.EMPTY, output)) {
                writer.accept(page(face, "éÅ"));
                writer.finish();
            }
            assertEquals("éÅ", text(output));
            assertEquals(1, cache.stats().parses());
            assertEquals(2, cache.stats().hits());
            assertFalse(cache.stats().closed());
        }
    }

    @Test
    void idleExpiryStartsOnReturnAndNeverClosesAnActiveBorrower() throws IOException {
        var face = font("LiberationSans-Regular.ttf");
        var clock = new AtomicLong();
        var closes = new AtomicInteger();
        try (var cache = new PdfFontCache(PdfFontCache.builder().idleTimeout(Duration.ofDays(1)),
                clock::get, PdfFontPrograms::parse, font -> { font.close(); closes.incrementAndGet(); })) {
            var lease = cache.acquire(face.data());
            clock.set(Duration.ofDays(2).toNanos());
            cache.cleanUp();
            assertEquals(0, closes.get());
            assertEquals(1, cache.stats().borrowedEntries());
            var original = lease.font();
            lease.close();
            clock.addAndGet(Duration.ofHours(23).toNanos());
            cache.cleanUp();
            assertEquals(1, cache.stats().entries());
            try (var hit = cache.acquire(face.data())) { assertSame(original, hit.font()); }
            clock.addAndGet(Duration.ofDays(1).toNanos());
            cache.cleanUp();
            assertEquals(0, cache.stats().entries());
            assertEquals(1, closes.get());
            assertEquals(1, cache.stats().expirations());
            try (var replacement = cache.acquire(face.data())) { assertNotSame(original, replacement.font()); }
        }
    }

    @Test
    void idleProgramsExpireWithoutAnotherOperationAndReaperDoesNotInheritRequestContext() throws Exception {
        var face = font("LiberationSans-Regular.ttf");
        var released = new CountDownLatch(1);
        var inherited = new AtomicReference<String>();
        var daemon = new AtomicBoolean();
        var contextLoader = new AtomicReference<ClassLoader>();
        var request = new InheritableThreadLocal<String>();
        request.set("request-context");
        try (var cache = new PdfFontCache(PdfFontCache.builder().idleTimeout(Duration.ofMillis(20)),
                System::nanoTime, PdfFontPrograms::parse, font -> {
                    inherited.set(request.get());
                    daemon.set(Thread.currentThread().isDaemon());
                    contextLoader.set(Thread.currentThread().getContextClassLoader());
                    font.close();
                    released.countDown();
                })) {
            try (var lease = cache.acquire(face.data())) { assertTrue(lease.font().getNumberOfGlyphs() > 0); }
            assertTrue(released.await(5, TimeUnit.SECONDS), "idle cleanup must run without another cache operation");
            assertEquals(0, cache.stats().entries(), "stats are passive and cannot cause this eviction");
            assertNull(inherited.get());
            assertNull(contextLoader.get());
            assertTrue(daemon.get());
        } finally { request.remove(); }
    }

    @Test
    void simultaneousExportsBorrowDifferentProgramsAndNeverRelaxThePoolCapacity() throws Exception {
        var face = font("LiberationSans-Regular.ttf");
        var entered = new CountDownLatch(2);
        var proceed = new CountDownLatch(1);
        try (var cache = new PdfFontCache(PdfFontCache.builder().maxEntries(1), System::nanoTime, data -> {
                entered.countDown();
                try {
                    if (!proceed.await(5, TimeUnit.SECONDS)) { throw new IOException("bounded test parser timed out"); }
                } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
                return PdfFontPrograms.parse(data);
            }, org.apache.fontbox.ttf.TrueTypeFont::close);
                var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> cache.acquire(face.data()));
            var second = threads.submit(() -> cache.acquire(face.data()));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(1, cache.stats().entries());
                assertEquals(1, cache.stats().borrowedEntries());
            } finally { proceed.countDown(); }
            try (var left = first.get(5, TimeUnit.SECONDS); var right = second.get(5, TimeUnit.SECONDS)) {
                assertNotSame(left.font(), right.font());
                assertEquals(1, cache.stats().bypasses());
                assertEquals(2, cache.stats().parses());
            }
        } finally { proceed.countDown(); }
    }

    @Test
    void clearAndCloseRetireBorrowedProgramsOnlyAfterTheirLastUse() throws IOException {
        var face = font("LiberationSans-Regular.ttf");
        var closes = new AtomicInteger();
        var cache = new PdfFontCache(PdfFontCache.builder(), System::nanoTime, PdfFontPrograms::parse,
                font -> { font.close(); closes.incrementAndGet(); });
        try {
            var old = cache.acquire(face.data());
            cache.clear();
            assertEquals(0, closes.get());
            assertTrue(old.font().getNumberOfGlyphs() > 0);
            old.close();
            assertEquals(1, closes.get());
            var active = cache.acquire(face.data());
            cache.close();
            assertEquals(1, closes.get());
            assertTrue(active.font().getNumberOfGlyphs() > 0);
            assertThrows(IllegalStateException.class, () -> cache.acquire(face.data()));
            active.close();
            assertEquals(2, closes.get());
            assertEquals(0, cache.stats().entries());
        } finally { cache.close(); }
    }

    @Test
    void clearAndCloseDuringAnInFlightParseRetireItsReservationWithoutInterruptingTheBorrower() throws Exception {
        var face = font("LiberationSans-Regular.ttf");
        for (boolean close : List.of(false, true)) {
            var entered = new CountDownLatch(1);
            var proceed = new CountDownLatch(1);
            var closes = new AtomicInteger();
            var cache = new PdfFontCache(PdfFontCache.builder(), System::nanoTime, data -> {
                    entered.countDown();
                    try {
                        if (!proceed.await(5, TimeUnit.SECONDS)) { throw new IOException("bounded test parser timed out"); }
                    } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
                    return PdfFontPrograms.parse(data);
                }, font -> { font.close(); closes.incrementAndGet(); });
            try (var threads = Executors.newSingleThreadExecutor()) {
                var pending = threads.submit(() -> cache.acquire(face.data()));
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    assertEquals(1, cache.stats().borrowedEntries());
                    if (close) { cache.close(); } else { cache.clear(); }
                    assertEquals(0, closes.get(), "an unfinished parse has no program to close yet");
                } finally { proceed.countDown(); }
                try (var lease = pending.get(5, TimeUnit.SECONDS)) {
                    assertTrue(lease.font().getNumberOfGlyphs() > 0);
                    assertEquals(0, closes.get());
                }
                assertEquals(1, closes.get());
                assertEquals(0, cache.stats().entries());
                assertEquals(0, cache.stats().sourceBytes());
            } finally { proceed.countDown(); cache.close(); }
        }
    }

    @Test
    void uncheckedCleanupFailureDoesNotPreventDisposingTheRemainingEntries() throws IOException {
        var first = font("LiberationSans-Regular.ttf");
        var second = font("LiberationSerif-Regular.ttf");
        var attempts = new AtomicInteger();
        var cache = new PdfFontCache(PdfFontCache.builder(), System::nanoTime, PdfFontPrograms::parse,
                font -> {
                    font.close();
                    if (attempts.incrementAndGet() == 1) { throw new IllegalStateException("expected cleanup failure"); }
                });
        try {
            cache.acquire(first.data()).close();
            cache.acquire(second.data()).close();
            assertThrows(IllegalStateException.class, cache::close);
            assertEquals(2, attempts.get());
            assertEquals(0, cache.stats().entries());
            assertTrue(cache.stats().closed());
        } finally { cache.close(); }
    }

    @Test
    void sourceByteLimitsEvictIdleProgramsAndOversizedProgramsAreNotRetained() throws IOException {
        var sans = font("LiberationSans-Regular.ttf");
        var serif = font("LiberationSerif-Regular.ttf");
        long bound = Math.max(sans.data().byteSize(), serif.data().byteSize());
        try (var cache = PdfFontCache.builder().maxSourceBytes(bound).build()) {
            try (var lease = cache.acquire(sans.data())) {
                assertTrue(lease.font().getNumberOfGlyphs() > 0);
                assertTrue(cache.stats().sourceBytes() <= bound);
            }
            try (var lease = cache.acquire(serif.data())) {
                assertTrue(lease.font().getNumberOfGlyphs() > 0);
                assertTrue(cache.stats().sourceBytes() <= bound);
            }
            assertEquals(1, cache.stats().entries());
            assertEquals(1, cache.stats().evictions());
        }
        try (var cache = PdfFontCache.builder().maxSourceBytes(sans.data().byteSize() - 1L).build()) {
            try (var lease = cache.acquire(sans.data())) { assertTrue(lease.font().getNumberOfGlyphs() > 0); }
            assertEquals(0, cache.stats().entries());
            assertEquals(1, cache.stats().bypasses());
        }
    }

    @Test
    void disabledPoolUsesIndependentProgramsAndRetainsNothing() throws IOException {
        var face = font("LiberationSans-Regular.ttf");
        try (var cache = PdfFontCache.builder().enabled(false).build()) {
            for (int index = 0; index < 2; index++) {
                var output = new ByteArrayOutputStream();
                PdfBackend.builder().fontCache(cache).build().write(document(face, "AB"), output);
                assertEquals("AB", text(output));
            }
            assertEquals(2, cache.stats().parses());
            assertEquals(2, cache.stats().bypasses());
            assertEquals(0, cache.stats().entries());
        }
    }

    @Test
    void failedAndAbandonedExportsDiscardTheirProgramsBeforeTheNextExport() throws IOException {
        var face = font("LiberationSans-Regular.ttf");
        try (var cache = PdfFontCache.builder().build()) {
            var backend = PdfBackend.builder().fontCache(cache).build();
            var failed = new OutputStream() {
                @Override public void write(int value) throws IOException { throw new IOException("expected failure"); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException { throw new IOException("expected failure"); }
            };
            assertThrows(IOException.class, () -> backend.write(document(face, "AB"), failed));
            assertEquals(0, cache.stats().entries());
            try (var writer = backend.open(DocumentMetadata.EMPTY, new ByteArrayOutputStream())) {
                writer.accept(page(face, "AB"));
            }
            assertEquals(0, cache.stats().entries());
            var output = new ByteArrayOutputStream();
            backend.write(document(face, "CD"), output);
            assertEquals("CD", text(output));
            assertEquals(3, cache.stats().parses());
        }
    }

    @Test
    void cancellationAfterParsingClosesTheUnpublishedProgramAndReleasesItsReservation() throws IOException {
        var face = font("LiberationSans-Regular.ttf");
        var cancelled = new AtomicBoolean();
        var closes = new AtomicInteger();
        try (var cache = new PdfFontCache(PdfFontCache.builder(), System::nanoTime, data -> {
                var parsed = PdfFontPrograms.parse(data);
                cancelled.set(true);
                return parsed;
            }, font -> { font.close(); closes.incrementAndGet(); })) {
            var options = ExecutionOptions.builder().cancelled(cancelled::get).build();
            assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options, () -> cache.acquire(face.data())));
            assertEquals(1, closes.get());
            assertEquals(0, cache.stats().entries());
            assertEquals(0, cache.stats().sourceBytes());
        }
    }

    @Test
    void asynchronousCloseFailureIsReportedOnceAndDoesNotPermanentlyDisableTheCache() throws Exception {
        var face = font("LiberationSans-Regular.ttf");
        var attempted = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        try (var cache = new PdfFontCache(PdfFontCache.builder().idleTimeout(Duration.ofMillis(20)),
                System::nanoTime, PdfFontPrograms::parse, font -> {
                    font.close();
                    if (first.getAndSet(false)) { attempted.countDown(); throw new IOException("expected cleanup failure"); }
                })) {
            cache.acquire(face.data()).close();
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertEquals(1, cache.stats().cleanupFailures());
            assertThrows(IOException.class, () -> cache.acquire(face.data()));
            try (var restored = cache.acquire(face.data())) { assertTrue(restored.font().getNumberOfGlyphs() > 0); }
            assertEquals(2, cache.stats().parses());
        }
    }

    @Test
    void invalidBudgetsAndDurationsAreRejectedBeforeAnyLoad() {
        assertThrows(IllegalArgumentException.class, () -> PdfFontCache.builder().maxEntries(0));
        assertThrows(IllegalArgumentException.class, () -> PdfFontCache.builder().maxSourceBytes(0));
        assertThrows(IllegalArgumentException.class, () -> PdfFontCache.builder().idleTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> PdfFontCache.builder().idleTimeout(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> PdfFontCache.builder().idleTimeout(Duration.ofSeconds(Long.MAX_VALUE)));
    }

    private static String text(ByteArrayOutputStream output) throws IOException {
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            return String.join("\n", new PDFTextStripper().getText(pdf).lines().toList());
        }
    }

    private static DisplayDocument document(FontFace face, String text) {
        return new DisplayDocument(DocumentMetadata.EMPTY, List.of(page(face, text)));
    }

    private static DisplayPage page(FontFace face, String text) {
        var glyphs = new SimpleTextShaper().shape(text, List.of(face), 18)
                .glyphs().stream().map(glyph -> glyph.glyph()).toList();
        var command = new DrawCommand.GlyphText(new GlyphRun(face.data(), 18, glyphs), new Point(20, 35), RgbColor.BLACK);
        return new DisplayPage(new PageSize(200, 100), List.of(command));
    }

    private static FontFace font(String resource) throws IOException {
        try (var input = PdfFontCacheTest.class.getResourceAsStream("/fonts/" + resource)) {
            return TrueTypeFontLoader.load(input);
        }
    }
}
