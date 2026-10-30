// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.model.Table;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Collects block rotations by stable semantic ID after binding and lowering. */
final class ModelRotations {
    private ModelRotations() { }

    static Map<String, Double> of(List<Block> blocks) {
        var result = new HashMap<String, Double>();
        SemanticTree.walk(blocks, visit -> {
            double angle = switch (visit.block()) {
                case Paragraph paragraph -> paragraph.format().rotation();
                case Table table -> table.format().rotation();
                default -> 0;
            };
            if (angle != 0) { result.put(visit.block().id(), angle); }
        });
        return Map.copyOf(result);
    }
}
