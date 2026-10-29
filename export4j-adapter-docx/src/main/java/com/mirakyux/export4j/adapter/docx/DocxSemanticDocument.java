// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.SectionedDocument;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/** Immutable Word semantic document. Contains typed values, never package bytes, XML fragments or
 * library handles. Text positions refer to the review view, including tracked deletions, in UTF-16.
 * Body and textbox paragraph IDs share one namespace. Use edit() to keep anchors valid after edits.
 */
public record DocxSemanticDocument(SectionedDocument body, List<Drawing> drawings, List<Comment> comments,
        List<Revision> revisions, List<Control> controls, List<Contents> contents) {
    public DocxSemanticDocument {
        Objects.requireNonNull(body, "body"); drawings = List.copyOf(drawings); comments = List.copyOf(comments);
        revisions = List.copyOf(revisions); controls = List.copyOf(controls); contents = List.copyOf(contents);
        DocxSemanticSupport.validate(body, drawings, comments, revisions, controls, contents);
        var paragraphOrder = new java.util.ArrayList<>(DocxSemanticSupport.paragraphs(body, drawings).keySet());
        drawings = drawings.stream().sorted(java.util.Comparator.comparingInt((Drawing d) -> paragraphOrder.indexOf(d.anchor().paragraphId())).thenComparingInt(d -> d.anchor().utf16())).toList();
    }
    public DocxSemanticDocument(SectionedDocument body) { this(body, List.of(), List.of(), List.of(), List.of(), List.of()); }
    public DocxSemanticEditor.Request edit() { return DocxSemanticEditor.edit(this); }

    public record Point(String paragraphId, int utf16) {
        public Point { key(paragraphId); if (utf16 < 0) { throw new IllegalArgumentException("negative Word text position"); } }
    }
    /** Ordered inclusive start/exclusive end; a point comment is represented by equal endpoints. */
    public record Range(Point start, Point end) {
        public Range { Objects.requireNonNull(start, "start"); Objects.requireNonNull(end, "end"); }
        public static Range of(String paragraph, int start, int end) { return new Range(new Point(paragraph, start), new Point(paragraph, end)); }
    }
    public enum RelativeTo { PAGE, MARGIN, COLUMN, PARAGRAPH, CHARACTER, LINE, LEFT_MARGIN, RIGHT_MARGIN, INSIDE_MARGIN, OUTSIDE_MARGIN, TOP_MARGIN, BOTTOM_MARGIN }
    public enum Align { OFFSET, LEFT, CENTER, RIGHT, TOP, BOTTOM, INSIDE, OUTSIDE }
    /** One axis in points. OFFSET uses offset; other values use standard Word alignment. */
    public record Axis(RelativeTo relativeTo, Align align, double offset) {
        public Axis { Objects.requireNonNull(relativeTo, "relativeTo"); Objects.requireNonNull(align, "align"); finite(offset); }
        public static Axis offset(RelativeTo relativeTo, double points) { return new Axis(relativeTo, Align.OFFSET, points); }
    }
    public enum Wrap { INLINE, NONE, SQUARE, TOP_AND_BOTTOM, TIGHT, THROUGH }
    public enum WrapSide { BOTH, LEFT, RIGHT, LARGEST }
    public record Vertex(double x, double y) { public Vertex { finite(x); finite(y); } }
    public record Placement(Axis horizontal, Axis vertical, Wrap wrap, WrapSide wrapSide, List<Vertex> polygon,
            boolean behindText, boolean allowOverlap, boolean layoutInCell, long zOrder,
            double distanceTop, double distanceRight, double distanceBottom, double distanceLeft) {
        public Placement {
            Objects.requireNonNull(horizontal, "horizontal"); Objects.requireNonNull(vertical, "vertical");
            Objects.requireNonNull(wrap, "wrap"); Objects.requireNonNull(wrapSide, "wrapSide"); polygon = List.copyOf(polygon);
            if (zOrder < 0 || zOrder > 0xffffffffL || polygon.size() > 4096) { throw new IllegalArgumentException("Word drawing placement budget exceeded"); }
            nonnegative(distanceTop); nonnegative(distanceRight); nonnegative(distanceBottom); nonnegative(distanceLeft);
            if (wrap == Wrap.INLINE && (distanceTop != 0 || distanceRight != 0 || distanceBottom != 0 || distanceLeft != 0 || behindText || zOrder != 0)) { throw new IllegalArgumentException("inline drawing cannot carry unmapped floating distances/layers"); }
            if ((wrap == Wrap.TIGHT || wrap == Wrap.THROUGH) && polygon.size() < 3) { throw new IllegalArgumentException("polygon wrap requires at least three vertices"); }
        }
        public static Placement inline() { return new Placement(Axis.offset(RelativeTo.COLUMN, 0), Axis.offset(RelativeTo.PARAGRAPH, 0), Wrap.INLINE, WrapSide.BOTH, List.of(), false, true, true, 0, 0, 0, 0, 0); }
    }
    /** Crop fractions refer to source edges. Rotation is clockwise degrees; flips precede rotation. */
    public record Transform(double rotation, boolean flipHorizontal, boolean flipVertical,
            double cropLeft, double cropTop, double cropRight, double cropBottom) {
        public static final Transform NONE = new Transform(0, false, false, 0, 0, 0, 0);
        public Transform {
            finite(rotation); nonnegative(cropLeft); nonnegative(cropTop); nonnegative(cropRight); nonnegative(cropBottom);
            if (cropLeft + cropRight >= 1 || cropTop + cropBottom >= 1) { throw new IllegalArgumentException("image crop removes the complete source"); }
        }
    }
    public sealed interface DrawingContent permits Picture, TextBox { }
    public record Picture(InlineImage image, Transform transform) implements DrawingContent {
        public Picture { Objects.requireNonNull(image, "image"); Objects.requireNonNull(transform, "transform"); }
    }
    public enum VerticalAlignment { TOP, CENTER, BOTTOM }
    public record TextBox(List<Block> blocks, double width, double height, double insetTop, double insetRight,
            double insetBottom, double insetLeft, VerticalAlignment verticalAlignment, Integer fillRgb,
            Integer borderRgb, double borderWidth, Transform transform) implements DrawingContent {
        public TextBox {
            blocks = DocxSemanticSupport.mapBlocks(List.copyOf(blocks), p -> com.mirakyux.export4j.model.Paragraph.mixed(p.id(), p.content(), p.format())); positive(width); positive(height); nonnegative(insetTop); nonnegative(insetRight);
            nonnegative(insetBottom); nonnegative(insetLeft); Objects.requireNonNull(verticalAlignment, "verticalAlignment");
            rgb(fillRgb); rgb(borderRgb); nonnegative(borderWidth); Objects.requireNonNull(transform, "transform");
            if (transform.cropLeft() != 0 || transform.cropTop() != 0 || transform.cropRight() != 0 || transform.cropBottom() != 0) { throw new IllegalArgumentException("textbox cropping requires a typed shape clipping model"); }
            if (blocks.isEmpty() || insetLeft + insetRight >= width || insetTop + insetBottom >= height) { throw new IllegalArgumentException("textbox needs positive content geometry and blocks"); }
        }
    }
    public record Drawing(String id, Point anchor, Placement placement, DrawingContent content) {
        public Drawing { key(id); Objects.requireNonNull(anchor, "anchor"); Objects.requireNonNull(placement, "placement"); Objects.requireNonNull(content, "content"); }
    }
    public record Comment(String id, Range range, String author, String initials, OffsetDateTime date, List<Block> blocks) {
        public Comment { key(id); Objects.requireNonNull(range, "range"); Objects.requireNonNull(author, "author"); Objects.requireNonNull(initials, "initials"); blocks = DocxSemanticSupport.mapBlocks(List.copyOf(blocks), p -> com.mirakyux.export4j.model.Paragraph.mixed(p.id(), p.content(), p.format())); if (blocks.isEmpty()) { throw new IllegalArgumentException("comment requires content"); } }
    }
    public enum RevisionKind { INSERT, DELETE, MOVE_FROM, MOVE_TO, RUN_FORMAT, PARAGRAPH_FORMAT }
    public record Revision(String id, RevisionKind kind, Range range, String author, OffsetDateTime date,
            String moveId, InlineStyle priorStyle, ParagraphFormat priorParagraphFormat) {
        public Revision {
            key(id); Objects.requireNonNull(kind, "kind"); Objects.requireNonNull(range, "range"); Objects.requireNonNull(author, "author"); Objects.requireNonNull(moveId, "moveId");
            if (kind == RevisionKind.RUN_FORMAT && priorStyle == null || kind == RevisionKind.PARAGRAPH_FORMAT && priorParagraphFormat == null) { throw new IllegalArgumentException("format revision requires typed prior properties"); }
            if ((kind == RevisionKind.MOVE_FROM || kind == RevisionKind.MOVE_TO) && moveId.isBlank()) { throw new IllegalArgumentException("move revision requires a pair ID"); }
        }
    }
    public enum ControlKind { RICH_TEXT, PLAIN_TEXT, CHECKBOX, DROP_DOWN, COMBO_BOX, DATE, PICTURE, GROUP, REPEATING_SECTION, REPEATING_ITEM, LEGACY_TEXT, LEGACY_CHECKBOX, LEGACY_DROP_DOWN }
    public record ListItem(String label, String value) { public ListItem { Objects.requireNonNull(label, "label"); Objects.requireNonNull(value, "value"); } }
    /** Passive legacy form constraints/defaults. Macro callbacks and bound data are never represented. */
    public record ControlOptions(String defaultText, int maxLength, String textType, String textFormat,
            String helpText, String statusText, boolean calculateOnExit, boolean enabled,
            double checkboxSize, boolean defaultChecked, int defaultSelection) {
        public static final ControlOptions DEFAULT = new ControlOptions("", 0, "regular", "", "", "", false, true, 0, false, 0);
        public ControlOptions { Objects.requireNonNull(defaultText, "defaultText"); Objects.requireNonNull(textType, "textType"); Objects.requireNonNull(textFormat, "textFormat"); Objects.requireNonNull(helpText, "helpText"); Objects.requireNonNull(statusText, "statusText"); if (maxLength < 0 || maxLength > 4_000_000 || defaultSelection < 0 || !java.util.Set.of("regular", "number", "date", "currentTime", "currentDate", "calculated").contains(textType)) { throw new IllegalArgumentException("invalid form constraint"); } nonnegative(checkboxSize); }
    }
    /** Range contains actual value content; tag/title/list/date/checked are editable native properties. */
    public record Control(String id, ControlKind kind, Range range, String tag, String title, boolean checked,
            List<ListItem> items, String dateFormat, String language, boolean blockLevel, ControlOptions options) {
        public Control {
            key(id); Objects.requireNonNull(kind, "kind"); Objects.requireNonNull(range, "range"); Objects.requireNonNull(tag, "tag"); Objects.requireNonNull(title, "title"); items = List.copyOf(items); Objects.requireNonNull(dateFormat, "dateFormat"); Objects.requireNonNull(language, "language"); Objects.requireNonNull(options, "options"); if (items.size() > 10_000) { throw new IllegalArgumentException("control option budget exceeded"); }
            if (!items.isEmpty() && kind != ControlKind.DROP_DOWN && kind != ControlKind.COMBO_BOX && kind != ControlKind.LEGACY_DROP_DOWN) { throw new IllegalArgumentException("only list controls accept items"); }
            if (kind != ControlKind.LEGACY_TEXT && kind != ControlKind.LEGACY_CHECKBOX && kind != ControlKind.LEGACY_DROP_DOWN && !options.equals(ControlOptions.DEFAULT)) { throw new IllegalArgumentException("legacy options require a legacy form control"); }
            if (kind == ControlKind.LEGACY_DROP_DOWN && !items.isEmpty() && options.defaultSelection() >= items.size()) { throw new IllegalArgumentException("legacy default selection exceeds available items"); }
        }
        public Control(String id, ControlKind kind, Range range, String tag, String title, boolean checked, List<ListItem> items, String dateFormat, String language, boolean blockLevel) { this(id, kind, range, tag, title, checked, items, dateFormat, language, blockLevel, ControlOptions.DEFAULT); }
    }
    /** Native TOC declaration with an explicit cached range, regenerated from heading semantics by updateContents. */
    public record Contents(String id, Range range, int firstLevel, int lastLevel, boolean hyperlinks,
            boolean pageNumbers, boolean dirty) {
        public Contents { key(id); Objects.requireNonNull(range, "range"); if (firstLevel < 1 || lastLevel > 9 || firstLevel > lastLevel) { throw new IllegalArgumentException("invalid TOC heading levels"); } }
    }
    private static void key(String value) { if (Objects.requireNonNull(value, "key").isBlank()) { throw new IllegalArgumentException("empty Word semantic ID"); } }
    private static void finite(double value) { if (!Double.isFinite(value) || Math.abs(value) > 1e9) { throw new IllegalArgumentException("Word geometry is not finite or exceeds budget"); } }
    private static void nonnegative(double value) { finite(value); if (value < 0) { throw new IllegalArgumentException("negative Word geometry"); } }
    private static void positive(double value) { nonnegative(value); if (value == 0) { throw new IllegalArgumentException("zero Word geometry"); } }
    private static void rgb(Integer value) { if (value != null && (value < 0 || value > 0xffffff)) { throw new IllegalArgumentException("invalid Word RGB"); } }
}
