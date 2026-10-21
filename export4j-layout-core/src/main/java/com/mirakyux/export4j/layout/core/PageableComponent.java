// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.List;
import com.mirakyux.export4j.layout.ComponentLayout;

/** Explicitly splittable flow component. Ordered nonempty parts are measured once against a fresh page. */
public interface PageableComponent extends Component {
    List<ComponentLayout> layoutParts(ComponentContext context);

    /** Optional adaptive first fragment; firstPageHeight is finite, from zero to context.height(), in points.
     * If no atomic part fits, a fresh-page first part may be returned.
     */
    default List<ComponentLayout> layoutParts(ComponentContext context, double firstPageHeight) {
        return layoutParts(context);
    }

    /** Fixed-region use requires one part. Flow pagination uses layoutParts instead. */
    @Override
    default ComponentLayout layout(ComponentContext context) {
        var parts = List.copyOf(layoutParts(context));
        if (parts.size() != 1) {
            throw new IllegalArgumentException("pageable component needs flow pagination");
        }
        return parts.getFirst();
    }
}
