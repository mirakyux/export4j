// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.CellLocation;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ExternalDestination;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy.Treatment;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DocumentType;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.nodes.XmlDeclaration;

final class HtmlImportMapper {
    private static final Set<String> FORBIDDEN = Set.of("script", "iframe", "object", "embed", "applet", "base", "link", "svg", "math", "noscript");
    private static final Set<String> CONTAINERS = Set.of("html", "body", "div", "section", "article", "main", "header", "footer", "nav", "aside");
    private static final Set<String> INLINE = Set.of("span", "strong", "b", "em", "i", "u", "s", "del", "strike", "br", "a", "img", "code");
    private static final Set<String> GLOBAL = Set.of("id", "class", "style");
    private static final String PASSIVE_CSP = "default-src 'none'; img-src data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'";
    private record Frame(Node node, int depth) { }
    private record Context(String block, List<CellLocation> cells, double indent) { }
    private final HtmlImporter importer;
    private final ImportSupport.State state;
    private final HtmlCss css;
    private final Set<String> reserved = new HashSet<>();
    private final Map<Element, String> ids = new IdentityHashMap<>();
    private final Map<Element, Boolean> omitted = new IdentityHashMap<>();
    private final Set<Element> checkedAttributes = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean compilingBox;
    private final Set<Element> layoutAnchors = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    HtmlImportMapper(HtmlImporter importer, ImportSupport.State state) { this.importer = importer; this.state = state; css = new HtmlCss(importer, state); }

