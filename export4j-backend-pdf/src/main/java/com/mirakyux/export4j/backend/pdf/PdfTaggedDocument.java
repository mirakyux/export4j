// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import com.mirakyux.export4j.model.ExecutionContext;

/** Bounded inspection and metadata editing of existing page-tagged PDF/UA-1 documents.
 * Reads actual structure, MCIDs and ParentTree links; a conformance identifier alone is insufficient.
 * Forms/annotations, structure-owned Form XObjects and object-reference tags are explicitly unsupported.
 * Metadata edits preserve logical elements, header IDs, page content streams and resources. Signed inputs fail.
 * Structural inspection supplements, and never replaces, independent conformance and assistive-reader checks.
 */
public final class PdfTaggedDocument {
    private PdfTaggedDocument() { }
    public record Limits(int maxInputBytes, int maxElements, int maxMarkedContent, int maxDecodedPageBytes) {
        public static final Limits DEFAULT = new Limits(32 * 1_048_576, 100_000, 1_000_000, 32 * 1_048_576);
        public Limits { if (maxInputBytes <= 0 || maxInputBytes > 256 * 1_048_576 || maxElements <= 0 || maxElements > 100_000 || maxMarkedContent <= 0 || maxMarkedContent > 1_000_000 || maxDecodedPageBytes <= 0 || maxDecodedPageBytes > 64 * 1_048_576) { throw new IllegalArgumentException("invalid tagged PDF budgets"); } }
    }
    public record Content(int pageIndex, int mcid) { }
    public record Element(String id, String role, String alternativeText, List<String> headers, List<Content> content, List<Element> children) {
        public Element { headers = List.copyOf(headers); content = List.copyOf(content); children = List.copyOf(children); }
    }
    public record Report(String title, String language, int pages, List<Element> elements, int markedContent) {
        public Report { elements = List.copyOf(elements); }
    }
    public static Report inspect(Path source, Limits limits) throws IOException {
        byte[] bytes = read(source, limits.maxInputBytes()); try (var pdf = Loader.loadPDF(bytes)) { return inspectDocument(pdf, limits); }
    }
    /** Atomic title/language update that preserves existing tagged content. No repair or retagging is inferred. */
    public static Report metadata(Path source, Path target, String title, String language, Limits limits) throws IOException {
        if (title == null || title.isBlank() || title.length() > 4096 || title.codePoints().anyMatch(cp -> cp < 32 || cp >= 0xd800 && cp <= 0xdfff)
                || language == null || !language.matches("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")) { throw new IllegalArgumentException("invalid accessible metadata"); }
        byte[] bytes = read(source, limits.maxInputBytes()); var absolute = target.toAbsolutePath(); var temporary = Files.createTempFile(absolute.getParent(), ".export4j-tagged-", ".pdf");
        try {
            Report result;
            try (var pdf = Loader.loadPDF(bytes)) {
                inspectDocument(pdf, limits); if (!pdf.getSignatureDictionaries().isEmpty()) { throw new IOException("editing tagged metadata would invalidate a signature"); }
                var xml = xmp(pdf); var titles = xml.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", "title");
                if (titles.getLength() != 1) { throw new IOException("existing tagged metadata has ambiguous titles"); }
                var alternatives = ((org.w3c.dom.Element) titles.item(0)).getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "li");
                org.w3c.dom.Element selected = null;
                for (int i = 0; i < alternatives.getLength(); i++) { var item = (org.w3c.dom.Element) alternatives.item(i); if ("x-default".equals(item.getAttributeNS(XMLConstants.XML_NS_URI, "lang"))) { if (selected != null) { throw new IOException("duplicate default accessible titles"); } selected = item; } }
                if (selected == null) { throw new IOException("existing tagged metadata lacks the default title"); } selected.setTextContent(title);
                var transformerFactory = TransformerFactory.newInstance(); transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true); transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
                var encoded = new ByteArrayOutputStream(); transformerFactory.newTransformer().transform(new DOMSource(xml), new StreamResult(encoded));
                if (encoded.size() > 1_048_576) { throw new IOException("tagged metadata byte budget exceeded"); }
                var metadata = new PDMetadata(pdf); metadata.importXMPMetadata(encoded.toByteArray()); pdf.getDocumentCatalog().setMetadata(metadata); pdf.getDocumentCatalog().setLanguage(language); pdf.getDocumentInformation().setTitle(title);
                result = inspectDocument(pdf, limits);
                try (var output = Files.newOutputStream(temporary)) { pdf.save(ExecutionContext.output(output)); }
            } catch (javax.xml.transform.TransformerException failure) { throw new IOException("tagged metadata encoding failed", failure); }
            if (Files.size(temporary) > limits.maxInputBytes()) { throw new IOException("tagged PDF output budget exceeded"); }
            ExecutionContext.checkpoint(); Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); return result;
        } catch (IOException | RuntimeException | Error failure) { try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { failure.addSuppressed(cleanup); } throw failure; }
    }

    private static Report inspectDocument(PDDocument pdf, Limits limits) throws IOException {
        if (pdf.isEncrypted() || pdf.getDocumentCatalog().getAcroForm() != null) { throw new IOException("tagged inspection requires unencrypted static page content"); }
        var catalog = pdf.getDocumentCatalog(); if (catalog.getMarkInfo() == null || !catalog.getMarkInfo().isMarked() || catalog.getStructureTreeRoot() == null || catalog.getLanguage() == null || catalog.getLanguage().isBlank()) { throw new IOException("existing file lacks an explicit tagged logical document"); }
        var xml = xmp(pdf); var identifiers = xml.getElementsByTagNameNS("http://www.aiim.org/pdfua/ns/id/", "part"); boolean ua1 = identifiers.getLength() == 1 && "1".equals(identifiers.item(0).getTextContent());
        var descriptions = xml.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Description");
        for (int i = 0; i < descriptions.getLength(); i++) { if ("1".equals(((org.w3c.dom.Element) descriptions.item(i)).getAttributeNS("http://www.aiim.org/pdfua/ns/id/", "part"))) { ua1 = true; } }
        if (!ua1) { throw new IOException("existing tagged processing selects PDF/UA-1"); }
        var pages = new IdentityHashMap<COSDictionary, Integer>(); for (int page = 0; page < pdf.getNumberOfPages(); page++) { if (!pdf.getPage(page).getAnnotations().isEmpty()) { throw new IOException("existing tagged annotations require object-reference handling"); } pages.put(pdf.getPage(page).getCOSObject(), page); }
        var context = new Context(pdf, limits, pages); var tree = catalog.getStructureTreeRoot();
        var elements = context.children(tree.getCOSObject().getDictionaryObject(COSName.K), tree.getCOSObject(), null, 0);
        for (var entry : context.headers.entrySet()) { for (var id : entry.getValue()) { if (!"TH".equals(context.ids.get(id))) { throw new IOException("tagged table headers reference an unknown header ID"); } } }
        if (tree.getParentTree() == null) { throw new IOException("tagged PDF has no parent tree"); }
        for (var entry : context.owners.entrySet()) {
            var page = pdf.getPage(entry.getKey().pageIndex()); int key = page.getCOSObject().getInt(COSName.STRUCT_PARENTS, -1);
            var parent = tree.getParentTree().getValue(key); if (!(parent instanceof org.apache.pdfbox.pdmodel.common.COSObjectable object) || !(object.getCOSObject() instanceof COSArray array) || entry.getKey().mcid() >= array.size() || array.getObject(entry.getKey().mcid()) != entry.getValue()) { throw new IOException("tagged ParentTree disagrees with the structure owner"); }
        }
        var encountered = new HashSet<Content>();
        for (int page = 0; page < pdf.getNumberOfPages(); page++) { painted(pdf.getPage(page), page, context.owners, encountered, limits); }
        if (!encountered.equals(context.owners.keySet())) { throw new IOException("tagged structure references missing marked content"); }
        return new Report(pdf.getDocumentInformation().getTitle(), catalog.getLanguage(), pdf.getNumberOfPages(), elements, encountered.size());
    }
    private static final class Context {
        private final PDDocument pdf; private final Limits limits; private final Map<COSDictionary, Integer> pages;
        private final Set<COSDictionary> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<Content, COSDictionary> owners = new HashMap<>(); private final Map<String, String> ids = new HashMap<>(); private final Map<COSDictionary, List<String>> headers = new IdentityHashMap<>();
        Context(PDDocument pdf, Limits limits, Map<COSDictionary, Integer> pages) { this.pdf = pdf; this.limits = limits; this.pages = pages; }
        List<Element> children(COSBase value, COSDictionary parent, PDPage inheritedPage, int depth) throws IOException {
            var result = new ArrayList<Element>();
            for (var item : items(value)) {
                if (!(item instanceof COSDictionary element) || !"StructElem".equals(element.getNameAsString(COSName.TYPE)) || element.getDictionaryObject(COSName.P) != parent) { throw new IOException("invalid tagged structure child/parent relationship"); }
                if (depth >= 64 || seen.size() >= limits.maxElements() || !seen.add(element)) { throw new IOException("tagged structure budget or cycle violation"); }
                ExecutionContext.checkpoint(); String role = role(element.getNameAsString(COSName.S)); String id = element.getString(COSName.ID, "");
                if (!id.isEmpty() && ids.putIfAbsent(id, role) != null) { throw new IOException("duplicate tagged structure ID"); }
                String alt = element.getString(COSName.ALT, ""); if (role.equals("Figure") && alt.isBlank()) { throw new IOException("tagged figure lacks alternative text"); }
                var pageDictionary = element.getCOSDictionary(COSName.PG); PDPage page = pageDictionary == null ? inheritedPage : new PDPage(pageDictionary);
                var content = new ArrayList<Content>(); var descendants = new ArrayList<Element>();
                for (var child : items(element.getDictionaryObject(COSName.K))) {
                    if (child instanceof COSInteger integer) { content.add(content(element, page == null ? null : page.getCOSObject(), integer.intValue())); }
                    else if (child instanceof COSDictionary dictionary && "MCR".equals(dictionary.getNameAsString(COSName.TYPE))) {
                        if (dictionary.containsKey(COSName.getPDFName("Stm"))) { throw new IOException("structure-owned tagged streams are unsupported"); }
                        var markedPage = dictionary.getCOSDictionary(COSName.PG); content.add(content(element, markedPage == null && page != null ? page.getCOSObject() : markedPage, dictionary.getInt(COSName.MCID, -1)));
                    } else { descendants.addAll(children(child, element, page, depth + 1)); }
                }
                var referencedHeaders = new ArrayList<String>();
                for (var attribute : items(element.getDictionaryObject(COSName.A))) { if (attribute instanceof COSDictionary dictionary) { var headerArray = dictionary.getCOSArray(COSName.getPDFName("Headers")); if (headerArray != null) { for (int index = 0; index < headerArray.size(); index++) { if (!(headerArray.getObject(index) instanceof org.apache.pdfbox.cos.COSString string)) { throw new IOException("invalid tagged table header ID"); } referencedHeaders.add(string.getString()); } } } }
                headers.put(element, referencedHeaders); result.add(new Element(id, role, alt, referencedHeaders, content, descendants));
            } return result;
        }
        private Content content(COSDictionary owner, COSDictionary page, int mcid) throws IOException {
            var index = pages.get(page); if (index == null || mcid < 0 || owners.size() >= limits.maxMarkedContent()) { throw new IOException("invalid tagged content reference"); }
            var result = new Content(index, mcid); if (owners.putIfAbsent(result, owner) != null) { throw new IOException("marked content has multiple logical owners"); } return result;
        }
        private String role(String role) throws IOException {
            if (role == null) { throw new IOException("missing tagged structure role"); }
            var map = pdf.getDocumentCatalog().getStructureTreeRoot().getCOSObject().getCOSDictionary(COSName.ROLE_MAP); var seen = new HashSet<String>();
            while (map != null && map.containsKey(COSName.getPDFName(role))) { if (seen.size() > 16 || !seen.add(role)) { throw new IOException("cyclic tagged role map"); } role = map.getNameAsString(COSName.getPDFName(role)); if (role == null) { throw new IOException("invalid tagged role mapping"); } }
            return role;
        }
    }
    private record Mark(boolean artifact, Content owner) { }
    private static void painted(PDPage page, int pageIndex, Map<Content, COSDictionary> owners, Set<Content> encountered, Limits limits) throws IOException {
        byte[] data; try (var input = page.getContents()) { data = input.readNBytes(limits.maxDecodedPageBytes() + 1); } if (data.length > limits.maxDecodedPageBytes()) { throw new IOException("tagged page decoded-byte budget exceeded"); }
        var parser = new PDFStreamParser(data); var operands = new ArrayList<COSBase>(); var stack = new ArrayDeque<Mark>(); int count = 0;
        try {
            Object token; while ((token = parser.parseNextToken()) != null) {
                ExecutionContext.checkpoint(); if (++count > 1_000_000) { throw new IOException("tagged page token budget exceeded"); }
                if (token instanceof Operator operator) {
                    String name = operator.getName();
                    if (name.equals("BMC") || name.equals("BDC")) {
                        if (stack.size() >= 64 || operands.isEmpty() || !(operands.getFirst() instanceof COSName tag)) { throw new IOException("invalid tagged content stack"); }
                        boolean artifact = COSName.ARTIFACT.equals(tag) || !stack.isEmpty() && stack.peek().artifact(); Content content = !stack.isEmpty() ? stack.peek().owner() : null;
                        if (name.equals("BDC")) {
                            if (operands.size() != 2) { throw new IOException("invalid marked-content properties"); } var properties = operands.get(1);
                            if (properties instanceof COSName key) { var property = page.getResources().getProperties(key); properties = property == null ? null : property.getCOSObject(); }
                            if (!(properties instanceof COSDictionary dictionary)) { throw new IOException("missing marked-content property dictionary"); }
                            if (dictionary.containsKey(COSName.MCID)) { int mcid = dictionary.getInt(COSName.MCID, -1); content = new Content(pageIndex, mcid); if (!owners.containsKey(content) || !encountered.add(content)) { throw new IOException("marked-content ID is missing or duplicated"); } }
                        }
                        stack.push(new Mark(artifact, content));
                    } else if (name.equals("EMC")) { if (stack.isEmpty()) { throw new IOException("unbalanced marked content"); } stack.pop(); }
                    else if (Set.of("Tj", "TJ", "'", "\"", "Do", "BI", "f", "F", "f*", "S", "s", "B", "B*", "b", "b*", "sh").contains(name)
                            && (stack.isEmpty() || !stack.peek().artifact() && stack.peek().owner() == null)) { throw new IOException("existing PDF paints untagged nonartifact content"); }
                    operands.clear();
                } else if (token instanceof COSBase value) { if (operands.size() >= 4096) { throw new IOException("tagged operand budget exceeded"); } operands.add(value); }
            }
            if (!stack.isEmpty()) { throw new IOException("unclosed marked content"); }
        } finally { parser.close(); }
    }
    private static List<COSBase> items(COSBase value) {
        if (value == null) { return List.of(); } if (value instanceof COSObject object) { value = object.getObject(); }
        if (value instanceof COSArray array) { var result = new ArrayList<COSBase>(); for (int i = 0; i < array.size(); i++) { result.add(array.getObject(i)); } return result; } return List.of(value);
    }
    private static org.w3c.dom.Document xmp(PDDocument pdf) throws IOException {
        var metadata = pdf.getDocumentCatalog().getMetadata(); if (metadata == null) { throw new IOException("tagged PDF lacks XMP metadata"); }
        byte[] bytes; try (var input = metadata.createInputStream()) { bytes = input.readNBytes(1_048_577); } if (bytes.length > 1_048_576) { throw new IOException("tagged metadata byte budget exceeded"); }
        try { var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true); factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, ""); return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)); }
        catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException failure) { throw new IOException("invalid tagged XMP", failure); }
    }
    private static byte[] read(Path source, int maximum) throws IOException { ExecutionContext.checkpoint(); byte[] bytes; try (var stream = Files.newInputStream(source)) { bytes = stream.readNBytes(maximum + 1); } if (bytes.length > maximum) { throw new IOException("tagged PDF input budget exceeded"); } return bytes; }
}
