// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Caller-thread adapters for lazy table rows; no adapter counts, caches, materializes or retries the source.
 * Sources are single-pass and task-confined. Factories reopen their input on each export pass;
 * the caller must supply the same stable business snapshot when replay is requested.
 */
public final class TableRowSources {
    private TableRowSources() { }

    /** Requests a new borrowed iterator from rows on each factory open; never closes the iterable or iterator. */
    public static TableRowSource.Factory fromIterable(Iterable<? extends TableRow> rows) {
        return TableRowSources.<TableRow>fromIterable(rows, Function.identity());
    }

    /** Requests a new borrowed iterator only when the factory opens; maps each requested value once.
     * No iteration occurs at factory creation. The caller retains ownership even of closeable iterators.
     * A mapper must return a non-null row, and replay requires stable values across openings.
     */
    public static <T> TableRowSource.Factory fromIterable(Iterable<? extends T> values,
            Function<? super T, ? extends TableRow> mapper) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(mapper, "mapper");
        return () -> fromIterator(values.iterator(), mapper);
    }

    /** Wraps one borrowed iterator without reading or closing it, including an AutoCloseable iterator. */
    public static TableRowSource fromIterator(Iterator<? extends TableRow> rows) {
        return TableRowSources.<TableRow>fromIterator(rows, Function.identity());
    }

    /** Wraps one borrowed iterator and maps values only on next(). No ownership of the iterator transfers.
     * Closing the source prevents further reads but does not close a closeable iterator.
     * The mapper must return a non-null row. A failure propagates and the caller must close/discard this source.
     */
    public static <T> TableRowSource fromIterator(Iterator<? extends T> values,
            Function<? super T, ? extends TableRow> mapper) {
        return new IteratorSource<>(values, mapper, null);
    }

    /** Takes ownership of a non-null stream immediately; the returned source must be closed even after end-of-input. */
    public static TableRowSource fromStream(Stream<? extends TableRow> rows) {
        return TableRowSources.<TableRow>fromStream(rows, Function.identity());
    }

    /** Takes ownership of a non-null stream at entry, including when mapper validation or iterator acquisition fails.
     * A construction failure closes the stream and suppresses a distinct cleanup failure onto the original failure.
     * Values are mapped on next(); the mapper must return a non-null row. The source closes the stream at most once,
     * but does not close it automatically at end-of-input. The caller must close the source on completion or failure.
     */
    public static <T> TableRowSource fromStream(Stream<? extends T> values,
            Function<? super T, ? extends TableRow> mapper) {
        Objects.requireNonNull(values, "values");
        try {
            Objects.requireNonNull(mapper, "mapper");
            return new IteratorSource<>(values.iterator(), mapper, values);
        } catch (RuntimeException | Error failure) {
            try { values.close(); }
            catch (RuntimeException | Error cleanup) { if (cleanup != failure) { failure.addSuppressed(cleanup); } }
            throw failure;
        }
    }

    /** Opens a new owned stream only when the factory opens; each source closes its own stream at most once. */
    public static TableRowSource.Factory fromStreams(Supplier<? extends Stream<? extends TableRow>> rows) {
        return TableRowSources.<TableRow>fromStreams(rows, Function.identity());
    }

    /** Opens a new non-null owned stream for each pass and maps values lazily; no stream is opened at factory creation.
     * A stream returned by the supplier transfers to fromStream immediately and is closed on construction failure.
     * The supplier owns anything it allocates before throwing or before returning a stream. Replay requires the same
     * business snapshot on every open; the adapter neither creates a snapshot nor caches rows.
     */
    public static <T> TableRowSource.Factory fromStreams(Supplier<? extends Stream<? extends T>> values,
            Function<? super T, ? extends TableRow> mapper) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(mapper, "mapper");
        return () -> fromStream(Objects.requireNonNull(values.get(), "stream"), mapper);
    }

    private static final class IteratorSource<T> implements TableRowSource {
        private final Iterator<? extends T> values;
        private final Function<? super T, ? extends TableRow> mapper;
        private final Stream<?> ownedStream;
        private boolean ended;
        private boolean closed;

        private IteratorSource(Iterator<? extends T> values, Function<? super T, ? extends TableRow> mapper,
                Stream<?> ownedStream) {
            this.values = Objects.requireNonNull(values, "iterator");
            this.mapper = Objects.requireNonNull(mapper, "mapper");
            this.ownedStream = ownedStream;
        }

        @Override public Optional<TableRow> next() {
            if (closed) { throw new IllegalStateException("table row source is closed"); }
            ExecutionContext.checkpoint();
            if (ended) { return Optional.empty(); }
            boolean available = values.hasNext();
            ExecutionContext.checkpoint();
            if (!available) { ended = true; return Optional.empty(); }
            T value = values.next();
            ExecutionContext.checkpoint();
            TableRow row = Objects.requireNonNull(mapper.apply(value), "mapped row");
            ExecutionContext.checkpoint();
            return Optional.of(row);
        }

        @Override public void close() {
            if (!closed) {
                closed = true;
                if (ownedStream != null) { ownedStream.close(); }
            }
        }
    }
}
