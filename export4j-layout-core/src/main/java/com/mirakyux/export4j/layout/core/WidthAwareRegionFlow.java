// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.List;

/** Region flow that wraps at the actual width of each region.
 * Widths are an immutable cyclic sequence in points, indexed by Fragment.regionIndex(), including skipped
 * initial regions. Each width must be positive and fit context.width(); heights follow FlowRegions.
 * Implementations retain original UTF-16 coordinates and must reject unsupported policies explicitly.
 * Owns no resources; implementations and callbacks are trusted cooperative Java code.
 */
public interface WidthAwareRegionFlow extends RegionFlow {
    List<Fragment> layoutVariableRegions(ComponentContext context, FlowRegions regions, List<Double> widths);
}
