// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable fixed header/footer in points. Content is ordered paragraphs and semantic tables,
 * including explicit inline images and fixed-width PAGE/PAGES fields. Links and reference fields
 * are rejected. Height/gap reserve body space; overflow fails without clipping or scaling.
 * Limits: 256 semantic blocks, 4,096 inline items, 16,384 table slots and 100,000 code points.
 * Owns no resources; safe to share. Binding/layout apply additional work budgets.
 */
public final class PageRegion {
    public static final PageRegion EMPTY = new PageRegion(0, 0, List.of());
    private final double height;
    private final double gap;
    private final double edgeOffset;
    private final double leftInset;
    private final double rightInset;
    private final List<Block> blocks;

    /** Snapshots content. All dimensions must be finite and nonnegative; content requires positive height. */
    public PageRegion(double height, double gap, List<? extends Block> blocks) {
        this(height, gap, -1, -1, -1, blocks);
    }

    /** Negative one inherits the corresponding page margin; explicit zero reaches the paper edge. */
    public PageRegion(double height, double gap, double edgeOffset, double leftInset, double rightInset,
            List<? extends Block> blocks) {
        this.blocks = List.copyOf(blocks);
        this.height = height;
        this.gap = gap;
        this.edgeOffset = edgeOffset;
        this.leftInset = leftInset;
        this.rightInset = rightInset;
        if (!Double.isFinite(height) || height < 0 || !Double.isFinite(gap) || gap < 0
                || !offset(edgeOffset) || !offset(leftInset) || !offset(rightInset)
                || height == 0 && (gap != 0 || !blocks.isEmpty() || edgeOffset != -1 || leftInset != -1 || rightInset != -1)) {
            throw new IllegalArgumentException("invalid page region dimensions");
        }
        long[] counts = new long[4];
        SemanticTree.walk(this.blocks, visit -> {
            if (++counts[0] > 256) { throw new IllegalArgumentException("page region block budget exceeded"); }
            if (visit.block() instanceof Paragraph paragraph) {
                checkContent(paragraph.content(), counts);
            } else if (visit.block() instanceof Table table) {
                counts[1] += (long) table.rows().size() * table.columns().size();
                if (counts[1] > 16_384) { throw new IllegalArgumentException("page region table slot budget exceeded"); }
                for (var row : table.rows()) {
                    for (var cell : row.cells()) { if (!cell.hasBlocks()) { checkContent(new Paragraph("cell", cell.text(), ParagraphFormat.of(cell.format().fontSize()),
                                cell.inlines(), cell.images(), cell.fields()).content(), counts); } }
                }
            } else { throw new IllegalArgumentException("page regions accept paragraphs and tables only"); }
        });
        SemanticTree.validateUniqueIds(this.blocks);
    }

    private static boolean offset(double value) { return Double.isFinite(value) && (value == -1 || value >= 0); }

    private static void checkContent(List<InlineContent> contents, long[] counts) {
        for (var content : contents) {
            ExecutionContext.checkpoint();
            counts[2]++;
            if (content instanceof InlineText text) {
                counts[3] += text.text().codePointCount(0, text.text().length());
                if (!text.hyperlink().isEmpty()) { throw new IllegalArgumentException("page region links are unsupported"); }
            } else if (content instanceof InlineImage image) {
                counts[3]++;
                if (!image.hyperlink().isEmpty()) { throw new IllegalArgumentException("page region links are unsupported"); }
            } else if (content instanceof InlineField field) {
                counts[3]++;
                if (field.kind() != InlineField.Kind.PAGE && field.kind() != InlineField.Kind.PAGES) {
                    throw new IllegalArgumentException("page regions support PAGE/PAGES fields only");
                }
            }
            if (counts[2] > 4_096 || counts[3] > 100_000) { throw new IllegalArgumentException("page region source budget exceeded"); }
        }
    }

    public double height() { return height; }
    public double gap() { return gap; }
    public double edgeOffset() { return edgeOffset; }
    public double leftInset() { return leftInset; }
    public double rightInset() { return rightInset; }
    public List<Block> blocks() { return blocks; }
    /** Legacy text-only view. Fails on tables so content cannot be silently omitted. */
    public List<Paragraph> paragraphs() {
        if (blocks.stream().anyMatch(block -> !(block instanceof Paragraph))) {
            throw new IllegalStateException("page region contains tables; use blocks()");
        }
        return blocks.stream().map(block -> (Paragraph) block).toList();
    }
    public static Builder builder() { return new Builder(); }
    public Builder toBuilder() { return new Builder().height(height).gap(gap).edgeOffset(edgeOffset).leftInset(leftInset).rightInset(rightInset).blocks(blocks); }
    @Override public boolean equals(Object other) {
        return other instanceof PageRegion value && Double.compare(height, value.height) == 0
                && Double.compare(gap, value.gap) == 0 && Double.compare(edgeOffset, value.edgeOffset) == 0
                && Double.compare(leftInset, value.leftInset) == 0 && Double.compare(rightInset, value.rightInset) == 0
                && blocks.equals(value.blocks);
    }
    @Override public int hashCode() { return Objects.hash(height, gap, edgeOffset, leftInset, rightInset, blocks); }
    @Override public String toString() { return "PageRegion[height=" + height + ", gap=" + gap + ", edgeOffset=" + edgeOffset + ", leftInset=" + leftInset + ", rightInset=" + rightInset + ", blocks=" + blocks + "]"; }

    /** Task-confined builder; explicit block formats and inline styles remain unchanged. */
    public static final class Builder {
        private double height;
        private double gap;
        private double edgeOffset = -1;
        private double leftInset = -1;
        private double rightInset = -1;
        private final List<Block> blocks = new ArrayList<>();
        private Builder() { }
        public Builder height(double value) { height = value; return this; }
        public Builder gap(double value) { gap = value; return this; }
        public Builder edgeOffset(double value) { edgeOffset = value; return this; }
        public Builder leftInset(double value) { leftInset = value; return this; }
        public Builder rightInset(double value) { rightInset = value; return this; }
        public Builder blocks(List<? extends Block> values) { blocks.clear(); blocks.addAll(List.copyOf(values)); return this; }
        public Builder block(Block value) { blocks.add(Objects.requireNonNull(value, "block")); return this; }
        public Builder paragraphs(List<Paragraph> values) { return blocks(values); }
        public Builder paragraph(Paragraph value) { return block(value); }
        public Builder table(Table value) { return block(value); }
        public PageRegion build() { return new PageRegion(height, gap, edgeOffset, leftInset, rightInset, blocks); }
    }
}
