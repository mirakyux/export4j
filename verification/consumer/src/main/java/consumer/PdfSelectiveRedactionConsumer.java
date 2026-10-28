// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfLimits;
import com.mirakyux.export4j.adapter.pdf.PdfOperationException;
import com.mirakyux.export4j.adapter.pdf.PdfSelectiveRedactor;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Uses public selective redaction APIs and produces externally inspectable PDF candidates. */
public final class PdfSelectiveRedactionConsumer {
    private PdfSelectiveRedactionConsumer() { }
    public static void main(String[] args) throws Exception {
        Path fixtures=Path.of("../../build/verification/pdf-selective-redaction-fixtures");
        Path output=Path.of(System.getProperty("consumer.outputDirectory")).resolve("pdf-selective-redaction"); Files.createDirectories(output);
        var selections=Files.readAllLines(fixtures.resolve("regions.tsv")); var adapter=new PdfAdapter(); int sources=0,outputs=0,rejections=0;
        for(String name:List.of("text-vector","nested-form","image-instance","image-clip","crop-0","crop-90","crop-180","crop-270","clipped-text","unsupported-alpha","unsupported-operator","combined-depth","malformed-nested-bt","malformed-missing-et","malformed-orphan-et","malformed-orphan-emc","malformed-missing-emc","unicode-budget")) {
            byte[] original=Files.readAllBytes(fixtures.resolve(name+".pdf")); Files.copy(fixtures.resolve(name+".pdf"),output.resolve(name+"-source.pdf"),StandardCopyOption.REPLACE_EXISTING);
            if(name.startsWith("unsupported-") || name.startsWith("malformed-") || name.equals("combined-depth") || name.equals("unicode-budget")) {
                var checked=name.equals("combined-depth")?PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxDepth(32).build()).build():name.equals("unicode-budget")?PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxTextCharacters(1).build()).build():adapter;
                var expectedCode=name.equals("combined-depth")?PdfOperationException.Code.DEPTH_LIMIT:name.equals("unicode-budget")?PdfOperationException.Code.TEXT_LIMIT:name.startsWith("malformed-")?PdfOperationException.Code.INVALID_PDF:PdfOperationException.Code.UNSUPPORTED_FEATURE;
                Path protectedTarget=output.resolve("unsupported-protected.pdf"); byte[] sentinel="existing output".getBytes(StandardCharsets.US_ASCII); Files.write(protectedTarget,sentinel);
                boolean rejected=false; try { request(checked,original,name,selections).policy(policy(ProcessingPolicy.Action.IGNORE)).prepare().to(protectedTarget); }
                catch(PdfOperationException expected) { rejected=expected.code()==expectedCode; }
                require(rejected && Arrays.equals(sentinel,Files.readAllBytes(protectedTarget)),"unsupported input produced partially redacted success"); rejections++; continue;
            }
            boolean strict=false; try { request(adapter,original,name,selections).prepare(); } catch(ProcessingException expected) { strict=true; }
            require(strict,"content reconstruction bypassed strict policy"); rejections++;
            for(var action:List.of(ProcessingPolicy.Action.WARN,ProcessingPolicy.Action.IGNORE)) {
                String label=name+"-"+action.name().toLowerCase(Locale.ROOT); var prepared=request(adapter,original,name,selections).policy(policy(action)).prepare();
                var result=prepared.to(output.resolve(label+".pdf")); require(result.pageCount()==(name.equals("text-vector")?2:1),"selective redaction lost a page");
                require(prepared.preview().issues().equals(result.report().issues()),"selective preview differs from actual losses");
                require(action==ProcessingPolicy.Action.IGNORE==prepared.preview().warnings().isEmpty(),"IGNORE warnings were displayed");
                var losses=new ArrayList<String>(); losses.add("feature\taction\ttreatment\teffects");
                for(var issue:result.report().issues()) { losses.add(issue.feature()+"\t"+issue.action()+"\t"+issue.treatment()+"\t"+String.join(";",issue.effects())); }
                Files.write(output.resolve(label+".losses.tsv"),losses,StandardCharsets.UTF_8); outputs++;
            }
            require(Arrays.equals(original,Files.readAllBytes(fixtures.resolve(name+".pdf"))),"redaction changed the source"); sources++;
        }
        byte[] source=Files.readAllBytes(fixtures.resolve("text-vector.pdf"));
        for(var limits:List.of(PdfLimits.DEFAULT.toBuilder().maxOutputBytes(200).build(),PdfLimits.DEFAULT.toBuilder().maxContentOperators(20).build(),PdfLimits.DEFAULT.toBuilder().maxTextCharacters(4).build())) {
            Path target=output.resolve("protected.pdf"); byte[] sentinel="existing output".getBytes(StandardCharsets.US_ASCII); Files.write(target,sentinel); boolean rejected=false;
            try { request(PdfAdapter.builder().limits(limits).build(),source,"text-vector",selections).policy(policy(ProcessingPolicy.Action.IGNORE)).prepare().to(target); }
            catch(PdfOperationException expected) { rejected=List.of(PdfOperationException.Code.OUTPUT_LIMIT,PdfOperationException.Code.OPERATOR_LIMIT,PdfOperationException.Code.TEXT_LIMIT).contains(expected.code()); }
            require(rejected && Arrays.equals(sentinel,Files.readAllBytes(target)),"budget failure replaced the old target"); rejections++;
        }
        boolean cancelled=false; try { request(adapter,source,"text-vector",selections).policy(policy(ProcessingPolicy.Action.IGNORE)).execution(ExecutionOptions.builder().cancelled(()->true).build()).prepare(); }
        catch(ExecutionException expected) { cancelled=true; } require(cancelled,"redaction ignored cancellation"); rejections++;
        Files.writeString(output.resolve("summary.json"),"{\"sources\":"+sources+",\"outputs\":"+outputs+",\"rejections\":"+rejections+"}\n");
        System.out.println("Selective redaction: retained searchable text/vector content, removal and protected failures verified");
    }
    private static PdfSelectiveRedactor request(PdfAdapter adapter,byte[] source,String name,List<String> selections) throws Exception {
        var result=adapter.redactSelective(adapter.input(source,""));
        for(String line:selections) { String[] cells=line.split("\t"); if(!cells[0].equals(name)) { continue; } result.area(Integer.parseInt(cells[1]),Double.parseDouble(cells[2]),Double.parseDouble(cells[3]),Double.parseDouble(cells[4]),Double.parseDouble(cells[5])); }
        return result;
    }
    private static ProcessingPolicy policy(ProcessingPolicy.Action action) {
        var result=ProcessingPolicy.builder().defaultRule(action,ProcessingPolicy.Treatment.DISCARD);
        for(String feature:List.of("PDF_CONTENT_RECONSTRUCTION","ENCRYPTION_REMOVED","PAGE_GEOMETRY","PDF_CLIPPED_TEXT","PDF_IMAGE_BOUNDARY","PDF_COLOR_MODEL")) { result.feature(feature,action,ProcessingPolicy.Treatment.DEGRADE); }
        return result.build();
    }
    private static void require(boolean condition,String message) { if(!condition) { throw new IllegalStateException(message); } }
}
