// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.graphics.core.TextLayoutPainter;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.DocumentLayout;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextLine;
import com.mirakyux.export4j.layout.LayoutPage;

/** Bounded monotone reservation; large notes continue in complete lines in a band capped at 40% of body height. */
final class Footnotes {
    record Result(DocumentLayout body, List<ComponentLayout> pages) { }
    private record Measured(String id, String paragraph, ParagraphLayout text) { }

    private Footnotes() { }

    static Result layout(Document document, List<Note> allNotes, FontGroup fonts, Map<String, FontGroup> roles,
            DocumentLayoutLimits limits, ComponentLimits componentLimits) {
        var ids = document.blocks().stream().map(com.mirakyux.export4j.model.Block::id).collect(java.util.stream.Collectors.toSet());
        var text = new ParagraphLayouter();
        boolean complexNotes = allNotes.stream().anyMatch(note -> ParagraphLayouter.requiresOpenType(note.text(), fonts));
        double step = complexNotes ? Math.max(13.5, ParagraphLayouter.minimumLineHeight(fonts, 9))
                : Math.max(13.5, 9 * (fonts.fonts().stream().mapToDouble(f -> f.metrics().ascent()).max().orElseThrow()
                + fonts.fonts().stream().mapToDouble(f -> f.metrics().descent()).max().orElseThrow()));
        List<Measured> measured = new ArrayList<>();
        for (int index = 0; index < allNotes.size(); index++) {
            var note = allNotes.get(index);
            if (note.kind() == Note.Kind.FOOTNOTE && ids.contains(note.paragraphId())) {
                measured.add(new Measured(note.id(), note.paragraphId(), text.layout("[" + (index + 1) + "] " + note.text(), fonts,
                        document.pageSettings().contentWidth(), new ParagraphStyle(9, step, 0, 0, com.mirakyux.export4j.text.TextAlignment.LEFT))));
            }
        }
        double reserve = 0;
        for (int iteration = 0; iteration < 32; iteration++) {
            com.mirakyux.export4j.model.ExecutionContext.checkpoint();
            var settings = document.pageSettings();
            var margins = settings.margins();
            var bodySettings = new PageSettings(settings.size(), new PageMargins(margins.top(), margins.right(), margins.bottom() + reserve, margins.left()));
            var body = new DocumentLayouter(new ParagraphLayouter(), limits, componentLimits).layout(
                    new Document(document.metadata(), bodySettings, document.blocks()), fonts, roles);
            Map<String, Integer> lastPages = new HashMap<>();
            Map<String, Integer> markerPages = new HashMap<>();
            for (var page : body.pages()) {
                for (var fragment : page.elements()) {
                    lastPages.put(fragment.blockId(), page.pageIndex());
                    if (fragment instanceof com.mirakyux.export4j.layout.ParagraphFragment p) {
                        for (var line : p.layout().lines()) {
                            for (var field : line.fields()) {
                                if (field.field().kind() == com.mirakyux.export4j.model.InlineField.Kind.NOTE_MARKER) {
                                    markerPages.put(field.field().targetId(), page.pageIndex());
                                }
                            }
                        }
                    }
                }
            }
            List<List<ParagraphLayout>> assigned = new ArrayList<>();
            for (int page = 0; page < body.pages().size(); page++) {
                assigned.add(new ArrayList<>());
            }
            for (var note : measured) {
                assigned.get(markerPages.getOrDefault(note.id(), lastPages.get(note.paragraph()))).add(note.text());
            }
            double cap = Math.floor((settings.contentHeight() * 0.4 - 16) / step) * step + 16;
            if (!measured.isEmpty() && cap < step + 16) { throw new IllegalArgumentException("page is too short for footnotes and body"); }
            double required = Math.min(cap, assigned.stream().filter(group -> !group.isEmpty()).mapToDouble(Footnotes::height).max().orElse(0));
            if (required > Math.nextUp(reserve)) {
                reserve = required;
                continue;
            }
            assigned = continueNotes(assigned, reserve, step, limits.maxPages());
            if (assigned.size() > body.pages().size()) {
                var extended = new ArrayList<>(body.pages());
                var bounds = body.pages().getFirst().contentBounds();
                while (extended.size() < assigned.size()) {
                    extended.add(new LayoutPage(extended.size(), settings.size(), bounds, List.of()));
                }
                body = new DocumentLayout(body.metadata(), extended);
            }
            List<ComponentLayout> pages = new ArrayList<>();
            for (var group : assigned) {
                var builder = new DisplayListBuilder(settings.size());
                double y = settings.size().height() - margins.bottom() - height(group);
                int lines = 0;
                List<TextDiagnostic> diagnostics = new ArrayList<>();
                if (!group.isEmpty()) {
                    builder.strokePath(new VectorPath(List.of(new VectorPath.MoveTo(new Point(margins.left(), y + 3)),
                            new VectorPath.LineTo(new Point(margins.left() + Math.min(96, settings.contentWidth()), y + 3)))),
                            new RgbColor(0.5, 0.55, 0.6), 0.5);
                    y += 12;
                    for (var note : group) {
                        TextLayoutPainter.draw(builder, note, new Point(margins.left(), y), RgbColor.BLACK);
                        y += note.height() + 4;
                        lines += note.lines().size();
                        diagnostics.addAll(note.diagnostics());
                    }
                }
                pages.add(new ComponentLayout(settings.size().width(), settings.size().height(), lines, builder.build().commands(), diagnostics));
            }
            return new Result(body, List.copyOf(pages));
        }
        throw new IllegalArgumentException("footnote pagination did not converge within 32 reservations");
    }

