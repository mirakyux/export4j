// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.HashSet;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable semantic document with ordered mixed blocks; empty documents are valid. */
public record Document(DocumentMetadata metadata, PageSettings pageSettings, List<Block> blocks) {
    /** A4, 54-point margins, empty metadata and 12-point body text; fonts remain explicit in DocumentEngine. */
    public static Builder builder() { return builder(DocumentMetadata.EMPTY, PageSettings.A4); }

    public static Builder builder(DocumentMetadata metadata) { return builder(metadata, PageSettings.A4); }

    /** Creates a task-confined builder; build() takes an immutable snapshot. */
    public static Builder builder(DocumentMetadata metadata, PageSettings pageSettings) {
        return new Builder(metadata, pageSettings);
    }

    public static final class Builder {
        private DocumentMetadata metadata;
        private PageSettings settings;
        private final java.util.ArrayList<Block> blocks = new java.util.ArrayList<>();
        private final Set<String> usedIds = new HashSet<>();
        private DocumentTheme theme = DocumentTheme.builder().build();
        private ParagraphFormat bodyFormat = theme.bodyFormat();
        private CellFormat cellFormat = theme.cellFormat();
        private TableFormat tableFormat = theme.tableFormat();
        private long sequence;

        private Builder(DocumentMetadata metadata, PageSettings settings) {
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            this.settings = Objects.requireNonNull(settings, "settings");
        }

        public Builder add(Block block) {
            blocks.add(Objects.requireNonNull(block, "block"));
            SemanticTree.walk(block, visit -> usedIds.add(visit.block().id()));
            return this;
        }

        /** Gives a custom block factory a deterministic unused document-local ID.
         * The factory runs synchronously; its result must use the supplied ID. */
        public Builder block(java.util.function.Function<String, ? extends Block> factory) {
            String id = nextId();
            Block block = Objects.requireNonNull(factory, "factory").apply(id);
            if (!id.equals(Objects.requireNonNull(block, "block").id())) {
                throw new IllegalArgumentException("block factory must preserve the supplied ID");
            }
            return add(block);
        }

        public Builder pageSettings(java.util.function.Consumer<PageSettings.Builder> configure) {
            var value = settings.toBuilder();
            configure.accept(value);
            return pageSettings(value.build());
        }

        public Builder bodyFormat(java.util.function.Consumer<ParagraphFormat.Builder> configure) {
            var value = bodyFormat.toBuilder();
            configure.accept(value);
            return bodyFormat(value.build());
        }

        public Builder table(java.util.function.Consumer<Table.Builder> configure) {
            return block(id -> {
                var value = Table.builder(id).cellFormat(cellFormat).textAppearance(theme.cellText()).format(tableFormat);
                configure.accept(value);
                return value.build();
            });
        }

        public Builder metadata(DocumentMetadata value) { metadata = Objects.requireNonNull(value, "metadata"); return this; }

        public Builder pageSettings(PageSettings value) { settings = Objects.requireNonNull(value, "pageSettings"); return this; }

        /** Replaces defaults for future body content, headings and convenience tables.
         * Existing blocks and explicit complete values remain unchanged; build() retains no theme. */
        public Builder theme(DocumentTheme value) {
            theme = Objects.requireNonNull(value, "theme");
            bodyFormat = theme.bodyFormat(); cellFormat = theme.cellFormat(); tableFormat = theme.tableFormat();
            return this;
        }

        /** Applies only to subsequently added body paragraphs, lists and convenience tables. */
        public Builder bodyFormat(ParagraphFormat value) {
            bodyFormat = Objects.requireNonNull(value, "bodyFormat");
            cellFormat = new CellFormat(value.fontSize(), value.lineHeight(), value.alignment(), value.lineBreakPolicy());
            return this;
        }

        /** Automatic IDs are document-local and deterministic; explicitly name blocks used as reference targets. */
        public Builder paragraph(String text) { return paragraph(nextId(), text, bodyFormat); }

