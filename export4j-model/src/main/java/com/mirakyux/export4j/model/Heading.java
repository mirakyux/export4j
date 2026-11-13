// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;
import java.util.List;

/** Semantic heading, levels 1 through 6. Layout retains its ID; navigation can collect headings automatically. */
public record Heading(String id, String text, int level, ParagraphFormat format, List<InlineText> inlines,
        List<Paragraph.ImageSpan> images, List<Paragraph.FieldSpan> fields, List<Paragraph.VectorSpan> vectors) implements Block {
    public Heading {
        inlines = List.copyOf(inlines);
        images = List.copyOf(images); fields = List.copyOf(fields); vectors = List.copyOf(vectors);
        new Paragraph(id, text, format, inlines, images, fields, vectors);
        Objects.requireNonNull(format, "format");
        if (fields.stream().anyMatch(span -> span.field().kind() == InlineField.Kind.NOTE_MARKER)) {
            throw new IllegalArgumentException("note markers require a paragraph or list owner");
        }
        if (text.isBlank() || level < 1 || level > 6) {
            throw new IllegalArgumentException("heading requires text and a level from 1 through 6");
        }
    }

    /** 保留原有仅文字的完整构造器。 */
    public Heading(String id, String text, int level, ParagraphFormat format, List<InlineText> inlines) {
        this(id, text, level, format, inlines, List.of(), List.of(), List.of());
    }

    public Heading(String id, String text, int level, ParagraphFormat format) {
        this(id, text, level, format, List.of());
    }

    /** Styled text and external hyperlinks. */
    public static Heading rich(String id, List<InlineText> inlines, int level, ParagraphFormat format) {
        var paragraph = new Paragraph(id, inlines, format);
        return new Heading(id, paragraph.text(), level, format, paragraph.inlines());
    }

    /** 混合文字及显式行内对象，保留真实 UTF-16 位置和资源。 */
    public static Heading mixed(String id, List<? extends InlineContent> content, int level, ParagraphFormat format) {
        var paragraph = Paragraph.mixed(id, content, format);
        return new Heading(id, paragraph.text(), level, format, paragraph.inlines(), paragraph.images(), paragraph.fields(), paragraph.vectors());
    }

    public static Heading mixed(String id, List<? extends InlineContent> content, int level) {
        return mixed(id, content, level, defaultFormat(level));
    }

    public static Heading rich(String id, List<InlineText> inlines, int level) {
        return rich(id, inlines, level, defaultFormat(level));
    }

    /** Default heading stays whole and with the next block; explicit page boundaries take precedence. */
    public Heading(String id, String text, int level) {
        this(id, text, level, defaultFormat(level));
    }

    /** Default point-based size/spacing, keep-together and keep-with-next for levels 1 through 6. */
    public static ParagraphFormat defaultFormat(int level) {
        if (level < 1 || level > 6) { throw new IllegalArgumentException("heading level must be from 1 through 6"); }
        return new ParagraphFormat(26 - level * 2, 39 - level * 3, 0, 0,
                ParagraphAlignment.LEFT, 12, 8, true,
                new PaginationPolicy(true, false, 1, 1));
    }

    public Paragraph paragraph() {
        return new Paragraph(id, text, format, inlines, images, fields, vectors);
    }
}
