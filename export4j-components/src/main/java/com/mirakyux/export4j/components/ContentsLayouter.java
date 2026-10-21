// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.graphics.core.TextLayoutPainter;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.TextDiagnostic;

/** Two-pass front matter: reserve a fixed number column, then resolve labels/links using the final page offset. */
final class ContentsLayouter {
    private static final double LABEL_WIDTH = 48;
    private static final double ENTRY_GAP = 8;

    record Result(List<DisplayPage> pages, List<DocumentExtras.Link> links, List<ComponentLayout> traces) {
    }

    private record Row(Navigation.Bookmark bookmark, ParagraphLayout title, double indent, double height) {
    }

    Result layout(Navigation navigation, PageSettings settings, List<FontFace> fonts,
            Map<String, ResolvedAnchor> anchors, int maxPages, DocumentLayoutLimits limits, ComponentLimits componentLimits) {
        return layout(navigation, settings, FontGroup.strict(fonts), anchors, maxPages, limits, componentLimits, (page, offset) -> page + offset + 1);
    }

    Result layout(Navigation navigation, PageSettings settings, FontGroup fonts,
            Map<String, ResolvedAnchor> anchors, int maxPages, DocumentLayoutLimits limits, ComponentLimits componentLimits,
            java.util.function.IntBinaryOperator pageNumber) {
        if (!navigation.contents().enabled()) {
            return new Result(List.of(), List.of(), List.of());
        }
        if (navigation.bookmarks().isEmpty() || maxPages <= 0) {
            throw new IllegalArgumentException("contents requires bookmarks and an available page budget");
        }
        String heading = navigation.contents().title();
        long codePoints = heading.codePointCount(0, heading.length());
        for (var bookmark : navigation.bookmarks()) {
            codePoints += bookmark.title().codePointCount(0, bookmark.title().length());
        }
        if (codePoints > limits.maxCodePoints()) {
            throw new IllegalArgumentException("contents source text budget exceeded");
        }
        var text = new ParagraphLayouter();
        var style = navigation.contents().style();
        var appearance = navigation.contents().appearance();
        var labelStyle = navigation.contents().pageLabelStyle();
        var title = text.layout(navigation.contents().title(), fonts, settings.contentWidth(),
                navigation.contents().titleStyle());
        double top = title.height() + 12;
        long lineCount = title.lines().size();
        List<List<Row>> planned = new ArrayList<>();
        List<Row> current = new ArrayList<>();
        double used = top;
        for (var bookmark : navigation.bookmarks()) {
            double indent = (bookmark.level() - 1) * 14.0;
            var entry = text.layout(bookmark.title(), fonts, settings.contentWidth() - LABEL_WIDTH - indent, style);
            // Both shared text paths use one fixed lineHeight per line; labels remain one line.
            // Reserve before knowing final numbers, without shaping any artificial probe characters.
            double height = Math.max(Math.max(style.lineHeight(), entry.height()), labelStyle.lineHeight());
            if (top + height > Math.nextUp(settings.contentHeight())) {
                throw new IllegalArgumentException("contents entry and title exceed a fresh page");
            }
            if (used + height > Math.nextUp(settings.contentHeight())) {
                planned.add(List.copyOf(current));
                if (planned.size() >= maxPages) {
                    throw new IllegalArgumentException("contents page budget exceeded");
                }
                current.clear();
                used = top;
                lineCount += title.lines().size();
            }
            lineCount += entry.lines().size() + 1L;
            if (lineCount > limits.maxLines()) {
                throw new IllegalArgumentException("contents output line budget exceeded");
            }
            current.add(new Row(bookmark, entry, indent, height));
            used += height + ENTRY_GAP;
        }
        planned.add(List.copyOf(current));
        List<DisplayPage> pages = new ArrayList<>();
        List<DocumentExtras.Link> links = new ArrayList<>();
        List<ComponentLayout> traces = new ArrayList<>();
        long commands = 0;
        for (int index = 0; index < planned.size(); index++) {
            var builder = new DisplayListBuilder(settings.size());
            double x = settings.margins().left();
            double y = settings.margins().top();
            TextLayoutPainter.draw(builder, title, new Point(x, y), appearance.titleColor());
            List<TextDiagnostic> diagnostics = new ArrayList<>(title.diagnostics());
            int lines = title.lines().size();
            y += top;
            for (var row : planned.get(index)) {
                ResolvedAnchor target = anchors.get(row.bookmark().blockId());
                if (target == null) {
                    throw new IllegalArgumentException("contents refers to an unknown block");
                }
                int page = target.pageIndex() + planned.size();
                var label = text.layout(Integer.toString(pageNumber.applyAsInt(target.pageIndex(), planned.size())), fonts, LABEL_WIDTH, labelStyle);
                if (label.lines().size() != 1 || label.height() > Math.nextUp(labelStyle.lineHeight())) {
                    throw new IllegalArgumentException("contents page label exceeds its reserved column");
                }
                TextLayoutPainter.draw(builder, row.title(), new Point(x + row.indent(), y), appearance.entryColor());
                TextLayoutPainter.draw(builder, label, new Point(x + settings.contentWidth() - LABEL_WIDTH, y), appearance.pageLabelColor());
                links.add(new DocumentExtras.Link(index, new Rect(x, y, settings.contentWidth(), row.height()), page, target.point()));
                diagnostics.addAll(row.title().diagnostics());
                diagnostics.addAll(label.diagnostics());
                lines += row.title().lines().size() + label.lines().size();
                y += row.height() + ENTRY_GAP;
            }
            var page = builder.build();
            commands += page.commands().size();
            if (commands > componentLimits.maxCommands()) {
                throw new IllegalArgumentException("contents command budget exceeded");
            }
            pages.add(page);
            traces.add(new ComponentLayout(settings.size().width(), settings.size().height(), lines, page.commands(), diagnostics));
        }
        return new Result(List.copyOf(pages), List.copyOf(links), List.copyOf(traces));
    }
}
