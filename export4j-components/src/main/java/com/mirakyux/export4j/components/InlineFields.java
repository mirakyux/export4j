// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.layout.DocumentLayout;
import com.mirakyux.export4j.layout.LayoutFragment;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.model.CellLocation;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.StyledSpan;
import com.mirakyux.export4j.text.TextAlignment;
import com.mirakyux.export4j.text.TextLine;
import com.mirakyux.export4j.text.TextOutline;

/** Replaces reserved logical slots with final ink without changing source ranges, line positions or pagination. */
final class InlineFields {
    record Result(DocumentLayout body, List<DocumentExtras.Link> links, List<ContextualDiagnostic> diagnostics) { }
    private record Owner(String blockId, int blockIndex, int pageIndex, int rowIndex, int columnIndex, Point origin,
            List<CellLocation> sourcePath) {
        DocumentLayoutException failure(DocumentLayoutException.Code code, int source, String message, Throwable cause) {
            return new DocumentLayoutException(code, blockId, blockIndex, pageIndex, rowIndex, columnIndex, source, sourcePath, message, cause);
        }
    }
    private InlineFields() { }

    static Result resolve(DocumentLayout body, int offset, IntUnaryOperator numbers,
            Map<String, ResolvedAnchor> anchors, Map<String, String> labels, List<Note> notes,
            FontGroup fonts, Map<String, FontGroup> roles, long maxLinks, long maxGeneratedCodePoints) {
        boolean present = body.pages().stream().flatMap(page -> page.elements().stream()).anyMatch(InlineFields::hasFields);
        if (!present) { return new Result(body, List.of(), List.of()); }
        var resolver = new Resolver(body, offset, numbers, anchors, labels, notes, maxLinks, maxGeneratedCodePoints);
        var pages = new ArrayList<LayoutPage>();
        for (var page : body.pages()) {
            var fragments = new ArrayList<LayoutFragment>();
            for (var fragment : page.elements()) {
                fragments.add(SemanticFragments.map(fragment, fonts, roles, leaf -> resolver.layout(leaf.layout(),
                        new Owner(leaf.blockId(), leaf.blockIndex(), page.pageIndex() + offset, leaf.rowIndex(), leaf.columnIndex(),
                                leaf.origin(), leaf.sourcePath()), page.pageIndex(), leaf.fonts(), leaf.roles())));
            }
            pages.add(new LayoutPage(page.pageIndex(), page.size(), page.contentBounds(), fragments));
        }
        return new Result(new DocumentLayout(body.metadata(), pages), List.copyOf(resolver.links), List.copyOf(resolver.diagnostics));
    }

    private static boolean hasFields(ParagraphLayout layout) {
        return layout.lines().stream().anyMatch(line -> !line.fields().isEmpty());
    }

    private static boolean hasFields(LayoutFragment fragment) {
        boolean[] present = {false};
        SemanticFragments.walk(fragment, ignored -> { }, leaf -> present[0] |= hasFields(leaf.layout()));
        return present[0];
    }

    /** One state per document: repeated headers and cell continuations share the same generation budgets. */
    private static final class Resolver {
        private final int offset;
        private final int totalPages;
        private final IntUnaryOperator numbers;
        private final Map<String, ResolvedAnchor> anchors;
        private final Map<String, String> labels;
        private final Map<String, Integer> noteNumbers = new java.util.HashMap<>();
        private final long maxLinks;
        private final long maxGeneratedCodePoints;
        private final ParagraphLayouter layouter = new ParagraphLayouter();
        private final List<DocumentExtras.Link> links = new ArrayList<>();
        private final List<ContextualDiagnostic> diagnostics = new ArrayList<>();
        private int count;
        private long generatedCodePoints;

        Resolver(DocumentLayout body, int offset, IntUnaryOperator numbers, Map<String, ResolvedAnchor> anchors,
                Map<String, String> labels, List<Note> notes,
                long maxLinks, long maxGeneratedCodePoints) {
            this.offset = offset;
            totalPages = Math.addExact(offset, body.pages().size());
            this.numbers = numbers;
            this.anchors = anchors;
            this.labels = labels;
            this.maxLinks = maxLinks;
            this.maxGeneratedCodePoints = maxGeneratedCodePoints;
            for (int i = 0; i < notes.size(); i++) { noteNumbers.put(notes.get(i).id(), i + 1); }
        }

