// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import com.mirakyux.export4j.graphics.FormStyle;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.layout.core.Position;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.text.ParagraphStyle;

/** Immutable unresolved document interactions. Block targets follow the first fragment after pagination. */
public record Navigation(List<Bookmark> bookmarks, TableOfContents contents, List<Comment> comments, List<Field> fields) {
    public static final Navigation EMPTY = new Navigation(List.of(), TableOfContents.NONE, List.of(), List.of());

    /** Task-confined interaction builder. Targets resolve after pagination; no resources are owned. */
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final List<Bookmark> bookmarks = new java.util.ArrayList<>();
        private TableOfContents contents = TableOfContents.NONE;
        private final List<Comment> comments = new java.util.ArrayList<>();
        private final List<Field> fields = new java.util.ArrayList<>();
        private Builder() { }
        public Builder bookmark(String title, String blockId, int level) { bookmarks.add(new Bookmark(title, blockId, level)); return this; }
        public Builder contents(TableOfContents value) { contents = Objects.requireNonNull(value, "contents"); return this; }
        public Builder comment(Target target, String author, String text) { comments.add(new Comment(target, author, text)); return this; }
        public Builder field(Field value) { fields.add(Objects.requireNonNull(value, "field")); return this; }
        public Navigation build() { return new Navigation(bookmarks, contents, comments, fields); }
    }

    /** Collects semantic headings in source order, with a front contents page. Heading levels cannot skip hierarchy. */
    public static Navigation fromHeadings(Document document) {
        return fromHeadings(document, new TableOfContents(true, "目录 / Contents", ParagraphStyle.of(12)), List.of(), List.of());
    }

    public static Navigation fromHeadings(SectionedDocument document) {
        return fromHeadings(new Document(document.metadata(), document.sections().getFirst().pageSettings(),
                document.sections().stream().flatMap(section -> section.blocks().stream()).toList()));
    }

    /** Resolves portable declarations against current heading text; no source model is cached. */
    public static Navigation fromDocument(SectionedDocument document) {
        var value = document.navigation(); value.validate(document);
        var bookmarks = value.headings() ? document.sections().stream().flatMap(section -> section.blocks().stream())
                .filter(Heading.class::isInstance).map(Heading.class::cast).filter(heading -> heading.level() <= value.maxHeadingLevel())
                .map(heading -> new Bookmark(heading.text(), heading.id(), heading.level())).toList()
                : value.bookmarks().stream().map(bookmark -> new Bookmark(bookmark.title(), bookmark.blockId(), bookmark.level())).toList();
        var source = value.contents(); var appearance = ContentsStyle.builder();
        source.styles().forEach((part, style) -> {
            if (part == com.mirakyux.export4j.model.DocumentNavigation.Part.TITLE) { appearance.titleStyle(typography(style)); }
            else { appearance.pageLabelStyle(typography(style)); }
        });
        source.colors().forEach((part, color) -> {
            var rgb = new com.mirakyux.export4j.graphics.RgbColor(((color >> 16) & 255) / 255.0, ((color >> 8) & 255) / 255.0, (color & 255) / 255.0);
            switch (part) { case TITLE -> appearance.titleColor(rgb); case ENTRY -> appearance.entryColor(rgb); case PAGE_LABEL -> appearance.pageLabelColor(rgb); }
        });
        return new Navigation(bookmarks, new TableOfContents(source.enabled(), source.title(), typography(source.entries()), appearance.build()), List.of(), List.of());
    }

    private static ParagraphStyle typography(com.mirakyux.export4j.model.DocumentNavigation.TextStyle style) {
        return new ParagraphStyle(style.size(), style.lineHeight(), style.firstIndent(), style.subsequentIndent(),
                com.mirakyux.export4j.text.TextAlignment.valueOf(style.alignment().name()), style.lineBreak());
    }

    public static Navigation fromHeadings(Document document, TableOfContents contents, List<Comment> comments, List<Field> fields) {
        Objects.requireNonNull(document, "document");
        var bookmarks = document.blocks().stream().filter(Heading.class::isInstance).map(Heading.class::cast)
                .map(heading -> new Bookmark(heading.text(), heading.id(), heading.level())).toList();
        return new Navigation(bookmarks, contents, comments, fields);
    }

    public Navigation {
        bookmarks = List.copyOf(bookmarks);
        comments = List.copyOf(comments);
        fields = List.copyOf(fields);
        Objects.requireNonNull(contents, "contents");
        int level = 0;
        for (Bookmark bookmark : bookmarks) {
            if (bookmark.level() < 1 || bookmark.level() > level + 1) {
                throw new IllegalArgumentException("bookmark levels start at one and cannot skip");
            }
            level = bookmark.level();
        }
    }

    public record Bookmark(String title, String blockId, int level) {
        public Bookmark {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(blockId, "blockId");
            if (title.isBlank() || blockId.isBlank() || level < 1) {
                throw new IllegalArgumentException("invalid bookmark source");
            }
        }
    }

    public sealed interface Target permits BlockTarget, PageTarget {
    }

    /** Point offsets and positive dimensions measured from the first source block fragment's top-left corner. */
    public record BlockTarget(String blockId, Rect relativeBounds) implements Target {
        public BlockTarget {
            Objects.requireNonNull(blockId, "blockId");
            Objects.requireNonNull(relativeBounds, "relativeBounds");
            if (blockId.isBlank()) {
                throw new IllegalArgumentException("target block ID must not be blank");
            }
        }
    }

    /** Absolute/fractional/anchored positioning on a zero-based FINAL page, including any contents pages. */
    public record PageTarget(int pageIndex, Position position) implements Target {
        public PageTarget {
            Objects.requireNonNull(position, "position");
            if (pageIndex < 0) {
                throw new IllegalArgumentException("target page must be nonnegative");
            }
        }
    }

    public record Comment(Target target, String author, String text) {
        public Comment {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(author, "author");
            Objects.requireNonNull(text, "text");
        }
    }

    public sealed interface Field permits TextField, CheckBox, ChoiceField, RadioGroup {
        Target target();
        String name();
        FormStyle style();
    }

    public record TextField(Target target, String name, String value, boolean multiline,
            List<FontData> fonts, FormStyle style) implements Field {
        /** Task-confined builder; build validates and snapshots the complete value. */
        public static Builder builder() { return new Builder(); }
        public static final class Builder {
            private Target target = null;
            private String name = "";
            private String value = "";
            private boolean multiline = false;
            private List<FontData> fonts = new java.util.ArrayList<>();
            private FormStyle style = FormStyle.DEFAULT;
            private Builder() { }
            public Builder target(Target value) { target = java.util.Objects.requireNonNull(value, "target"); return this; }
            public Builder name(String value) { name = java.util.Objects.requireNonNull(value, "name"); return this; }
            public Builder value(String value) { this.value = Objects.requireNonNull(value, "value"); return this; }
            public Builder multiline(boolean value) { multiline = value; return this; }
            public Builder fonts(List<FontData> value) { fonts = new java.util.ArrayList<>(List.copyOf(value)); return this; }

            public Builder style(FormStyle value) { style = Objects.requireNonNull(value, "style"); return this; }
            /** Updates a copy of the current declarations and snapshots it before returning. */
            public Builder style(Consumer<FormStyle.Builder> action) {
                var child = style.toBuilder();
                Objects.requireNonNull(action, "action").accept(child);
                style = child.build();
                return this;
            }

            public TextField build() { return new TextField(target, name, value, multiline, fonts, style); }
        }

        public TextField {
            Objects.requireNonNull(target, "target");
            // Reuse the output contract so unsupported form text is rejected before layout.
            fonts = List.copyOf(fonts);
            new com.mirakyux.export4j.graphics.DocumentExtras.TextField(0, new Rect(0, 0, 1, 1), name, value, multiline, fonts, style);
        }

        public TextField(Target target, String name, String value, boolean multiline) {
            this(target, name, value, multiline, List.of(), FormStyle.DEFAULT);
        }

        /** Compatibility constructor retaining the text control's legacy appearance. */
        public TextField(Target target, String name, String value, boolean multiline, List<FontData> fonts) {
            this(target, name, value, multiline, fonts, FormStyle.DEFAULT);
        }
    }

    public record ChoiceField(Target target, String name, List<String> options, String value,
            List<FontData> fonts, FormStyle style) implements Field {
        /** Task-confined builder; build validates and snapshots the complete value. */
        public static Builder builder() { return new Builder(); }
        public static final class Builder {
            private Target target = null;
            private String name = "";
            private List<String> options = new java.util.ArrayList<>();
            private String value = "";
            private List<FontData> fonts = new java.util.ArrayList<>();
            private FormStyle style = FormStyle.DEFAULT;
            private Builder() { }
            public Builder target(Target value) { target = java.util.Objects.requireNonNull(value, "target"); return this; }
            public Builder name(String value) { name = java.util.Objects.requireNonNull(value, "name"); return this; }
            public Builder options(List<String> value) { options = new java.util.ArrayList<>(List.copyOf(value)); return this; }
            public Builder value(String value) { this.value = Objects.requireNonNull(value, "value"); return this; }
            public Builder fonts(List<FontData> value) { fonts = new java.util.ArrayList<>(List.copyOf(value)); return this; }

            public Builder style(FormStyle value) { style = Objects.requireNonNull(value, "style"); return this; }
            /** Updates a copy of the current declarations and snapshots it before returning. */
            public Builder style(Consumer<FormStyle.Builder> action) {
                var child = style.toBuilder();
                Objects.requireNonNull(action, "action").accept(child);
                style = child.build();
                return this;
            }

            public ChoiceField build() { return new ChoiceField(target, name, options, value, fonts, style); }
        }

        public ChoiceField {
            Objects.requireNonNull(target, "target");
            options = List.copyOf(options);
            fonts = List.copyOf(fonts);
            new com.mirakyux.export4j.graphics.DocumentExtras.ChoiceField(0, new Rect(0, 0, 1, 1), name, options, value, fonts, style);
        }

        /** Compatibility constructor retaining the choice control's legacy appearance. */
        public ChoiceField(Target target, String name, List<String> options, String value, List<FontData> fonts) {
            this(target, name, options, value, fonts, FormStyle.DEFAULT);
        }
    }

    public record RadioGroup(Target target, String name, List<String> options, String value, FormStyle style) implements Field {
        /** Task-confined builder; build validates and snapshots the complete value. */
        public static Builder builder() { return new Builder(); }
        public static final class Builder {
            private Target target = null;
            private String name = "";
            private List<String> options = new java.util.ArrayList<>();
            private String value = "";
            private FormStyle style = FormStyle.DEFAULT;
            private Builder() { }
            public Builder target(Target value) { target = java.util.Objects.requireNonNull(value, "target"); return this; }
            public Builder name(String value) { name = java.util.Objects.requireNonNull(value, "name"); return this; }
            public Builder options(List<String> value) { options = new java.util.ArrayList<>(List.copyOf(value)); return this; }
            public Builder value(String value) { this.value = Objects.requireNonNull(value, "value"); return this; }

            public Builder style(FormStyle value) { style = Objects.requireNonNull(value, "style"); return this; }
            /** Updates a copy of the current declarations and snapshots it before returning. */
            public Builder style(Consumer<FormStyle.Builder> action) {
                var child = style.toBuilder();
                Objects.requireNonNull(action, "action").accept(child);
                style = child.build();
                return this;
            }

            public RadioGroup build() { return new RadioGroup(target, name, options, value, style); }
        }

        public RadioGroup {
            Objects.requireNonNull(target, "target");
            options = List.copyOf(options);
            new com.mirakyux.export4j.graphics.DocumentExtras.RadioGroup(0, new Rect(0, 0, 1, 1), name, options, value, style);
        }

        /** Compatibility constructor retaining the radio control's legacy appearance. */
        public RadioGroup(Target target, String name, List<String> options, String value) {
            this(target, name, options, value, FormStyle.DEFAULT);
        }
    }

    public record CheckBox(Target target, String name, boolean checked, FormStyle style) implements Field {
        /** Task-confined builder; build validates and snapshots the complete value. */
        public static Builder builder() { return new Builder(); }
        public static final class Builder {
            private Target target = null;
            private String name = "";
            private boolean checked = false;
            private FormStyle style = FormStyle.DEFAULT;
            private Builder() { }
            public Builder target(Target value) { target = java.util.Objects.requireNonNull(value, "target"); return this; }
            public Builder name(String value) { name = java.util.Objects.requireNonNull(value, "name"); return this; }
            public Builder checked(boolean value) { checked = value; return this; }

            public Builder style(FormStyle value) { style = Objects.requireNonNull(value, "style"); return this; }
            /** Updates a copy of the current declarations and snapshots it before returning. */
            public Builder style(Consumer<FormStyle.Builder> action) {
                var child = style.toBuilder();
                Objects.requireNonNull(action, "action").accept(child);
                style = child.build();
                return this;
            }

            public CheckBox build() { return new CheckBox(target, name, checked, style); }
        }

        public CheckBox {
            Objects.requireNonNull(target, "target");
            new com.mirakyux.export4j.graphics.DocumentExtras.CheckBox(0, new Rect(0, 0, 1, 1), name, checked, style);
        }

        /** Compatibility constructor retaining the checkbox control's legacy appearance. */
        public CheckBox(Target target, String name, boolean checked) {
            this(target, name, checked, FormStyle.DEFAULT);
        }
    }
}
