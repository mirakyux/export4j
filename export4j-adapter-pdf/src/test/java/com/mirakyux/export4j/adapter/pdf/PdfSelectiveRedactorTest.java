// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfSelectiveRedactorTest {
    @TempDir Path temporary;
    @Test void removalKeepsOutsideSearchTextAndRebuildsWithoutOriginalMetadataOrStreams() throws Exception {
        byte[] source;
        try(var fixture=new Fixture()) {
            fixture.document.getDocumentInformation().setTitle("SECRET-METADATA");
            fixture.text("SECRET",100,300); fixture.text("KEEP",100,200); source=fixture.save();
        }
        var adapter=new PdfAdapter(); var input=adapter.input(source,""); var prepared=adapter.redactSelective(input).area(0,95,80,140,30).policy(policy()).prepare();
        var bytes=new ByteArrayOutputStream(); var result=prepared.to(bytes);
        try(var actual=Loader.loadPDF(bytes.toByteArray())) {
            assertEquals("KEEP",new PDFTextStripper().getText(actual).strip());
            assertTrue(actual.getDocumentInformation().getCOSObject().keySet().isEmpty());
            assertFalse(actual.getDocument().getTrailer().containsKey(org.apache.pdfbox.cos.COSName.PREV));
            assertTrue(actual.getPage(0).getResources().getFontNames().iterator().hasNext());
        }
        assertEquals(prepared.preview().issues(),result.report().issues()); assertArrayEquals(source,input.bytes());
    }
    @Test void sameImageDrawnTwiceGetsIndependentSanitizedPixels() throws Exception {
        byte[] source;
        try(var fixture=new Fixture()) {
            var pixels=new BufferedImage(3,2,BufferedImage.TYPE_INT_RGB);
            for(int y=0;y<2;y++) { pixels.setRGB(0,y,0xFF0000); pixels.setRGB(1,y,0x00FF00); pixels.setRGB(2,y,0x0000FF); }
            var image=LosslessFactory.createFromImage(fixture.document,pixels); fixture.content.drawImage(image,20,200,90,60); fixture.content.drawImage(image,200,200,90,60); source=fixture.save();
        }
        var adapter=new PdfAdapter(); var prepared=adapter.redactSelective(adapter.input(source,"")).area(0,50,140,30,60).policy(policy()).prepare(); var bytes=new ByteArrayOutputStream(); prepared.to(bytes);
        try(var actual=Loader.loadPDF(bytes.toByteArray())) {
            var names=actual.getPage(0).getResources().getXObjectNames().iterator();
            var first=(org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject)actual.getPage(0).getResources().getXObject(names.next());
            var second=(org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject)actual.getPage(0).getResources().getXObject(names.next());
            assertEquals(0,first.getImage().getRGB(1,0)&0xFFFFFF); assertEquals(0x00FF00,second.getImage().getRGB(1,0)&0xFFFFFF);
            assertEquals(0xFF0000,first.getImage().getRGB(0,0)&0xFFFFFF); assertEquals(0x0000FF,first.getImage().getRGB(2,0)&0xFFFFFF);
        }
    }
    @Test void nestedFormInvocationIsFlattenedWithoutChangingTheUnselectedInvocation() throws Exception {
        byte[] source;
        try(var fixture=new Fixture()) {
            var child=new PDFormXObject(fixture.document); child.setBBox(new PDRectangle(0,0,150,40)); child.setResources(new PDResources());
            try(var commands=new PDFormContentStream(child)) { commands.beginText();commands.setFont(fixture.font,18);commands.newLineAtOffset(5,10);commands.showText("SECRET");commands.endText(); }
            var parent=new PDFormXObject(fixture.document); parent.setBBox(new PDRectangle(0,0,170,60)); parent.setResources(new PDResources());
            try(var commands=new PDFormContentStream(parent)) { commands.drawForm(child); }
            fixture.content.saveGraphicsState(); fixture.content.transform(Matrix.getTranslateInstance(40,280)); fixture.content.drawForm(parent); fixture.content.restoreGraphicsState();
            fixture.content.saveGraphicsState(); fixture.content.transform(Matrix.getTranslateInstance(40,100)); fixture.content.drawForm(parent); fixture.content.restoreGraphicsState(); source=fixture.save();
        }
        var adapter=new PdfAdapter(); var bytes=new ByteArrayOutputStream(); adapter.redactSelective(adapter.input(source,"")).area(0,35,70,170,70).policy(policy()).prepare().to(bytes);
        try(var actual=Loader.loadPDF(bytes.toByteArray())) { assertEquals("SECRET",new PDFTextStripper().getText(actual).strip()); assertFalse(actual.getPage(0).getResources().getXObjectNames().iterator().hasNext()); }
    }
    @Test void rotatedCroppedViewUsesPhysicalPointSelectionAndRetainsSearchableOutsideGlyphs() throws Exception {
        byte[] source;
        try(var fixture=new Fixture()) {
            fixture.page.setCropBox(new PDRectangle(20,30,360,340)); fixture.page.setRotation(90); fixture.page.setUserUnit(2);
            fixture.text("SECRET",100,300); fixture.text("KEEP",100,200); source=fixture.save();
        }
        var adapter=new PdfAdapter(); var bytes=new ByteArrayOutputStream(); adapter.redactSelective(adapter.input(source,"")).area(0,530,140,70,250).policy(policy()).prepare().to(bytes);
        try(var original=Loader.loadPDF(source);var actual=Loader.loadPDF(bytes.toByteArray())) {
            assertEquals(90,actual.getPage(0).getRotation());assertEquals(2,actual.getPage(0).getUserUnit());assertEquals(360,actual.getPage(0).getCropBox().getWidth());assertEquals(340,actual.getPage(0).getCropBox().getHeight());
            // PDFTextStripper separates characters in this rotated source control too.
            // Compare content without formatting whitespace, and every retained glyph's geometry.
            assertEquals("KEEP",new PDFTextStripper().getText(actual).replaceAll("\\s+",""));
            var before=positions(original);var after=positions(actual);assertEquals(10,before.size());assertEquals(4,after.size());
            for(int index=0;index<4;index++) {var expected=before.get(index+6);var value=after.get(index);assertEquals(expected.text(),value.text());assertEquals(expected.x(),value.x(),.001);assertEquals(expected.y(),value.y(),.001);assertEquals(expected.width(),value.width(),.001);assertEquals(expected.height(),value.height(),.001);}
        }
    }
    @Test void cleanedClippedImageDoesNotPaintWhiteOverUnselectedBackground() throws Exception {
        byte[] source;
        try(var fixture=new Fixture()) {
            fixture.content.setNonStrokingColor(0f,1f,0f);fixture.content.addRect(0,0,400,400);fixture.content.fill();
            var pixels=new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB);for(int y=0;y<2;y++){for(int x=0;x<2;x++){pixels.setRGB(x,y,0xFF0000);}}
            var image=LosslessFactory.createFromImage(fixture.document,pixels);fixture.content.saveGraphicsState();fixture.content.addRect(40,200,50,100);fixture.content.clip();fixture.content.drawImage(image,40,200,100,100);fixture.content.restoreGraphicsState();source=fixture.save();
        }
        var adapter=new PdfAdapter();var bytes=new ByteArrayOutputStream();adapter.redactSelective(adapter.input(source,"")).area(0,300,300,10,10).policy(policy()).prepare().to(bytes);
        try(var actual=Loader.loadPDF(bytes.toByteArray())) {var pixels=new org.apache.pdfbox.rendering.PDFRenderer(actual).renderImage(0);assertEquals(0x00FF00,pixels.getRGB(120,150)&0xFFFFFF);assertEquals(0xFF0000,pixels.getRGB(60,150)&0xFFFFFF);}
    }
    @Test void graphicsStackDepthAccumulatesAcrossTheFormInvocation() throws Exception {
        byte[] source;
        try(var fixture=new Fixture()) {
            var form=new PDFormXObject(fixture.document);form.setBBox(new PDRectangle(400,400));form.setResources(new PDResources());
            try(var commands=new PDFormContentStream(form)) {for(int i=0;i<20;i++){commands.saveGraphicsState();}commands.beginText();commands.setFont(fixture.font,18);commands.newLineAtOffset(100,200);commands.showText("KEEP");commands.endText();for(int i=0;i<20;i++){commands.restoreGraphicsState();}}
            for(int i=0;i<20;i++){fixture.content.saveGraphicsState();}fixture.content.drawForm(form);for(int i=0;i<20;i++){fixture.content.restoreGraphicsState();}source=fixture.save();
        }
        var adapter=PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxDepth(32).build()).build();var failure=assertThrows(PdfOperationException.class,()->adapter.redactSelective(adapter.input(source,"")).area(0,300,300,10,10).policy(policy()).prepare());assertEquals(PdfOperationException.Code.DEPTH_LIMIT,failure.code());
    }
    @Test void aTransparentDrawingCannotProducePartialSuccessEvenUnderIgnore() throws Exception {
        byte[] source;
        try(var fixture=new Fixture()) { fixture.text("SECRET",100,300); var state=new PDExtendedGraphicsState(); state.setNonStrokingAlphaConstant(.5f); fixture.content.setGraphicsStateParameters(state); fixture.content.addRect(10,10,100,100);fixture.content.fill();source=fixture.save(); }
        var adapter=new PdfAdapter(); var failure=assertThrows(PdfOperationException.class,()->adapter.redactSelective(adapter.input(source,"")).area(0,95,80,140,30).policy(policy()).prepare()); assertEquals(PdfOperationException.Code.UNSUPPORTED_FEATURE,failure.code());
    }
    @Test void unicodeExpansionAndEveryOccurrenceConsumeTheUtf16TextBudget() throws Exception {
        byte[] source;
        try(var fixture=new Fixture()) {
            fixture.text("AA",100,200);
            String code=java.util.HexFormat.of().formatHex(fixture.font.encode("A"));
            String unicode=java.util.HexFormat.of().formatHex("AB\uD83D\uDE00".getBytes(java.nio.charset.StandardCharsets.UTF_16BE));
            String cmap="/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CIDSystemInfo << /Registry (Test) /Ordering (UCS) /Supplement 0 >> def /CMapName /Test def /CMapType 2 def 1 begincodespacerange <0000> <FFFF> endcodespacerange 1 beginbfchar <"+code+"> <"+unicode+"> endbfchar endcmap CMapName currentdict /CMap defineresource pop end end";
            fixture.font.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.TO_UNICODE,new org.apache.pdfbox.pdmodel.common.PDStream(fixture.document,new ByteArrayInputStream(cmap.getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
            source=fixture.save();
        }
        var bounded=PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxTextCharacters(7).build()).build();
        var failure=assertThrows(PdfOperationException.class,()->bounded.redactSelective(bounded.input(source,"")).area(0,350,350,10,10).policy(policy()).prepare());assertEquals(PdfOperationException.Code.TEXT_LIMIT,failure.code());
        var exact=PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxTextCharacters(8).build()).build();var output=new ByteArrayOutputStream();exact.redactSelective(exact.input(source,"")).area(0,350,350,10,10).policy(policy()).prepare().to(output);
        try(var actual=Loader.loadPDF(output.toByteArray())) {assertEquals("AB\uD83D\uDE00AB\uD83D\uDE00",new PDFTextStripper().getText(actual).strip());}
    }
    @Test void malformedTextAndMarkedContentStructureNeverProducePartialSuccess() throws Exception {
        for(String malformed:List.of("BT BT ET ET","BT","ET","EMC","/Span BMC")) {
            byte[] source;
            try(var fixture=new Fixture()) {
                fixture.text("KEEP",100,200);fixture.content.close();
                var contents=new org.apache.pdfbox.cos.COSArray();contents.add(fixture.page.getCOSObject().getItem(org.apache.pdfbox.cos.COSName.CONTENTS));contents.add(new org.apache.pdfbox.pdmodel.common.PDStream(fixture.document,new ByteArrayInputStream(malformed.getBytes(java.nio.charset.StandardCharsets.US_ASCII))));fixture.page.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.CONTENTS,contents);source=fixture.save();
            }
            var adapter=new PdfAdapter();var failure=assertThrows(PdfOperationException.class,()->adapter.redactSelective(adapter.input(source,"")).area(0,350,350,10,10).policy(policy()).prepare());assertEquals(PdfOperationException.Code.INVALID_PDF,failure.code());
        }
    }
    @Test void strictPolicyCancellationBoundsAndOutputBudgetPreserveExistingTarget() throws Exception {
        byte[] source; try(var fixture=new Fixture()) { fixture.text("SECRET",100,300);fixture.text("KEEP",100,200);source=fixture.save(); }
        var adapter=new PdfAdapter();var input=adapter.input(source,"");
        assertThrows(ProcessingException.class,()->adapter.redactSelective(input).area(0,95,80,140,30).prepare());
        assertThrows(ExecutionException.class,()->adapter.redactSelective(input).area(0,95,80,140,30).policy(policy()).execution(ExecutionOptions.builder().cancelled(()->true).build()).prepare());
        assertThrows(IllegalArgumentException.class,()->adapter.redactSelective(input).area(0,390,390,30,30).policy(policy()).prepare());
        Path target=temporary.resolve("protected.pdf");byte[] sentinel={1,2,3};Files.write(target,sentinel);
        var bounded=PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxOutputBytes(100).build()).build();
        assertThrows(PdfOperationException.class,()->bounded.redactSelective(bounded.input(source,"")).area(0,95,80,140,30).policy(policy()).prepare().to(target));assertArrayEquals(sentinel,Files.readAllBytes(target));
    }
    private static ProcessingPolicy policy() {
        var builder=ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.IGNORE,ProcessingPolicy.Treatment.DISCARD);
        for(String feature:List.of("PDF_CONTENT_RECONSTRUCTION","PAGE_GEOMETRY","PDF_IMAGE_BOUNDARY","PDF_CLIPPED_TEXT")) {builder.feature(feature,ProcessingPolicy.Action.IGNORE,ProcessingPolicy.Treatment.DEGRADE);}return builder.build();
    }
    private record Position(String text,float x,float y,float width,float height) { }
    private static List<Position> positions(PDDocument document) throws Exception {
        var result=new java.util.ArrayList<Position>();
        new PDFTextStripper(){@Override protected void processTextPosition(TextPosition value){result.add(new Position(value.getUnicode(),value.getXDirAdj(),value.getYDirAdj(),value.getWidthDirAdj(),value.getHeightDir()));super.processTextPosition(value);}}.getText(document);
        return result;
    }
    private static final class Fixture implements AutoCloseable {
        final PDDocument document=new PDDocument();final PDPage page=new PDPage(new PDRectangle(400,400)); final PDType0Font font; final PDPageContentStream content;
        Fixture() throws Exception {document.addPage(page);font=PDType0Font.load(document,new ByteArrayInputStream(Files.readAllBytes(Path.of("../assets/fonts/LiberationSans-Regular.ttf"))),false);content=new PDPageContentStream(document,page);}
        void text(String text,float x,float y) throws Exception {content.beginText();content.setFont(font,18);content.newLineAtOffset(x,y);content.showText(text);content.endText();}
        byte[] save() throws Exception {content.close();var bytes=new ByteArrayOutputStream();document.save(bytes);return bytes.toByteArray();}
        @Override public void close() throws java.io.IOException {content.close();document.close();}
    }
}