        ParagraphLayout layout(ParagraphLayout source, Owner owner, int bodyPageIndex, FontGroup measuredFonts, Map<String, FontGroup> measuredRoles) {
            if (!hasFields(source)) { return source; }
            var lines = new ArrayList<TextLine>();
            double step = source.height() / source.lines().size();
            for (var line : source.lines()) {
                if (line.fields().isEmpty()) { lines.add(line); continue; }
                var ink = new ArrayList<>(line.outlines());
                for (var box : line.fields()) {
                    com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                    try {
                        if (++count > 100_000) { throw budget(owner, box.sourceIndex()); }
                        var field = box.field();
                        var target = anchors.get(field.targetId());
                        String value = switch (field.kind()) {
                            case PAGE -> Integer.toString(numbers.applyAsInt(bodyPageIndex));
                            case PAGES -> Integer.toString(totalPages);
                            case REFERENCE_PAGE -> {
                                if (target == null) { throw new IllegalArgumentException("unknown inline reference target"); }
                                yield Integer.toString(numbers.applyAsInt(target.pageIndex()));
                            }
                            case REFERENCE_NUMBER -> {
                                if (target == null || !labels.containsKey(field.targetId())) {
                                    throw new IllegalArgumentException("inline number reference requires a numbered target");
                                }
                                yield labels.get(field.targetId());
                            }
                            case NOTE_MARKER -> {
                                var number = noteNumbers.get(field.targetId());
                                if (number == null) { throw new IllegalArgumentException("unknown inline note"); }
                                yield "[" + number + "]";
                            }
                        };
                        generatedCodePoints += value.codePointCount(0, value.length());
                        if (generatedCodePoints > maxGeneratedCodePoints) { throw budget(owner, box.sourceIndex()); }
                        var style = field.style();
                        var group = style.fontRole().isEmpty() ? measuredFonts : measuredRoles.get(style.fontRole());
                        if (group == null) { throw new IllegalArgumentException("unknown inline field font role"); }
                        var resolved = layouter.layoutOpenType(List.of(new StyledSpan(value, group, style.fontSize(), style.rgb(),
                                style.baselineShift(), style.underline(), style.strikeThrough())), field.width(),
                                new ParagraphStyle(style.fontSize(), step, 0, 0, TextAlignment.LEFT));
                        if (resolved.lines().size() != 1 || resolved.lines().getFirst().width() > Math.nextUp(field.width())) {
                            throw new IllegalArgumentException("inline field value exceeds reserved width");
                        }
                        List<TextOutline.Segment> paths = new ArrayList<>();
                        for (var outline : resolved.lines().getFirst().outlines()) {
                            for (var segment : outline.segments()) {
                                var coordinates = new ArrayList<>(segment.coordinates());
                                for (int i = 0; i < coordinates.size(); i += 2) { coordinates.set(i, coordinates.get(i) + box.x()); }
                                paths.add(new TextOutline.Segment(segment.operation(), coordinates));
                            }
                        }
                        ink.set(box.outlineIndex(), new TextOutline(value, paths, style.rgb()));
                        for (var diagnostic : resolved.diagnostics()) {
                            diagnostics.add(new ContextualDiagnostic(owner.blockId(), owner.pageIndex(),
                                    "INLINE_FIELD:" + box.sourceIndex(), owner.rowIndex(), owner.columnIndex(), diagnostic, owner.sourcePath()));
                        }
                        if (field.kind() == InlineField.Kind.REFERENCE_PAGE || field.kind() == InlineField.Kind.REFERENCE_NUMBER) {
                            if (links.size() >= maxLinks) { throw budget(owner, box.sourceIndex()); }
                            double x = owner.origin().x() + line.offsetX() + box.x();
                            double y = owner.origin().y() + line.baselineY() - source.lines().getFirst().baselineY();
                            links.add(new DocumentExtras.Link(owner.pageIndex(), new Rect(x, y, field.width(), step),
                                    target.pageIndex() + offset, new Point(target.point().x(), target.point().y())));
                        }
                    } catch (DocumentLayoutException cause) {
                        throw cause;
                    } catch (IllegalArgumentException cause) {
                        throw owner.failure(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, box.sourceIndex(), "inline field resolution failed", cause);
                    }
                }
                lines.add(new TextLine(line.sourceStart(), line.sourceEnd(), line.offsetX(), line.baselineY(),
                        line.width(), line.runs(), ink, line.images()));
            }
            return new ParagraphLayout(source.width(), source.height(), lines, source.diagnostics());
        }

        private static DocumentLayoutException budget(Owner owner, int source) {
            return owner.failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, source, "inline field generation budget exceeded", null);
        }
    }
}
