// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfLimits;
import com.mirakyux.export4j.adapter.pdf.PdfOperationException;
import com.mirakyux.export4j.adapter.pdf.PdfSemanticImporter;
import com.mirakyux.export4j.adapter.pdf.PreparedPdfImport;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Checks real automatic recovery through public APIs and emits independently inspectable models. */
public final class PdfAutomaticRecoveryConsumer {
    private PdfAutomaticRecoveryConsumer() { }
    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of("../../build/verification/pdf-automatic-fixtures");
        Path output = Path.of(System.getProperty("consumer.outputDirectory")).resolve("pdf-automatic-recovery"); Files.createDirectories(output);
        byte[] font = Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Regular.ttf"));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(font)))).build();
        var adapter = new PdfAdapter(); int sources = 0; int conversions = 0; int rejections = 0;
        for (String name : List.of("columns-bands", "three-column-bands", "paragraph-lists", "ruled", "broken-rule", "merged", "nested", "wireless", "wireless-merged", "continued", "aligned-prose", "explicit-override", "ambiguous-grid")) {
            Path source = fixtures.resolve(name + ".pdf"); byte[] original = Files.readAllBytes(source);
            Files.copy(source, output.resolve(name + "-source.pdf"), StandardCopyOption.REPLACE_EXISTING); var input = adapter.input(original, "");
            boolean strict = false; try { request(adapter, original, name).prepare(); } catch (ProcessingException expected) { strict = expected.issue().feature().equals("PDF_LAYOUT"); }
            require(strict, "automatic inference bypassed strict policy"); rejections++;
            PreparedPdfImport.Result previous = null;
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                String label = name + "-" + action.name().toLowerCase(Locale.ROOT); var policy = policy(action);
                var prepared = request(adapter, original, name).policy(policy).prepare(); var result = prepared.execute();
                require(prepared.preview().issues().equals(result.report().issues()), "preview/actual losses differ");
                require(action == ProcessingPolicy.Action.IGNORE == prepared.preview().warnings().isEmpty(), "warning display differs from action");
                if (previous != null) { require(previous.document().equals(result.document()) && previous.sources().equals(result.sources()), "WARN/IGNORE changed recovered content"); }
                previous = result; writeModel(output, label, result);
                for (var target : List.of(new DocxAdapter().conversionTarget(), new HtmlAdapter().conversionTarget(), new MarkdownAdapter().conversionTarget(), engine.conversionTarget("PDF", new PdfBackend()))) {
                    var conversion = request(adapter, original, name).conversion().policy(policy).prepare(target);
                    String extension = target.format().toLowerCase(Locale.ROOT).replace("markdown", "md");
                    var written = conversion.to(output.resolve(label + "." + extension));
                    require(conversion.preview().issues().equals(written.report().issues()), "conversion losses differ after writing"); conversions++;
                }
            }
            require(Arrays.equals(original, Files.readAllBytes(source)) && original.length == input.byteSize(), "automatic recovery changed source bytes"); sources++;
        }
        byte[] source = Files.readAllBytes(fixtures.resolve("columns-bands.pdf"));
        var mutable = adapter.importDocument(adapter.input(source, "")).analysis(PdfSemanticImporter.Analysis.AUTO);
        var conversion = mutable.conversion(); mutable.analysis(PdfSemanticImporter.Analysis.LINES);
        var snapshot = conversion.policy(policy(ProcessingPolicy.Action.WARN)).prepare(new HtmlAdapter().conversionTarget());
        snapshot.to(output.resolve("snapshot.html"));
        var legacy = adapter.importDocument(adapter.input(source, "")).policy(policy(ProcessingPolicy.Action.WARN)).prepare();
        require(legacy.document().sections().getFirst().pageRegions().equals(com.mirakyux.export4j.model.PageRegions.EMPTY), "LINES default changed");
        for (var budget : List.of(PdfLimits.DEFAULT.toBuilder().maxObjects(130).build(), PdfLimits.DEFAULT.toBuilder().maxContentOperators(150).build())) {
            byte[] sentinel = "existing output".getBytes(StandardCharsets.US_ASCII); Path target = output.resolve("budget-protected.html"); Files.write(target, sentinel);
            boolean rejected = false;
            try { request(PdfAdapter.builder().limits(budget).build(), source, "columns-bands").conversion().policy(policy(ProcessingPolicy.Action.IGNORE)).prepare(new HtmlAdapter().conversionTarget()).to(target); }
            catch (PdfOperationException expected) { rejected = expected.code() == PdfOperationException.Code.OBJECT_LIMIT || expected.code() == PdfOperationException.Code.OPERATOR_LIMIT; }
            require(rejected && Arrays.equals(sentinel, Files.readAllBytes(target)), "automatic work budget bypassed policy/atomicity"); rejections++;
        }
        boolean cancelled = false;
        try { request(adapter, source, "columns-bands").policy(policy(ProcessingPolicy.Action.IGNORE)).execution(ExecutionOptions.builder().cancelled(() -> true).build()).prepare(); }
        catch (ExecutionException expected) { cancelled = true; }
        require(cancelled, "automatic recovery ignored cancellation"); rejections++;
        Files.writeString(output.resolve("summary.json"), "{\"sources\":" + sources + ",\"conversions\":" + conversions + ",\"rejections\":" + rejections + "}\n");
        System.out.println("Automatic PDF recovery: columns, paragraphs, lists, bands, tables, provenance and failures verified");
    }
    private static PdfSemanticImporter request(PdfAdapter adapter, byte[] source, String name) throws java.io.IOException {
        var request = adapter.importDocument(adapter.input(source, "")).analysis(PdfSemanticImporter.Analysis.AUTO);
        if (name.equals("explicit-override")) { request.table(0, List.of(50d, 350d), List.of(100d, 180d), 0); }
        return request;
    }
    private static ProcessingPolicy policy(ProcessingPolicy.Action action) {
        return ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DEGRADE)
                .feature("PDF_VECTOR_GRAPHICS", action, ProcessingPolicy.Treatment.DISCARD)
                .feature("PAGE_REGIONS", action, ProcessingPolicy.Treatment.DEGRADE)
                .feature("PDF_STRUCTURE", ProcessingPolicy.Action.ERROR, ProcessingPolicy.Treatment.SKIP_BLOCK).build();
    }
    private static void writeModel(Path output, String label, PreparedPdfImport.Result result) throws Exception {
        var rows = new ArrayList<String>(); rows.add("scope\tpath\ttype\tid\ttext64\trows\tcolumns\trowSpan\tcolumnSpan\theaderRows\tlevel\tnumber");
        var ids = new HashSet<String>(); int section = 0;
        for (var value : result.document().sections()) {
            write(rows, ids, "BODY", "s"+section, value.blocks()); write(rows, ids, "HEADER", "s"+section+".h", value.pageRegions().header().blocks()); write(rows, ids, "FOOTER", "s"+section+".f", value.pageRegions().footer().blocks()); section++;
        }
        Files.write(output.resolve(label + ".model.tsv"), rows, StandardCharsets.UTF_8);
        var sources = new ArrayList<String>(); sources.add("id\tpage\tx\ty\twidth\theight\tcellPath");
        for (var value : result.sources()) { require(ids.contains(value.blockId()), "source map refers to a missing semantic block"); sources.add(value.blockId()+"\t"+value.page()+"\t"+value.x()+"\t"+value.y()+"\t"+value.width()+"\t"+value.height()+"\t"+value.cellPath().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("/"))); }
        Files.write(output.resolve(label + ".sources.tsv"), sources, StandardCharsets.UTF_8);
        var losses = new ArrayList<String>(); losses.add("feature\taction\ttreatment\tregion\teffects");
        for (var issue : result.report().issues()) { losses.add(issue.feature()+"\t"+issue.action()+"\t"+issue.treatment()+"\t"+issue.location().region()+"\t"+String.join(";", issue.effects())); }
        Files.write(output.resolve(label + ".losses.tsv"), losses, StandardCharsets.UTF_8);
    }
    private static void write(List<String> rows, Set<String> ids, String scope, String path, List<Block> blocks) {
        for (int index = 0; index < blocks.size(); index++) {
            var block = blocks.get(index); require(ids.add(block.id()), "duplicate recovered block ID"); String current = path+".b"+index;
            if (block instanceof Table table) {
                rows.add(scope+"\t"+current+"\tTABLE\t"+table.id()+"\t\t"+table.rows().size()+"\t"+table.columns().size()+"\t0\t0\t"+table.headerRows()+"\t0\t0");
                for (var anchors : table.grid().anchorRows()) {
                    for (var anchor : anchors) {
                        var cell = anchor.cell(); int row = anchor.rowIndex(); int column = anchor.columnIndex();
                        String cellPath = current+".r"+row+".c"+column; rows.add(scope+"\t"+cellPath+"\tCELL\t\t"+encoded(cell.hasBlocks() ? "" : cell.text())+"\t0\t0\t"+cell.rowSpan()+"\t"+cell.columnSpan()+"\t0\t0\t0");
                        if (cell.hasBlocks()) { write(rows, ids, scope, cellPath, cell.blocks()); }
                    }
                }
            } else {
                String text; int level = 0; int number = 0; String type = "PARAGRAPH";
                if (block instanceof Paragraph value) { text = value.text(); }
                else if (block instanceof Heading value) { text = value.text(); level = value.level(); type = "HEADING"; }
                else if (block instanceof BulletItem value) { text = value.text(); level = value.level(); type = "BULLET"; }
                else if (block instanceof NumberedItem value) { text = value.text(); level = value.level(); number = value.restartAt(); type = "NUMBER"; }
                else { throw new IllegalStateException("unknown recovered block"); }
                rows.add(scope+"\t"+current+"\t"+type+"\t"+block.id()+"\t"+encoded(text)+"\t0\t0\t0\t0\t0\t"+level+"\t"+number);
            }
        }
    }
    private static String encoded(String text) { return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
    private static void require(boolean condition, String message) { if (!condition) { throw new IllegalStateException(message); } }
}
