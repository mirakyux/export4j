// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.apache.fontbox.ttf.TrueTypeFont;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.model.ExecutionContext;

/**
 * Thread-safe bounded pool of parsed horizontal TrueType programs. A program is borrowed exclusively
 * by one export session; PDF fonts, subsets and PDF documents are never shared by this pool.
 * Idle time starts when a borrower returns a program. A daemon removes idle entries even without
 * subsequent exports. Active borrowers survive expiration, {@link #clear()} and {@link #close()}.
 * The byte limit counts encoded source data, not the parsed object's total heap or native memory.
 * Closing or expiring an entry releases this cache's references; reclamation remains JVM-managed.
 * Caller-created caches are caller-owned; backends only borrow them.
 */
public final class PdfFontCache implements AutoCloseable {
    private static final AtomicLong THREAD_IDS = new AtomicLong();
    private static final class Defaults {
        private static final PdfFontCache CACHE = builder().build();
    }

    private final boolean enabled;
    private final int maxEntries;
    private final long maxSourceBytes;
    private final long idleNanos;
    private final LongSupplier clock;
    private final Parser parser;
    private final Closer closer;
    private final List<Entry> entries = new ArrayList<>();
    private ScheduledThreadPoolExecutor executor;
    private ScheduledFuture<?> cleanupTask;
    private long cleanupGeneration;
    private IOException pendingCleanupFailure;
    private long sourceBytes;
    private long hits;
    private long misses;
    private long parses;
    private long bypasses;
    private long evictions;
    private long expirations;
    private long cleanupFailures;
    private boolean closed;

    /** Creates a task-confined configuration builder. */
    public static Builder builder() { return new Builder(); }

    /** Configuration is copied by {@link #build()}; later builder changes do not affect a cache. */
    public static final class Builder {
        private boolean enabled = true;
        private int maxEntries = 8;
        private long maxSourceBytes = 32L * 1024 * 1024;
        private Duration idleTimeout = Duration.ofMinutes(5);
        private Builder() { }

        /** Enables retention; disabled caches still parse each requested program independently. */
        public Builder enabled(boolean value) { enabled = value; return this; }
        /** Bounds pooled programs, including active borrowers and in-progress pooled loads. */
        public Builder maxEntries(int value) {
            if (value <= 0) { throw new IllegalArgumentException("font cache entry limit must be positive"); }
            maxEntries = value; return this;
        }
        /** Bounds pooled encoded source bytes, including active borrowers. */
        public Builder maxSourceBytes(long value) {
            if (value <= 0) { throw new IllegalArgumentException("font cache source byte limit must be positive"); }
            maxSourceBytes = value; return this;
        }
        /** Positive idle retention, measured with a monotonic clock after each exclusive return. */
        public Builder idleTimeout(Duration value) {
            Objects.requireNonNull(value, "idleTimeout");
            try {
                if (value.isNegative() || value.isZero() || value.toNanos() <= 0) {
                    throw new IllegalArgumentException("font cache idle timeout must be positive");
                }
            } catch (ArithmeticException failure) {
                throw new IllegalArgumentException("font cache idle timeout is too large", failure);
            }
            idleTimeout = value; return this;
        }
        public PdfFontCache build() { return new PdfFontCache(this, System::nanoTime, PdfFontPrograms::parse, TrueTypeFont::close); }
    }

    /** Passive cumulative counters and current pooled occupancy. Reading them performs no cleanup.
     * Borrowed entries include pooled loads in progress; independent bypass loads are not resident.
     */
    public record Stats(long hits, long misses, long parses, long bypasses, long evictions,
            long expirations, int entries, int borrowedEntries, long sourceBytes,
            long cleanupFailures, boolean closed) { }

    /** Returns a passive snapshot of this cache. */
    public synchronized Stats stats() {
        return new Stats(hits, misses, parses, bypasses, evictions, expirations, entries.size(),
                (int) entries.stream().filter(entry -> entry.borrowed).count(), sourceBytes,
                cleanupFailures, closed);
    }

    /** Snapshot of the default pool used by backends without an explicit cache. */
    public static Stats defaultStats() { return Defaults.CACHE.stats(); }

    /** Drops idle default programs and retires active default programs on return. */
    public static void clearDefault() throws IOException { Defaults.CACHE.clear(); }

