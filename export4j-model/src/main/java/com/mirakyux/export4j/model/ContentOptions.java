// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable authoring declarations. Undeclared properties inherit independently; explicit zero,
 * false, black and an empty font role remain declarations. Geometry uses points. This value owns no
 * resources and never enters the finished Document. Builders are confined to the calling task. */
public final class ContentOptions {
    public enum Format { INHERIT, TEXT, MARKDOWN, HTML }
    public enum Toggle { INHERIT, ENABLED, DISABLED }

    private final String fontRole;
    private final Integer rgb;
    private final Double fontSize;
    private final Double lineHeight;
    private final Double firstLineIndent;
    private final Double subsequentLineIndent;
    private final ParagraphAlignment alignment;
    private final Double spaceBefore;
    private final Double spaceAfter;
    private final Boolean keepTogether;
    private final Boolean keepWithNext;
    private final Boolean pageBreakBefore;
    private final Integer minLinesBeforeBreak;
    private final Integer minLinesAfterBreak;
    private final LineBreakPolicy lineBreakPolicy;
    private final Double rotation;
    private final CellFormat cellFormat;
    private final TableFormat tableFormat;
    private final Format format;
    private final Toggle formulas;
    private final Toggle tables;
    private final Toggle rawHtml;

    private ContentOptions(Builder value) {
        fontRole = value.fontRole;
        rgb = value.rgb;
        fontSize = value.fontSize;
        lineHeight = value.lineHeight;
        firstLineIndent = value.firstLineIndent;
        subsequentLineIndent = value.subsequentLineIndent;
        alignment = value.alignment;
        spaceBefore = value.spaceBefore;
        spaceAfter = value.spaceAfter;
        keepTogether = value.keepTogether;
        keepWithNext = value.keepWithNext;
        pageBreakBefore = value.pageBreakBefore;
        minLinesBeforeBreak = value.minLinesBeforeBreak;
        minLinesAfterBreak = value.minLinesAfterBreak;
        lineBreakPolicy = value.lineBreakPolicy;
        rotation = value.rotation;
        cellFormat = value.cellFormat;
        tableFormat = value.tableFormat;
        format = value.format; formulas = value.formulas; tables = value.tables; rawHtml = value.rawHtml;
        resolve(defaults());
    }

    public static Builder builder() { return new Builder(); }
    public Builder toBuilder() { return new Builder(this); }

    /** Combines declarations without resolving inherited values. Explicit declarations in
     * {@code overrides} replace this value; undeclared properties keep their original intent.
     * In particular, zero, false and the empty default-font role remain explicit overrides.
     */
    public ContentOptions overlay(ContentOptions overrides) {
        Objects.requireNonNull(overrides, "overrides");
        var result = toBuilder();
        if (overrides.fontRole != null) { result.fontRole = overrides.fontRole; }
        if (overrides.rgb != null) { result.rgb = overrides.rgb; }
        if (overrides.fontSize != null) { result.fontSize = overrides.fontSize; }
        if (overrides.lineHeight != null) { result.lineHeight = overrides.lineHeight; }
        if (overrides.firstLineIndent != null) { result.firstLineIndent = overrides.firstLineIndent; }
        if (overrides.subsequentLineIndent != null) { result.subsequentLineIndent = overrides.subsequentLineIndent; }
        if (overrides.alignment != null) { result.alignment = overrides.alignment; }
        if (overrides.spaceBefore != null) { result.spaceBefore = overrides.spaceBefore; }
        if (overrides.spaceAfter != null) { result.spaceAfter = overrides.spaceAfter; }
        if (overrides.keepTogether != null) { result.keepTogether = overrides.keepTogether; }
        if (overrides.keepWithNext != null) { result.keepWithNext = overrides.keepWithNext; }
        if (overrides.pageBreakBefore != null) { result.pageBreakBefore = overrides.pageBreakBefore; }
        if (overrides.minLinesBeforeBreak != null) { result.minLinesBeforeBreak = overrides.minLinesBeforeBreak; }
        if (overrides.minLinesAfterBreak != null) { result.minLinesAfterBreak = overrides.minLinesAfterBreak; }
        if (overrides.lineBreakPolicy != null) { result.lineBreakPolicy = overrides.lineBreakPolicy; }
        if (overrides.rotation != null) { result.rotation = overrides.rotation; }
        if (overrides.cellFormat != null) { result.cellFormat = overrides.cellFormat; }
        if (overrides.tableFormat != null) { result.tableFormat = overrides.tableFormat; }
        if (overrides.format != Format.INHERIT) { result.format = overrides.format; }
        if (overrides.formulas != Toggle.INHERIT) { result.formulas = overrides.formulas; }
        if (overrides.tables != Toggle.INHERIT) { result.tables = overrides.tables; }
        if (overrides.rawHtml != Toggle.INHERIT) { result.rawHtml = overrides.rawHtml; }
        return result.build();
    }

