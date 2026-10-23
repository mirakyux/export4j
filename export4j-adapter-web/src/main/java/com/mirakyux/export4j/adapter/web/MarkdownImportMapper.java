// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.CellLocation;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ExternalDestination;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy.Treatment;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.ListBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;

final class MarkdownImportMapper {
    private static final Pattern EXECUTABLE_HTML = Pattern.compile("(?is)<\\s*(?:script|iframe|object|embed|applet|base|link|!doctype|!entity)\\b|\\bon[a-z]+\\s*=|(?:javascript|vbscript)\\s*:");
    private final MarkdownImporter importer;
    private final ImportSupport.State state;
    private record Frame(Node node, int depth) { }
    private record Context(InlineStyle style, String link, boolean strong, boolean emphasis) { }
    MarkdownImportMapper(MarkdownImporter importer, ImportSupport.State state) { this.importer = importer; this.state = state; }

    Document map(Node root) {
        var pending = new ArrayDeque<Frame>(); pending.push(new Frame(root, 0)); int count = 0;
        while (!pending.isEmpty()) {
            var frame = pending.pop(); state.work(1);
            if (++count > state.limits.maxNodes() || frame.depth() > state.limits.maxDepth()) {
                throw new IllegalArgumentException("Markdown syntax tree budget exceeded");
            }
            var node = frame.node();
            String raw = node instanceof HtmlInline html ? html.getLiteral() : node instanceof HtmlBlock html ? html.getLiteral() : "";
            if (EXECUTABLE_HTML.matcher(raw).find()) { throw new IllegalArgumentException("executable HTML is not accepted in Markdown input"); }
            String destination = node instanceof Link link ? link.getDestination()
                    : node instanceof LinkReferenceDefinition link ? link.getDestination() : "";
            if (!destination.isEmpty()) { safeLink(destination); }
            if (node instanceof Image image && image.getDestination().matches("(?is)\\s*(?:javascript|vbscript):.*")) {
                throw new IllegalArgumentException("executable image destination is not accepted");
            }
            for (var child = node.getLastChild(); child != null; child = child.getPrevious()) {
                pending.push(new Frame(child, frame.depth() + 1));
            }
        }
        var output = new ArrayList<Block>();
        blocks(root, output, 0, 0);
        return new Document(importer.metadata, importer.pageSettings, output);
    }
    private void blocks(Node parent, List<Block> output, int quoteDepth, int listDepth) {
        for (var node = parent.getFirstChild(); node != null; node = node.getNext()) {
            block(node, output, quoteDepth, listDepth);
        }
    }
    private void block(Node node, List<Block> output, int quoteDepth, int listDepth) {
        state.work(1);
        if (node instanceof LinkReferenceDefinition) { return; }
        String id = state.id(); var location = location(node, id, List.of());
        var format = indent(importer.format, (quoteDepth + listDepth) * 18);
        if (node instanceof org.commonmark.node.Paragraph) {
            output.add(Paragraph.mixed(id, inlines(node, context(format), location), format));
        } else if (node instanceof org.commonmark.node.Heading heading) {
            var headingFormat = indent(com.mirakyux.export4j.model.Heading.defaultFormat(heading.getLevel()), (quoteDepth + listDepth) * 18);
            output.add(com.mirakyux.export4j.model.Heading.rich(id,
                    textOnly(inlines(node, context(headingFormat), location), "HEADING_INLINE_OBJECT", location), heading.getLevel(), headingFormat));
        } else if (node instanceof ListBlock list) {
            list(list, output, quoteDepth, listDepth + 1, list instanceof OrderedList ? id : "", 0);
        } else if (node instanceof BlockQuote) {
            if (loss("BLOCK_QUOTE", location, "model has no block quote container", "QUOTE_CONTAINER_FLATTENED") == Treatment.DEGRADE) {
                blocks(node, output, quoteDepth + 1, listDepth);
            }
        } else if (node instanceof FencedCodeBlock code) {
            if (loss("CODE_BLOCK", location, "model has no fenced code block or language metadata", "CODE_STRUCTURE_AND_LANGUAGE_REMOVED") == Treatment.DEGRADE) {
                output.add(code(id, code.getLiteral(), format));
            }
        } else if (node instanceof IndentedCodeBlock code) {
            if (loss("CODE_BLOCK", location, "model has no code block container", "CODE_STRUCTURE_REMOVED") == Treatment.DEGRADE) {
                output.add(code(id, code.getLiteral(), format));
            }
        } else if (node instanceof TableBlock) { table(node, id, location, output); }
        else if (node instanceof ThematicBreak) {
            if (loss("THEMATIC_BREAK", location, "model has no semantic thematic break", "RULE_REPLACED_WITH_TEXT") == Treatment.DEGRADE) {
                state.text(5); output.add(new Paragraph(id, "-----", format));
            }
        } else if (node instanceof HtmlBlock html) {
            if (loss("RAW_HTML", location, "raw HTML is not interpreted by Markdown import", "HTML_RENDERED_AS_LITERAL_TEXT") == Treatment.DEGRADE) {
                state.text(html.getLiteral().length()); output.add(new Paragraph(id, html.getLiteral(), format));
            }
        } else { state.unsupported("UNSUPPORTED_NODE", location, "unsupported Markdown block", List.of("CONTENT_REMOVED"), Treatment.DISCARD); }
    }
    private void list(ListBlock list, List<Block> output, int quoteDepth, int visualDepth, String orderedId, int orderedDepth) {
        if (visualDepth > 9) { throw new IllegalArgumentException("Markdown list nesting exceeds model limit"); }
        boolean ordered = list instanceof OrderedList;
        int numberDepth = ordered ? orderedDepth + 1 : orderedDepth;
        String effectiveId = ordered && orderedId.isEmpty() ? state.id() : orderedId;
        int firstNumber = ordered ? ((OrderedList) list).getMarkerStartNumber() : 1;
        if (ordered && firstNumber == 0) {
            if (loss("NUMBERING_START", location(list, effectiveId, List.of()), "model numbering starts at one", "ZERO_START_RENUMBERED_TO_ONE") == Treatment.DISCARD) { return; }
            firstNumber = 1;
        }
        int index = 0;
        boolean restart = true;
        for (var item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem)) { throw new IllegalArgumentException("invalid Markdown list structure"); }
            state.work(1); String id = state.id(); var location = location(item, id, List.of());
            Node paragraph = item.getFirstChild();
            if (!(paragraph instanceof org.commonmark.node.Paragraph)) {
                if (loss("LIST_ITEM_STRUCTURE", location, "list item has no initial paragraph", "LIST_ITEM_CONTAINER_FLATTENED") == Treatment.DEGRADE) {
                    blocks(item, output, quoteDepth, visualDepth);
                }
                index++; restart = true; continue;
            }
            int level = ordered ? numberDepth : visualDepth;
            var format = indent(importer.format, (quoteDepth + (ordered ? visualDepth - numberDepth : 0)) * 18);
            var runs = textOnly(inlines(paragraph, context(format), location(paragraph, id, List.of())), "LIST_INLINE_OBJECT", location);
            if (paragraph.getFirstChild() instanceof Text text && text.getLiteral().matches("(?s)^\\[[ xX]\\](?:[ \\t].*|$)")) {
                if (loss("TASK_LIST", location, "model has no task checkbox semantics", "TASK_STATE_RETAINED_AS_LITERAL_TEXT") == Treatment.DISCARD) {
                    index++; restart = true; continue;
                }
            }
            if (ordered) {
                int start = restart ? Math.addExact(firstNumber, index) : 0;
                output.add(NumberedItem.rich(id, effectiveId, runs, start, format, level));
                restart = false;
            } else { output.add(BulletItem.rich(id, runs, "•", level, format)); }
            for (var child = paragraph.getNext(); child != null; child = child.getNext()) {
                if (child instanceof ListBlock nested) {
                    list(nested, output, quoteDepth, visualDepth + 1, effectiveId, numberDepth);
                } else {
                    var childLocation = location(child, id, List.of());
                    if (loss("LIST_ITEM_STRUCTURE", childLocation, "model does not retain multi-block list item containers", "LIST_ITEM_CONTAINER_FLATTENED") == Treatment.DEGRADE) {
                        block(child, output, quoteDepth, visualDepth);
                    }
                }
            }
            index++;
        }
    }
    private Paragraph code(String id, String literal, ParagraphFormat format) {
        state.text(literal.length());
        return new Paragraph(id, List.of(new InlineText(literal, InlineStyle.of(format.fontSize()).toBuilder().fontRole(importer.codeRole).build())), format);
    }
    private void table(Node source, String id, ProcessingIssue.Location location, List<Block> output) {
        var rows = new ArrayList<TableRow>(); int headers = 0; int columns = -1;
        for (var group = source.getFirstChild(); group != null; group = group.getNext()) {
            for (var row = group.getFirstChild(); row != null; row = row.getNext()) {
                state.work(1); var cells = new ArrayList<TableCell>(); int column = 0;
                for (var value = row.getFirstChild(); value != null; value = value.getNext()) {
                    if (!(value instanceof org.commonmark.ext.gfm.tables.TableCell cell)) { throw new IllegalArgumentException("invalid Markdown table cell"); }
                    if (++state.tableSlots > state.limits.maxTableSlots()) { throw new IllegalArgumentException("import table slot budget exceeded"); }
                    var path = List.of(new CellLocation(id, rows.size(), column++, -1));
                    var cellLocation = location(value, id, path);
                    ParagraphAlignment alignment = cell.getAlignment() == null ? ParagraphAlignment.LEFT : switch (cell.getAlignment()) {
                        case LEFT -> ParagraphAlignment.LEFT; case CENTER -> ParagraphAlignment.CENTER; case RIGHT -> ParagraphAlignment.RIGHT;
                    };
                    var format = CellFormat.of(importer.format.fontSize()).withAlignment(alignment);
                    cells.add(TableCell.mixed(inlines(cell, context(importer.format), cellLocation), format));
                }
                if (columns == -1) { columns = column; }
                if (columns != column || column == 0) { throw new IllegalArgumentException("invalid Markdown table coverage"); }
                rows.add(new TableRow(cells)); if (group instanceof TableHead) { headers++; }
            }
        }
        if (rows.size() <= headers) {
            if (loss("TABLE_HEADER_ONLY", location, "model requires at least one table body row", "EMPTY_BODY_ROW_ADDED") != Treatment.DEGRADE) { return; }
            var empty = new ArrayList<TableCell>();
            for (int column = 0; column < columns; column++) {
                if (++state.tableSlots > state.limits.maxTableSlots()) { throw new IllegalArgumentException("import table slot budget exceeded"); }
                empty.add(new TableCell("", CellFormat.of(importer.format.fontSize())));
            }
            rows.add(new TableRow(empty));
        }
        var widths = new ArrayList<ColumnWidth>();
        for (int column = 0; column < columns; column++) { widths.add(new ColumnWidth.Fraction(1)); }
        output.add(new Table(id, widths, rows, headers, TableFormat.DEFAULT));
    }
    private List<InlineContent> inlines(Node parent, Context context, ProcessingIssue.Location location) {
        var output = new ArrayList<InlineContent>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNext()) {
            state.work(1); var at = location(node, location.blockId(), location.cellPath());
            if (node instanceof Text text) { text(output, text.getLiteral(), context); }
            else if (node instanceof SoftLineBreak) { text(output, " ", context); }
            else if (node instanceof HardLineBreak) { text(output, "\n", context); }
            else if (node instanceof StrongEmphasis || node instanceof Emphasis) {
                boolean strong = context.strong() || node instanceof StrongEmphasis;
                boolean emphasis = context.emphasis() || node instanceof Emphasis;
                var style = context.style().toBuilder().fontRole(strong && emphasis ? importer.combinedRole : strong ? importer.strongRole : importer.emphasisRole).build();
                output.addAll(inlines(node, new Context(style, context.link(), strong, emphasis), at));
            } else if (node instanceof Strikethrough) {
                output.addAll(inlines(node, new Context(context.style().toBuilder().strikeThrough(true).build(), context.link(), context.strong(), context.emphasis()), at));
            } else if (node instanceof Code code) {
                if (loss("INLINE_CODE", at, "model has no inline code semantics", "INLINE_CODE_RETAINED_AS_STYLED_TEXT") == Treatment.DEGRADE) {
                    text(output, code.getLiteral(), new Context(context.style().toBuilder().fontRole(importer.codeRole).build(), context.link(), false, false));
                }
            } else if (node instanceof Link link) {
                String destination = link(link.getDestination(), at);
                if (link.getTitle() != null && !link.getTitle().isEmpty()) { loss("LINK_TITLE", at, "model has no hyperlink title", "LINK_TITLE_REMOVED"); }
                output.addAll(inlines(node, new Context(context.style(), destination, context.strong(), context.emphasis()), at));
            } else if (node instanceof Image image) {
                var resource = importer.images.get(image.getDestination());
                String alt = plain(image);
                if (resource == null) {
                    if (loss("IMAGE_RESOURCE", at, "image is not explicitly registered", "IMAGE_REPLACED_WITH_ALT_TEXT") == Treatment.DEGRADE) { text(output, alt, context); }
                } else {
                    if (image.getTitle() != null && !image.getTitle().isEmpty()) { loss("IMAGE_TITLE", at, "model has no image title", "IMAGE_TITLE_REMOVED"); }
                    state.text(1); output.add(new InlineImage(resource.resource(), resource.width(), resource.height(), resource.baselineShift(), alt, context.link()));
                }
            } else if (node instanceof HtmlInline html) {
                if (html.getLiteral().matches("(?i)<br\\s*/?>")) { text(output, "\n", context); }
                else if (loss("RAW_HTML", at, "raw inline HTML is not interpreted", "HTML_RENDERED_AS_LITERAL_TEXT") == Treatment.DEGRADE) { text(output, html.getLiteral(), context); }
            } else { state.unsupported("UNSUPPORTED_NODE", at, "unsupported Markdown inline node", List.of("CONTENT_REMOVED"), Treatment.DISCARD); }
        }
        return List.copyOf(output);
    }
    private void text(List<InlineContent> output, String literal, Context context) {
        state.text(literal.length()); output.add(new InlineText(literal, context.style(), context.link()));
    }
    private List<InlineText> textOnly(List<InlineContent> content, String feature, ProcessingIssue.Location location) {
        var runs = new ArrayList<InlineText>();
        for (var item : content) {
            if (item instanceof InlineText run) { runs.add(run); }
            else if (item instanceof InlineImage image && loss(feature, location, "text-only model block cannot retain an image", "IMAGE_REPLACED_WITH_ALT_TEXT") == Treatment.DEGRADE) {
                state.text(image.altText().length()); runs.add(new InlineText(image.altText(), InlineStyle.of(importer.format.fontSize()), image.hyperlink()));
            }
        }
        return runs;
    }
    private String plain(Node parent) {
        var result = new StringBuilder(); var stack = new ArrayDeque<Node>();
        for (var child = parent.getLastChild(); child != null; child = child.getPrevious()) { stack.push(child); }
        while (!stack.isEmpty()) {
            var node = stack.pop(); state.work(1);
            if (node instanceof Text text) { result.append(text.getLiteral()); }
            else if (node instanceof Code code) { result.append(code.getLiteral()); }
            else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) { result.append(' '); }
            for (var child = node.getLastChild(); child != null; child = child.getPrevious()) { stack.push(child); }
        }
        return result.toString();
    }
    private String link(String raw, ProcessingIssue.Location location) {
        var uri = safeLink(raw);
        if (!uri.isAbsolute() && importer.baseUri != null && !raw.startsWith("#")) { uri = importer.baseUri.resolve(uri); }
        if (!uri.isAbsolute()) {
            loss("RELATIVE_LINK", location, "relative/internal link has no model destination", "LINK_TARGET_REMOVED"); return "";
        }
        try { return new ExternalDestination(uri.toString()).uri(); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("invalid or unsafe Markdown hyperlink"); }
    }
    private static URI safeLink(String value) {
        try {
            var uri = URI.create(value); String scheme = uri.getScheme();
            if (value.codePoints().anyMatch(Character::isISOControl) || value.length() > 8192
                    || scheme != null && !List.of("https", "http", "mailto").contains(scheme.toLowerCase(Locale.ROOT))
                    || uri.getUserInfo() != null || !uri.isAbsolute() && value.startsWith("//")) {
                throw new IllegalArgumentException("invalid or unsafe Markdown hyperlink");
            }
            if (uri.isAbsolute()) { new ExternalDestination(value); }
            return uri;
        } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("invalid or unsafe Markdown hyperlink"); }
    }
    private Treatment loss(String feature, ProcessingIssue.Location location, String reason, String effect) {
        boolean discard = state.policy.resolve("MARKDOWN", feature, "IMPORT").treatment() == Treatment.DISCARD;
        return state.unsupported(feature, location, reason, List.of(discard ? "CONTENT_REMOVED" : effect), Treatment.DEGRADE, Treatment.DISCARD);
    }
    private static Context context(ParagraphFormat format) { return new Context(InlineStyle.of(format.fontSize()), "", false, false); }
    private static ParagraphFormat indent(ParagraphFormat format, double amount) {
        return format.toBuilder().firstLineIndent(format.firstLineIndent() + amount).subsequentLineIndent(format.subsequentLineIndent() + amount).build();
    }
    private static ProcessingIssue.Location location(Node node, String id, List<CellLocation> path) {
        int start = Integer.MAX_VALUE; int end = -1;
        for (var span : node.getSourceSpans()) { start = Math.min(start, span.getInputIndex()); end = Math.max(end, span.getInputIndex() + span.getLength()); }
        return new ProcessingIssue.Location("", "BODY", id, path, end < 0 ? -1 : start, end);
    }
}
