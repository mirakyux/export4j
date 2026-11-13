// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;

/** Adapter-owned immutable association between explicit Word families and physical semantic roles. */
final class DocxFontProfiles {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    record Role(DocxFontFamilies families, boolean bold, boolean italic) { }
    private final Map<DocxFontFamilies, DocxAdapter.FontRoles> profiles;
    private final Map<String, Role> roles;
    private final DocxFontFamilies defaults;

    DocxFontProfiles(Map<DocxFontFamilies, DocxAdapter.FontRoles> profiles, DocxFontFamilies defaults) {
        this(profiles, defaults, Map.of());
    }

    DocxFontProfiles(Map<DocxFontFamilies, DocxAdapter.FontRoles> profiles, DocxFontFamilies defaults,
            Map<String, Role> aliases) {
        this.profiles = Map.copyOf(profiles); this.defaults = defaults;
        var mapped = new HashMap<String, Role>();
        profiles.forEach((family, keys) -> {
            add(mapped, keys.regular(), new Role(family, false, false));
            add(mapped, keys.bold(), new Role(family, true, false));
            add(mapped, keys.italic(), new Role(family, false, true));
            add(mapped, keys.boldItalic(), new Role(family, true, true));
        });
        aliases.forEach((name, role) -> {
            if (mapped.putIfAbsent(name, role) != null) { throw new IllegalArgumentException("DOCX alias conflicts with a font profile role"); }
        });
        roles = Map.copyOf(mapped);
    }

    private static void add(Map<String, Role> roles, String key, Role value) {
        var previous = roles.putIfAbsent(key, value);
        if (previous != null && !previous.equals(value)) { throw new IllegalArgumentException("ambiguous DOCX output font role"); }
    }

    String defaultsXml() { return defaults == null ? "" : "<w:docDefaults><w:rPrDefault><w:rPr>" + xml(defaults) + "</w:rPr></w:rPrDefault></w:docDefaults>"; }
    Role role(String name) { return roles.get(name); }
    DocxAdapter.FontRoles mapping(Element fonts) throws IOException { return profiles.get(read(fonts)); }
    boolean empty() { return roles.isEmpty(); }

    static String xml(DocxFontFamilies families) {
        return "<w:rFonts w:ascii=\"" + DocxAdapter.escape(families.ascii()) + "\" w:hAnsi=\"" + DocxAdapter.escape(families.highAnsi())
                + "\" w:eastAsia=\"" + DocxAdapter.escape(families.eastAsia()) + "\" w:cs=\"" + DocxAdapter.escape(families.complexScript()) + "\"/>";
    }

    static DocxFontFamilies read(Element fonts) throws IOException {
        for (int index = 0; index < fonts.getAttributes().getLength(); index++) {
            var attribute = fonts.getAttributes().item(index);
            if (javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) { continue; }
            if (!W.equals(attribute.getNamespaceURI()) || !Set.of("ascii", "hAnsi", "eastAsia", "cs").contains(attribute.getLocalName())) {
                throw new IOException("unsupported DOCX font-family attribute");
            }
        }
        String latin = fonts.getAttributeNS(W, "ascii");
        if (latin.isEmpty()) { latin = fonts.getAttributeNS(W, "hAnsi"); }
        if (latin.isEmpty()) { latin = fonts.getAttributeNS(W, "eastAsia"); }
        if (latin.isEmpty()) { latin = fonts.getAttributeNS(W, "cs"); }
        try {
            return new DocxFontFamilies(value(fonts, "ascii", latin), value(fonts, "hAnsi", latin),
                    value(fonts, "eastAsia", latin), value(fonts, "cs", latin));
        } catch (IllegalArgumentException failure) { throw new IOException("invalid DOCX font families", failure); }
    }

    private static String value(Element fonts, String name, String fallback) {
        return fonts.hasAttributeNS(W, name) ? fonts.getAttributeNS(W, name) : fallback;
    }

    /** Flat declarations emitted by this explicitly configured adapter retain their semantic role identity. */
    boolean declared(Element styles) throws IOException {
        if (empty()) { return false; }
        for (var style : DocxAdapter.children(styles)) {
            if (style.getLocalName().equals("docDefaults")) {
                var values = DocxAdapter.children(style);
                var run = DocxAdapter.child(DocxAdapter.child(style, "rPrDefault"), "rPr");
                if (!W.equals(style.getNamespaceURI()) || defaults == null || values.size() != 1 || run == null || DocxAdapter.children(run).size() != 1
                        || DocxAdapter.child(run, "rFonts") == null || !defaults.equals(read(DocxAdapter.child(run, "rFonts")))) { return false; }
            } else {
                String id = style.getAttributeNS(W, "styleId");
                if (DocxStyles.simple(style)) { continue; }
                var role = roles.get(id); var properties = DocxAdapter.child(style, "rPr");
                if (role == null || !style.getAttributeNS(W, "type").equals("character") || properties == null
                        || DocxAdapter.children(style).stream().anyMatch(e -> !W.equals(e.getNamespaceURI()) || !Set.of("name", "rPr").contains(e.getLocalName()))
                        || DocxAdapter.children(properties).stream().anyMatch(e -> !W.equals(e.getNamespaceURI()) || !Set.of("rFonts", "b", "i").contains(e.getLocalName()))
                        || DocxAdapter.child(properties, "rFonts") == null || !role.families().equals(read(DocxAdapter.child(properties, "rFonts")))
                        || role.bold() != declaredToggle(properties, "b") || role.italic() != declaredToggle(properties, "i")) { return false; }
            }
        }
        return true;
    }
    private static boolean declaredToggle(Element properties, String name) {
        var toggle = DocxAdapter.child(properties, name);
        return toggle != null && DocxAdapter.children(toggle).isEmpty()
                && Set.of("1", "true", "on").contains(DocxSemanticXml.attr(toggle, "val", "1"));
    }
}
