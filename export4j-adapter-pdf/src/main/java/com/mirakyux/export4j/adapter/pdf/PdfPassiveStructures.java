// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;

/** Document-confined validation of passive optional-content and PDF 1.7 structure trees.
 * Original objects remain untouched. Page selection/composition must remap these graphs or reject it;
 * copying only page dictionaries cannot preserve either document-level contract.
 */
final class PdfPassiveStructures {
    private static final Set<String> ACTIVE = Set.of("JS", "JavaScript", "AA", "OpenAction", "Launch", "URI", "GoToR", "GoToE", "SubmitForm", "ImportData", "SetOCGState");
    private static final Set<String> STANDARD_ROLES = Set.of("Document", "Part", "Art", "Sect", "Div", "BlockQuote", "Caption", "TOC", "TOCI", "Index", "NonStruct", "Private", "P", "H", "H1", "H2", "H3", "H4", "H5", "H6", "L", "LI", "Lbl", "LBody", "Table", "TR", "TH", "TD", "THead", "TBody", "TFoot", "Span", "Quote", "Note", "Reference", "BibEntry", "Code", "Link", "Annot", "Ruby", "RB", "RT", "RP", "Warichu", "WT", "WP", "Figure", "Formula", "Form");
    private final PdfLimits limits;
    private final COSDictionary catalog;
    private final Set<COSDictionary> groups = identities();
    private final Map<COSDictionary, Boolean> enabled = new IdentityHashMap<>();
    private final Map<COSDictionary, Boolean> visibility = new IdentityHashMap<>();
    private final Set<COSDictionary> pages = identities();
    private final Set<COSDictionary> structural = identities();
    private final Map<COSDictionary, Set<COSDictionary>> objectPages = new IdentityHashMap<>();
    private final Map<Integer, COSDictionary> owners = new HashMap<>();
    private final Map<Integer, COSBase> parents = new HashMap<>();
    private final Map<COSDictionary, Set<Integer>> marked = new IdentityHashMap<>();
    private final Map<COSDictionary, Map<Integer, COSDictionary>> associated = new IdentityHashMap<>();
    private final Map<COSDictionary, COSDictionary> objectAssociations = new IdentityHashMap<>();
    private final Map<String, COSDictionary> ids = new HashMap<>();
    private final List<COSArray> elementReferences = new ArrayList<>();
    private final Set<COSDictionary> graph = identities();
    private final Set<COSDictionary> content = identities();
    private long work;
    private long operators;
    private boolean layers;
    private boolean tags;
    private COSDictionary roleMap;

    private PdfPassiveStructures(PDDocument document, PdfLimits limits) {
        this.limits = limits; catalog = document.getDocumentCatalog().getCOSObject();
    }

    static PdfPassiveStructures inspect(PDDocument document, PdfLimits limits) throws IOException {
        var result = new PdfPassiveStructures(document, limits);
        for (var page : document.getPages()) { result.pages.add(page.getCOSObject()); }
        result.optionalContent();
        var root = result.dictionary(result.catalog, "StructTreeRoot", false);
        result.tags = root != null;
        var mark = result.dictionary(result.catalog, "MarkInfo", false);
        if (mark != null) {
            result.keys(mark, Set.of("Marked", "UserProperties", "Suspects"));
            for (String key : Set.of("Marked", "UserProperties", "Suspects")) { result.bool(mark, key); }
            if (mark.getBoolean(COSName.getPDFName("Marked"), false) && root == null) { throw PdfObjects.invalid(); }
        }
        for (var page : document.getPages()) {
            var owner = page.getCOSObject(); result.owner(owner, "StructParents");
            for (var annotation : page.getAnnotations()) {
                var object = annotation.getCOSObject(); result.owner(object, "StructParent");
                result.objectPages.computeIfAbsent(object, ignored -> identities()).add(owner);
            }
            result.resources(page.getResources(), owner, identities(), 0);
            var streams = page.getContentStreams(); var values = new ArrayList<COSStream>();
            while (streams.hasNext()) { values.add(streams.next().getCOSObject()); }
            result.commands(values, owner, page.getResources());
        }
        result.walk(result.catalog, identities(), 0);
        if (root != null) { result.structure(root); }
        else if (!result.owners.isEmpty() || !result.marked.isEmpty()) { throw PdfObjects.invalid(); }
        return result;
    }

