// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.text.InlineGraphic;
import com.mirakyux.export4j.text.TextOutline;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lowers immutable semantic vector resources without changing their baseline geometry. */
final class InlineVectors {
    private InlineVectors() { }

    static Map<Integer, InlineGraphic> graphics(List<Paragraph.VectorSpan> vectors) {
        if (vectors.isEmpty()) { return Map.of(); }
        long segments = 0;
        for (var span : vectors) {
            ExecutionContext.checkpoint();
            segments += span.vector().resource().segmentCount();
            if (segments > 2_000_000) {
                throw new com.mirakyux.export4j.text.TextLayoutException(
                        com.mirakyux.export4j.text.TextLayoutException.Code.BUDGET_EXCEEDED,
                        span.sourceIndex(), "inline vector shaping segment budget exceeded");
            }
        }
        var result = new LinkedHashMap<Integer, InlineGraphic>();
        var geometry = new java.util.HashMap<String, List<TextOutline.Segment>>();
        for (var span : vectors) {
            ExecutionContext.checkpoint();
            var vector = span.vector();
            var resource = vector.resource();
            var paths = geometry.computeIfAbsent(resource.id(), ignored -> resource.segments().stream().map(segment -> new TextOutline.Segment(
                    switch (segment.operation()) {
                        case MOVE -> TextOutline.Operation.MOVE;
                        case LINE -> TextOutline.Operation.LINE;
                        case CURVE -> TextOutline.Operation.CURVE;
                        case CLOSE -> TextOutline.Operation.CLOSE;
                    }, segment.coordinates())).toList());
            result.put(span.sourceIndex(), new InlineGraphic(resource.width(), resource.ascent(),
                    resource.descent(), new TextOutline(vector.logicalText(), paths, vector.rgb(), 1)));
        }
        return Map.copyOf(result);
    }
}
