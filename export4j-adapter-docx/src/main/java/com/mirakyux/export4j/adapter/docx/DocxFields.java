// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.model.Table;
import static com.mirakyux.export4j.adapter.docx.DocxAdapter.child;
import static com.mirakyux.export4j.adapter.docx.DocxAdapter.children;
import static com.mirakyux.export4j.adapter.docx.DocxAdapter.escape;
import static com.mirakyux.export4j.adapter.docx.DocxAdapter.loss;

/** Offline whitelist for native Word fields. Cached text is never used as a dynamic field value. */
final class DocxFields {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String OD = "urn:com.mirakyux.export4j:semantic:1";
    private static final int MAX_FIELDS = 100_000;
    private DocxFields() { }

    static final class Writer {
        private final Map<String, Integer> bookmarks = new LinkedHashMap<>();
        private boolean present;

        Writer(SectionedDocument document) throws IOException {
            List<Block> blocks = new ArrayList<>();
            var bandBlocks = new java.util.HashSet<Block>();
            var bands = new java.util.HashSet<com.mirakyux.export4j.model.PageRegion>();
            document.sections().forEach(section -> {
                SemanticTree.walk(section.blocks(), visit -> blocks.add(visit.block()));
                for (var band : List.of(section.pageRegions().header(), section.pageRegions().footer())) {
                    if (bands.add(band)) { SemanticTree.walk(band.blocks(), visit -> {
                        blocks.add(visit.block()); bandBlocks.add(visit.block());
                    }); }
                }
            });
            var byId = new HashMap<String, Block>();
            blocks.stream().filter(block -> !bandBlocks.contains(block)).forEach(b -> byId.put(b.id(), b));
            int count = 0;
            for (var block : blocks) {
                var fields = new ArrayList<Paragraph.FieldSpan>();
                if (block instanceof Paragraph p) { fields.addAll(p.fields()); }
                if (block instanceof Table t) {
                    for (int row = 0; row < t.rows().size(); row++) {
                        for (var cell : t.rows().get(row).cells()) {
                            if (cell.hasBlocks()) { continue; }
                            if (!bandBlocks.contains(block) && row < t.headerRows() && cell.fields().stream().anyMatch(f -> f.field().kind() == InlineField.Kind.PAGE)) {
                                throw new IOException("DOCX repeated table headers cannot preserve per-occurrence PAGE fields");
                            }
                            fields.addAll(cell.fields());
                        }
                    }
                }
                for (var span : fields) {
                    if (++count > MAX_FIELDS) { throw new IOException("DOCX field budget exceeded"); }
                    var field = span.field();
                    if (field.kind() == InlineField.Kind.NOTE_MARKER) { throw new IOException("DOCX inline note markers are unsupported"); }
                    if (field.kind() == InlineField.Kind.REFERENCE_PAGE || field.kind() == InlineField.Kind.REFERENCE_NUMBER) {
                        var target = byId.get(field.targetId());
                        if (target == null) { throw new IOException("DOCX field target is missing"); }
                        if (field.kind() == InlineField.Kind.REFERENCE_NUMBER && !(target instanceof NumberedItem)) {
                            throw new IOException("DOCX number references require a numbered item");
                        }
                        bookmarks.computeIfAbsent(field.targetId(), ignored -> bookmarks.size());
                    }
                    present = true;
                }
            }
        }

        boolean present() { return present; }
        void start(StringBuilder xml, String id) {
            var number = bookmarks.get(id);
            if (number != null) { xml.append("<w:bookmarkStart w:id=\"").append(number).append("\" w:name=\"od_b_").append(number)
                    .append("\" od:owner=\"").append(escape(id)).append("\"/>"); }
        }
        void end(StringBuilder xml, String id) {
            var number = bookmarks.get(id);
            if (number != null) { xml.append("<w:bookmarkEnd w:id=\"").append(number).append("\" od:owner=\"").append(escape(id)).append("\"/>"); }
        }
        void write(StringBuilder xml, InlineField field) {
            String instruction = switch (field.kind()) {
                case PAGE -> "PAGE";
                case PAGES -> "NUMPAGES";
                case REFERENCE_PAGE -> "PAGEREF od_b_" + bookmarks.get(field.targetId()) + " \\h";
                case REFERENCE_NUMBER -> "REF od_b_" + bookmarks.get(field.targetId()) + " \\w \\h";
                case NOTE_MARKER -> throw new IllegalArgumentException("unvalidated DOCX note marker");
            };
            xml.append("<w:fldSimple w:instr=\"").append(escape(instruction)).append("\" w:dirty=\"true\" od:width=\"")
                    .append(field.width()).append("\"><w:r>");
            DocxAdapter.runProperties(xml, field.style());
            xml.append("<w:t xml:space=\"preserve\"></w:t></w:r></w:fldSimple>");
        }
    }

