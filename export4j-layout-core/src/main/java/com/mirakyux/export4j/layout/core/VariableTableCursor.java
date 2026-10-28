// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.Optional;
import com.mirakyux.export4j.layout.TableFragment;

/** Layout-local table continuation at explicit changing widths, in points. Original source and accepted
 * line widths are retained; only remaining content rewraps. Mutable, not thread-safe, owns no resources.
 * Repeated measurement is bounded and observes the current execution scope. */
public final class VariableTableCursor {
    interface State {
        void width(double value);
        double remainingHeight();
        boolean done();
        Optional<TableFragment> take(double height);
    }
    private final State state;
    VariableTableCursor(State state) { this.state = state; }
    /** Selects a positive width within the preparation viewport. Does not consume source or output budgets. */
    public void width(double value) { state.width(value); }
    /** Remaining source height at the selected width, including the next repeated headers. */
    public double remainingHeight() { return state.remainingHeight(); }
    public boolean done() { return state.done(); }
    /** Takes complete rows or explicitly enabled complete lines; an empty result consumes no source.
     * Height is finite, nonnegative and within the preparation viewport. */
    public Optional<TableFragment> take(double height) { return state.take(height); }
}
