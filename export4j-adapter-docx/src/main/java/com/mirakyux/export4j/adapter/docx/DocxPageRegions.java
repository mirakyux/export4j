// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Section;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;
import static com.mirakyux.export4j.adapter.docx.DocxAdapter.child;
import static com.mirakyux.export4j.adapter.docx.DocxAdapter.children;
import static com.mirakyux.export4j.adapter.docx.DocxAdapter.loss;
import static com.mirakyux.export4j.adapter.docx.DocxAdapter.parse;

/** Package-scoped, task-confined relationship resolver for fixed default bands. Never performs I/O. */
final class DocxPageRegions {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String OD = "urn:com.mirakyux.export4j:semantic:1";
    private record Source(String part, String kind) { }
    private final Map<String, Source> sources = new HashMap<>();
    private final Map<String, PageRegion> regions = new HashMap<>();
    private final Set<String> knownParts = new HashSet<>();
    private final Set<String> usedIds = new HashSet<>();
    private final DocxAdapter.Policy policy;
    private final List<DocxAdapter.Diagnostic> diagnostics;
    private PageRegions inherited = PageRegions.EMPTY;
    private final boolean evenEnabled;

    DocxPageRegions(Map<String, byte[]> parts, Element relationships, DocxAdapter.RegionGeometry fallback,
            DocxAdapter.Policy policy, List<DocxAdapter.Diagnostic> diagnostics, DocxCells.Budget budget,
            Map<String, ImageResource> decoded, double defaultWidth) throws IOException {
        this.policy = policy; this.diagnostics = diagnostics;
        evenEnabled = parts.containsKey("word/settings.xml") && enabled(child(parse(parts.get("word/settings.xml")), "evenAndOddHeaders"));
        if (relationships == null) { return; }
        var targetKinds = new HashMap<String, String>();
        for (var relation : children(relationships)) {
            String type = relation.getAttribute("Type");
            if (!type.equals(R + "/header") && !type.equals(R + "/footer")) { continue; }
            String kind = type.endsWith("/header") ? "header" : "footer";
            String id = relation.getAttribute("Id");
            if (!relation.getAttribute("TargetMode").isEmpty() && !relation.getAttribute("TargetMode").equals("Internal")) {
                throw new IOException("external DOCX page region is unsupported");
            }
            String part;
            try {
                var uri = URI.create(relation.getAttribute("Target"));
                String path = uri.getPath();
                if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getQuery() != null || uri.getFragment() != null
                        || path == null || path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains(":")
                        || List.of(path.split("/", -1)).contains("..")) { throw new IllegalArgumentException(); }
                part = "word/" + path;
            } catch (IllegalArgumentException failure) { throw new IOException("unsafe DOCX page region relationship", failure); }
            var previousKind = targetKinds.putIfAbsent(part, kind);
            if (previousKind != null && !previousKind.equals(kind)) { throw new IOException("DOCX header and footer cannot share one typed part"); }
            if (!parts.containsKey(part) || id.isEmpty() || sources.putIfAbsent(id, new Source(part, kind)) != null) {
                throw new IOException("missing or duplicate DOCX page region relationship");
            }
        }
        // Parse each unique source once. Multiple sections reference one immutable band.
        for (var source : sources.values().stream().sorted(java.util.Comparator.comparing(Source::part)).toList()) {
            if (regions.containsKey(source.part())) { continue; }
            var root = parse(parts.get(source.part()));
            if (!W.equals(root.getNamespaceURI()) || !root.getLocalName().equals(source.kind().equals("header") ? "hdr" : "ftr")) {
                throw new IOException("invalid DOCX page region root");
            }
            var localDiagnostics = new ArrayList<DocxAdapter.Diagnostic>();
            try {
                DocxAdapter.validateMarkup(root, policy, localDiagnostics);
                var localIds = new HashSet<String>();
                var ids = DocxCells.index(root, budget, localIds, source.part() + ":");
                String relPart = source.part().substring(0, source.part().lastIndexOf('/') + 1) + "_rels/"
                        + source.part().substring(source.part().lastIndexOf('/') + 1) + ".rels";
                Element rels = parts.containsKey(relPart) ? parse(parts.get(relPart)) : null;
                var images = new DocxImages.Reader(parts, rels, decoded);
                var fields = new DocxFields.Reader(root, ids, policy, localDiagnostics, defaultWidth, Map.of());
                var links = new HashMap<String, String>();
                if (rels != null) {
                    for (var relation : children(rels)) {
                        if (relation.getAttribute("Type").equals(R + "/hyperlink") && relation.getAttribute("TargetMode").equals("External")) {
                            links.put(relation.getAttribute("Id"), new com.mirakyux.export4j.model.ExternalDestination(relation.getAttribute("Target")).uri());
                        }
                    }
                }
                var blocks = new ArrayList<Block>();
                for (var element : children(root)) {
                    if (!W.equals(element.getNamespaceURI())) { continue; }
                    if (element.getLocalName().equals("p")) {
                        if (element.getAttributeNS(OD, "emptyBand").equals("true")) {
                            if (!children(element).isEmpty() || !element.getTextContent().isBlank()) { throw new IOException("nonempty DOCX empty-band marker"); }
                        } else { blocks.add(DocxAdapter.readParagraph(element, ids.get(element), new DocxNumbering.Index(Map.of(), Set.of()),
                                links, images, fields, policy, localDiagnostics, budget)); }
                    } else if (element.getLocalName().equals("tbl")) {
                        blocks.add(DocxAdapter.readTable(element, ids, new DocxNumbering.Index(Map.of(), Set.of()),
                                links, images, fields, policy, localDiagnostics, budget, 1));
                    }
                }
                boolean metadata = root.hasAttributeNS(OD, "height") || root.hasAttributeNS(OD, "gap");
                double height, gap;
                if (metadata) {
                    if (!root.hasAttributeNS(OD, "height") || !root.hasAttributeNS(OD, "gap")) { throw new IOException("incomplete DOCX band geometry"); }
                    height = Double.parseDouble(root.getAttributeNS(OD, "height")); gap = Double.parseDouble(root.getAttributeNS(OD, "gap"));
                } else {
                    if (fallback == null) { throw new IOException("DOCX page regions require explicit fixed geometry when metadata is absent"); }
                    height = source.kind().equals("header") ? fallback.headerHeight() : fallback.footerHeight();
                    gap = source.kind().equals("header") ? fallback.headerGap() : fallback.footerGap();
                }
                var band = new PageRegion(height, gap, blocks);
                var linked = regions.values().stream().filter(band::equals).findFirst();
                if (linked.isPresent()) { band = linked.get(); }
                else {
                    for (var id : localIds) { if (!usedIds.add(id)) { throw new IOException("duplicate DOCX page region semantic ID"); } }
                }
                regions.put(source.part(), band);
                knownParts.add(source.part()); if (rels != null) { knownParts.add(relPart); }
                parts.keySet().stream().filter(images::contains).forEach(knownParts::add);
            } finally {
                for (var diagnostic : localDiagnostics) { diagnostics.add(new DocxAdapter.Diagnostic(source.part(), diagnostic.element(), diagnostic.message())); }
            }
        }
    }

    boolean contains(String part) { return knownParts.contains(part); }
    Set<String> usedIds() { return usedIds; }

    private static void checkDistance(Element margins, String name, double height, double expected) throws IOException {
        if (!margins.hasAttributeNS(W, name)) { return; }
        double distance = Double.parseDouble(margins.getAttributeNS(W, name)) / 20;
        if (!Double.isFinite(distance) || distance < 0 || height > 0 && Math.abs(distance - expected) > .051) {
            throw new IOException("DOCX page region geometry conflicts with body reservation");
        }
    }

    private static boolean enabled(Element element) {
        return element != null && !Set.of("false", "0", "off").contains(element.getAttributeNS(W, "val"));
    }

    Section attach(Section section, Element properties) throws IOException {
        var builder = inherited.toBuilder();
        var kinds = new HashSet<String>();
        boolean firstEnabled = enabled(child(properties, "titlePg"));
        for (var element : children(properties)) {
            String name = element.getLocalName();
            if (!W.equals(element.getNamespaceURI()) || !Set.of("headerReference", "footerReference").contains(name)) { continue; }
            String kind = name.equals("headerReference") ? "header" : "footer";
            String variant = element.getAttributeNS(W, "type");
            if (!Set.of("default", "first", "even").contains(variant)) {
                loss(policy, diagnostics, "word/document.xml", name, "unknown page region variant"); continue;
            }
            if (!kinds.add(kind + ":" + variant)) { throw new IOException("duplicate DOCX page region reference"); }
            var source = sources.get(element.getAttributeNS(R, "id"));
            if (source == null || !source.kind().equals(kind)) { throw new IOException("missing or mismatched DOCX page region relationship"); }
            var band = regions.get(source.part());
            if (variant.equals("default")) {
                if (kind.equals("header")) { builder.header(band); } else { builder.footer(band); }
            } else {
                var selected = variant.equals("first") ? PageRegions.Variant.FIRST : PageRegions.Variant.EVEN;
                if (kind.equals("header")) { builder.header(selected, band); } else { builder.footer(selected, band); }
                if (selected == PageRegions.Variant.FIRST && !firstEnabled || selected == PageRegions.Variant.EVEN && !evenEnabled) {
                    loss(policy, diagnostics, "word/document.xml", name, "inactive page region declaration is not represented in the semantic model");
                }
            }
        }
        inherited = builder.build();
        if (!firstEnabled) { builder.inherit(PageRegions.Variant.FIRST); }
        if (!evenEnabled) { builder.inherit(PageRegions.Variant.EVEN); }
        // A global Word switch needs explicit default references in sections without a model override.
        if (properties.getAttributeNS(OD, "evenDefault").equals("true")) {
            var pair = inherited.variants().get(PageRegions.Variant.EVEN);
            if (!evenEnabled || pair == null || !pair.equals(new PageRegions.Bands(inherited.header(), inherited.footer()))) {
                throw new IOException("DOCX default-even declaration conflicts with its references");
            }
            builder.inherit(PageRegions.Variant.EVEN);
        }
        // Word uses a blank pair when a switch is active before any reference was declared.
        var active = builder.build();
        if (firstEnabled && !active.variants().containsKey(PageRegions.Variant.FIRST)) { builder.variant(PageRegions.Variant.FIRST, PageRegions.Bands.EMPTY); }
        if (evenEnabled && !properties.getAttributeNS(OD, "evenDefault").equals("true") && !active.variants().containsKey(PageRegions.Variant.EVEN)) {
            builder.variant(PageRegions.Variant.EVEN, PageRegions.Bands.EMPTY);
        }
        active = builder.build();
        double top = active.headers().stream().mapToDouble(band -> band.height() + band.gap()).max().orElse(0);
        double bottom = active.footers().stream().mapToDouble(band -> band.height() + band.gap()).max().orElse(0);
        var margins = section.pageSettings().margins();
        var settings = new PageSettings(section.pageSettings().size(), new PageMargins(
                margins.top() - Math.round(top * 20) / 20.0, margins.right(),
                margins.bottom() - Math.round(bottom * 20) / 20.0, margins.left()));
        var wordMargins = child(properties, "pgMar");
        if (wordMargins != null) {
            checkDistance(wordMargins, "header", top, settings.margins().top());
            checkDistance(wordMargins, "footer", bottom, settings.margins().bottom());
        }
        return new Section(section.id(), settings, section.blocks(), section.firstPageNumber(), active);
    }
}
