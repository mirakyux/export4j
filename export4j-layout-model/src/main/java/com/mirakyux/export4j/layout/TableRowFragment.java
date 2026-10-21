// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout;

import java.util.List;

/** Original zero-based anchor-row identity and header/repetition flags on a single page.
 * Vertically merged cells may cover later physical rows. Fully covered rows have no separate fragment;
 * their geometry is included in the anchor bounds and enclosing table height, without repeated content.
 */
public record TableRowFragment(int rowIndex, boolean header, boolean repeatedHeader, List<CellFragment> cells, boolean continuedRow) {
    public TableRowFragment {
        cells = List.copyOf(cells);
        if (rowIndex < 0 || cells.isEmpty() || repeatedHeader && !header) {
            throw new IllegalArgumentException("invalid table row fragment");
        }
    }

    public TableRowFragment(int rowIndex, boolean header, boolean repeatedHeader, List<CellFragment> cells) {
        this(rowIndex, header, repeatedHeader, cells, false);
    }
}
