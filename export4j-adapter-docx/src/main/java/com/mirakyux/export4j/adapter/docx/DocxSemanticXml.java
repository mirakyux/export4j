// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import static com.mirakyux.export4j.adapter.docx.DocxAdapter.children;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Bounded, task-confined DOM utilities. No model ever retains a DOM or a package map. */
final class DocxSemanticXml {
    static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    static final String REL = "http://schemas.openxmlformats.org/package/2006/relationships";
    static final String OD = "urn:com.mirakyux.export4j:semantic:1";
    static final String WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
    static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    static final String PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture";
    static final String WPS = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape";
    static final String W14 = "http://schemas.microsoft.com/office/word/2010/wordml";
    static final String W15 = "http://schemas.microsoft.com/office/word/2012/wordml";
    private DocxSemanticXml() { }
    static boolean is(Element e, String local) { return e != null && W.equals(e.getNamespaceURI()) && local.equals(e.getLocalName()); }
    static Element direct(Element parent, String namespace, String local) throws IOException {
        Element result = null;
        for (var child : children(parent)) { if (namespace.equals(child.getNamespaceURI()) && local.equals(child.getLocalName())) { if (result != null) { throw new IOException("duplicate Word semantic element: " + local); } result = child; } }
        return result;
    }
    static List<Element> all(Element root, String namespace, String local) {
        var result = new ArrayList<Element>(); var nodes = root.getElementsByTagNameNS(namespace, local);
        for (int i = 0; i < nodes.getLength(); i++) { result.add((Element) nodes.item(i)); }
        return result;
    }
    static Element element(Node owner, String namespace, String qualified) {
        var doc = owner instanceof org.w3c.dom.Document d ? d : owner.getOwnerDocument();
        return doc.createElementNS(namespace, qualified);
    }
    static Element word(Node owner, String local) { return element(owner, W, "w:" + local); }
    static Element property(Node owner, String local, String value) { var result = word(owner, local); result.setAttributeNS(W, "w:val", value); return result; }
    static String attr(Element e, String local, String fallback) { return e != null && e.hasAttributeNS(W, local) ? e.getAttributeNS(W, local) : fallback; }
    static String identity(Element e, String fallback) { return e.hasAttributeNS(OD, "id") ? e.getAttributeNS(OD, "id") : attr(e, "id", fallback); }
    static void identity(Element e, String id, int numeric) { e.setAttributeNS(W, "w:id", Integer.toString(numeric)); e.setAttributeNS(OD, "od:id", id); }
    static boolean on(Element e) { return e != null && !Set.of("false", "0", "off").contains(attr(e, "val", "true")); }
    static double number(String value) throws IOException { try { double result = Double.parseDouble(value); if (!Double.isFinite(result)) { throw new NumberFormatException(); } return result; } catch (NumberFormatException failure) { throw new IOException("invalid Word semantic numeric value", failure); } }
    static long emu(double points) { if (!Double.isFinite(points) || Math.abs(points) > 1e9) { throw new IllegalArgumentException("Word geometry exceeds budget"); } return Math.round(points * 12700); }
    static void unwrap(Element wrapper) { var parent = wrapper.getParentNode(); while (wrapper.hasChildNodes()) { parent.insertBefore(wrapper.getFirstChild(), wrapper); } parent.removeChild(wrapper); }
    static void remove(Element e) { if (e != null && e.getParentNode() != null) { e.getParentNode().removeChild(e); } }
    static byte[] encode(Element root, int max) throws IOException {
        try {
            var factory = TransformerFactory.newInstance(); factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var tx = factory.newTransformer(); tx.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            var bytes = new ByteArrayOutputStream(); tx.transform(new DOMSource(root), new StreamResult(bounded(bytes, max))); return bytes.toByteArray();
        } catch (TransformerException failure) { throw new IOException("Word semantic XML encoding failed", failure); }
    }
    static byte[] zip(Map<String, byte[]> parts, DocxLimits limits) throws IOException {
        var result = new ByteArrayOutputStream(); long expanded = 0;
        if (parts.size() > limits.maxEntries()) { throw new IOException("Word semantic entry budget exceeded"); }
        try (var zip = new ZipOutputStream(bounded(result, limits.maxInputBytes()))) {
            for (var entry : parts.entrySet()) {
                com.mirakyux.export4j.model.ExecutionContext.checkpoint(); expanded += entry.getValue().length;
                if (entry.getValue().length > limits.maxEntryBytes() || expanded > limits.maxExpandedBytes()) { throw new IOException("Word semantic expanded budget exceeded"); }
                var name = new ZipEntry(entry.getKey()); name.setTime(0); zip.putNextEntry(name); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
        return result.toByteArray();
    }
    static OutputStream bounded(ByteArrayOutputStream result, long limit) {
        return new OutputStream() {
            @Override public void write(int value) throws IOException { if (result.size() >= limit) { throw new IOException("Word semantic output budget exceeded"); } result.write(value); }
            @Override public void write(byte[] source, int off, int len) throws IOException { if (len > limit - result.size()) { throw new IOException("Word semantic output budget exceeded"); } result.write(source, off, len); }
        };
    }
    /** Actual semantic length, independent of XML run partitioning. Ordinary fields/images are atomic. */
    static int length(Element e) {
        if (e == null || is(e, "rPr") || is(e, "pPr") || is(e, "sdtPr") || is(e, "instrText") || is(e, "fldChar") || is(e, "commentReference")) { return 0; }
        if (is(e, "t") || is(e, "delText")) { return e.getTextContent().length(); }
        if (is(e, "drawing")) { return e.getElementsByTagNameNS(WPS, "wsp").getLength() > 0 ? 0 : 1; }
        if (is(e, "tab") || is(e, "br") || is(e, "fldSimple")) { return 1; }
        int result = 0; for (var child : children(e)) { result = Math.addExact(result, length(child)); } return result;
    }
    static Element paragraph(Element e) { for (Node n = e; n instanceof Element p; n = n.getParentNode()) { if (is(p, "p")) { return p; } } return null; }
    /** Splits real run/hyperlink containers at a source boundary and returns the following direct child. */
    static Node boundary(Element paragraph, int at) throws IOException {
        int offset = 0;
        for (var child : children(paragraph)) {
            if (is(child, "pPr")) { continue; }
            int size = length(child);
            if (offset == at) { return child; }
            if (at < offset + size) { var right = split(child, at - offset); paragraph.insertBefore(right, child.getNextSibling()); return right; }
            offset += size;
        }
        if (offset != at) { throw new IOException("Word source boundary exceeds paragraph"); } return null;
    }
    private static Element split(Element source, int at) throws IOException {
        if (at <= 0 || at >= length(source) || is(source, "drawing") || is(source, "fldSimple")) { throw new IOException("Word native edit splits an atomic object"); }
        var right = (Element) source.cloneNode(false); int offset = 0;
        for (var child : children(source)) {
            if (is(child, "rPr") || is(child, "sdtPr")) { right.appendChild(child.cloneNode(true)); continue; }
            int size = length(child);
            if (offset >= at) { right.appendChild(child); }
            else if (offset + size > at) {
                if (is(child, "t") || is(child, "delText")) {
                    String text = child.getTextContent(); var tail = (Element)child.cloneNode(false); tail.setTextContent(text.substring(at - offset)); child.setTextContent(text.substring(0, at - offset)); right.appendChild(tail);
                } else { right.appendChild(split(child, at - offset)); }
            }
            offset += size;
        }
        return right;
    }
    static Element textRun(Node owner, String value) {
        var run = word(owner, "r"); var text = word(owner, "t"); text.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve"); text.setTextContent(value); run.appendChild(text); return run;
    }
    static void ensureNamespaces(Element root) {
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:od", OD);
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:wp", WP);
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:a", A);
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:pic", PIC);
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:wps", WPS);
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:w14", W14);
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:w15", W15);
        String mc = "http://schemas.openxmlformats.org/markup-compatibility/2006";
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:mc", mc);
        root.setAttributeNS(mc, "mc:Ignorable", "od wps w14 w15");
    }
}