    boolean hasLayers() { return layers; }
    boolean hasTags() { return tags; }

    /** Default View visibility. The dictionary is an OCG or OCMD, never a resource name. */
    boolean visible(COSDictionary dictionary) throws IOException {
        return optional(dictionary, identities(), 0);
    }

    /** Only validated structure back-references may bypass generic graph-cycle checks. */
    boolean structuralBackReference(COSDictionary dictionary, COSName key) {
        return structural.contains(dictionary) && Set.of("P", "Pg", "Stm", "Obj", "Ref").contains(key.getName());
    }

    private void tick(int depth) throws IOException {
        ExecutionContext.checkpoint();
        if (depth > limits.maxDepth()) { throw failure(PdfOperationException.Code.DEPTH_LIMIT); }
        if (++work > limits.maxObjects()) { throw failure(PdfOperationException.Code.OBJECT_LIMIT); }
    }
    private static PdfOperationException failure(PdfOperationException.Code code) { return new PdfOperationException(code, 0, -1); }
    private static Set<COSDictionary> identities() { return Collections.newSetFromMap(new IdentityHashMap<>()); }
    private static COSBase value(COSDictionary dictionary, String key) { return dictionary.getDictionaryObject(COSName.getPDFName(key)); }
    private COSDictionary dictionary(COSDictionary dictionary, String key, boolean required) throws IOException {
        var value = value(dictionary, key);
        if (value == null && !required) { return null; }
        if (!(value instanceof COSDictionary result)) { throw PdfObjects.invalid(); } return result;
    }
    private COSArray array(COSDictionary dictionary, String key, boolean required) throws IOException {
        var value = value(dictionary, key);
        if (value == null && !required) { return null; }
        if (!(value instanceof COSArray result)) { throw PdfObjects.invalid(); }
        if (result.size() > limits.maxObjects()) { throw failure(PdfOperationException.Code.OBJECT_LIMIT); } return result;
    }
    private void keys(COSDictionary dictionary, Set<String> accepted) throws IOException {
        for (var key : dictionary.keySet()) { if (!accepted.contains(key.getName())) { throw PdfObjects.unsupported(); } }
    }
    private void text(COSDictionary dictionary, String key, boolean required) throws IOException {
        var value = value(dictionary, key);
        if (value == null && !required) { return; }
        if (!(value instanceof COSString string)) { throw PdfObjects.invalid(); }
        if (string.getBytes().length > limits.maxTextCharacters()) { throw failure(PdfOperationException.Code.TEXT_LIMIT); }
    }
    private void bool(COSDictionary dictionary, String key) throws IOException {
        if (value(dictionary, key) != null && !(value(dictionary, key) instanceof COSBoolean)) { throw PdfObjects.invalid(); }
    }
    private int integer(COSBase value) throws IOException {
        if (!(value instanceof COSInteger number) || number.longValue() < 0 || number.longValue() > Integer.MAX_VALUE) { throw PdfObjects.invalid(); }
        return number.intValue();
    }
    private String name(COSDictionary dictionary, String key, String fallback, Set<String> accepted) throws IOException {
        var value = value(dictionary, key);
        if (value == null) { return fallback; }
        if (!(value instanceof COSName item)) { throw PdfObjects.invalid(); }
        if (accepted != null && !accepted.contains(item.getName())) { throw PdfObjects.unsupported(); } return item.getName();
    }

