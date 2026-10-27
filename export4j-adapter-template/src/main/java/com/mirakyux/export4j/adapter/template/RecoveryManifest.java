// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.model.CellLocation;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Versioned, content-free binding and cumulative actual diagnostics. Never Java deserialization. */
record RecoveryManifest(int version, String format, int length, String digest, String modelDigest,
        List<ProcessingIssue> issues) {
    RecoveryManifest { issues = List.copyOf(issues); }

    byte[] encode(int maxBytes) throws IOException {
        try {
            var document = factory().newDocumentBuilder().newDocument();
            var root = document.createElement("recovery"); document.appendChild(root);
            attributes(root, "version", version, "format", format, "bytes", length, "sha256", digest, "model-sha256", modelDigest);
            for (var issue : issues) {
                ExecutionContext.checkpoint();
                var item = document.createElement("issue"); root.appendChild(item);
                attributes(item, "stage", issue.stage(), "source", issue.sourceFormat(), "target", issue.targetFormat(),
                        "feature", issue.feature(), "operation", issue.operation(), "reason", issue.reason(),
                        "action", issue.action(), "treatment", issue.treatment());
                var at = issue.location(); var location = document.createElement("location"); item.appendChild(location);
                attributes(location, "section", at.sectionId(), "region", at.region(), "block", at.blockId(), "start", at.startUtf16(), "end", at.endUtf16());
                for (var cell : at.cellPath()) {
                    var step = document.createElement("cell"); location.appendChild(step);
                    attributes(step, "table", cell.tableId(), "row", cell.rowIndex(), "column", cell.columnIndex(), "block", cell.blockIndex());
                }
                for (var effect : issue.effects()) {
                    var entry = document.createElement("effect"); item.appendChild(entry); attributes(entry, "value", effect);
                }
            }
            var bytes = new ByteArrayOutputStream();
            var transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = transformerFactory.newTransformer(); transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.transform(new DOMSource(document), new StreamResult(TemplateBundleIO.bounded(bytes, maxBytes)));
            return bytes.toByteArray();
        } catch (javax.xml.parsers.ParserConfigurationException | javax.xml.transform.TransformerException failure) {
            throw new IOException("cannot encode recovery manifest", failure);
        }
    }

    static RecoveryManifest read(byte[] bytes, int maxBytes, int maxIssues) throws IOException {
        if (bytes.length > maxBytes) { throw new IOException("recovery manifest byte budget exceeded"); }
        try {
            var parser = factory().newDocumentBuilder();
            parser.setErrorHandler(new DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException exception) throws SAXException { throw exception; }
                @Override public void fatalError(org.xml.sax.SAXParseException exception) throws SAXException { throw exception; }
            });
            parser.setEntityResolver((publicId, systemId) -> { throw new SAXException("external XML resource refused"); });
            var document = parser.parse(new ByteArrayInputStream(bytes)); var root = document.getDocumentElement();
            check(root, "recovery", "version", "format", "bytes", "sha256", "model-sha256");
            int version = number(root, "version"), length = number(root, "bytes");
            String format = root.getAttribute("format"), digest = root.getAttribute("sha256"), model = root.getAttribute("model-sha256");
            if (version < 1 || length < 0 || !format.matches("[A-Z][A-Z0-9_]{0,63}")
                    || !digest.matches("[0-9a-f]{64}") || !model.matches("[0-9a-f]{64}")) { throw new IOException("invalid recovery binding"); }
            var issues = new ArrayList<ProcessingIssue>();
            for (var item : children(root)) {
                ExecutionContext.checkpoint();
                if (issues.size() >= maxIssues) { throw new IOException("recovery issue budget exceeded"); }
                check(item, "issue", "stage", "source", "target", "feature", "operation", "reason", "action", "treatment");
                var values = children(item);
                if (values.isEmpty()) { throw new IOException("missing recovery diagnostic location"); }
                var location = values.getFirst(); check(location, "location", "section", "region", "block", "start", "end");
                var path = new ArrayList<CellLocation>();
                for (var step : children(location)) {
                    if (path.size() >= 16) { throw new IOException("recovery cell depth budget exceeded"); }
                    check(step, "cell", "table", "row", "column", "block"); empty(step);
                    path.add(new CellLocation(step.getAttribute("table"), number(step, "row"), number(step, "column"), number(step, "block")));
                }
                var at = new ProcessingIssue.Location(location.getAttribute("section"), location.getAttribute("region"),
                        location.getAttribute("block"), path, number(location, "start"), number(location, "end"));
                var effects = new ArrayList<String>();
                for (var effect : values.subList(1, values.size())) {
                    if (effects.size() >= 128) { throw new IOException("recovery effect budget exceeded"); }
                    check(effect, "effect", "value"); empty(effect); effects.add(effect.getAttribute("value"));
                }
                var action = ProcessingPolicy.Action.valueOf(item.getAttribute("action"));
                if (action == ProcessingPolicy.Action.ERROR) { throw new IOException("failed action cannot be an actual recovery diagnostic"); }
                issues.add(new ProcessingIssue(item.getAttribute("stage"), item.getAttribute("source"), item.getAttribute("target"),
                        item.getAttribute("feature"), item.getAttribute("operation"), at, item.getAttribute("reason"), action,
                        ProcessingPolicy.Treatment.valueOf(item.getAttribute("treatment")), effects));
            }
            return new RecoveryManifest(version, format, length, digest, model, issues);
        } catch (SAXException failure) {
            throw new IOException("invalid recovery manifest XML");
        } catch (javax.xml.parsers.ParserConfigurationException | IllegalArgumentException failure) {
            throw new IOException("invalid recovery manifest", failure);
        }
    }

    private static DocumentBuilderFactory factory() throws javax.xml.parsers.ParserConfigurationException {
        var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "24");
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false); return factory;
    }
    private static void attributes(Element element, Object... values) {
        for (int index = 0; index < values.length; index += 2) {
            String value = values[index + 1].toString();
            for (int offset = 0; offset < value.length();) {
                int cp = value.codePointAt(offset); offset += Character.charCount(cp);
                if (!(cp == 9 || cp == 10 || cp == 13 || cp >= 32 && cp <= 0xd7ff || cp >= 0xe000 && cp <= 0xfffd || cp >= 0x10000 && cp <= 0x10ffff)) {
                    throw new IllegalArgumentException("invalid recovery diagnostic Unicode");
                }
            }
            element.setAttribute(values[index].toString(), value);
        }
    }
    private static void check(Element element, String name, String... attributes) throws IOException {
        if (!element.getTagName().equals(name) || element.getNamespaceURI() != null) { throw new IOException("invalid recovery element"); }
        Set<String> expected = new HashSet<>(List.of(attributes));
        var actual = element.getAttributes();
        for (int index = 0; index < actual.getLength(); index++) {
            if (!expected.remove(actual.item(index).getNodeName())) { throw new IOException("unknown recovery attribute"); }
        }
        if (!expected.isEmpty()) { throw new IOException("missing recovery attribute"); }
    }
    private static int number(Element element, String attribute) { return Integer.parseInt(element.getAttribute(attribute)); }
    private static void empty(Element element) throws IOException { if (!children(element).isEmpty()) { throw new IOException("unexpected recovery child"); } }
    private static List<Element> children(Element element) throws IOException {
        var result = new ArrayList<Element>();
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            ExecutionContext.checkpoint();
            if (child instanceof Element item) { result.add(item); }
            else if (child.getNodeType() != Node.TEXT_NODE || !child.getNodeValue().isBlank()) { throw new IOException("unexpected recovery XML content"); }
        }
        return result;
    }
}
