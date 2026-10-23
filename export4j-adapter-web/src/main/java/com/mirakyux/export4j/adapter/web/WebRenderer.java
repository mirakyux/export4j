// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.Caption;
import com.mirakyux.export4j.model.CellLocation;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.CrossReference;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Task-local semantic traversal, shared source budgets and target-specific emission. */
final class WebRenderer {
    record Rendered(byte[] bytes, ExportResult result, ProcessingReport report) { }
    private static final ExportLocation DOCUMENT = new ExportLocation(-1, "DOCUMENT", -1, "", List.of(), -1);
    private final SectionedDocument document;
    private final boolean html;
    private final ExportPolicy policy;
    private final ProcessingPolicy processing;
    private final ExportLimits limits;
    private final Map<String, FontStyle> fonts;
    private final List<ExportDiagnostic> diagnostics = new ArrayList<>();
    private final List<ProcessingIssue> issues = new ArrayList<>();
    private final Map<String, String> images = new HashMap<>();
    private final Map<String, int[]> lists = new HashMap<>();
    private final Sink out;
    private long blocks, slots, codePoints, inlineItems, imageBytes;
    private final HashSet<String> imageIds = new HashSet<>();
    private int elementId;
    private String previousMarkdownList;
    private int previousMarkdownNumber;

    WebRenderer(SectionedDocument document, boolean html, ExportPolicy policy, ExportLimits limits, Map<String, FontStyle> fonts) {
        this(document, html, policy, limits, fonts, null);
    }
    WebRenderer(SectionedDocument document, boolean html, ExportPolicy policy, ExportLimits limits, Map<String, FontStyle> fonts, ProcessingPolicy processing) {
        this.document = document; this.html = html; this.policy = policy; this.limits = limits; this.fonts = fonts;
        this.processing = processing;
        out = new Sink(limits.maxOutputBytes());
    }

