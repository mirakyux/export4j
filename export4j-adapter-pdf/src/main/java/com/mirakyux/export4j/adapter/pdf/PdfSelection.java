// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import java.util.List;

/** Immutable ordered selection, created with {@link PdfInput#pages(int...)} or {@link PdfInput#allPages()}.
 * Its indices use zero-based physical pages. It owns no resource and can be reused concurrently.
 */
public final class PdfSelection {
    private final PdfInput input;
    private final List<Integer> indices;
    private final boolean allPages;

    PdfSelection(PdfInput input, List<Integer> indices, boolean allPages) {
        this.input = input;
        this.indices = List.copyOf(indices);
        this.allPages = allPages;
    }

    public PdfInput input() { return input; }
    public List<Integer> indices() { return indices; }
    public boolean includesAllPages() { return allPages; }
}
