// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable bounded encoded snapshot created by {@link PdfAdapter#input(byte[], String)}.
 * Retains the explicit password for its lifetime; neither password nor encoded data is exposed.
 * It owns no open resource and can be reused concurrently. Parsing occurs at each operation.
 */
public final class PdfInput {
    private final byte[] bytes;
    private final String password;

    PdfInput(byte[] ownedBytes, String password) {
        bytes = ownedBytes;
        this.password = Objects.requireNonNull(password, "password");
    }

    byte[] bytes() { return bytes; }
    String password() { return password; }
    public int byteSize() { return bytes.length; }

    /** Selects every page in original order. The page count is checked when the input is parsed. */
    public PdfSelection allPages() { return new PdfSelection(this, List.of(), true); }

    /** Selects zero-based page indices in the supplied order. Repetition deliberately duplicates a page.
     * An empty selection and negative indices are rejected; the parsed document determines upper bounds.
     */
    public PdfSelection pages(int... indices) {
        Objects.requireNonNull(indices, "indices");
        if (indices.length == 0) { throw new IllegalArgumentException("page selection must not be empty"); }
        var values = new ArrayList<Integer>(indices.length);
        for (int index : indices) {
            if (index < 0) { throw new IllegalArgumentException("page index must not be negative"); }
            values.add(index);
        }
        return new PdfSelection(this, values, false);
    }
}
