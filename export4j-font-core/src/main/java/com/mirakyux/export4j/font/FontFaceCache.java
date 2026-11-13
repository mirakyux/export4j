// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Thread-safe, content-addressed cache of immutable static font faces. Encoded source bytes and
 * entry count are bounded; parsed mappings and other runtime storage can exceed the source weight.
 * Idle expiration removes the cache's references, leaving caller-owned faces fully usable.
 * Cleanup runs automatically at the next scheduled idle deadline, subject to JVM scheduling;
 * it needs no later load, does not force garbage collection, and stops when the cache is empty.
 * Loading, clearing and closing are serialized. A closed cache rejects new loads.
 */
public final class FontFaceCache implements AutoCloseable {
    private final int maxEntries;
    private final long maxSourceBytes;
    private final long idleNanos;
    private final boolean enabled;
    private final LongSupplier ticker;
    private final Supplier<ScheduledThreadPoolExecutor> executorFactory;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private long sourceBytes;
    private long hits;
    private long misses;
    private long loads;
    private long expirations;
    private long evictions;
    private long cleanupGeneration;
    private ScheduledThreadPoolExecutor executor;
    private ScheduledFuture<?> cleanupTask;
    private boolean closed;

    private FontFaceCache(Builder builder) {
        this(builder, System::nanoTime, FontFaceCache::newExecutor);
    }

    FontFaceCache(Builder builder, LongSupplier ticker,
            Supplier<ScheduledThreadPoolExecutor> executorFactory) {
        maxEntries = builder.maxEntries;
        maxSourceBytes = builder.maxSourceBytes;
        idleNanos = builder.idleNanos;
        enabled = builder.enabled;
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.executorFactory = Objects.requireNonNull(executorFactory, "executorFactory");
    }

    /** Creates task-confined configuration with defaults of 16 entries, 64 MiB and five idle minutes. */
    public static Builder builder() { return new Builder(); }

    /** Mutable configuration; build snapshots all values and retains no builder reference. */
    public static final class Builder {
        private int maxEntries = 16;
        private long maxSourceBytes = 64L * 1024 * 1024;
        private long idleNanos = Duration.ofMinutes(5).toNanos();
        private boolean enabled = true;

        private Builder() { }

        /** Maximum retained faces; must be positive even when retention is disabled. */
        public Builder maxEntries(int value) {
            if (value <= 0) { throw new IllegalArgumentException("maxEntries must be positive"); }
            maxEntries = value;
            return this;
        }

        /** Maximum retained encoded source bytes, not a bound on total heap use; must be positive. */
        public Builder maxSourceBytes(long value) {
            if (value <= 0) { throw new IllegalArgumentException("maxSourceBytes must be positive"); }
            maxSourceBytes = value;
            return this;
        }

        /** Idle period after each successful use; must be positive and fit in nanoseconds. */
        public Builder idleTimeout(Duration value) {
            Objects.requireNonNull(value, "idleTimeout");
            long nanos;
            try { nanos = value.toNanos(); }
            catch (ArithmeticException failure) {
                throw new IllegalArgumentException("idleTimeout must fit in nanoseconds", failure);
            }
            if (nanos <= 0) { throw new IllegalArgumentException("idleTimeout must be positive"); }
            idleNanos = nanos;
            return this;
        }

        /** False parses each input without retaining a face or starting a cleanup thread. */
        public Builder enabled(boolean value) { enabled = value; return this; }

        public FontFaceCache build() { return new FontFaceCache(this); }
    }

