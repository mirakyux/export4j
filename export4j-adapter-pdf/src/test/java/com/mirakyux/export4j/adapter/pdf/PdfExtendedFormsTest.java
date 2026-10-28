// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDListBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfExtendedFormsTest {
    @Test void fillsMultilineCombRichAndMultiChoiceWithActualAppearances()throws Exception{
        var adapter=new PdfAdapter();var input=adapter.input(source(),"");var font=font();var editor=adapter.editObjects(input)
                .fillText("multi","FIRST LINE\nSECOND LINE",font,10).fillText("comb","AB12",font,10)
                .fillRichText("rich",List.of(new PdfFieldText.Run("RED ",font,10,0xcc0000,false),new PdfFieldText.Run("UNDERLINE",font,10,0x0022bb,true)))
                .fillChoices("choice",List.of("A","C"),font,9);
        var bytes=bytes(editor.prepare());var snapshot=adapter.readObjects(adapter.input(bytes,""));
        assertEquals(List.of("FIRST LINE\nSECOND LINE","AB12","RED UNDERLINE","[A, C]"),snapshot.fields().stream().map(PdfObjects.Field::value).toList());
        assertTrue(snapshot.fields().get(0).multiline());assertTrue(snapshot.fields().get(1).comb());assertTrue(snapshot.fields().get(2).richText());assertEquals(List.of("A","C"),snapshot.fields().get(3).values());
        assertTrue(snapshot.fields().get(2).richValue().contains("text-decoration:underline"));
        try(var pdf=Loader.loadPDF(bytes)){var form=pdf.getDocumentCatalog().getAcroForm(null);assertFalse(form.getNeedAppearances());for(var field:form.getFieldTree()){for(var widget:field.getWidgets()){assertTrue(widget.getNormalAppearanceStream().getCOSObject().getLength()>0);}}}
        assertEquals("OLD",adapter.readObjects(input).fields().getFirst().value());
    }
    @Test void explicitAutoSizeAndCapacityFailureDoNotSilentlyClip()throws Exception{
        var adapter=new PdfAdapter();var prepared=adapter.editObjects(adapter.input(source(),"")).fillTextAuto("comb","ABCDEF",font(),4,60).prepare();
        try(var pdf=Loader.loadPDF(bytes(prepared))){String da=pdf.getDocumentCatalog().getAcroForm(null).getField("comb").getCOSObject().getString(COSName.DA);double size=Double.parseDouble(da.split(" ")[1]);assertTrue(size>=4&&size<60);}
        assertThrows(IllegalArgumentException.class,()->adapter.editObjects(adapter.input(source(),"")).fillText("comb","TOO-LONG-FOR-SIX",font(),10).prepare());
        assertThrows(IllegalArgumentException.class,()->adapter.editObjects(adapter.input(source(),"")).fillText("multi","A".repeat(1000),font(),20).prepare());
    }
    @Test void exactNamesAndRichDataLossRemainPolicyControlled()throws Exception{
        var adapter=new PdfAdapter();var input=adapter.input(source(),"");var data=PdfFormData.of(List.of(new PdfFormData.FieldValue("missing","X")));
        assertThrows(ProcessingException.class,()->adapter.editObjects(input).importFormData(data,font(),10).prepare());
        for(var action:List.of(ProcessingPolicy.Action.WARN,ProcessingPolicy.Action.IGNORE)){var policy=ProcessingPolicy.builder().feature("FIELD_MAPPING",action,ProcessingPolicy.Treatment.SKIP_MATCH).build();var candidate=adapter.editObjects(input).policy(policy).importFormData(data,font(),10).prepare();assertEquals("FIELD_MAPPING",candidate.preview().issues().getFirst().feature());assertEquals("OLD",adapter.readObjects(adapter.input(bytes(candidate),"")).fields().getFirst().value());}
    }
    @Test void listOptionWidthIsCheckedWithoutWrappingIntoAnotherSelectionRow()throws Exception{
        byte[] input;try(var pdf=Loader.loadPDF(source())){var choice=(PDListBox)pdf.getDocumentCatalog().getAcroForm(null).getField("choice");choice.setOptions(List.of("A","B","C"),List.of("THIS DISPLAY OPTION EXCEEDS THE FIELD WIDTH","Beta","Gamma"));var output=new ByteArrayOutputStream();pdf.save(output);input=output.toByteArray();}
        var adapter=new PdfAdapter();var failure=assertThrows(IllegalArgumentException.class,()->adapter.editObjects(adapter.input(input,"")).fillChoices("choice",List.of("A"),font(),10).prepare());
        assertTrue(failure.getMessage().contains("appearance box"));
        assertEquals(List.of("B"),adapter.readObjects(adapter.input(input,"")).fields().getLast().values());
    }
    @Test void allDisplayedListOptionsRespectFormattingAndShapingPolicies()throws Exception{
        for(String display:List.of("TWO\nLINES","\u0627\u0644\u0639\u0631\u0628\u064a\u0629")){byte[] input;try(var pdf=Loader.loadPDF(source())){var choice=(PDListBox)pdf.getDocumentCatalog().getAcroForm(null).getField("choice");choice.setOptions(List.of("A","B","C"),List.of(display,"Beta","Gamma"));var output=new ByteArrayOutputStream();pdf.save(output);input=output.toByteArray();}
            var adapter=new PdfAdapter();String feature=display.contains("\n")?"FIELD_FORMAT":"TEXT_SHAPING";assertThrows(ProcessingException.class,()->adapter.editObjects(adapter.input(input,"")).fillChoices("choice",List.of("C"),font(),10).prepare());
            var policy=ProcessingPolicy.builder().feature(feature,ProcessingPolicy.Action.WARN,ProcessingPolicy.Treatment.SKIP_MATCH).build();var candidate=adapter.editObjects(adapter.input(input,"")).policy(policy).fillChoices("choice",List.of("C"),font(),10).prepare();assertEquals(feature,candidate.preview().issues().getFirst().feature());assertEquals(List.of("B"),adapter.readObjects(adapter.input(bytes(candidate),"")).fields().getLast().values());
        }
    }
    @Test void annotationGeometryAndAllSixConcreteAppearancesRoundTrip()throws Exception{
        var adapter=new PdfAdapter();var input=adapter.input(source(),"");var box=new PdfAdapter.Box(10,10,100,20);var quad=new PdfAnnotationSpec.Quad(new PdfAnnotationSpec.Point(10,30),new PdfAnnotationSpec.Point(110,30),new PdfAnnotationSpec.Point(10,10),new PdfAnnotationSpec.Point(110,10));
        var editor=adapter.editObjects(input);for(var type:PdfAnnotationSpec.Type.values()){editor.addAnnotation(0,new PdfAnnotationSpec(type,box,type.name(),type==PdfAnnotationSpec.Type.HIGHLIGHT||type==PdfAnnotationSpec.Type.UNDERLINE||type==PdfAnnotationSpec.Type.STRIKEOUT?List.of(quad):List.of(),type==PdfAnnotationSpec.Type.INK?List.of(List.of(new PdfAnnotationSpec.Point(12,12),new PdfAnnotationSpec.Point(30,28),new PdfAnnotationSpec.Point(90,15))):List.of(),0x2244bb,type==PdfAnnotationSpec.Type.SQUARE?0xccddff:-1,1,.8));}
        var result=adapter.readObjects(adapter.input(bytes(editor.prepare()),""));assertEquals(6,result.annotations().stream().filter(annotation->!annotation.type().equals("WIDGET")).count());assertEquals(List.of(quad),result.annotations().get(4).quads());
    }
    @Test void malformedFieldTypesAndRichActiveContentAreNotRepairable()throws Exception{
        var adapter=new PdfAdapter();try(var pdf=Loader.loadPDF(source())){var field=pdf.getDocumentCatalog().getAcroForm(null).getField("comb");field.getCOSObject().setItem(COSName.MAX_LEN,new COSFloat(6.5f));var out=new ByteArrayOutputStream();pdf.save(out);assertThrows(PdfOperationException.class,()->adapter.readObjects(adapter.input(out.toByteArray(),"")));}
        try(var pdf=Loader.loadPDF(source())){var field=(PDTextField)pdf.getDocumentCatalog().getAcroForm(null).getField("rich");field.setRichText(true);field.setRichTextValue("<body xmlns='http://www.w3.org/1999/xhtml'><span style='color:url(https://example.invalid/)'>VALUE</span></body>");var out=new ByteArrayOutputStream();pdf.save(out);assertThrows(PdfOperationException.class,()->adapter.readObjects(adapter.input(out.toByteArray(),"")));}
    }
    @Test void rolloverAndDownStatesHaveUpdatedTextAndExplicitStyleLoss()throws Exception{
        byte[] original;try(var pdf=Loader.loadPDF(source())){var widget=pdf.getDocumentCatalog().getAcroForm(null).getField("multi").getWidgets().getFirst();var appearance=widget.getAppearance();appearance.getCOSObject().setItem(COSName.R,widget.getNormalAppearanceStream());var states=new org.apache.pdfbox.cos.COSDictionary();states.setItem(COSName.getPDFName("Pressed"),widget.getNormalAppearanceStream());appearance.getCOSObject().setItem(COSName.D,states);var bytes=new ByteArrayOutputStream();pdf.save(bytes);original=bytes.toByteArray();}
        var adapter=new PdfAdapter();var input=adapter.input(original,"");assertThrows(ProcessingException.class,()->adapter.editObjects(input).fillText("multi","UPDATED",font(),10).prepare());
        var policy=ProcessingPolicy.builder().feature("FIELD_APPEARANCE_STATES",ProcessingPolicy.Action.WARN,ProcessingPolicy.Treatment.DEGRADE).build();try(var pdf=Loader.loadPDF(bytes(adapter.editObjects(input).policy(policy).fillText("multi","UPDATED",font(),10).prepare()))){var widget=pdf.getDocumentCatalog().getAcroForm(null).getField("multi").getWidgets().getFirst();var normal=widget.getNormalAppearanceStream().getCOSObject();assertEquals(normal,widget.getAppearance().getCOSObject().getDictionaryObject(COSName.R));assertEquals(normal,widget.getAppearance().getCOSObject().getCOSDictionary(COSName.D).getDictionaryObject(COSName.getPDFName("Pressed")));}
    }
    static byte[] source()throws Exception{try(var document=new PDDocument()){var page=new PDPage(new PDRectangle(300,300));document.addPage(page);var form=new PDAcroForm(document);document.getDocumentCatalog().setAcroForm(form);var resources=new PDResources();resources.put(COSName.getPDFName("F"),PDType0Font.load(document,new ByteArrayInputStream(font().bytes()),false));form.setDefaultResources(resources);form.setDefaultAppearance("/F 10 Tf 0 g");int i=0;
        for(String name:List.of("multi","comb","rich")){var field=new PDTextField(form);field.setPartialName(name);field.setMultiline(name.equals("multi"));if(name.equals("comb")){field.setComb(true);field.setMaxLen(6);}form.getFields().add(field);var widget=field.getWidgets().getFirst();widget.setRectangle(new PDRectangle(10,210-i++*65,180,55));widget.setPage(page);page.getAnnotations().add(widget);field.setValue("OLD");}
        var choice=new PDListBox(form);choice.setPartialName("choice");choice.setMultiSelect(true);choice.setOptions(List.of("A","B","C"),List.of("Alpha","Beta","Gamma"));form.getFields().add(choice);var widget=choice.getWidgets().getFirst();widget.setRectangle(new PDRectangle(200,180,90,100));widget.setPage(page);page.getAnnotations().add(widget);choice.setValue(List.of("B"));var output=new ByteArrayOutputStream();document.save(output);return output.toByteArray();}}
    static PdfFont font()throws Exception{return PdfFont.of(Files.readAllBytes(Path.of("../assets/fonts/LiberationSans-Regular.ttf")));}
    static byte[] bytes(PreparedPdf prepared)throws Exception{var output=new ByteArrayOutputStream();prepared.to(output);return output.toByteArray();}
}
