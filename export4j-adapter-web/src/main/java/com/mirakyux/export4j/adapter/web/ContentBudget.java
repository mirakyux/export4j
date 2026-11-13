// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.SourceContentParser;

/** Session-confined accounting. Parser input/work and the resulting AST have separate budgets;
 * source characters are not charged as hypothetical syntax nodes. Not a JVM heap sandbox. */
final class ContentBudget {
    final ImportLimits limits;
    final int maxFormulas;
    final int maxFormulaUtf16;
    final int maxSegments;
    private long input, work, text, slots, nodes;
    private int formulas, segments, blocks;

    ContentBudget(ImportLimits limits, int maxFormulas, int maxFormulaUtf16, int maxSegments) {
        this.limits = limits; this.maxFormulas = maxFormulas;
        this.maxFormulaUtf16 = maxFormulaUtf16; this.maxSegments = maxSegments;
    }
    void input(int length) { input = add(input, length, limits.maxInputUtf16(), "content input budget exceeded"); }
    void work(long count) { ExecutionContext.checkpoint(); work = add(work, count, limits.maxWork(), "content work budget exceeded"); }
    void text(int count) { text = add(text, count, limits.maxTextUtf16(), "content text budget exceeded"); }
    void slots(long count) { slots = add(slots, count, limits.maxTableSlots(), "content table slot budget exceeded"); }
    void block() { blocks = (int) add(blocks, 1, limits.maxNodes(), "content block budget exceeded"); }
    int remainingFormulas() { return maxFormulas - formulas; }
    int remainingSegments() { return maxSegments - segments; }
    int remainingWork() { return (int) Math.min(Integer.MAX_VALUE, limits.maxWork() - work); }
    void formulas(int count) { formulas = (int) add(formulas, count, maxFormulas, "content formula count budget exceeded"); }
    void segments(int count) { segments = (int) add(segments, count, maxSegments, "content vector segment budget exceeded"); }

    Reservation reserve(int sourceLength, SourceContentParser.Remaining remaining) {
        ExecutionContext.checkpoint();
        // Bound the parser's source before entry, then charge actual nodes while traversing its
        // result. A long literal paragraph can have three AST nodes; 4*N is not its node count.
        if (sourceLength < 0 || sourceLength > limits.maxWork() - work) {
            throw new IllegalArgumentException("content parser input work budget exceeded");
        }
        work(sourceLength);
        long allowance = Math.min(limits.maxNodes() - nodes, remaining.maxVisitedNodes());
        if (allowance <= 0) { throw new IllegalArgumentException("content AST budget exceeded"); }
        return new Reservation(allowance);
    }
    final class Reservation implements AutoCloseable {
        private final long allowance;
        private long visited;
        private boolean closed;
        Reservation(long allowance) { this.allowance = allowance; }
        void visited(long count) {
            if (closed || count < visited || count > allowance) { throw new IllegalArgumentException("content AST budget exceeded"); }
            nodes = add(nodes, count - visited, limits.maxNodes(), "content AST budget exceeded");
            visited = count;
        }
        void actual(long count) {
            visited(count);
        }
        @Override public void close() { closed = true; }
    }
    private static long add(long used, long count, long limit, String message) {
        if (count < 0 || count > limit - used) { throw new IllegalArgumentException(message); }
        return used + count;
    }
}
