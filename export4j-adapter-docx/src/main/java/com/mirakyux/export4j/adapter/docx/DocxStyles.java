// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import com.mirakyux.export4j.model.ExecutionContext;
import static com.mirakyux.export4j.adapter.docx.DocxAdapter.child;
import static com.mirakyux.export4j.adapter.docx.DocxAdapter.children;
import static com.mirakyux.export4j.adapter.docx.DocxAdapter.loss;

/** Resolves a bounded style graph to concrete properties, before existing semantic readers run.
 * The source package is not modified; named-style identity/graph loss is explicit.
 */
final class DocxStyles {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String OD = "urn:com.mirakyux.export4j:semantic:1";
    private static final Set<String> P = Set.of("spacing", "ind", "jc", "keepLines", "keepNext", "pageBreakBefore", "outlineLvl", "numPr");
    private static final Set<String> R = Set.of("b", "i", "u", "strike", "sz", "color", "position", "rFonts");
    private static final Set<String> TOGGLES = Set.of("b", "i", "strike");
    private record Style(String id, String type, String parent, Element p, Element r) { }
    private record Resolved(Element p, Element r) { }
    private final Map<String, Style> styles = new LinkedHashMap<>();
    private final Map<String, Resolved> resolved = new HashMap<>();
    private final Map<String, DocxAdapter.FontRoles> fonts;
    private final DocxAdapter.Policy policy;
    private final List<DocxAdapter.Diagnostic> diagnostics;
    private final DocxLimits limits;
    private Element defaultP;
    private Element defaultR;
    private String defaultParagraph = "";
    private int paragraphs;
    private boolean hasDefaults;
    private long formattingWork;

