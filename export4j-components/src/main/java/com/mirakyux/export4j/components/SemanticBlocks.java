// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.Caption;
import com.mirakyux.export4j.model.CrossReference;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;

/** Internal semantic lowering; preserves source block IDs and order. */
final class SemanticBlocks {
    record Result(SectionedDocument document, Map<String, String> labels, Map<String, CrossReference> references, List<Note> notes) { }

    private SemanticBlocks() { }

    static Result compile(SectionedDocument document, List<Note> notes) {
        notes = List.copyOf(notes);
        Note.validate(document, notes);
        Map<String, String> labels = new HashMap<>();
        Map<String, Integer> counters = new HashMap<>();
        Map<String, int[]> lists = new HashMap<>();
        Map<String, CrossReference> refs = new HashMap<>();
        var ids = new HashSet<String>();
        SemanticTree.walk(document.sections().stream().flatMap(section -> section.blocks().stream()).toList(),
                visit -> ids.add(visit.block().id()));
        for (var note : notes) {
            if (!ids.add(note.id())) {
                throw new IllegalArgumentException("note IDs must be globally unique");
            }
        }
        var noteById = notes.stream().collect(java.util.stream.Collectors.toMap(Note::id, note -> note));
        var explicitNotes = new HashSet<String>();
        for (var section : document.sections()) {
            for (var block : section.blocks()) {
                if (!(block instanceof Paragraph p)) { continue; }
                for (var span : p.fields()) {
                    var field = span.field();
                    if (field.kind() != InlineField.Kind.NOTE_MARKER) { continue; }
                    var note = noteById.get(field.targetId());
                    if (note == null || !note.paragraphId().equals(p.id()) || !explicitNotes.add(note.id())) {
                        throw new IllegalArgumentException("inline note marker requires one matching note in its owning paragraph");
                    }
                }
            }
        }
        var attached = new HashSet<String>();
        List<Section> sections = new ArrayList<>();
        for (var section : document.sections()) {
            List<Block> blocks = new ArrayList<>();
            for (var original : section.blocks()) {
                Block block = original;
                if (block instanceof NumberedItem item) {
                    int[] levels = lists.computeIfAbsent(item.listId(), ignored -> new int[9]);
                    for (int level = 0; level < item.level() - 1; level++) {
                        if (levels[level] == 0) { throw new IllegalArgumentException("numbered list cannot skip levels"); }
                    }
                    levels[item.level() - 1] = item.restartAt() > 0 ? item.restartAt() : Math.addExact(levels[item.level() - 1], 1);
                    java.util.Arrays.fill(levels, item.level(), levels.length, 0);
                    String label = java.util.stream.IntStream.range(0, item.level()).mapToObj(level -> Integer.toString(levels[level]))
                            .collect(java.util.stream.Collectors.joining("."));
                    labels.put(item.id(), label);
                    block = prefix(item.paragraph(), label + ". ", indent(item.format(), item.level()), item.markerStyle());
                } else if (block instanceof BulletItem item) {
                    block = prefix(item.paragraph(), item.marker() + " ", indent(item.format(), item.level()), item.markerStyle());
                } else if (block instanceof Caption caption) {
                    int number = Math.addExact(counters.getOrDefault("caption:" + caption.kind(), 0), 1);
                    counters.put("caption:" + caption.kind(), number);
                    String label = caption.kind() + " " + number;
                    labels.put(caption.id(), label);
                    block = new Paragraph(caption.id(), label + ". " + caption.text(), caption.format());
                } else if (block instanceof CrossReference reference) {
                    refs.put(reference.id(), reference);
                    // Reserve wrapped geometry using the final number label or a conservative 10-digit page label.
                    block = new ComponentBlock(reference.id(), context -> {
                        var format = reference.format();
                        int maxLines = 1;
                        if (reference.kind() == CrossReference.Kind.NUMBER) {
                            String label = labels.get(reference.targetId());
                            if (label == null) { throw new IllegalArgumentException("number reference requires a numbered target"); }
                            maxLines = new com.mirakyux.export4j.text.ParagraphLayouter().layout(reference.prefix() + label,
                                    context.fontGroup(), context.width(), style(format)).lines().size();
                        } else {
                            // All ten digits are considered; page/restart numbering is bounded by signed int.
                            for (int digit = 0; digit < 10; digit++) {
                                maxLines = Math.max(maxLines, new com.mirakyux.export4j.text.ParagraphLayouter().layout(
                                        reference.prefix() + Integer.toString(digit).repeat(10), context.fontGroup(), context.width(), style(format)).lines().size());
                            }
                        }
                        return new com.mirakyux.export4j.layout.ComponentLayout(context.width(), maxLines * format.lineHeight(), maxLines, List.of(), List.of());
                    }, reference.format().spaceBefore(), reference.format().spaceAfter());
                }
                for (int index = 0; index < notes.size(); index++) {
                    var note = notes.get(index);
                    if (note.paragraphId().equals(block.id())) {
                        if (!(block instanceof Paragraph p)) {
                            throw new IllegalArgumentException("notes require a paragraph or list/caption anchor");
                        }
                        attached.add(note.id());
                        if (explicitNotes.contains(note.id())) { continue; }
                        var sourceInlines = p.inlines().isEmpty() && !p.images().isEmpty() ? Paragraph.mixed(p.id(), p.content(), p.format()).inlines() : p.inlines();
                        var inlines = new ArrayList<>(sourceInlines.isEmpty() ? List.of(new InlineText(p.text(), InlineStyle.of(p.format().fontSize()))) : sourceInlines);
                        inlines.add(new InlineText("[" + (index + 1) + "]", new InlineStyle("", p.format().fontSize() * 0.65,
                                0x285080, -p.format().fontSize() * 0.3, false, false)));
                        block = new Paragraph(p.id(), inlines.stream().map(InlineText::text).collect(java.util.stream.Collectors.joining()), p.format(), inlines, p.images(), p.fields());
                    }
                }
                blocks.add(block);
            }
            sections.add(new Section(section.id(), section.pageSettings(), blocks, section.firstPageNumber()));
        }
        if (attached.size() != notes.size()) {
            throw new IllegalArgumentException("note refers to an unknown paragraph");
        }
        var last = sections.removeLast();
        var tail = new ArrayList<>(last.blocks());
        for (int index = 0; index < notes.size(); index++) {
            var note = notes.get(index);
            if (note.kind() == Note.Kind.ENDNOTE) {
                tail.add(new Paragraph(note.id(), "[" + (index + 1) + "] " + note.text(), ParagraphFormat.of(10)));
            }
        }
        sections.add(new Section(last.id(), last.pageSettings(), tail, last.firstPageNumber()));
        return new Result(new SectionedDocument(document.metadata(), sections, document.navigation()), Map.copyOf(labels), Map.copyOf(refs), notes);
    }