    /** Immutable heading defaults. Geometry uses points and no resources are retained. */
    public record SemanticStyle(TextAppearance text, ParagraphFormat paragraphFormat) {
        public SemanticStyle {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(paragraphFormat, "paragraphFormat");
        }
    }

    /** Independent semantic defaults: exactly six heading levels and the cell text appearance.
     * The heading list is defensively copied; instances are safe to share between parser sessions.
     */
    public record SemanticStyles(List<SemanticStyle> headings, TextAppearance cellText) {
        public SemanticStyles {
            Objects.requireNonNull(headings, "headings");
            if (headings.size() != 6) { throw new IllegalArgumentException("exactly six heading styles are required"); }
            headings = List.copyOf(headings);
            Objects.requireNonNull(cellText, "cellText");
        }
        /** Returns the defaults for a heading level from 1 to 6, inclusive. */
        public SemanticStyle heading(int level) {
            if (level < 1 || level > 6) { throw new IllegalArgumentException("heading level must be between 1 and 6"); }
            return headings.get(level - 1);
        }
        private static SemanticStyles uniform(String fontRole, int rgb, ParagraphFormat paragraph) {
            var text = new TextAppearance(fontRole, rgb);
            return new SemanticStyles(Collections.nCopies(6, new SemanticStyle(text, paragraph)), text);
        }
    }

    /** Complete values used by one parser invocation; no member remains INHERIT.
     * The compatibility constructors retain the historic uniform body/heading/cell appearance.
     */
    public record Resolved(String fontRole, int rgb, ParagraphFormat paragraphFormat, CellFormat cellFormat,
            TableFormat tableFormat, Format format, boolean formulas, boolean tables, boolean rawHtml,
            SemanticStyles styles) {
        public Resolved {
            new TextAppearance(fontRole, rgb);
            Objects.requireNonNull(paragraphFormat, "paragraphFormat");
            Objects.requireNonNull(cellFormat, "cellFormat");
            Objects.requireNonNull(tableFormat, "tableFormat");
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(styles, "styles");
            if (format == Format.INHERIT) { throw new IllegalArgumentException("resolved content format cannot inherit"); }
        }
        public TextAppearance textAppearance() { return new TextAppearance(fontRole, rgb); }
        /** Compatibility shape with uniform semantic styles. */
        public Resolved(String fontRole, int rgb, ParagraphFormat paragraphFormat, CellFormat cellFormat,
                TableFormat tableFormat, Format format, boolean formulas, boolean tables, boolean rawHtml) {
            this(fontRole, rgb, paragraphFormat, cellFormat, tableFormat, format, formulas, tables, rawHtml,
                    SemanticStyles.uniform(fontRole, rgb, paragraphFormat));
        }
        /** Compatibility shape: Markdown HTML remains disabled unless explicitly requested. */
        public Resolved(String fontRole, int rgb, ParagraphFormat paragraphFormat, CellFormat cellFormat,
                TableFormat tableFormat, Format format, boolean formulas, boolean tables) {
            this(fontRole, rgb, paragraphFormat, cellFormat, tableFormat, format, formulas, tables, false);
        }
    }

    /** Plain text, formulas disabled, tables enabled when the selected grammar supports them. */
    public static Resolved defaults() {
        var theme = DocumentTheme.builder().build();
        return new Resolved(theme.bodyText().fontRole(), theme.bodyText().rgb(), theme.bodyFormat(),
                theme.cellFormat(), theme.tableFormat(), Format.TEXT, false, true);
    }

