// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.layout.DocumentLayout;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.core.ComponentSemantics;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableGrid;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.layout.core.InlineLinkGeometry;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.text.ParagraphLayout;

/** Resolves inline hyperlink boxes from final line ink, including continuation pages and bidi positions. */
final class InlineHyperlinks {
    private record Source(String text, List<InlineText> inlines, double step, Point origin, ParagraphLayout layout,
            SemanticFragments.Text context) { }
    private InlineHyperlinks() { }

    static List<DocumentExtras.ExternalLink> collect(SectionedDocument document, DocumentLayout body, int pageOffset, long maxLinks) {
        Map<String, Block> blocks = new HashMap<>();
        SemanticTree.walk(document.sections().stream().flatMap(section -> section.blocks().stream()).toList(),
                visit -> blocks.put(visit.block().id(), visit.block()));
        for (var page : body.pages()) {
            for (var fragment : page.elements()) {
                if (fragment instanceof ComponentFragment component) {
                    for (var located : ComponentSemantics.locate(component.layout(), component.origin())) {
                        var source = located.placement().source();
                        if (!blocks.containsKey(source.id())) {
                            SemanticTree.walk(source, visit -> blocks.put(visit.block().id(), visit.block()));
                        }
                    }
                }
            }
        }
        List<DocumentExtras.ExternalLink> result = new ArrayList<>();
        Map<Table, TableGrid> grids = new java.util.IdentityHashMap<>();
        for (var page : body.pages()) {
            for (var fragment : page.elements()) {
                List<Source> sources = new ArrayList<>();
                SemanticFragments.walk(fragment, ignored -> { }, leaf -> {
                    var block = blocks.get(leaf.blockId());
                    if (block instanceof Table table) {
                        var original = grids.computeIfAbsent(table, Table::grid)
                                .anchorAt(leaf.rowIndex(), leaf.columnIndex()).orElseThrow().cell();
                        sources.add(new Source(original.text(), original.inlines(), original.format().lineHeight(), leaf.origin(), leaf.layout(), leaf));
                    } else {
                        Paragraph paragraph = switch (block) {
                            case Paragraph value -> value;
                            case Heading value -> value.paragraph();
                            default -> throw new IllegalArgumentException("final text leaf has no semantic source");
                        };
                        sources.add(new Source(paragraph.text(), paragraph.inlines(), paragraph.format().lineHeight(), leaf.origin(), leaf.layout(), leaf));
                    }
                });
                for (var paragraph : sources) {
                    ExecutionContext.checkpoint();
                    InlineLinkGeometry.collect(paragraph.text(), paragraph.inlines(), paragraph.step(),
                            paragraph.origin(), paragraph.layout(), page.pageIndex() + pageOffset, page.size().width(),
                            (link, source) -> append(result, maxLinks, link, paragraph, source));
                }
            }
        }
        return List.copyOf(result);
    }

    private static void append(List<DocumentExtras.ExternalLink> links, long maxLinks, DocumentExtras.ExternalLink link,
            Source source, int sourceIndex) {
        if (!links.isEmpty()) {
            var previous = links.getLast();
            var a = previous.bounds(); var b = link.bounds();
            if (previous.pageIndex() == link.pageIndex() && previous.destination().equals(link.destination())
                    && a.y() == b.y() && a.height() == b.height() && Math.abs(a.x() + a.width() - b.x()) < 1e-8) {
                links.set(links.size() - 1, new DocumentExtras.ExternalLink(link.pageIndex(), new Rect(a.x(), a.y(), b.x() + b.width() - a.x(), a.height()), link.destination()));
                return;
            }
        }
        if (links.size() >= maxLinks) {
            var owner = source.context();
            throw new DocumentLayoutException(DocumentLayoutException.Code.BUDGET_EXCEEDED, owner.blockId(), owner.blockIndex(),
                    link.pageIndex(), owner.rowIndex(), owner.columnIndex(), sourceIndex, owner.sourcePath(),
                    "inline link interaction budget exceeded", null);
        }
        links.add(link);
    }
}
