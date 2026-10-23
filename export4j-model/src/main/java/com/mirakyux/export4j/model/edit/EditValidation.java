// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model.edit;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.Caption;
import com.mirakyux.export4j.model.CrossReference;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

final class EditValidation {
    private EditValidation() { }
    private record Reference(String target, boolean numbered) { }
    static void validate(SectionedDocument document, List<Note> notes, EditSupport.State state, boolean references) {
        Map<String, Block> targets = new HashMap<>();
        Map<String, Block> noteParents = new HashMap<>();
        Map<String, int[]> lists = new HashMap<>();
        var ids = new HashSet<String>();
        var refs = new ArrayList<Reference>();
        Map<String, String> markers = new HashMap<>();
        long[] counts = { 0, 0 };
        EditSupport.map(document, state, (block, location) -> {
            count(counts, 1, 0, state);
            if (!ids.add(block.id())) { throw new IllegalArgumentException("duplicate block ID in edited document"); }
            if (location.region().equals("BODY")) {
                targets.put(block.id(), block);
                if (location.cellPath().isEmpty() && (block instanceof Paragraph || block instanceof NumberedItem
                        || block instanceof com.mirakyux.export4j.model.BulletItem || block instanceof Caption)) {
                    noteParents.put(block.id(), block);
                }
            }
            var paragraph = DocumentEditor.paragraph(block);
            if (paragraph != null) { text(paragraph, location, counts, refs, markers, state); }
            if (block instanceof CrossReference reference) {
                EditSupport.unicode(reference.prefix(), state); count(counts, 0, reference.prefix().length(), state);
                refs.add(new Reference(reference.targetId(), reference.kind() == CrossReference.Kind.NUMBER));
            }
            if (references && block instanceof NumberedItem item) {
                int[] levels = lists.computeIfAbsent(item.listId(), ignored -> new int[9]);
                for (int level = 0; level < item.level() - 1; level++) {
                    if (levels[level] == 0) { throw new IllegalArgumentException("edited numbered list skips a parent level"); }
                }
                levels[item.level() - 1] = item.restartAt() > 0 ? item.restartAt() : Math.addExact(levels[item.level() - 1], 1);
                Arrays.fill(levels, item.level(), levels.length, 0);
            }
            return List.of(block);
        }, (cell, location) -> {
            count(counts, 1, 0, state);
            if (!cell.hasBlocks()) { text(DocumentEditor.inlineCell(cell), location, counts, refs, markers, state); }
            return cell;
        });
        var noteIds = new HashSet<String>();
        for (var note : notes) {
            state.work(1); EditSupport.unicode(note.text(), state); count(counts, 1, note.text().length(), state);
            if (!noteIds.add(note.id()) || !ids.add(note.id())) { throw new IllegalArgumentException("duplicate note ID"); }
            if (references && !noteParents.containsKey(note.paragraphId())) { throw new IllegalArgumentException("note anchor missing after editing"); }
            var parent = markers.get(note.id());
            if (references && parent != null && !parent.equals(note.paragraphId())) { throw new IllegalArgumentException("note marker owner mismatch"); }
        }
        if (!references) { return; }
        if (!noteIds.containsAll(markers.keySet())) { throw new IllegalArgumentException("note marker has no supplied note"); }
        for (var ref : refs) {
            var target = targets.get(ref.target());
            if (target == null || ref.numbered() && !(target instanceof NumberedItem || target instanceof Caption)) {
                throw new IllegalArgumentException("reference target missing or incompatible after editing");
            }
        }
    }
    private static void text(Paragraph paragraph, ProcessingIssue.Location location, long[] counts,
            List<Reference> refs, Map<String, String> markers, EditSupport.State state) {
        EditSupport.unicode(paragraph.text(), state); count(counts, 0, paragraph.text().length(), state);
        for (var run : paragraph.inlines()) {
            state.work(1); EditSupport.unicode(run.text(), state);
        }
        for (var span : paragraph.fields()) {
            state.work(1); var field = span.field();
            if (field.kind() == InlineField.Kind.NOTE_MARKER) {
                if (!location.region().equals("BODY") || !location.cellPath().isEmpty()
                        || markers.putIfAbsent(field.targetId(), paragraph.id()) != null) {
                    throw new IllegalArgumentException("invalid or duplicate note marker");
                }
            } else if (field.kind() == InlineField.Kind.REFERENCE_PAGE || field.kind() == InlineField.Kind.REFERENCE_NUMBER) {
                refs.add(new Reference(field.targetId(), field.kind() == InlineField.Kind.REFERENCE_NUMBER));
            }
        }
    }
    private static void count(long[] counts, long nodes, long text, EditSupport.State state) {
        counts[0] += nodes; counts[1] += text;
        if (counts[0] > state.limits.maxNodes() || counts[1] > state.limits.maxTextUtf16()) {
            throw new IllegalArgumentException("editing source or candidate budget exceeded");
        }
    }
}
