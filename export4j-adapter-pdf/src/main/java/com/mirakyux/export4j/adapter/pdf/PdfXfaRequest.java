// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Offline XFA template/datasets subset. No scripts, SOM execution, submit action or external resource.
 * Geometry in the snapshot uses physical pt from the top-left. Flattening reconstructs fresh pages.
 */
public final class PdfXfaRequest {
    public record Field(String name,String value,int page,PdfAdapter.Box box,boolean editable) { }
    public record Page(double width,double height) { }
    public record Snapshot(boolean dynamic,List<Page> pages,List<Field> fields) {
        public Snapshot { pages=List.copyOf(pages);fields=List.copyOf(fields); }
    }
    public record Result(Snapshot snapshot,ProcessingReport report) { }
    private final PdfAdapter adapter;private final PdfInput input;private final PdfLimits limits;private ProcessingPolicy policy=ProcessingPolicy.STRICT;private ExecutionOptions execution=ExecutionOptions.DEFAULT;
    PdfXfaRequest(PdfAdapter adapter,PdfInput input,PdfLimits limits){this.adapter=adapter;this.input=input;this.limits=limits;}
    public PdfXfaRequest policy(ProcessingPolicy value){policy=Objects.requireNonNull(value);return this;}
    public PdfXfaRequest execution(ExecutionOptions value){execution=Objects.requireNonNull(value);return this;}
    public Result read()throws IOException{var strategy=policy;return ExecutionContext.execute(execution,()->{try(var source=adapter.open(input,adapter.preflightXfa())){if(!source.getCurrentAccessPermission().canExtractContent()){throw new PdfOperationException(PdfOperationException.Code.PERMISSION_DENIED,0,-1);}var state=new State(strategy);state.parse(source);return new Result(state.snapshot(),new ProcessingReport(ProcessingReport.Phase.ACTUAL,state.issues));}});}
    /** Reconstructs the declared subset as fresh, static pages using one explicit complete TrueType.
     * Requires XFA_INTERACTIVITY/DISCARD and XFA_PAGE_RECONSTRUCTION, XFA_FONT_MAPPING/DEGRADE.
     */
    public PreparedPdf flatten(PdfFont font,double size)throws IOException{Objects.requireNonNull(font);if(!Double.isFinite(size)||size<=0||size>10_000){throw new IllegalArgumentException("invalid XFA font size");}var strategy=policy;var control=execution;
        return ExecutionContext.execute(control,()->{try(var source=adapter.openForEdit(input,adapter.preflightXfa());var output=new PDDocument()){var state=new State(strategy);state.parse(source);state.loss("XFA_INTERACTIVITY",ProcessingPolicy.Treatment.DISCARD,"XFA_TEMPLATE_DATA_BINDING_AND_EDITABILITY_REMOVED");state.loss("XFA_PAGE_RECONSTRUCTION",ProcessingPolicy.Treatment.DEGRADE,"DECLARED_TEMPLATE_RECONSTRUCTED_SOURCE_ENVELOPE_NOT_COPIED");state.loss("XFA_FONT_MAPPING",ProcessingPolicy.Treatment.DEGRADE,"EXPLICIT_CALLER_FONT_AND_UNIFORM_TEXT_APPEARANCE_REPLACE_TEMPLATE_STYLE");if(source.isEncrypted()){state.loss("ENCRYPTION_REMOVED",ProcessingPolicy.Treatment.DEGRADE,"OUTPUT_IS_UNENCRYPTED");}
            if(font.bytes().length>limits.maxTotalInputBytes()-input.byteSize()){throw new PdfOperationException(PdfOperationException.Code.INPUT_LIMIT,0,-1);}var actual=PDType0Font.load(output,new ByteArrayInputStream(font.bytes()),false);long work=0;
            for(int index=0;index<state.pages.size();index++){var settings=state.pages.get(index);var page=new PDPage(new PDRectangle((float)settings.width(),(float)settings.height()));output.addPage(page);try(var content=new PDPageContentStream(output,page)){for(var field:state.fields){if(field.page()!=index){continue;}ExecutionContext.checkpoint();if(!PdfPageEditor.simpleText(field.value())){throw PdfObjects.unsupported();}var box=field.box();double innerWidth=box.width()-4,ascent=actual.getFontDescriptor().getAscent()/1000d*size,descent=actual.getFontDescriptor().getDescent()/1000d*size,lineHeight=(ascent-descent)*1.2;var lines=new ArrayList<String>();var line=new StringBuilder();double width=0;
                for(int offset=0;offset<field.value().length();){if(++work>limits.maxContentOperators()){throw new PdfOperationException(PdfOperationException.Code.OPERATOR_LIMIT,0,index);}int cp=field.value().codePointAt(offset);offset+=Character.charCount(cp);if(cp=='\r'){continue;}String text=new String(Character.toChars(cp));double advance=cp=='\n'?0:actual.getStringWidth(text)/1000d*size;if(advance>innerWidth){throw new IllegalArgumentException("XFA glyph exceeds field width");}if(cp=='\n'||width+advance>innerWidth){lines.add(line.toString());line.setLength(0);width=0;if(cp=='\n'){continue;}}line.append(text);width+=advance;}lines.add(line.toString());if(lines.size()*lineHeight>box.height()-4){throw new IllegalArgumentException("XFA value exceeds declared field height");}
                if(field.editable()){content.setStrokingColor(.4f);content.setLineWidth(.5f);content.addRect((float)box.x(),(float)(settings.height()-box.y()-box.height()),(float)box.width(),(float)box.height());content.stroke();}
                double y=settings.height()-box.y()-2-ascent;for(String text:lines){content.beginText();content.setNonStrokingColor(0f);content.setFont(actual,(float)size);content.newLineAtOffset((float)(box.x()+2),(float)y);content.showText(text);content.endText();y-=lineHeight;}
            }}}
            adapter.preflight(false).inspect(output);return PreparedPdf.encode(output,state.fields.size(),state.issues,limits,control);}});
    }
    private final class State {
        final ProcessingPolicy strategy;final List<ProcessingIssue> issues=new ArrayList<>();final List<Page> pages=new ArrayList<>();final List<Field> fields=new ArrayList<>();final Set<String> names=new HashSet<>();Element template,data;boolean dynamic;long decoded,nodes,text;double pageWidth,pageHeight,left,top,areaWidth,areaHeight,cursor;int page;
        State(ProcessingPolicy strategy){this.strategy=strategy;}
        Snapshot snapshot(){return new Snapshot(dynamic,pages,fields);}
        void loss(String feature,ProcessingPolicy.Treatment treatment,String effect){if(issues.size()>=limits.maxObjects()){throw new IllegalArgumentException("XFA issue budget exceeded");}PdfFormData.loss(strategy,issues,"PDF",feature,"FLATTEN_XFA",treatment,effect);}
        void tick(int depth)throws IOException{ExecutionContext.checkpoint();if(depth>limits.maxDepth()){throw new PdfOperationException(PdfOperationException.Code.DEPTH_LIMIT,0,-1);}if(++nodes>limits.maxObjects()){throw new PdfOperationException(PdfOperationException.Code.OBJECT_LIMIT,0,-1);}}
        Element packet(COSStream stream)throws IOException{byte[] bytes;try(var input=stream.createInputStream()){bytes=input.readNBytes((int)Math.min(limits.maxDecodedStreamBytes(),Integer.MAX_VALUE-9L)+1);}decoded+=bytes.length;if(bytes.length>limits.maxDecodedStreamBytes()||decoded>limits.maxDecodedBytes()){throw new PdfOperationException(PdfOperationException.Code.DECODED_STREAM_LIMIT,0,-1);}return PdfSafeXml.parse(bytes,limits).getDocumentElement();}
        void parse(PDDocument document)throws IOException{var form=document.getDocumentCatalog().getAcroForm(null);if(form==null){throw PdfObjects.invalid();}var raw=form.getCOSObject().getDictionaryObject(COSName.XFA);var packets=new ArrayList<Element>();if(raw instanceof COSStream stream){packets.add(packet(stream));}else if(raw instanceof COSArray array){if(array.size()%2!=0||array.size()>limits.maxObjects()){throw PdfObjects.invalid();}var packetNames=new HashSet<String>();for(int i=0;i<array.size();i+=2){if(!(array.getObject(i)instanceof COSString name)||!packetNames.add(name.getString())||!(array.getObject(i+1)instanceof COSStream stream)){throw PdfObjects.invalid();}packets.add(packet(stream));}}else{throw PdfObjects.invalid();}
            for(var root:packets){scan(root,0);if("xdp".equals(root.getLocalName())&&"http://ns.adobe.com/xdp/".equals(root.getNamespaceURI())){for(var child:children(root)){assign(child);}}else{assign(root);}}
            if(template==null||data==null){throw PdfObjects.invalid();}var area=descendants(template,"pageArea");if(area.size()!=1){throw PdfObjects.unsupported();}var medium=one(area.getFirst(),"medium",true);pageWidth=measure(medium,"short",0);pageHeight=measure(medium,"long",0);if("landscape".equals(medium.getAttribute("orientation"))){double swap=pageWidth;pageWidth=pageHeight;pageHeight=swap;}
            var content=one(area.getFirst(),"contentArea",true);left=measure(content,"x",0);top=measure(content,"y",0);areaWidth=measure(content,"w",pageWidth-left);areaHeight=measure(content,"h",pageHeight-top);if(pageWidth<=0||pageHeight<=0||areaWidth<=0||areaHeight<=0||left<0||top<0||left+areaWidth>pageWidth||top+areaHeight>pageHeight){throw PdfObjects.invalid();}pages.add(new Page(pageWidth,pageHeight));
            var roots=children(template).stream().filter(element->element.getLocalName().equals("subform")).toList();if(roots.size()!=1){throw PdfObjects.unsupported();}var root=roots.getFirst();var dataRoot=children(data).isEmpty()?data:children(data).getFirst();if(dataRoot.getLocalName().equals(root.getAttribute("name"))){visit(root,dataRoot,"",left,top,0);}else{visit(root,data,"",left,top,0);}if(fields.isEmpty()){throw PdfObjects.unsupported();}
        }
        void assign(Element root)throws IOException{String name=root.getLocalName();if(name.equals("template")){if(template!=null||root.getNamespaceURI()==null||!root.getNamespaceURI().matches("http://www\\.xfa\\.org/schema/xfa-template/(2\\.[568]|3\\.[0-3])/")){throw PdfObjects.invalid();}template=root;}
            else if(name.equals("datasets")){if(data!=null||!"http://www.xfa.org/schema/xfa-data/1.0/".equals(root.getNamespaceURI())){throw PdfObjects.invalid();}data=one(root,"data",true);}
            else if(name.equals("config")){var values=descendants(root,"dynamicRender");if(!values.isEmpty()){dynamic=values.getFirst().getTextContent().trim().equals("required");}}
            else{loss("XFA_PACKETS",ProcessingPolicy.Treatment.DISCARD,"PASSIVE_NON_TEMPLATE_PACKET_NOT_RECONSTRUCTED");}}
        void scan(Element element,int depth)throws IOException{tick(depth);String name=element.getLocalName();if(name==null){throw PdfObjects.invalid();}if(Set.of("script","event","submit","execute","connectionSet","connect","wsdlConnection","xmlConnection","xsdConnection").contains(name)){throw PdfObjects.unsupported();}var attributes=element.getAttributes();for(int i=0;i<attributes.getLength();i++){var item=attributes.item(i);String key=item.getLocalName()==null?item.getNodeName():item.getLocalName();if(Set.of("href","usehref","use","refuri","accessKey").contains(key)){throw PdfObjects.unsupported();}text+=item.getNodeValue().length();}for(Node node=element.getFirstChild();node!=null;node=node.getNextSibling()){if(node instanceof Element child){scan(child,depth+1);}else{text+=node.getTextContent()==null?0:node.getTextContent().length();}}if(text>limits.maxTextCharacters()){throw new PdfOperationException(PdfOperationException.Code.TEXT_LIMIT,0,-1);}}
        void visit(Element element,Element context,String prefix,double x,double y,int depth)throws IOException{tick(depth);String kind=element.getLocalName();String presence=element.getAttribute("presence");if(presence.equals("hidden")||presence.equals("invisible")){return;}String name=element.getAttribute("name");String full=prefix.isEmpty()?name:name.isEmpty()?prefix:prefix+"."+name;
            if(kind.equals("subform")){String layout=element.getAttribute("layout");boolean flowed=layout.equals("tb");if(flowed&&depth>0){throw PdfObjects.unsupported();}if(!layout.isEmpty()&&!layout.equals("position")&&!flowed){throw PdfObjects.unsupported();}dynamic|=flowed;Element scope=context;var matches=matching(context,name);if(!name.isEmpty()&&!context.getLocalName().equals(name)&&matches.size()==1){scope=matches.getFirst();}double ox=x+measure(element,"x",0),oy=y+measure(element,"y",0);if(flowed){cursor=Math.max(0,oy-top);}
                for(var child:children(element)){String childKind=child.getLocalName();if(Set.of("pageSet","occur","bind","font","margin","para","assist").contains(childKind)){continue;}if(!Set.of("subform","field","draw").contains(childKind)){loss("XFA_LAYOUT",ProcessingPolicy.Treatment.DISCARD,"UNSUPPORTED_PASSIVE_ELEMENT_NOT_RECONSTRUCTED");continue;}
                    var occurrences=one(child,"occur",false);var repetitions=childKind.equals("subform")&&occurrences!=null?matching(scope,child.getAttribute("name")):List.<Element>of();if(occurrences!=null&&repetitions.isEmpty()){int min=integer(occurrences,"min",1);if(min==0){continue;}}
                    int count=repetitions.isEmpty()?1:repetitions.size();if(occurrences!=null){int min=integer(occurrences,"min",1),max=integer(occurrences,"max",1);if(min<0||max< -1||max!=-1&&count>max||count<min){throw PdfObjects.invalid();}dynamic|=max!=1;}
                    for(int i=0;i<count;i++){tick(depth+1);double h=measure(child,"h",childKind.equals("subform")?estimatedHeight(child):0);double cy=oy;if(flowed){if(h<=0||h>areaHeight){throw new IllegalArgumentException("XFA flow item exceeds content area");}if(cursor+h>areaHeight){newPage();}cy=top+cursor;cursor+=h;}visit(child,repetitions.isEmpty()?scope:repetitions.get(i),full+(count>1?"["+i+"]":""),ox,cy,depth+1);}
                }return;}
            if(!kind.equals("field")&&!kind.equals("draw")){throw PdfObjects.unsupported();}if(element.hasAttribute("rotate")&&!element.getAttribute("rotate").equals("0")){throw PdfObjects.unsupported();}double width=measure(element,"w",0),height=measure(element,"h",0),px=x+measure(element,"x",0),py=y+measure(element,"y",0);if(width<=4||height<=4||px<0||py<0||px+width>pageWidth||py+height>pageHeight){throw PdfObjects.invalid();}
            String value="";var source=one(element,"value",false);if(source!=null){var values=children(source);if(values.size()!=1||!Set.of("text","integer","decimal","float","date","time","dateTime","boolean").contains(values.getFirst().getLocalName())){throw PdfObjects.unsupported();}value=values.getFirst().getTextContent();}
            if(kind.equals("field")){var ui=one(element,"ui",false);if(ui!=null){var types=children(ui);if(types.size()!=1||!types.getFirst().getLocalName().equals("textEdit")){throw PdfObjects.unsupported();}}var bind=one(element,"bind",false);String reference=bind==null?name:bind.getAttribute("ref");if(bind!=null&&"none".equals(bind.getAttribute("match"))){reference="";}if(!reference.isEmpty()){Element match=resolve(context,reference);if(match!=null){value=match.getTextContent();}}if(name.isEmpty()){throw PdfObjects.invalid();}}
            var caption=one(element,"caption",false);if(caption!=null){var captionValue=one(caption,"value",true);String label=captionValue.getTextContent();double reserve=measure(caption,"reserve",0);if(!caption.getAttribute("placement").equals("left")||reserve<=4||reserve>=width-4){throw PdfObjects.unsupported();}add(full+"#caption",label,px,py,reserve,height,false);px+=reserve;width-=reserve;}
            add(full,value,px,py,width,height,kind.equals("field"));
        }
        double estimatedHeight(Element element)throws IOException{double height=0;for(var child:children(element)){if(Set.of("field","draw","subform").contains(child.getLocalName())){height=Math.max(height,measure(child,"y",0)+measure(child,"h",0));}}return height;}
        void add(String name,String value,double x,double y,double width,double height,boolean editable)throws IOException{tick(0);if(!names.add(name+"@"+page)){throw PdfObjects.invalid();}text+=value.length();if(text>limits.maxTextCharacters()){throw new PdfOperationException(PdfOperationException.Code.TEXT_LIMIT,0,-1);}fields.add(new Field(name,value,page,new PdfAdapter.Box(x,y,width,height),editable));}
        void newPage()throws IOException{if(pages.size()>=limits.maxPages()){throw new PdfOperationException(PdfOperationException.Code.PAGE_LIMIT,0,-1);}page++;pages.add(new Page(pageWidth,pageHeight));cursor=0;}
    }
    private static Element resolve(Element context,String ref)throws IOException{String path=ref;if(path.startsWith("$record.")){path=path.substring(8);}else if(path.startsWith("$.")){path=path.substring(2);}if(!path.matches("[A-Za-z_][A-Za-z0-9_.-]*")){throw PdfObjects.unsupported();}Element current=context;for(String part:path.split("\\.")){var children=matching(current,part);if(children.size()>1){throw PdfObjects.invalid();}if(children.isEmpty()){return null;}current=children.getFirst();}if(!children(current).isEmpty()){throw PdfObjects.unsupported();}return current;}
    private static int integer(Element element,String name,int fallback)throws IOException{String value=element.getAttribute(name);if(value.isEmpty()){return fallback;}try{return Integer.parseInt(value);}catch(NumberFormatException failure){throw PdfObjects.invalid();}}
    private static double measure(Element element,String name,double fallback)throws IOException{String value=element.getAttribute(name);if(value.isEmpty()){return fallback;}var match=java.util.regex.Pattern.compile("([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+))(pt|in|mm|cm)?").matcher(value);if(!match.matches()){throw PdfObjects.unsupported();}double number=Double.parseDouble(match.group(1));String unit=match.group(2);number*=unit==null||unit.equals("pt")?1:unit.equals("in")?72:unit.equals("mm")?72/25.4:72/2.54;if(!Double.isFinite(number)||Math.abs(number)>1_000_000){throw PdfObjects.invalid();}return number;}
    private static Element one(Element parent,String name,boolean required)throws IOException{Element result=null;for(var child:children(parent)){if(child.getLocalName().equals(name)){if(result!=null){throw PdfObjects.invalid();}result=child;}}if(result==null&&required){throw PdfObjects.invalid();}return result;}
    private static List<Element> children(Element parent){var children=new ArrayList<Element>();for(Node node=parent.getFirstChild();node!=null;node=node.getNextSibling()){if(node instanceof Element element){children.add(element);}}return children;}
    private static List<Element> matching(Element parent,String name){return children(parent).stream().filter(child->child.getLocalName().equals(name)).toList();}
    private static List<Element> descendants(Element root,String name){var result=new ArrayList<Element>();if(root.getLocalName().equals(name)){result.add(root);}for(var child:children(root)){result.addAll(descendants(child,name));}return result;}
}
