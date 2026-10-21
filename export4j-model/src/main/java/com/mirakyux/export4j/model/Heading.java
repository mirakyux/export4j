// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;
import java.util.List;

/** Semantic heading, levels 1 through 6. Layout retains its ID; navigation can collect headings automatically. */
public record Heading(String id, String text, int level, ParagraphFormat format, List<InlineText> inlines) implements Block {
    public Heading {
        inlines = List.copyOf(inlines);
        new Paragraph(id, text, format, inlines);
        Objects.requireNonNull(format, "format");
        if (text.isBlank() || level < 1 || level > 6) {
            throw new IllegalArgumentException("heading requires text and a level from 1 through 6");
        }
    }

    public Heading(String id, String text, int level, ParagraphFormat format) {
        this(id, text, level, format, List.of());
    }

    /** Styled text and external hyperlinks; images and fields are not heading content. */
    public static Heading rich(String id, List<InlineText> inlines, int level, ParagraphFormat format) {
        var paragraph = new Paragraph(id, inlines, format);
        return new Heading(id, paragraph.text(), level, format, paragraph.inlines());
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
        return new Paragraph(id, text, format, inlines);
    }
}
