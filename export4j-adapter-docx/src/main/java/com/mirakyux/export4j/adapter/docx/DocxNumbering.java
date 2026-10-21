// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import com.mirakyux.export4j.model.ExecutionContext;
import static com.mirakyux.export4j.adapter.docx.DocxAdapter.child;
import static com.mirakyux.export4j.adapter.docx.DocxAdapter.children;
import static com.mirakyux.export4j.adapter.docx.DocxAdapter.loss;

/** Bounded validation of the complete numbering part, including unused definitions and marker properties. */
final class DocxNumbering {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String MC = "http://schemas.openxmlformats.org/markup-compatibility/2006";
    private static final String PART = "word/numbering.xml";
    private static final Set<String> BOOLEANS = Set.of("true", "false", "on", "off", "0", "1");

    private DocxNumbering() { }

    record Index(Map<String, Element> levels, Set<String> overrides) {
        Index { levels = Map.copyOf(levels); overrides = Set.copyOf(overrides); }
    }

    static Index read(Element root, DocxLimits limits, DocxAdapter.Policy policy,
            List<DocxAdapter.Diagnostic> diagnostics) throws IOException {
        if (!named(root, "numbering")) { throw new IOException("invalid OOXML numbering root"); }
        var budget = new Budget(Math.min(1_000_000L, limits.maxBlocks() * 128L + 1));
        validate(root, "", budget, policy, diagnostics);
        Map<String, Map<String, Element>> abstracts = new HashMap<>();
        Map<String, Element> instances = new HashMap<>();
        Set<String> overrides = new HashSet<>();
        for (var element : children(root)) {
            if (named(element, "abstractNum")) {
                String id = index(element, "abstractNumId", 0, Integer.MAX_VALUE);
                if (abstracts.putIfAbsent(id, uniqueLevels(element, "lvl")) != null) { throw new IOException("duplicate DOCX abstract numbering ID"); }
            } else if (named(element, "num")) {
                String id = index(element, "numId", 1, Integer.MAX_VALUE);
                if (instances.putIfAbsent(id, element) != null) { throw new IOException("duplicate DOCX numbering instance ID"); }
                if (!uniqueLevels(element, "lvlOverride").isEmpty()) { overrides.add(id); }
            }
        }
        Map<String, Element> result = new HashMap<>();
        for (var instance : instances.entrySet()) {
            ExecutionContext.checkpoint();
            var reference = child(instance.getValue(), "abstractNumId");
            if (reference == null) { throw new IOException("DOCX numbering instance has no abstract definition reference"); }
            var definition = abstracts.get(index(reference, "val", 0, Integer.MAX_VALUE));
            if (definition == null) {
                loss(policy, diagnostics, PART, "abstractNumId", "numbering abstract definition is missing");
                continue;
            }
            definition.forEach((level, element) -> result.put(instance.getKey() + ":" + level, element));
        }
        return new Index(result, overrides);
    }

    private static Map<String, Element> uniqueLevels(Element container, String name) throws IOException {
        Map<String, Element> levels = new HashMap<>();
        for (var element : children(container)) {
            if (named(element, name) && levels.putIfAbsent(index(element, "ilvl", 0, 8), element) != null) {
                throw new IOException("duplicate DOCX numbering level");
            }
        }
        return levels;
    }