    static final class Reader {
        private record Anchor(String blockId, Element block, String endBlockId, boolean atBlockStart) { }
        private final Map<String, Anchor> anchors = new HashMap<>();
        private final DocxAdapter.Policy policy;
        private final List<DocxAdapter.Diagnostic> diagnostics;
        private final double defaultWidth;
        private final Map<String, Element> numbers;
        private int count;

        Reader(Element body, Map<Element, String> ids, DocxAdapter.Policy policy,
                List<DocxAdapter.Diagnostic> diagnostics, double defaultWidth, Map<String, Element> numbers) throws IOException {
            this.policy = policy; this.diagnostics = diagnostics; this.defaultWidth = defaultWidth; this.numbers = numbers;
            var starts = new HashMap<String, Element>();
            var owners = new HashMap<String, Element>();
            var ends = new HashMap<String, String>();
            var endNodes = new HashMap<String, Element>();
            var nodes = body.getElementsByTagNameNS(W, "bookmarkStart");
            for (int i = 0; i < nodes.getLength(); i++) {
                var start = (Element) nodes.item(i);
                String id = start.getAttributeNS(W, "id");
                if (starts.size() >= MAX_FIELDS || !id.matches("[0-9]{1,10}") || starts.putIfAbsent(id, start) != null) {
                    throw new IOException("duplicate, invalid or excessive DOCX bookmarks");
                }
                var owner = bookmarkOwner(start, ids);
                if (start.hasAttributeNS(OD, "owner") && !atBlockStart(start, owner)) {
                    throw new IOException("DOCX bookmark owner hint requires the modeled block start");
                }
                owners.put(id, owner);
            }
            nodes = body.getElementsByTagNameNS(W, "bookmarkEnd");
            for (int i = 0; i < nodes.getLength(); i++) {
                var end = (Element) nodes.item(i);
                String id = end.getAttributeNS(W, "id");
                endNodes.put(id, end);
                if (ends.putIfAbsent(id, ids.get(bookmarkOwner(end, ids))) != null) { throw new IOException("duplicate DOCX bookmark end"); }
            }
            if (!starts.keySet().equals(ends.keySet())) { throw new IOException("unbalanced DOCX bookmarks"); }
            for (var entry : starts.entrySet()) {
                if ((entry.getValue().compareDocumentPosition(endNodes.get(entry.getKey())) & Node.DOCUMENT_POSITION_FOLLOWING) == 0) {
                    throw new IOException("DOCX bookmark ends before its start");
                }
                String name = entry.getValue().getAttributeNS(W, "name");
                var owner = owners.get(entry.getKey());
                var end = endNodes.get(entry.getKey());
                if ((entry.getValue().hasAttributeNS(OD, "owner") || end.hasAttributeNS(OD, "owner"))
                        && (!entry.getValue().hasAttributeNS(OD, "owner") || !end.hasAttributeNS(OD, "owner")
                        || !ids.get(owner).equals(ends.get(entry.getKey())))) {
                    throw new IOException("DOCX bookmark owner hints must agree on both boundaries");
                }
                if (name.isBlank() || anchors.putIfAbsent(name, new Anchor(ids.get(owner), owner, ends.get(entry.getKey()), atBlockStart(entry.getValue(), owner))) != null) {
                    throw new IOException("duplicate or invalid DOCX bookmark name");
                }
            }
        }

        private static Element bookmarkOwner(Element bookmark, Map<Element, String> ids) throws IOException {
            String hint = bookmark.getAttributeNS(OD, "owner");
            for (Node node = bookmark.getParentNode(); node instanceof Element element; node = node.getParentNode()) {
                if (ids.containsKey(element) && (!bookmark.hasAttributeNS(OD, "owner") || ids.get(element).equals(hint))) { return element; }
            }
            throw new IOException("DOCX bookmark has no valid modeled ancestor owner");
        }

