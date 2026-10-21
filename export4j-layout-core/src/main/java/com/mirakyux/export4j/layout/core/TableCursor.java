// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.Optional;
import com.mirakyux.export4j.layout.TableFragment;

/**
 * Synchronous, layout-local cursor over a table measured once at a fixed width. Dimensions are points.
 * Successful takes preserve complete source lines and unresolved fields; unsuccessful takes consume nothing.
 * Mutable and not thread-safe. Fonts and image resources remain borrowed; this cursor owns no closeable resources.
 */
public final class TableCursor {
    interface State {
        double remainingHeight();
        boolean done();
        Optional<TableFragment> take(double height);
        int rowIndex();
        int fragmentIndex();
    }

    private final State state;

    TableCursor(State state) { this.state = state; }

    /** Height of remaining source rows plus one upcoming header group; zero after completion.
     * Further fragments can require additional repeated headers and padding. */
    public double remainingHeight() { return state.remainingHeight(); }

    /** Whether every body source line or complete row has been consumed. */
    public boolean done() { return state.done(); }

    /**
     * Takes the largest complete-row or explicitly enabled complete-line fragment fitting the given height.
     * Height must be finite, nonnegative and no greater than the preparation viewport. Returns empty when
     * done or when the next source cannot fit; an empty result never changes cursor state or output budgets.
     * Throws {@link DocumentLayoutException} for exhausted output budgets, observing the current execution scope.
     */
    public Optional<TableFragment> take(double availableHeight) { return state.take(availableHeight); }

    int rowIndex() { return state.rowIndex(); }
    int fragmentIndex() { return state.fragmentIndex(); }
}
