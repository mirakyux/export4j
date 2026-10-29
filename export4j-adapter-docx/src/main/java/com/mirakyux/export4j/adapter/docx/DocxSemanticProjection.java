// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Picture;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Point;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Range;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.TextBox;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Transform;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Wrap;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/** Explicit, measured native-to-portable projection. Every lost behavior has an actual issue. */
final class DocxSemanticProjection {
    private DocxSemanticProjection() { }
    static DocumentConversion.Input project(DocxSemanticDocument original, ProcessingPolicy policy) throws IOException {
        var issues = new ArrayList<ProcessingIssue>(); var doc = original;
        for (var revision : original.revisions()) { issue(issues, policy, "REVISIONS", revision.range(), "portable model has no tracked-review behavior", "REVISION_ACCEPTED_AND_REVIEW_METADATA_REMOVED", ProcessingPolicy.Treatment.DEGRADE); }
        var decided = new HashSet<String>();
        for (var revision : original.revisions()) {
            if (!decided.add(revision.id())) { continue; }
            if (!revision.moveId().isEmpty()) { for (var r : original.revisions()) { if (r.moveId().equals(revision.moveId())) { decided.add(r.id()); } } }
            doc = doc.edit().acceptRevision(revision.id()).execute().document();
        }
        for (var comment : doc.comments()) { issue(issues, policy, "COMMENTS", comment.range(), "portable model has no native Word comment range", "COMMENT_TEXT_AUTHOR_DATE_AND_RANGE_REMOVED", ProcessingPolicy.Treatment.DISCARD); }
        for (var control : doc.controls()) { issue(issues, policy, "CONTENT_CONTROLS", control.range(), "portable model has no native control or form behavior", "CONTROL_BEHAVIOR_AND_CONSTRAINTS_REMOVED_VALUE_CONTENT_RETAINED", ProcessingPolicy.Treatment.DEGRADE); }
        for (var toc : doc.contents()) { issue(issues, policy, "TABLE_OF_CONTENTS", toc.range(), "native TOC declaration needs Word field evaluation", "TOC_BECOMES_CACHED_CONTENT_WITHOUT_NATIVE_UPDATE_BEHAVIOR", ProcessingPolicy.Treatment.DEGRADE); }
        var images = new HashMap<Point, InlineImage>(); var boxes = new HashMap<String, List<Block>>();
        for (var drawing : doc.drawings()) {
            var at = new Range(drawing.anchor(), drawing.anchor());
            if (drawing.content() instanceof Picture picture) {
                if (drawing.placement().wrap() != Wrap.INLINE) { issue(issues, policy, "FLOATING_DRAWING", at, "portable image is inline and has no floating placement", "POSITION_WRAP_LAYER_AND_OVERLAP_REPLACED_WITH_INLINE_IMAGE", ProcessingPolicy.Treatment.DEGRADE); }
                if (!picture.transform().equals(Transform.NONE)) { issue(issues, policy, "IMAGE_TRANSFORM", at, "portable images have no editable crop/flip/rotation properties", "VISIBLE_TRANSFORM_RASTERIZED_NATIVE_TRANSFORM_EDITABILITY_REMOVED", ProcessingPolicy.Treatment.DEGRADE); images.put(drawing.anchor(), rasterize(picture)); }
            } else {
                var box = (TextBox)drawing.content(); issue(issues, policy, "TEXTBOX", at, "portable blocks have no floating textbox frame", "TEXTBOX_BLOCKS_INSERTED_AFTER_ANCHOR_PARAGRAPH_FRAME_POSITION_AND_ROTATION_REMOVED", ProcessingPolicy.Treatment.DEGRADE);
                boxes.computeIfAbsent(drawing.anchor().paragraphId(), ignored -> new ArrayList<>()).addAll(box.blocks());
            }
        }
        var mapped = DocxSemanticSupport.map(doc.body(), p -> portableParagraph(p, images, issues, policy));
        var sections = mapped.sections().stream().map(s -> new Section(s.id(), s.pageSettings(), insertBoxes(s.blocks(), boxes, images, new HashSet<>(), issues, policy), s.firstPageNumber(), s.pageRegions().map((name, band) -> new PageRegion(band.height(), band.gap(), insertBoxes(band.blocks(), boxes, images, new HashSet<>(), issues, policy))))).toList();
        return new DocumentConversion.Input(new SectionedDocument(mapped.metadata(), sections, mapped.navigation()), List.of(), new ProcessingReport(ProcessingReport.Phase.ACTUAL, issues));
    }
    private static Paragraph portableParagraph(Paragraph paragraph, Map<Point, InlineImage> images, List<ProcessingIssue> issues, ProcessingPolicy policy) {
        int tab = paragraph.text().indexOf('\t');
        if (tab >= 0) { issue(issues, policy, "TAB_LAYOUT", Range.of(paragraph.id(), tab, paragraph.text().lastIndexOf('\t') + 1), "portable text has no Word tab-stop layout", "TAB_STOP_LAYOUT_REPLACED_WITH_SINGLE_SPACES", ProcessingPolicy.Treatment.DEGRADE); }
        var values = new ArrayList<InlineContent>(); int offset = 0;
        for (var item : paragraph.content()) {
            var replacement = images.get(new Point(paragraph.id(), offset));
            values.add(item instanceof InlineText text ? new InlineText(text.text().replace('\t', ' '), text.style(), text.hyperlink()) : item instanceof InlineImage && replacement != null ? replacement : item);
            offset += item instanceof InlineText text ? text.text().length() : 1;
        }
        double lineHeight = paragraph.format().lineHeight();
        for (var value : values) { if (value instanceof InlineImage image) { lineHeight = Math.max(lineHeight, image.height() + Math.abs(image.baselineShift()) + paragraph.format().fontSize()); } }
        var format = paragraph.format();
        if (lineHeight > format.lineHeight()) {
            issue(issues, policy, "INLINE_IMAGE_LINE_HEIGHT", Range.of(paragraph.id(), 0, paragraph.text().length()), "fixed Word line height cannot contain the projected inline image", "FIXED_LINE_HEIGHT_EXPANDED_FOR_INLINE_IMAGE", ProcessingPolicy.Treatment.DEGRADE);
            format = format.toBuilder().lineHeight(lineHeight).build();
        }
        return Paragraph.mixed(paragraph.id(), values, format);
    }
    private static TableCell portableCell(TableCell cell, String tableId, Map<Point, InlineImage> images, List<ProcessingIssue> issues, ProcessingPolicy policy) {
        if (cell.text().indexOf('\t') < 0 && cell.images().isEmpty()) { return cell; }
        var format = cell.format(); var paragraph = new Paragraph(tableId, cell.text(), new ParagraphFormat(format.fontSize(), format.lineHeight(), 0, 0, format.alignment(), 0, 0, false), cell.inlines(), cell.images(), cell.fields());
        var mapped = portableParagraph(paragraph, images, issues, policy);
        return cell.toBuilder().content(mapped.content()).format(format.toBuilder().lineHeight(mapped.format().lineHeight()).build()).build();
    }
    private static List<Block> insertBoxes(List<Block> source, Map<String, List<Block>> boxes, Map<Point, InlineImage> images, Set<String> active, List<ProcessingIssue> issues, ProcessingPolicy policy) {
        var result = new ArrayList<Block>();
        for (var block : source) {
            ExecutionContext.checkpoint();
            if (block instanceof Table table) { block = new Table(table.id(), table.columns(), table.rows().stream().map(row -> row.cells().isEmpty() ? TableRow.covered() : new TableRow(row.cells().stream().map(c -> c.hasBlocks() ? c.toBuilder().blocks(insertBoxes(c.blocks(), boxes, images, active, issues, policy)).build() : portableCell(c, table.id(), images, issues, policy)).toList())).toList(), table.headerRows(), table.format()); }
            else if (block instanceof Paragraph p) { block = portableParagraph(p, images, issues, policy); }
            result.add(block);
            if (boxes.containsKey(block.id())) { if (!active.add(block.id())) { throw new IllegalArgumentException("cyclic textbox projection"); } result.addAll(insertBoxes(boxes.get(block.id()), boxes, images, active, issues, policy)); active.remove(block.id()); }
        }
        return List.copyOf(result);
    }
    private static InlineImage rasterize(Picture picture) throws IOException {
        var image = picture.image(); var transform = picture.transform(); var source = ImageIO.read(new ByteArrayInputStream(image.resource().bytes()));
        if (source == null) { throw new IOException("cannot decode validated native image"); }
        int left = (int)Math.round(source.getWidth() * transform.cropLeft()); int top = (int)Math.round(source.getHeight() * transform.cropTop());
        int right = (int)Math.round(source.getWidth() * (1 - transform.cropRight())); int bottom = (int)Math.round(source.getHeight() * (1 - transform.cropBottom()));
        int w = right - left; int h = bottom - top; if (w < 1 || h < 1) { throw new IOException("crop is smaller than one source pixel"); }
        double radians = Math.toRadians(transform.rotation() % 360); double sin = Math.abs(Math.sin(radians)); double cos = Math.abs(Math.cos(radians));
        // Word first stretches the cropped source into the display rectangle, then rotates that rectangle.
        // A common physical density preserves this geometry even when cropping changes the source aspect.
        double density = Math.max(w / image.width(), h / image.height());
        double baseW = image.width() * density; double baseH = image.height() * density;
        int rotatedW = Math.max(1, (int)Math.ceil(baseW * cos + baseH * sin - 1e-9)); int rotatedH = Math.max(1, (int)Math.ceil(baseH * cos + baseW * sin - 1e-9));
        if ((long)rotatedW * rotatedH > ImageResource.MAX_PIXELS) { throw new IOException("transformed image pixel budget exceeded"); }
        var target = new BufferedImage(rotatedW, rotatedH, BufferedImage.TYPE_INT_ARGB); var graphics = target.createGraphics();
        try { var mapping = new AffineTransform(); mapping.translate(rotatedW / 2.0, rotatedH / 2.0); mapping.rotate(radians); mapping.scale(transform.flipHorizontal() ? -baseW / w : baseW / w, transform.flipVertical() ? -baseH / h : baseH / h); mapping.translate(-w / 2.0, -h / 2.0); graphics.drawImage(source.getSubimage(left, top, w, h), mapping, null); }
        finally { graphics.dispose(); }
        var bytes = new ByteArrayOutputStream(); if (!ImageIO.write(target, "png", bytes)) { throw new IOException("PNG encoder unavailable"); }
        double visibleW = image.width() * cos + image.height() * sin; double visibleH = image.height() * cos + image.width() * sin;
        return new InlineImage(ImageResource.load(bytes.toByteArray()), visibleW, visibleH, image.baselineShift(), image.altText(), image.hyperlink());
    }
    private static void issue(List<ProcessingIssue> issues, ProcessingPolicy policy, String feature, Range range, String reason, String effect, ProcessingPolicy.Treatment treatment) { DocxSemanticSupport.issue(issues, policy, "PROJECT", feature, range, reason, effect, treatment); }
}
