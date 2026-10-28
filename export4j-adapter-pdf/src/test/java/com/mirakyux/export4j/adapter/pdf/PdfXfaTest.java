// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfXfaTest {
    @Test void staticTemplateReadsDatasetValuesAndFlattensFreshVisiblePage()throws Exception{
        var adapter=new PdfAdapter();var input=adapter.input(source(false,false),"");assertThrows(PdfOperationException.class,()->adapter.readObjects(input));var read=adapter.xfa(input).read();assertFalse(read.snapshot().dynamic());assertEquals("DATA VALUE",read.snapshot().fields().getFirst().value());assertEquals(1,read.snapshot().pages().size());assertThrows(ProcessingException.class,()->adapter.xfa(input).flatten(PdfExtendedFormsTest.font(),10));
        var candidate=adapter.xfa(input).policy(policy(ProcessingPolicy.Action.WARN)).flatten(PdfExtendedFormsTest.font(),10);assertEquals(3,candidate.preview().losses().size());try(var pdf=Loader.loadPDF(PdfExtendedFormsTest.bytes(candidate))){assertEquals(1,pdf.getNumberOfPages());assertEquals(null,pdf.getDocumentCatalog().getAcroForm(null));assertTrue(new PDFTextStripper().getText(pdf).contains("DATA VALUE"));}
    }
    @Test void flowedRepeatedDatasetRowsCreateRealPagesAndIgnoreRetainsLosses()throws Exception{
        var adapter=new PdfAdapter();var input=adapter.input(source(true,false),"");var result=adapter.xfa(input).read();assertTrue(result.snapshot().dynamic());assertEquals(8,result.snapshot().fields().size());assertEquals(2,result.snapshot().pages().size());assertEquals(List.of(0,0,0,0,0,1,1,1),result.snapshot().fields().stream().map(PdfXfaRequest.Field::page).toList());
        var candidate=adapter.xfa(input).policy(policy(ProcessingPolicy.Action.IGNORE)).flatten(PdfExtendedFormsTest.font(),10);assertEquals(3,candidate.preview().losses().size());assertTrue(candidate.preview().warnings().isEmpty());try(var pdf=Loader.loadPDF(PdfExtendedFormsTest.bytes(candidate))){String text=new PDFTextStripper().getText(pdf);for(int i=0;i<8;i++){assertTrue(text.contains("ROW-"+i));}assertEquals(2,pdf.getNumberOfPages());}
    }
    @Test void xfaScriptsAndXmlEntitiesRemainHardFailuresAndCancellationIsSynchronous()throws Exception{
        var adapter=new PdfAdapter();for(var action:List.of(ProcessingPolicy.Action.WARN,ProcessingPolicy.Action.IGNORE)){assertThrows(java.io.IOException.class,()->adapter.xfa(adapter.input(source(false,true),"")).policy(policy(action)).read());}
        assertThrows(ExecutionException.class,()->adapter.xfa(adapter.input(source(false,false),"")).execution(ExecutionOptions.builder().cancelled(()->true).build()).read());
    }
    static ProcessingPolicy policy(ProcessingPolicy.Action action){return ProcessingPolicy.builder().defaultRule(action,ProcessingPolicy.Treatment.DEGRADE).feature("XFA_INTERACTIVITY",action,ProcessingPolicy.Treatment.DISCARD).build();}
    static byte[] source(boolean dynamic,boolean script)throws Exception{try(var document=new PDDocument()){document.addPage(new PDPage());var form=new PDAcroForm(document);document.getDocumentCatalog().setAcroForm(form);
        String page="<pageSet><pageArea><medium short='300pt' long='220pt'/><contentArea x='20pt' y='20pt' w='260pt' h='160pt'/></pageArea></pageSet>";
        String field="<field name='name' w='180pt' h='26pt'><ui><textEdit/></ui><value><text>DEFAULT</text></value></field>";
        String template="<template xmlns='http://www.xfa.org/schema/xfa-template/3.3/'><subform name='form' layout='"+(dynamic?"tb":"position")+"'>"+page+(dynamic?"<subform name='row' layout='position' h='30pt'><occur min='0' max='-1'/>"+field+"</subform>":field)+(script?"<event><script>for(;;){}</script></event>":"")+"</subform></template>";
        StringBuilder data=new StringBuilder("<xfa:datasets xmlns:xfa='http://www.xfa.org/schema/xfa-data/1.0/'><xfa:data><form>");if(dynamic){for(int i=0;i<8;i++){data.append("<row><name>ROW-").append(i).append("</name></row>");}}else{data.append("<name>DATA VALUE</name>");}data.append("</form></xfa:data></xfa:datasets>");
        var packets=new COSArray();packets.add(new COSString("template"));packets.add(stream(document,template));packets.add(new COSString("datasets"));packets.add(stream(document,data.toString()));form.getCOSObject().setItem(COSName.XFA,packets);var output=new ByteArrayOutputStream();document.save(output);return output.toByteArray();}}
    private static PDStream stream(PDDocument document,String value)throws Exception{var stream=new PDStream(document);try(var output=stream.createOutputStream(COSName.FLATE_DECODE)){output.write(value.getBytes(StandardCharsets.UTF_8));}return stream;}
}