    /** Loads caller-owned bytes under the default loader limit, taking a private snapshot before hashing. */
    public FontFace load(byte[] bytes) throws IOException {
        return load(bytes, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
    }

    /**
     * Loads under the per-call encoded byte limit, including on a cache hit. Caller bytes are never retained.
     * An oversized cache entry is returned normally without retention. Failed parses are never cached.
     */
    public FontFace load(byte[] bytes, int maxBytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        requireLimit(maxBytes);
        if (bytes.length > maxBytes) {
            throw new FontLoadException(FontLoadException.Code.BUDGET_EXCEEDED, "font byte budget exceeded");
        }
        byte[] snapshot = bytes.clone();
        return loadSnapshot(snapshot);
    }

    /** Reads a borrowed stream under the default loader limit and never closes it. */
    public FontFace load(InputStream input) throws IOException {
        return load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
    }

    /** Reads at most maxBytes+1 bytes from a borrowed stream, including on a cache hit; never closes it. */
    public FontFace load(InputStream input, int maxBytes) throws IOException {
        Objects.requireNonNull(input, "input");
        requireLimit(maxBytes);
        requireOpen();
        // readNBytes can be overridden, so load(byte[]) must still snapshot its result.
        return load(input.readNBytes(maxBytes + 1), maxBytes);
    }

    /** Reads an explicit file under the default loader limit, closing only the stream opened here. */
    public FontFace load(Path source) throws IOException {
        return load(source, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
    }

    /** Reads and hashes the current file contents on every call; cached paths never hide file replacement. */
    public FontFace load(Path source, int maxBytes) throws IOException {
        Objects.requireNonNull(source, "source");
        requireLimit(maxBytes);
        requireOpen();
        try (var input = Files.newInputStream(source)) { return load(input, maxBytes); }
    }

    private synchronized FontFace loadSnapshot(byte[] snapshot) throws IOException {
        requireOpen();
        expire(ticker.getAsLong());
        String id = enabled ? identifier(snapshot) : null;
        Entry cached = enabled ? entries.get(id) : null;
        if (cached != null) {
            cached.lastAccess = ticker.getAsLong();
            hits++;
            return cached.face;
        }
        misses++;
        FontFace loaded = TrueTypeFontLoader.loadSnapshotUncached(snapshot);
        loads++;
        if (!enabled || snapshot.length > maxSourceBytes) { return loaded; }
        while (entries.size() >= maxEntries || sourceBytes > maxSourceBytes - snapshot.length) {
            var iterator = entries.entrySet().iterator();
            sourceBytes -= iterator.next().getValue().sourceBytes;
            iterator.remove();
            evictions++;
        }
        entries.put(id, new Entry(loaded, snapshot.length, ticker.getAsLong()));
        sourceBytes += snapshot.length;
        scheduleCleanup();
        return loaded;
    }

    /**
     * Returns current retained source weight and cumulative counters. Reading does not expire entries;
     * this also permits observation of autonomous cleanup. Clear and close preserve the counters.
     */
    public synchronized Stats stats() {
        return new Stats(entries.size(), sourceBytes, hits, misses, loads, expirations, evictions);
    }

    /** Immutable occupancy and counters; loads counts successful parses, including disabled retention. */
    public record Stats(int entries, long sourceBytes, long hits, long misses, long loads,
            long expirations, long evictions) { }

    /** Immediately drops retained faces and stops pending cleanup, without invalidating caller-held faces. */
    public synchronized void clear() {
        entries.clear();
        sourceBytes = 0;
        stopCleanup();
    }

    /** Idempotently drops retained references, stops cleanup and rejects future loads. */
    @Override public synchronized void close() {
        closed = true;
        clear();
    }

    private synchronized void requireOpen() {
        if (closed) { throw new IllegalStateException("font face cache is closed"); }
    }

    private void expire(long now) {
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (now - entry.lastAccess >= idleNanos) {
                sourceBytes -= entry.sourceBytes;
                iterator.remove();
                expirations++;
            }
        }
        if (entries.isEmpty()) { stopCleanup(); }
    }

    private void scheduleCleanup() {
        if (entries.isEmpty() || cleanupTask != null) { return; }
        long now = ticker.getAsLong();
        long remaining = idleNanos;
        for (var entry : entries.values()) {
            remaining = Math.min(remaining, Math.max(1, idleNanos - (now - entry.lastAccess)));
        }
        if (executor == null) { executor = executorFactory.get(); }
        long generation = ++cleanupGeneration;
        cleanupTask = executor.schedule(() -> cleanup(generation), remaining, TimeUnit.NANOSECONDS);
    }

    private synchronized void cleanup(long generation) {
        if (generation != cleanupGeneration || closed) { return; }
        cleanupTask = null;
        expire(ticker.getAsLong());
        scheduleCleanup();
    }

    private void stopCleanup() {
        cleanupGeneration++;
        if (cleanupTask != null) { cleanupTask.cancel(false); cleanupTask = null; }
        if (executor != null) { executor.shutdown(); executor = null; }
    }

    private static ScheduledThreadPoolExecutor newExecutor() {
        var result = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(null, task, "export4j-font-face-cache", 0, false);
            thread.setDaemon(true);
            thread.setContextClassLoader(null);
            return thread;
        });
        result.setRemoveOnCancelPolicy(true);
        result.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return result;
    }

    private static String identifier(byte[] snapshot) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(snapshot));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", failure);
        }
    }

    private static void requireLimit(int maxBytes) {
        if (maxBytes <= 0 || maxBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxBytes must be positive and less than Integer.MAX_VALUE");
        }
    }

    private static final class Entry {
        private final FontFace face;
        private final int sourceBytes;
        private long lastAccess;

        private Entry(FontFace face, int sourceBytes, long lastAccess) {
            this.face = face;
            this.sourceBytes = sourceBytes;
            this.lastAccess = lastAccess;
        }
    }
}
