// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Comment;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Contents;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Control;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.ControlKind;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Drawing;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Point;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Range;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Revision;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.RevisionKind;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.TextBox;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** Sequential native semantic edits. Every published result revalidates all UTF-16 anchors.
 * Revision acceptance removes rejected source content from the model; no hidden original XML exists.
 */
public final class DocxSemanticEditor {
    private DocxSemanticEditor() { }
    public static Request edit(DocxSemanticDocument source) { return new Request(Objects.requireNonNull(source, "source")); }
    public record Result(DocxSemanticDocument document, List<String> changes, ProcessingReport report) {
        public Result { Objects.requireNonNull(document, "document"); changes = List.copyOf(changes); Objects.requireNonNull(report, "report"); }
    }
    @FunctionalInterface private interface Operation { DocxSemanticDocument apply(DocxSemanticDocument input, State state); }
    private static final class State {
        final ProcessingPolicy policy; final List<ProcessingIssue> issues = new ArrayList<>(); final List<String> changes = new ArrayList<>();
        State(ProcessingPolicy policy) { this.policy = policy; }
    }
    public static final class Request {
        private final DocxSemanticDocument source; private final List<Operation> operations = new ArrayList<>();
        private ProcessingPolicy policy = ProcessingPolicy.STRICT; private ExecutionOptions execution = ExecutionOptions.DEFAULT;
        private Request(DocxSemanticDocument source) { this.source = source; }
        public Request policy(ProcessingPolicy value) { policy = Objects.requireNonNull(value, "policy"); return this; }
        public Request execution(ExecutionOptions value) { execution = Objects.requireNonNull(value, "execution"); return this; }
        private Request add(String name, Operation operation) {
            if (operations.size() >= 10_000) { throw new IllegalArgumentException("native edit operation budget exceeded"); }
            operations.add((doc, state) -> { ExecutionContext.checkpoint(); var value = operation.apply(doc, state); state.changes.add(name); return value; }); return this;
        }
        public Request replaceText(String paragraphId, int startUtf16, int endUtf16, String replacement) {
            Objects.requireNonNull(replacement, "replacement"); var range = Range.of(paragraphId, startUtf16, endUtf16);
            return add("REPLACE_TEXT", (doc, state) -> replace(doc, range, replacement, state, false));
        }
        public Request drawing(Drawing drawing) { Objects.requireNonNull(drawing, "drawing"); return add("SET_DRAWING", (doc, state) -> copy(doc, replaceById(doc.drawings(), drawing, Drawing::id), doc.comments(), doc.revisions(), doc.controls(), doc.contents())); }
        public Request comment(Comment comment) { Objects.requireNonNull(comment, "comment"); return add("SET_COMMENT", (doc, state) -> copy(doc, doc.drawings(), replaceById(doc.comments(), comment, Comment::id), doc.revisions(), doc.controls(), doc.contents())); }
        public Request removeComment(String id) { return add("REMOVE_COMMENT", (doc, state) -> { require(doc.comments(), id, Comment::id); return copy(doc, doc.drawings(), doc.comments().stream().filter(v -> !v.id().equals(id)).toList(), doc.revisions(), doc.controls(), doc.contents()); }); }
        public Request revision(Revision value) { Objects.requireNonNull(value, "revision"); return add("SET_REVISION", (doc, state) -> copy(doc, doc.drawings(), doc.comments(), replaceById(doc.revisions(), value, Revision::id), doc.controls(), doc.contents())); }
        public Request control(Control value) { Objects.requireNonNull(value, "control"); return add("SET_CONTROL", (doc, state) -> copy(doc, doc.drawings(), doc.comments(), doc.revisions(), replaceById(doc.controls(), value, Control::id), doc.contents())); }
        public Request contents(Contents value) { Objects.requireNonNull(value, "contents"); return add("SET_CONTENTS", (doc, state) -> copy(doc, doc.drawings(), doc.comments(), doc.revisions(), doc.controls(), replaceById(doc.contents(), value, Contents::id))); }
        public Request setControlText(String id, String value) {
            Objects.requireNonNull(value, "value");
            return add("SET_CONTROL_VALUE", (doc, state) -> {
                var control = require(doc.controls(), id, Control::id); String text = value;
                if (!control.options().enabled()) { throw new IllegalArgumentException("control is disabled"); }
                if (control.options().maxLength() > 0 && value.length() > control.options().maxLength()) { throw new IllegalArgumentException("control input exceeds maxLength"); }
                if (control.kind() == ControlKind.PICTURE || control.kind() == ControlKind.GROUP || control.kind() == ControlKind.REPEATING_SECTION || control.kind() == ControlKind.REPEATING_ITEM) { throw new IllegalArgumentException("use typed content editing for structural control"); }
                if (control.kind() == ControlKind.DROP_DOWN || control.kind() == ControlKind.LEGACY_DROP_DOWN) { text = control.items().stream().filter(i -> i.value().equals(value) || i.label().equals(value)).findFirst().orElseThrow(() -> new IllegalArgumentException("control value is not an available option")).label(); }
                return replace(doc, control.range(), text, state, false);
            });
        }
        public Request setChecked(String id, boolean checked) {
            return add("SET_CHECKED", (doc, state) -> {
                var c = require(doc.controls(), id, Control::id);
                if (c.kind() != ControlKind.CHECKBOX && c.kind() != ControlKind.LEGACY_CHECKBOX) { throw new IllegalArgumentException("control is not a checkbox"); }
                if (!c.options().enabled()) { throw new IllegalArgumentException("control is disabled"); }
                var changed = replace(doc, c.range(), checked ? "\u2612" : "\u2610", state, false);
                var rebased = require(changed.controls(), id, Control::id);
                var value = new Control(c.id(), c.kind(), rebased.range(), c.tag(), c.title(), checked, c.items(), c.dateFormat(), c.language(), c.blockLevel(), c.options());
                return copy(changed, changed.drawings(), changed.comments(), changed.revisions(), replaceById(changed.controls(), value, Control::id), changed.contents());
            });
        }
        public Request acceptRevision(String id) { return decide(id, true); }
        public Request rejectRevision(String id) { return decide(id, false); }
        private Request decide(String id, boolean accept) {
            return add(accept ? "ACCEPT_REVISION" : "REJECT_REVISION", (doc, state) -> {
                var selected = require(doc.revisions(), id, Revision::id);
                var related = doc.revisions().stream().filter(r -> r.id().equals(id) || !selected.moveId().isEmpty() && selected.moveId().equals(r.moveId())).toList();
                var value = copy(doc, doc.drawings(), doc.comments(), doc.revisions().stream().filter(r -> !related.contains(r)).toList(), doc.controls(), doc.contents());
                var order = new ArrayList<>(DocxSemanticSupport.paragraphs(doc.body(), doc.drawings()).keySet());
                var descending = new ArrayList<>(related); descending.sort(Comparator.comparingInt((Revision r) -> order.indexOf(r.range().start().paragraphId())).thenComparingInt(r -> r.range().start().utf16()).reversed());
                for (var revision : descending) {
                    boolean delete = accept ? revision.kind() == RevisionKind.DELETE || revision.kind() == RevisionKind.MOVE_FROM
                            : revision.kind() == RevisionKind.INSERT || revision.kind() == RevisionKind.MOVE_TO;
                    if (delete) { value = replace(value, revision.range(), "", state, true); }
                    else if (!accept && revision.kind() == RevisionKind.RUN_FORMAT) { value = style(value, revision.range(), revision.priorStyle(), null); }
                    else if (!accept && revision.kind() == RevisionKind.PARAGRAPH_FORMAT) { value = style(value, revision.range(), null, revision.priorParagraphFormat()); }
                }
                return value;
            });
        }
        /** Rebuilds cached headings. Page numbers must come from actual pagination supplied by caller. */
        public Request updateContents(String id, Map<String, Integer> headingPages) {
            var pages = Map.copyOf(headingPages);
            return add("UPDATE_CONTENTS", (doc, state) -> {
                var toc = require(doc.contents(), id, Contents::id); var lines = new ArrayList<String>();
                for (var section : doc.body().sections()) { for (var block : section.blocks()) { if (block instanceof Heading h && h.level() >= toc.firstLevel() && h.level() <= toc.lastLevel()) {
                    if (toc.pageNumbers() && (!pages.containsKey(h.id()) || pages.get(h.id()) < 1)) { throw new IllegalArgumentException("actual page number missing for TOC heading: " + h.id()); }
                    lines.add(h.text() + (toc.pageNumbers() ? "\t" + pages.get(h.id()) : ""));
                } } }
                var updated = replace(doc, toc.range(), String.join("\n", lines), state, false);
                var current = require(updated.contents(), id, Contents::id);
                var cached = new Contents(current.id(), current.range(), current.firstLevel(), current.lastLevel(), current.hyperlinks(), current.pageNumbers(), false);
                return copy(updated, updated.drawings(), updated.comments(), updated.revisions(), updated.controls(), replaceById(updated.contents(), cached, Contents::id));
            });
        }
        public Result execute() throws IOException { return ExecutionContext.execute(execution, () -> { var state = new State(policy); var value = source; for (var op : List.copyOf(operations)) { value = op.apply(value, state); } return new Result(value, state.changes, new ProcessingReport(ProcessingReport.Phase.ACTUAL, state.issues)); }); }
    }
    private static <T> T require(List<T> values, String id, java.util.function.Function<T, String> key) { return values.stream().filter(v -> key.apply(v).equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("native semantic ID missing: " + id)); }
    private static <T> List<T> replaceById(List<T> values, T value, java.util.function.Function<T, String> key) { var result = new ArrayList<T>(); boolean found = false; for (var prior : values) { if (key.apply(prior).equals(key.apply(value))) { result.add(value); found = true; } else { result.add(prior); } } if (!found) { result.add(value); } return List.copyOf(result); }
    private static DocxSemanticDocument copy(DocxSemanticDocument doc, List<Drawing> drawings, List<Comment> comments, List<Revision> revisions, List<Control> controls, List<Contents> contents) { return new DocxSemanticDocument(doc.body(), drawings, comments, revisions, controls, contents); }
    private static DocxSemanticDocument replace(DocxSemanticDocument doc, Range range, String replacement, State state, boolean intentionalRemoval) {
        var paragraphs = DocxSemanticSupport.paragraphs(doc.body(), doc.drawings()); var order = new ArrayList<>(paragraphs.keySet()); DocxSemanticSupport.range(range, paragraphs, order);
        for (var group : doc.controls()) {
            if (group.kind() != ControlKind.GROUP || !overlaps(group.range(), range, order)) { continue; }
            boolean editableChild = doc.controls().stream().anyMatch(control -> control.kind() != ControlKind.GROUP && control.kind() != ControlKind.PICTURE
                    && control.kind() != ControlKind.REPEATING_SECTION && control.kind() != ControlKind.REPEATING_ITEM
                    && contains(group.range(), control.range(), order) && contains(control.range(), range, order));
            if (!editableChild) { throw new IllegalArgumentException("group content is locked outside its editable child controls"); }
        }
        int first = order.indexOf(range.start().paragraphId()); int last = order.indexOf(range.end().paragraphId());
        var value = doc;
        for (int index = last; index >= first; index--) {
            String id = order.get(index); var p = DocxSemanticSupport.paragraphs(value.body(), value.drawings()).get(id);
            int a = index == first ? range.start().utf16() : 0; int b = index == last ? range.end().utf16() : p.text().length();
            value = replaceOne(value, id, a, b, index == first ? replacement : "", state, intentionalRemoval);
        }
        return value;
    }
    private static int compare(Point first, Point second, List<String> order) {
        int paragraphs = Integer.compare(order.indexOf(first.paragraphId()), order.indexOf(second.paragraphId()));
        return paragraphs == 0 ? Integer.compare(first.utf16(), second.utf16()) : paragraphs;
    }
    private static boolean contains(Range outer, Range inner, List<String> order) { return compare(outer.start(), inner.start(), order) <= 0 && compare(inner.end(), outer.end(), order) <= 0; }
    private static boolean overlaps(Range first, Range second, List<String> order) {
        if (second.start().equals(second.end())) { return compare(first.start(), second.start(), order) <= 0 && compare(second.end(), first.end(), order) <= 0; }
        return compare(first.start(), second.end(), order) < 0 && compare(second.start(), first.end(), order) < 0;
    }
    private static DocxSemanticDocument replaceOne(DocxSemanticDocument doc, String id, int start, int end, String replacement, State state, boolean intentionalRemoval) {
        var p = DocxSemanticSupport.paragraphs(doc.body(), doc.drawings()).get(id);
        if (replacement.length() > 4_000_000 || (long)p.text().length() - (end - start) + replacement.length() > 4_000_000) { throw new IllegalArgumentException("native edit source budget exceeded"); }
        var drawings = new ArrayList<Drawing>();
        for (var drawing : doc.drawings()) {
            boolean removed = drawing.anchor().paragraphId().equals(id) && drawing.anchor().utf16() >= start && drawing.anchor().utf16() < end;
            if (removed) { if (!intentionalRemoval) { DocxSemanticSupport.issue(state.issues, state.policy, "EDIT", "DRAWING_REMOVED", Range.of(id, start, end), "replacement covers a native drawing anchor", "DRAWING_AND_TEXTBOX_CONTENT_REMOVED", ProcessingPolicy.Treatment.DISCARD); } continue; }
            var content = drawing.content(); if (content instanceof TextBox box) { content = new TextBox(DocxSemanticSupport.mapBlocks(box.blocks(), q -> q.id().equals(id) ? editParagraph(q, start, end, replacement) : q), box.width(), box.height(), box.insetTop(), box.insetRight(), box.insetBottom(), box.insetLeft(), box.verticalAlignment(), box.fillRgb(), box.borderRgb(), box.borderWidth(), box.transform()); }
            drawings.add(new Drawing(drawing.id(), rebase(drawing.anchor(), id, start, end, replacement.length(), false), drawing.placement(), content));
        }
        UnaryOperator<Range> mapper = r -> new Range(rebase(r.start(), id, start, end, replacement.length(), false), rebase(r.end(), id, start, end, replacement.length(), true));
        return new DocxSemanticDocument(DocxSemanticSupport.map(doc.body(), q -> q.id().equals(id) ? editParagraph(q, start, end, replacement) : q), drawings,
                doc.comments().stream().map(c -> new Comment(c.id(), mapper.apply(c.range()), c.author(), c.initials(), c.date(), c.blocks())).toList(),
                doc.revisions().stream().map(r -> new Revision(r.id(), r.kind(), mapper.apply(r.range()), r.author(), r.date(), r.moveId(), r.priorStyle(), r.priorParagraphFormat())).toList(),
                doc.controls().stream().map(c -> new Control(c.id(), c.kind(), mapper.apply(c.range()), c.tag(), c.title(), c.checked(), c.items(), c.dateFormat(), c.language(), c.blockLevel(), c.options())).toList(),
                doc.contents().stream().map(c -> new Contents(c.id(), mapper.apply(c.range()), c.firstLevel(), c.lastLevel(), c.hyperlinks(), c.pageNumbers(), c.dirty())).toList());
    }
    private static Point rebase(Point p, String id, int a, int b, int length, boolean right) {
        if (!p.paragraphId().equals(id) || p.utf16() < a) { return p; }
        int at = p.utf16(); int mapped = at > b ? at + length - (b - a) : at == b && b > a ? a + length : at == a && !right ? a : a + (right ? length : 0);
        return new Point(id, mapped);
    }
    private static Paragraph editParagraph(Paragraph p, int start, int end, String value) {
        var contents = new ArrayList<InlineContent>(); var tail = new ArrayList<InlineContent>(); InlineStyle insertion = InlineStyle.of(p.format().fontSize()); int offset = 0;
        for (var part : p.content()) {
            int len = part instanceof InlineText text ? text.text().length() : 1;
            if (part instanceof InlineText text) {
                if (offset <= start && start <= offset + len) { insertion = text.style(); }
                if (offset < start) { contents.add(new InlineText(text.text().substring(0, Math.min(len, start - offset)), text.style(), text.hyperlink())); }
                if (offset + len > end) { tail.add(new InlineText(text.text().substring(Math.max(0, end - offset)), text.style(), text.hyperlink())); }
            } else { if (offset < start) { contents.add(part); } if (offset >= end) { tail.add(part); } }
            offset += len;
        }
        if (!value.isEmpty()) { contents.add(new InlineText(value, insertion)); } contents.addAll(tail); return Paragraph.mixed(p.id(), contents, p.format());
    }
    private static DocxSemanticDocument style(DocxSemanticDocument doc, Range range, InlineStyle prior, ParagraphFormat paragraphFormat) {
        var keys = new ArrayList<>(DocxSemanticSupport.paragraphs(doc.body(), doc.drawings()).keySet()); int a = keys.indexOf(range.start().paragraphId()); int b = keys.indexOf(range.end().paragraphId());
        UnaryOperator<Paragraph> mapper = p -> {
            int at = keys.indexOf(p.id()); if (at < a || at > b) { return p; }
            if (paragraphFormat != null) { return new Paragraph(p.id(), p.text(), paragraphFormat, p.inlines(), p.images(), p.fields()); }
            int start = at == a ? range.start().utf16() : 0; int end = at == b ? range.end().utf16() : p.text().length();
            var values = new ArrayList<InlineContent>(); int offset = 0;
            for (var content : p.content()) { int len = content instanceof InlineText text ? text.text().length() : 1;
                if (content instanceof InlineText text) { int x = Math.max(0, start - offset); int y = Math.min(len, end - offset);
                    if (x >= y) { values.add(text); } else { if (x > 0) { values.add(new InlineText(text.text().substring(0, x), text.style(), text.hyperlink())); } values.add(new InlineText(text.text().substring(x, y), prior, text.hyperlink())); if (y < len) { values.add(new InlineText(text.text().substring(y), text.style(), text.hyperlink())); } }
                } else { values.add(content); } offset += len;
            }
            return Paragraph.mixed(p.id(), values, p.format());
        };
        var drawings = doc.drawings().stream().map(d -> d.content() instanceof TextBox box ? new Drawing(d.id(), d.anchor(), d.placement(), new TextBox(DocxSemanticSupport.mapBlocks(box.blocks(), mapper), box.width(), box.height(), box.insetTop(), box.insetRight(), box.insetBottom(), box.insetLeft(), box.verticalAlignment(), box.fillRgb(), box.borderRgb(), box.borderWidth(), box.transform())) : d).toList();
        return new DocxSemanticDocument(DocxSemanticSupport.map(doc.body(), mapper), drawings, doc.comments(), doc.revisions(), doc.controls(), doc.contents());
    }
}