    private void optionalContent() throws IOException {
        var properties = dictionary(catalog, "OCProperties", false); if (properties == null) { return; }
        layers = true; keys(properties, Set.of("OCGs", "D", "Configs"));
        for (var item : array(properties, "OCGs", true)) {
            tick(0); var resolved = item instanceof org.apache.pdfbox.cos.COSObject object ? object.getObject() : item;
            if (!(resolved instanceof COSDictionary group) || !groups.add(group)) { throw PdfObjects.invalid(); }
            if (groups.size() > limits.maxResources()) { throw failure(PdfOperationException.Code.RESOURCE_LIMIT); }
            keys(group, Set.of("Type", "Name", "Intent", "Usage"));
            if (!name(group, "Type", "", null).equals("OCG")) { throw PdfObjects.invalid(); }
            text(group, "Name", true); intent(value(group, "Intent")); usage(dictionary(group, "Usage", false));
        }
        var defaultConfig = dictionary(properties, "D", true); configuration(defaultConfig);
        boolean base = !name(defaultConfig, "BaseState", "ON", Set.of("ON", "OFF", "Unchanged")).equals("OFF");
        for (var group : groups) { enabled.put(group, base); }
        stateGroups(defaultConfig, "ON", true); stateGroups(defaultConfig, "OFF", false);
        // ViewState is the explicit source View usage intent; print/export states
        // never affect this API's View recovery/rendering contract.
        for (var group : groups) {
            var usage = dictionary(group, "Usage", false); var view = usage == null ? null : dictionary(usage, "View", false);
            if (view != null && value(view, "ViewState") != null) { enabled.put(group, name(view, "ViewState", "ON", Set.of("ON", "OFF")).equals("ON")); }
        }
        var configs = array(properties, "Configs", false);
        if (configs != null) { for (int i = 0; i < configs.size(); i++) { if (!(configs.getObject(i) instanceof COSDictionary config)) { throw PdfObjects.invalid(); } configuration(config); } }
    }
    private void intent(COSBase value) throws IOException {
        if (value == null || value instanceof COSName) { return; }
        if (!(value instanceof COSArray array)) { throw PdfObjects.invalid(); }
        for (int i = 0; i < array.size(); i++) { tick(0); if (!(array.getObject(i) instanceof COSName)) { throw PdfObjects.invalid(); } }
    }
    private void usage(COSDictionary usage) throws IOException {
        if (usage == null) { return; }
        keys(usage, Set.of("CreatorInfo", "Language", "Export", "Zoom", "Print", "View", "User", "PageElement"));
        for (var key : usage.keySet()) {
            var item = dictionary(usage, key.getName(), true); tick(0);
            switch (key.getName()) {
                case "CreatorInfo" -> { keys(item, Set.of("Creator", "Subtype")); text(item, "Creator", true); name(item, "Subtype", "", null); }
                case "Language" -> { keys(item, Set.of("Lang", "Preferred")); text(item, "Lang", true); name(item, "Preferred", "OFF", Set.of("ON", "OFF")); }
                case "View" -> { keys(item, Set.of("ViewState")); name(item, "ViewState", "ON", Set.of("ON", "OFF")); }
                case "Export" -> { keys(item, Set.of("ExportState")); name(item, "ExportState", "ON", Set.of("ON", "OFF")); }
                case "Print" -> { keys(item, Set.of("Subtype", "PrintState")); name(item, "Subtype", "", null); name(item, "PrintState", "ON", Set.of("ON", "OFF")); }
                case "PageElement" -> { keys(item, Set.of("Subtype")); name(item, "Subtype", "", Set.of("HF", "FG", "BG", "L")); }
                case "Zoom" -> { keys(item, Set.of("min", "max")); for (String bound : Set.of("min", "max")) { var number = value(item, bound); if (number != null && (!(number instanceof COSNumber n) || !Float.isFinite(n.floatValue()) || n.floatValue() < 0)) { throw PdfObjects.invalid(); } } }
                case "User" -> { keys(item, Set.of("Type", "Name")); name(item, "Type", "", Set.of("Ind", "Ttl", "Org")); var names = value(item, "Name"); if (!(names instanceof COSString) && !(names instanceof COSArray)) { throw PdfObjects.invalid(); } if (names instanceof COSArray values) { for (int i = 0; i < values.size(); i++) { tick(0); if (!(values.getObject(i) instanceof COSString)) { throw PdfObjects.invalid(); } } } }
                default -> throw PdfObjects.unsupported();
            }
        }
    }
    private void configuration(COSDictionary config) throws IOException {
        tick(0); keys(config, Set.of("Name", "Creator", "BaseState", "ON", "OFF", "Intent", "AS", "Order", "ListMode", "RBGroups", "Locked"));
        text(config, "Name", false); text(config, "Creator", false); intent(value(config, "Intent"));
        name(config, "BaseState", "ON", Set.of("ON", "OFF", "Unchanged")); name(config, "ListMode", "AllPages", Set.of("AllPages", "VisiblePages"));
        var on = groupList(value(config, "ON"), false); var off = groupList(value(config, "OFF"), false);
        if (on.stream().anyMatch(off::contains)) { throw PdfObjects.invalid(); }
        groupList(value(config, "Locked"), false); order(value(config, "Order"), identities(), 0);
        var radio = array(config, "RBGroups", false); if (radio != null) { for (int i = 0; i < radio.size(); i++) { groupList(radio.getObject(i), false); } }
        var automatic = array(config, "AS", false);
        if (automatic != null) { for (int i = 0; i < automatic.size(); i++) {
            if (!(automatic.getObject(i) instanceof COSDictionary item)) { throw PdfObjects.invalid(); }
            keys(item, Set.of("Event", "OCGs", "Category")); name(item, "Event", "", Set.of("View", "Print", "Export"));
            groupList(value(item, "OCGs"), false); var categories = array(item, "Category", true);
            for (int j = 0; j < categories.size(); j++) { tick(0); if (!(categories.getObject(j) instanceof COSName category) || !Set.of("View", "Print", "Export", "Zoom", "User", "Language").contains(category.getName())) { throw PdfObjects.unsupported(); } }
        } }
    }
    private List<COSDictionary> groupList(COSBase value, boolean single) throws IOException {
        if (value == null) { return List.of(); } var result = new ArrayList<COSDictionary>();
        if (single && value instanceof COSDictionary group) { requireGroup(group); return List.of(group); }
        if (!(value instanceof COSArray array)) { throw PdfObjects.invalid(); }
        for (int i = 0; i < array.size(); i++) { tick(0); if (!(array.getObject(i) instanceof COSDictionary group)) { throw PdfObjects.invalid(); } requireGroup(group); if (result.contains(group)) { throw PdfObjects.invalid(); } result.add(group); } return result;
    }
    private void requireGroup(COSDictionary group) throws IOException { if (!groups.contains(group)) { throw PdfObjects.invalid(); } }
    private void stateGroups(COSDictionary config, String key, boolean state) throws IOException { for (var group : groupList(value(config, key), false)) { enabled.put(group, state); } }
    private void order(COSBase value, Set<COSDictionary> active, int depth) throws IOException {
        if (value == null) { return; } tick(depth);
        if (value instanceof COSString) { return; }
        if (value instanceof COSDictionary group) { requireGroup(group); return; }
        if (!(value instanceof COSArray array)) { throw PdfObjects.invalid(); }
        for (int i = 0; i < array.size(); i++) { order(array.getObject(i), active, depth + 1); }
    }
    private boolean optional(COSDictionary value, Set<COSDictionary> active, int depth) throws IOException {
        tick(depth); var cached = visibility.get(value); if (cached != null) { return cached; }
        if (groups.contains(value)) { return enabled.get(value); }
        if (!active.add(value)) { throw PdfObjects.invalid(); }
        try {
            keys(value, Set.of("Type", "OCGs", "P", "VE"));
            if (!name(value, "Type", "", null).equals("OCMD")) { throw PdfObjects.invalid(); }
            var values = groupList(value(value, "OCGs"), true);
            String policy = name(value, "P", "AnyOn", Set.of("AnyOn", "AllOn", "AnyOff", "AllOff"));
            boolean result;
            if (value.containsKey(COSName.getPDFName("VE"))) { result = expression(value(value, "VE"), depth + 1); }
            else {
                if (values.isEmpty()) { throw PdfObjects.invalid(); }
                result = switch (policy) {
                    case "AllOn" -> values.stream().allMatch(enabled::get);
                    case "AnyOff" -> values.stream().anyMatch(group -> !enabled.get(group));
                    case "AllOff" -> values.stream().noneMatch(enabled::get);
                    default -> values.stream().anyMatch(enabled::get);
                };
            }
            visibility.put(value, result); return result;
        } finally { active.remove(value); }
    }
    private boolean expression(COSBase value, int depth) throws IOException {
        tick(depth); if (value instanceof COSDictionary group) { requireGroup(group); return enabled.get(group); }
        if (!(value instanceof COSArray array) || array.size() < 2 || !(array.getObject(0) instanceof COSName operator)) { throw PdfObjects.invalid(); }
        String name = operator.getName(); if (!Set.of("And", "Or", "Not").contains(name) || name.equals("Not") && array.size() != 2) { throw PdfObjects.invalid(); }
        boolean result = !name.equals("Or");
        for (int i = 1; i < array.size(); i++) { boolean item = expression(array.getObject(i), depth + 1); result = name.equals("Not") ? !item : name.equals("And") ? result && item : result || item; } return result;
    }

