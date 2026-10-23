// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfPageEditor;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/** Geometric PDF recovery with an explicit table grid, followed by real editable-model conversion. */
public final class PdfSemanticImportExample {
    private PdfSemanticImportExample() { }
    public static void main(String[] args) throws IOException {
        var output = Path.of("examples/build/pdf-semantic-import"); Files.createDirectories(output);
        var face = AuthoringExample.face("LiberationSans-Regular.ttf"); var engine = DocumentEngine.builder(FontGroup.strict(List.of(face))).build();
        var raster = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB); var graphics = raster.createGraphics(); graphics.setColor(new Color(0x2266AA)); graphics.fillRect(0, 0, 8, 6); graphics.dispose();
        var bytes = new ByteArrayOutputStream(); ImageIO.write(raster, "png", bytes); raster.flush();
        var source = Document.builder().pageSettings(page -> page.size(320, 300).margins(20))
                .heading("RECOVERY TITLE", 1).paragraph("BODY-RECOVERY")
                .table(table -> table.header("NAME", "VALUE").row("ALPHA", "42")).build();
        engine.export(source).backend(new PdfBackend()).to(output.resolve("source.pdf"));
        var adapter = new PdfAdapter();
        adapter.editPages(adapter.input(output.resolve("source.pdf"), "")).image(0, ImageResource.load(bytes.toByteArray()),
                new PdfPageEditor.Rectangle(20, 200, 40, 30), PdfPageEditor.Layer.OVER_CONTENT).prepare().to(output.resolve("source.pdf"));
        // This sample's grid is supplied from its known form specification. General PDFs require
        // caller-provided grid edges; these coordinates are not an automatic table detector.
        var geometry = (TableFragment) engine.layout(source).body().pages().getFirst().elements().stream().filter(TableFragment.class::isInstance).findFirst().orElseThrow();
        var x = List.of(geometry.origin().x(), geometry.origin().x() + geometry.rows().getFirst().cells().getFirst().bounds().width(), geometry.origin().x() + geometry.width());
        var y = new java.util.ArrayList<Double>(); y.add(geometry.origin().y());
        for (var row : geometry.rows()) { y.add(geometry.origin().y() + row.cells().getFirst().bounds().y() + row.cells().getFirst().bounds().height()); }
        var input = adapter.input(output.resolve("source.pdf"), "");
        var policy = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
                .feature("PDF_VECTOR_GRAPHICS", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
                .feature("PDF_LOGICAL_TEXT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
                .feature("PDF_HIDDEN_TEXT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
                .feature("DOCUMENT_METADATA", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD).build();
        var imported = adapter.importDocument(input).table(0, x, y, 1).policy(policy).prepare();
        Files.writeString(output.resolve("sources.tsv"), "block\tpage\tx\ty\twidth\theight\n" + imported.sources().stream()
                .map(item -> item.blockId() + "\t" + item.page() + "\t" + item.x() + "\t" + item.y() + "\t" + item.width() + "\t" + item.height())
                .collect(java.util.stream.Collectors.joining("\n")) + "\n");
        for (var target : List.of(new DocxAdapter().conversionTarget(), new HtmlAdapter().conversionTarget(), new MarkdownAdapter().conversionTarget(), engine.conversionTarget("PDF", new PdfBackend()))) {
            var prepared = adapter.importDocument(input).table(0, x, y, 1).conversion().policy(policy)
                    .edit(editor -> editor.replaceText("ALPHA", "EDITED-CELL")).prepare(target);
            Path path = output.resolve("recovered." + target.format().toLowerCase(java.util.Locale.ROOT).replace("markdown", "md"));
            var result = prepared.to(path); if (!prepared.preview().issues().equals(result.report().issues())) { throw new IllegalStateException("PDF recovery report mismatch"); }
            Files.writeString(path.resolveSibling(path.getFileName() + ".losses.tsv"), result.report().issues().stream()
                    .map(issue -> issue.stage() + "\t" + issue.feature() + "\t" + issue.treatment()).collect(java.util.stream.Collectors.joining("\n")) + "\n");
        }
    }
}
