// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

import java.io.IOException;

/**
 * Synchronous, mutable output session confined to the thread that opened it.
 * Pages are consumed once, in call order, without backend layout or pagination.
 * After any accept or finish failure, only close is permitted. Successful finish
 * also ends page acceptance. Callers must close the writer even after finish.
 * The borrowed output stream remains open throughout the session's lifetime.
 */
public interface PageWriter extends AutoCloseable {
    /**
     * Consumes one non-null prelaid-out page before returning. Coordinates and dimensions
     * use points with the logical origin at the top left. Implementations do not retain
     * the supplied page after this call, but may retain their own format objects/resources.
     *
     * @param page next page in document order; the caller may release it after this call
     * @throws IOException if page validation or conversion fails
     * @throws IllegalStateException if the session is terminal or accessed from another thread
     */
    void accept(DisplayPage page) throws IOException;

    /**
     * Completes successful output after at least one page. May write buffered format data;
     * a failed finish can leave partial bytes. This method does not close the writer or the
     * borrowed destination and cannot be called twice.
     *
     * @throws IOException if output cannot be completed
     * @throws IllegalStateException if no page was accepted, the session is terminal,
     *         or the caller is not the opening thread
     */
    void finish() throws IOException;

    /**
     * Releases owned backend resources on the opening thread, without implicitly finishing
     * output and without closing the borrowed destination. Idempotent even when an earlier
     * close failed; the session remains terminal. Cleanup must also work after cancellation.
     *
     * @throws IOException if resource cleanup fails
     * @throws IllegalStateException if accessed from another thread
     */
    @Override
    void close() throws IOException;
}
