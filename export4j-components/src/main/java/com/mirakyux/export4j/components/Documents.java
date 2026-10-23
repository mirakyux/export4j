// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TextAppearance;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;

/** Component-aware authoring entry. Produces the same immutable Document as the semantic builder.
 * Owns no resources. Builders and callbacks are confined to the calling task. */
public final class Documents {
    private Documents() { }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final Document.Builder document = Document.builder();
        private DocumentMetadata metadata = DocumentMetadata.EMPTY;
        private ParagraphFormat bodyFormat = ParagraphFormat.of(12);
        private TextAppearance bodyText = TextAppearance.DEFAULT;
        private boolean componentDefaultsSet;
        private Builder() { }

        public Builder metadata(DocumentMetadata value) { metadata = Objects.requireNonNull(value, "metadata"); document.metadata(value); return this; }
        public Builder title(String title) { return metadata(new DocumentMetadata(title, metadata.author())); }
        public Builder author(String author) { return metadata(new DocumentMetadata(metadata.title(), author)); }
        public Builder pageSettings(PageSettings value) { document.pageSettings(value); return this; }
        public Builder pageSettings(Consumer<PageSettings.Builder> configure) { document.pageSettings(configure); return this; }
        /** Snapshots theme defaults for future semantic content and component typography.
         * Components inherit body typography, color and default fonts; explicit colors and named run roles
         * remain independent. Empty rich-run roles select the scoped default fonts. The finished document contains resolved values and no theme reference. */
        public Builder theme(DocumentTheme value) {
            Objects.requireNonNull(value, "theme");
            document.theme(value); bodyFormat = value.bodyFormat(); bodyText = value.bodyText(); componentDefaultsSet = true; return this;
        }
        /** Snapshots defaults for subsequently added body content. Components inherit only typography:
         * size, fixed line height, indents, alignment and line breaking. Explicit component styles win.
         * Paragraph spacing and adjacency policies remain semantic block properties.
         */
        public Builder bodyFormat(ParagraphFormat value) {
            bodyFormat = Objects.requireNonNull(value, "bodyFormat");
            componentDefaultsSet = true; document.bodyFormat(value); return this;
        }
        public Builder bodyFormat(Consumer<ParagraphFormat.Builder> configure) {
            var value = bodyFormat.toBuilder(); Objects.requireNonNull(configure, "configure").accept(value);
            return bodyFormat(value.build());
        }
        public Builder add(Block block) { document.add(block); return this; }
        public Builder paragraph(String text) { document.paragraph(text); return this; }
        public Builder paragraph(String text, ParagraphFormat format) { document.paragraph(text, format); return this; }
        public Builder paragraph(String id, String text) { document.paragraph(id, text); return this; }
        public Builder paragraph(List<? extends InlineContent> content) { document.paragraph(content); return this; }
        public Builder paragraph(String id, List<? extends InlineContent> content) { document.paragraph(id, content); return this; }
        public Builder heading(String text, int level) { document.heading(text, level); return this; }
        public Builder heading(String id, String text, int level) { document.heading(id, text, level); return this; }
        public Builder heading(List<InlineText> runs, int level) { document.heading(runs, level); return this; }
        public Builder bullet(String text) { document.bullet(text); return this; }
        public Builder bullet(List<InlineText> runs) { document.bullet(runs); return this; }
        public Builder numbered(String listId, String text) { document.numbered(listId, text); return this; }
        public Builder numbered(String listId, List<InlineText> runs) { document.numbered(listId, runs); return this; }
        public Builder table(List<String> headers, List<List<String>> rows) { document.table(headers, rows); return this; }
        public Builder table(Consumer<Table.Builder> configure) { document.table(configure); return this; }
        public Builder component(Component component) {
            Objects.requireNonNull(component, "component");
            var scoped = scoped(component);
            document.block(id -> new ComponentBlock(id, scoped, 0, 0)); return this;
        }
        public Builder component(String id, Component component) { return add(new ComponentBlock(id, scoped(component), 0, 0)); }
        private Component scoped(Component component) {
            Objects.requireNonNull(component, "component");
            if (!componentDefaultsSet) { return component; }
            var style = new ParagraphStyle(bodyFormat.fontSize(), bodyFormat.lineHeight(), bodyFormat.firstLineIndent(),
                    bodyFormat.subsequentLineIndent(), TextAlignment.valueOf(bodyFormat.alignment().name()), bodyFormat.lineBreakPolicy());
            Component content = StyleScope.builder(component).style(style)
                    .color(new RgbColor((bodyText.rgb() >> 16 & 255) / 255.0,
                            (bodyText.rgb() >> 8 & 255) / 255.0, (bodyText.rgb() & 255) / 255.0)).build();
            return bodyText.fontRole().isEmpty() ? content : new FontRoleScope(content, bodyText.fontRole());
        }
        public Builder column(Consumer<FlowColumn.Builder> configure) {
            var value = FlowColumn.builder(); configure.accept(value); return component(value.build());
        }
        public Builder grid(Consumer<GridTable.Builder> configure) {
            var value = GridTable.builder(); configure.accept(value); return component(value.build());
        }
        public Builder pageBreak() { document.pageBreak(); return this; }
        public Document build() { return document.build(); }
    }
}