    static PdfFontCache defaultCache() { return Defaults.CACHE; }

    @FunctionalInterface interface Parser { TrueTypeFont parse(FontData data) throws IOException; }
    @FunctionalInterface interface Closer { void close(TrueTypeFont font) throws IOException; }

    PdfFontCache(Builder options, LongSupplier clock, Parser parser, Closer closer) {
        enabled = options.enabled;
        maxEntries = options.maxEntries;
        maxSourceBytes = options.maxSourceBytes;
        idleNanos = options.idleTimeout.toNanos();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.closer = Objects.requireNonNull(closer, "closer");
    }

    Lease acquire(FontData data) throws IOException {
        Objects.requireNonNull(data, "data");
        ExecutionContext.checkpoint();
        Entry reservation = null;
        synchronized (this) {
            requireOpen();
            throwPendingCleanupFailure();
            expireIdle();
            for (var entry : entries) {
                if (!entry.borrowed && !entry.retired && entry.id.equals(data.id())) {
                    entry.borrowed = true;
                    hits++;
                    scheduleCleanup();
                    return new Lease(this, entry, entry.font);
                }
            }
            misses++;
            if (enabled && data.byteSize() <= maxSourceBytes) {
                while (entries.size() >= maxEntries || data.byteSize() > maxSourceBytes - sourceBytes) {
                    Entry oldest = oldestIdle();
                    if (oldest == null) { break; }
                    remove(oldest);
                    evictions++;
                    closer.close(oldest.font);
                }
                if (entries.size() < maxEntries && data.byteSize() <= maxSourceBytes - sourceBytes) {
                    reservation = new Entry(data.id(), data.byteSize());
                    entries.add(reservation);
                    sourceBytes += data.byteSize();
                }
            }
            if (reservation == null) { bypasses++; }
            scheduleCleanup();
        }
        TrueTypeFont font = null;
        try {
            font = parser.parse(data);
            ExecutionContext.checkpoint();
        } catch (IOException | RuntimeException | Error failure) {
            if (font != null) {
                try { closer.close(font); }
                catch (IOException | RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            }
            synchronized (this) {
                if (reservation != null) { remove(reservation); }
                scheduleCleanup();
            }
            throw failure;
        }
        synchronized (this) {
            parses++;
            if (reservation != null) { reservation.font = font; }
        }
        return new Lease(this, reservation, font);
    }

    /** Removes idle entries immediately. Active entries remain usable and are disposed on return.
     * Previously recorded asynchronous cleanup failures are reported once, after this cleanup attempt.
     */
    public synchronized void clear() throws IOException {
        Throwable failure = pendingCleanupFailure;
        pendingCleanupFailure = null;
        for (var entry : List.copyOf(entries)) {
            entry.retired = true;
            if (!entry.borrowed) {
                remove(entry);
                try { closer.close(entry.font); }
                catch (IOException | RuntimeException | Error cause) { failure = append(failure, cause); }
            }
        }
        scheduleCleanup();
        throwFailure(failure);
    }

    /** Prevents new leases, disposes idle programs and retires active ones without interrupting them.
     * Does not close a backend or any caller-owned output stream. Successful repeated close is harmless.
     */
    @Override public synchronized void close() throws IOException {
        if (closed) { throwPendingCleanupFailure(); return; }
        closed = true;
        clear();
        stopReaper();
    }

    private synchronized void release(Lease lease) throws IOException {
        if (lease.returned) { return; }
        lease.returned = true;
        Entry entry = lease.entry;
        if (entry == null || closed || entry.retired || !lease.reusable) {
            if (entry != null) { remove(entry); }
            try { closer.close(lease.font); }
            finally { scheduleCleanup(); }
        } else {
            entry.borrowed = false;
            entry.idleSince = clock.getAsLong();
            scheduleCleanup();
        }
    }

    private void expireIdle() throws IOException {
        long now = clock.getAsLong();
        Throwable failure = null;
        for (var entry : List.copyOf(entries)) {
            if (!entry.borrowed && now - entry.idleSince >= idleNanos) {
                remove(entry);
                expirations++;
                try { closer.close(entry.font); }
                catch (IOException | RuntimeException | Error cause) { failure = append(failure, cause); }
            }
        }
        throwFailure(failure);
    }

    // Test seam for exact monotonic expiry boundaries; production always also schedules cleanup.
    synchronized void cleanUp() throws IOException {
        try { expireIdle(); }
        finally { scheduleCleanup(); }
    }

    private void runCleanup(long generation) {
        synchronized (this) {
            if (generation != cleanupGeneration) { return; }
            cleanupTask = null;
            try { expireIdle(); }
            catch (IOException | RuntimeException | Error failure) {
                cleanupFailures++;
                var reported = new IOException("PDF font cache automatic cleanup failed", failure);
                if (pendingCleanupFailure == null) { pendingCleanupFailure = reported; }
                else { pendingCleanupFailure.addSuppressed(reported); }
            } finally { scheduleCleanup(); }
        }
    }

    private void scheduleCleanup() {
        long generation = ++cleanupGeneration;
        if (cleanupTask != null) { cleanupTask.cancel(false); cleanupTask = null; }
        if (closed || entries.isEmpty()) { stopReaper(); return; }
        long now = clock.getAsLong();
        long delay = Long.MAX_VALUE;
        boolean hasIdle = false;
        for (var entry : entries) {
            if (!entry.borrowed) {
                hasIdle = true;
                delay = Math.min(delay, Math.max(0, idleNanos - (now - entry.idleSince)));
            }
        }
        if (!hasIdle) { return; }
        if (executor == null) {
            executor = new ScheduledThreadPoolExecutor(1, runnable -> {
                var thread = new Thread(null, runnable,
                        "export4j-pdf-font-cache-" + THREAD_IDS.incrementAndGet(), 0, false);
                thread.setDaemon(true);
                thread.setContextClassLoader(null);
                return thread;
            });
            executor.setRemoveOnCancelPolicy(true);
            executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            executor.setKeepAliveTime(1, TimeUnit.SECONDS);
            executor.allowCoreThreadTimeOut(true);
        }
        cleanupTask = executor.schedule(() -> runCleanup(generation), delay, TimeUnit.NANOSECONDS);
    }

    private void stopReaper() {
        cleanupGeneration++;
        if (cleanupTask != null) { cleanupTask.cancel(false); cleanupTask = null; }
        if (executor != null) { executor.shutdown(); executor = null; }
    }

    private Entry oldestIdle() {
        Entry oldest = null;
        long now = clock.getAsLong();
        for (var entry : entries) {
            if (!entry.borrowed && (oldest == null || now - entry.idleSince > now - oldest.idleSince)) {
                oldest = entry;
            }
        }
        return oldest;
    }

    private void remove(Entry entry) {
        if (entries.remove(entry)) { sourceBytes -= entry.sourceBytes; }
    }

    private void requireOpen() {
        if (closed) { throw new IllegalStateException("PDF font cache is closed"); }
    }

    private void throwPendingCleanupFailure() throws IOException {
        if (pendingCleanupFailure != null) {
            IOException failure = pendingCleanupFailure;
            pendingCleanupFailure = null;
            throw failure;
        }
    }

    private static Throwable append(Throwable original, Throwable added) {
        if (original == null) { return added; }
        if (original != added) { original.addSuppressed(added); }
        return original;
    }

    private static void throwFailure(Throwable failure) throws IOException {
        if (failure instanceof IOException cause) { throw cause; }
        if (failure instanceof RuntimeException cause) { throw cause; }
        if (failure instanceof Error cause) { throw cause; }
    }

    private static final class Entry {
        private final String id;
        private final int sourceBytes;
        private TrueTypeFont font;
        private boolean borrowed = true;
        private boolean retired;
        private long idleSince;
        private Entry(String id, int sourceBytes) { this.id = id; this.sourceBytes = sourceBytes; }
    }

    static final class Lease implements AutoCloseable {
        private final PdfFontCache owner;
        private final Entry entry;
        private final TrueTypeFont font;
        private boolean reusable = true;
        private boolean returned;
        private Lease(PdfFontCache owner, Entry entry, TrueTypeFont font) {
            this.owner = owner; this.entry = entry; this.font = font;
        }
        TrueTypeFont font() { return font; }
        boolean reusable() { return reusable; }
        void discard() { reusable = false; }
        @Override public void close() throws IOException { owner.release(this); }
    }
}