    Rendered render() throws IOException {
        preflight();
        if (html) {
            out.add("<!doctype html>\n<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
            out.add("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\">");
            out.add("<title>"); escaped(document.metadata().title(), false); out.add("</title><meta name=\"author\" content=\"");
            escaped(document.metadata().author(), false);
            out.add("\"><style>body{font-family:sans-serif;margin:24px;color:#111;background:white}main{max-width:960px;margin:auto}"
                    + "p,h1,h2,h3,h4,h5,h6,li{white-space:pre-wrap;overflow-wrap:anywhere}table{border-collapse:collapse;max-width:100%;width:100%}"
                    + "td,th{border:1px solid #bbb;vertical-align:top;overflow-wrap:anywhere}th{text-align:left}img{max-width:100%;object-fit:contain}"
                    + ".table-scroll{overflow-x:auto}.marker{display:inline-block;margin-right:.5em}ul,ol{padding-left:1.5em}"
                    + "header,footer{border-block:1px solid #ddd;padding:8px 0}section+section{margin-top:24px}"
                    + "@media print{.page-break{break-after:page}section+section{break-before:page}}</style></head><body><main>\n");
        } else if (!document.metadata().title().isEmpty() || !document.metadata().author().isEmpty()) {
            loss(ExportFeature.METADATA, DOCUMENT, "Document metadata omitted from Markdown body");
        }
        for (int index = 0; index < document.sections().size(); index++) {
            var section = document.sections().get(index);
            var base = new ExportLocation(index, "BODY", -1, "", List.of(), -1);
            ExecutionContext.progress(html ? "HTML_SECTION" : "MARKDOWN_SECTION", "", -1, index, document.sections().size());
            if (!section.pageSettings().equals(com.mirakyux.export4j.model.PageSettings.A4)) {
                loss(ExportFeature.PAGE_GEOMETRY, base, "Nondefault paper and margins replaced with flowing output defaults");
            }
            if (section.firstPageNumber() != 0) { loss(ExportFeature.PAGE_NUMBERING, base, "Displayed page numbering omitted from flowing output"); }
            if (html) { out.add("<section data-section=\""); escaped(section.id(), false); out.add("\">\n"); }
            if (!section.pageRegions().variants().isEmpty()) {
                loss(ExportFeature.PAGE_REGIONS, base,
                        "First/even page selection replaced with sequential section bands; explicit blank selection is lost");
            }
            overlays(section.pageRegions().background(), index, "BACKGROUND");
            region(section.pageRegions().header(), index, "HEADER");
            for (var variant : section.pageRegions().variants().entrySet()) {
                region(variant.getValue().header(), index, "HEADER_" + variant.getKey());
            }
            roots(section.blocks(), index, "BODY");
            region(section.pageRegions().footer(), index, "FOOTER");
            for (var variant : section.pageRegions().variants().entrySet()) {
                region(variant.getValue().footer(), index, "FOOTER_" + variant.getKey());
            }
            overlays(section.pageRegions().foreground(), index, "FOREGROUND");
            if (html) { out.add("</section>\n"); }
        }
        if (html) { out.add("</main></body></html>\n"); }
        byte[] bytes = out.text.toString().getBytes(StandardCharsets.UTF_8);
        return new Rendered(bytes, new ExportResult(bytes.length, diagnostics), new ProcessingReport(ProcessingReport.Phase.ACTUAL, issues));
    }

    private void overlays(List<com.mirakyux.export4j.model.PageOverlay> values, int section, String name) throws IOException {
        for (int index = 0; index < values.size(); index++) {
            var region = name + ":" + index;
            loss(ExportFeature.PAGE_REGIONS, new ExportLocation(section, region, -1, "", List.of(), -1),
                    "Repeated page overlay emitted once in section flow; position, overlap and paint order are lost");
            if (html) { out.add("<div data-page-layer=\"" + name + "\">\n"); }
            roots(values.get(index).content().blocks(), section, region);
            if (html) { out.add("</div>\n"); }
        }
    }

    private void region(PageRegion region, int section, String name) throws IOException {
        if (region.height() == 0) { return; }
        loss(ExportFeature.PAGE_REGIONS, new ExportLocation(section, name, -1, "", List.of(), -1),
                "Fixed repeated region emitted once in section flow without fixed geometry");
        if (html) { out.add(name.startsWith("HEADER") ? "<header>\n" : "<footer>\n"); }
        roots(region.blocks(), section, name);
        if (html) { out.add(name.startsWith("HEADER") ? "</header>\n" : "</footer>\n"); }
    }

    private void roots(List<Block> values, int section, String region) throws IOException {
        for (int i = 0; i < values.size(); i++) {
            var block = values.get(i);
            block(block, new ExportLocation(section, region, i, block.id(), List.of(), -1));
        }
    }

    private void block(Block block, ExportLocation location) throws IOException {
        ExecutionContext.checkpoint();
        if (block instanceof Paragraph paragraph) { paragraph(paragraph, "p", "", location, ParagraphFormat.of(12)); }
        else if (block instanceof Heading heading) {
            paragraph(heading.paragraph(), "h" + heading.level(), "#".repeat(heading.level()) + " ", location, Heading.defaultFormat(heading.level()));
        } else if (block instanceof Table table) { table(table, location); }
        else if (block instanceof BulletItem item) {
            list(item.paragraph(), item.marker(), item.level(), false, item.markerStyle(), location);
        } else if (block instanceof NumberedItem item) {
            int[] counter = lists.computeIfAbsent(item.listId(), key -> new int[9]);
            int level = item.level() - 1;
            for (int i = 0; i < level; i++) {
                if (counter[i] == 0) { throw failure(ExportException.Code.INVALID_NUMBERING, ExportFeature.LIST_MARKER, location, "Numbered list is missing a parent level"); }
            }
            if (item.restartAt() > 0) { counter[level] = item.restartAt(); }
            else if (counter[level] == Integer.MAX_VALUE) {
                throw failure(ExportException.Code.INVALID_NUMBERING, ExportFeature.LIST_MARKER, location, "Numbered list counter overflow");
            } else { counter[level]++; }
            java.util.Arrays.fill(counter, level + 1, counter.length, 0);
            var marker = new StringBuilder();
            for (int i = 0; i <= level; i++) { if (i > 0) { marker.append('.'); } marker.append(counter[i]); }
            if (!html) {
                if (previousMarkdownList != null && (!previousMarkdownList.equals(item.listId())
                        || counter[level] != (long) previousMarkdownNumber + 1)) {
                    // CommonMark ignores later item numbers in one list. An empty comment ends that list.
                    out.add("<!-- -->\n\n");
                }
                previousMarkdownList = level == 0 && marker.length() <= 9 ? item.listId() : null;
                previousMarkdownNumber = counter[level];
            }
            list(item.paragraph(), marker + ".", item.level(), true, item.markerStyle(), location);
        } else if (block instanceof PageBreak) {
            if (html) { out.add("<hr class=\"page-break\">\n"); }
            else { loss(ExportFeature.PAGE_BREAK, location, "Page break replaced with thematic separator"); out.add("\n---\n\n"); }
        } else if (block instanceof Caption caption) {
            loss(ExportFeature.CAPTION, location, "Caption emitted as unnumbered text");
            paragraph(new Paragraph(caption.id(), caption.text(), caption.format()), "p", "", location, ParagraphFormat.of(12));
        } else if (block instanceof CrossReference reference) {
            loss(ExportFeature.CROSS_REFERENCE, location, "Reference replaced with explicit unresolved marker");
            paragraph(new Paragraph(reference.id(), reference.prefix() + "[unresolved reference]", reference.format()), "p", "", location, ParagraphFormat.of(12));
        } else { loss(ExportFeature.CUSTOM_BLOCK, location, "Unsupported custom block omitted without invoking its code"); }
    }

    private void paragraph(Paragraph paragraph, String tag, String prefix, ExportLocation location, ParagraphFormat normal) throws IOException {
        if (html) {
            out.add("<" + tag); attributes(location); out.add(" style=\""); escaped(paragraphCss(paragraph.format()), false); out.add("\">");
        } else {
            paragraphPolicy(paragraph.format(), normal, location);
            out.add(prefix);
        }
        inlines(paragraph, location);
        out.add(html ? "</" + tag + ">\n" : "\n\n");
    }

    private void list(Paragraph paragraph, String marker, int level, boolean numbered, InlineStyle markerStyle, ExportLocation location) throws IOException {
        if (html) {
            out.add(numbered ? "<ol style=\"list-style:none;margin-left:" : "<ul style=\"list-style:none;margin-left:");
            out.add((level - 1) * 18 + "pt\"><li"); attributes(location);
            out.add(" style=\""); escaped(paragraphCss(paragraph.format()), false); out.add("\"><span class=\"marker\" style=\"");
            escaped(inlineCss(markerStyle, location), false); out.add("\">"); escaped(marker, false); out.add("</span>");
            inlines(paragraph, location); out.add(numbered ? "</li></ol>\n" : "</li></ul>\n");
        } else {
            paragraphPolicy(paragraph.format(), ParagraphFormat.of(12), location);
            if (!markerStyle.equals(InlineStyle.of(paragraph.format().fontSize()))) { loss(ExportFeature.LIST_MARKER, location, "Independent marker style replaced with Markdown list style"); }
            boolean nativeMarker = level == 1 && (numbered ? marker.length() <= 10 : marker.equals("•") || marker.equals("-"));
            if (nativeMarker) { out.add(numbered ? marker + " " : "- "); }
            else { loss(ExportFeature.LIST_MARKER, location, "List marker retained as plain text without nested list semantics"); escaped(marker, true); out.add(" "); }
            inlines(paragraph, location); out.add("\n\n");
        }
    }

    private void inlines(Paragraph paragraph, ExportLocation location) throws IOException {
        int offset = 0;
        for (var inline : paragraph.content()) {
            ExecutionContext.checkpoint();
            var source = location.offset(offset);
            if (inline instanceof InlineText text) {
                if (html) {
                    if (!text.hyperlink().isEmpty()) { linkStart(text.hyperlink()); }
                    out.add("<span style=\""); escaped(inlineCss(text.style(), source), false); out.add("\">");
                    escaped(text.text(), false); out.add("</span>");
                    if (!text.hyperlink().isEmpty()) { out.add("</a>"); }
                } else { markdownText(text, paragraph.format().fontSize(), source, paragraph.content().size() == 1); }
                offset += text.text().length();
            } else if (inline instanceof InlineImage image) {
                if (html) {
                    if (!image.hyperlink().isEmpty()) { linkStart(image.hyperlink()); }
                    out.add("<img src=\""); out.add(image(image.resource())); out.add("\" alt=\""); escaped(image.altText(), false);
                    out.add("\" style=\"width:" + number(image.width()) + "pt;height:" + number(image.height())
                            + "pt;vertical-align:" + number(-image.baselineShift()) + "pt\">");
                    if (!image.hyperlink().isEmpty()) { out.add("</a>"); }
                } else {
                    loss(ExportFeature.IMAGE_GEOMETRY, source, "Image retained at viewer-defined size without baseline geometry");
                    if (!image.hyperlink().isEmpty()) { out.add("["); }
                    out.add("!["); markdownAlt(image.altText()); out.add("](<"); out.add(image(image.resource())); out.add(">)");
                    if (!image.hyperlink().isEmpty()) { out.add("](<"); out.add(destination(image.hyperlink())); out.add(">)"); }
                }
                offset++;
            } else if (inline instanceof InlineField field) {
                loss(ExportFeature.INLINE_FIELD, source, "Field replaced with explicit unresolved marker");
                escaped("[unresolved " + field.kind().name() + "]", !html); offset++;
            }
        }
    }

    private void markdownText(InlineText text, double size, ExportLocation location, boolean isolated) throws IOException {
        InlineStyle style = text.style();
        if (style.fontSize() != size || style.rgb() != 0 || style.baselineShift() != 0 || style.underline()) {
            loss(ExportFeature.TEXT_STYLE, location, "Point size, color, baseline or underline omitted from Markdown");
        }
        FontStyle role = role(style.fontRole(), location);
        String decoration = (style.strikeThrough() ? "~~" : "")
                + (role != null && role.weight() >= 600 ? "**" : "") + (role != null && role.italic() ? "*" : "");
        if (text.text().isBlank()) { decoration = ""; }
        if (!text.hyperlink().isEmpty() && !text.text().isEmpty()) { out.add("["); }
        if (!isolated && !decoration.isEmpty()) {
            // Delimiters at adjacent styled/punctuation boundaries can change meaning in CommonMark.
            // Generated static inline tags preserve those exact runs without accepting source HTML.
            if (style.strikeThrough()) { out.add("<del>"); }
            if (role != null && role.weight() >= 600) { out.add("<strong>"); }
            if (role != null && role.italic()) { out.add("<em>"); }
            escaped(text.text(), true);
            if (role != null && role.italic()) { out.add("</em>"); }
            if (role != null && role.weight() >= 600) { out.add("</strong>"); }
            if (style.strikeThrough()) { out.add("</del>"); }
            if (!text.hyperlink().isEmpty()) { out.add("](<"); out.add(destination(text.hyperlink())); out.add(">)"); }
            return;
        }
        // Keep delimiter-adjacent whitespace outside emphasis to preserve CommonMark delimiter rules.
        String value = text.text();
        int first = 0, last = value.length();
        while (first < last && Character.isWhitespace(value.charAt(first))) { first++; }
        while (last > first && Character.isWhitespace(value.charAt(last - 1))) { last--; }
        escaped(value.substring(0, first), true); out.add(decoration);
        escaped(value.substring(first, last), true); out.add(new StringBuilder(decoration).reverse().toString());
        escaped(value.substring(last), true);
        if (!text.hyperlink().isEmpty() && !text.text().isEmpty()) { out.add("](<"); out.add(destination(text.hyperlink())); out.add(">)"); }
    }

    private void markdownAlt(String value) throws IOException {
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\n' || ch == '\r') {
                escaped(value.substring(start, i), true);
                out.add(ch == '\n' ? "&#10;" : "&#13;"); start = i + 1;
            }
        }
        escaped(value.substring(start), true);
    }

    private void table(Table table, ExportLocation location) throws IOException {
        var rows = table.grid().anchorRows();
        if (html) {
            out.add("<div class=\"table-scroll\"><table"); attributes(location);
            out.add(" style=\"margin-block:" + number(table.format().spaceBefore()) + "pt " + number(table.format().spaceAfter()) + "pt\"><colgroup>");
            double fixed = 0, weights = 0;
            for (var column : table.columns()) {
                if (column instanceof ColumnWidth.Fixed value) { fixed += value.points(); }
                else if (column instanceof ColumnWidth.Fraction value) { weights += value.weight(); }
            }
            if (!Double.isFinite(fixed) || !Double.isFinite(weights)) {
                throw failure(ExportException.Code.LIMIT_EXCEEDED, ExportFeature.TABLE_GEOMETRY, location, "Column width accumulation overflow");
            }
            for (var column : table.columns()) {
                out.add("<col style=\"");
                if (column instanceof ColumnWidth.Fixed value) { out.add("width:" + number(value.points()) + "pt"); }
                else if (column instanceof ColumnWidth.Fraction value) {
                    double share = value.weight() / weights;
                    out.add("width:calc(" + number(share * 100) + "% - " + number(fixed * share) + "pt)");
                } else if (column instanceof ColumnWidth.Auto value) {
                    out.add("min-width:" + number(value.minPoints()) + "pt;max-width:" + number(value.maxPoints()) + "pt");
                }
                out.add("\">");
            }
            out.add("</colgroup>");
            if (table.headerRows() > 0) { out.add("<thead>"); } else { out.add("<tbody>"); }
            for (int row = 0; row < rows.size(); row++) {
                if (row == table.headerRows() && row != 0) { out.add("</thead><tbody>"); }
                out.add(table.format().allowRowSplit() ? "<tr>" : "<tr style=\"break-inside:avoid\">");
                for (var anchor : rows.get(row)) {
                    var cell = anchor.cell(); String tag = row < table.headerRows() ? "th" : "td";
                    var cellLocation = location.cell(new CellLocation(table.id(), row, anchor.columnIndex(), -1), table.id());
                    var padding = table.format().padding();
                    out.add("<" + tag + " colspan=\"" + cell.columnSpan() + "\" rowspan=\"" + cell.rowSpan()
                            + "\" style=\"padding:" + number(padding.top()) + "pt " + number(padding.right()) + "pt "
                            + number(padding.bottom()) + "pt " + number(padding.left()) + "pt\">");
                    if (cell.hasBlocks()) {
                        for (int i = 0; i < cell.blocks().size(); i++) {
                            var child = cell.blocks().get(i);
                            block(child, location.cell(new CellLocation(table.id(), row, anchor.columnIndex(), i), child.id()));
                        }
                    } else {
                        paragraph(cellParagraph(cell), "p", "", cellLocation, ParagraphFormat.of(12));
                    }
                    out.add("</" + tag + ">");
                }
                out.add("</tr>");
            }
            out.add("</tbody></table></div>\n");
        } else {
            if (table.headerRows() != 1) { loss(ExportFeature.TABLE_STRUCTURE, location, "Markdown uses one header row; missing header synthesized or later header rows become body"); }
            markdownTableGeometry(table, location);
            if (table.headerRows() == 0) { out.add("|" + " |".repeat(table.columns().size()) + "\n"); separator(table.columns().size()); }
            for (int row = 0; row < rows.size(); row++) {
                out.add("|"); int column = 0;
                for (var anchor : rows.get(row)) {
                    while (column < anchor.columnIndex()) { out.add(" |"); column++; }
                    var cell = anchor.cell();
                    var source = location.cell(new CellLocation(table.id(), row, column, -1), table.id());
                    if (cell.columnSpan() != 1 || cell.rowSpan() != 1 || cell.hasBlocks()) {
                        loss(ExportFeature.TABLE_STRUCTURE, source, "Merged/block cell flattened at its anchor; covered slots stay empty");
                    }
                    out.add(" "); flatCell(cell, source); out.add(" |"); column++;
                    for (int i = 1; i < cell.columnSpan(); i++) { out.add(" |"); column++; }
                }
                while (column++ < table.columns().size()) { out.add(" |"); }
                out.add("\n");
                if (row == 0 && table.headerRows() > 0) { separator(table.columns().size()); }
            }
            out.add("\n");
        }
    }

    private void flatCell(TableCell cell, ExportLocation location) throws IOException {
        if (!cell.hasBlocks()) {
            if (cell.format().fontSize() != 12 || cell.format().lineHeight() != 18
                    || cell.format().alignment() != com.mirakyux.export4j.model.ParagraphAlignment.LEFT
                    || cell.format().lineBreakPolicy() != LineBreakPolicy.STANDARD) {
                loss(ExportFeature.PARAGRAPH_STYLE, location, "Cell typography omitted from Markdown");
            }
            inlines(cellParagraph(cell), location); return;
        }
        for (int i = 0; i < cell.blocks().size(); i++) {
            if (i > 0) { out.add("<br>"); }
            var child = cell.blocks().get(i);
            var last = location.cellPath().getLast();
            var path = new ArrayList<>(location.cellPath());
            path.set(path.size() - 1, new CellLocation(last.tableId(), last.rowIndex(), last.columnIndex(), i));
            var source = new ExportLocation(location.sectionIndex(), location.region(), location.rootBlockIndex(), child.id(), path, -1);
            if (child instanceof Paragraph paragraph) {
                paragraphPolicy(paragraph.format(), ParagraphFormat.of(12), source); inlines(paragraph, source);
            } else if (child instanceof Table table) {
                markdownTableGeometry(table, source);
                boolean first = true;
                for (var anchors : table.grid().anchorRows()) {
                    for (var anchor : anchors) {
                        if (!first) { out.add(" / "); } first = false;
                        flatCell(anchor.cell(), source.cell(new CellLocation(table.id(), anchor.rowIndex(), anchor.columnIndex(), -1), table.id()));
                    }
                }
            }
        }
    }

    private void markdownTableGeometry(Table table, ExportLocation location) throws IOException {
        boolean simpleWidths = table.columns().stream().allMatch(column -> column instanceof ColumnWidth.Fraction value
                && value.weight() == ((ColumnWidth.Fraction) table.columns().getFirst()).weight());
        if (!simpleWidths || !table.format().equals(TableFormat.DEFAULT)) {
            loss(ExportFeature.TABLE_GEOMETRY, location, "Table widths, padding and pagination omitted from Markdown");
        }
    }

    private void separator(int count) throws IOException { out.add("|" + " --- |".repeat(count) + "\n"); }
    private static Paragraph cellParagraph(TableCell cell) {
        var format = new ParagraphFormat(cell.format().fontSize(), cell.format().lineHeight(), 0, 0,
                cell.format().alignment(), 0, 0, false).withLineBreakPolicy(cell.format().lineBreakPolicy());
        return new Paragraph("cell", cell.text(), format, cell.inlines(), cell.images(), cell.fields());
    }
    private void paragraphPolicy(ParagraphFormat format, ParagraphFormat normal, ExportLocation location) throws IOException {
        if (!format.equals(normal)) { loss(ExportFeature.PARAGRAPH_STYLE, location, "Paragraph geometry and pagination replaced with Markdown flow"); }
    }
    private static String paragraphCss(ParagraphFormat format) {
        var pagination = format.pagination();
        return "font-size:" + number(format.fontSize()) + "pt;line-height:" + number(format.lineHeight())
                + "pt;margin:" + number(format.spaceBefore()) + "pt 0 " + number(format.spaceAfter())
                + "pt;padding-left:" + number(format.subsequentLineIndent()) + "pt;text-indent:"
                + number(format.firstLineIndent() - format.subsequentLineIndent()) + "pt;text-align:"
                + format.alignment().name().toLowerCase(Locale.ROOT) + ";break-inside:" + (format.keepTogether() ? "avoid" : "auto")
                + ";break-before:" + (pagination.pageBreakBefore() ? "page" : "auto")
                + ";break-after:" + (pagination.keepWithNext() ? "avoid" : "auto")
                + ";orphans:" + pagination.minLinesBeforeBreak() + ";widows:" + pagination.minLinesAfterBreak()
                + ";line-break:" + (format.lineBreakPolicy() == LineBreakPolicy.CJK_STRICT ? "strict" : "auto");
    }
    private String inlineCss(InlineStyle style, ExportLocation location) throws IOException {
        FontStyle font = role(style.fontRole(), location);
        var css = new StringBuilder("font-size:").append(number(style.fontSize())).append("pt;color:#")
                .append(String.format(Locale.ROOT, "%06x", style.rgb())).append(";vertical-align:").append(number(-style.baselineShift())).append("pt;")
                .append("text-decoration:").append(style.underline() ? "underline " : "")
                .append(style.strikeThrough() ? "line-through" : style.underline() ? "" : "none").append(';');
        if (font != null) {
            css.append("font-family:\""); font.family().codePoints().forEach(cp -> css.append('\\').append(Integer.toHexString(cp)).append(' '));
            css.append("\",sans-serif;font-weight:").append(font.weight()).append(";font-style:").append(font.italic() ? "italic" : "normal").append(';');
        }
        return css.toString();
    }
    private FontStyle role(String name, ExportLocation location) throws IOException {
        if (name.isEmpty()) { return null; }
        FontStyle style = fonts.get(name);
        if (style == null) { loss(ExportFeature.FONT_ROLE, location, "Unmapped font role replaced with default text style"); }
        return style;
    }
    private void linkStart(String href) throws IOException { out.add("<a rel=\"noreferrer\" href=\""); escaped(href, false); out.add("\">"); }
    private static String destination(String href) {
        return href.replace("\\", "%5C").replace("<", "%3C").replace(">", "%3E")
                .replace("&", "&amp;").replace("\"", "%22").replace("'", "%27").replace("|", "%7C");
    }
    private String image(ImageResource image) throws IOException {
        String value = images.get(image.id());
        if (value == null) {
            out.ensure(4L * ((image.byteSize() + 2L) / 3) + image.mediaType().length() + 13);
            value = "data:" + image.mediaType() + ";base64," + Base64.getEncoder().encodeToString(image.bytes());
            images.put(image.id(), value);
        }
        return value;
    }
    private void attributes(ExportLocation location) throws IOException {
        out.add(" id=\"e" + elementId++ + "\" data-source-id=\""); escaped(location.blockId(), false); out.add("\"");
    }
    private void loss(ExportFeature feature, ExportLocation location, String message) throws IOException {
        if (processing != null) {
            String target = html ? "HTML" : "MARKDOWN";
            var rule = processing.resolve(target, feature.name(), "EXPORT");
            var treatment = switch (feature) {
                case CUSTOM_BLOCK, METADATA, PAGE_NUMBERING -> ProcessingPolicy.Treatment.DISCARD;
                default -> ProcessingPolicy.Treatment.DEGRADE;
            };
            String section = location.sectionIndex() < 0 ? "" : document.sections().get(location.sectionIndex()).id();
            var source = new ProcessingIssue.Location(section, location.region(), location.blockId(), location.cellPath(), location.sourceOffset(), location.sourceOffset());
            var issue = new ProcessingIssue("EXPORT", "MODEL", target, feature.name(), "EXPORT", source,
                    message, rule.action(), rule.treatment(), List.of(feature.name() + "_" + treatment.name()));
            if (rule.action() == ProcessingPolicy.Action.ERROR) { throw new ProcessingException(issue); }
            if (rule.treatment() != treatment) { throw new IllegalArgumentException("web export treatment unavailable: " + feature); }
            if (issues.size() >= limits.maxDiagnostics()) { limit(location, "Processing report budget exceeded"); }
            issues.add(issue); return;
        }
        UnsupportedAction action = policy.action(feature);
        if (action == UnsupportedAction.IGNORE) { return; }
        var diagnostic = new ExportDiagnostic(feature, location, message);
        if (action == UnsupportedAction.ERROR) { throw new ExportException(ExportException.Code.UNSUPPORTED, diagnostic); }
        if (diagnostics.size() == limits.maxDiagnostics()) { limit(location, "Diagnostic budget exceeded"); }
        diagnostics.add(diagnostic);
    }

    private void preflight() throws IOException {
        if (document.sections().size() > limits.maxSections()) { limit(DOCUMENT, "Section budget exceeded"); }
        sourceText(document.metadata().title(), DOCUMENT); sourceText(document.metadata().author(), DOCUMENT);
        var headers = new HashSet<PageRegion>(); var footers = new HashSet<PageRegion>();
        var backgrounds = new HashSet<PageRegion>(); var foregrounds = new HashSet<PageRegion>();
        for (int section = 0; section < document.sections().size(); section++) {
            var value = document.sections().get(section);
            sourceText(value.id(), DOCUMENT);
            scan(value.blocks(), section, "BODY");
            for (var band : value.pageRegions().headers()) { if (headers.add(band)) { scan(band.blocks(), section, "HEADER"); } }
            for (var band : value.pageRegions().footers()) { if (footers.add(band)) { scan(band.blocks(), section, "FOOTER"); } }
            for (var band : value.pageRegions().backgrounds()) { if (backgrounds.add(band)) { scan(band.blocks(), section, "BACKGROUND"); } }
            for (var band : value.pageRegions().foregrounds()) { if (foregrounds.add(band)) { scan(band.blocks(), section, "FOREGROUND"); } }
        }
    }
    private void scan(List<Block> values, int section, String region) throws IOException {
        try {
            SemanticTree.walk(values, visit -> {
                var block = visit.block();
                var location = new ExportLocation(section, region, visit.rootBlockIndex(), block.id(), visit.cellPath(), -1);
                try {
                    if (++blocks > limits.maxBlocks()) { limit(location, "Block budget exceeded"); }
                    sourceText(block.id(), location);
                    if (block instanceof Table table) {
                        slots += (long) table.rows().size() * table.columns().size();
                        if (slots > limits.maxTableSlots()) { limit(location, "Table slot budget exceeded"); }
                        for (var anchors : table.grid().anchorRows()) {
                            for (var anchor : anchors) {
                                if (!anchor.cell().hasBlocks()) {
                                    var source = location.cell(new CellLocation(table.id(), anchor.rowIndex(), anchor.columnIndex(), -1), table.id());
                                    scanInlines(cellParagraph(anchor.cell()), source);
                                }
                            }
                        }
                    } else if (block instanceof Paragraph paragraph) { scanInlines(paragraph, location); }
                    else if (block instanceof Heading heading) { scanInlines(heading.paragraph(), location); }
                    else if (block instanceof BulletItem item) { scanInlines(item.paragraph(), location); sourceText(item.marker(), location); sourceText(item.markerStyle().fontRole(), location); }
                    else if (block instanceof NumberedItem item) { scanInlines(item.paragraph(), location); sourceText(item.listId(), location); sourceText(item.markerStyle().fontRole(), location); }
                    else if (block instanceof Caption caption) { sourceText(caption.text(), location); sourceText(caption.kind(), location); }
                    else if (block instanceof CrossReference reference) { sourceText(reference.prefix(), location); sourceText(reference.targetId(), location); }
                } catch (IOException failure) { throw new UncheckedIOException(failure); }
            });
        } catch (UncheckedIOException failure) { throw failure.getCause(); }
    }
    private void scanInlines(Paragraph paragraph, ExportLocation location) throws IOException {
        int offset = 0;
        for (var item : paragraph.content()) {
            var source = location.offset(offset);
            if (++inlineItems > limits.maxInlineItems()) { limit(source, "Inline item budget exceeded"); }
            if (item instanceof InlineText text) {
                sourceText(text.text(), source); sourceText(text.hyperlink(), source); sourceText(text.style().fontRole(), source); offset += text.text().length();
            } else if (item instanceof InlineImage image) {
                sourceText(image.altText(), source); sourceText(image.hyperlink(), source); offset++;
                if (imageIds.add(image.resource().id())) {
                    imageBytes += image.resource().byteSize();
                    if (imageIds.size() > limits.maxImages() || imageBytes > limits.maxImageBytes()) { limit(source, "Image resource budget exceeded"); }
                }
            } else if (item instanceof InlineField field) {
                sourceText(field.targetId(), source); sourceText(field.style().fontRole(), source); offset++;
            }
        }
    }
    private void sourceText(String value, ExportLocation location) throws IOException {
        for (int i = 0; i < value.length();) {
            if ((i & 1023) == 0) { ExecutionContext.checkpoint(); }
            int cp = value.codePointAt(i);
            if (cp >= 0xd800 && cp <= 0xdfff || cp == 0xfffe || cp == 0xffff
                    || Character.isISOControl(cp) && cp != '\n' && cp != '\r' && cp != '\t') {
                throw failure(ExportException.Code.INVALID_TEXT, ExportFeature.TEXT_STYLE,
                        location.offset(Math.max(0, location.sourceOffset()) + i), "Invalid Unicode or control character");
            }
            if (++codePoints > limits.maxCodePoints()) { limit(location, "Source text budget exceeded"); }
            i += Character.charCount(cp);
        }
    }
    private void escaped(String value, boolean markdown) throws IOException {
        boolean onlyWhitespace = value.isBlank();
        for (int i = 0; i < value.length();) {
            if ((i & 1023) == 0) { ExecutionContext.checkpoint(); }
            int cp = value.codePointAt(i); i += Character.charCount(cp);
            if (markdown && (cp == '\n' || cp == '\r')) {
                if (cp == '\r' && i < value.length() && value.charAt(i) == '\n') { i++; }
                out.add("<br>");
            } else if (markdown && (cp == ' ' || cp == '\t')) {
                out.add(cp == '\t' ? "&#9;" : onlyWhitespace || i == 1 || i == value.length() ? "&#32;" : " ");
            } else if (cp == '&') { out.add("&amp;"); }
            else if (cp == '<') { out.add("&lt;"); }
            else if (cp == '>') { out.add("&gt;"); }
            else if (cp == '"') { out.add("&quot;"); }
            else if (cp == '\'') { out.add("&#39;"); }
            else {
                if (markdown && cp < 128 && !Character.isLetterOrDigit(cp) && cp != ' ') { out.add("\\"); }
                out.add(new String(Character.toChars(cp)));
            }
        }
    }
    private void limit(ExportLocation location, String message) throws ExportException {
        throw failure(ExportException.Code.LIMIT_EXCEEDED, ExportFeature.CUSTOM_BLOCK, location, message);
    }
    private static ExportException failure(ExportException.Code code, ExportFeature feature, ExportLocation location, String message) {
        return new ExportException(code, new ExportDiagnostic(feature, location, message));
    }
    private static String number(double value) { return Double.toString(value); }
    private final class Sink {
        private final StringBuilder text = new StringBuilder();
        private final long limit;
        private long bytes;
        Sink(long limit) { this.limit = limit; }
        void ensure(long length) throws ExportException { if (length > limit - bytes) { limit(DOCUMENT, "UTF-8 output byte budget exceeded"); } }
        void add(String value) throws IOException {
            ExecutionContext.checkpoint();
            long size = value.getBytes(StandardCharsets.UTF_8).length;
            ensure(size); text.append(value); bytes += size;
        }
    }
}