        /** Converts a flat begin/instrText/separate/result/end sequence into one simple field before run decoding. */
        void normalize(Element paragraph) throws IOException {
            Element field = null;
            Element beginProperties = null;
            var instruction = new StringBuilder();
            boolean result = false;
            for (var node : children(paragraph)) {
                var marker = child(node, "fldChar");
                if (marker != null) {
                    if (!node.getLocalName().equals("r") || children(node).stream().filter(e -> e.getLocalName().equals("fldChar")).count() != 1
                            || children(node).stream().anyMatch(e -> !Set.of("rPr", "fldChar").contains(e.getLocalName()))) {
                        throw new IOException("mixed DOCX field control run");
                    }
                    String kind = marker.getAttributeNS(W, "fldCharType");
                    switch (kind) {
                        case "begin" -> {
                            if (field != null) { throw new IOException("nested DOCX fields are unsupported"); }
                            field = paragraph.getOwnerDocument().createElementNS(W, "w:fldSimple");
                            if (marker.hasAttributeNS(OD, "width")) { field.setAttributeNS(OD, "od:width", marker.getAttributeNS(OD, "width")); }
                            beginProperties = child(node, "rPr");
                            if (marker.hasAttributeNS(W, "fldLock")) { field.setAttributeNS(W, "w:fldLock", marker.getAttributeNS(W, "fldLock")); }
                            paragraph.insertBefore(field, node);
                            instruction.setLength(0); result = false;
                        }
                        case "separate" -> {
                            if (field == null || result) { throw new IOException("unbalanced DOCX field separator"); }
                            result = true;
                        }
                        case "end" -> {
                            if (field == null) { throw new IOException("unbalanced DOCX field end"); }
                            field.setAttributeNS(W, "w:instr", instruction.toString());
                            if (child(field, "r") == null) {
                                var run = paragraph.getOwnerDocument().createElementNS(W, "w:r");
                                if (beginProperties != null) { run.appendChild(beginProperties.cloneNode(true)); }
                                field.appendChild(run);
                            }
                            field = null;
                        }
                        default -> throw new IOException("unknown DOCX field control");
                    }
                    paragraph.removeChild(node);
                } else if (field != null) {
                    if (!W.equals(node.getNamespaceURI()) || !node.getLocalName().equals("r")) { throw new IOException("unsupported DOCX field structure"); }
                    if (result) { field.appendChild(node); }
                    else {
                        for (var content : children(node)) {
                            if (content.getLocalName().equals("instrText")) { instruction.append(content.getTextContent()); }
                            else if (!content.getLocalName().equals("rPr")) { throw new IOException("DOCX field has content before separator"); }
                        }
                        paragraph.removeChild(node);
                    }
                    if (instruction.length() > 2048) { throw new IOException("DOCX field instruction budget exceeded"); }
                } else if (node.getElementsByTagNameNS(W, "fldChar").getLength() > 0 || node.getElementsByTagNameNS(W, "instrText").getLength() > 0) {
                    throw new IOException("unsupported or unbalanced DOCX complex field");
                } else if (node.getLocalName().equals("fldSimple") && node.getElementsByTagNameNS(W, "fldSimple").getLength() > 0) {
                    throw new IOException("nested DOCX fields are unsupported");
                }
            }
            if (field != null) { throw new IOException("unterminated DOCX field"); }
        }

