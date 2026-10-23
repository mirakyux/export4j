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
    private final TextAppearance text;
    private final TextOverride bodyText;
    private final TextOverride cellText;
    private final List<TextOverride> headingText;
    private final TextOverride headerText;
    private final TextOverride footerText;
    private final TextOverride backgroundText;
    private final TextOverride foregroundText;
    private final PageRegionStyle headerStyle;
    private final PageRegionStyle footerStyle;
    private final PageRegionStyle backgroundStyle;
    private final PageRegionStyle foregroundStyle;

    private DocumentTheme(Builder builder) {
        bodyFormat = builder.bodyFormat;
        headingFormats = List.copyOf(builder.headingFormats);
        cellFormat = builder.currentCellFormat();
        cellFormatExplicit = builder.cellFormatExplicit;
        tableFormat = builder.tableFormat;
        paragraphStyles = Collections.unmodifiableMap(new LinkedHashMap<>(builder.paragraphStyles));
        inlineStyles = Collections.unmodifiableMap(new LinkedHashMap<>(builder.inlineStyles));
        text = builder.text;
        bodyText = builder.bodyText; cellText = builder.cellText;
        headingText = List.copyOf(builder.headingText);
        headerText = builder.headerText; footerText = builder.footerText;
        headerStyle = regionStyle(builder.headerStyle, headerText.resolve(text));
        footerStyle = regionStyle(builder.footerStyle, footerText.resolve(text));
        backgroundText = builder.backgroundText; foregroundText = builder.foregroundText;
        backgroundStyle = regionStyle(builder.backgroundStyle, backgroundText.resolve(text));
        foregroundStyle = regionStyle(builder.foregroundStyle, foregroundText.resolve(text));
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

    /** Global defaults; body, headings, cells and page bands inherit each undeclared property. */
    public TextAppearance text() { return text; }
    public TextAppearance bodyText() { return bodyText.resolve(text); }
    public TextAppearance cellText() { return cellText.resolve(text); }
    public TextAppearance headingText(int level) { return headingText.get(headingIndex(level)).resolve(text); }

    /** Resolved header defaults, including global appearance for undeclared properties. */
    public PageRegionStyle headerStyle() { return headerStyle; }
    /** Resolved footer defaults, independent of body and header overrides. */
    public PageRegionStyle footerStyle() { return footerStyle; }
    /** Resolved background-box defaults, independent of body and band overrides. */
    public PageRegionStyle backgroundStyle() { return backgroundStyle; }
    /** Resolved foreground-box defaults, independent of all other domains. */
    public PageRegionStyle foregroundStyle() { return foregroundStyle; }

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

    private static PageRegionStyle regionStyle(PageRegionStyle style, TextAppearance text) {
        return new PageRegionStyle(style.format(), text.fontRole(), text.rgb());
    }

    /** Nullable members encode declaration intent, including explicit empty role and black. */
    private record TextOverride(String fontRole, Integer rgb) {
        private static final TextOverride INHERIT = new TextOverride(null, null);
        TextAppearance resolve(TextAppearance base) {
            return new TextAppearance(fontRole == null ? base.fontRole() : fontRole, rgb == null ? base.rgb() : rgb);
        }
        TextOverride role(String value) {
            if (value != null) { new TextAppearance(value, 0); }
            return new TextOverride(value, rgb);
        }
        TextOverride color(Integer value) {
            if (value != null) { new TextAppearance("", value); }
            return new TextOverride(fontRole, value);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) { return true; }
        if (!(other instanceof DocumentTheme value)) { return false; }
        return cellFormatExplicit == value.cellFormatExplicit && bodyFormat.equals(value.bodyFormat)
                && headingFormats.equals(value.headingFormats) && cellFormat.equals(value.cellFormat)
                && tableFormat.equals(value.tableFormat) && paragraphStyles.equals(value.paragraphStyles)
                && inlineStyles.equals(value.inlineStyles) && headerStyle.equals(value.headerStyle)
                && footerStyle.equals(value.footerStyle) && text.equals(value.text)
                && bodyText.equals(value.bodyText) && cellText.equals(value.cellText)
                && headingText.equals(value.headingText) && headerText.equals(value.headerText)
                && footerText.equals(value.footerText) && backgroundText.equals(value.backgroundText)
                && foregroundText.equals(value.foregroundText) && backgroundStyle.equals(value.backgroundStyle)
                && foregroundStyle.equals(value.foregroundStyle);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bodyFormat, headingFormats, cellFormat, cellFormatExplicit,
                tableFormat, paragraphStyles, inlineStyles, headerStyle, footerStyle,
                text, bodyText, cellText, headingText, headerText, footerText,
                backgroundText, foregroundText, backgroundStyle, foregroundStyle);
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
        private TextAppearance text = TextAppearance.DEFAULT;
        private TextOverride bodyText = TextOverride.INHERIT;
        private TextOverride cellText = TextOverride.INHERIT;
        private final ArrayList<TextOverride> headingText = new ArrayList<>(Collections.nCopies(6, TextOverride.INHERIT));
        private TextOverride headerText = TextOverride.INHERIT;
        private TextOverride footerText = TextOverride.INHERIT;
        private TextOverride backgroundText = TextOverride.INHERIT;
        private TextOverride foregroundText = TextOverride.INHERIT;
        private PageRegionStyle headerStyle = PageRegionStyle.DEFAULT;
        private PageRegionStyle footerStyle = PageRegionStyle.DEFAULT;
        private PageRegionStyle backgroundStyle = PageRegionStyle.DEFAULT;
        private PageRegionStyle foregroundStyle = PageRegionStyle.DEFAULT;

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
            text = theme.text; bodyText = theme.bodyText; cellText = theme.cellText;
            headingText.clear(); headingText.addAll(theme.headingText);
            headerText = theme.headerText; footerText = theme.footerText;
            headerStyle = theme.headerStyle;
            footerStyle = theme.footerStyle;
            backgroundText = theme.backgroundText; foregroundText = theme.foregroundText;
            backgroundStyle = theme.backgroundStyle; foregroundStyle = theme.foregroundStyle;
        }

        /** Complete snapshot: both role and RGB become explicit, including empty role and black. */
        public Builder headerStyle(PageRegionStyle value) {
            headerStyle = Objects.requireNonNull(value, "headerStyle");
            headerText = new TextOverride(value.fontRole(), value.rgb()); return this;
        }
        /** Complete snapshot, with the same declaration semantics as headerStyle. */
        public Builder footerStyle(PageRegionStyle value) {
            footerStyle = Objects.requireNonNull(value, "footerStyle");
            footerText = new TextOverride(value.fontRole(), value.rgb()); return this;
        }
        public Builder headerStyle(Consumer<PageRegionStyle.Builder> configure) {
            var value = regionStyle(headerStyle, headerText.resolve(text)).toBuilder(); Objects.requireNonNull(configure, "configure").accept(value); return headerStyle(value.build());
        }
        public Builder footerStyle(Consumer<PageRegionStyle.Builder> configure) {
            var value = regionStyle(footerStyle, footerText.resolve(text)).toBuilder(); Objects.requireNonNull(configure, "configure").accept(value); return footerStyle(value.build());
        }


        /** Complete snapshot for background boxes, including explicit empty role and black. */
        public Builder backgroundStyle(PageRegionStyle value) {
            backgroundStyle = Objects.requireNonNull(value, "backgroundStyle");
            backgroundText = new TextOverride(value.fontRole(), value.rgb()); return this;
        }
        public Builder backgroundStyle(Consumer<PageRegionStyle.Builder> configure) {
            var value = regionStyle(backgroundStyle, backgroundText.resolve(text)).toBuilder();
            Objects.requireNonNull(configure, "configure").accept(value); return backgroundStyle(value.build());
        }
        /** Declares only this overlay layer's font role or color. */
        public Builder backgroundFontRole(String value) { backgroundText = backgroundText.role(Objects.requireNonNull(value, "fontRole")); return this; }
        public Builder backgroundRgb(int value) { backgroundText = backgroundText.color(value); return this; }
        /** Clears just this property, restoring the global default. */
        public Builder inheritBackgroundFontRole() { backgroundText = backgroundText.role(null); return this; }
        public Builder inheritBackgroundRgb() { backgroundText = backgroundText.color(null); return this; }

        /** Complete snapshot for foreground boxes, including explicit empty role and black. */
        public Builder foregroundStyle(PageRegionStyle value) {
            foregroundStyle = Objects.requireNonNull(value, "foregroundStyle");
            foregroundText = new TextOverride(value.fontRole(), value.rgb()); return this;
        }
        public Builder foregroundStyle(Consumer<PageRegionStyle.Builder> configure) {
            var value = regionStyle(foregroundStyle, foregroundText.resolve(text)).toBuilder();
            Objects.requireNonNull(configure, "configure").accept(value); return foregroundStyle(value.build());
        }
        /** Declares only this overlay layer's font role or color. */
        public Builder foregroundFontRole(String value) { foregroundText = foregroundText.role(Objects.requireNonNull(value, "fontRole")); return this; }
        public Builder foregroundRgb(int value) { foregroundText = foregroundText.color(value); return this; }
        /** Clears just this property, restoring the global default. */
        public Builder inheritForegroundFontRole() { foregroundText = foregroundText.role(null); return this; }
        public Builder inheritForegroundRgb() { foregroundText = foregroundText.color(null); return this; }

        /** Replaces a global property; undeclared domain properties follow later global edits. */
        public Builder fontRole(String value) { text = new TextAppearance(value, text.rgb()); return this; }
        public Builder rgb(int value) { text = new TextAppearance(text.fontRole(), value); return this; }

        /** Declares only this domain's role; empty selects the explicit default font group. */
        public Builder bodyFontRole(String value) { bodyText = bodyText.role(Objects.requireNonNull(value, "fontRole")); return this; }
        /** Declares only this domain's RGB; black is an explicit override. */
        public Builder bodyRgb(int value) { bodyText = bodyText.color(value); return this; }
        /** Restores the domain's role to the global default, preserving its color declaration. */
        public Builder inheritBodyFontRole() { bodyText = bodyText.role(null); return this; }
        /** Restores the domain's color to the global default, preserving its role declaration. */
        public Builder inheritBodyRgb() { bodyText = bodyText.color(null); return this; }

        /** Declares only this domain's role; empty selects the explicit default font group. */
        public Builder cellFontRole(String value) { cellText = cellText.role(Objects.requireNonNull(value, "fontRole")); return this; }
        /** Declares only this domain's RGB; black is an explicit override. */
        public Builder cellRgb(int value) { cellText = cellText.color(value); return this; }
        /** Restores the domain's role to the global default, preserving its color declaration. */
        public Builder inheritCellFontRole() { cellText = cellText.role(null); return this; }
        /** Restores the domain's color to the global default, preserving its role declaration. */
        public Builder inheritCellRgb() { cellText = cellText.color(null); return this; }

        /** Declares only this domain's role; empty selects the explicit default font group. */
        public Builder headerFontRole(String value) { headerText = headerText.role(Objects.requireNonNull(value, "fontRole")); return this; }
        /** Declares only this domain's RGB; black is an explicit override. */
        public Builder headerRgb(int value) { headerText = headerText.color(value); return this; }
        /** Restores the domain's role to the global default, preserving its color declaration. */
        public Builder inheritHeaderFontRole() { headerText = headerText.role(null); return this; }
        /** Restores the domain's color to the global default, preserving its role declaration. */
        public Builder inheritHeaderRgb() { headerText = headerText.color(null); return this; }

        /** Declares only this domain's role; empty selects the explicit default font group. */
        public Builder footerFontRole(String value) { footerText = footerText.role(Objects.requireNonNull(value, "fontRole")); return this; }
        /** Declares only this domain's RGB; black is an explicit override. */
        public Builder footerRgb(int value) { footerText = footerText.color(value); return this; }
        /** Restores the domain's role to the global default, preserving its color declaration. */
        public Builder inheritFooterFontRole() { footerText = footerText.role(null); return this; }
        /** Restores the domain's color to the global default, preserving its role declaration. */
        public Builder inheritFooterRgb() { footerText = footerText.color(null); return this; }

        /** Declares one property for heading level 1..6, independent of body/cell defaults. */
        public Builder headingFontRole(int level, String value) {
            int index = headingIndex(level);
            headingText.set(index, headingText.get(index).role(Objects.requireNonNull(value, "fontRole"))); return this;
        }
        public Builder headingRgb(int level, int value) {
            int index = headingIndex(level); headingText.set(index, headingText.get(index).color(value)); return this;
        }
        /** Restores only the selected heading property to the global default. */
        public Builder inheritHeadingFontRole(int level) {
            int index = headingIndex(level); headingText.set(index, headingText.get(index).role(null)); return this;
        }
        public Builder inheritHeadingRgb(int level) {
            int index = headingIndex(level); headingText.set(index, headingText.get(index).color(null)); return this;
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
