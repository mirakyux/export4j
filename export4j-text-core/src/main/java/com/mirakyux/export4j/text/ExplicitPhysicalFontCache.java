// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import com.mirakyux.export4j.font.FontData;
import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Bounded, idle-expiring base fonts; returned fonts remain usable after cache eviction. */
final class ExplicitPhysicalFontCache implements AutoCloseable {
    private record Entry(Font font, int sourceBytes, long lastAccess) { }

    private final int maxEntries;
    private final long maxSourceBytes;
    private final long idleNanos;
    private final LongSupplier clock;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private long sourceBytes;
    private ScheduledThreadPoolExecutor cleaner;
    private ScheduledFuture<?> cleanup;
    private boolean closed;

    ExplicitPhysicalFontCache(int maxEntries, long maxSourceBytes) {
        this(maxEntries, maxSourceBytes, Duration.ofMinutes(5), System::nanoTime);
    }

    ExplicitPhysicalFontCache(int maxEntries, long maxSourceBytes, Duration idleTimeout, LongSupplier clock) {
        if (maxEntries < 1 || maxSourceBytes < 1) {
            throw new IllegalArgumentException("physical font cache limits must be positive");
        }
        long nanos;
        try { nanos = Objects.requireNonNull(idleTimeout, "idleTimeout").toNanos(); }
        catch (ArithmeticException failure) { throw new IllegalArgumentException("idle timeout is too large", failure); }
        if (nanos <= 0) { throw new IllegalArgumentException("idle timeout must be positive"); }
        this.maxEntries = maxEntries;
        this.maxSourceBytes = maxSourceBytes;
        this.idleNanos = nanos;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // Loading under the same lock prevents concurrent paragraphs from parsing one program repeatedly.
    synchronized Font get(FontData data) {
        if (closed) { throw new IllegalStateException("physical font cache is closed"); }
        expire(clock.getAsLong());
        String id = data.id();
        Entry cached = entries.get(id);
        if (cached != null) {
            entries.put(id, new Entry(cached.font(), cached.sourceBytes(), clock.getAsLong()));
            return cached.font();
        }

        Font loaded = load(data);
        int weight = data.byteSize();
        if (weight > maxSourceBytes) { return loaded; }

        while (entries.size() >= maxEntries || sourceBytes > maxSourceBytes - weight) {
            var oldest = entries.entrySet().iterator();
            sourceBytes -= oldest.next().getValue().sourceBytes();
            oldest.remove();
        }
        entries.put(id, new Entry(loaded, weight, clock.getAsLong()));
        sourceBytes += weight;
        schedule();
        return loaded;
    }

    /** Passive observation for lifecycle verification; does not perform cleanup. */
    synchronized int entryCount() { return entries.size(); }

    synchronized void cleanUp() { expire(clock.getAsLong()); }

    private void expire(long now) {
        var iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (now - entry.lastAccess() >= idleNanos) {
                sourceBytes -= entry.sourceBytes();
                iterator.remove();
            }
        }
        if (entries.isEmpty()) { stopCleaner(); }
    }

    private void schedule() {
        if (cleanup != null || entries.isEmpty()) { return; }
        if (cleaner == null) {
            cleaner = new ScheduledThreadPoolExecutor(1, task -> {
                var thread = new Thread(null, task, "export4j-physical-font-cache", 0, false);
                thread.setDaemon(true);
                thread.setContextClassLoader(null);
                return thread;
            });
            cleaner.setRemoveOnCancelPolicy(true);
            cleaner.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        }
        long remaining = idleNanos - (clock.getAsLong() - entries.firstEntry().getValue().lastAccess());
        var owner = cleaner;
        cleanup = owner.schedule(() -> cleanIdle(owner), Math.max(1, remaining), TimeUnit.NANOSECONDS);
    }

    private synchronized void cleanIdle(ScheduledThreadPoolExecutor owner) {
        if (owner != cleaner) { return; }
        cleanup = null;
        expire(clock.getAsLong());
        schedule();
    }

    private void stopCleaner() {
        if (cleanup != null) { cleanup.cancel(false); cleanup = null; }
        if (cleaner != null) { cleaner.shutdown(); cleaner = null; }
    }

    @Override public synchronized void close() {
        closed = true;
        entries.clear();
        sourceBytes = 0;
        stopCleaner();
    }

    private static Font load(FontData data) {
        // FontData owns the immutable program; the JDK only needs a private read cursor.
        ByteBuffer bytes = data.readOnlyBuffer();
        try (var input = new InputStream() {
            @Override public int read() {
                return bytes.hasRemaining() ? Byte.toUnsignedInt(bytes.get()) : -1;
            }

            @Override public int read(byte[] target, int offset, int length) {
                Objects.checkFromIndexSize(offset, length, target.length);
                if (length == 0) { return 0; }
                if (!bytes.hasRemaining()) { return -1; }
                int count = Math.min(length, bytes.remaining());
                bytes.get(target, offset, count);
                return count;
            }
        }) {
            return Font.createFont(Font.TRUETYPE_FONT, input);
        } catch (IOException | FontFormatException failure) {
            throw new IllegalArgumentException("explicit physical font cannot be loaded", failure);
        }
    }
}
