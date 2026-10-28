// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfAnnotationSpec;
import com.mirakyux.export4j.adapter.pdf.PdfFieldText;
import com.mirakyux.export4j.adapter.pdf.PdfFont;
import com.mirakyux.export4j.adapter.pdf.PdfFormData;
import com.mirakyux.export4j.adapter.pdf.PdfObjectEditor;
import com.mirakyux.export4j.adapter.pdf.PdfOperationException;
import com.mirakyux.export4j.adapter.pdf.PdfLimits;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Published-coordinate proof for field formats, concrete markup, real exchange, and offline XFA. */
public final class PdfExtendedFormsConsumer {
    private PdfExtendedFormsConsumer() { }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    public static void main(String[] args)throws Exception{
        Path fixtures=Path.of("../../build/verification/pdf-extended-forms-fixtures");Path output=Path.of(System.getProperty("consumer.outputDirectory")).resolve("pdf-extended-forms");Files.createDirectories(output);
        var adapter=new PdfAdapter();var font=PdfFont.of(Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Regular.ttf")));var bold=PdfFont.of(Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Bold.ttf")));byte[] source=Files.readAllBytes(fixtures.resolve("forms.pdf"));var input=adapter.input(source,"");Files.write(output.resolve("source.pdf"),source);int rejected=0,protectedTargets=0,pdfs=0,exchanges=0;
        Path protectedTarget=output.resolve("protected.pdf");byte[] sentinel="existing destination".getBytes(StandardCharsets.UTF_8);
        Files.write(protectedTarget,sentinel);expectLoss(()->adapter.editObjects(input).flattenForms().prepare().to(protectedTarget),"FORMS");rejected++;protectedTargets++;unchanged(protectedTarget,sentinel);
        expectLoss(()->adapter.formData(input).prepare(),"FORM_DATA_APPEARANCE");rejected++;
        for(var action:List.of(ProcessingPolicy.Action.WARN,ProcessingPolicy.Action.IGNORE)){
            String label=action.name().toLowerCase(Locale.ROOT);var policy=policy(action);var editor=filled(adapter.editObjects(input),font,bold);addAnnotations(editor);var filled=editor.policy(policy).prepare();var actual=filled.to(output.resolve("filled-"+label+".pdf"));report(filled.preview(),actual.report(),action);writeReport(output.resolve("filled-"+label+".losses.tsv"),actual.report());pdfs++;
            var filledInput=adapter.input(output.resolve("filled-"+label+".pdf"),"");var read=adapter.readObjects(filledInput);require(read.fields().get(0).value().equals("FIRST LINE\nSECOND LINE"),"multiline value changed");require(read.fields().get(1).comb()&&read.fields().get(1).value().equals("AB12CD34"),"comb value/flag changed");require(read.fields().get(2).richText()&&read.fields().get(2).value().equals("RED BOLD UNDERLINED"),"rich text changed");require(read.fields().get(3).values().equals(List.of("A","C")),"multi-choice changed");require(read.annotations().stream().filter(value->!value.type().equals("WIDGET")).count()==6,"markup types lost");
            var flat=adapter.editObjects(filledInput).policy(policy).flattenForms().prepare();writeReport(output.resolve("flattened-"+label+".losses.tsv"),flat.to(output.resolve("flattened-"+label+".pdf")).report());require(adapter.readObjects(adapter.input(output.resolve("flattened-"+label+".pdf"),"")).fields().isEmpty(),"flatten retained fields");pdfs++;
            for(var format:PdfFormData.Format.values()){
                String ext=format.name().toLowerCase(Locale.ROOT);var exported=adapter.formData(filledInput).format(format).policy(policy).prepare();var exportedResult=exported.to(output.resolve("exported-"+label+"."+ext));report(exported.preview(),exportedResult.report(),action);writeReport(output.resolve("exported-"+label+"."+ext+".losses.tsv"),exportedResult.report());exchanges++;
                var parsed=PdfFormData.parse(exported.bytes(),format);require(parsed.fields().stream().map(PdfFormData.FieldValue::name).toList().equals(List.of("multi","comb","rich","list")),"exchange field order changed");
                var imported=adapter.editObjects(filledInput).policy(policy).importFormData(parsed,font,10).prepare();writeReport(output.resolve("roundtrip-"+label+"-"+ext+".losses.tsv"),imported.to(output.resolve("roundtrip-"+label+"-"+ext+".pdf")).report());pdfs++;
                var external=PdfFormData.parse(Files.readAllBytes(fixtures.resolve("values."+ext)),format);var applied=adapter.editObjects(filledInput).policy(policy).importFormData(external,font,10).prepare();writeReport(output.resolve("external-"+label+"-"+ext+".losses.tsv"),applied.to(output.resolve("external-"+label+"-"+ext+".pdf")).report());pdfs++;require(adapter.readObjects(adapter.input(output.resolve("external-"+label+"-"+ext+".pdf"),"")).fields().getFirst().value().equals("EXCHANGED FIRST\nEXCHANGED SECOND"),"external exchange not applied");
            }
            var passive=PdfFormData.parse(Files.readAllBytes(fixtures.resolve("unknown-property.xfdf")),PdfFormData.Format.XFDF,PdfLimits.DEFAULT,policy,ExecutionOptions.DEFAULT);require(passive.report().issues().getFirst().feature().equals("FORM_DATA_PROPERTIES"),"passive property loss absent");var passiveResult=adapter.editObjects(filledInput).policy(policy).importFormData(passive,font,10).prepare();writeReport(output.resolve("passive-data-"+label+".losses.tsv"),passiveResult.to(output.resolve("passive-data-"+label+".pdf")).report());pdfs++;
            for(String name:List.of("xfa-static","xfa-xdp","xfa-dynamic")){
                var xfaInput=adapter.input(fixtures.resolve(name+".pdf"),"");var result=adapter.xfa(xfaInput).read();require(result.snapshot().pages().size()==(name.equals("xfa-dynamic")?2:1),"XFA declared pagination changed");var converted=adapter.xfa(xfaInput).policy(policy).flatten(font,10);writeReport(output.resolve(name+"-"+label+".losses.tsv"),converted.to(output.resolve(name+"-"+label+".pdf")).report());pdfs++;
            }
            for(String name:List.of("xfa-invalid-script.pdf","xfa-invalid-external.pdf")){
                Files.write(protectedTarget,sentinel);expectHard(()->adapter.xfa(adapter.input(fixtures.resolve(name),"")).policy(policy).flatten(font,10).to(protectedTarget));rejected++;protectedTargets++;unchanged(protectedTarget,sentinel);
            }
            for(String name:List.of("invalid-action.fdf","invalid-xxe.xfdf")){Files.write(protectedTarget,sentinel);expectHard(()->{var data=PdfFormData.parse(Files.readAllBytes(fixtures.resolve(name)),name.endsWith(".fdf")?PdfFormData.Format.FDF:PdfFormData.Format.XFDF,PdfLimits.DEFAULT,policy,ExecutionOptions.DEFAULT);adapter.editObjects(input).policy(policy).importFormData(data,font,10).prepare().to(protectedTarget);});rejected++;protectedTargets++;unchanged(protectedTarget,sentinel);}
        }
        Files.write(protectedTarget,sentinel);expectHard(()->adapter.editObjects(input).fillText("comb","TOO-LONG-FOR-EIGHT",font,10).prepare().to(protectedTarget));rejected++;protectedTargets++;unchanged(protectedTarget,sentinel);
        Files.write(protectedTarget,sentinel);expectHard(()->adapter.editObjects(input).fillChoices("list",List.of("UNKNOWN"),font,10).prepare().to(protectedTarget));rejected++;protectedTargets++;unchanged(protectedTarget,sentinel);
        var auto=adapter.editObjects(input).fillTextAuto("comb","ABCDEFGH",font,4,100).prepare();auto.to(output.resolve("auto.pdf"));pdfs++;
        expectLoss(()->adapter.editObjects(input).importFormData(PdfFormData.of(List.of(new PdfFormData.FieldValue("unknown","X"))),font,10).prepare(),"FIELD_MAPPING");rejected++;
        expectHard(()->adapter.xfa(adapter.input(fixtures.resolve("xfa-static.pdf"),"")).execution(ExecutionOptions.builder().cancelled(()->true).build()).read());rejected++;
        var tiny=PdfAdapter.builder().limits(PdfLimits.builder().maxTextCharacters(10).build()).build();expectHard(()->tiny.formData(tiny.input(source,"")).policy(policy(ProcessingPolicy.Action.IGNORE)).prepare());rejected++;
        require(Arrays.equals(source,Files.readAllBytes(fixtures.resolve("forms.pdf"))),"source modified");Files.writeString(output.resolve("summary.json"),"{\"pdfOutputs\":"+pdfs+",\"exchangeOutputs\":"+exchanges+",\"rejections\":"+rejected+",\"protectedTargets\":"+protectedTargets+"}\n");System.out.println("Extended PDF forms, six markup types, actual FDF/XFDF exchange and static/dynamic XFA verified");
    }
    private static PdfObjectEditor filled(PdfObjectEditor editor,PdfFont regular,PdfFont bold){return editor.fillText("multi","FIRST LINE\nSECOND LINE",regular,12).fillText("comb","AB12CD34",regular,12).fillRichText("rich",List.of(new PdfFieldText.Run("RED ",regular,12,0xcc2200,false),new PdfFieldText.Run("BOLD ",bold,12,0x001122,false),new PdfFieldText.Run("UNDERLINED",regular,12,0x0033cc,true))).fillChoices("list",List.of("A","C"),regular,12);}
    private static void addAnnotations(PdfObjectEditor editor){int index=0;for(var type:List.of(PdfAnnotationSpec.Type.HIGHLIGHT,PdfAnnotationSpec.Type.UNDERLINE,PdfAnnotationSpec.Type.STRIKEOUT)){double y=398-index++*50;var box=new PdfAdapter.Box(28,y-2,250,22);var quad=new PdfAnnotationSpec.Quad(new PdfAnnotationSpec.Point(30,y+16),new PdfAnnotationSpec.Point(270,y+16),new PdfAnnotationSpec.Point(30,y),new PdfAnnotationSpec.Point(270,y));editor.addAnnotation(1,new PdfAnnotationSpec(type,box,type.name(),List.of(quad),List.of(),type==PdfAnnotationSpec.Type.HIGHLIGHT?0xffdd00:0xcc2222,-1,1,type==PdfAnnotationSpec.Type.HIGHLIGHT?.4:1));}
        editor.addAnnotation(1,new PdfAnnotationSpec(PdfAnnotationSpec.Type.INK,new PdfAdapter.Box(30,180,140,70),"INK",List.of(),List.of(List.of(new PdfAnnotationSpec.Point(32,182),new PdfAnnotationSpec.Point(70,245),new PdfAnnotationSpec.Point(160,195))),0x2244cc,-1,2,1));
        editor.addAnnotation(1,new PdfAnnotationSpec(PdfAnnotationSpec.Type.SQUARE,new PdfAdapter.Box(220,190,60,60),"SQUARE",List.of(),List.of(),0x225522,0xccffcc,2,1));editor.addAnnotation(1,new PdfAnnotationSpec(PdfAnnotationSpec.Type.CIRCLE,new PdfAdapter.Box(300,190,70,60),"CIRCLE",List.of(),List.of(),0x3333cc,-1,2,1));}
    private static ProcessingPolicy policy(ProcessingPolicy.Action action){var builder=ProcessingPolicy.builder().defaultRule(action,ProcessingPolicy.Treatment.DEGRADE);for(String feature:List.of("FORMS","FORM_DATA_APPEARANCE","FORM_DATA_PROPERTIES","FORM_DATA_RICH_TEXT","FIELD_RICH_TEXT","XFA_INTERACTIVITY","XFA_PACKETS","XFA_LAYOUT")){builder.feature(feature,action,ProcessingPolicy.Treatment.DISCARD);}return builder.feature("FIELD_MAPPING",action,ProcessingPolicy.Treatment.SKIP_MATCH).build();}
    private static void report(ProcessingReport preview,ProcessingReport actual,ProcessingPolicy.Action action){require(preview.phase()==ProcessingReport.Phase.PREVIEW&&actual.phase()==ProcessingReport.Phase.ACTUAL&&preview.issues().equals(actual.issues()),"preview/actual differ");require(actual.issues().stream().allMatch(issue->issue.action()==action),"action changed");require((action==ProcessingPolicy.Action.IGNORE)==actual.warnings().isEmpty()||actual.issues().isEmpty(),"warnings differ");}
    private static void writeReport(Path path,ProcessingReport report)throws Exception{Files.writeString(path,"stage\tfeature\taction\ttreatment\teffects\n"+report.issues().stream().map(issue->issue.stage()+"\t"+issue.feature()+"\t"+issue.action()+"\t"+issue.treatment()+"\t"+String.join(",",issue.effects())).collect(Collectors.joining("\n"))+"\n");}
    private static void expectLoss(Checked operation,String feature)throws Exception{try{operation.run();throw new IllegalStateException("strict loss accepted");}catch(ProcessingException expected){require(expected.issue().feature().equals(feature),"unexpected loss: "+expected.issue().feature());}}
    private static void expectHard(Checked operation)throws Exception{try{operation.run();throw new IllegalStateException("invalid input accepted");}catch(PdfOperationException|IllegalArgumentException|ExecutionException expected){}}
    private static void unchanged(Path target,byte[] sentinel)throws Exception{require(Arrays.equals(sentinel,Files.readAllBytes(target)),"old target changed");}
    private static void require(boolean value,String message){if(!value){throw new IllegalStateException(message);}}
}