    /** Takes independent body, heading, cell and table snapshots without retaining the theme.
     * Explicit content declarations subsequently override each applicable semantic domain.
     */
    public static Resolved fromTheme(DocumentTheme theme) {
        Objects.requireNonNull(theme, "theme");
        var headings = new ArrayList<SemanticStyle>(6);
        for (int level = 1; level <= 6; level++) {
            headings.add(new SemanticStyle(theme.headingText(level), theme.headingFormat(level)));
        }
        return new Resolved(theme.bodyText().fontRole(), theme.bodyText().rgb(), theme.bodyFormat(),
                theme.cellFormat(), theme.tableFormat(), Format.TEXT, false, true, false,
                new SemanticStyles(headings, theme.cellText()));
    }

    public Resolved resolve(Resolved parent) {
        Objects.requireNonNull(parent, "parent");
        var headings = new ArrayList<SemanticStyle>(6);
        for (var heading : parent.styles().headings()) {
            headings.add(new SemanticStyle(resolveText(heading.text()), resolveParagraph(heading.paragraphFormat())));
        }
        return new Resolved(fontRole == null ? parent.fontRole() : fontRole, rgb == null ? parent.rgb() : rgb,
                resolveParagraph(parent.paragraphFormat()), cellFormat == null ? parent.cellFormat() : cellFormat,
                tableFormat == null ? parent.tableFormat() : tableFormat,
                format == Format.INHERIT ? parent.format() : format,
                formulas == Toggle.INHERIT ? parent.formulas() : formulas == Toggle.ENABLED,
                tables == Toggle.INHERIT ? parent.tables() : tables == Toggle.ENABLED,
                rawHtml == Toggle.INHERIT ? parent.rawHtml() : rawHtml == Toggle.ENABLED,
                new SemanticStyles(headings, resolveText(parent.styles().cellText())));
    }

    private TextAppearance resolveText(TextAppearance parent) {
        return new TextAppearance(fontRole == null ? parent.fontRole() : fontRole, rgb == null ? parent.rgb() : rgb);
    }

    private ParagraphFormat resolveParagraph(ParagraphFormat p) {
        var pagination = p.pagination();
        var resolvedPagination = new PaginationPolicy(
                keepWithNext == null ? pagination.keepWithNext() : keepWithNext,
                pageBreakBefore == null ? pagination.pageBreakBefore() : pageBreakBefore,
                minLinesBeforeBreak == null ? pagination.minLinesBeforeBreak() : minLinesBeforeBreak,
                minLinesAfterBreak == null ? pagination.minLinesAfterBreak() : minLinesAfterBreak);
        return new ParagraphFormat(
                fontSize == null ? p.fontSize() : fontSize,
                lineHeight == null ? p.lineHeight() : lineHeight,
                firstLineIndent == null ? p.firstLineIndent() : firstLineIndent,
                subsequentLineIndent == null ? p.subsequentLineIndent() : subsequentLineIndent,
                alignment == null ? p.alignment() : alignment,
                spaceBefore == null ? p.spaceBefore() : spaceBefore,
                spaceAfter == null ? p.spaceAfter() : spaceAfter,
                keepTogether == null ? p.keepTogether() : keepTogether,
                resolvedPagination, lineBreakPolicy == null ? p.lineBreakPolicy() : lineBreakPolicy,
                rotation == null ? p.rotation() : rotation);
    }

    @Override public boolean equals(Object other) {
        if (this == other) { return true; }
        if (!(other instanceof ContentOptions value)) { return false; }
        return Objects.equals(fontRole, value.fontRole)
                && Objects.equals(rgb, value.rgb)
                && Objects.equals(fontSize, value.fontSize)
                && Objects.equals(lineHeight, value.lineHeight)
                && Objects.equals(firstLineIndent, value.firstLineIndent)
                && Objects.equals(subsequentLineIndent, value.subsequentLineIndent)
                && Objects.equals(alignment, value.alignment)
                && Objects.equals(spaceBefore, value.spaceBefore)
                && Objects.equals(spaceAfter, value.spaceAfter)
                && Objects.equals(keepTogether, value.keepTogether)
                && Objects.equals(keepWithNext, value.keepWithNext)
                && Objects.equals(pageBreakBefore, value.pageBreakBefore)
                && Objects.equals(minLinesBeforeBreak, value.minLinesBeforeBreak)
                && Objects.equals(minLinesAfterBreak, value.minLinesAfterBreak)
                && Objects.equals(lineBreakPolicy, value.lineBreakPolicy)
                && Objects.equals(rotation, value.rotation)
                && Objects.equals(cellFormat, value.cellFormat)
                && Objects.equals(tableFormat, value.tableFormat)
                && format == value.format
                && formulas == value.formulas
                && tables == value.tables
                && rawHtml == value.rawHtml;
    }
    @Override public int hashCode() {
        return Objects.hash(fontRole, rgb, fontSize, lineHeight, firstLineIndent, subsequentLineIndent, alignment, spaceBefore, spaceAfter, keepTogether, keepWithNext, pageBreakBefore, minLinesBeforeBreak, minLinesAfterBreak, lineBreakPolicy, rotation, cellFormat, tableFormat, format, formulas, tables, rawHtml);
    }

