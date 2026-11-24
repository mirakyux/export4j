// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web.internal;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.Table;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

/** Internal boundary between source validation and optional CSS box layout. */
public interface HtmlLayoutProvider {
    Block compile(String blockId, Box box);

    static HtmlLayoutProvider load() {
        return ServiceLoader.load(HtmlLayoutProvider.class, HtmlLayoutProvider.class.getClassLoader())
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "CSS layout mode requires com.mirakyux.export4j:export4j-web-layout"));
    }

    /** Immutable CSS box input with a semantic table for any measured leaf. */
    record Box(CssBoxStyle style, List<Box> children, Table leaf) {
        public Box {
            Objects.requireNonNull(style, "style");
            children = List.copyOf(children);
            if (children.size() > 256) { throw new IllegalArgumentException("CSS box child budget exceeded"); }
        }

        public boolean absolute() { return style.text("position", "static").equals("absolute"); }

        public Box withDeclaration(String name, String value) {
            return new Box(style.withDeclaration(name, value), children, leaf);
        }
    }
}
