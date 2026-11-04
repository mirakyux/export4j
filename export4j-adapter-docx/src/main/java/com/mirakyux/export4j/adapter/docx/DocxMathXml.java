// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import static com.mirakyux.export4j.adapter.docx.DocxAdapter.children;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.W;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.element;
import com.mirakyux.export4j.model.ExecutionContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.xml.XMLConstants;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Strict, typed OMML codec. Transient DOM nodes never escape the adapter. */
final class DocxMathXml {
    static final String M = "http://schemas.openxmlformats.org/officeDocument/2006/math";
    private static final Set<String> SETTINGS = Set.of("mathFont", "brkBin", "brkBinSub", "smallFrac", "dispDef",
            "lMargin", "rMargin", "defJc", "preSp", "postSp", "interSp", "intraSp", "wrapIndent", "wrapRight", "intLim", "naryLim");
    private DocxMathXml() { }
    /** Runs before styles or side stories are materialized, so no ordinary reader can discard math. */
    static void rejectNativeContent(Element root, String part) throws IOException {
        if (M.equals(root.getNamespaceURI())) { throw requiresMath(); }
        var nodes = root.getElementsByTagNameNS(M, "*");
        for (int index = 0; index < nodes.getLength(); index++) {
            ExecutionContext.checkpoint(); var current = (Element) nodes.item(index);
            if (!part.equals("word/settings.xml") || !DocxSemanticXml.is(root, "settings")) { throw requiresMath(); }
            if (is(current, "mathPr") && current.getParentNode() == root) { continue; }
            if (SETTINGS.contains(current.getLocalName()) && current.getParentNode() instanceof Element parent
                    && is(parent, "mathPr") && parent.getParentNode() == root && children(current).isEmpty()) { continue; }
            throw requiresMath();
        }
    }
    private static IOException requiresMath() { return new IOException("native OMML mathematics requires readMath; ordinary import cannot discard equations"); }
    static Element math(Node owner, String local) { return element(owner, M, "m:" + local); }
    static boolean is(Element element, String local) { return element != null && M.equals(element.getNamespaceURI()) && local.equals(element.getLocalName()); }
    static Element property(Node owner, String local, String value) {
        var result = math(owner, local); result.setAttributeNS(M, "m:val", value); return result;
    }
    static void append(Element owner, DocxMath expression) {
        if (expression instanceof DocxMath.Sequence sequence) { for (var item : sequence.elements()) { append(owner, item); } return; }
        Element result;
        switch (expression) {
            case DocxMath.Run run -> {
                result = math(owner, "r"); var pr = math(owner, "rPr");
                if (run.style() == DocxMath.Style.NORMAL_TEXT) { pr.appendChild(property(owner, "nor", "1")); }
                else { pr.appendChild(property(owner, "sty", switch (run.style()) { case ITALIC -> "i"; case PLAIN -> "p"; case BOLD -> "b"; case BOLD_ITALIC -> "bi"; default -> throw new IllegalArgumentException("invalid run style"); })); }
                result.appendChild(pr);
                if (run.fontSize() != 0 || run.rgb() != 0) {
                    var wordPr = DocxSemanticXml.word(owner, "rPr");
                    if (run.fontSize() != 0) { wordPr.appendChild(DocxSemanticXml.property(owner, "sz", Long.toString(Math.round(run.fontSize() * 2)))); }
                    if (run.rgb() != 0) { wordPr.appendChild(DocxSemanticXml.property(owner, "color", String.format(java.util.Locale.ROOT, "%06X", run.rgb()))); }
                    result.appendChild(wordPr);
                }
                var text = math(owner, "t"); text.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve"); text.setTextContent(run.text()); result.appendChild(text);
            }
            case DocxMath.Fraction fraction -> { result = math(owner, "f"); arg(result, "num", fraction.numerator()); arg(result, "den", fraction.denominator()); }
            case DocxMath.Subscript script -> { result = math(owner, "sSub"); arg(result, "e", script.base()); arg(result, "sub", script.subscript()); }
            case DocxMath.Superscript script -> { result = math(owner, "sSup"); arg(result, "e", script.base()); arg(result, "sup", script.superscript()); }
            case DocxMath.SubSuperscript script -> { result = math(owner, "sSubSup"); arg(result, "e", script.base()); arg(result, "sub", script.subscript()); arg(result, "sup", script.superscript()); }
            case DocxMath.Radical radical -> {
                result = math(owner, "rad"); var pr = math(owner, "radPr"); pr.appendChild(property(owner, "degHide", radical.degree().isEmpty() ? "1" : "0")); result.appendChild(pr);
                arg(result, "deg", radical.degree().orElseGet(() -> new DocxMath.Sequence(List.of()))); arg(result, "e", radical.radicand());
            }
            case DocxMath.Nary nary -> {
                result = math(owner, "nary"); var pr = math(owner, "naryPr");
                pr.appendChild(property(owner, "chr", operator(nary.operator()))); pr.appendChild(property(owner, "limLoc", location(nary.limits())));
                pr.appendChild(property(owner, "grow", nary.grow() ? "1" : "0")); pr.appendChild(property(owner, "subHide", nary.lower().isEmpty() ? "1" : "0")); pr.appendChild(property(owner, "supHide", nary.upper().isEmpty() ? "1" : "0")); result.appendChild(pr);
                arg(result, "sub", nary.lower().orElseGet(() -> new DocxMath.Sequence(List.of()))); arg(result, "sup", nary.upper().orElseGet(() -> new DocxMath.Sequence(List.of()))); arg(result, "e", nary.operand());
            }
            case DocxMath.Matrix matrix -> {
                result = math(owner, "m"); var pr = math(owner, "mPr"); pr.appendChild(property(owner, "baseJc", switch (matrix.baseline()) { case TOP -> "top"; case CENTER -> "center"; case BOTTOM -> "bot"; }));
                var columns = math(owner, "mcs");
                for (var alignment : matrix.columns()) { var column = math(owner, "mc"); var cp = math(owner, "mcPr"); cp.appendChild(property(owner, "count", "1")); cp.appendChild(property(owner, "mcJc", alignment.name().toLowerCase(java.util.Locale.ROOT))); column.appendChild(cp); columns.appendChild(column); }
                pr.appendChild(columns); result.appendChild(pr);
                for (var row : matrix.rows()) { var mr = math(owner, "mr"); for (var value : row) { arg(mr, "e", value); } result.appendChild(mr); }
            }
            case DocxMath.Delimiter delimiter -> {
                result = math(owner, "d"); var pr = math(owner, "dPr");
                pr.appendChild(property(owner, "begChr", delimiter.opening())); pr.appendChild(property(owner, "sepChr", delimiter.separator())); pr.appendChild(property(owner, "endChr", delimiter.closing()));
                pr.appendChild(property(owner, "grow", delimiter.grow() ? "1" : "0")); pr.appendChild(property(owner, "shp", delimiter.shape() == DocxMath.DelimiterShape.CENTERED ? "centered" : "match")); result.appendChild(pr);
                for (var value : delimiter.items()) { arg(result, "e", value); }
            }
            case DocxMath.Function function -> { result = math(owner, "func"); arg(result, "fName", function.name()); arg(result, "e", function.argument()); }
            case DocxMath.LowerLimit limit -> { result = math(owner, "limLow"); arg(result, "e", limit.base()); arg(result, "lim", limit.limit()); }
            case DocxMath.UpperLimit limit -> { result = math(owner, "limUpp"); arg(result, "e", limit.base()); arg(result, "lim", limit.limit()); }
            case DocxMath.Sequence ignored -> throw new IllegalArgumentException("sequence already handled");
        }
        owner.appendChild(result);
    }
    private static void arg(Element owner, String name, DocxMath value) { var argument = math(owner, name); append(argument, value); owner.appendChild(argument); }
    static String location(DocxMath.LimitLocation value) { return value == DocxMath.LimitLocation.SUB_SUP ? "subSup" : "undOvr"; }
    static DocxMath.LimitLocation location(String value) throws IOException { return switch (value) { case "subSup" -> DocxMath.LimitLocation.SUB_SUP; case "undOvr" -> DocxMath.LimitLocation.UNDER_OVER; default -> throw invalid(); }; }
    private static String operator(DocxMath.Operator value) { return switch (value) { case INTEGRAL -> "∫"; case DOUBLE_INTEGRAL -> "∬"; case CONTOUR_INTEGRAL -> "∮"; case SUM -> "∑"; case PRODUCT -> "∏"; }; }
    private static DocxMath.Operator operator(String value) throws IOException { return switch (value) { case "∫" -> DocxMath.Operator.INTEGRAL; case "∬" -> DocxMath.Operator.DOUBLE_INTEGRAL; case "∮" -> DocxMath.Operator.CONTOUR_INTEGRAL; case "∑" -> DocxMath.Operator.SUM; case "∏" -> DocxMath.Operator.PRODUCT; default -> throw invalid(); }; }