        /** Adds an automatically named paragraph with a complete explicit format. */
        public Builder paragraph(String text, ParagraphFormat format) { return paragraph(nextId(), text, format); }

        public Builder paragraph(String id, String text) { return paragraph(id, text, bodyFormat); }

        public Builder paragraph(List<? extends InlineContent> content) { return paragraph(nextId(), content); }

        public Builder paragraph(String id, List<? extends InlineContent> content) {
            return add(Paragraph.mixed(id, content, bodyFormat));
        }

        public Builder heading(String text, int level) { return heading(nextId(), text, level); }

        public Builder heading(String id, String text, int level) { return add(new Heading(id, text, level, theme.headingFormat(level), runs(text, theme.headingText(level), theme.headingFormat(level).fontSize()))); }

        public Builder heading(List<InlineText> runs, int level) {
            return add(Heading.rich(nextId(), runs, level, theme.headingFormat(level)));
        }

        public Builder bullet(String text) { return add(new BulletItem(nextId(), text, "•", 1, bodyFormat, runs(text, theme.bodyText(), bodyFormat.fontSize()), theme.bodyText().style(bodyFormat.fontSize()))); }

        public Builder bullet(List<InlineText> runs) { return add(BulletItem.rich(nextId(), runs, "•", 1, bodyFormat, theme.bodyText().style(bodyFormat.fontSize()))); }

        /** Continues a level-one list; use NumberedItem for restart or nesting control. */
        public Builder numbered(String listId, String text) {
            return add(new NumberedItem(nextId(), listId, text, 0, bodyFormat, 1, runs(text, theme.bodyText(), bodyFormat.fontSize()), theme.bodyText().style(bodyFormat.fontSize())));
        }

        public Builder numbered(String listId, List<InlineText> runs) {
            return add(NumberedItem.rich(nextId(), listId, runs, 0, bodyFormat, 1, theme.bodyText().style(bodyFormat.fontSize())));
        }

        /** Repeating header, equal-width columns and current convenience-table defaults; requires a body row. */
        public Builder table(List<String> headers, List<List<String>> rows) {
            var value = Table.text(nextId(), headers, rows, cellFormat, theme.cellText());
            return add(value.format().equals(tableFormat) ? value
                    : new Table(value.id(), value.columns(), value.rows(), value.headerRows(), tableFormat));
        }

        public Builder pageBreak() { return pageBreak(nextId()); }

        private String nextId() {
            String id;
            do { sequence = Math.incrementExact(sequence); id = "auto-" + sequence; } while (usedIds.contains(id));
            return id;
        }

        public Builder paragraph(String id, String text, ParagraphFormat format) {
            return add(new Paragraph(id, text, format, runs(text, theme.bodyText(), format.fontSize())));
        }

        public Builder heading(String id, String text, int level, ParagraphFormat format) {
            return add(new Heading(id, text, level, format, runs(text, theme.headingText(level), format.fontSize())));
        }

        private static List<InlineText> runs(String text, TextAppearance appearance, double size) {
            Objects.requireNonNull(text, "text");
            return appearance.equals(TextAppearance.DEFAULT) ? List.of() : List.of(new InlineText(text, appearance.style(size)));
        }

        public Builder pageBreak(String id) {
            return add(new PageBreak(id));
        }

        public Document build() {
            return new Document(metadata, settings, blocks);
        }
    }
    public Document {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(pageSettings, "pageSettings");
        blocks = List.copyOf(blocks);
        SemanticTree.validateUniqueIds(blocks);
    }

    /** Accepts typed paragraph/table collections and takes an immutable snapshot. */
    public Document(DocumentMetadata metadata, PageSettings pageSettings, Collection<? extends Block> blocks) {
        this(metadata, pageSettings, List.copyOf(blocks));
    }

    /** Compatibility view of top-level paragraphs only; use blocks() to traverse mixed content. */
    public List<Paragraph> paragraphs() {
        return blocks.stream().filter(Paragraph.class::isInstance).map(Paragraph.class::cast).toList();
    }
}
