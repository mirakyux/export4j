// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.layout.core.DocumentPainter;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.PageBand;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.layout.core.Placement;
import com.mirakyux.export4j.layout.core.Position;
import com.mirakyux.export4j.graphics.Rect;
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

/** Bridges immutable model page regions to the component SPI without a template-adapter dependency.
 * Lengths use points; bands and explicit avoiding boxes reserve body space before pagination. Fields resolve afterwards.
 * Returned templates are immutable and reusable with immutable font resources. No I/O or resources owned.
 */
public final class PageRegionTemplates {
    private PageRegionTemplates() { }

    /** Produces a uniform header/footer template. Use toBuilder() to add explicit Java overlays/variants.
     * Invalid content, missing roles and overflowing text fail when the component is measured.
     */
    public static PageTemplate from(PageRegions regions) {
        Objects.requireNonNull(regions, "regions");
        if (!regions.variants().isEmpty()) {
            throw new IllegalArgumentException("page-region variants require sectionFrom");
        }
        return template(regions, new PageRegions.Bands(regions.header(), regions.footer()));
    }

    /** Converts complete model variants, selecting FIRST before displayed parity and reserving all bands.
     * An absent FIRST uses odd/even even on the section's first page; a declared blank pair stays blank.
     */
    public static SectionTemplate sectionFrom(PageRegions regions) {
        Objects.requireNonNull(regions, "regions");
        var odd = template(regions, new PageRegions.Bands(regions.header(), regions.footer()));
        var evenPair = regions.variants().get(PageRegions.Variant.EVEN);
        var firstPair = regions.variants().get(PageRegions.Variant.FIRST);
        var even = evenPair == null ? odd : template(regions, evenPair);
        var first = firstPair == null ? null : template(regions, firstPair);
        return new SectionTemplate(first == null ? odd : first, odd, even, first == null ? even : first);
    }

    private static PageTemplate template(PageRegions regions, PageRegions.Bands pair) {
        var result = PageTemplate.builder().header(band(pair.header())).footer(band(pair.footer()));
        for (var box : regions.background()) {
            result.background(overlay(box));
        }
        for (var box : regions.foreground()) {
            result.foreground(overlay(box));
        }
        return result.build();
    }

    private static Placement overlay(com.mirakyux.export4j.model.PageOverlay box) {
        return new Placement(new RegionContent(box.content(), box.heightMode() == com.mirakyux.export4j.model.PageOverlay.SizeMode.AUTO),
                new Position.PageBox(box.x(), box.y(), box.width(), box.content().height(),
                        box.widthMode() == com.mirakyux.export4j.model.PageOverlay.SizeMode.FILL,
                        box.heightMode() != com.mirakyux.export4j.model.PageOverlay.SizeMode.FIXED), box.page(),
                box.bodyFlow() == com.mirakyux.export4j.model.PageOverlay.BodyFlow.AVOID);
    }

    private static PageBand band(PageRegion region) {
        var value = PageBand.builder().height(region.height()).gap(region.gap())
                .edgeOffset(region.edgeOffset()).leftInset(region.leftInset()).rightInset(region.rightInset());
        if (!region.blocks().isEmpty()) { value.content(new RegionContent(region)); }
        return value.build();
    }

    private static String key(String id, int row, int column) { return id + ":" + row + ":" + column; }

    private record RegionContent(PageRegion region, boolean autoHeight) implements Component {
        private RegionContent(PageRegion region) { this(region, false); }
        @Override public ComponentLayout layout(ComponentContext context) {
            var settings = PageSettings.builder().size(context.width(), context.height()).margins(0).build();
            com.mirakyux.export4j.layout.DocumentLayout measured;
            try {
                measured = new DocumentLayouter(new ParagraphLayouter(),
                        new DocumentLayoutLimits(256, 100_000, 10_000, 1), ComponentLimits.DEFAULT)
                        .layout(new Document(DocumentMetadata.EMPTY, settings, region.blocks()), context.fontGroup(), context.fontRoles());
            } catch (DocumentLayoutException cause) {
                // Regions cannot continue on another page. Only this local one-page capacity limit
                // is geometry overflow; independent source/line/structure budgets remain budgets.
                if (cause.code() != DocumentLayoutException.Code.BUDGET_EXCEEDED || cause.pageIndex() != 1
                        || !cause.getMessage().equals("document page budget exceeded")) { throw cause; }
                throw new DocumentLayoutException(DocumentLayoutException.Code.CONTENT_TOO_TALL,
                        cause.blockId(), cause.blockIndex(), context.pageIndex(), cause.rowIndex(), cause.columnIndex(),
                        cause.sourceIndex(), cause.sourcePath(), "page region content '" + cause.blockId()
                        + "' exceeds " + context.height() + "pt height; increase the region height or use height-mode=AUTO for a fixed box", cause);
            }
            if (context.pageCount() == 0) {
                return new ComponentLayout(context.width(), measuredHeight(measured, context), 0, java.util.List.of(), java.util.List.of());
            }
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
            double height = measuredHeight(resolved.body(), context);
            return new ComponentLayout(context.width(), height, lines[0],
                    DocumentPainter.paintPage(resolved.body().pages().getFirst(), ModelRotations.of(region.blocks())).commands(), diagnostics);
        }

        private double measuredHeight(com.mirakyux.export4j.layout.DocumentLayout body, ComponentContext context) {
            return autoHeight ? Math.max(0.01, body.pages().getFirst().elements().stream().mapToDouble(fragment -> {
                if (fragment instanceof com.mirakyux.export4j.layout.ParagraphFragment p) { return p.origin().y() + p.layout().height(); }
                if (fragment instanceof com.mirakyux.export4j.layout.TableFragment t) { return t.origin().y() + t.height(); }
                return context.height();
            }).max().orElse(0.01)) : context.height();
        }
    }
}
