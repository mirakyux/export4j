// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout;

import java.util.List;
import java.util.Collection;
import java.util.Objects;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.model.PageSize;

/** Immutable page IR, with zero-based index, point-based content bounds and ordered fragments. */
public record LayoutPage(int pageIndex, PageSize size, Rect contentBounds, List<LayoutFragment> elements) {
    public LayoutPage {
        Objects.requireNonNull(size, "size");
        Objects.requireNonNull(contentBounds, "contentBounds");
        elements = List.copyOf(elements);
        if (pageIndex < 0 || contentBounds.x() < 0 || contentBounds.y() < 0
                || contentBounds.x() + contentBounds.width() > size.width()
                || contentBounds.y() + contentBounds.height() > size.height()) {
            throw new IllegalArgumentException("content bounds must lie within a valid page");
        }
    }

    public LayoutPage(int pageIndex, PageSize size, Rect contentBounds, Collection<? extends LayoutFragment> elements) {
        this(pageIndex, size, contentBounds, List.copyOf(elements));
    }

    /** Compatibility view of paragraph fragments only; elements() preserves all mixed content. */
    public List<ParagraphFragment> fragments() {
        return elements.stream().filter(ParagraphFragment.class::isInstance).map(ParagraphFragment.class::cast).toList();
    }
}
