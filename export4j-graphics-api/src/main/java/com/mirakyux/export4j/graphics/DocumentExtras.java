// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.font.FontData;

/** Resolved backend-neutral navigation/interaction data. Zero-based pages, top-left point coordinates, no actions/scripts. */
public record DocumentExtras(List<Bookmark> bookmarks, List<Link> links, List<Comment> comments, List<FormField> fields, List<ExternalLink> externalLinks) {
    public static final DocumentExtras EMPTY = new DocumentExtras(List.of(), List.of(), List.of(), List.of());

    public DocumentExtras {
        bookmarks = List.copyOf(bookmarks);
        links = List.copyOf(links);
        comments = List.copyOf(comments);
        fields = List.copyOf(fields);
        externalLinks = List.copyOf(externalLinks);
    }

    public DocumentExtras(List<Bookmark> bookmarks, List<Link> links, List<Comment> comments, List<FormField> fields) {
        this(bookmarks, links, comments, fields, List.of());
    }

    public record ExternalLink(int pageIndex, Rect bounds, com.mirakyux.export4j.model.ExternalDestination destination) {
        public ExternalLink {
            Objects.requireNonNull(bounds, "bounds"); Objects.requireNonNull(destination, "destination");
            if (pageIndex < 0) { throw new IllegalArgumentException("invalid link page"); }
        }
    }

    /** Ordered outline level starts at 1; hierarchy is validated by DisplayDocument. */
    public record Bookmark(String title, int level, int pageIndex, Point destination) {
        public Bookmark {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(destination, "destination");
            if (title.isBlank() || level < 1 || pageIndex < 0) {
                throw new IllegalArgumentException("invalid bookmark");
            }
        }
    }

    public record Link(int pageIndex, Rect bounds, int targetPageIndex, Point destination) {
        public Link {
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(destination, "destination");
            if (pageIndex < 0 || targetPageIndex < 0) {
                throw new IllegalArgumentException("invalid link page");
            }
        }
    }

    /** Text comment annotation, distinct from body text and form values. */
    public record Comment(int pageIndex, Rect bounds, String author, String text) {
        public Comment {
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(author, "author");
            Objects.requireNonNull(text, "text");
            if (pageIndex < 0 || text.isBlank()) {
                throw new IllegalArgumentException("invalid comment");
            }
        }
    }

    public sealed interface FormField permits TextField, CheckBox, ChoiceField, RadioGroup {
        int pageIndex();
        Rect bounds();
        String name();
        FormStyle style();
    }

    /** Editable text. Empty fonts use printable ASCII; one explicit full font enables Unicode. LF requires multiline. */
    public record TextField(int pageIndex, Rect bounds, String name, String value, boolean multiline,
            List<FontData> fonts, FormStyle style) implements FormField {
        public TextField {
            field(pageIndex, bounds, name);
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(style, "style");
            fonts = List.copyOf(fonts);
            boolean asciiOnly = fonts.isEmpty();
            if (fonts.size() > 1 || value.codePoints().anyMatch(cp -> Character.isISOControl(cp) && !(multiline && cp == '\n')
                    || cp >= 0xd800 && cp <= 0xdfff || asciiOnly && cp > 126)) {
                throw new IllegalArgumentException("form text requires valid scalars and printable text; Unicode needs one explicit font");
            }
        }

        public TextField(int pageIndex, Rect bounds, String name, String value, boolean multiline) {
            this(pageIndex, bounds, name, value, multiline, List.of(), FormStyle.DEFAULT);
        }

        /** Compatibility constructor retaining the text control's legacy appearance. */
        public TextField(int pageIndex, Rect bounds, String name, String value, boolean multiline, List<FontData> fonts) {
            this(pageIndex, bounds, name, value, multiline, fonts, FormStyle.DEFAULT);
        }
    }

    /** Closed dropdown with one selected export/display value. An explicit full font enables Unicode editing. */
    public record ChoiceField(int pageIndex, Rect bounds, String name, List<String> options, String value,
            List<FontData> fonts, FormStyle style) implements FormField {
        public ChoiceField {
            field(pageIndex, bounds, name);
            options = List.copyOf(options);
            fonts = List.copyOf(fonts);
            Objects.requireNonNull(style, "style");
            if (options.isEmpty() || options.size() > 10_000 || new java.util.HashSet<>(options).size() != options.size()
                    || !options.contains(value) || fonts.size() > 1) {
                throw new IllegalArgumentException("invalid choice values");
            }
            for (String option : options) {
                new TextField(pageIndex, bounds, name, option, false, fonts);
            }
        }

        /** Compatibility constructor retaining the choice control's legacy appearance. */
        public ChoiceField(int pageIndex, Rect bounds, String name, List<String> options, String value, List<FontData> fonts) {
            this(pageIndex, bounds, name, options, value, fonts, FormStyle.DEFAULT);
        }
    }

    /** One canonical radio group with equally spaced widgets across its bounds. Value must name an option. */
    public record RadioGroup(int pageIndex, Rect bounds, String name, List<String> options, String value,
            FormStyle style) implements FormField {
        public RadioGroup {
            field(pageIndex, bounds, name);
            options = List.copyOf(options);
            markStyle(style);
            if (options.size() < 2 || options.size() > 100 || new java.util.HashSet<>(options).size() != options.size()
                    || !options.contains(value) || options.stream().anyMatch(option -> !option.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))) {
                throw new IllegalArgumentException("invalid radio options");
            }
        }

        /** Compatibility constructor retaining the radio control's legacy appearance. */
        public RadioGroup(int pageIndex, Rect bounds, String name, List<String> options, String value) {
            this(pageIndex, bounds, name, options, value, FormStyle.DEFAULT);
        }
    }

    public record CheckBox(int pageIndex, Rect bounds, String name, boolean checked, FormStyle style) implements FormField {
        public CheckBox {
            field(pageIndex, bounds, name);
            markStyle(style);
        }

        /** Compatibility constructor retaining the checkbox control's legacy appearance. */
        public CheckBox(int pageIndex, Rect bounds, String name, boolean checked) {
            this(pageIndex, bounds, name, checked, FormStyle.DEFAULT);
        }
    }

    private static void markStyle(FormStyle style) {
        Objects.requireNonNull(style, "style");
        if (style.isExplicit(FormStyle.Property.FONT_SIZE) || style.isExplicit(FormStyle.Property.ALIGNMENT)) {
            throw new IllegalArgumentException("check and radio controls do not support font size or text alignment");
        }
    }

    private static void field(int page, Rect bounds, String name) {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(name, "name");
        if (page < 0 || !name.matches("[A-Za-z_][A-Za-z0-9_-]{0,127}")) {
            throw new IllegalArgumentException("invalid form page or flat field name");
        }
    }
}