    private DocxStyles(Map<String, DocxAdapter.FontRoles> fonts, DocxAdapter.Policy policy, List<DocxAdapter.Diagnostic> diagnostics, DocxLimits limits) {
        this.fonts = fonts; this.policy = policy; this.diagnostics = diagnostics; this.limits = limits;
    }
    static boolean materialize(Map<String, byte[]> parts, Map<String, DocxAdapter.FontRoles> fonts, DocxAdapter.Policy policy,
            List<DocxAdapter.Diagnostic> diagnostics, DocxLimits limits) throws IOException {
        if (!parts.containsKey("word/styles.xml") && fonts.isEmpty()) { return false; }
        var root = DocxAdapter.parse(parts.getOrDefault("word/styles.xml", ("<w:styles xmlns:w=\"" + W + "\"/>").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (!W.equals(root.getNamespaceURI()) || !root.getLocalName().equals("styles")) { throw new IOException("invalid DOCX styles root"); }
        // Empty project-owned role declarations and the original built-in styles retain their old path.
        boolean complex = children(root).stream().anyMatch(style -> !simple(style));
        if (!complex && fonts.isEmpty()) { return false; }
        var mapper = new DocxStyles(fonts, policy, diagnostics, limits); mapper.read(root);
        for (var style : mapper.styles.values()) { mapper.resolve(style.id(), new HashSet<>(), 0); }
        if (complex) { loss(policy, diagnostics, "word/styles.xml", "styleGraph", "named style graph is materialized into concrete semantic formatting"); }
        for (var entry : parts.entrySet()) {
            if (!entry.getKey().startsWith("word/") || !entry.getKey().endsWith(".xml") || entry.getKey().equals("word/styles.xml")) { continue; }
            var document = DocxAdapter.parse(entry.getValue());
            if (!W.equals(document.getNamespaceURI()) || !Set.of("document", "hdr", "ftr", "comments").contains(document.getLocalName())) { continue; }
            var paragraphs = document.getElementsByTagNameNS(W, "p");
            for (int index = 0; index < paragraphs.getLength(); index++) { mapper.paragraph((Element) paragraphs.item(index)); }
            entry.setValue(encode(document, limits.maxEntryBytes()));
        }
        long expanded = 0;
        for (byte[] part : parts.values()) {
            ExecutionContext.checkpoint();
            if (part.length > limits.maxExpandedBytes() - expanded) { throw new IOException("DOCX materialized package byte budget exceeded"); }
            expanded += part.length;
        }
        return true;
    }
    private static boolean simple(Element style) {
        if (!W.equals(style.getNamespaceURI()) || !style.getLocalName().equals("style")) { return false; }
        String id = style.getAttributeNS(W, "styleId");
        return children(style).stream().allMatch(property -> property.getLocalName().equals("name")
                || property.getLocalName().equals("pPr") && id.matches("Heading[1-6]") && children(property).stream().allMatch(p -> p.getLocalName().equals("outlineLvl"))
                || property.getLocalName().equals("rPr") && Set.of("bold", "italic", "boldItalic").contains(id)
                && children(property).stream().allMatch(p -> Set.of("b", "i").contains(p.getLocalName()) && Set.of("1", "true", "on").contains(value(p, "1"))));
    }
    private void read(Element root) throws IOException {
        for (var element : children(root)) {
            ExecutionContext.checkpoint();
            if (!W.equals(element.getNamespaceURI())) { loss(policy, diagnostics, "word/styles.xml", element.getLocalName(), "unsupported style namespace"); continue; }
            if (element.getLocalName().equals("docDefaults")) {
                if (hasDefaults) { throw new IOException("duplicate DOCX style defaults"); } hasDefaults = true;
                defaultP = properties(child(child(element, "pPrDefault"), "pPr"), false);
                defaultR = properties(child(child(element, "rPrDefault"), "rPr"), true);
                for (var node : children(element)) {
                    if (!Set.of("pPrDefault", "rPrDefault").contains(node.getLocalName())) { loss(policy, diagnostics, "word/styles.xml", node.getLocalName(), "unsupported style defaults"); }
                }
                continue;
            }
            if (!element.getLocalName().equals("style")) { loss(policy, diagnostics, "word/styles.xml", element.getLocalName(), "unsupported style metadata"); continue; }
            String id = element.getAttributeNS(W, "styleId"); String type = element.getAttributeNS(W, "type");
            if (type.isEmpty()) { type = "paragraph"; }
            if (id.isEmpty() || styles.size() >= limits.maxBlocks() || styles.containsKey(id)) { throw new IOException("DOCX style ID or graph budget invalid"); }
            if (!Set.of("paragraph", "character").contains(type)) { loss(policy, diagnostics, "word/styles.xml", "style", "unsupported style type"); continue; }
            Element p = properties(child(element, "pPr"), false); Element r = properties(child(element, "rPr"), true);
            String parent = value(child(element, "basedOn"), "");
            styles.put(id, new Style(id, type, parent, p, r));
            if (type.equals("paragraph") && enabledAttribute(element, "default")) {
                if (!defaultParagraph.isEmpty()) { throw new IOException("multiple default DOCX paragraph styles"); } defaultParagraph = id;
            }
            var seen = new HashSet<String>();
            for (var property : children(element)) {
                if (!seen.add(property.getLocalName())) { throw new IOException("duplicate DOCX style property"); }
                if (!Set.of("name", "basedOn", "pPr", "rPr", "next", "link", "qFormat", "uiPriority", "semiHidden", "unhideWhenUsed", "aliases", "rsid").contains(property.getLocalName())) {
                    loss(policy, diagnostics, "word/styles.xml", property.getLocalName(), "unsupported style property");
                }
            }
        }
    }
    private Element properties(Element source, boolean run) throws IOException {
        if (source == null) { return null; }
        reserve(estimate(source));
        var result = (Element) source.cloneNode(false); var seen = new HashSet<String>();
        for (var property : children(source)) {
            if (!seen.add(property.getLocalName())) { throw new IOException("duplicate DOCX formatting property"); }
            if (!W.equals(property.getNamespaceURI()) || !(run ? R : P).contains(property.getLocalName())) {
                loss(policy, diagnostics, "word/styles.xml", property.getLocalName(), "unsupported inherited formatting"); continue;
            }
            if (property.getLocalName().equals("rFonts")) {
                // Resolved later: one explicit family must map all four physical font roles.
                result.appendChild(property.cloneNode(true)); continue;
            }
            result.appendChild(property.cloneNode(true));
        }
        if (run) { DocxAdapter.inline(result, 12); }
        else {
            DocxAdapter.format(result);
            var outline = child(result, "outlineLvl");
            if (outline != null && !value(outline, "").matches("[0-9]")) { throw new IOException("invalid DOCX style outline level"); }
        }
        return result;
    }
    private Resolved resolve(String id, Set<String> visiting, int depth) throws IOException {
        ExecutionContext.checkpoint();
        if (resolved.containsKey(id)) { return resolved.get(id); }
        if (depth >= 64 || !visiting.add(id)) { throw new IOException("DOCX style inheritance cycle or depth budget"); }
        var source = styles.get(id); if (source == null) { throw new IOException("missing DOCX base style"); }
        Resolved parent = new Resolved(null, source.type().equals("paragraph") ? defaultR : null);
        if (!source.parent().isEmpty()) {
            var base = styles.get(source.parent());
            if (base == null || !base.type().equals(source.type())) { throw new IOException("missing or incompatible DOCX base style"); }
            parent = resolve(source.parent(), visiting, depth + 1);
        }
        var result = new Resolved(merge(parent.p(), source.p(), false), merge(parent.r(), source.r(), true));
        visiting.remove(id); resolved.put(id, result); return result;
    }
    private void paragraph(Element paragraph) throws IOException {
        ExecutionContext.checkpoint(); if (++paragraphs > limits.maxBlocks()) { throw new IOException("DOCX styled paragraph budget exceeded"); }
        // These verified project markers represent structure, not styled semantic paragraphs. Leave
        // their original nodes for the existing container validators; never normalize away defects.
        if (paragraph.hasAttributeNS(OD, "cellTail") || paragraph.hasAttributeNS(OD, "emptyBand")
                || "true".equals(paragraph.getAttributeNS(OD, "boundary"))) { return; }
        var directP = child(paragraph, "pPr"); String id = value(child(directP, "pStyle"), defaultParagraph);
        Resolved style = id.isEmpty() ? new Resolved(null, null) : resolved.get(id);
        if (style == null) { throw new IOException("missing DOCX paragraph style"); }
        if (!id.isEmpty() && !styles.get(id).type().equals("paragraph")) { throw new IOException("DOCX paragraph references non-paragraph style"); }
        Element p = merge(defaultP, style.p(), false); p = merge(p, directP, false);
        Element r = merge(defaultR, style.r(), false);
        if (paragraph.hasAttributeNS(OD, "id")) { r = merge(r, child(directP, "rPr"), false); }
        var target = paragraph.getOwnerDocument();
        if (p == null) { p = target.createElementNS(W, "w:pPr"); } else { p = (Element) target.importNode(p, true); }
        remove(p, "pStyle"); var outline = child(p, "outlineLvl");
        int heading = id.matches("(?i)Heading[1-6]") ? Integer.parseInt(id.substring(7)) : 0;
        if (outline != null) {
            try { int value = Integer.parseInt(value(outline, "9")); if (value < 0 || value > 9) { throw new NumberFormatException(); } heading = value < 6 ? value + 1 : 0; }
            catch (NumberFormatException failure) { throw new IOException("invalid DOCX outline level", failure); }
        }
        remove(p, "outlineLvl");
        if (heading > 0) { var name = target.createElementNS(W, "w:pStyle"); name.setAttributeNS(W, "w:val", "Heading" + heading); p.insertBefore(name, p.getFirstChild()); }
        // Existing geometry reader uses paragraph-mark size as the default line-box size.
        var mark = child(p, "rPr"); if (mark == null) { mark = target.createElementNS(W, "w:rPr"); p.appendChild(mark); }
        if (child(mark, "sz") == null && child(r, "sz") != null) { mark.appendChild(target.importNode(child(r, "sz"), true)); }
        if (directP != null) { paragraph.removeChild(directP); } paragraph.insertBefore(p, paragraph.getFirstChild());
        var runs = paragraph.getElementsByTagNameNS(W, "r");
        for (int index = 0; index < runs.getLength(); index++) {
            var run = (Element) runs.item(index); if (nearestParagraph(run) != paragraph) { continue; }
            var directR = child(run, "rPr"); String character = value(child(directR, "rStyle"), "");
            Element inherited = r;
            if (!character.isEmpty()) {
                var definition = styles.get(character);
                if (definition == null) { throw new IOException("missing DOCX character style"); }
                // Empty custom definitions are Export4j's explicit font-role declarations.
                if (definition.r() == null && definition.parent().isEmpty()) { inherited = merge(inherited, directR, false); }
                else {
                    if (!definition.type().equals("character") && !Set.of("bold", "italic", "boldItalic").contains(character)) { throw new IOException("DOCX run references incompatible style"); }
                    inherited = merge(inherited, resolved.get(character).r(), true);
                }
            }
            Element concrete = merge(inherited, directR, false);
            if (concrete == null) { continue; }
            if (!character.isEmpty() && styles.get(character).r() != null) { remove(concrete, "rStyle"); }
            applyFamily(concrete);
            if (directR != null) { run.removeChild(directR); } run.insertBefore(target.importNode(concrete, true), run.getFirstChild());
        }
    }
    private static Element nearestParagraph(Element run) {
        for (var node = run.getParentNode(); node instanceof Element parent; node = parent.getParentNode()) { if (W.equals(parent.getNamespaceURI()) && parent.getLocalName().equals("p")) { return parent; } } return null;
    }
    private void applyFamily(Element properties) throws IOException {
        var family = child(properties, "rFonts"); if (family == null) { return; }
        String name = ""; boolean supported = true;
        for (int index = 0; index < family.getAttributes().getLength(); index++) {
            var attribute = family.getAttributes().item(index); if (!W.equals(attribute.getNamespaceURI())) { continue; }
            if (!Set.of("ascii", "hAnsi", "eastAsia", "cs").contains(attribute.getLocalName())) { supported = false; continue; }
            if (!name.isEmpty() && !name.equals(attribute.getNodeValue())) { supported = false; } name = attribute.getNodeValue();
        }
        var mapping = fonts.get(name);
        if (!supported || mapping == null) { loss(policy, diagnostics, "word/styles.xml", "rFonts", "font family requires one explicit registered physical-role mapping"); }
        else {
            boolean bold = enabled(child(properties, "b")); boolean italic = enabled(child(properties, "i"));
            String role = bold && italic ? mapping.boldItalic() : bold ? mapping.bold() : italic ? mapping.italic() : mapping.regular();
            remove(properties, "rStyle");
            if (!role.isEmpty()) { var style = properties.getOwnerDocument().createElementNS(W, "w:rStyle"); style.setAttributeNS(W, "w:val", role); properties.appendChild(style); }
        }
        remove(properties, "rFonts");
    }
    /** True style toggle properties invert inherited state; direct properties are absolute. */
    private Element merge(Element base, Element override, boolean toggle) throws IOException {
        if (base == null && override == null) { return null; }
        reserve(estimate(base) + estimate(override));
        var result = base == null ? (Element) override.cloneNode(false) : (Element) base.cloneNode(true);
        var seen = new HashSet<String>();
        for (var source : children(override)) {
            if (!seen.add(source.getNamespaceURI() + ":" + source.getLocalName())) { throw new IOException("duplicate DOCX direct formatting property"); }
            var previous = child(result, source.getLocalName()); var copy = (Element) result.getOwnerDocument().importNode(source, true);
            if (toggle && TOGGLES.contains(source.getLocalName())) {
                if (!enabled(source)) { continue; }
                copy.setAttributeNS(W, "w:val", enabled(previous) ? "0" : "1");
            } else if (previous != null && Set.of("spacing", "ind", "rFonts", "numPr").contains(source.getLocalName())) {
                if (source.getLocalName().equals("numPr")) { copy = merge(previous, source, false); }
                else {
                    copy = (Element) previous.cloneNode(true);
                    for (int index = 0; index < source.getAttributes().getLength(); index++) { var a = source.getAttributes().item(index); copy.setAttributeNS(a.getNamespaceURI(), a.getNodeName(), a.getNodeValue()); }
                }
            }
            if (previous != null) { result.removeChild(previous); } result.appendChild(copy);
        }
        return result;
    }
    private void reserve(long bytes) throws IOException {
        ExecutionContext.checkpoint();
        if (bytes > limits.maxExpandedBytes() - formattingWork) { throw new IOException("DOCX cumulative formatting materialization budget exceeded"); }
        formattingWork += bytes;
    }
    /** Conservative XML-copy work estimate, not a memory or serialized-size measurement. */
    private long estimate(Element element) throws IOException {
        if (element == null) { return 0; } ExecutionContext.checkpoint(); long total = 32;
        for (int index = 0; index < element.getAttributes().getLength(); index++) { total += 32L + element.getAttributes().item(index).getNodeValue().length() * 6L; }
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            total += node instanceof Element child ? estimate(child) : node.getNodeValue() == null ? 0 : node.getNodeValue().length() * 6L;
            if (total > limits.maxExpandedBytes()) { throw new IOException("DOCX formatting property budget exceeded"); }
        }
        return total;
    }
    private static void remove(Element parent, String name) { for (var node : children(parent)) { if (W.equals(node.getNamespaceURI()) && node.getLocalName().equals(name)) { parent.removeChild(node); } } }
    private static String value(Element source, String fallback) { return source == null ? fallback : source.hasAttributeNS(W, "val") ? source.getAttributeNS(W, "val") : fallback; }
    private static boolean enabledAttribute(Element element, String name) throws IOException {
        if (!element.hasAttributeNS(W, name)) { return false; } return onOff(element.getAttributeNS(W, name));
    }
    private static boolean enabled(Element element) throws IOException { return element != null && onOff(value(element, "1")); }
    private static boolean onOff(String value) throws IOException {
        return switch (value) { case "1", "true", "on" -> true; case "0", "false", "off" -> false; default -> throw new IOException("invalid DOCX toggle property"); };
    }
    private static byte[] encode(Element root, int limit) throws IOException {
        try {
            var factory = TransformerFactory.newInstance(); factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var bytes = new ByteArrayOutputStream();
            var bounded = new java.io.OutputStream() {
                private void reserve(int count) { ExecutionContext.checkpoint(); if (count > limit - bytes.size()) { throw new IllegalArgumentException("DOCX materialized XML byte budget exceeded"); } }
                @Override public void write(int value) { reserve(1); bytes.write(value); }
                @Override public void write(byte[] value, int offset, int count) { java.util.Objects.checkFromIndexSize(offset, count, value.length); reserve(count); bytes.write(value, offset, count); }
            };
            factory.newTransformer().transform(new DOMSource(root.getOwnerDocument()), new StreamResult(bounded)); return bytes.toByteArray();
        } catch (TransformerException failure) { throw new IOException("DOCX style materialization failed", failure); }
    }
}