        List<InlineContent> read(Element field, double fontSize) throws IOException {
            if (++count > MAX_FIELDS) { throw new IOException("DOCX field budget exceeded"); }
            if (field.getElementsByTagNameNS(W, "fldSimple").getLength() > 0
                    || field.getElementsByTagNameNS(W, "fldChar").getLength() > 0
                    || field.getElementsByTagNameNS(W, "instrText").getLength() > 0) { throw new IOException("nested or malformed DOCX field"); }
            double width = defaultWidth;
            if (field.hasAttributeNS(OD, "width")) {
                width = DocxAdapter.number(field.getAttributeNS(OD, "width"));
                if (width <= 0 || width > Float.MAX_VALUE) { throw new IOException("invalid DOCX field reserved width"); }
            }
            var cache = new ArrayList<InlineContent>();
            InlineStyle style = null;
            boolean unsupportedResult = false;
            for (var run : children(field)) {
                if (!W.equals(run.getNamespaceURI()) || !run.getLocalName().equals("r")) { unsupportedResult = true; continue; }
                var ink = DocxAdapter.inline(child(run, "rPr"), fontSize);
                if (style == null) { style = ink; }
                else if (!style.equals(ink)) { unsupportedResult = true; }
                var text = new StringBuilder();
                for (var content : children(run)) {
                    switch (content.getLocalName()) {
                        case "t" -> text.append(content.getTextContent());
                        case "tab" -> text.append('\t');
                        case "br" -> text.append('\n');
                        case "rPr" -> { }
                        default -> unsupportedResult = true;
                    }
                }
                if (!text.isEmpty()) { cache.add(new InlineText(text.toString(), ink)); }
            }
            if (style == null) { style = InlineStyle.of(fontSize); }
            String instruction = field.getAttributeNS(W, "instr").strip();
            if (instruction.length() > 2048) { throw new IOException("DOCX field instruction budget exceeded"); }
            if (unsupportedResult || Set.of("true", "1", "on").contains(field.getAttributeNS(W, "fldLock"))) {
                return reduced(cache, "locked or rich field result is unsupported");
            }
            var tokens = instruction.isEmpty() ? new String[0] : instruction.split("\\s+");
            InlineField.Kind kind;
            if (tokens.length == 0) { return reduced(cache, "empty field instruction"); }
            kind = switch (tokens[0].toUpperCase(Locale.ROOT)) {
                case "PAGE" -> InlineField.Kind.PAGE;
                case "NUMPAGES" -> InlineField.Kind.PAGES;
                case "PAGEREF" -> InlineField.Kind.REFERENCE_PAGE;
                case "REF" -> InlineField.Kind.REFERENCE_NUMBER;
                default -> null;
            };
            if (kind == null) { return reduced(cache, "unsupported field instruction; cached text retained"); }
            boolean reference = kind == InlineField.Kind.REFERENCE_PAGE || kind == InlineField.Kind.REFERENCE_NUMBER;
            if (reference && (tokens.length < 2 || !tokens[1].matches("[A-Za-z_][A-Za-z0-9_]{0,39}"))) {
                return reduced(cache, "invalid field bookmark target");
            }
            boolean fullNumber = false;
            var switches = new java.util.HashSet<String>();
            for (int i = reference ? 2 : 1; i < tokens.length; i++) {
                String token = tokens[i].toLowerCase(Locale.ROOT);
                if (!switches.add(token)) { return reduced(cache, "duplicate field switch"); }
                if (token.equals("\\h") && reference) { continue; }
                if (token.equals("\\w") && kind == InlineField.Kind.REFERENCE_NUMBER) { fullNumber = true; continue; }
                if (token.equals("\\*") && i + 1 < tokens.length && Set.of("ARABIC", "MERGEFORMAT").contains(tokens[i + 1].toUpperCase(Locale.ROOT))) { i++; continue; }
                return reduced(cache, "unsupported field switch");
            }
            if (kind == InlineField.Kind.REFERENCE_NUMBER && !fullNumber) { return reduced(cache, "REF requires full paragraph number switch"); }
            String target = "";
            if (reference) {
                var anchor = anchors.get(tokens[1]);
                if (anchor == null) { return reduced(cache, "field bookmark target is missing"); }
                if (kind == InlineField.Kind.REFERENCE_PAGE && !anchor.atBlockStart()) { return reduced(cache, "PAGEREF requires a bookmark at the block start"); }
                if (kind == InlineField.Kind.REFERENCE_NUMBER && !numbered(anchor)) {
                    return reduced(cache, "REF requires a single numbered paragraph");
                }
                target = anchor.blockId();
            }
            if (width == 0) { return reduced(cache, "field reserved width is missing; configure an explicit import width"); }
            try { return List.of(new InlineField(kind, target, width, style)); }
            catch (IllegalArgumentException failure) { throw new IOException("invalid DOCX field geometry", failure); }
        }

        private static boolean atBlockStart(Element start, Element block) {
            Element paragraph = block;
            while (paragraph != null && paragraph.getLocalName().equals("tbl")) {
                var cell = child(child(paragraph, "tr"), "tc");
                paragraph = children(cell).stream().filter(element -> W.equals(element.getNamespaceURI())
                        && Set.of("p", "tbl").contains(element.getLocalName())).findFirst().orElse(null);
            }
            if (paragraph == null || start.getParentNode() != paragraph) { return false; }
            for (var node : children(paragraph)) {
                if (node == start) { return true; }
                if (!Set.of("pPr", "bookmarkStart", "bookmarkEnd", "proofErr").contains(node.getLocalName())) { return false; }
            }
            return false;
        }

        private boolean numbered(Anchor anchor) {
            if (!anchor.blockId().equals(anchor.endBlockId()) || !anchor.block().getLocalName().equals("p")) { return false; }
            var properties = child(anchor.block(), "pPr");
            var number = child(properties, "numPr");
            var id = child(number, "numId");
            var level = child(number, "ilvl");
            var definition = id == null ? null : numbers.get(id.getAttributeNS(W, "val") + ":" + (level == null ? "0" : level.getAttributeNS(W, "val")));
            var format = child(definition, "numFmt");
            return definition != null && (format == null || format.getAttributeNS(W, "val").equals("decimal"))
                    && child(properties, "pStyle") == null && !anchor.block().hasAttributeNS(OD, "marker")
                    && anchor.block().getElementsByTagNameNS(W, "drawing").getLength() == 0
                    && anchor.block().getElementsByTagNameNS(W, "fldSimple").getLength() == 0
                    && anchor.block().getElementsByTagNameNS(W, "fldChar").getLength() == 0;
        }

        private List<InlineContent> reduced(List<InlineContent> cache, String reason) throws IOException {
            loss(policy, diagnostics, "word/document.xml", "field", reason);
            return cache;
        }
    }
}
