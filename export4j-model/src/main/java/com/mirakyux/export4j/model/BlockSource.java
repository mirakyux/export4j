// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.io.IOException;
import java.util.Optional;

/** Caller-thread, single-pass source of immutable document blocks. No size, rewind or background I/O.
 * An empty result ends the source permanently; next() and Factory.open() must never return null.
 * Instances are task-confined. A factory transfers ownership of its opened source to the export;
 * borrowed connections or other resources remain the caller's responsibility. Layout may read ahead
 * through a necessary keep-with-next chain, subject to the document's finite layout budgets.
 */
@FunctionalInterface
public interface BlockSource extends AutoCloseable {
    /** Returns the next immutable block, or empty at end; failures are not implicitly retried. */
    Optional<Block> next() throws IOException;

    /** Releases only resources owned by this source. The default source owns no resources. */
    @Override default void close() throws IOException { }

    /** Opens a new owned source when an export begins, synchronously on the calling thread.
     * A replaying export requires each opening to expose the same immutable snapshot.
     */
    @FunctionalInterface
    interface Factory {
        BlockSource open() throws IOException;
    }
}