    static ParagraphStyle style(ParagraphFormat format) {
        return new ParagraphStyle(format.fontSize(), format.lineHeight(), format.firstLineIndent(), format.subsequentLineIndent(), switch (format.alignment()) {
            case LEFT -> TextAlignment.LEFT; case CENTER -> TextAlignment.CENTER; case RIGHT -> TextAlignment.RIGHT; case JUSTIFY -> TextAlignment.JUSTIFY;
        }, format.lineBreakPolicy());
    }

    private static ParagraphFormat indent(ParagraphFormat f, int level) {
        double indent = (level - 1) * f.fontSize() * 1.5;
        return new ParagraphFormat(f.fontSize(), f.lineHeight(), f.firstLineIndent() + indent,
                f.subsequentLineIndent() + indent, f.alignment(), f.spaceBefore(), f.spaceAfter(), f.keepTogether(), f.pagination(), f.lineBreakPolicy(), f.rotation());
    }

    private static Paragraph prefix(Paragraph source, String prefix, ParagraphFormat format, InlineStyle markerStyle) {
        var bodyStyle = InlineStyle.of(format.fontSize());
        if (source.inlines().isEmpty() && markerStyle.equals(bodyStyle)) {
            return new Paragraph(source.id(), prefix + source.text(), format);
        }
        var runs = new ArrayList<InlineText>();
        runs.add(new InlineText(prefix, markerStyle));
        if (source.inlines().isEmpty()) { runs.add(new InlineText(source.text(), bodyStyle)); }
        else { runs.addAll(source.inlines()); }
        return new Paragraph(source.id(), runs, format);
    }
}
