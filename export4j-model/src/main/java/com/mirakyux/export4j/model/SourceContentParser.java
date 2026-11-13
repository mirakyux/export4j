// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.List;

/** Explicit synchronous input compilation. Implementations own their parser dependencies; neither
 * parsing nor a Source node is deferred to layout. A parser must reject unsupported input or losses,
 * not silently discard diagnostics. Parser instances may be shared only when their implementation
 * supports it; each render opens its own task-confined session, once per parser identity.
 * Implementations must use explicit resources and must never fetch files or network content implicitly.
 */
@FunctionalInterface
public interface SourceContentParser {
    Session openSession();

    /** A session accumulates parser input, AST/work, table, formula and vector budgets across every
     * call, including loops and sections. It must debit before materializing bounded intermediate
     * state, checkpoint synchronous cancellation, and honor each call's remaining output allowance.
     * Returned blocks use namespace (or namespace + ':' + a local suffix), including descendants.
     * The returned tree is final: its text, links and vector reading text are not interpolated again.
     * close releases session-owned resources on success, failure and cancellation, but not borrowed
     * resources. No document-level objects may be retained after close.
     */
    interface Session extends AutoCloseable {
        List<Block> parse(String namespace, String source, ContentOptions.Resolved options, Remaining remaining);
        @Override default void close() { }
    }

    /** Nonnegative allowance immediately before a source is parsed. A new snapshot does not reset
     * the session's accumulated parser budgets. Counts are template work, blocks, table rows, Unicode
     * code points and remaining nesting depth; implementations may impose stricter local limits.
     */
    record Remaining(int maxVisitedNodes, int maxBlocks, int maxRows, int maxCodePoints, int maxDepth) {
        public Remaining {
            if (maxVisitedNodes < 0 || maxBlocks < 0 || maxRows < 0 || maxCodePoints < 0 || maxDepth < 0) {
                throw new IllegalArgumentException("negative source parsing allowance");
            }
        }
    }
}
