// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.List;

/** Immutable heights in points for sequential regions of one common width.
 * Up to 16 initial regions may be shorter (including zero); subsequent regions use continuationHeight.
 * The caller supplies the width and maximum height through ComponentContext. Owns no resources.
 */
public record FlowRegions(List<Double> initialHeights, double continuationHeight) {
    public FlowRegions {
        initialHeights = List.copyOf(initialHeights);
        if (!Double.isFinite(continuationHeight) || continuationHeight <= 0 || initialHeights.size() > 16) {
            throw new IllegalArgumentException("invalid continuation height or initial region count");
        }
        for (double height : initialHeights) {
            if (!Double.isFinite(height) || height < 0 || height > Math.nextUp(continuationHeight)) {
                throw new IllegalArgumentException("initial region height must fit the continuation viewport");
            }
        }
    }

    /** Returns the height at a nonnegative logical region index; indices are independent of physical pages. */
    public double height(int regionIndex) {
        if (regionIndex < 0) { throw new IllegalArgumentException("region index must be nonnegative"); }
        return regionIndex < initialHeights.size() ? initialHeights.get(regionIndex) : continuationHeight;
    }
}
