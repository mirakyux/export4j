// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import static com.mirakyux.export4j.adapter.docx.DocxAdapter.children;
import static com.mirakyux.export4j.adapter.docx.DocxMathXml.M;
import static com.mirakyux.export4j.adapter.docx.DocxMathXml.math;
import static com.mirakyux.export4j.adapter.docx.DocxMathXml.property;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.OD;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.W;
import com.mirakyux.export4j.model.ExecutionContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Transient standard-package bridge. Only the typed semantic document and AST survive import. */
final class DocxMathPackage {
    private DocxMathPackage() { }
    static DocxAdapter.MathReadResult read(DocxAdapter adapter, DocxLimits limits, InputStream input,
            DocxAdapter.Policy policy, DocxMathLimits mathLimits) throws IOException {
        try {
            var parts = adapter.unzip(input); var roots = DocxPackageEditor.validateSemanticPackage(parts);
            var settings = readSettings(roots.get("word/settings.xml"));
            var reader = new DocxMathXml.Reader(mathLimits, settings);
            var equations = new ArrayList<DocxMathDocument.Equation>(); var usedIds = new HashSet<String>(); var equationIds = new HashSet<String>();
            for (var root : roots.values()) {
                for (var paragraph : DocxSemanticXml.all(root, W, "p")) { String id = paragraph.getAttributeNS(OD, "id"); if (!id.isEmpty()) { usedIds.add(id); } }
                for (var table : DocxSemanticXml.all(root, W, "tbl")) { String id = table.getAttributeNS(OD, "id"); if (!id.isEmpty()) { usedIds.add(id); } }
                for (var formula : DocxSemanticXml.all(root, M, "oMath")) {
                    String id = formula.getAttributeNS(OD, "id"); if (!id.isEmpty() && !equationIds.add(id)) { throw new IOException("duplicate native equation ID"); }
                }
            }
            var reservedEquationIds = new HashSet<>(equationIds); equationIds.clear(); int paragraphCounter = 0; int equationCounter = 0;
            for (var entry : roots.entrySet()) {
                var root = entry.getValue();
                var candidates = new ArrayList<Element>();
                for (var formula : DocxSemanticXml.all(root, M, "oMath")) {
                    if (formula.getParentNode() instanceof Element parent && DocxMathXml.is(parent, "oMathPara")) { if (!candidates.contains(parent)) { candidates.add(parent); } }
                    else { candidates.add(formula); }
                }
                for (var candidate : candidates) {
                    ExecutionContext.checkpoint();
                    if (++equationCounter > mathLimits.maxEquations()) { throw new IOException("native equation count budget exceeded"); }
                    if (!(candidate.getParentNode() instanceof Element paragraph) || !DocxSemanticXml.is(paragraph, "p")) { throw new IOException("OMML requires a direct ordinary paragraph anchor"); }
                    var placement = DocxMathDocument.Placement.INLINE; Element formula = candidate;
                    if (DocxMathXml.is(candidate, "oMathPara")) {
                        DocxMathXml.check(candidate, Set.of()); var values = children(candidate); Element pr = null; int offset = 0;
                        if (!values.isEmpty() && DocxMathXml.is(values.getFirst(), "oMathParaPr")) { pr = values.getFirst(); offset++; }
                        if (values.size() - offset != 1 || !DocxMathXml.is(values.get(offset), "oMath")) { throw DocxMathXml.invalid(); }
                        var properties = DocxMathXml.properties(pr, Set.of("jc"));
                        placement = placement(DocxMathXml.value(properties.get("jc"), justification(settings.defaultJustification()))); formula = values.get(offset);
                    }
                    DocxMathXml.check(formula, Set.of(OD + "|id")); String id = formula.getAttributeNS(OD, "id");
                    if (id.isEmpty()) { int number = equations.size(); do { id = "math-" + number++; } while (reservedEquationIds.contains(id) || equationIds.contains(id)); }
                    if (!equationIds.add(id)) { throw new IOException("duplicate native equation ID"); }
                    formula.removeAttributeNS(OD, "id");
                    var expression = reader.expression(formula, 1);
                    String paragraphId = paragraph.getAttributeNS(OD, "id");
                    if (paragraphId.isEmpty()) { do { paragraphId = "math-p-" + paragraphCounter++; } while (!usedIds.add(paragraphId)); paragraph.setAttributeNS(OD, "od:id", paragraphId); }
                    int offset = 0;
                    for (Node node = paragraph.getFirstChild(); node != candidate; node = node.getNextSibling()) {
                        if (node == null) { throw DocxMathXml.invalid(); } if (node instanceof Element element) { offset = Math.addExact(offset, DocxSemanticXml.length(element)); }
                    }
                    equations.add(new DocxMathDocument.Equation(id, new DocxSemanticDocument.Point(paragraphId, offset), placement, expression));
                    paragraph.replaceChild(DocxSemanticXml.textRun(paragraph, "\ufffc"), candidate);
                }
                if (M.equals(root.getNamespaceURI()) || root.getElementsByTagNameNS(M, "*").getLength() != 0) { throw new IOException("unrepresented OMML content is unsupported"); }
                parts.put(entry.getKey(), DocxSemanticXml.encode(root, limits.maxEntryBytes()));
            }
            var imported = adapter.readSemantic(new ByteArrayInputStream(DocxSemanticXml.zip(parts, limits)), policy);
            equations = reanchor(imported.document(), equations);
            var document = new DocxMathDocument(imported.document(), equations, settings);
            check(document, limits, mathLimits);
            return new DocxAdapter.MathReadResult(document, imported.diagnostics());
        } catch (IllegalArgumentException | ArithmeticException failure) { throw new IOException("invalid native Word mathematics", failure); }
    }
    private static ArrayList<DocxMathDocument.Equation> reanchor(DocxSemanticDocument semantic,
            List<DocxMathDocument.Equation> source) throws IOException {
        var paragraphs = DocxSemanticSupport.paragraphs(semantic.body(), semantic.drawings());
        for (var comment : semantic.comments()) { DocxSemanticSupport.collect(comment.blocks(), paragraphs); }
        var grouped = new LinkedHashMap<String, List<DocxMathDocument.Equation>>();
        for (var equation : source) { grouped.computeIfAbsent(equation.anchor().paragraphId(), ignored -> new ArrayList<>()).add(equation); }
        var result = new ArrayList<DocxMathDocument.Equation>();
        for (var entry : grouped.entrySet()) {
            var paragraph = paragraphs.get(entry.getKey()); if (paragraph == null) { throw new IOException("mathematical paragraph was not retained by semantic import"); }
            var reserved = new HashSet<Integer>(); for (var image : paragraph.images()) { reserved.add(image.sourceIndex()); } for (var field : paragraph.fields()) { reserved.add(field.sourceIndex()); }
            for (var vector : paragraph.vectors()) { reserved.add(vector.sourceIndex()); }
            int index = 0;
            for (int offset = 0; offset < paragraph.text().length(); offset++) {
                ExecutionContext.checkpoint();
                if (paragraph.text().charAt(offset) != '\ufffc' || reserved.contains(offset)) { continue; }
                if (index == entry.getValue().size()) { throw new IOException("ambiguous unbound mathematical marker"); }
                var equation = entry.getValue().get(index++);
                result.add(new DocxMathDocument.Equation(equation.id(), new DocxSemanticDocument.Point(paragraph.id(), offset), equation.placement(), equation.expression()));
            }
            if (index != entry.getValue().size()) { throw new IOException("mathematical marker lost during semantic import"); }
        }
        return result;
    }
    static byte[] write(DocxAdapter adapter, DocxLimits limits, DocxMathDocument document, DocxMathLimits mathLimits) throws IOException {
        check(document, limits, mathLimits);
        var encoded = DocxSemanticWriter.write(adapter, limits, document.semantic());
        var parts = adapter.unzip(new ByteArrayInputStream(encoded)); var roots = DocxPackageEditor.validateSemanticPackage(parts);
        var paragraphs = new HashMap<String, Element>();
        for (var root : roots.values()) {
            for (var paragraph : DocxSemanticXml.all(root, W, "p")) {
                String id = paragraph.getAttributeNS(OD, "id"); if (!id.isEmpty() && paragraphs.putIfAbsent(id, paragraph) != null) { throw new IOException("duplicate equation paragraph ID"); }
            }
        }
        var ordered = new ArrayList<>(document.equations()); ordered.sort(Comparator.comparing((DocxMathDocument.Equation equation) -> equation.anchor().paragraphId()).thenComparing(Comparator.comparingInt((DocxMathDocument.Equation equation) -> equation.anchor().utf16()).reversed()));
        for (var equation : ordered) {
            ExecutionContext.checkpoint(); var paragraph = paragraphs.get(equation.anchor().paragraphId()); if (paragraph == null) { throw new IOException("equation paragraph was not retained by semantic output"); }
            DocxSemanticXml.boundary(paragraph, equation.anchor().utf16() + 1); Node start = DocxSemanticXml.boundary(paragraph, equation.anchor().utf16());
            Element marker = null;
            for (Node current = start; current != null; current = current.getNextSibling()) {
                if (!(current instanceof Element candidate)) { continue; } int length = DocxSemanticXml.length(candidate);
                if (length == 0) { continue; }
                if (!DocxSemanticXml.is(candidate, "r") || length != 1 || !candidate.getTextContent().equals("\ufffc")) { throw new IOException("equation marker is not a plain atomic run"); }
                marker = candidate; break;
            }
            if (marker == null) { throw new IOException("equation marker missing"); }
            var formula = math(paragraph, "oMath"); formula.setAttributeNS(OD, "od:id", equation.id()); DocxMathXml.append(formula, equation.expression()); Element output = formula;
            if (equation.placement() != DocxMathDocument.Placement.INLINE) {
                var display = math(paragraph, "oMathPara"); var pr = math(paragraph, "oMathParaPr"); pr.appendChild(property(paragraph, "jc", justification(equation.placement()))); display.appendChild(pr); display.appendChild(formula); output = display;
            }
            paragraph.replaceChild(output, marker);
        }
        writeSettings(roots.get("word/settings.xml"), document.settings());
        for (var entry : roots.entrySet()) { parts.put(entry.getKey(), DocxSemanticXml.encode(entry.getValue(), limits.maxEntryBytes())); }
        var result = DocxSemanticXml.zip(parts, limits); DocxPackageEditor.validateSemanticPackage(adapter.unzip(new ByteArrayInputStream(result))); return result;
    }
    private static void check(DocxMathDocument document, DocxLimits limits, DocxMathLimits mathLimits) throws IOException {
        try { DocxMathSupport.validate(document.equations(), mathLimits); }
        catch (IllegalArgumentException failure) { throw new IOException("native mathematics budget exceeded", failure); }
        DocxSemanticSupport.budget(document.semantic(), limits, document.equations().stream().map(DocxMathDocument.Equation::expression).toList());
    }
    private static DocxMathDocument.Placement placement(String value) throws IOException {
        return switch (value) { case "left" -> DocxMathDocument.Placement.DISPLAY_LEFT; case "center" -> DocxMathDocument.Placement.DISPLAY_CENTER; case "right" -> DocxMathDocument.Placement.DISPLAY_RIGHT; case "centerGroup" -> DocxMathDocument.Placement.DISPLAY_CENTER_GROUP; default -> throw DocxMathXml.invalid(); };
    }
    private static String justification(DocxMathDocument.Placement value) {
        return switch (value) { case DISPLAY_LEFT -> "left"; case DISPLAY_CENTER -> "center"; case DISPLAY_RIGHT -> "right"; case DISPLAY_CENTER_GROUP -> "centerGroup"; case INLINE -> throw new IllegalArgumentException("inline equation has no display justification"); };
    }
    private static String justification(DocxMathSettings.Justification value) {
        return switch (value) { case LEFT -> "left"; case CENTER -> "center"; case RIGHT -> "right"; case CENTER_GROUP -> "centerGroup"; };
    }
    private static DocxMathSettings readSettings(Element root) throws IOException {
        if (root == null) { return DocxMathSettings.DEFAULT; }
        Element mathPr = null;
        for (var child : children(root)) { if (DocxMathXml.is(child, "mathPr")) { if (mathPr != null) { throw DocxMathXml.invalid(); } mathPr = child; } }
        if (mathPr == null) { return DocxMathSettings.DEFAULT; }
        var properties = DocxMathXml.properties(mathPr, Set.of("mathFont", "brkBin", "brkBinSub", "smallFrac", "dispDef", "lMargin", "rMargin", "defJc", "preSp", "postSp", "interSp", "intraSp", "wrapIndent", "wrapRight", "intLim", "naryLim"));
        var defaults = DocxMathSettings.DEFAULT;
        var binary = switch (DocxMathXml.value(properties.get("brkBin"), "before")) { case "before" -> DocxMathSettings.BinaryBreak.BEFORE; case "after" -> DocxMathSettings.BinaryBreak.AFTER; case "repeat" -> DocxMathSettings.BinaryBreak.REPEAT; default -> throw DocxMathXml.invalid(); };
        var subtraction = switch (DocxMathXml.value(properties.get("brkBinSub"), "--")) { case "--" -> DocxMathSettings.BinarySubtraction.MINUS_MINUS; case "+-" -> DocxMathSettings.BinarySubtraction.PLUS_MINUS; case "-+" -> DocxMathSettings.BinarySubtraction.MINUS_PLUS; default -> throw DocxMathXml.invalid(); };
        var jc = switch (DocxMathXml.value(properties.get("defJc"), "centerGroup")) { case "left" -> DocxMathSettings.Justification.LEFT; case "center" -> DocxMathSettings.Justification.CENTER; case "right" -> DocxMathSettings.Justification.RIGHT; case "centerGroup" -> DocxMathSettings.Justification.CENTER_GROUP; default -> throw DocxMathXml.invalid(); };
        var result = new DocxMathSettings(DocxMathXml.value(properties.get("mathFont"), defaults.fontFamily()), binary, subtraction,
                DocxMathXml.on(properties.get("smallFrac"), defaults.smallFractions()), DocxMathXml.on(properties.get("dispDef"), defaults.displayDefault()),
                spacing(properties, "lMargin", defaults.leftMargin()), spacing(properties, "rMargin", defaults.rightMargin()), jc,
                spacing(properties, "preSp", defaults.preSpacing()), spacing(properties, "postSp", defaults.postSpacing()), spacing(properties, "interSp", defaults.interSpacing()), spacing(properties, "intraSp", defaults.intraSpacing()),
                spacing(properties, "wrapIndent", defaults.wrapIndent()), DocxMathXml.on(properties.get("wrapRight"), defaults.wrapRight()),
                DocxMathXml.location(DocxMathXml.value(properties.get("intLim"), DocxMathXml.location(defaults.integralLimits()))),
                DocxMathXml.location(DocxMathXml.value(properties.get("naryLim"), DocxMathXml.location(defaults.naryLimits()))));
        root.removeChild(mathPr); return result;
    }
    private static int spacing(Map<String, Element> properties, String key, int fallback) throws IOException { return DocxMathXml.number(DocxMathXml.value(properties.get(key), Integer.toString(fallback))); }
    private static void writeSettings(Element root, DocxMathSettings settings) throws IOException {
        if (root == null) { throw new IOException("semantic writer omitted native settings part"); }
        var mathPr = math(root, "mathPr");
        var values = new LinkedHashMap<String, String>(); values.put("mathFont", settings.fontFamily()); values.put("brkBin", settings.binaryBreak().name().toLowerCase(java.util.Locale.ROOT));
        values.put("brkBinSub", switch (settings.binarySubtraction()) { case MINUS_MINUS -> "--"; case PLUS_MINUS -> "+-"; case MINUS_PLUS -> "-+"; });
        values.put("smallFrac", settings.smallFractions() ? "1" : "0"); values.put("dispDef", settings.displayDefault() ? "1" : "0");
        values.put("lMargin", Integer.toString(settings.leftMargin())); values.put("rMargin", Integer.toString(settings.rightMargin())); values.put("defJc", justification(settings.defaultJustification()));
        values.put("preSp", Integer.toString(settings.preSpacing())); values.put("postSp", Integer.toString(settings.postSpacing())); values.put("interSp", Integer.toString(settings.interSpacing())); values.put("intraSp", Integer.toString(settings.intraSpacing()));
        values.put("wrapIndent", Integer.toString(settings.wrapIndent())); values.put("wrapRight", settings.wrapRight() ? "1" : "0"); values.put("intLim", DocxMathXml.location(settings.integralLimits())); values.put("naryLim", DocxMathXml.location(settings.naryLimits()));
        for (var entry : values.entrySet()) { mathPr.appendChild(property(root, entry.getKey(), entry.getValue())); } root.appendChild(mathPr);
    }
}
