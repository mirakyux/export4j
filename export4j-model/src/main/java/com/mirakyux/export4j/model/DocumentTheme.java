// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Immutable authoring defaults and named complete styles, independent of fonts and backends.
 * Dimensions use points. Themes own no resources and may be shared between tasks; builders may not.
 * Document builders resolve defaults when content is added, without retaining a theme in the document.
 * Named inline styles remain complete values, including size, RGB and font role; they do not cascade.
 */
public final class DocumentTheme {
    private static final int MAX_NAMED_STYLES = 128;
    private static final Pattern STYLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z_0-9-]{0,63}");

    private final ParagraphFormat bodyFormat;
    private final List<ParagraphFormat> headingFormats;
    private final CellFormat cellFormat;
    private final boolean cellFormatExplicit;
    private final TableFormat tableFormat;
    private final Map<String, ParagraphFormat> paragraphStyles;
    private final Map<String, InlineStyle> inlineStyles;
    private final PageRegionStyle headerStyle;
    private final PageRegionStyle footerStyle;

    private DocumentTheme(Builder builder) {
        bodyFormat = builder.bodyFormat;
        headingFormats = List.copyOf(builder.headingFormats);
        cellFormat = builder.currentCellFormat();
        cellFormatExplicit = builder.cellFormatExplicit;
        tableFormat = builder.tableFormat;
        paragraphStyles = Collections.unmodifiableMap(new LinkedHashMap<>(builder.paragraphStyles));
        inlineStyles = Collections.unmodifiableMap(new LinkedHashMap<>(builder.inlineStyles));
        headerStyle = builder.headerStyle;
        footerStyle = builder.footerStyle;
    }

    /** Starts with the existing 12-point body, six heading formats and convenience-table defaults. */
    public static Builder builder() { return new Builder(); }

    /**
     * Starts an independent builder, preserving whether cell formatting follows the body.
     * Equality includes that intent: an explicitly supplied cell format remains fixed on body edits.
     */
    public Builder toBuilder() { return new Builder(this); }

    public ParagraphFormat bodyFormat() { return bodyFormat; }

    /** Returns the complete format for heading levels 1 through 6; other levels are rejected. */
    public ParagraphFormat headingFormat(int level) { return headingFormats.get(headingIndex(level)); }

    /** Returns the resolved convenience-cell format, derived from the body unless explicitly set. */
    public CellFormat cellFormat() { return cellFormat; }

    public TableFormat tableFormat() { return tableFormat; }

    /** Independent complete text defaults for fixed page headers; never derived from body/cell defaults. */
    public PageRegionStyle headerStyle() { return headerStyle; }
    /** Independent complete text defaults for fixed page footers. */
    public PageRegionStyle footerStyle() { return footerStyle; }

    /** Looks up a case-sensitive complete style; null, invalid and unknown names are rejected. */
    public ParagraphFormat paragraphStyle(String name) {
        validateName(name);
        var value = paragraphStyles.get(name);
        if (value == null) { throw new IllegalArgumentException("unknown paragraph style: " + name); }
        return value;
    }

    /** Looks up a case-sensitive complete style; null, invalid and unknown names are rejected. */
    public InlineStyle inlineStyle(String name) {
        validateName(name);
        var value = inlineStyles.get(name);
        if (value == null) { throw new IllegalArgumentException("unknown inline style: " + name); }
        return value;
    }

    /** Immutable snapshot in definition order; each named-style map contains at most 128 entries. */
    public Map<String, ParagraphFormat> paragraphStyles() { return paragraphStyles; }

    /** Immutable snapshot in definition order; each named-style map contains at most 128 entries. */
    public Map<String, InlineStyle> inlineStyles() { return inlineStyles; }

    private static int headingIndex(int level) {
        if (level < 1 || level > 6) { throw new IllegalArgumentException("heading level must be from 1 through 6"); }
        return level - 1;
    }

    private static void validateName(String name) {
        Objects.requireNonNull(name, "name");
        if (!STYLE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("style name must match [A-Za-z_][A-Za-z_0-9-]{0,63}");
        }
    }

    private static void validateRegistration(String name, Map<String, ?> styles) {
        validateName(name);
        if (!styles.containsKey(name) && styles.size() >= MAX_NAMED_STYLES) {
            throw new IllegalArgumentException("named style count exceeds limit of " + MAX_NAMED_STYLES);
        }
    }

    private static CellFormat cellFormatFrom(ParagraphFormat format) {
        return new CellFormat(format.fontSize(), format.lineHeight(), format.alignment(), format.lineBreakPolicy());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) { return true; }
        if (!(other instanceof DocumentTheme value)) { return false; }
        return cellFormatExplicit == value.cellFormatExplicit && bodyFormat.equals(value.bodyFormat)
                && headingFormats.equals(value.headingFormats) && cellFormat.equals(value.cellFormat)
                && tableFormat.equals(value.tableFormat) && paragraphStyles.equals(value.paragraphStyles)
                && inlineStyles.equals(value.inlineStyles) && headerStyle.equals(value.headerStyle)
                && footerStyle.equals(value.footerStyle);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bodyFormat, headingFormats, cellFormat, cellFormatExplicit,
                tableFormat, paragraphStyles, inlineStyles, headerStyle, footerStyle);
    }

    /**
     * Mutable, task-confined construction state. Callbacks run synchronously; setters snapshot their
     * completed values and never retain callbacks or mutable child builders. Null values are rejected.
     */
    public static final class Builder {
        private ParagraphFormat bodyFormat = ParagraphFormat.of(12);
        private final ArrayList<ParagraphFormat> headingFormats = new ArrayList<>(6);
        private CellFormat cellFormat = CellFormat.of(12);
        private boolean cellFormatExplicit;
        private TableFormat tableFormat = TableFormat.DEFAULT;
        private final Map<String, ParagraphFormat> paragraphStyles = new LinkedHashMap<>();
        private final Map<String, InlineStyle> inlineStyles = new LinkedHashMap<>();
        private PageRegionStyle headerStyle = PageRegionStyle.DEFAULT;
        private PageRegionStyle footerStyle = PageRegionStyle.DEFAULT;

        private Builder() {
            for (int level = 1; level <= 6; level++) { headingFormats.add(Heading.defaultFormat(level)); }
        }

        private Builder(DocumentTheme theme) {
            bodyFormat = theme.bodyFormat;
            headingFormats.addAll(theme.headingFormats);
            cellFormat = theme.cellFormat;
            cellFormatExplicit = theme.cellFormatExplicit;
            tableFormat = theme.tableFormat;
            paragraphStyles.putAll(theme.paragraphStyles);
            inlineStyles.putAll(theme.inlineStyles);
            headerStyle = theme.headerStyle;
            footerStyle = theme.footerStyle;
        }

        public Builder headerStyle(PageRegionStyle value) { headerStyle = Objects.requireNonNull(value, "headerStyle"); return this; }
        public Builder footerStyle(PageRegionStyle value) { footerStyle = Objects.requireNonNull(value, "footerStyle"); return this; }
        public Builder headerStyle(Consumer<PageRegionStyle.Builder> configure) {
            var value = headerStyle.toBuilder(); Objects.requireNonNull(configure, "configure").accept(value); return headerStyle(value.build());
        }
        public Builder footerStyle(Consumer<PageRegionStyle.Builder> configure) {
            var value = footerStyle.toBuilder(); Objects.requireNonNull(configure, "configure").accept(value); return footerStyle(value.build());
        }

        /** Replaces the complete body value; previously defined named styles remain unchanged. */
        public Builder bodyFormat(ParagraphFormat value) {
            bodyFormat = Objects.requireNonNull(value, "bodyFormat"); return this;
        }

        /** Edits the current complete body value, including its fixed line height. */
        public Builder bodyFormat(Consumer<ParagraphFormat.Builder> configure) {
            var value = bodyFormat.toBuilder();
            Objects.requireNonNull(configure, "configure").accept(value);
            return bodyFormat(value.build());
        }

        public Builder headingFormat(int level, ParagraphFormat value) {
            headingFormats.set(headingIndex(level), Objects.requireNonNull(value, "headingFormat")); return this;
        }

        /** Edits the current level's complete format, preserving unmodified pagination properties. */
        public Builder headingFormat(int level, Consumer<ParagraphFormat.Builder> configure) {
            var value = headingFormats.get(headingIndex(level)).toBuilder();
            Objects.requireNonNull(configure, "configure").accept(value);
            return headingFormat(level, value.build());
        }

        /** Makes convenience-cell formatting explicit, independent of subsequent body edits. */
        public Builder cellFormat(CellFormat value) {
            cellFormat = Objects.requireNonNull(value, "cellFormat"); cellFormatExplicit = true; return this;
        }

        /** Edits the current resolved cell value and makes the completed format explicit. */
        public Builder cellFormat(Consumer<CellFormat.Builder> configure) {
            var value = currentCellFormat().toBuilder();
            Objects.requireNonNull(configure, "configure").accept(value);
            return cellFormat(value.build());
        }

        public Builder tableFormat(TableFormat value) {
            tableFormat = Objects.requireNonNull(value, "tableFormat"); return this;
        }

        public Builder tableFormat(Consumer<TableFormat.Builder> configure) {
            var value = tableFormat.toBuilder();
            Objects.requireNonNull(configure, "configure").accept(value);
            return tableFormat(value.build());
        }

        /** Registers or replaces a complete style; names are case-sensitive and maps are bounded. */
        public Builder paragraphStyle(String name, ParagraphFormat value) {
            validateRegistration(name, paragraphStyles);
            paragraphStyles.put(name, Objects.requireNonNull(value, "paragraphStyle")); return this;
        }

        /**
         * New styles start from the current body; an existing name starts from its current complete
         * value. Name and capacity validation precede the callback. Later body edits do not cascade.
         */
        public Builder paragraphStyle(String name, Consumer<ParagraphFormat.Builder> configure) {
            validateRegistration(name, paragraphStyles);
            var value = paragraphStyles.getOrDefault(name, bodyFormat).toBuilder();
            Objects.requireNonNull(configure, "configure").accept(value);
            return paragraphStyle(name, value.build());
        }

        /** Registers or replaces a complete style; names are case-sensitive and maps are bounded. */
        public Builder inlineStyle(String name, InlineStyle value) {
            validateRegistration(name, inlineStyles);
            inlineStyles.put(name, Objects.requireNonNull(value, "inlineStyle")); return this;
        }

        /**
         * New styles start from black, undecorated text at the current body size and default font
         * role; an existing name starts from its complete value. Later body edits do not cascade.
         */
        public Builder inlineStyle(String name, Consumer<InlineStyle.Builder> configure) {
            validateRegistration(name, inlineStyles);
            var value = inlineStyles.getOrDefault(name, InlineStyle.of(bodyFormat.fontSize())).toBuilder();
            Objects.requireNonNull(configure, "configure").accept(value);
            return inlineStyle(name, value.build());
        }

        private CellFormat currentCellFormat() {
            return cellFormatExplicit ? cellFormat : cellFormatFrom(bodyFormat);
        }

        /** Takes immutable structural snapshots and resolves an implicit cell format from the body. */
        public DocumentTheme build() { return new DocumentTheme(this); }
    }
}
