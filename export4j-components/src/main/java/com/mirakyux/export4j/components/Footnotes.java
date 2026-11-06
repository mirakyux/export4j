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

    /** Compact replay plan: source and shaped note slices only, never completed body-page IR or display lists.
     * Shaped note storage grows with the supplied footnote text; the caller's semantic source remains retained.
     */
    static final class Plan {
        private final Document source;
        private final int bodyPages;
        private final List<List<ParagraphLayout>> notes;
        private final List<Double> bottoms;
        private final PageSettings settings;
        private final java.util.function.IntFunction<List<com.mirakyux.export4j.graphics.Rect>> exclusions;

        private Plan(Document source, int bodyPages, List<List<ParagraphLayout>> notes, List<Double> bottoms,
                PageSettings settings, java.util.function.IntFunction<List<com.mirakyux.export4j.graphics.Rect>> exclusions) {
            this.source = source;
            this.bodyPages = bodyPages;
            this.notes = notes.stream().map(List::copyOf).toList();
            this.bottoms = List.copyOf(bottoms);
            this.settings = settings;
            this.exclusions = exclusions;
        }

        int pages() { return Math.max(bodyPages, notes.size()); }

        int replay(FontGroup fonts, Map<String, FontGroup> roles, DocumentLayoutLimits limits,
                ComponentLimits componentLimits, DocumentLayouter.PageConsumer consumer) throws java.io.IOException {
            var layouter = new DocumentLayouter(new ParagraphLayouter(), limits, componentLimits);
            int count = exclusions == null ? layouter.layoutPages(source, fonts, roles, consumer)
                    : layouter.layoutPages(source, fonts, roles, exclusions, consumer);
            if (count != bodyPages) { throw new IllegalArgumentException("body pagination changed between planning and replay"); }
            var bodySettings = source.pageSettings();
            var bounds = new com.mirakyux.export4j.graphics.Rect(bodySettings.margins().left(), bodySettings.margins().top(),
                    bodySettings.contentWidth(), bodySettings.contentHeight());
            while (count < pages()) {
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                consumer.accept(new LayoutPage(count++, bodySettings.size(), bounds, List.of()));
            }
            return count;
        }

        ComponentLayout page(int index) {
            var group = index < notes.size() ? notes.get(index) : List.<ParagraphLayout>of();
            double bottom = index < bottoms.size() ? bottoms.get(index) : settings.size().height() - settings.margins().bottom();
            return paintNotes(settings, group, bottom);
        }
    }

    /** The supplied consumer observes each final stable body page once; reservations retain only note metadata. */
    static Plan plan(Document document, List<Note> allNotes, FontGroup fonts, Map<String, FontGroup> roles,
            DocumentLayoutLimits limits, ComponentLimits componentLimits,
            java.util.function.IntFunction<List<com.mirakyux.export4j.graphics.Rect>> exclusions,
            DocumentLayouter.PageConsumer consumer) throws java.io.IOException {
        var ids = document.blocks().stream().map(com.mirakyux.export4j.model.Block::id).collect(java.util.stream.Collectors.toSet());
        var relevant = allNotes.stream().filter(note -> note.kind() == Note.Kind.FOOTNOTE && ids.contains(note.paragraphId())).toList();
        var layouter = new DocumentLayouter(new ParagraphLayouter(), limits, componentLimits);
        if (relevant.isEmpty()) {
            int count = exclusions == null ? layouter.layoutPages(document, fonts, roles, consumer)
                    : layouter.layoutPages(document, fonts, roles, exclusions, consumer);
            return new Plan(document, count, List.of(), List.of(), document.pageSettings(), exclusions);
        }
        var text = new ParagraphLayouter();
        boolean complex = relevant.stream().anyMatch(note -> ParagraphLayouter.requiresOpenType(note.text(), fonts));
        double step = complex ? Math.max(13.5, ParagraphLayouter.minimumLineHeight(fonts, 9))
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
        var settings = document.pageSettings();
        var margins = settings.margins();
        double cap = Math.floor((settings.contentHeight() * 0.4 - 16) / step) * step + 16;
        if (cap < step + 16) { throw new IllegalArgumentException("page is too short for footnotes and body"); }
        double reserve = 0;
        double avoidanceOffset = 0;
        for (int iteration = 0; iteration < 32; iteration++) {
            com.mirakyux.export4j.model.ExecutionContext.checkpoint();
            var bodySettings = new PageSettings(settings.size(), new PageMargins(margins.top(), margins.right(),
                    margins.bottom() + reserve + avoidanceOffset, margins.left()));
            var bodySource = new Document(document.metadata(), bodySettings, document.blocks());
            Map<String, Integer> lastPages = new HashMap<>();
            Map<String, Integer> markerPages = new HashMap<>();
            DocumentLayouter.PageConsumer locate = page -> {
                for (var fragment : page.elements()) {
                    lastPages.put(fragment.blockId(), page.pageIndex());
                    if (fragment instanceof com.mirakyux.export4j.layout.ParagraphFragment paragraph) {
                        for (var line : paragraph.layout().lines()) {
                            for (var field : line.fields()) {
                                if (field.field().kind() == com.mirakyux.export4j.model.InlineField.Kind.NOTE_MARKER) {
                                    markerPages.put(field.field().targetId(), page.pageIndex());
                                }
                            }
                        }
                    }
                }
            };
            int count = exclusions == null ? layouter.layoutPages(bodySource, fonts, roles, locate)
                    : layouter.layoutPages(bodySource, fonts, roles, exclusions, locate);
            List<List<ParagraphLayout>> assigned = new ArrayList<>();
            for (int index = 0; index < count; index++) { assigned.add(new ArrayList<>()); }
            for (var note : measured) {
                Integer page = markerPages.getOrDefault(note.id(), lastPages.get(note.paragraph()));
                if (page == null) { throw new IllegalArgumentException("footnote anchor has no body page"); }
                assigned.get(page).add(note.text());
            }
            double required = Math.min(cap, assigned.stream().filter(group -> !group.isEmpty()).mapToDouble(Footnotes::height).max().orElse(0));
            if (required > Math.nextUp(reserve)) { reserve = required; continue; }
            assigned = continueNotes(assigned, reserve, step, limits.maxPages());
            var bottoms = new ArrayList<Double>();
            double requiredOffset = avoidanceOffset;
            for (int page = 0; page < assigned.size(); page++) {
                double end = settings.size().height() - margins.bottom();
                double noteHeight = height(assigned.get(page));
                if (noteHeight > 0) {
                    var areas = exclusions == null ? List.<com.mirakyux.export4j.graphics.Rect>of() : List.copyOf(exclusions.apply(page));
                    if (areas.size() > 64) { throw new IllegalArgumentException("footnote exclusion budget exceeded"); }
                    boolean moved;
                    do {
                        com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                        moved = false;
                        for (var area : areas) {
                            if (area.x() < settings.size().width() - margins.right() && area.x() + area.width() > margins.left()
                                    && end > area.y() && end - noteHeight < area.y() + area.height()) {
                                end = area.y(); moved = true;
                            }
                        }
                    } while (moved);
                    if (end - noteHeight < margins.top()) { throw new IllegalArgumentException("fixed body avoidance leaves no room for footnotes"); }
                    requiredOffset = Math.max(requiredOffset, settings.size().height() - margins.bottom() - end);
                }
                bottoms.add(end);
            }
            if (requiredOffset > Math.nextUp(avoidanceOffset)) { avoidanceOffset = requiredOffset; continue; }
            var result = new Plan(bodySource, count, assigned, bottoms, settings, exclusions);
            result.replay(fonts, roles, limits, componentLimits, consumer);
            return result;
        }
        throw new IllegalArgumentException("footnote pagination did not converge within 32 reservations");
    }

    private static ComponentLayout paintNotes(PageSettings settings, List<ParagraphLayout> group, double bottom) {
        var builder = new DisplayListBuilder(settings.size());
        double left = settings.margins().left();
        double y = bottom - height(group);
        int lines = 0;
        List<TextDiagnostic> diagnostics = new ArrayList<>();
        if (!group.isEmpty()) {
            builder.strokePath(new VectorPath(List.of(new VectorPath.MoveTo(new Point(left, y + 3)),
                    new VectorPath.LineTo(new Point(left + Math.min(96, settings.contentWidth()), y + 3)))),
                    new RgbColor(0.5, 0.55, 0.6), 0.5);
            y += 12;
            for (var note : group) {
                TextLayoutPainter.draw(builder, note, new Point(left, y), RgbColor.BLACK);
                y += note.height() + 4;
                lines += note.lines().size();
                diagnostics.addAll(note.diagnostics());
            }
        }
        return new ComponentLayout(settings.size().width(), settings.size().height(), lines, builder.build().commands(), diagnostics);
    }

    static Result layout(Document document, List<Note> allNotes, FontGroup fonts, Map<String, FontGroup> roles,
            DocumentLayoutLimits limits, ComponentLimits componentLimits) {
        return layout(document, allNotes, fonts, roles, limits, componentLimits, null);
    }

    static Result layout(Document document, List<Note> allNotes, FontGroup fonts, Map<String, FontGroup> roles,
            DocumentLayoutLimits limits, ComponentLimits componentLimits,
            java.util.function.IntFunction<List<com.mirakyux.export4j.graphics.Rect>> exclusions) {
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
        double avoidanceOffset = 0;
        for (int iteration = 0; iteration < 32; iteration++) {
            com.mirakyux.export4j.model.ExecutionContext.checkpoint();
            var settings = document.pageSettings();
            var margins = settings.margins();
            var bodySettings = new PageSettings(settings.size(), new PageMargins(margins.top(), margins.right(), margins.bottom() + reserve + avoidanceOffset, margins.left()));
            var layouter = new DocumentLayouter(new ParagraphLayouter(), limits, componentLimits);
            var bodySource = new Document(document.metadata(), bodySettings, document.blocks());
            var body = exclusions == null ? layouter.layout(bodySource, fonts, roles) : layouter.layout(bodySource, fonts, roles, exclusions);
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
            var noteBottoms = new ArrayList<Double>();
            double requiredOffset = avoidanceOffset;
            for (int page = 0; page < assigned.size(); page++) {
                double end = settings.size().height() - margins.bottom();
                double noteHeight = height(assigned.get(page));
                if (noteHeight > 0) {
                    var areas = exclusions == null ? List.<com.mirakyux.export4j.graphics.Rect>of() : List.copyOf(exclusions.apply(page));
                    if (areas.size() > 64) { throw new IllegalArgumentException("footnote exclusion budget exceeded"); }
                    // Every collision strictly lowers the candidate band; 64 rectangles bound the work.
                    boolean moved;
                    do {
                        com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                        moved = false;
                        for (var area : areas) {
                            if (area.x() < settings.size().width() - margins.right() && area.x() + area.width() > margins.left()
                                    && end > area.y() && end - noteHeight < area.y() + area.height()) {
                                end = area.y(); moved = true;
                            }
                        }
                    } while (moved);
                    if (end - noteHeight < margins.top()) { throw new IllegalArgumentException("fixed body avoidance leaves no room for footnotes"); }
                    requiredOffset = Math.max(requiredOffset, settings.size().height() - margins.bottom() - end);
                }
                noteBottoms.add(end);
            }
            if (requiredOffset > Math.nextUp(avoidanceOffset)) { avoidanceOffset = requiredOffset; continue; }
            if (assigned.size() > body.pages().size()) {
                var extended = new ArrayList<>(body.pages());
                var bounds = body.pages().getFirst().contentBounds();
                while (extended.size() < assigned.size()) {
                    extended.add(new LayoutPage(extended.size(), settings.size(), bounds, List.of()));
                }
                body = new DocumentLayout(body.metadata(), extended);
            }
            List<ComponentLayout> pages = new ArrayList<>();
            for (int page = 0; page < assigned.size(); page++) {
                var group = assigned.get(page);
                var builder = new DisplayListBuilder(settings.size());
                double y = noteBottoms.get(page) - height(group);
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
