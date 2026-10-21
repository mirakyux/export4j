// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.layout.core.DocumentPainter;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.PageBand;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.TextDiagnostic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;

/** Bridges immutable model page bands to the component SPI without a template-adapter dependency.
 * Lengths use points; regions reserve body space before pagination and resolve fields afterwards.
 * Returned templates are immutable and reusable with immutable font resources. No I/O or resources owned.
 */
public final class PageRegionTemplates {
    private PageRegionTemplates() { }

    /** Produces a uniform header/footer template. Use toBuilder() to add explicit Java overlays/variants.
     * Invalid content, missing roles and overflowing text fail when the component is measured.
     */
    public static PageTemplate from(PageRegions regions) {
        Objects.requireNonNull(regions, "regions");
        return PageTemplate.builder().header(band(regions.header())).footer(band(regions.footer())).build();
    }

    private static PageBand band(PageRegion region) {
        var value = PageBand.builder().height(region.height()).gap(region.gap());
        if (!region.blocks().isEmpty()) { value.content(new RegionContent(region)); }
        return value.build();
    }

    private static String key(String id, int row, int column) { return id + ":" + row + ":" + column; }

    private record RegionContent(PageRegion region) implements Component {
        @Override public ComponentLayout layout(ComponentContext context) {
            if (context.pageCount() < 1) { throw new IllegalArgumentException("page regions require final pagination"); }
            var settings = PageSettings.builder().size(context.width(), context.height()).margins(0).build();
            var measured = new DocumentLayouter(new ParagraphLayouter(),
                    new DocumentLayoutLimits(256, 100_000, 10_000, 1), ComponentLimits.DEFAULT)
                    .layout(new Document(DocumentMetadata.EMPTY, settings, region.blocks()), context.fontGroup(), context.fontRoles());
            var resolved = InlineFields.resolveRegion(measured, context);
            var diagnostics = new ArrayList<TextDiagnostic>();
            var offsets = new HashMap<String, Integer>();
            int[] offset = {0};
            com.mirakyux.export4j.model.SemanticTree.walk(region.blocks(), visit -> {
                if (visit.block() instanceof com.mirakyux.export4j.model.Paragraph paragraph) {
                    offsets.put(paragraph.id(), offset[0]); offset[0] += paragraph.text().length();
                } else if (visit.block() instanceof com.mirakyux.export4j.model.Table table) {
                    for (var row : table.grid().anchorRows()) {
                        for (var anchor : row) {
                            if (anchor.cell().hasBlocks()) { continue; }
                            offsets.put(key(table.id(), anchor.rowIndex(), anchor.columnIndex()), offset[0]);
                            offset[0] += anchor.cell().text().length();
                        }
                    }
                }
            });
            int[] lines = {0};
            for (var fragment : resolved.body().pages().getFirst().elements()) {
                SemanticFragments.walk(fragment, ignored -> { }, leaf -> {
                    lines[0] += leaf.layout().lines().size();
                    int base = offsets.getOrDefault(key(leaf.blockId(), leaf.rowIndex(), leaf.columnIndex()),
                            offsets.getOrDefault(leaf.blockId(), 0));
                    for (var diagnostic : leaf.layout().diagnostics()) {
                        diagnostics.add(new TextDiagnostic(diagnostic.code(), base + diagnostic.sourceStart(),
                                base + diagnostic.sourceEnd(), diagnostic.fontId(), diagnostic.sourceCodePoint(), diagnostic.replacementCodePoint()));
                    }
                });
            }
            for (var generated : resolved.diagnostics()) {
                var diagnostic = generated.diagnostic();
                int base = offsets.getOrDefault(key(generated.blockId(), generated.rowIndex(), generated.columnIndex()),
                        offsets.getOrDefault(generated.blockId(), 0));
                int marker = base + Integer.parseInt(generated.region().substring("INLINE_FIELD:".length()));
                diagnostics.add(new TextDiagnostic(diagnostic.code(), marker, marker + 1, diagnostic.fontId(), -1, diagnostic.replacementCodePoint()));
            }
            return new ComponentLayout(context.width(), context.height(), lines[0],
                    DocumentPainter.paintPage(resolved.body().pages().getFirst()).commands(), diagnostics);
        }
    }
}
