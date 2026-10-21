// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/** Synchronous, scoped control propagation through nested components. Never starts threads; scope is always restored.
 * Custom code is cooperative: cancellation/deadline is observed at the next checkpoint after it returns.
 */
public final class ExecutionContext {
    private static final ThreadLocal<ExecutionContext> CURRENT = new ThreadLocal<>();
    private final ExecutionOptions options;
    private final long started = System.nanoTime();
    private final long timeoutNanos;
    private ExecutionOptions.Progress context = new ExecutionOptions.Progress("START", "", -1, 0, 0);

    private ExecutionContext(ExecutionOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        timeoutNanos = options.timeout().toNanos();
    }

    private ExecutionContext parent;

    @FunctionalInterface
    public interface Operation<T> { T run() throws IOException; }

    /** Creates a caller-thread scope. Nested scopes also observe the enclosing cancellation/deadline. */
    public static <T> T execute(ExecutionOptions options, Operation<T> operation) throws IOException {
        Objects.requireNonNull(operation, "operation");
        var previous = CURRENT.get();
        var current = new ExecutionContext(options);
        current.parent = previous;
        CURRENT.set(current);
        try {
            checkpoint();
            T value = operation.run();
            checkpoint();
            return value;
        } finally {
            if (previous == null) { CURRENT.remove(); } else { CURRENT.set(previous); }
        }
    }

    /** Cheap checkpoint; a thread interrupt is treated as cancellation and its flag is preserved. */
    public static void checkpoint() {
        for (var current = CURRENT.get(); current != null; current = current.parent) {
            if (Thread.currentThread().isInterrupted() || current.options.cancelled().getAsBoolean()) {
                throw new ExecutionException(ExecutionException.Code.CANCELLED, current.context);
            }
            if (current.timeoutNanos > 0 && System.nanoTime() - current.started >= current.timeoutNanos) {
                throw new ExecutionException(ExecutionException.Code.TIMEOUT, current.context);
            }
        }
    }

    /** Updates failure context and reports a stage boundary, then rechecks cancellation requested by the callback. */
    public static void progress(String stage, String blockId, int page, long completed, long total) {
        checkpoint();
        var current = CURRENT.get();
        if (current != null) {
            current.context = new ExecutionOptions.Progress(stage, blockId, page, completed, total);
            current.options.progress().accept(current.context);
            checkpoint();
        }
    }

    /** Borrowed, nonclosing output with the current byte budget. Checks large writes before forwarding any bytes. */
    public static OutputStream output(OutputStream target) {
        Objects.requireNonNull(target, "target");
        var context = CURRENT.get();
        long limit = context == null ? Long.MAX_VALUE : context.options.maxOutputBytes();
        return new OutputStream() {
            private long count;
            private void reserve(int length) {
                checkpoint();
                if (length > limit - count) {
                    throw new ExecutionException(ExecutionException.Code.OUTPUT_LIMIT,
                            context == null ? new ExecutionOptions.Progress("OUTPUT", "", -1, 0, 0) : context.context);
                }
                count += length;
            }
            @Override public void write(int value) throws IOException { reserve(1); target.write(value); }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, bytes.length);
                reserve(length);
                target.write(bytes, offset, length);
            }
            @Override public void flush() throws IOException { checkpoint(); target.flush(); }
            @Override public void close() { /* Caller retains ownership. */ }
        };
    }
}
