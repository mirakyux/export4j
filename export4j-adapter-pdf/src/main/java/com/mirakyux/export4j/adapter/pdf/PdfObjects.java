// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExternalDestination;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDChoice;
import org.apache.pdfbox.pdmodel.interactive.form.PDNonTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;

/** Immutable read projections of a bounded passive PDF subset. Strings are source content returned
 * only to the caller, never diagnostics. Geometry remains PDF-native, unrotated user units.
 */
public final class PdfObjects {
    private PdfObjects() { }
    public record Field(String name, String type, String value, List<String> options, boolean readOnly,
            List<String> values, boolean multiline, boolean comb, boolean richText, boolean multiSelect,
            int maxLength, String richValue) {
        public Field { options = List.copyOf(options); values=List.copyOf(values); java.util.Objects.requireNonNull(richValue); }
        public Field(String name,String type,String value,List<String> options,boolean readOnly){this(name,type,value,options,readOnly,List.of(value),false,false,false,false,0,"");}
    }
    public record Annotation(int page, int index, String type, PdfAdapter.Box box, String text, String uri, int targetPage,
            List<PdfAnnotationSpec.Quad> quads,List<List<PdfAnnotationSpec.Point>> ink,int strokeRgb,int fillRgb,double width,double opacity) {
        public Annotation{quads=List.copyOf(quads);ink=ink.stream().map(List::copyOf).toList();}
        public Annotation(int page,int index,String type,PdfAdapter.Box box,String text,String uri,int targetPage){this(page,index,type,box,text,uri,targetPage,List.of(),List.of(),0,-1,1,1);}
    }
    public record Bookmark(String title, int page, List<Bookmark> children) {
        public Bookmark { java.util.Objects.requireNonNull(title, "title"); children = List.copyOf(children); }
    }
    /** Opaque attachment bytes. No implicit extraction to a filesystem or execution. */
    public static final class Attachment {
        private final String name; private final String mediaType; private final byte[] bytes;
        Attachment(String name, String mediaType, byte[] bytes) { this.name = name; this.mediaType = mediaType; this.bytes = bytes; }
        public String name() { return name; }
        public String mediaType() { return mediaType; }
        public int byteSize() { return bytes.length; }
        public byte[] bytes() { return bytes.clone(); }
    }
    public record Snapshot(int pageCount, Map<String, String> metadata, List<Field> fields,
            List<Annotation> annotations, List<Bookmark> bookmarks, List<Attachment> attachments) {
        public Snapshot { metadata = Map.copyOf(metadata); fields = List.copyOf(fields); annotations = List.copyOf(annotations); bookmarks = List.copyOf(bookmarks); attachments = List.copyOf(attachments); }
    }
    static Snapshot inspect(PDDocument document, PdfLimits limits) throws IOException {
        var count = new Counter(limits); var metadata = new LinkedHashMap<String, String>();
        for (String key : List.of("Title", "Author", "Subject", "Keywords", "Creator", "Producer")) {
            String value = document.getDocumentInformation().getCustomMetadataValue(key);
            if (value != null) { count.text(value); metadata.put(key, value); }
        }
        if (document.getDocumentCatalog().getCOSObject().containsKey(COSName.ACRO_FORM)
                && document.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.ACRO_FORM) == null) { throw invalid(); }
        var form = document.getDocumentCatalog().getAcroForm(null);
        Set<COSDictionary> fieldNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<COSDictionary> fieldWidgets = Collections.newSetFromMap(new IdentityHashMap<>());
        var fields = new ArrayList<Field>();
        if (form != null) {
            var roots = form.getCOSObject().getCOSArray(COSName.FIELDS);
            if (roots == null) { throw invalid(); }
            for (int index = 0; index < roots.size(); index++) {
                if (!(roots.getObject(index) instanceof COSDictionary root)) { throw invalid(); }
                fieldTree(root, null, fieldNodes, fieldWidgets, count, 0);
            }
            var names = new HashSet<String>();
            for (var field : form.getFieldTree()) {
                count.node();
                if (field instanceof PDNonTerminalField) { continue; }
                String type = field instanceof PDTextField ? "TEXT" : field instanceof PDCheckBox ? "CHECKBOX"
                        : field instanceof PDRadioButton ? "RADIO" : field instanceof PDChoice ? "CHOICE" : "";
                if (type.isEmpty()) { throw unsupported(); }
                if(field.getCOSObject().containsKey(COSName.FF)&&!(field.getCOSObject().getDictionaryObject(COSName.FF)instanceof COSInteger)){throw invalid();}
                if(field instanceof PDTextField){var value=field.getCOSObject().getDictionaryObject(COSName.V);if(value!=null&&!(value instanceof COSString)){throw invalid();}var maximum=field.getCOSObject().getDictionaryObject(COSName.MAX_LEN);if(maximum!=null&&!(maximum instanceof COSInteger)){throw invalid();}}
                String name = field.getFullyQualifiedName();
                if (name == null || name.isBlank() || !names.add(name)) { throw invalid(); }
                var options = field instanceof PDChoice choice ? choice.getOptionsExportValues()
                        : field instanceof PDRadioButton radio ? radio.getOnValues().stream().sorted().toList() : List.<String>of();
                count.text(name); count.text(field.getValueAsString()); for (String option : options) { count.text(option); }
                var values=field instanceof PDChoice choice?choice.getValue():List.of(field.getValueAsString());
                boolean multiline=false,comb=false,rich=false;int maxLength=0;String richValue="";
                if(field instanceof PDTextField text){multiline=text.isMultiline();comb=text.isComb();rich=text.isRichText();maxLength=text.getMaxLen();richValue=text.getRichTextValue();if(richValue==null){richValue="";}count.text(richValue);if(!richValue.isEmpty()){PdfRichText.validate(richValue,limits);}
                    if(maxLength<0&&text.getCOSObject().containsKey(COSName.MAX_LEN)||comb&&(maxLength<=0||multiline||text.isPassword()||text.isFileSelect())){throw invalid();}if(maxLength<0){maxLength=0;}}
                for(String value:values){count.text(value);}fields.add(new Field(name,type,field.getValueAsString(),options,field.isReadOnly(),values,multiline,comb,rich,field instanceof PDChoice choice&&choice.isMultiSelect(),maxLength,richValue));
            }
        }
        var annotations = new ArrayList<Annotation>(); Set<COSDictionary> seenWidgets = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<COSDictionary> seenAnnotations = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
            var page = document.getPage(pageIndex);
            if (page.getCOSObject().containsKey(COSName.ANNOTS) && !(page.getCOSObject().getDictionaryObject(COSName.ANNOTS) instanceof COSArray)) { throw invalid(); }
            var values = page.getAnnotations();
            for (int index = 0; index < values.size(); index++) {
                count.node(); var annotation = values.get(index);
                if (!seenAnnotations.add(annotation.getCOSObject())) { throw invalid(); }
                if (annotation.getPage() != null && annotation.getPage().getCOSObject() != page.getCOSObject()) { throw invalid(); }
                var rectangle = annotation.getRectangle();
                if (rectangle == null || !Float.isFinite(rectangle.getLowerLeftX()) || !Float.isFinite(rectangle.getLowerLeftY())
                        || !Float.isFinite(rectangle.getWidth()) || !Float.isFinite(rectangle.getHeight()) || rectangle.getWidth() <= 0 || rectangle.getHeight() <= 0) { throw invalid(); }
                String type; String uri = ""; int target = -1;PdfAnnotationSpec specification=null;
                if (annotation instanceof PDAnnotationWidget) {
                    if (!fieldWidgets.contains(annotation.getCOSObject()) || !seenWidgets.add(annotation.getCOSObject())) { throw invalid(); } type = "WIDGET";
                } else if (annotation instanceof PDAnnotationText) { type = "TEXT"; }
                else if (annotation instanceof PDAnnotationLink link) {
                    type = "LINK"; var action = link.getAction();
                    if (action != null && link.getDestination() != null) { throw invalid(); }
                    if (action instanceof PDActionURI external) { uri = external.getURI(); new ExternalDestination(uri); }
                    else if (action instanceof PDActionGoTo internal) { target = destination(document, internal.getDestination()); }
                    else if (action != null) { throw unsupported(); }
                    else if (link.getDestination() != null) { target = destination(document, link.getDestination()); }
                    else { throw invalid(); }
                } else if(PdfPassiveAnnotations.supported(annotation.getSubtype())){specification=PdfPassiveAnnotations.inspect(annotation,limits);type=specification.type().name();}
                else { throw unsupported(); }
                String text = annotation.getContents() == null ? "" : annotation.getContents(); count.text(text); count.text(uri);
                var box=new PdfAdapter.Box(rectangle.getLowerLeftX(),rectangle.getLowerLeftY(),rectangle.getWidth(),rectangle.getHeight());
                annotations.add(specification==null?new Annotation(pageIndex,index,type,box,text,uri,target):new Annotation(pageIndex,index,type,box,text,uri,target,specification.quads(),specification.ink(),specification.strokeRgb(),specification.fillRgb(),specification.width(),specification.opacity()));
            }
        }
        if (!seenWidgets.equals(fieldWidgets)) { throw invalid(); }
        var outline = document.getDocumentCatalog().getDocumentOutline();
        var bookmarks = outline == null ? List.<Bookmark>of() : outline(document, outline.getFirstChild(),
                outline.getCOSObject(), Collections.newSetFromMap(new IdentityHashMap<>()), count, 0);
        var attachments = new ArrayList<Attachment>(); long attachmentBytes = 0;
        for (var entry : files(document, limits).entrySet()) {
            count.node(); count.text(entry.getKey()); var specification = entry.getValue();
            var file = specification.getEmbeddedFileUnicode(); if (file == null) { file = specification.getEmbeddedFile(); }
            if (file == null) { throw unsupported(); }
            byte[] bytes;
            try (var stream = file.createInputStream()) {
                bytes = stream.readNBytes((int) Math.min(limits.maxDecodedStreamBytes(), Integer.MAX_VALUE - 9L) + 1);
            }
            attachmentBytes += bytes.length;
            if (bytes.length > limits.maxDecodedStreamBytes() || attachmentBytes > limits.maxDecodedBytes()) { throw new PdfOperationException(PdfOperationException.Code.DECODED_STREAM_LIMIT, 0, -1); }
            attachments.add(new Attachment(entry.getKey(), file.getSubtype() == null ? "" : file.getSubtype(), bytes));
        }
        return new Snapshot(document.getNumberOfPages(), metadata, fields, annotations, bookmarks, attachments);
    }
    private static void fieldTree(COSDictionary field, COSDictionary parent, Set<COSDictionary> seen,
            Set<COSDictionary> widgets, Counter count, int depth) throws IOException {
        count.depth(depth); count.node();
        if (!seen.add(field) || field.getCOSDictionary(COSName.PARENT) != parent) { throw invalid(); }
        if ("Widget".equals(field.getNameAsString(COSName.SUBTYPE))) { widgets.add(field); }
        var children = field.getCOSArray(COSName.KIDS);
        if (field.containsKey(COSName.KIDS) && children == null || children != null && widgets.contains(field)) { throw invalid(); }
        if (children == null) { return; }
        for (int i = 0; i < children.size(); i++) {
            if (!(children.getObject(i) instanceof COSDictionary child)) { throw invalid(); }
            fieldTree(child, field, seen, widgets, count, depth + 1);
        }
    }
    private static List<Bookmark> outline(PDDocument document, PDOutlineItem item, COSDictionary parent,
            Set<COSDictionary> seen, Counter count, int depth) throws IOException {
        count.depth(depth); var result = new ArrayList<Bookmark>(); COSDictionary previous = null;
        for (; item != null; item = item.getNextSibling()) {
            count.node(); var dictionary = item.getCOSObject();
            if (!seen.add(dictionary) || dictionary.getCOSDictionary(COSName.PARENT) != parent || dictionary.getCOSDictionary(COSName.PREV) != previous) { throw invalid(); }
            PDDestination destination = item.getDestination(); var action = item.getAction();
            if (action != null && destination != null) { throw invalid(); }
            if (action instanceof PDActionGoTo goTo) { destination = goTo.getDestination(); }
            else if (action != null) { throw unsupported(); }
            int page = destination == null ? -1 : destination(document, destination);
            String title = item.getTitle(); if (title == null) { throw invalid(); } count.text(title);
            result.add(new Bookmark(title, page, outline(document, item.getFirstChild(), dictionary, seen, count, depth + 1))); previous = dictionary;
        }
        return result;
    }
    static int destination(PDDocument document, PDDestination value) throws IOException {
        if (!(value instanceof PDPageDestination page)) { throw unsupported(); }
        int index = page.getPage() == null ? page.getPageNumber() : document.getPages().indexOf(page.getPage());
        if (index < 0 || index >= document.getNumberOfPages()) { throw invalid(); }
        return index;
    }
    static Map<String, PDComplexFileSpecification> files(PDDocument document, PdfLimits limits) throws IOException {
        var names = document.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.NAMES);
        var result = new TreeMap<String, PDComplexFileSpecification>();
        if (document.getDocumentCatalog().getCOSObject().containsKey(COSName.NAMES) && names == null) { throw invalid(); }
        if (names == null) { return result; }
        for (var key : names.keySet()) { if (!key.equals(COSName.EMBEDDED_FILES)) { throw unsupported(); } }
        var tree = names.getCOSDictionary(COSName.EMBEDDED_FILES); if (tree == null) { throw invalid(); }
        nameTree(tree, result, Collections.newSetFromMap(new IdentityHashMap<>()), new Counter(limits), 0); return result;
    }
    private static void nameTree(COSDictionary tree, Map<String, PDComplexFileSpecification> result,
            Set<COSDictionary> seen, Counter count, int depth) throws IOException {
        count.depth(depth); count.node(); if (!seen.add(tree)) { throw invalid(); }
        var names = tree.getCOSArray(COSName.NAMES); var kids = tree.getCOSArray(COSName.KIDS);
        if (tree.containsKey(COSName.NAMES) && names == null || tree.containsKey(COSName.KIDS) && kids == null) { throw invalid(); }
        if (names != null && kids != null) { throw invalid(); }
        if (names != null) {
            if (names.size() % 2 != 0) { throw invalid(); }
            for (int index = 0; index < names.size(); index += 2) {
                count.node();
                if (!(names.getObject(index) instanceof COSString name) || !(names.getObject(index + 1) instanceof COSDictionary file)) { throw invalid(); }
                if (result.putIfAbsent(name.getString(), new PDComplexFileSpecification(file)) != null) { throw invalid(); }
            }
        }
        if (kids != null) { for (int index = 0; index < kids.size(); index++) {
            if (!(kids.getObject(index) instanceof COSDictionary child)) { throw invalid(); } nameTree(child, result, seen, count, depth + 1);
        } }
    }
    private static final class Counter {
        final PdfLimits limits; int nodes; long text;
        Counter(PdfLimits limits) { this.limits = limits; }
        void node() throws IOException { ExecutionContext.checkpoint(); if (++nodes > limits.maxObjects()) { throw new PdfOperationException(PdfOperationException.Code.OBJECT_LIMIT, 0, -1); } }
        void depth(int value) throws IOException { if (value > limits.maxDepth()) { throw new PdfOperationException(PdfOperationException.Code.DEPTH_LIMIT, 0, -1); } }
        void text(String value) throws IOException {
            ExecutionContext.checkpoint();
            for (int index = 0; index < value.length(); index++) {
                char c = value.charAt(index);
                if (Character.isHighSurrogate(c)) { if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) { throw invalid(); } }
                else if (Character.isLowSurrogate(c)) { throw invalid(); }
            }
            text += value.length(); if (text > limits.maxTextCharacters()) { throw new PdfOperationException(PdfOperationException.Code.TEXT_LIMIT, 0, -1); }
        }
    }
    static IOException invalid() { return new PdfOperationException(PdfOperationException.Code.INVALID_PDF, 0, -1); }
    static IOException unsupported() { return new PdfOperationException(PdfOperationException.Code.UNSUPPORTED_FEATURE, 0, -1); }
}
