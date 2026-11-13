// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;
import java.util.List;

/** Immutable inline cell or ordered semantic block cell. Empty inline text retains one line.
 * columnSpan and rowSpan are positive counts of consecutive physical grid columns and rows; defaults are 1.
 * Block content accepts Paragraph/Table only, at most 16 table levels including the owning root table.
 * Legacy inline views fail for block cells; UTF-16 offsets belong to each original leaf paragraph.
 */
public record TableCell(String text, CellFormat format, List<InlineText> inlines, List<Paragraph.ImageSpan> images,
        List<Paragraph.FieldSpan> fields, List<Paragraph.VectorSpan> vectors, int columnSpan, int rowSpan, List<Block> blocks) {
    /** Task-confined builder; content replaces text and vice versa. All lists are snapshotted. */
    public static Builder builder() { return new Builder(); }
    /** Copies content, format and both spans without flattening rich text or atomic objects. */
    public Builder toBuilder() { return new Builder(this); }
    public static final class Builder {
        private String text = "";
        private CellFormat format = CellFormat.of(12);
        private List<? extends InlineContent> content = List.of();
        private List<InlineText> inlines = List.of();
        private List<Paragraph.ImageSpan> images = List.of();
        private List<Paragraph.FieldSpan> fields = List.of();
        private List<Paragraph.VectorSpan> vectors = List.of();
        private List<Block> blocks = List.of();
        private int columnSpan = 1;
        private int rowSpan = 1;
        private Builder() { }
        private Builder(TableCell source) {
            text = source.text; format = source.format; inlines = source.inlines;
            images = source.images; fields = source.fields; vectors = source.vectors; columnSpan = source.columnSpan; rowSpan = source.rowSpan;
            blocks = source.blocks;
        }
        public Builder text(String value) {
            text = Objects.requireNonNull(value, "text"); content = List.of();
            inlines = List.of(); images = List.of(); fields = List.of(); vectors = List.of(); blocks = List.of(); return this;
        }
        public Builder format(CellFormat value) { format = Objects.requireNonNull(value, "format"); return this; }
        public Builder content(List<? extends InlineContent> value) {
            content = List.copyOf(value); text = ""; inlines = List.of(); images = List.of(); fields = List.of(); vectors = List.of(); blocks = List.of(); return this;
        }
        /** Replaces inline/block content with a nonempty immutable block snapshot. */
        public Builder blocks(List<? extends Block> value) {
            var snapshot = List.<Block>copyOf(value);
            if (snapshot.isEmpty()) { throw new IllegalArgumentException("block cell content must not be empty"); }
            blocks = snapshot; text = ""; content = List.of(); inlines = List.of(); images = List.of(); fields = List.of(); vectors = List.of();
            return this;
        }
        /** Appends an explicitly identified paragraph; preserves its format, resources and source text. */
        public Builder paragraph(Paragraph value) { return appendBlock(Objects.requireNonNull(value, "paragraph")); }
        /** Appends a paragraph using this cell's current text size, line height and alignment. */
        public Builder paragraph(String id, String value) {
            return paragraph(new Paragraph(id, value, new ParagraphFormat(format.fontSize(), format.lineHeight(),
                    0, 0, format.alignment(), 0, 0, false).withLineBreakPolicy(format.lineBreakPolicy())));
        }
        /** Runs a nested table builder synchronously and appends its immutable result. Explicit IDs are
         * required because this builder has no document-wide automatic-ID context. */
        public Builder table(String id, java.util.function.Consumer<Table.Builder> configure) {
            var value = Table.builder(id); Objects.requireNonNull(configure, "configure").accept(value);
            return appendBlock(value.build());
        }
        private Builder appendBlock(Block value) {
            var result = new java.util.ArrayList<>(blocks); result.add(value); return blocks(result);
        }
        public Builder columnSpan(int value) { columnSpan = value; return this; }
        /** Covers this many consecutive physical rows, including the anchor row. Must be positive. */
        public Builder rowSpan(int value) { rowSpan = value; return this; }
        public TableCell build() {
            if (content.isEmpty()) { return new TableCell(text, format, inlines, images, fields, vectors, columnSpan, rowSpan, blocks); }
            var mixed = InlineContents.mixed(content, format.fontSize());
            return new TableCell(mixed.text(), format, mixed.inlines(), mixed.images(), mixed.fields(), mixed.vectors(), columnSpan, rowSpan, List.of());
        }
    }
    public TableCell {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(format, "format");
        inlines = List.copyOf(inlines);
        images = List.copyOf(images);
        fields = List.copyOf(fields);
        vectors = List.copyOf(vectors);
        blocks = List.copyOf(blocks);
        if (columnSpan <= 0) { throw new IllegalArgumentException("cell column span must be positive"); }
        if (rowSpan <= 0) { throw new IllegalArgumentException("cell row span must be positive"); }
        if (!blocks.isEmpty()) {
            if (!text.isEmpty() || !inlines.isEmpty() || !images.isEmpty() || !fields.isEmpty() || !vectors.isEmpty()) {
                throw new IllegalArgumentException("inline and block cell content are mutually exclusive");
            }
            SemanticTree.validateCellBlocks(blocks);
        } else {
            InlineContents.validate(text, inlines, images, fields, vectors, format.fontSize());
        }
        for (var span : fields) {
            if (span.field().kind() == InlineField.Kind.NOTE_MARKER) {
                throw new IllegalArgumentException("inline note markers require body paragraphs");
            }
        }
    }
    /** True when this value has explicit nonempty semantic blocks. */
    public boolean hasBlocks() { return !blocks.isEmpty(); }
    /** Legacy cell-local source view; block cells must be traversed through blocks(). */
    @Override public String text() { requireInline(); return text; }
    /** Legacy styled-run view; fails rather than hiding block descendants. */
    @Override public List<InlineText> inlines() { requireInline(); return inlines; }
    /** Legacy inline-image view; fails rather than hiding block descendants. */
    @Override public List<Paragraph.ImageSpan> images() { requireInline(); return images; }
    /** Legacy inline-field view; fails rather than hiding block descendants. */
    @Override public List<Paragraph.FieldSpan> fields() { requireInline(); return fields; }
    /** Legacy inline-vector view; fails rather than hiding block descendants. */
    @Override public List<Paragraph.VectorSpan> vectors() { requireInline(); return vectors; }
    private void requireInline() {
        if (hasBlocks()) { throw new IllegalStateException("block cell content requires blocks() traversal"); }
    }
    public TableCell(String text, CellFormat format) { this(text, format, List.of()); }
    public TableCell(String text, CellFormat format, List<InlineText> inlines) { this(text, format, inlines, List.of()); }
    public TableCell(String text, CellFormat format, List<InlineText> inlines, List<Paragraph.ImageSpan> images) {
        this(text, format, inlines, images, List.of());
    }
    /** Compatibility constructor: one physical grid column. */
    public TableCell(String text, CellFormat format, List<InlineText> inlines, List<Paragraph.ImageSpan> images,
            List<Paragraph.FieldSpan> fields) {
        this(text, format, inlines, images, fields, 1);
    }
    /** Compatibility constructor: horizontal coverage with one physical grid row. */
    public TableCell(String text, CellFormat format, List<InlineText> inlines, List<Paragraph.ImageSpan> images,
            List<Paragraph.FieldSpan> fields, int columnSpan) {
        this(text, format, inlines, images, fields, columnSpan, 1);
    }
    /** Compatibility constructor retaining the previous seven-component inline shape. */
    public TableCell(String text, CellFormat format, List<InlineText> inlines, List<Paragraph.ImageSpan> images,
            List<Paragraph.FieldSpan> fields, int columnSpan, int rowSpan) {
        this(text, format, inlines, images, fields, columnSpan, rowSpan, List.of());
    }
    /** Compatibility constructor retaining the previous eight-component shape. */
    public TableCell(String text, CellFormat format, List<InlineText> inlines, List<Paragraph.ImageSpan> images,
            List<Paragraph.FieldSpan> fields, int columnSpan, int rowSpan, List<Block> blocks) {
        this(text, format, inlines, images, fields, List.of(), columnSpan, rowSpan, blocks);
    }
    public static TableCell rich(List<InlineText> inlines, CellFormat format) {
        return new TableCell(InlineContents.text(inlines), format, inlines);
    }

    public static TableCell mixed(List<? extends InlineContent> content, CellFormat format) {
        var mixed = InlineContents.mixed(content, format.fontSize());
        return new TableCell(mixed.text(), format, mixed.inlines(), mixed.images(), mixed.fields(), mixed.vectors(), 1, 1, List.of());
    }
}
