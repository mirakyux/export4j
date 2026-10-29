// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Comment;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Contents;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Control;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Drawing;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Picture;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Point;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Range;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Revision;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.RevisionKind;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.TextBox;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

final class DocxSemanticSupport {
    private DocxSemanticSupport() { }
    /** Native side stories share the same source/resource budgets as the ordinary body. */
    static void budget(DocxSemanticDocument document, DocxLimits limits) throws java.io.IOException {
        var sources = new ArrayList<List<Block>>(); var bands = new HashSet<PageRegion>();
        for (var section : document.body().sections()) {
            sources.add(section.blocks());
            for (var band : java.util.stream.Stream.concat(section.pageRegions().headers().stream(), section.pageRegions().footers().stream()).toList()) { if (bands.add(band)) { sources.add(band.blocks()); } }
        }
        long units = document.drawings().size() + (long)document.comments().size() + document.revisions().size() + document.controls().size() + document.contents().size();
        for (var drawing : document.drawings()) { units += drawing.placement().polygon().size(); if (drawing.content() instanceof TextBox box) { sources.add(box.blocks()); } }
        for (var comment : document.comments()) { sources.add(comment.blocks()); }
        for (var control : document.controls()) { units += control.items().size(); }
        if (units > limits.maxBlocks()) { throw new java.io.IOException("DOCX cumulative native feature budget exceeded"); }
        var sourceBudget = new DocxCells.Budget(limits); var ids = new HashSet<String>(); var images = new HashMap<String, ImageResource>();
        long bytes = 0; long pixels = 0;
        for (var blocks : sources) {
            var visits = new ArrayList<SemanticTree.Visit>(); SemanticTree.walk(blocks, visits::add);
            for (var visit : visits) {
                sourceBudget.node(); var block = visit.block(); if (!ids.add(block.id())) { throw new java.io.IOException("duplicate native source block ID"); }
                var p = paragraph(block);
                if (p != null) { sourceBudget.text(p.text()); for (var image : p.images()) { images.putIfAbsent(image.image().resource().id(), image.image().resource()); } }
                else if (block instanceof Table table) { sourceBudget.slots(table.rows().size(), table.columns().size());
                    for (var row : table.rows()) { for (var cell : row.cells()) { if (!cell.hasBlocks()) { sourceBudget.text(cell.text()); for (var image : cell.images()) { images.putIfAbsent(image.image().resource().id(), image.image().resource()); } } } }
                }
            }
        }
        for (var image : images.values()) { bytes += image.byteSize(); pixels += (long)image.pixelWidth() * image.pixelHeight(); }
        if (bytes > 64L * 1024 * 1024 || pixels > 50_000_000) { throw new java.io.IOException("DOCX cumulative native image budget exceeded"); }
    }
    static Map<String, Paragraph> paragraphs(SectionedDocument body, List<Drawing> drawings) {
        var result = new LinkedHashMap<String, Paragraph>();
        for (var section : body.sections()) {
            collect(section.blocks(), result);
            for (var band : java.util.stream.Stream.concat(section.pageRegions().headers().stream(), section.pageRegions().footers().stream()).distinct().toList()) { collect(band.blocks(), result); }
        }
        for (var drawing : drawings) { if (drawing.content() instanceof TextBox box) { collect(box.blocks(), result); } }
        return result;
    }
    static void collect(List<Block> blocks, Map<String, Paragraph> output) {
        SemanticTree.walk(blocks, visit -> {
            var p = paragraph(visit.block());
            if (p != null) { var prior = output.putIfAbsent(p.id(), p); if (prior != null && prior != p && !prior.equals(p)) { throw new IllegalArgumentException("duplicate Word paragraph ID"); } }
        });
    }
    static Paragraph paragraph(Block block) {
        if (block instanceof Paragraph p) { return p; }
        if (block instanceof Heading h) { return h.paragraph(); }
        if (block instanceof BulletItem b) { return b.paragraph(); }
        if (block instanceof NumberedItem n) { return n.paragraph(); }
        return null;
    }
    static void validate(SectionedDocument body, List<Drawing> drawings, List<Comment> comments, List<Revision> revisions, List<Control> controls, List<Contents> contents) {
        var paragraphs = paragraphs(body, drawings); var order = new ArrayList<>(paragraphs.keySet());
        if ((long)drawings.size() + comments.size() + revisions.size() + controls.size() + contents.size() > 100_000) { throw new IllegalArgumentException("Word semantic feature budget exceeded"); }
        var ids = new HashSet<String>(); var picturePoints = new HashSet<Point>();
        for (var drawing : drawings) {
            id(ids, "drawing/" + drawing.id()); point(drawing.anchor(), paragraphs);
            if (drawing.content() instanceof Picture picture) {
                var p = paragraphs.get(drawing.anchor().paragraphId());
                if (!picturePoints.add(drawing.anchor()) || p.images().stream().noneMatch(span -> span.sourceIndex() == drawing.anchor().utf16() && span.image().equals(picture.image()))) { throw new IllegalArgumentException("Word picture must reference its actual inline image marker"); }
            }
        }
        for (var comment : comments) { id(ids, "comment/" + comment.id()); range(comment.range(), paragraphs, order); }
        var moves = new HashMap<String, EnumSet<RevisionKind>>();
        for (var revision : revisions) {
            id(ids, "revision/" + revision.id()); range(revision.range(), paragraphs, order);
            if (revision.kind() == RevisionKind.PARAGRAPH_FORMAT) {
                int start = order.indexOf(revision.range().start().paragraphId()); int end = order.indexOf(revision.range().end().paragraphId());
                for (String paragraphId : order.subList(start, end + 1)) {
                    if (paragraphs.get(paragraphId).format().fontSize() != revision.priorParagraphFormat().fontSize()) { throw new IllegalArgumentException("paragraph format revisions cannot change font size; use a run format revision"); }
                }
            }
            if (revision.kind() == RevisionKind.MOVE_FROM || revision.kind() == RevisionKind.MOVE_TO) { moves.computeIfAbsent(revision.moveId(), ignored -> EnumSet.noneOf(RevisionKind.class)).add(revision.kind()); }
        }
        for (var pair : moves.values()) { if (pair.size() != 2) { throw new IllegalArgumentException("unpaired Word move revision"); } }
        for (var control : controls) {
            id(ids, "control/" + control.id()); range(control.range(), paragraphs, order);
            if (control.kind() == DocxSemanticDocument.ControlKind.LEGACY_DROP_DOWN) {
                var start = control.range().start(); var end = control.range().end();
                if (!start.paragraphId().equals(end.paragraphId()) || control.items().isEmpty()) { throw new IllegalArgumentException("legacy dropdown requires inline content and available items"); }
                String selected = paragraphs.get(start.paragraphId()).text().substring(start.utf16(), end.utf16());
                if (control.items().stream().noneMatch(item -> item.label().equals(selected))) { throw new IllegalArgumentException("legacy dropdown current content must match an available item"); }
            }
            if (control.kind() == DocxSemanticDocument.ControlKind.PICTURE) {
                var start = control.range().start(); var end = control.range().end();
                if (!start.paragraphId().equals(end.paragraphId()) || end.utf16() != start.utf16() + 1
                        || paragraphs.get(start.paragraphId()).images().stream().noneMatch(image -> image.sourceIndex() == start.utf16())) {
                    throw new IllegalArgumentException("Word picture content controls require one actual inline image");
                }
                if (drawings.stream().anyMatch(drawing -> drawing.anchor().equals(start) && drawing.placement().wrap() != DocxSemanticDocument.Wrap.INLINE)) {
                    throw new IllegalArgumentException("Word picture content controls cannot contain floating drawings");
                }
            }
        }
        for (var toc : contents) { id(ids, "contents/" + toc.id()); range(toc.range(), paragraphs, order); }
    }
    private static void id(Set<String> ids, String id) { if (!ids.add(id)) { throw new IllegalArgumentException("duplicate Word semantic feature ID"); } }
    static void point(Point at, Map<String, Paragraph> paragraphs) {
        var p = paragraphs.get(at.paragraphId());
        if (p == null || at.utf16() > p.text().length() || split(p.text(), at.utf16())) { throw new IllegalArgumentException("Word anchor is missing or splits UTF-16 source: " + at.paragraphId() + ":" + at.utf16() + " (source length " + (p == null ? "missing" : p.text().length()) + ")"); }
    }
    private static boolean split(String text, int at) { return at > 0 && at < text.length() && Character.isHighSurrogate(text.charAt(at - 1)) && Character.isLowSurrogate(text.charAt(at)); }
    static void range(Range range, Map<String, Paragraph> paragraphs, List<String> order) {
        point(range.start(), paragraphs); point(range.end(), paragraphs);
        int a = order.indexOf(range.start().paragraphId()); int b = order.indexOf(range.end().paragraphId());
        if (a > b || a == b && range.start().utf16() > range.end().utf16()) { throw new IllegalArgumentException("reversed Word range"); }
    }
    static SectionedDocument map(SectionedDocument source, UnaryOperator<Paragraph> mapper) {
        return new SectionedDocument(source.metadata(), source.sections().stream().map(s -> new Section(s.id(), s.pageSettings(), mapBlocks(s.blocks(), mapper), s.firstPageNumber(),
                s.pageRegions().map((name, region) -> new PageRegion(region.height(), region.gap(), mapBlocks(region.blocks(), mapper))))).toList(), source.navigation());
    }
    static List<Block> mapBlocks(List<Block> source, UnaryOperator<Paragraph> mapper) {
        var result = new ArrayList<Block>();
        for (var block : source) {
            ExecutionContext.checkpoint();
            if (block instanceof Table t) {
                result.add(new Table(t.id(), t.columns(), t.rows().stream().map(row -> row.cells().isEmpty() ? TableRow.covered() : new TableRow(row.cells().stream().map(c -> c.hasBlocks() ? c.toBuilder().blocks(mapBlocks(c.blocks(), mapper)).build() : c).toList())).toList(), t.headerRows(), t.format()));
                continue;
            }
            var original = paragraph(block);
            if (original == null) { result.add(block); continue; }
            var p = mapper.apply(original);
            if (block instanceof Heading h && !p.text().isBlank()) { result.add(new Heading(p.id(), p.text(), h.level(), p.format(), p.inlines())); }
            else if (block instanceof BulletItem b) { result.add(new BulletItem(p.id(), p.text(), b.marker(), b.level(), p.format(), p.inlines(), b.markerStyle())); }
            else if (block instanceof NumberedItem n) { result.add(new NumberedItem(p.id(), n.listId(), p.text(), n.restartAt(), p.format(), n.level(), p.inlines(), n.markerStyle())); }
            else { result.add(p); }
        }
        return List.copyOf(result);
    }
    static void issue(List<ProcessingIssue> issues, ProcessingPolicy policy, String stage, String feature, Range range, String reason, String effect, ProcessingPolicy.Treatment treatment) {
        var rule = policy.resolve("DOCX", feature, stage);
        var at = range == null ? new ProcessingIssue.Location("", "DOCX_SEMANTICS", "", List.of(), -1, -1)
                : new ProcessingIssue.Location("", "DOCX_SEMANTICS", range.start().paragraphId(), List.of(), range.start().utf16(), range.start().paragraphId().equals(range.end().paragraphId()) ? range.end().utf16() : range.start().utf16());
        var issue = new ProcessingIssue(stage, "DOCX", stage.equals("PROJECT") ? "MODEL" : "DOCX", feature, stage, at, reason, rule.action(), rule.treatment(), List.of(effect));
        if (rule.action() == ProcessingPolicy.Action.ERROR) { throw new ProcessingException(issue); }
        if (rule.treatment() != treatment) { throw new IllegalArgumentException("unavailable DOCX semantic treatment for " + feature + ": " + rule.treatment()); }
        if (issues.size() >= 10_000) { throw new IllegalArgumentException("Word semantic report budget exceeded"); }
        issues.add(issue);
    }
}