    public static final class Builder {
        private String fontRole;
        private Integer rgb;
        private Double fontSize;
        private Double lineHeight;
        private Double firstLineIndent;
        private Double subsequentLineIndent;
        private ParagraphAlignment alignment;
        private Double spaceBefore;
        private Double spaceAfter;
        private Boolean keepTogether;
        private Boolean keepWithNext;
        private Boolean pageBreakBefore;
        private Integer minLinesBeforeBreak;
        private Integer minLinesAfterBreak;
        private LineBreakPolicy lineBreakPolicy;
        private Double rotation;
        private CellFormat cellFormat;
        private TableFormat tableFormat;
        private Format format = Format.INHERIT;
        private Toggle formulas = Toggle.INHERIT;
        private Toggle tables = Toggle.INHERIT;
        private Toggle rawHtml = Toggle.INHERIT;
        private Builder() { }
        private Builder(ContentOptions value) {
            fontRole = value.fontRole;
            rgb = value.rgb;
            fontSize = value.fontSize;
            lineHeight = value.lineHeight;
            firstLineIndent = value.firstLineIndent;
            subsequentLineIndent = value.subsequentLineIndent;
            alignment = value.alignment;
            spaceBefore = value.spaceBefore;
            spaceAfter = value.spaceAfter;
            keepTogether = value.keepTogether;
            keepWithNext = value.keepWithNext;
            pageBreakBefore = value.pageBreakBefore;
            minLinesBeforeBreak = value.minLinesBeforeBreak;
            minLinesAfterBreak = value.minLinesAfterBreak;
            lineBreakPolicy = value.lineBreakPolicy;
            rotation = value.rotation;
            cellFormat = value.cellFormat;
            tableFormat = value.tableFormat;
            format = value.format; formulas = value.formulas; tables = value.tables; rawHtml = value.rawHtml;
        }
        public Builder fontRole(String value) { fontRole = Objects.requireNonNull(value, "fontRole"); return this; }
        public Builder inheritFontRole() { fontRole = null; return this; }
        public Builder rgb(int value) { rgb = value; return this; }
        public Builder inheritRgb() { rgb = null; return this; }
        public Builder fontSize(double value) { fontSize = value; return this; }
        public Builder inheritFontSize() { fontSize = null; return this; }
        public Builder lineHeight(double value) { lineHeight = value; return this; }
        public Builder inheritLineHeight() { lineHeight = null; return this; }
        public Builder firstLineIndent(double value) { firstLineIndent = value; return this; }
        public Builder inheritFirstLineIndent() { firstLineIndent = null; return this; }
        public Builder subsequentLineIndent(double value) { subsequentLineIndent = value; return this; }
        public Builder inheritSubsequentLineIndent() { subsequentLineIndent = null; return this; }
        public Builder alignment(ParagraphAlignment value) { alignment = Objects.requireNonNull(value, "alignment"); return this; }
        public Builder inheritAlignment() { alignment = null; return this; }
        public Builder spaceBefore(double value) { spaceBefore = value; return this; }
        public Builder inheritSpaceBefore() { spaceBefore = null; return this; }
        public Builder spaceAfter(double value) { spaceAfter = value; return this; }
        public Builder inheritSpaceAfter() { spaceAfter = null; return this; }
        public Builder keepTogether(boolean value) { keepTogether = value; return this; }
        public Builder inheritKeepTogether() { keepTogether = null; return this; }
        public Builder keepWithNext(boolean value) { keepWithNext = value; return this; }
        public Builder inheritKeepWithNext() { keepWithNext = null; return this; }
        public Builder pageBreakBefore(boolean value) { pageBreakBefore = value; return this; }
        public Builder inheritPageBreakBefore() { pageBreakBefore = null; return this; }
        public Builder minLinesBeforeBreak(int value) { minLinesBeforeBreak = value; return this; }
        public Builder inheritMinLinesBeforeBreak() { minLinesBeforeBreak = null; return this; }
        public Builder minLinesAfterBreak(int value) { minLinesAfterBreak = value; return this; }
        public Builder inheritMinLinesAfterBreak() { minLinesAfterBreak = null; return this; }
        public Builder lineBreakPolicy(LineBreakPolicy value) { lineBreakPolicy = Objects.requireNonNull(value, "lineBreakPolicy"); return this; }
        public Builder inheritLineBreakPolicy() { lineBreakPolicy = null; return this; }
        public Builder rotation(double value) { rotation = value; return this; }
        public Builder inheritRotation() { rotation = null; return this; }
        public Builder cellFormat(CellFormat value) { cellFormat = Objects.requireNonNull(value, "cellFormat"); return this; }
        public Builder inheritCellFormat() { cellFormat = null; return this; }
        public Builder tableFormat(TableFormat value) { tableFormat = Objects.requireNonNull(value, "tableFormat"); return this; }
        public Builder inheritTableFormat() { tableFormat = null; return this; }
        /** Declares every paragraph property as a complete snapshot. */
        public Builder paragraphFormat(ParagraphFormat value) {
            Objects.requireNonNull(value, "paragraphFormat");
            fontSize(value.fontSize());
            lineHeight(value.lineHeight());
            firstLineIndent(value.firstLineIndent());
            subsequentLineIndent(value.subsequentLineIndent());
            alignment(value.alignment());
            spaceBefore(value.spaceBefore());
            spaceAfter(value.spaceAfter());
            keepTogether(value.keepTogether());
            lineBreakPolicy(value.lineBreakPolicy());
            rotation(value.rotation());
            return pagination(value.pagination());
        }
        public Builder pagination(PaginationPolicy value) {
            Objects.requireNonNull(value, "pagination");
            return keepWithNext(value.keepWithNext()).pageBreakBefore(value.pageBreakBefore())
                    .minLinesBeforeBreak(value.minLinesBeforeBreak()).minLinesAfterBreak(value.minLinesAfterBreak());
        }
        public Builder inheritPagination() {
            keepWithNext = null; pageBreakBefore = null; minLinesBeforeBreak = null; minLinesAfterBreak = null; return this;
        }
        public Builder inheritParagraphFormat() {
            fontSize = null;
            lineHeight = null;
            firstLineIndent = null;
            subsequentLineIndent = null;
            alignment = null;
            spaceBefore = null;
            spaceAfter = null;
            keepTogether = null;
            lineBreakPolicy = null;
            rotation = null;
            return inheritPagination();
        }
        public Builder format(Format value) { format = Objects.requireNonNull(value, "format"); return this; }
        public Builder inheritFormat() { format = Format.INHERIT; return this; }
        public Builder formulas(Toggle value) { formulas = Objects.requireNonNull(value, "formulas"); return this; }
        public Builder formulas(boolean value) { return formulas(value ? Toggle.ENABLED : Toggle.DISABLED); }
        public Builder inheritFormulas() { return formulas(Toggle.INHERIT); }
        public Builder tables(Toggle value) { tables = Objects.requireNonNull(value, "tables"); return this; }
        public Builder tables(boolean value) { return tables(value ? Toggle.ENABLED : Toggle.DISABLED); }
        public Builder inheritTables() { return tables(Toggle.INHERIT); }
        /** Enables the supported raw HTML subset inside explicitly selected Markdown. Other formats are unchanged. */
        public Builder rawHtml(Toggle value) { rawHtml = Objects.requireNonNull(value, "rawHtml"); return this; }
        public Builder rawHtml(boolean value) { return rawHtml(value ? Toggle.ENABLED : Toggle.DISABLED); }
        public Builder inheritRawHtml() { return rawHtml(Toggle.INHERIT); }
        public ContentOptions build() { return new ContentOptions(this); }
    }
}