    private static void validate(Element element, String parent, Budget budget, DocxAdapter.Policy policy,
            List<DocxAdapter.Diagnostic> diagnostics) throws IOException {
        budget.node();
        String name = element.getLocalName();
        Set<String> allowedChildren = switch (name) {
            case "numbering" -> Set.of("abstractNum", "num");
            case "abstractNum" -> Set.of("multiLevelType", "lvl");
            case "num" -> Set.of("abstractNumId", "lvlOverride");
            case "lvl" -> Set.of("start", "numFmt", "lvlText", "rPr");
            case "lvlOverride" -> Set.of("startOverride");
            case "rPr" -> Set.of("rStyle", "b", "i", "sz", "color", "position", "u", "strike");
            default -> Set.of();
        };
        boolean supported = W.equals(element.getNamespaceURI()) && switch (parent) {
            case "" -> name.equals("numbering");
            case "numbering" -> Set.of("abstractNum", "num").contains(name);
            case "abstractNum" -> Set.of("multiLevelType", "lvl").contains(name);
            case "num" -> Set.of("abstractNumId", "lvlOverride").contains(name);
            case "lvl" -> Set.of("start", "numFmt", "lvlText", "rPr").contains(name);
            case "lvlOverride" -> name.equals("startOverride");
            case "rPr" -> Set.of("rStyle", "b", "i", "sz", "color", "position", "u", "strike").contains(name);
            default -> false;
        };
        if (!supported) { loss(policy, diagnostics, PART, name, "unsupported numbering markup or container"); }
        Set<String> attributes = switch (name) {
            case "numbering", "rPr" -> Set.of();
            case "abstractNum" -> Set.of("abstractNumId");
            case "num" -> Set.of("numId");
            case "lvl", "lvlOverride" -> Set.of("ilvl");
            default -> Set.of("val");
        };
        var attrs = element.getAttributes();
        for (int index = 0; index < attrs.getLength(); index++) {
            var attribute = attrs.item(index);
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) { continue; }
            if (name.equals("numbering") && MC.equals(attribute.getNamespaceURI()) && attribute.getLocalName().equals("Ignorable")) { continue; }
            if (!W.equals(attribute.getNamespaceURI()) || !attributes.contains(attribute.getLocalName())) {
                loss(policy, diagnostics, PART, name, "unsupported numbering attribute: " + attribute.getLocalName());
            }
        }
        if (supported) { validateValue(element, policy, diagnostics); }
        Set<String> singletons = new HashSet<>();
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element nested) {
                String nestedName = nested.getLocalName();
                boolean repeatable = name.equals("numbering") || name.equals("abstractNum") && nestedName.equals("lvl")
                        || name.equals("num") && nestedName.equals("lvlOverride");
                if (W.equals(nested.getNamespaceURI()) && allowedChildren.contains(nestedName)
                        && !repeatable && !singletons.add(nestedName)) {
                    throw new IOException("duplicate DOCX numbering property: " + nestedName);
                }
                validate(nested, W.equals(element.getNamespaceURI()) && supported ? name : "unsupported", budget, policy, diagnostics);
            } else if ((node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE)
                    && !node.getTextContent().isBlank()) {
                loss(policy, diagnostics, PART, name, "numbering properties cannot contain text");
            }
        }
    }

    private static void validateValue(Element element, DocxAdapter.Policy policy,
            List<DocxAdapter.Diagnostic> diagnostics) throws IOException {
        String value = element.getAttributeNS(W, "val");
        switch (element.getLocalName()) {
            case "abstractNum" -> index(element, "abstractNumId", 0, Integer.MAX_VALUE);
            case "num" -> index(element, "numId", 1, Integer.MAX_VALUE);
            case "lvl", "lvlOverride" -> index(element, "ilvl", 0, 8);
            case "abstractNumId" -> index(element, "val", 0, Integer.MAX_VALUE);
            case "start", "startOverride", "sz" -> index(element, "val", 1, Integer.MAX_VALUE);
            case "position" -> index(element, "val", Integer.MIN_VALUE, Integer.MAX_VALUE);
            case "b", "i", "strike" -> {
                if (element.hasAttributeNS(W, "val") && !BOOLEANS.contains(value)) { throw new IOException("invalid DOCX numbering boolean"); }
            }
            case "color" -> {
                if (!value.equals("auto") && !value.matches("[0-9A-Fa-f]{6}")) { throw new IOException("invalid DOCX numbering color"); }
            }
            case "rStyle" -> {
                if (value.isBlank()) { throw new IOException("DOCX numbering style reference is blank"); }
            }
            case "numFmt" -> {
                if (!Set.of("decimal", "bullet").contains(value)) {
                    loss(policy, diagnostics, PART, "numFmt", "only decimal and bullet numbering are supported");
                }
            }
            case "multiLevelType" -> {
                if (!Set.of("multilevel", "singleLevel").contains(value)) {
                    loss(policy, diagnostics, PART, "multiLevelType", "unsupported numbering level type");
                }
            }
            case "u" -> {
                if (element.hasAttributeNS(W, "val") && !Set.of("single", "none").contains(value)) {
                    loss(policy, diagnostics, PART, "u", "unsupported numbering underline style");
                }
            }
            default -> { }
        }
    }

    private static String index(Element element, String attribute, int minimum, int maximum) throws IOException {
        try {
            int value = Integer.parseInt(element.getAttributeNS(W, attribute));
            if (value < minimum || value > maximum) { throw new NumberFormatException(); }
            return Integer.toString(value);
        } catch (NumberFormatException failure) { throw new IOException("invalid DOCX numbering index or integer", failure); }
    }

    private static boolean named(Element element, String name) {
        return W.equals(element.getNamespaceURI()) && name.equals(element.getLocalName());
    }

    private static final class Budget {
        private long remaining;
        Budget(long limit) { remaining = limit; }
        void node() throws IOException {
            ExecutionContext.checkpoint();
            if (--remaining < 0) { throw new IOException("DOCX numbering node budget exceeded"); }
        }
    }
}
