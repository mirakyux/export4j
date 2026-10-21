// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.io.IOException;
import java.util.Optional;

/** Caller-thread, single-pass body rows for a streaming table. No total size, rewind or background I/O.
 * An empty result ends the source permanently; next() and Factory.open() must never return null.
 * Instances are task-confined. A factory transfers ownership of its opened source to the export;
 * borrowed connections or other resources remain the caller's responsibility.
 */
@FunctionalInterface
public interface TableRowSource extends AutoCloseable {
    /** Returns the next immutable row, or empty at end; no implicit retry follows a failure. */
    Optional<TableRow> next() throws IOException;

    /** Releases only resources owned by this source. The default source owns no resources. */
    @Override default void close() throws IOException { }

    /** Opens a new owned source when an export begins, on the caller thread. */
    @FunctionalInterface
    interface Factory {
        TableRowSource open() throws IOException;
    }
}