    private static double height(List<ParagraphLayout> group) {
        return group.isEmpty() ? 0 : 12 + group.stream().mapToDouble(note -> note.height() + 4).sum();
    }

    private static List<List<ParagraphLayout>> continueNotes(List<List<ParagraphLayout>> anchored, double reserve, double step, int maxPages) {
        List<List<ParagraphLayout>> assigned = new ArrayList<>();
        var pending = new java.util.ArrayDeque<ParagraphLayout>();
        for (int page = 0; page < anchored.size() || !pending.isEmpty(); page++) {
            if (page >= maxPages) { throw new IllegalArgumentException("footnote continuation page budget exceeded"); }
            var arrivals = page < anchored.size() ? anchored.get(page) : List.<ParagraphLayout>of();
            double minimum = arrivals.size() * (step + 4);
            if (!arrivals.isEmpty() && minimum > Math.nextUp(reserve - 12)) {
                throw new IllegalArgumentException("footnote band cannot hold the first line of each newly anchored note");
            }
            int carried = pending.size();
            pending.addAll(arrivals);
            List<ParagraphLayout> group = new ArrayList<>();
            double remaining = reserve - 12;
            var next = new java.util.ArrayDeque<ParagraphLayout>();
            int notes = pending.size();
            for (int index = 0; index < notes; index++) {
                var note = pending.removeFirst();
                if (index >= carried) { minimum -= step + 4; }
                int count = Math.min(note.lines().size(), (int) Math.floor(Math.nextUp((remaining - minimum - 4) / step)));
                if (count < 1) { next.addLast(note); continue; }
                group.add(slice(note, 0, count));
                remaining -= count * step + 4;
                if (count < note.lines().size()) {
                    next.addLast(slice(note, count, note.lines().size() - count));
                }
            }
            pending = next;
            if (group.isEmpty() && !pending.isEmpty() && reserve < step + 16) { throw new IllegalArgumentException("footnote band cannot hold one line"); }
            assigned.add(group);
        }
        return assigned;
    }

    private static ParagraphLayout slice(ParagraphLayout source, int first, int count) {
        List<TextLine> lines = new ArrayList<>();
        double base = source.lines().get(first).baselineY() - source.lines().getFirst().baselineY();
        for (int index = first; index < first + count; index++) {
            var line = source.lines().get(index);
            lines.add(line.atBaseline(line.baselineY() - base));
        }
        int start = lines.getFirst().sourceStart();
        int end = lines.getLast().sourceEnd();
        return new ParagraphLayout(source.width(), count * (source.height() / source.lines().size()), lines,
                source.diagnostics().stream().filter(d -> d.sourceStart() >= start && d.sourceStart() < end).toList());
    }
}