    void validate(org.jsoup.nodes.Document tree) {
        var pending = new ArrayDeque<Frame>(); pending.push(new Frame(tree, 0)); int count = 0;
        while (!pending.isEmpty()) {
            var frame = pending.pop(); state.work(1);
            if (++count > state.limits.maxNodes() || frame.depth() > state.limits.maxDepth()) { throw new IllegalArgumentException("HTML tree budget exceeded"); }
            var node = frame.node();
            if (node instanceof Element element) {
                if (FORBIDDEN.contains(element.normalName())) { throw new IllegalArgumentException("executable or external HTML construct is not accepted"); }
                if (element.hasAttr("id") && (!element.id().isEmpty() && !reserved.add(element.id()))) { throw new IllegalArgumentException("duplicate HTML id"); }
                for (var attribute : element.attributes()) {
                    String name = attribute.getKey().toLowerCase(Locale.ROOT);
                    if (name.startsWith("on") || name.equals("srcdoc") || name.equals("http-equiv") && !passiveCsp(element)) {
                        throw new IllegalArgumentException("active HTML attributes are not accepted");
                    }
                    if (name.equals("href")) { safeLink(attribute.getValue()); }
                    if (name.equals("src") && attribute.getValue().matches("(?is)\\s*(?:javascript|vbscript):.*")) { throw new IllegalArgumentException("executable HTML resource is not accepted"); }
                    if (name.equals("style")) { HtmlCss.safety(attribute.getValue()); }
                }
                if (element.normalName().equals("style")) { HtmlCss.safety(element.data()); }
            } else if (node instanceof XmlDeclaration) { throw new IllegalArgumentException("XML declarations are not accepted as HTML"); }
            else if (node instanceof DocumentType type && (!type.name().equalsIgnoreCase("html") || !type.publicId().isEmpty() || !type.systemId().isEmpty())) {
                throw new IllegalArgumentException("external or non-HTML doctype is not accepted");
            }
            for (int index = node.childNodeSize() - 1; index >= 0; index--) { pending.push(new Frame(node.childNode(index), frame.depth() + 1)); }
        }
    }
    /** The self-contained export's restrictive declaration is data, removed with HTML_METADATA loss.
     * No refresh, resource preload, report endpoint or arbitrary HTTP directive is accepted. */
    private static boolean passiveCsp(Element element) {
        return element.normalName().equals("meta") && element.attr("http-equiv").equalsIgnoreCase("Content-Security-Policy")
                && element.attr("content").equals(PASSIVE_CSP);
    }
    Document map(org.jsoup.nodes.Document tree) {
        // Stylesheet validation covers unmatched rules, too; no network loading is available.
        for (var style : tree.getElementsByTag("style")) { attributes(style, "", List.of()); css.stylesheet(style); }
        css.get(tree); css.get(tree.head()); css.get(tree.body());
        attributes(tree.selectFirst("html"), "", List.of()); attributes(tree.body(), "", List.of());
        attributes(tree.head(), "", List.of());
        for (var child : tree.head().children()) {
            if (child.normalName().equals("style")) { continue; }
            if (child.normalName().equals("meta") && child.attributesSize() == 1 && child.attr("charset").equalsIgnoreCase("utf-8")) { continue; }
            loss(child, "HTML_METADATA", "HTML head metadata has no explicit model mapping", "HEAD_METADATA_REMOVED", "", List.of());
        }
        var output = new ArrayList<Block>();
        if (importer.layoutMode && needsBox(tree.body())) { block(tree.body(), output, new Context("", List.of(), 0)); }
        else { blocks(tree.body(), output, new Context("", List.of(), 0)); }
        return new Document(importer.metadata, css.pageSettings(), output);
    }
    private String id(Element source) {
        return ids.computeIfAbsent(source, element -> {
            if (!element.id().isEmpty()) { return element.id(); }
            return generatedId();
        });
    }
    private String generatedId() {
        String value; do { value = state.id(); } while (reserved.contains(value)); reserved.add(value); return value;
    }
    private void blocks(Element parent, List<Block> output, Context context) {
        if (hidden(parent, context.block(), context.cells())) { return; }
        var inline = new ArrayList<Node>();
        for (var node : parent.childNodes()) {
            state.work(1);
            if (node instanceof Comment || node instanceof DocumentType) { continue; }
            if (node instanceof TextNode text && text.isBlank() && inline.isEmpty()) { continue; }
            if (node instanceof Element element && !INLINE.contains(element.normalName())) {
                flush(parent, inline, output, context); block(element, output, context);
            } else { inline.add(node); }
        }
        flush(parent, inline, output, context);
    }
    private void flush(Element parent, List<Node> nodes, List<Block> output, Context context) {
        if (nodes.isEmpty()) { return; }
        String id = generatedId(); var content = content(nodes, parent, new Context(id, context.cells(), context.indent()));
        if (!content.isEmpty()) { output.add(Paragraph.mixed(id, content, css.get(parent).paragraph(importer.format, context.indent()))); }
        nodes.clear();
    }
    private void block(Element element, List<Block> output, Context context) {
        state.work(1); String tag = element.normalName(); String id = id(element);
        if (importer.layoutMode && !compilingBox && needsBox(element) && CONTAINERS.contains(tag)) { layoutAnchors.add(element); }
        attributes(element, id, context.cells()); if (hidden(element, id, context.cells())) { return; }
        var own = new Context(id, context.cells(), context.indent());
        var style = css.get(element); var format = style.paragraph(importer.format, context.indent());
        if (style.breakBefore) { output.add(new PageBreak(generatedId())); }
        if (importer.layoutMode && !compilingBox && needsBox(element)) {
            if (style.box.getOrDefault("position", "static").equals("absolute")) { throw new IllegalArgumentException("top-level absolute CSS content requires a positioned containing box"); }
            var component = box(element, own, 0, false);
            if (component != null) { output.add(new com.mirakyux.export4j.layout.core.ComponentBlock(CONTAINERS.contains(tag) ? id : generatedId(), component)); }
            if (style.breakAfter) { output.add(new PageBreak(generatedId())); }
            return;
        }
        if (tag.equals("p")) { output.add(Paragraph.mixed(id, content(element.childNodes(), element, own), format)); }
        else if (tag.matches("h[1-6]")) { output.add(Heading.rich(id, textOnly(content(element.childNodes(), element, own), element, own), tag.charAt(1) - '0', format)); }
        else if (CONTAINERS.contains(tag)) {
            if (css.get(element).geometryExplicit) { loss(element, "CONTAINER_GEOMETRY", "container margins cannot preserve CSS box and margin-collapse semantics", "CONTAINER_GEOMETRY_REMOVED", id, context.cells()); }
            blocks(element, output, context);
        } else if (tag.equals("table")) { output.add(table(element, own, 1)); }
        else if (tag.equals("ul") || tag.equals("ol")) { list(element, output, context, 1, "", 0); }
        else if (tag.equals("pre")) {
            if (loss(element, "CODE_BLOCK", "model has no preformatted code container or no-wrap guarantee", "CODE_CONTAINER_REMOVED", id, context.cells()) == Treatment.DEGRADE) {
                output.add(Paragraph.mixed(id, content(element.childNodes(), element, own), format));
            }
        } else if (tag.equals("blockquote")) {
            if (loss(element, "BLOCK_QUOTE", "model has no quote container", "QUOTE_CONTAINER_FLATTENED", id, context.cells()) == Treatment.DEGRADE) {
                blocks(element, output, new Context(id, context.cells(), context.indent() + 18));
            }
        } else if (tag.equals("style")) { /* Parsed once above, never emitted as document text. */ }
        else if (loss(element, "HTML_ELEMENT", "HTML element has no semantic mapping", "ELEMENT_CONTAINER_FLATTENED", id, context.cells()) == Treatment.DEGRADE) { blocks(element, output, own); }
        if (style.breakAfter) { output.add(new PageBreak(generatedId())); }
    }
    private boolean needsBox(Element element) {
        var style = css.get(element); return !style.box.isEmpty() && !(style.box.size() == 1 && Set.of("block", "inline").contains(style.box.get("display")));
    }
    private HtmlCssBox box(Element source, Context context, int depth, boolean containing) {
        state.work(1); if (depth > 32) { throw new IllegalArgumentException("compiled CSS box depth exceeded"); }
        attributes(source, context.block(), context.cells()); if (hidden(source, context.block(), context.cells())) { return null; }
        var computed = css.get(source); var declarations = new HashMap<>(computed.box); var tag = source.normalName();
        if (tag.equals("ul") || tag.equals("ol")) {
            state.unsupported("CSS_LAYOUT_LEAF", location(source, context.block(), context.cells()), "list semantics have no compiled CSS leaf mapping", List.of("LIST_SUBTREE_REMOVED"), Treatment.DISCARD);
            return null;
        }
        boolean positioned = !declarations.getOrDefault("position", "static").equals("static");
        if (depth == 0 && declarations.getOrDefault("position", "static").equals("relative")
                && List.of("left", "right", "top", "bottom").stream().anyMatch(name -> declarations.containsKey(name) && !declarations.get(name).equals("auto") && CssBoxStyle.dimension(declarations.get(name), false).value() != 0)) {
            state.unsupported("CSS_LAYOUT", location(source, context.block(), context.cells()), "relative root offsets need an explicit containing layout box", List.of("ROOT_RELATIVE_OFFSETS_REMOVED"), Treatment.DEGRADE);
            for (String name : List.of("left", "right", "top", "bottom")) { declarations.remove(name); }
        }
        if (declarations.getOrDefault("position", "static").equals("absolute") && !containing) { throw new IllegalArgumentException("absolute CSS content requires an explicit positioned parent"); }
        if (declarations.getOrDefault("position", "static").equals("absolute")) {
            for (var axis : List.of(List.of("left", "right"), List.of("top", "bottom"))) {
                if (axis.stream().allMatch(name -> declarations.getOrDefault(name, "auto").equals("auto"))) {
                    state.unsupported("CSS_LAYOUT", location(source, context.block(), context.cells()), "absolute static-position offsets require explicit axis constraints", List.of("STATIC_POSITION_REPLACED_WITH_ZERO"), Treatment.DEGRADE);
                    declarations.put(axis.getFirst(), "0pt");
                }
            }
            if (declarations.getOrDefault("width", "auto").equals("auto") && (declarations.getOrDefault("left", "auto").equals("auto") || declarations.getOrDefault("right", "auto").equals("auto"))) {
                state.unsupported("CSS_LAYOUT", location(source, context.block(), context.cells()), "absolute intrinsic width requires a width or two explicit horizontal constraints", List.of("INTRINSIC_WIDTH_REPLACED_WITH_EDGE_CONSTRAINTS"), Treatment.DEGRADE);
                declarations.putIfAbsent("left", "0pt"); declarations.putIfAbsent("right", "0pt");
                if (declarations.get("left").equals("auto")) { declarations.put("left", "0pt"); }
                if (declarations.get("right").equals("auto")) { declarations.put("right", "0pt"); }
            }
        }
        if (depth > 0 && (computed.breakBefore || computed.breakAfter)) {
            state.unsupported("CSS_PAGINATION_CONTEXT", location(source, id(source), context.cells()), "page breaks cannot occur inside an atomic CSS layout box", List.of("NESTED_PAGE_BREAK_REMOVED"), Treatment.DEGRADE);
        }
        var children = new ArrayList<HtmlCssBox>(); com.mirakyux.export4j.layout.core.Component leaf = null;
        if (CONTAINERS.contains(tag)) {
            var inline = new ArrayList<Node>();
            for (var node : source.childNodes()) {
                if (node instanceof Comment || node instanceof TextNode text && text.isBlank() && inline.isEmpty()) { continue; }
                if (node instanceof Element child && !INLINE.contains(child.normalName())) {
                    boxText(source, inline, children, context); var nested = box(child, new Context(id(child), context.cells(), 0), depth + 1, positioned);
                    if (nested != null) { children.add(nested); }
                } else { inline.add(node); }
            }
            boxText(source, inline, children, context);
        } else {
            var blocks = new ArrayList<Block>(); boolean previous = compilingBox; compilingBox = true;
            try { block(source, blocks, context); } finally { compilingBox = previous; }
            blocks.removeIf(block -> block instanceof PageBreak);
            if (!blocks.isEmpty()) { leaf = semanticBox(blocks, computed); }
            declarations.putIfAbsent("margin-top", computed.before + "pt"); declarations.putIfAbsent("margin-bottom", computed.after + "pt");
        }
        if (declarations.getOrDefault("display", "block").equals("flex")) {
            for (int i = 0; i < children.size(); i++) {
                var child = children.get(i);
                for (String name : List.of("flex-grow", "flex-shrink")) {
                    double factor = child.style.number(name, name.equals("flex-grow") ? 0 : 1);
                    if (!child.absolute() && factor > 0 && factor < 1) {
                        state.unsupported("CSS_LAYOUT", location(source, context.block(), context.cells()), "subunit flex factors are outside the finite distribution subset", List.of("SUBUNIT_FLEX_FACTOR_REPLACED_WITH_ONE"), Treatment.DEGRADE);
                        child = child.withDeclaration(name, "1"); children.set(i, child);
                    }
                }
            }
            boolean column = declarations.getOrDefault("flex-direction", "row").startsWith("column");
            for (var child : children) {
                if (!child.absolute() && child.style.length("flex-basis").automatic() && child.style.length(column ? "height" : "width").automatic()) {
                    state.unsupported("CSS_LAYOUT", location(source, context.block(), context.cells()), "flex items require an explicit main-axis size/basis in this finite layout subset", List.of("FLEX_REPLACED_WITH_BLOCK_FLOW"), Treatment.DEGRADE);
                    declarations.put("display", "block"); break;
                }
            }
            if (column && (!declarations.containsKey("height") || !declarations.getOrDefault("flex-wrap", "nowrap").equals("nowrap"))) {
                state.unsupported("CSS_LAYOUT", location(source, context.block(), context.cells()), "column flex needs an explicit height and nowrap", List.of("FLEX_REPLACED_WITH_BLOCK_FLOW"), Treatment.DEGRADE); declarations.put("display", "block");
            }
        }
        boolean grid = declarations.getOrDefault("display", "block").equals("grid");
        if (grid) {
            for (String name : List.of("height", "min-height", "max-height", "align-content", "justify-content")) {
                if (declarations.containsKey(name) && !Set.of("auto", "none").contains(declarations.get(name))) {
                    state.unsupported("CSS_LAYOUT", location(source, context.block(), context.cells()), "grid container block size and track-group alignment use content-sized start alignment in this subset", List.of("GRID_CONTAINER_CONSTRAINT_REMOVED"), Treatment.DEGRADE);
                    declarations.remove(name);
                }
            }
        }
        boolean columnFlex = declarations.getOrDefault("display", "block").equals("flex") && declarations.getOrDefault("flex-direction", "row").startsWith("column");
        if (grid || columnFlex) {
            String self = grid ? "justify-self" : "align-self", items = grid ? "justify-items" : "align-items";
            for (int i = 0; i < children.size(); i++) {
                var child = children.get(i); String alignment = child.style.text(self, "auto");
                if (alignment.equals("auto")) { alignment = declarations.getOrDefault(items, "stretch"); }
                if (!child.absolute() && child.style.length("width").automatic() && !alignment.equals("stretch")) {
                    state.unsupported("CSS_LAYOUT", location(source, context.block(), context.cells()), "non-stretch horizontal alignment requires an explicit item width", List.of("INTRINSIC_ALIGNMENT_REPLACED_WITH_STRETCH"), Treatment.DEGRADE);
                    children.set(i, child.withDeclaration(self, "stretch"));
                }
            }
        }
        if (declarations.getOrDefault("height", "auto").equals("auto")) {
            for (int i = 0; i < children.size(); i++) {
                var child = children.get(i);
                if (!child.style.text("position", "static").equals("relative")) { continue; }
                for (String name : List.of("top", "bottom")) {
                    if (child.style.length(name).percent()) {
                        state.unsupported("CSS_LAYOUT", location(source, context.block(), context.cells()), "percentage relative block offsets require an explicit containing height", List.of("PERCENTAGE_BLOCK_OFFSET_REMOVED"), Treatment.DEGRADE);
                        child = child.withDeclaration(name, "0pt"); children.set(i, child);
                    }
                }
            }
        }
        return new HtmlCssBox(new CssBoxStyle(declarations), children, leaf);
    }
    private void boxText(Element owner, List<Node> nodes, List<HtmlCssBox> children, Context context) {
        if (nodes.isEmpty()) { return; } String id = generatedId(); var content = content(nodes, owner, new Context(id, context.cells(), 0)); nodes.clear();
        if (!content.isEmpty()) { var style = css.get(owner); children.add(new HtmlCssBox(new CssBoxStyle(Map.of()), List.of(), semanticBox(List.of(Paragraph.mixed(id, content, style.paragraph(importer.format, 0))), style))); }
    }
    private com.mirakyux.export4j.layout.core.Component semanticBox(List<Block> source, HtmlCss.Computed computed) {
        if (source.size() == 1 && source.getFirst() instanceof Table table) { return new com.mirakyux.export4j.components.SemanticTable(table); }
        var blocks = new ArrayList<Block>();
        for (var block : source) {
            if (block instanceof Heading heading) { block = heading.paragraph(); }
            if (block instanceof Paragraph paragraph) {
                var format = paragraph.format().toBuilder().spaceBefore(0).spaceAfter(0).firstLineIndent(paragraph.format().firstLineIndent() - computed.indent).subsequentLineIndent(paragraph.format().subsequentLineIndent() - computed.indent).build();
                blocks.add(new Paragraph(paragraph.id(), paragraph.text(), format, paragraph.inlines(), paragraph.images(), paragraph.fields()));
            } else if (block instanceof Table) { blocks.add(block); }
            else { throw new IllegalArgumentException("CSS layout leaf supports paragraphs, headings and semantic tables"); }
        }
        var cell = TableCell.builder().blocks(blocks).format(new CellFormat(computed.size, computed.lineHeight(), computed.alignment)).build();
        var table = new Table(generatedId(), List.of(new ColumnWidth.Fraction(1)), List.of(new TableRow(List.of(cell))), 0,
                TableFormat.builder().padding(PageMargins.uniform(0)).spaceAfter(0).drawBorders(false).build());
        return new com.mirakyux.export4j.components.SemanticTable(table);
    }
    private boolean hidden(Element element, String id, List<CellLocation> cells) {
        if (!css.get(element).hidden) { return false; }
        if (!omitted.containsKey(element)) {
            state.unsupported("HIDDEN_CONTENT", location(element, id, cells), "hidden HTML source has no round-trip storage", List.of("HIDDEN_SUBTREE_REMOVED"), Treatment.DISCARD);
            omitted.put(element, true);
        }
        return true;
    }
    private List<InlineContent> content(List<Node> nodes, Element parent, Context context) {
        var output = new ArrayList<InlineContent>(); var whitespace = new Space();
        for (var node : nodes) { inline(node, parent, context, "", output, whitespace); }
        if (whitespace.pending) { whitespace.pending = false; }
        return List.copyOf(output);
    }
    private static final class Space {
        boolean pending;
        boolean beginning = true;
        HtmlCss.Computed style;
        String link = "";
    }
    private void inline(Node node, Element parent, Context context, String link, List<InlineContent> output, Space space) {
        state.work(1);
        if (node instanceof TextNode text) {
            String literal = text.getWholeText(); var computed = css.get(parent);
            if (computed.pre) { pendingSpace(space, output); append(output, literal, computed, link); space.beginning = literal.endsWith("\n"); return; }
            var normalized = new StringBuilder();
            for (int index = 0; index < literal.length(); index++) {
                char value = literal.charAt(index);
                if (value == ' ' || value == '\n' || value == '\r' || value == '\t' || value == '\f') {
                    if (!normalized.isEmpty()) { append(output, normalized.toString(), computed, link); normalized.setLength(0); }
                    if (!space.beginning && !space.pending) { space.pending = true; space.style = computed; space.link = link; }
                } else { pendingSpace(space, output); normalized.append(value); space.beginning = false; }
            }
            if (!normalized.isEmpty()) { append(output, normalized.toString(), computed, link); }
        } else if (node instanceof Element element) {
            String tag = element.normalName(); attributes(element, context.block(), context.cells());
            if (hidden(element, context.block(), context.cells())) { return; }
            if (tag.equals("br")) { append(output, "\n", css.get(element), link); space.beginning = true; space.pending = false; }
            else if (tag.equals("a")) {
                String target = element.hasAttr("href") ? link(element.attr("href"), element, context) : link;
                for (var child : element.childNodes()) { inline(child, element, context, target, output, space); }
            } else if (tag.equals("img")) {
                var image = importer.images.get(element.attr("src"));
                if (image == null) {
                    if (loss(element, "IMAGE_RESOURCE", "HTML image is not explicitly registered", "IMAGE_REPLACED_WITH_ALT_TEXT", context.block(), context.cells()) == Treatment.DEGRADE) {
                        inline(new TextNode(element.attr("alt")), parent, context, link, output, space);
                    }
                } else {
                    pendingSpace(space, output);
                    double width = image.width(), height = image.height();
                    if (element.hasAttr("width")) { width = dimension(element.attr("width")); }
                    if (element.hasAttr("height")) { height = dimension(element.attr("height")); }
                    if (element.hasAttr("width") && !element.hasAttr("height")) { height = image.height() * width / image.width(); }
                    if (element.hasAttr("height") && !element.hasAttr("width")) { width = image.width() * height / image.height(); }
                    var imageStyle = css.get(element).box;
                    boolean cssWidth = imageStyle.containsKey("width") && !imageStyle.get("width").equals("auto"), cssHeight = imageStyle.containsKey("height") && !imageStyle.get("height").equals("auto");
                    if (cssWidth) { width = HtmlCss.length(imageStyle.get("width")); }
                    if (cssHeight) { height = HtmlCss.length(imageStyle.get("height")); }
                    if (cssWidth && !cssHeight && !element.hasAttr("height")) { height = image.height() * width / image.width(); }
                    if (cssHeight && !cssWidth && !element.hasAttr("width")) { width = image.width() * height / image.height(); }
                    output.add(new InlineImage(image.resource(), width, height, image.baselineShift(), element.attr("alt"), link)); state.text(1); space.beginning = false;
                }
            } else if (INLINE.contains(tag) || tag.equals("code")) {
                if (tag.equals("code") && loss(element, "INLINE_CODE", "model has no inline code semantics", "CODE_SEMANTICS_REMOVED", context.block(), context.cells()) == Treatment.DISCARD) { return; }
                for (var child : element.childNodes()) { inline(child, element, context, link, output, space); }
            } else if (loss(element, "INLINE_ELEMENT", "HTML inline container has no semantic mapping", "INLINE_CONTAINER_FLATTENED", context.block(), context.cells()) == Treatment.DEGRADE) {
                for (var child : element.childNodes()) { inline(child, element, context, link, output, space); }
            }
        } else if (!(node instanceof Comment)) { throw new IllegalArgumentException("unsupported HTML data node"); }
    }
    private void append(List<InlineContent> output, String literal, HtmlCss.Computed style, String link) {
        state.text(literal.length()); output.add(new InlineText(literal, style.inline(), link));
    }
    private void pendingSpace(Space space, List<InlineContent> output) {
        if (space.pending) { append(output, " ", space.style, space.link); space.pending = false; }
    }
    private List<InlineText> textOnly(List<InlineContent> content, Element source, Context context) {
        var runs = new ArrayList<InlineText>();
        for (var value : content) {
            if (value instanceof InlineText run) { runs.add(run); }
            else if (value instanceof InlineImage image && loss(source, "TEXT_BLOCK_IMAGE", "heading/list cannot hold an inline image", "IMAGE_REPLACED_WITH_ALT_TEXT", context.block(), context.cells()) == Treatment.DEGRADE) {
                state.text(image.altText().length()); runs.add(new InlineText(image.altText(), css.get(source).inline(), image.hyperlink()));
            }
        }
        return runs;
    }
    private void list(Element source, List<Block> output, Context context, int depth, String parentList, int numberDepth) {
        if (depth > 9) { throw new IllegalArgumentException("HTML list nesting exceeds model limit"); }
        boolean ordered = source.normalName().equals("ol"); String listId = ordered && parentList.isEmpty() ? id(source) : parentList;
        int level = ordered ? numberDepth + 1 : depth;
        int start = ordered && source.hasAttr("start") ? positiveInteger(source.attr("start")) : 1;
        int index = 0;
        for (var item : source.children()) {
            state.work(1); if (!item.normalName().equals("li")) { throw new IllegalArgumentException("invalid HTML list structure"); }
            String id = id(item); attributes(item, id, context.cells());
            if (hidden(item, id, context.cells())) { index++; continue; }
            var inline = new ArrayList<Node>(); var nested = new ArrayList<Element>();
            for (var child : item.childNodes()) {
                if (child instanceof Element element && Set.of("ol", "ul").contains(element.normalName())) { nested.add(element); }
                else if (!nested.isEmpty() && !(child instanceof TextNode text && text.isBlank())) { throw new IllegalArgumentException("HTML list content after nested list is not supported"); }
                else if (nested.isEmpty()) { inline.add(child); }
            }
            var own = new Context(id, context.cells(), context.indent());
            Element marker = !inline.isEmpty() && inline.getFirst() instanceof Element first && first.hasAttr("data-export4j-marker") ? first : null;
            String markerText = ""; int markerLevel = 1;
            if (marker != null) {
                if (!marker.normalName().equals("span") || !marker.attr("data-export4j-marker").equals("1")
                        || !marker.className().equals("marker") || marker.childNodes().stream().anyMatch(node -> !(node instanceof TextNode))) {
                    throw new IllegalArgumentException("invalid explicit HTML list marker");
                }
                attributes(marker, id, context.cells());
                markerLevel = positiveInteger(marker.attr("data-export4j-level"));
                if (markerLevel > 9) { throw new IllegalArgumentException("HTML marker level exceeds model limit"); }
                markerText = marker.wholeText();
                if (markerText.isEmpty() || css.get(marker).hidden) { throw new IllegalArgumentException("empty or hidden explicit HTML list marker"); }
                if (markerLevel != 1 || !nested.isEmpty() || ordered && !markerText.matches("[1-9][0-9]*\\.")) {
                    if (loss(marker, "LIST_MARKER", "complex explicit marker has no native list mapping", "LIST_STRUCTURE_FLATTENED", id, context.cells()) == Treatment.DEGRADE) {
                        output.add(Paragraph.mixed(id, content(inline, item, own), css.get(item).paragraph(importer.format, context.indent())));
                    }
                    for (var child : nested) { attributes(child, id(child), context.cells()); list(child, output, context, depth + 1, listId, ordered ? level : numberDepth); }
                    index++; continue;
                }
                state.text(markerText.length());
                inline.removeFirst();
            }
            var runs = textOnly(content(inline, item, own), item, own);
            var format = css.get(item).paragraph(importer.format, context.indent() + (ordered ? depth - level : 0) * 18);
            var markerStyle = css.get(marker == null ? item : marker).inline();
            if (ordered) {
                int number = marker == null ? Math.addExact(start, index) : positiveInteger(markerText.substring(0, markerText.length() - 1));
                output.add(NumberedItem.rich(id, listId, runs, number, format, level, markerStyle));
            } else { output.add(BulletItem.rich(id, runs, marker == null ? "•" : markerText, level, format, markerStyle)); }
            for (var child : nested) { attributes(child, id(child), context.cells()); list(child, output, context, depth + 1, listId, ordered ? level : numberDepth); }
            index++;
        }
    }
    private Table table(Element source, Context context, int depth) {
        if (depth > com.mirakyux.export4j.model.SemanticTree.MAX_TABLE_DEPTH) { throw new IllegalArgumentException("HTML table nesting exceeds model limit"); }
        var inputRows = new ArrayList<Element>(); int headers = 0; int declaredColumns = 0;
        for (var child : source.children()) {
            String tag = child.normalName();
            if (tag.equals("colgroup")) {
                if (!inputRows.isEmpty()) { throw new IllegalArgumentException("HTML column groups must precede rows"); }
                int count = columnGroup(child, context);
                if (count > state.limits.maxTableSlots() - declaredColumns) { throw new IllegalArgumentException("HTML column declaration budget exceeded"); }
                declaredColumns += count;
            } else if (tag.equals("tr")) { inputRows.add(child); }
            else if (Set.of("thead", "tbody", "tfoot").contains(tag)) {
                attributes(child, context.block(), context.cells());
                if (css.get(child).hidden) { throw new IllegalArgumentException("hidden HTML row groups cannot preserve table coverage"); }
                if (tag.equals("thead") && !inputRows.isEmpty()) { throw new IllegalArgumentException("HTML table header must be first"); }
                for (var row : child.children()) { if (!row.normalName().equals("tr")) { throw new IllegalArgumentException("invalid HTML table row group"); } inputRows.add(row); }
                if (tag.equals("thead")) { headers = inputRows.size(); }
            } else { throw new IllegalArgumentException("HTML table child has no supported structural mapping"); }
        }
        if (inputRows.isEmpty()) { throw new IllegalArgumentException("HTML table must have rows"); }
        if (headers == 0) {
            for (var row : inputRows) {
                if (row.children().isEmpty() || !row.children().stream().allMatch(cell -> cell.normalName().equals("th"))) { break; }
                headers++;
            }
        }
        var rows = new ArrayList<TableRow>(); var occupied = new HashMap<Integer, Set<Integer>>(); int columns = 0, borderless = 0, cellCount = 0;
        for (int rowIndex = 0; rowIndex < inputRows.size(); rowIndex++) {
            state.work(1); var row = inputRows.get(rowIndex); attributes(row, context.block(), context.cells());
            if (css.get(row).hidden) { throw new IllegalArgumentException("hidden HTML rows cannot preserve table coverage"); }
            var cells = new ArrayList<TableCell>(); int column = 0;
            for (var cell : row.children()) {
                while (occupied.getOrDefault(rowIndex, Set.of()).contains(column)) { column++; state.work(1); }
                if (!Set.of("td", "th").contains(cell.normalName())) { throw new IllegalArgumentException("invalid HTML cell structure"); }
                int width = cell.hasAttr("colspan") ? positiveInteger(cell.attr("colspan")) : 1;
                int height = cell.hasAttr("rowspan") ? positiveInteger(cell.attr("rowspan")) : 1;
                long slots = (long) width * height;
                if (slots > state.limits.maxTableSlots() - state.tableSlots || height > inputRows.size() - rowIndex) { throw new IllegalArgumentException("HTML table coverage/budget exceeded"); }
                state.tableSlots += slots; state.work(slots);
                var path = new ArrayList<>(context.cells()); path.add(new CellLocation(context.block(), rowIndex, column, -1));
                attributes(cell, context.block(), path);
                if (css.get(cell).hidden) { throw new IllegalArgumentException("hidden HTML table cells cannot preserve grid coverage"); }
                if (cell.normalName().equals("th") && rowIndex >= headers) { loss(cell, "TABLE_HEADER_ROLE", "body header-cell role has no model association", "HEADER_ASSOCIATION_REMOVED", context.block(), path); }
                for (int y = rowIndex; y < rowIndex + height; y++) {
                    var coverage = occupied.computeIfAbsent(y, unused -> new HashSet<>());
                    for (int x = column; x < column + width; x++) { if (!coverage.add(x)) { throw new IllegalArgumentException("overlapping HTML table cells"); } }
                }
                var style = css.get(cell); var format = new CellFormat(style.size, style.lineHeight(), style.alignment);
                cellCount++; if (style.borderless) { borderless++; }
                var own = new Context(context.block(), List.copyOf(path), 0); TableCell value;
                if (cell.children().stream().anyMatch(child -> Set.of("p", "table", "div").contains(child.normalName()))) {
                    var blocks = new ArrayList<Block>();
                    for (var child : cell.children()) {
                        if (child.normalName().equals("table")) { String childId = id(child); attributes(child, childId, path); blocks.add(table(child, new Context(childId, List.copyOf(path), 0), depth + 1)); }
                        else if (child.normalName().equals("p")) { block(child, blocks, own); }
                        else if (child.normalName().equals("div")) {
                            attributes(child, context.block(), path);
                            if (!child.className().equals("table-scroll") || child.childrenSize() != 1
                                    || !child.child(0).normalName().equals("table")
                                    || child.textNodes().stream().anyMatch(text -> !text.isBlank()) || css.get(child).hidden) {
                                throw new IllegalArgumentException("HTML cell table wrapper requires one visible table");
                            }
                            if (loss(child, "TABLE_CELL_CONTAINER", "scroll wrapper geometry has no block-cell mapping",
                                    "CELL_TABLE_WRAPPER_REMOVED", context.block(), path) == Treatment.DEGRADE) {
                                var nested = child.child(0); String childId = id(nested); attributes(nested, childId, path);
                                blocks.add(table(nested, new Context(childId, List.copyOf(path), 0), depth + 1));
                            }
                        } else { throw new IllegalArgumentException("HTML block cell supports paragraphs and tables only"); }
                    }
                    if (cell.textNodes().stream().anyMatch(text -> !text.isBlank())) { throw new IllegalArgumentException("HTML block cell has uncontained text"); }
                    if (rowIndex < headers) {
                        if (blocks.size() != 1 || !(blocks.getFirst() instanceof Paragraph paragraph)) {
                            throw new IllegalArgumentException("HTML header block cell requires one paragraph");
                        }
                        var treatment = loss(cell, "TABLE_HEADER_PARAGRAPH", "repeating header cannot retain paragraph block geometry and identity",
                                "HEADER_PARAGRAPH_FLATTENED", context.block(), path);
                        value = TableCell.mixed(treatment == Treatment.DISCARD ? List.of() : paragraph.content(), format)
                                .toBuilder().columnSpan(width).rowSpan(height).build();
                    } else if (blocks.isEmpty()) { value = TableCell.mixed(List.of(), format).toBuilder().columnSpan(width).rowSpan(height).build(); }
                    else { value = TableCell.builder().format(format).blocks(blocks).columnSpan(width).rowSpan(height).build(); }
                } else { value = TableCell.mixed(content(cell.childNodes(), cell, own), format).toBuilder().columnSpan(width).rowSpan(height).build(); }
                cells.add(value); column += width;
            }
            columns = Math.max(columns, occupied.getOrDefault(rowIndex, Set.of()).size()); rows.add(new TableRow(cells));
        }
        if (headers >= rows.size()) { throw new IllegalArgumentException("HTML table requires body rows"); }
        if (declaredColumns > columns) { throw new IllegalArgumentException("HTML column declarations exceed cell grid"); }
        var widths = new ArrayList<ColumnWidth>(); for (int column = 0; column < columns; column++) { widths.add(new ColumnWidth.Fraction(1)); }
        if (borderless != 0 && borderless != cellCount) { state.unsupported("TABLE_BORDERS", location(source, context.block(), context.cells()), "the model uses one border flag for the complete table", List.of("MIXED_CELL_BORDER_OVERRIDES_REMOVED"), Treatment.DEGRADE); }
        return new Table(context.block(), widths, rows, headers, TableFormat.DEFAULT.toBuilder().drawBorders(borderless != cellCount).build());
    }
    private int columnGroup(Element group, Context context) {
        attributes(group, context.block(), context.cells());
        if (css.get(group).hidden || group.textNodes().stream().anyMatch(text -> !text.isBlank())) {
            throw new IllegalArgumentException("hidden or textual HTML column groups are not supported");
        }
        int count = 0;
        if (group.children().isEmpty()) { count = group.hasAttr("span") ? positiveInteger(group.attr("span")) : 1; }
        else {
            if (group.hasAttr("span")) { throw new IllegalArgumentException("HTML column group cannot combine span and children"); }
            for (var column : group.children()) {
                state.work(1);
                if (!column.normalName().equals("col") || column.childNodeSize() != 0) { throw new IllegalArgumentException("invalid HTML column group"); }
                attributes(column, context.block(), context.cells());
                if (css.get(column).hidden) { throw new IllegalArgumentException("hidden HTML columns are not supported"); }
                int span = column.hasAttr("span") ? positiveInteger(column.attr("span")) : 1;
                if (span > state.limits.maxTableSlots() - count) { throw new IllegalArgumentException("HTML column declaration budget exceeded"); }
                count += span;
            }
        }
        if (count > state.limits.maxTableSlots()) { throw new IllegalArgumentException("HTML column declaration budget exceeded"); }
        state.work(count);
        loss(group, "TABLE_COLUMNS", "column-group geometry has no supported model mapping", "COLUMNS_REPLACED_WITH_EQUAL_FRACTIONS", context.block(), context.cells());
        return count;
    }
    private void attributes(Element element, String id, List<CellLocation> cells) {
        if (element == null || !checkedAttributes.add(element)) { return; }
        String tag = element.normalName();
        if (element.hasAttr("id") && !tag.matches("p|h[1-6]|table|li") && !layoutAnchors.contains(element)) {
            loss(element, "SOURCE_ANCHOR", "source anchor has no corresponding model block", "ANCHOR_REMOVED", id, cells);
        }
        for (var attribute : element.attributes()) {
            String name = attribute.getKey(); if (GLOBAL.contains(name)) { continue; }
            boolean allowed = switch (tag) {
                case "a" -> name.equals("href"); case "img" -> Set.of("src", "alt", "width", "height").contains(name);
                case "ol" -> name.equals("start"); case "td", "th" -> Set.of("rowspan", "colspan").contains(name);
                case "colgroup", "col" -> name.equals("span");
                case "span" -> Set.of("data-export4j-marker", "data-export4j-level").contains(name);
                default -> false;
            };
            if (!allowed) { loss(element, "HTML_ATTRIBUTE", "HTML attribute has no supported semantic mapping", "ATTRIBUTE_REMOVED", id, cells); }
        }
    }
    private Treatment loss(Node source, String feature, String reason, String effect, String id, List<CellLocation> cells) {
        var treatment = state.policy.resolve("HTML", feature, "IMPORT").treatment();
        return state.unsupported(feature, location(source, id, cells), reason, List.of(treatment == Treatment.DISCARD ? "UNSUPPORTED_SOURCE_PART_REMOVED" : effect), Treatment.DEGRADE, Treatment.DISCARD);
    }
    private String link(String raw, Element source, Context context) {
        var uri = safeLink(raw); if (!uri.isAbsolute() && importer.baseUri != null && !raw.startsWith("#")) { uri = importer.baseUri.resolve(uri); }
        if (!uri.isAbsolute()) { loss(source, "RELATIVE_LINK", "relative/internal link has no explicit model destination", "LINK_TARGET_REMOVED", context.block(), context.cells()); return ""; }
        return new ExternalDestination(uri.toString()).uri();
    }
    private static URI safeLink(String value) {
        try {
            var uri = URI.create(value); String scheme = uri.getScheme();
            if (value.length() > 8192 || value.codePoints().anyMatch(Character::isISOControl) || uri.getUserInfo() != null
                    || scheme != null && !Set.of("http", "https", "mailto").contains(scheme.toLowerCase(Locale.ROOT)) || !uri.isAbsolute() && value.startsWith("//")) {
                throw new IllegalArgumentException();
            }
            if (uri.isAbsolute()) { new ExternalDestination(value); } return uri;
        } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("invalid or unsafe HTML hyperlink"); }
    }
    private static int positiveInteger(String value) {
        if (!value.matches("[1-9][0-9]{0,8}")) { throw new IllegalArgumentException("HTML integer must be positive and bounded"); }
        return Integer.parseInt(value);
    }
    private static double dimension(String value) { return positiveInteger(value) * 0.75; }
    static ProcessingIssue.Location location(Node node, String id, List<CellLocation> cells) {
        var range = node.sourceRange(); int start = range.isTracked() ? range.start().pos() : -1, end = range.isTracked() ? range.end().pos() : -1;
        if (node instanceof Element element && element.endSourceRange().isTracked() && start >= 0) { end = Math.max(end, element.endSourceRange().end().pos()); }
        return new ProcessingIssue.Location("", "BODY", id, cells, start, end);
    }
}
