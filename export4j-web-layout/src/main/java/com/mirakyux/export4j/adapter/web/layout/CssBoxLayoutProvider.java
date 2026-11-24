// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web.layout;

import com.mirakyux.export4j.adapter.web.internal.HtmlLayoutProvider;
import com.mirakyux.export4j.components.SemanticTable;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.model.Block;

/** Runtime provider for the optional layout mode of the HTML importer. */
public final class CssBoxLayoutProvider implements HtmlLayoutProvider {
    @Override
    public Block compile(String blockId, Box source) {
        return new ComponentBlock(blockId, compile(source));
    }

    private static HtmlCssBox compile(Box source) {
        var children = source.children().stream().map(CssBoxLayoutProvider::compile).toList();
        return new HtmlCssBox(source.style(), children, source.leaf() == null ? null : new SemanticTable(source.leaf()));
    }
}
