// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.HtmlImporter;
import com.mirakyux.export4j.adapter.web.ImportLimits;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Isolated Maven caller; the verifier compares real output against an independent browser. */
public final class HtmlCssLayoutConsumer {
    private static final PageSettings PAGE = new PageSettings(new PageSize(420, 300), PageMargins.uniform(20));
    private static final String BASE = "<style>p{margin:0;padding-left:4pt;box-sizing:border-box;font-size:12pt;line-height:18pt;font-weight:normal}</style>";
    private record Scene(String name, String html) { }
    private HtmlCssLayoutConsumer() { }

    public static void main(String[] arguments) throws Exception {
        var output = Path.of(System.getProperty("consumer.outputDirectory", "build/output")).resolve("html-css-layout");
        Files.createDirectories(output);
        var font = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Regular.ttf")));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(font))).build();
        var scenes = List.of(
                new Scene("flex-grow", container("display:flex;width:360pt;height:70pt;gap:12pt", cell("flex:1 1 60pt;background-color:#d85b36", "GROW-A") + cell("flex:2 1 60pt;background-color:#3574bd", "GROW-B"))),
                new Scene("flex-shrink", container("display:flex;width:360pt;height:60pt", cell("flex:0 1 240pt;background-color:#d85b36", "SHRINK-A") + cell("flex:0 3 240pt;background-color:#3574bd", "SHRINK-B"))),
                new Scene("flex-wrap", container("display:flex;flex-wrap:wrap;align-content:flex-start;width:360pt;height:70pt;row-gap:6pt;column-gap:12pt;align-items:start", cell("flex:0 0 100pt;height:24pt;background-color:#d85b36", "WRAP-A") + cell("flex:0 0 100pt;height:24pt;background-color:#3574bd", "WRAP-B") + cell("flex:0 0 100pt;height:24pt;background-color:#41965b", "WRAP-C") + cell("flex:0 0 100pt;height:24pt;background-color:#a26cbb", "WRAP-D"))),
                new Scene("flex-column", container("display:flex;flex-direction:column;width:360pt;height:120pt;gap:12pt", cell("flex:1;background-color:#d85b36", "COLUMN-A") + cell("flex:2;background-color:#3574bd", "COLUMN-B"))),
                new Scene("grid", container("display:grid;width:360pt;grid-template-columns:80pt 1fr 2fr;grid-template-rows:40pt 40pt;gap:12pt", cell("grid-column:1 / span 2;background-color:#d85b36", "GRID-A") + cell("background-color:#3574bd", "GRID-B") + cell("grid-column:span 3;background-color:#41965b", "GRID-C"))),
                new Scene("grid-span", container("display:grid;width:360pt;grid-template-columns:repeat(2,1fr);grid-template-rows:40pt 40pt;gap:6pt", cell("grid-row:1 / span 2;grid-column:1;background-color:#d85b36", "SPAN-A") + cell("grid-column:2;grid-row:1;background-color:#3574bd", "SPAN-B") + cell("grid-column:2;grid-row:2;background-color:#41965b", "SPAN-C"))),
                new Scene("position", container("position:relative;width:360pt;height:210pt;padding:12pt;box-sizing:border-box;background-color:#eee9dd", cell("position:relative;left:8pt;top:4pt;width:50%;min-width:160pt;max-width:200pt;height:30pt;background-color:#d85b36", "RELATIVE") + cell("position:absolute;right:18pt;bottom:18pt;width:80pt;height:30pt;background-color:#3574bd", "ABSOLUTE"))),
                new Scene("body", "<body style='display:flex;width:360pt;height:70pt;gap:12pt'>" + cell("flex:1;background-color:#d85b36", "BODY-A") + cell("flex:1;background-color:#3574bd", "BODY-B") + "</body>"));
        for (var scene : scenes) {
            String source = BASE + scene.html(); Files.writeString(output.resolve(scene.name() + ".html"), source);
            var document = read(source, ProcessingPolicy.STRICT);
            var layout = engine.layout(document);
            if (layout.display().pages().size() != 1 || !layout.diagnostics().isEmpty()) { throw new IllegalStateException("CSS scene did not produce one clean page"); }
            try (var stream = Files.newOutputStream(output.resolve(scene.name() + ".pdf"))) { new PdfBackend().write(layout.display(), stream); }
            try (var stream = Files.newOutputStream(output.resolve(scene.name() + ".svg"))) { new SvgBackend().write(layout.display(), stream); }
        }
        String pagination = "<style>p{margin-top:0;margin-bottom:0;font-size:12pt;line-height:18pt;font-weight:normal}@page{size:300pt 240pt;margin:20pt}@media print{p{color:#123456}}@media screen{p{color:red}}</style><p>PAGE-ONE</p><p style='break-before:page;break-after:page;break-inside:avoid;orphans:2;widows:3'>PAGE-TWO</p><p>PAGE-THREE</p>";
        var pages = HtmlImporter.builder().pageSettings(PAGE).build().read(pagination).execute().document();
        engine.write(pages, new PdfBackend(), output.resolve("pagination.pdf"));
        String linkSource = BASE + container("display:flex;width:360pt;height:70pt;gap:12pt", cell("flex:1", "<a href='https://example.org/css-layout'>LINK-TEXT</a>") + cell("flex:1", "LINK-OTHER"));
        engine.write(read(linkSource, ProcessingPolicy.STRICT), new PdfBackend(), output.resolve("links.pdf"));
        int losses = 0, refusals = 0;
        String fallback = BASE + container("display:flex;width:360pt", "<p>FALLBACK-A</p><p>FALLBACK-B</p>");
        try { read(fallback, ProcessingPolicy.STRICT); throw new IllegalStateException("intrinsic flex silently accepted"); } catch (ProcessingException expected) { refusals++; }
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var policy = ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DEGRADE).build();
            var prepared = importer(policy).read(fallback).prepare(); var actual = prepared.execute();
            if (!prepared.preview().losses().equals(actual.report().losses()) || actual.report().losses().size() != 1 || actual.report().warnings().isEmpty() != (action == ProcessingPolicy.Action.IGNORE)) { throw new IllegalStateException("CSS fallback reports differ"); }
            losses += actual.report().losses().size();
            engine.write(actual.document(), new PdfBackend(), output.resolve("fallback-" + action.name().toLowerCase(java.util.Locale.ROOT) + ".pdf"));
        }
        var component = read(BASE + scenes.getFirst().html(), ProcessingPolicy.STRICT);
        var semanticTarget = new Document(component.metadata(), PageSettings.A4, component.blocks());
        for (var target : List.of(new HtmlAdapter().conversionTarget(), new MarkdownAdapter().conversionTarget())) {
            try { DocumentConversion.from(semanticTarget).prepare(target); throw new IllegalStateException("runtime CSS component silently exported as semantics"); } catch (ProcessingException expected) { if (!expected.issue().feature().equals("CUSTOM_BLOCK")) { throw new IllegalStateException("unrelated loss masked runtime-component refusal", expected); } refusals++; }
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                var policy = ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DISCARD).build();
                var prepared = DocumentConversion.from(semanticTarget).policy(policy).prepare(target); var bytes = new ByteArrayOutputStream(); var actual = prepared.to(bytes);
                if (actual.report().losses().isEmpty() || bytes.toString(java.nio.charset.StandardCharsets.UTF_8).contains("GROW-A")) { throw new IllegalStateException("runtime component DISCARD did not match output"); }
                losses += actual.report().losses().size();
            }
        }
        for (String unsafe : List.of("<style>@media print{p{background:url(https://example.org/a)}}</style><p>X</p>", "<p onclick='run()'>X</p>")) {
            try { read(unsafe, ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DISCARD).build()); throw new IllegalStateException("inactive/ignored active source accepted"); } catch (IllegalArgumentException expected) { refusals++; }
        }
        try { HtmlImporter.builder().layoutMode(true).limits(new ImportLimits(1000, 1000, 100, 20, 1000, 100, 100, 20)).build().read(BASE + scenes.getFirst().html()).execute(); throw new IllegalStateException("work budget ignored"); } catch (IllegalArgumentException expected) { refusals++; }
        var old = output.resolve("atomic-old.pdf"); Files.writeString(old, "OLD-DESTINATION");
        try { engine.write(component, new PdfBackend(), old, ExecutionOptions.builder().cancelled(() -> true).build()); throw new IllegalStateException("cancelled CSS layout succeeded"); }
        catch (ExecutionException expected) { if (expected.code() != ExecutionException.Code.CANCELLED || !Files.readString(old).equals("OLD-DESTINATION")) { throw new IllegalStateException("CSS cancellation changed the destination"); } refusals++; }
        try { engine.write(component, new PdfBackend(), old, ExecutionOptions.builder().maxOutputBytes(32).build()); throw new IllegalStateException("CSS output budget succeeded"); }
        catch (ExecutionException expected) { if (expected.code() != ExecutionException.Code.OUTPUT_LIMIT || !Files.readString(old).equals("OLD-DESTINATION")) { throw new IllegalStateException("CSS output budget changed the destination"); } refusals++; }
        Files.writeString(output.resolve("summary.json"), "{\"scenes\":" + scenes.size() + ",\"losses\":" + losses + ",\"refusals\":" + refusals + ",\"paginationPages\":3,\"atomicControls\":2}\n");
    }
    private static String cell(String style, String text) { return "<p style='" + style + "'>" + text + "</p>"; }
    private static String container(String style, String children) { return "<div style='" + style + "'>" + children + "</div>"; }
    private static HtmlImporter importer(ProcessingPolicy policy) { return HtmlImporter.builder().layoutMode(true).pageSettings(PAGE).media(HtmlImporter.Media.SCREEN).viewport(PAGE.size()).policy(policy).build(); }
    private static Document read(String source, ProcessingPolicy policy) throws java.io.IOException { return importer(policy).read(source).execute().document(); }
}
