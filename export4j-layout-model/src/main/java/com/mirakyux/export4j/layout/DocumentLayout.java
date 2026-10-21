// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout;

import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.model.DocumentMetadata;

/** Immutable nonempty, sequential page IR. Contains font snapshots, never open resources or backend handles. */
public record DocumentLayout(DocumentMetadata metadata, List<LayoutPage> pages) {
    public DocumentLayout {
        Objects.requireNonNull(metadata, "metadata");
        pages = List.copyOf(pages);
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("layout must contain at least one page");
        }
        for (int index = 0; index < pages.size(); index++) {
            if (pages.get(index).pageIndex() != index) {
                throw new IllegalArgumentException("layout page indices must be sequential from zero");
            }
        }
    }
}
