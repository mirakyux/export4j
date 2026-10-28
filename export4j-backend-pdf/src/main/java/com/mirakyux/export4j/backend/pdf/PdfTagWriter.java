// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDStructureElementNameTreeNode;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import com.mirakyux.export4j.graphics.AccessibilityTree;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.model.ExecutionContext;

/** Converts explicit semantic bindings into standard PDF/UA-1 structure and marked content. */
final class PdfTagWriter {
    private record Binding(AccessibilityTree.Node owner, int mcid) { }
    private final DisplayDocument source;
    private final Binding[][] bindings;
    private final boolean[][] artifacts;
    private final Map<String, PDStructureElement> elements = new HashMap<>();

    PdfTagWriter(DisplayDocument source) {
        this.source = source;
        bindings = new Binding[source.pages().size()][];
        artifacts = new boolean[source.pages().size()][];
        for (int page = 0; page < bindings.length; page++) {
            int count = source.pages().get(page).commands().size();
            bindings[page] = new Binding[count]; artifacts[page] = new boolean[count];
        }
        for (var node : source.accessibility().children()) { bind(node); }
        for (var range : source.accessibility().artifacts()) {
            for (int command = range.firstCommand(); command < range.endCommand(); command++) { artifacts[range.pageIndex()][command] = true; }
        }
        for (int page = 0; page < bindings.length; page++) {
            int mcid = 0;
            for (int command = 0; command < bindings[page].length; command++) {
                if (bindings[page][command] != null) { bindings[page][command] = new Binding(bindings[page][command].owner(), mcid++); }
            }
        }
    }

    private void bind(AccessibilityTree.Node node) {
        ExecutionContext.checkpoint();
        for (var range : node.content()) {
            for (int index = range.firstCommand(); index < range.endCommand(); index++) {
                if (AccessibilityTree.paints(source.pages().get(range.pageIndex()).commands().get(index))) { bindings[range.pageIndex()][index] = new Binding(node, -1); }
            }
        }
        for (var child : node.children()) { bind(child); }
    }

    boolean begin(PDPageContentStream stream, int page, int command) throws IOException {
        var binding = bindings[page][command];
        if (binding != null) {
            var dictionary = new COSDictionary(); dictionary.setInt(COSName.MCID, binding.mcid());
            stream.beginMarkedContent(COSName.getPDFName(role(binding.owner().role())), PDPropertyList.create(dictionary));
            return true;
        }
        if (artifacts[page][command] && AccessibilityTree.paints(source.pages().get(page).commands().get(command))) {
            stream.beginMarkedContent(COSName.ARTIFACT); return true;
        }
        return false;
    }

    void finish(PDDocument pdf) throws IOException {
        var catalog = pdf.getDocumentCatalog();
        pdf.setVersion(1.7f);
        catalog.setLanguage(source.accessibility().language());
        var marked = new org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo(); marked.setMarked(true); catalog.setMarkInfo(marked);
        var preferences = new org.apache.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences(new COSDictionary());
        preferences.setDisplayDocTitle(true); catalog.setViewerPreferences(preferences);
        var tree = new PDStructureTreeRoot(); catalog.setStructureTreeRoot(tree);
        var document = new PDStructureElement("Document", tree); tree.appendKid(document);
        for (var node : source.accessibility().children()) { structure(pdf, document, node); }
        var idTree = new PDStructureElementNameTreeNode(); idTree.setNames(new TreeMap<>(elements)); tree.setIDTree(idTree);
        var numbers = new COSArray();
        for (int page = 0; page < bindings.length; page++) {
            var actual = pdf.getPage(page); actual.getCOSObject().setInt(COSName.STRUCT_PARENTS, page); actual.getCOSObject().setName(COSName.getPDFName("Tabs"), "S");
            var parents = new COSArray();
            for (var binding : bindings[page]) { if (binding != null) { parents.add(elements.get(binding.owner().id())); } }
            numbers.add(COSInteger.get(page)); numbers.add(parents);
        }
        var parentTree = new COSDictionary(); parentTree.setItem(COSName.NUMS, numbers);
        tree.getCOSObject().setItem(COSName.PARENT_TREE, parentTree); tree.setParentTreeNextKey(bindings.length);
        String xmp = "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\" xmlns:pdfuaid=\"http://www.aiim.org/pdfua/ns/id/\" pdfuaid:part=\"1\"/>"
                + "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">"
                + escape(source.metadata().title()) + "</rdf:li></rdf:Alt></dc:title></rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
        var metadata = new PDMetadata(pdf); metadata.importXMPMetadata(xmp.getBytes(StandardCharsets.UTF_8)); catalog.setMetadata(metadata);
    }

    private void structure(PDDocument pdf, PDStructureNode parent, AccessibilityTree.Node node) {
        ExecutionContext.checkpoint();
        var element = new PDStructureElement(role(node.role()), parent); parent.appendKid(element); elements.put(node.id(), element);
        element.setElementIdentifier(node.id());
        if (!node.language().isEmpty()) { element.setLanguage(node.language()); }
        if (!node.alternativeText().isEmpty()) { element.setAlternateDescription(node.alternativeText()); }
        if (node.role() == AccessibilityTree.Role.TH || node.role() == AccessibilityTree.Role.TD) {
            var attributes = new COSDictionary(); attributes.setName(COSName.O, "Table");
            if (node.cell().scope() != AccessibilityTree.Scope.NONE) {
                attributes.setName(COSName.getPDFName("Scope"), switch (node.cell().scope()) { case ROW -> "Row"; case COLUMN -> "Column"; case BOTH -> "Both"; default -> throw new IllegalStateException(); });
            }
            if (node.cell().rowSpan() > 1) { attributes.setInt(COSName.getPDFName("RowSpan"), node.cell().rowSpan()); }
            if (node.cell().columnSpan() > 1) { attributes.setInt(COSName.getPDFName("ColSpan"), node.cell().columnSpan()); }
            if (!node.cell().headers().isEmpty()) { var headers = new COSArray(); for (var id : node.cell().headers()) { headers.add(new COSString(id)); } attributes.setItem(COSName.getPDFName("Headers"), headers); }
            element.getCOSObject().setItem(COSName.A, attributes);
        }
        for (var range : node.content()) {
            for (int command = range.firstCommand(); command < range.endCommand(); command++) {
                var binding = bindings[range.pageIndex()][command];
                if (binding != null) { var reference = new PDMarkedContentReference(); reference.setPage(pdf.getPage(range.pageIndex())); reference.setMCID(binding.mcid()); element.appendKid(reference); }
            }
        }
        for (var child : node.children()) { structure(pdf, element, child); }
    }

    private static String role(AccessibilityTree.Role value) {
        return switch (value) {
            case PART -> "Part"; case SECT -> "Sect"; case DIV -> "Div"; case LI -> "LI"; case LBL -> "Lbl"; case LBODY -> "LBody";
            case FIGURE -> "Figure"; case CAPTION -> "Caption"; case TABLE -> "Table"; case SPAN -> "Span"; default -> value.name();
        };
    }
    private static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
