// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.layout.ComponentLayout;

/** Flow that can consume a finite prefix of shorter regions before full-height continuation regions.
 * Implementations measure once at context.width(), preserve source order, and return one positive-height fragment
 * per occupied region. Too-short initial regions may be skipped; continuation regions must make progress.
 * Results are ordered, immutable and bounded to 100,000 fragments. Child code remains trusted and cooperative.
 */
public interface RegionFlow extends PageableComponent {
    /** A region assignment, independent of the final physical page or column. The drawing uses local coordinates. */
    record Fragment(int regionIndex, ComponentLayout layout) {
        public Fragment {
            Objects.requireNonNull(layout, "layout");
            if (regionIndex < 0 || regionIndex >= 100_016) { throw new IllegalArgumentException("region index budget exceeded"); }
        }
    }

    /** The continuation height must fit context.height(); each fragment must fit its assigned height and width.
     * Initial regions may only be skipped when the next atomic fragment cannot fit there.
     * Unfit continuation content or impossible pagination constraints must fail, never generate empty regions.
     */
    List<Fragment> layoutRegions(ComponentContext context, FlowRegions regions);

    @Override
    default List<ComponentLayout> layoutParts(ComponentContext context) {
        return layoutRegions(context, new FlowRegions(List.of(), context.height())).stream().map(Fragment::layout).toList();
    }

    @Override
    default List<ComponentLayout> layoutParts(ComponentContext context, double firstPageHeight) {
        return layoutRegions(context, new FlowRegions(List.of(firstPageHeight), context.height())).stream().map(Fragment::layout).toList();
    }
}
