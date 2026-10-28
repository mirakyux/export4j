// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.FDFParser;
import org.apache.pdfbox.pdmodel.fdf.FDFDocument;
import org.apache.pdfbox.pdmodel.fdf.FDFField;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Immutable, exact field-name/value exchange. It never locates the source PDF or executes actions. */
public final class PdfFormData {
    public enum Format { FDF, XFDF }
    public record FieldValue(String name,List<String> values,String richText) {
        public FieldValue { Objects.requireNonNull(name);values=List.copyOf(values);Objects.requireNonNull(richText);if(name.isBlank()){throw new IllegalArgumentException("field name required");} }
        public FieldValue(String name,String value){this(name,List.of(value),"");}
    }
    private final List<FieldValue> fields;private final ProcessingReport report;
    PdfFormData(List<FieldValue> fields,List<ProcessingIssue> issues){this.fields=List.copyOf(fields);this.report=new ProcessingReport(ProcessingReport.Phase.ACTUAL,issues);}
    public static PdfFormData of(List<FieldValue> values){var fields=List.copyOf(values);var names=new HashSet<String>();for(var field:fields){if(!names.add(field.name())){throw new IllegalArgumentException("duplicate form field name");}}return new PdfFormData(fields,List.of());}
    public List<FieldValue> fields(){return fields;}
    public ProcessingReport report(){return report;}
    public static PdfFormData parse(byte[] bytes,Format format,PdfLimits limits,ProcessingPolicy policy,ExecutionOptions execution)throws IOException{
        Objects.requireNonNull(bytes);Objects.requireNonNull(format);Objects.requireNonNull(limits);Objects.requireNonNull(policy);
        if(bytes.length>limits.maxInputBytes()||bytes.length>limits.maxTotalInputBytes()){throw new PdfOperationException(PdfOperationException.Code.INPUT_LIMIT,0,-1);}byte[] snapshot=bytes.clone();
        return ExecutionContext.execute(execution,()->{var state=new ReadState(limits,policy);if(format==Format.XFDF){state.xml(snapshot);}else{state.fdf(snapshot);}return new PdfFormData(state.fields,state.issues);});
    }
    public static PdfFormData parse(byte[] bytes,Format format)throws IOException{return parse(bytes,format,PdfLimits.DEFAULT,ProcessingPolicy.STRICT,ExecutionOptions.DEFAULT);}
    static byte[] encode(List<FieldValue> fields,Format format,PdfLimits limits)throws IOException{
        byte[] bytes;
        if(format==Format.XFDF){var text=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><xfdf xmlns=\"http://ns.adobe.com/xfdf/\" xml:space=\"preserve\"><fields>");for(var field:fields){ExecutionContext.checkpoint();text.append("<field name=\"").append(PdfSafeXml.escape(field.name())).append("\">");for(String value:field.values()){text.append("<value>").append(PdfSafeXml.escape(value)).append("</value>");}if(!field.richText().isEmpty()){text.append("<value-richtext>").append(checkedRich(field.richText(),limits)).append("</value-richtext>");}text.append("</field>");if(text.length()>limits.maxOutputBytes()){throw new PdfOperationException(PdfOperationException.Code.OUTPUT_LIMIT,0,-1);}}text.append("</fields></xfdf>");bytes=text.toString().getBytes(StandardCharsets.UTF_8);}
        else{try(var document=new FDFDocument()){var values=new ArrayList<FDFField>();for(var field:fields){ExecutionContext.checkpoint();var value=new FDFField();value.setPartialFieldName(field.name());if(field.values().size()==1){value.setValue(field.values().getFirst());}else{var array=new COSArray();for(String item:field.values()){array.add(new COSString(item));}value.setValue(array);}if(!field.richText().isEmpty()){value.setRichText(new COSString(field.richText()));}values.add(value);}document.getCatalog().getFDF().setFields(values);var output=new ByteArrayOutputStream();document.save(new java.io.OutputStream(){@Override public void write(int value)throws IOException{reserve(1);output.write(value);}@Override public void write(byte[] data,int off,int count)throws IOException{reserve(count);output.write(data,off,count);}private void reserve(int count)throws IOException{ExecutionContext.checkpoint();if(count>Math.min(limits.maxOutputBytes(),Integer.MAX_VALUE-8L)-output.size()){throw new PdfOperationException(PdfOperationException.Code.OUTPUT_LIMIT,0,-1);}}});bytes=output.toByteArray();}}
        if(bytes.length>limits.maxOutputBytes()){throw new PdfOperationException(PdfOperationException.Code.OUTPUT_LIMIT,0,-1);}return bytes;
    }
    static void loss(ProcessingPolicy policy,List<ProcessingIssue> issues,String format,String feature,String operation,ProcessingPolicy.Treatment treatment,String effect){
        var rule=policy.resolve(format,feature,operation);var issue=new ProcessingIssue("FORM_DATA",format,format,feature,operation,new ProcessingIssue.Location("","FORM_DATA","",List.of(),-1,-1),"form data requires the declared treatment",rule.action(),rule.treatment(),List.of(effect));
        if(rule.action()==ProcessingPolicy.Action.ERROR){throw new ProcessingException(issue);}if(rule.treatment()!=treatment){throw new IllegalArgumentException("unsupported form data treatment");}issues.add(issue);
    }
    private static final class ReadState {
        final PdfLimits limits;final ProcessingPolicy policy;final List<FieldValue> fields=new ArrayList<>();final List<ProcessingIssue> issues=new ArrayList<>();final Set<String> names=new HashSet<>();long nodes,text;String format;
        ReadState(PdfLimits limits,ProcessingPolicy policy){this.limits=limits;this.policy=policy;}
        void tick(int depth)throws IOException{ExecutionContext.checkpoint();if(depth>limits.maxDepth()){throw new PdfOperationException(PdfOperationException.Code.DEPTH_LIMIT,0,-1);}if(++nodes>limits.maxObjects()){throw new PdfOperationException(PdfOperationException.Code.OBJECT_LIMIT,0,-1);}}
        void text(String value)throws IOException{for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i))){throw PdfObjects.invalid();}}else if(Character.isLowSurrogate(c)||c==0){throw PdfObjects.invalid();}}text+=value.length();if(text>limits.maxTextCharacters()){throw new PdfOperationException(PdfOperationException.Code.TEXT_LIMIT,0,-1);}}
        void field(String name,List<String> values,String rich)throws IOException{tick(0);text(name);for(String value:values){text(value);}text(rich);if(name.isBlank()||!names.add(name)){throw PdfObjects.invalid();}fields.add(new FieldValue(name,values,rich));}
        void discard(){if(issues.size()>=limits.maxObjects()){throw new IllegalArgumentException("form data issue budget exceeded");}loss(policy,issues,format,"FORM_DATA_PROPERTIES","IMPORT_FORM_DATA",ProcessingPolicy.Treatment.DISCARD,"UNMAPPED_PASSIVE_PROPERTY_DISCARDED");}
        void xml(byte[] bytes)throws IOException{format="XFDF";var document=PdfSafeXml.parse(bytes,limits);var root=document.getDocumentElement();if(!root.getLocalName().equals("xfdf")||!"http://ns.adobe.com/xfdf/".equals(root.getNamespaceURI())){throw PdfObjects.invalid();}attributes(root,Set.of("space"));boolean found=false;for(var child:children(root)){if(child.getLocalName().equals("fields")){if(found){throw PdfObjects.invalid();}found=true;attributes(child,Set.of());for(var item:children(child)){xmlField(item,"",0);}}else if(Set.of("f","annots","ids").contains(child.getLocalName())){if(child.getLocalName().equals("f")){throw PdfObjects.unsupported();}passiveXml(child);discard();}else{passiveXml(child);discard();}}if(!found){throw PdfObjects.invalid();}}
        void xmlField(Element element,String prefix,int depth)throws IOException{tick(depth);if(!"field".equals(element.getLocalName())||!"http://ns.adobe.com/xfdf/".equals(element.getNamespaceURI())){throw PdfObjects.invalid();}attributes(element,Set.of("name"));String partial=element.getAttribute("name");if(partial.isBlank()){throw PdfObjects.invalid();}String name=prefix.isEmpty()?partial:prefix+"."+partial;var values=new ArrayList<String>();String rich="";boolean children=false;for(var child:children(element)){switch(child.getLocalName()){case "field"->{children=true;xmlField(child,name,depth+1);}case "value"->{attributes(child,Set.of());if(!children(child).isEmpty()){throw PdfObjects.invalid();}values.add(child.getTextContent());}case "value-richtext"->{attributes(child,Set.of());if(!rich.isEmpty()){throw PdfObjects.invalid();}var bodies=children(child);if(bodies.size()!=1||!"body".equals(bodies.getFirst().getLocalName())||!"http://www.w3.org/1999/xhtml".equals(bodies.getFirst().getNamespaceURI())){throw PdfObjects.invalid();}rich=PdfRichText.validate(PdfSafeXml.serialize(bodies.getFirst()),limits);}default->{passiveXml(child);discard();}}}if(children&&!values.isEmpty()){throw PdfObjects.invalid();}if(!children){field(name,values,rich);}}
        void attributes(Element element,Set<String> allowed)throws IOException{var attributes=element.getAttributes();for(int i=0;i<attributes.getLength();i++){var attribute=attributes.item(i);if(XMLNS.equals(attribute.getNamespaceURI())){continue;}if(!allowed.contains(attribute.getLocalName()==null?attribute.getNodeName():attribute.getLocalName())){String key=attribute.getNodeName().toLowerCase(java.util.Locale.ROOT);if(key.contains("href")||key.startsWith("on")){throw PdfObjects.unsupported();}discard();}}}
        void passiveXml(Element element)throws IOException{tick(0);String name=element.getLocalName().toLowerCase(java.util.Locale.ROOT);if(name.contains("script")||name.contains("action")||name.equals("f")||name.contains("file")||element.hasAttribute("href")){throw PdfObjects.unsupported();}attributes(element,Set.of());for(var child:children(element)){passiveXml(child);}}
        void fdf(byte[] bytes)throws IOException{format="FDF";try(var source=new RandomAccessReadBuffer(bytes);var document=new BoundedFdf(source,this).parse()){scan(document.getCatalog().getCOSObject(),Collections.newSetFromMap(new IdentityHashMap<>()),0);var root=document.getCatalog().getFDF().getCOSObject();for(var key:root.keySet()){if(!key.equals(COSName.FIELDS)){discard();}}var fields=root.getCOSArray(COSName.FIELDS);if(fields==null){throw PdfObjects.invalid();}for(int i=0;i<fields.size();i++){if(!(fields.getObject(i)instanceof COSDictionary field)){throw PdfObjects.invalid();}fdfField(field,"",Collections.newSetFromMap(new IdentityHashMap<>()),0);}}catch(UncheckedIOException failure){throw failure.getCause();}}
        void fdfField(COSDictionary object,String prefix,Set<COSDictionary> seen,int depth)throws IOException{tick(depth);if(!seen.add(object)||!(object.getDictionaryObject(COSName.T)instanceof COSString name)){throw PdfObjects.invalid();}String full=prefix.isEmpty()?name.getString():prefix+"."+name.getString();for(var key:object.keySet()){if(!Set.of(COSName.T,COSName.V,COSName.RV,COSName.KIDS).contains(key)){discard();}}var kids=object.getDictionaryObject(COSName.KIDS);if(kids!=null){if(!(kids instanceof COSArray array)||object.containsKey(COSName.V)){throw PdfObjects.invalid();}for(int i=0;i<array.size();i++){if(!(array.getObject(i)instanceof COSDictionary child)){throw PdfObjects.invalid();}fdfField(child,full,seen,depth+1);}return;}var values=new ArrayList<String>();var value=object.getDictionaryObject(COSName.V);if(value instanceof COSString string){values.add(string.getString());}else if(value instanceof COSName symbol){values.add(symbol.getName());}else if(value instanceof COSArray array){for(int i=0;i<array.size();i++){if(!(array.getObject(i)instanceof COSString string)){throw PdfObjects.invalid();}values.add(string.getString());}}else{throw PdfObjects.invalid();}var rich=object.getDictionaryObject(COSName.RV);if(rich!=null&&!(rich instanceof COSString)){throw PdfObjects.unsupported();}field(full,values,rich instanceof COSString string?PdfRichText.validate(string.getString(),limits):"");}
        void scan(COSBase raw,Set<COSBase> active,int depth)throws IOException{tick(depth);COSBase value=raw instanceof COSObject object?object.getObject():raw;if(value instanceof COSDictionary||value instanceof COSArray){if(!active.add(value)){throw PdfObjects.invalid();}try{if(value instanceof COSDictionary dictionary){for(var key:dictionary.keySet()){if(Set.of("A","AA","JS","JavaScript","F","EF","EmbeddedFDFs","Differences","Launch","SubmitForm").contains(key.getName())){throw PdfObjects.unsupported();}scan(dictionary.getItem(key),active,depth+1);}}else{for(var item:(COSArray)value){scan(item,active,depth+1);}}}finally{active.remove(value);}}}
    }
    private static String checkedRich(String value,PdfLimits limits)throws IOException{return PdfRichText.validate(value,limits);}
    private static final String XMLNS="http://www.w3.org/2000/xmlns/";
    private static List<Element> children(Element parent){var values=new ArrayList<Element>();for(Node node=parent.getFirstChild();node!=null;node=node.getNextSibling()){if(node instanceof Element element){values.add(element);}}return values;}
    private static final class BoundedFdf extends FDFParser {
        private final ReadState state;private final Set<COSObjectKey> keys=new HashSet<>();int depth;
        BoundedFdf(RandomAccessRead source,ReadState state)throws IOException{super(source);this.state=state;setLenient(false);}
        @Override protected COSObjectKey getObjectKey(long number,int generation){var key=super.getObjectKey(number,generation);if(keys.add(key)&&keys.size()>state.limits.maxObjects()){throw new UncheckedIOException(new PdfOperationException(PdfOperationException.Code.OBJECT_LIMIT,0,-1));}return key;}
        @Override protected COSBase parseDirObject()throws IOException{state.tick(++depth);try{return super.parseDirObject();}finally{depth--;}}
        @Override public COSBase dereferenceCOSObject(COSObject object)throws IOException{try{return super.dereferenceCOSObject(object);}catch(IOException failure){throw new UncheckedIOException(failure);}}
        @Override protected COSStream parseCOSStream(COSDictionary dictionary)throws IOException{throw PdfObjects.unsupported();}
    }
}