    private void owner(COSDictionary dictionary, String key) throws IOException {
        if (value(dictionary, key) == null) { return; } tick(0); int index = integer(value(dictionary, key));
        if (value(dictionary, "StructParent") != null && value(dictionary, "StructParents") != null) { throw PdfObjects.invalid(); }
        var previous = owners.putIfAbsent(index, dictionary); if (previous != null && previous != dictionary) { throw PdfObjects.invalid(); }
    }
    private void resources(PDResources resources, COSDictionary page, Set<COSDictionary> active, int depth) throws IOException {
        if (resources == null) { return; } tick(depth); var dictionary = resources.getCOSObject();
        if (!active.add(dictionary)) { throw PdfObjects.invalid(); }
        try {
            for (var name : resources.getXObjectNames()) {
                var object = resources.getXObject(name); if (object == null) { throw PdfObjects.invalid(); }
                var raw = object.getCOSObject(); objectPages.computeIfAbsent(raw, ignored -> identities()).add(page);
                owner(raw, "StructParents"); owner(raw, "StructParent");
                if (object instanceof org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form) {
                    var local = form.getResources() == null ? resources : form.getResources();
                    if (content.add(raw)) { commands(List.of(raw), raw, local); }
                    if (form.getResources() != null) { resources(form.getResources(), page, active, depth + 1); }
                }
            }
        } finally { active.remove(dictionary); }
    }
    private void commands(List<COSStream> streams, COSDictionary owner, PDResources resources) throws IOException {
        int nesting = 0;
        for (var stream : streams) {
            var parser = new PDFStreamParser(new PdfContentStream(stream));
            try {
                Object token; var operands = new ArrayList<COSBase>();
                while ((token = parser.parseNextToken()) != null) {
                    tick(0);
                    if (token instanceof Operator operator) {
                        if (++operators > limits.maxContentOperators()) { throw failure(PdfOperationException.Code.OPERATOR_LIMIT); }
                        String name = operator.getName();
                        if (name.equals("BMC") || name.equals("BDC")) {
                            if (operands.size() != (name.equals("BMC") ? 1 : 2) || !(operands.getFirst() instanceof COSName)) { throw PdfObjects.invalid(); }
                            if (++nesting > limits.maxDepth()) { throw failure(PdfOperationException.Code.DEPTH_LIMIT); }
                            if (name.equals("BDC")) {
                                COSBase properties = operands.get(1);
                                if (properties instanceof COSName propertyName) {
                                    if (resources == null || !(resources.getCOSObject().getDictionaryObject(COSName.PROPERTIES) instanceof COSDictionary entries)) { throw PdfObjects.invalid(); }
                                    properties = entries.getDictionaryObject(propertyName);
                                }
                                if (!(properties instanceof COSDictionary property)) { throw PdfObjects.invalid(); }
                                if (operands.getFirst().equals(COSName.OC)) { optional(property, identities(), 0); }
                                if (value(property, "MCID") != null) {
                                    int mcid = integer(value(property, "MCID"));
                                    if (value(owner, "StructParents") == null || !marked.computeIfAbsent(owner, ignored -> new HashSet<>()).add(mcid)) { throw PdfObjects.invalid(); }
                                }
                            }
                        } else if (name.equals("EMC")) { if (!operands.isEmpty() || --nesting < 0) { throw PdfObjects.invalid(); } }
                        operands.clear();
                    } else if (token instanceof COSBase operand) { operands.add(operand); }
                    else { throw PdfObjects.invalid(); }
                }
                if (!operands.isEmpty()) { throw PdfObjects.invalid(); }
            } finally { parser.close(); }
        }
        if (nesting != 0) { throw PdfObjects.invalid(); }
    }
    private void walk(COSBase value, Set<COSDictionary> active, int depth) throws IOException {
        tick(depth);
        if (value instanceof org.apache.pdfbox.cos.COSObject object) { walk(object.getObject(), active, depth + 1); }
        else if (value instanceof COSArray array) { for (int i = 0; i < array.size(); i++) { walk(array.getObject(i), active, depth + 1); } }
        else if (value instanceof COSDictionary dictionary && graph.add(dictionary)) {
            String type = dictionary.getNameAsString(COSName.TYPE, "");
            if (type.equals("OCG")) { requireGroup(dictionary); }
            if (type.equals("OCMD")) { optional(dictionary, identities(), 0); }
            var oc = value(dictionary, "OC"); if (oc != null) { if (!(oc instanceof COSDictionary optional)) { throw PdfObjects.invalid(); } optional(optional, identities(), 0); }
            for (var entry : dictionary.entrySet()) {
                walk(entry.getValue(), active, depth + 1);
            }
        }
    }
    private void structure(COSDictionary root) throws IOException {
        tick(0); structural.add(root); keys(root, Set.of("Type", "K", "IDTree", "ParentTree", "ParentTreeNextKey", "RoleMap", "ClassMap"));
        if (!name(root, "Type", "StructTreeRoot", null).equals("StructTreeRoot")) { throw PdfObjects.invalid(); }
        roleMap = dictionary(root, "RoleMap", false);
        if (roleMap != null) { for (var key : roleMap.keySet()) { if (!(roleMap.getDictionaryObject(key) instanceof COSName mapped)) { throw PdfObjects.invalid(); } role(mapped.getName()); } }
        var classes = dictionary(root, "ClassMap", false); if (classes != null) { passive(classes, identities(), 0); }
        var tree = dictionary(root, "ParentTree", !owners.isEmpty()); if (tree != null) { numberTree(tree, identities(), 0); }
        children(value(root, "K"), root, null, identities(), 0);
        for (var references : elementReferences) { for (int i = 0; i < references.size(); i++) { if (!(references.getObject(i) instanceof COSDictionary element) || !elements.contains(element)) { throw PdfObjects.invalid(); } } }
        for (var entry : owners.entrySet()) {
            var parent = parents.get(entry.getKey()); if (parent == null) { throw PdfObjects.invalid(); }
            var object = entry.getValue();
            if (value(object, "StructParent") != null) { if (parent != objectAssociations.get(object)) { throw PdfObjects.invalid(); } }
            else {
                if (!(parent instanceof COSArray array)) { throw PdfObjects.invalid(); }
                var linked = associated.getOrDefault(object, Map.of()); var painted = marked.getOrDefault(object, Set.of());
                if (!painted.equals(linked.keySet())) { throw PdfObjects.invalid(); }
                for (int i = 0; i < array.size(); i++) {
                    var element = array.getObject(i);
                    if (element == COSNull.NULL) { if (linked.containsKey(i)) { throw PdfObjects.invalid(); } }
                    else if (element != linked.get(i)) { throw PdfObjects.invalid(); }
                }
                if (linked.keySet().stream().anyMatch(index -> index >= array.size())) { throw PdfObjects.invalid(); }
            }
        }
        if (!parents.keySet().equals(owners.keySet())) { throw PdfObjects.invalid(); }
        if (value(root, "ParentTreeNextKey") != null) { int next = integer(value(root, "ParentTreeNextKey")); if (!parents.isEmpty() && next <= Collections.max(parents.keySet())) { throw PdfObjects.invalid(); } }
        var idsTree = dictionary(root, "IDTree", false); if (idsTree != null) { var declared = new HashMap<String, COSDictionary>(); names(idsTree, declared, identities(), 0); if (!declared.equals(ids)) { throw PdfObjects.invalid(); } }
    }
    private void role(String name) throws IOException {
        var visited = new HashSet<String>();
        while (!STANDARD_ROLES.contains(name)) {
            tick(visited.size()); if (!visited.add(name) || roleMap == null || !(roleMap.getDictionaryObject(COSName.getPDFName(name)) instanceof COSName replacement)) { throw PdfObjects.invalid(); } name = replacement.getName();
        }
    }
    private void children(COSBase value, COSDictionary parent, COSDictionary page, Set<COSDictionary> active, int depth) throws IOException {
        if (value == null) { return; } tick(depth);
        if (value instanceof COSArray array) { for (int i = 0; i < array.size(); i++) { children(array.getObject(i), parent, page, active, depth + 1); } return; }
        if (value instanceof COSInteger) { if (page == null) { throw PdfObjects.invalid(); } associate(page, integer(value), parent); return; }
        if (!(value instanceof COSDictionary dictionary) || !active.add(dictionary)) { throw PdfObjects.invalid(); }
        try {
            String type = name(dictionary, "Type", "StructElem", null); structural.add(dictionary);
            var explicitPage = dictionary(dictionary, "Pg", false); if (explicitPage != null) { if (!pages.contains(explicitPage)) { throw PdfObjects.invalid(); } page = explicitPage; }
            if (type.equals("MCR")) {
                keys(dictionary, Set.of("Type", "Pg", "Stm", "StmOwn", "MCID"));
                var stream = dictionary(dictionary, "Stm", false);
                if (value(dictionary, "StmOwn") != null) { throw PdfObjects.unsupported(); }
                COSDictionary owner = stream == null ? page : stream;
                if (owner == null || page == null || stream != null && (!objectPages.containsKey(stream) || !objectPages.get(stream).contains(page))) { throw PdfObjects.invalid(); }
                associate(owner, integer(value(dictionary, "MCID")), parent); return;
            }
            if (type.equals("OBJR")) {
                keys(dictionary, Set.of("Type", "Pg", "Obj")); var object = dictionary(dictionary, "Obj", true);
                if (page == null || !objectPages.getOrDefault(object, Set.of()).contains(page) || value(object, "StructParent") == null || objectAssociations.put(object, parent) != null) { throw PdfObjects.invalid(); } return;
            }
            if (!type.equals("StructElem") || value(dictionary, "P") != parent || associatedElement(dictionary)) { throw PdfObjects.invalid(); }
            keys(dictionary, Set.of("Type", "S", "P", "ID", "Pg", "K", "A", "C", "R", "T", "Lang", "Alt", "E", "ActualText", "Ref"));
            role(name(dictionary, "S", "", null));
            for (String key : Set.of("ID", "T", "Lang", "Alt", "E", "ActualText")) { text(dictionary, key, false); }
            if (value(dictionary, "ID") instanceof COSString id && ids.put(HexFormat.of().formatHex(id.getBytes()), dictionary) != null) { throw PdfObjects.invalid(); }
            if (value(dictionary, "R") != null) { integer(value(dictionary, "R")); }
            passive(value(dictionary, "A"), identities(), 0); passive(value(dictionary, "C"), identities(), 0);
            var references = array(dictionary, "Ref", false); if (references != null) { elementReferences.add(references); }
            children(value(dictionary, "K"), dictionary, page, active, depth + 1);
        } finally { active.remove(dictionary); }
    }
    private final Set<COSDictionary> elements = identities();
    private boolean associatedElement(COSDictionary element) { return !elements.add(element); }
    private void associate(COSDictionary owner, int mcid, COSDictionary element) throws IOException {
        if (value(owner, "StructParents") == null || associated.computeIfAbsent(owner, ignored -> new HashMap<>()).put(mcid, element) != null) { throw PdfObjects.invalid(); }
    }
    private int[] numberTree(COSDictionary dictionary, Set<COSDictionary> active, int depth) throws IOException {
        tick(depth); if (!active.add(dictionary)) { throw PdfObjects.invalid(); } structural.add(dictionary);
        try {
            keys(dictionary, Set.of("Kids", "Nums", "Limits")); var nums = array(dictionary, "Nums", false); var kids = array(dictionary, "Kids", false);
            if ((nums == null) == (kids == null)) { throw PdfObjects.invalid(); }
            int previous = -1; int minimum = Integer.MAX_VALUE;
            if (nums != null) {
                if (nums.size() % 2 != 0) { throw PdfObjects.invalid(); }
                for (int i = 0; i < nums.size(); i += 2) { tick(depth); int key = integer(nums.getObject(i)); if (key <= previous || parents.put(key, nums.getObject(i + 1)) != null) { throw PdfObjects.invalid(); } previous = key; minimum = Math.min(minimum, key); }
            } else { for (int i = 0; i < kids.size(); i++) {
                if (!(kids.getObject(i) instanceof COSDictionary child)) { throw PdfObjects.invalid(); }
                int[] range = numberTree(child, active, depth + 1);
                if (range[0] <= previous || range[1] < range[0]) { throw PdfObjects.invalid(); }
                minimum = Math.min(minimum, range[0]); previous = range[1];
            } }
            var bounds = array(dictionary, "Limits", false); if (bounds != null && (bounds.size() != 2 || integer(bounds.getObject(0)) != minimum || integer(bounds.getObject(1)) != previous)) { throw PdfObjects.invalid(); }
            return new int[]{minimum, previous};
        } finally { active.remove(dictionary); }
    }
    private String[] names(COSDictionary dictionary, Map<String, COSDictionary> result, Set<COSDictionary> active, int depth) throws IOException {
        tick(depth); if (!active.add(dictionary)) { throw PdfObjects.invalid(); }
        try {
            keys(dictionary, Set.of("Kids", "Names", "Limits")); var names = array(dictionary, "Names", false); var kids = array(dictionary, "Kids", false);
            if ((names == null) == (kids == null)) { throw PdfObjects.invalid(); }
            String minimum = null; String previous = null;
            if (names != null) { if (names.size() % 2 != 0) { throw PdfObjects.invalid(); } for (int i = 0; i < names.size(); i += 2) {
                tick(depth); if (!(names.getObject(i) instanceof COSString key) || !(names.getObject(i + 1) instanceof COSDictionary element)) { throw PdfObjects.invalid(); }
                String encoded = HexFormat.of().formatHex(key.getBytes());
                if (previous != null && encoded.compareTo(previous) <= 0 || result.put(encoded, element) != null) { throw PdfObjects.invalid(); }
                if (minimum == null) { minimum = encoded; } previous = encoded;
            } }
            else { for (int i = 0; i < kids.size(); i++) {
                if (!(kids.getObject(i) instanceof COSDictionary child)) { throw PdfObjects.invalid(); }
                String[] range = names(child, result, active, depth + 1);
                if (range[0] == null || previous != null && range[0].compareTo(previous) <= 0) { throw PdfObjects.invalid(); }
                if (minimum == null) { minimum = range[0]; } previous = range[1];
            } }
            var bounds = array(dictionary, "Limits", false);
            if (bounds != null && (bounds.size() != 2 || !(bounds.getObject(0) instanceof COSString low) || !(bounds.getObject(1) instanceof COSString high)
                    || !HexFormat.of().formatHex(low.getBytes()).equals(minimum) || !HexFormat.of().formatHex(high.getBytes()).equals(previous))) { throw PdfObjects.invalid(); }
            return new String[]{minimum, previous};
        } finally { active.remove(dictionary); }
    }
    private void passive(COSBase value, Set<COSDictionary> active, int depth) throws IOException {
        if (value == null) { return; } tick(depth);
        if (value instanceof COSArray array) { for (int i = 0; i < array.size(); i++) { passive(array.getObject(i), active, depth + 1); } }
        else if (value instanceof COSDictionary dictionary) {
            if (!active.add(dictionary)) { throw PdfObjects.invalid(); }
            try {
                if (Set.of("Action", "Filespec", "EmbeddedFile").contains(dictionary.getNameAsString(COSName.TYPE, "")) || ACTIVE.contains(dictionary.getNameAsString(COSName.S, ""))) { throw PdfObjects.unsupported(); }
                for (var entry : dictionary.entrySet()) { if (ACTIVE.contains(entry.getKey().getName())) { throw PdfObjects.unsupported(); } passive(dictionary.getDictionaryObject(entry.getKey()), active, depth + 1); }
            } finally { active.remove(dictionary); }
        }
    }
}