    static final class Reader {
        private final DocxMathLimits limits; private final DocxMathSettings settings;
        private int nodes; private long points; private long cells;
        Reader(DocxMathLimits limits, DocxMathSettings settings) { this.limits = limits; this.settings = settings; }
        private Map<String, Element> properties(Element source, Set<String> allowed) throws IOException {
            return DocxMathXml.properties(source, allowed, settings.fontFamily());
        }
        DocxMath expression(Element container, int depth) throws IOException {
            check(container, Set.of()); return sequence(container, depth);
        }
        private DocxMath sequence(Element container, int depth) throws IOException {
            var values = new ArrayList<DocxMath>();
            for (var child : children(container)) { values.add(read(child, depth)); }
            if (values.size() == 1) { return values.getFirst(); }
            reserve(depth); return new DocxMath.Sequence(values);
        }
        private void reserve(int depth) throws IOException {
            ExecutionContext.checkpoint(); if (++nodes > limits.maxNodes() || depth > limits.maxDepth()) { throw new IOException("OMML node/depth budget exceeded"); }
        }
        private DocxMath read(Element source, int depth) throws IOException {
            reserve(depth); check(source, Set.of()); if (!M.equals(source.getNamespaceURI())) { throw invalid(); }
            String name = source.getLocalName();
            if (name.equals("r")) { return run(source); }
            if (name.equals("m")) { return matrix(source, depth); }
            if (name.equals("d")) { return delimiter(source, depth); }
            String pr = switch (name) { case "f" -> "fPr"; case "sSub" -> "sSubPr"; case "sSup" -> "sSupPr"; case "sSubSup" -> "sSubSupPr"; case "rad" -> "radPr"; case "nary" -> "naryPr"; case "func" -> "funcPr"; case "limLow" -> "limLowPr"; case "limUpp" -> "limUppPr"; default -> throw invalid(); };
            List<String> names = switch (name) { case "f" -> List.of("num", "den"); case "sSub" -> List.of("e", "sub"); case "sSup" -> List.of("e", "sup"); case "sSubSup" -> List.of("e", "sub", "sup"); case "rad" -> List.of("deg", "e"); case "nary" -> List.of("sub", "sup", "e"); case "func" -> List.of("fName", "e"); default -> List.of("e", "lim"); };
            var content = structure(source, pr, names); var properties = properties(content.properties(), controls(switch (name) { case "f" -> Set.of("type"); case "rad" -> Set.of("degHide"); case "nary" -> Set.of("chr", "limLoc", "grow", "subHide", "supHide"); case "sSubSup" -> Set.of("alnScr"); default -> Set.of(); }));
            if (name.equals("rad")) {
                var degree = optional(content.arguments().get(0), depth + 1, on(properties.get("degHide"), false));
                return new DocxMath.Radical(expression(content.arguments().get(1), depth + 1), degree);
            }
            if (name.equals("nary")) {
                var operator = operator(value(properties.get("chr"), "∫"));
                var defaultLocation = Set.of(DocxMath.Operator.INTEGRAL, DocxMath.Operator.DOUBLE_INTEGRAL, DocxMath.Operator.CONTOUR_INTEGRAL).contains(operator) ? settings.integralLimits() : settings.naryLimits();
                var lower = optional(content.arguments().get(0), depth + 1, on(properties.get("subHide"), false));
                var upper = optional(content.arguments().get(1), depth + 1, on(properties.get("supHide"), false));
                return new DocxMath.Nary(operator, lower, upper, expression(content.arguments().get(2), depth + 1),
                        location(value(properties.get("limLoc"), location(defaultLocation))), on(properties.get("grow"), false));
            }
            var arguments = new ArrayList<DocxMath>();
            for (var argument : content.arguments()) { arguments.add(expression(argument, depth + 1)); }
            return switch (name) {
                case "f" -> { if (!value(properties.get("type"), "bar").equals("bar")) { throw invalid(); } yield new DocxMath.Fraction(arguments.get(0), arguments.get(1)); }
                case "sSub" -> new DocxMath.Subscript(arguments.get(0), arguments.get(1));
                case "sSup" -> new DocxMath.Superscript(arguments.get(0), arguments.get(1));
                case "sSubSup" -> { if (on(properties.get("alnScr"), false)) { throw invalid(); } yield new DocxMath.SubSuperscript(arguments.get(0), arguments.get(1), arguments.get(2)); }
                case "func" -> new DocxMath.Function(arguments.get(0), arguments.get(1));
                case "limLow" -> new DocxMath.LowerLimit(arguments.get(0), arguments.get(1));
                case "limUpp" -> new DocxMath.UpperLimit(arguments.get(0), arguments.get(1));
                default -> throw invalid();
            };
        }
        private Optional<DocxMath> optional(Element container, int depth, boolean hidden) throws IOException {
            if (!hidden) { return Optional.of(expression(container, depth)); }
            check(container, Set.of());
            if (!children(container).isEmpty()) { throw new IOException("hidden OMML content cannot be discarded"); }
            // An absent optional AST child has no node/depth cost; its XML still passes package limits.
            return Optional.empty();
        }
        private DocxMath run(Element source) throws IOException {
            Element mathPr = null; Element wordPr = null; Element text = null; int phase = 0;
            for (var child : children(source)) {
                if (is(child, "rPr") && phase == 0) { mathPr = child; phase = 1; }
                else if (DocxSemanticXml.is(child, "rPr") && phase <= 1) { wordPr = child; phase = 2; }
                else if (is(child, "t") && phase <= 2) { text = child; phase = 3; }
                else { throw invalid(); }
            }
            if (text == null) { throw invalid(); }
            check(text, Set.of(XMLConstants.XML_NS_URI + "|space")); if (!children(text).isEmpty()) { throw invalid(); }
            if (text.hasAttributeNS(XMLConstants.XML_NS_URI, "space") && !Set.of("default", "preserve").contains(text.getAttributeNS(XMLConstants.XML_NS_URI, "space"))) { throw invalid(); }
            var properties = properties(mathPr, Set.of("sty", "nor"));
            var style = switch (value(properties.get("sty"), "i")) { case "i" -> DocxMath.Style.ITALIC; case "p" -> DocxMath.Style.PLAIN; case "b" -> DocxMath.Style.BOLD; case "bi" -> DocxMath.Style.BOLD_ITALIC; default -> throw invalid(); };
            if (on(properties.get("nor"), false)) { if (properties.containsKey("sty")) { throw invalid(); } style = DocxMath.Style.NORMAL_TEXT; }
            double size = 0; int color = 0; String complexSize = null;
            if (wordPr != null) {
                check(wordPr, Set.of()); var seen = new java.util.HashSet<String>();
                for (var property : children(wordPr)) {
                    if (!W.equals(property.getNamespaceURI()) || !seen.add(property.getLocalName())) { throw invalid(); }
                    switch (property.getLocalName()) {
                        case "sz" -> size = wordNumber(property) / 2.0;
                        case "szCs" -> complexSize = Integer.toString(wordNumber(property));
                        case "color" -> { String rgb = wordValue(property); if (!rgb.equals("auto") && !rgb.matches("[0-9A-Fa-f]{6}")) { throw invalid(); } color = rgb.equals("auto") ? 0 : Integer.parseInt(rgb, 16); }
                        case "rFonts" -> {
                            defaultFont(property, settings.fontFamily());
                        }
                        default -> throw invalid();
                    }
                }
                if (complexSize != null && !complexSize.equals(Integer.toString((int) (size * 2)))) { throw invalid(); }
            }
            String content = text.getTextContent(); points += content.codePointCount(0, content.length());
            if (points > limits.maxTextCodePoints()) { throw new IOException("OMML text budget exceeded"); }
            return new DocxMath.Run(content, style, size, color);
        }
        private DocxMath matrix(Element source, int depth) throws IOException {
            var parts = repeated(source, "mPr", "mr"); var properties = properties(parts.properties(), controls(Set.of("baseJc", "mcs")));
            var rows = new ArrayList<List<DocxMath>>(); int width = -1;
            for (var row : parts.arguments()) {
                check(row, Set.of()); var values = new ArrayList<DocxMath>();
                for (var cell : children(row)) { if (!is(cell, "e")) { throw invalid(); } if (++cells > limits.maxMatrixCells()) { throw new IOException("OMML matrix cell budget exceeded"); } values.add(expression(cell, depth + 1)); }
                if (values.isEmpty() || width >= 0 && values.size() != width) { throw invalid(); } width = values.size(); rows.add(List.copyOf(values));
                if (rows.size() > 1024 || width > 1024) { throw invalid(); }
            }
            if (rows.isEmpty()) { throw invalid(); }
            var columns = new ArrayList<DocxMath.ColumnAlignment>(); var mcs = properties.get("mcs");
            if (mcs != null) {
                check(mcs, Set.of());
                for (var column : children(mcs)) {
                    if (!is(column, "mc")) { throw invalid(); } check(column, Set.of());
                    var cp = children(column); if (cp.size() != 1 || !is(cp.getFirst(), "mcPr")) { throw invalid(); }
                    var values = properties(cp.getFirst(), Set.of("count", "mcJc")); int count = number(value(values.get("count"), "1"));
                    var alignment = switch (value(values.get("mcJc"), "center")) { case "left" -> DocxMath.ColumnAlignment.LEFT; case "center" -> DocxMath.ColumnAlignment.CENTER; case "right" -> DocxMath.ColumnAlignment.RIGHT; default -> throw invalid(); };
                    if (count < 1 || count > width - columns.size()) { throw invalid(); } for (int index = 0; index < count; index++) { columns.add(alignment); }
                }
                if (columns.size() != width) { throw invalid(); }
            } else { for (int index = 0; index < width; index++) { columns.add(DocxMath.ColumnAlignment.CENTER); } }
            var baseline = switch (value(properties.get("baseJc"), "center")) { case "top" -> DocxMath.BaseAlignment.TOP; case "center" -> DocxMath.BaseAlignment.CENTER; case "bot" -> DocxMath.BaseAlignment.BOTTOM; default -> throw invalid(); };
            return new DocxMath.Matrix(rows, columns, baseline);
        }
        private DocxMath delimiter(Element source, int depth) throws IOException {
            var parts = repeated(source, "dPr", "e"); if (parts.arguments().isEmpty() || parts.arguments().size() > 1024) { throw invalid(); }
            var properties = properties(parts.properties(), controls(Set.of("begChr", "endChr", "sepChr", "grow", "shp"))); var values = new ArrayList<DocxMath>();
            for (var item : parts.arguments()) { values.add(expression(item, depth + 1)); }
            var shape = switch (value(properties.get("shp"), "centered")) { case "centered" -> DocxMath.DelimiterShape.CENTERED; case "match" -> DocxMath.DelimiterShape.MATCH; default -> throw invalid(); };
            return new DocxMath.Delimiter(value(properties.get("begChr"), "("), value(properties.get("endChr"), ")"), value(properties.get("sepChr"), "|"), values, on(properties.get("grow"), true), shape);
        }
    }
    private record Structure(Element properties, List<Element> arguments) { }
    private static Structure structure(Element source, String property, List<String> expected) throws IOException {
        var all = children(source); Element pr = null; int start = 0;
        if (!all.isEmpty() && is(all.getFirst(), property)) { pr = all.getFirst(); start++; }
        if (all.size() - start != expected.size()) { throw invalid(); }
        var arguments = new ArrayList<Element>();
        for (int index = 0; index < expected.size(); index++) { var item = all.get(start + index); if (!is(item, expected.get(index))) { throw invalid(); } arguments.add(item); }
        return new Structure(pr, arguments);
    }
    private static Structure repeated(Element source, String property, String argument) throws IOException {
        var all = children(source); Element pr = null; int start = 0;
        if (!all.isEmpty() && is(all.getFirst(), property)) { pr = all.getFirst(); start++; }
        var arguments = new ArrayList<Element>();
        for (int index = start; index < all.size(); index++) { var item = all.get(index); if (!is(item, argument)) { throw invalid(); } arguments.add(item); }
        return new Structure(pr, arguments);
    }
    static Map<String, Element> properties(Element source, Set<String> allowed) throws IOException {
        return properties(source, allowed, null);
    }
    private static Map<String, Element> properties(Element source, Set<String> allowed, String controlFont) throws IOException {
        var result = new HashMap<String, Element>(); if (source == null) { return result; } check(source, Set.of());
        for (var child : children(source)) {
            if (!M.equals(child.getNamespaceURI())) { throw invalid(); }
            if (is(child, "ctrlPr")) {
                if (!allowed.contains("ctrlPr")) { throw invalid(); }
                check(child, Set.of()); if (children(child).size() > 1) { throw invalid(); }
                for (var value : children(child)) {
                    if (!DocxSemanticXml.is(value, "rPr")) { throw invalid(); } check(value, Set.of());
                    var formatting = children(value);
                    // Word writes the document's explicit math font onto structural control glyphs.
                    // This is the inherited default, not a second font or unrepresented formatting.
                    if (!formatting.isEmpty()) {
                        if (controlFont == null || formatting.size() != 1 || !DocxSemanticXml.is(formatting.getFirst(), "rFonts")) { throw invalid(); }
                        defaultFont(formatting.getFirst(), controlFont);
                    }
                }
                if (result.putIfAbsent("ctrlPr", child) != null) { throw invalid(); } continue;
            }
            if (!allowed.contains(child.getLocalName()) || result.putIfAbsent(child.getLocalName(), child) != null) { throw invalid(); }
        }
        return result;
    }
    private static void defaultFont(Element property, String family) throws IOException {
        check(property, Set.of(W + "|ascii", W + "|hAnsi", W + "|eastAsia", W + "|cs"));
        if (!children(property).isEmpty()) { throw invalid(); }
        for (String attribute : List.of("ascii", "hAnsi", "eastAsia", "cs")) {
            if (property.hasAttributeNS(W, attribute) && !property.getAttributeNS(W, attribute).equals(family)) { throw invalid(); }
        }
    }
    private static Set<String> controls(Set<String> values) { var result = new java.util.HashSet<>(values); result.add("ctrlPr"); return Set.copyOf(result); }
    static String value(Element source, String fallback) throws IOException {
        if (source == null) { return fallback; } check(source, Set.of(M + "|val")); if (!children(source).isEmpty()) { throw invalid(); }
        if (!source.hasAttributeNS(M, "val")) { if (fallback == null) { throw invalid(); } return fallback; } return source.getAttributeNS(M, "val");
    }
    static boolean on(Element source, boolean fallback) throws IOException {
        if (source == null) { return fallback; }
        return switch (value(source, "1")) { case "true", "1", "on" -> true; case "false", "0", "off" -> false; default -> throw invalid(); };
    }
    static int number(String value) throws IOException {
        try { if (!value.matches("[0-9]{1,8}")) { throw invalid(); } return Integer.parseInt(value); } catch (NumberFormatException failure) { throw new IOException("invalid OMML integer", failure); }
    }
    private static String wordValue(Element source) throws IOException {
        check(source, Set.of(W + "|val")); if (!children(source).isEmpty() || !source.hasAttributeNS(W, "val")) { throw invalid(); } return source.getAttributeNS(W, "val");
    }
    private static int wordNumber(Element source) throws IOException { return number(wordValue(source)); }
    static void check(Element source, Set<String> attributes) throws IOException {
        ExecutionContext.checkpoint();
        var list = source.getAttributes();
        for (int index = 0; index < list.getLength(); index++) {
            var attribute = list.item(index); if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) { continue; }
            if (!attributes.contains(attribute.getNamespaceURI() + "|" + attribute.getLocalName())) { throw invalid(); }
        }
        for (Node node = source.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE || !is(source, "t")
                    && (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) && !node.getTextContent().isBlank()) { throw invalid(); }
        }
    }
    static IOException invalid() { return new IOException("unsupported or malformed native OMML mathematics"); }
}
