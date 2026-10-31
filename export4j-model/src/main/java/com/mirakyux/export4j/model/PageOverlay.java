// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;

/** Immutable semantic page box. Coordinates and width use points from the page's top-left;
 * content.height is the declared height and content.gap must be zero. BodyFlow controls body avoidance.
 * A zero page repeats; positive pages are one-based document physical pages. AUTO height measures
 * within remaining page height; FILL uses the remaining extent. Other dimensions stay fixed.
 * Content must fit without scaling or clipping. Owns no resources and is safe to share.
 */
public record PageOverlay(double x, double y, double width, PageRegion content, int page,
        SizeMode widthMode, SizeMode heightMode, BodyFlow bodyFlow) {
    /** Fixed points, content height, or the remaining physical page extent. AUTO is valid only for height. */
    public enum SizeMode { FIXED, AUTO, FILL }
    /** OVERLAP leaves body flow unchanged; AVOID excludes the box's vertical extent across the body width. */
    public enum BodyFlow { OVERLAP, AVOID }

    /** Repeating box with explicit dimensions, preserving the original constructor. */
    public PageOverlay(double x, double y, double width, PageRegion content) {
        this(x, y, width, content, 0, SizeMode.FIXED, SizeMode.FIXED, BodyFlow.OVERLAP);
    }

    /** page is a one-based physical page, or zero to repeat on every page in the declaring section. */
    public PageOverlay(double x, double y, double width, PageRegion content, int page) {
        this(x, y, width, content, page, SizeMode.FIXED, SizeMode.FIXED, BodyFlow.OVERLAP);
    }

    /** Explicit page/dimensions with the compatibility body-overlap behavior. */
    public PageOverlay(double x, double y, double width, PageRegion content, int page,
            SizeMode widthMode, SizeMode heightMode) {
        this(x, y, width, content, page, widthMode, heightMode, BodyFlow.OVERLAP);
    }

    public PageOverlay {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(widthMode, "widthMode"); Objects.requireNonNull(heightMode, "heightMode");
        Objects.requireNonNull(bodyFlow, "bodyFlow");
        if (page < 0 || widthMode == SizeMode.AUTO) { throw new IllegalArgumentException("invalid overlay page or width mode"); }
        if (!Double.isFinite(x) || x < 0 || !Double.isFinite(y) || y < 0
                || !Double.isFinite(width) || width <= 0 || content.height() <= 0 || content.gap() != 0
                || !Double.isFinite(x + width) || !Double.isFinite(y + content.height())) {
            throw new IllegalArgumentException("invalid page overlay geometry");
        }
    }

    /** Rejects a box outside the physical page, including inherited boxes on a smaller section. */
    public void requireContained(PageSize page) {
        Objects.requireNonNull(page, "page");
        if (x >= page.width() || y >= page.height()
                || widthMode == SizeMode.FIXED && x + width > Math.nextUp(page.width())
                || heightMode == SizeMode.FIXED && y + content.height() > Math.nextUp(page.height())) {
            throw new IllegalArgumentException("page overlay exceeds page bounds");
        }
    }
}
