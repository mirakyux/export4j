// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;

/** Immutable repeating page box. Coordinates and width use points from the page's top-left;
 * content.height is the fixed viewport height and content.gap must be zero. Does not reserve body space.
 * Content must fit without scaling or clipping. Owns no resources and is safe to share.
 */
public record PageOverlay(double x, double y, double width, PageRegion content) {
    public PageOverlay {
        Objects.requireNonNull(content, "content");
        if (!Double.isFinite(x) || x < 0 || !Double.isFinite(y) || y < 0
                || !Double.isFinite(width) || width <= 0 || content.height() <= 0 || content.gap() != 0
                || !Double.isFinite(x + width) || !Double.isFinite(y + content.height())) {
            throw new IllegalArgumentException("invalid page overlay geometry");
        }
    }

    /** Rejects a box outside the physical page, including inherited boxes on a smaller section. */
    public void requireContained(PageSize page) {
        Objects.requireNonNull(page, "page");
        if (x + width > Math.nextUp(page.width()) || y + content.height() > Math.nextUp(page.height())) {
            throw new IllegalArgumentException("page overlay exceeds page bounds");
        }
    }
}
