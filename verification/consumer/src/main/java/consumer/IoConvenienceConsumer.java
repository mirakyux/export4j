// SPDX-License-Identifier: Apache-2.0
package consumer;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.template.XmlModelRecovery;
import com.mirakyux.export4j.adapter.template.XmlTemplateWriter;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.pdf.PdfSpoolBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageDocument;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;

/** Exercises convenient local-file APIs through isolated published artifacts, without implicit resource discovery. */
public final class IoConvenienceConsumer {
    private IoConvenienceConsumer() { }

    public static void main(String[] args) throws Exception {
        var output = Files.createDirectories(Path.of(System.getProperty("consumer.outputDirectory")).resolve("io-convenience"));
        var temporary = Files.createDirectories(output.resolve("temporary"));
        var face = TrueTypeFontLoader.load("../../assets/fonts/LiberationSans-Regular.ttf");
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(face))).build();
        var body = Document.builder().paragraph("Convenient file targets.").build();
        var paged = new PageDocument(body, new PageRegions(new PageRegion(24, 2,
                List.of(new Paragraph("header", "Header retained", ParagraphFormat.of(12)))), PageRegion.EMPTY));
        var sectioned = new SectionedDocument(body.metadata(), List.of(new Section("main", body.pageSettings(), body.blocks(), 0)));
        var pdf = new PdfBackend();
        engine.write(body, pdf, output.resolve("direct.pdf").toString());
        engine.write(body, pdf, output.resolve("direct-file.pdf").toFile(), ExecutionOptions.DEFAULT);
        engine.writeAtomically(body, pdf, output.resolve("atomic.pdf").toString(), ExecutionOptions.DEFAULT);
        engine.writeAtomically(body, pdf, output.resolve("atomic-file.pdf").toFile());
        engine.write(paged, pdf, output.resolve("paged.pdf").toString());
        engine.write(paged, pdf, output.resolve("paged-file.pdf").toFile(), ExecutionOptions.DEFAULT);
        engine.write(sectioned, pdf, output.resolve("sectioned.pdf").toString(), ExecutionOptions.DEFAULT);
        engine.write(sectioned, pdf, output.resolve("sectioned-file.pdf").toFile());
        engine.export(body).backend(pdf).to(output.resolve("fluent.pdf").toString());
        engine.export(body).backend(pdf).to(output.resolve("fluent-file.pdf").toFile());

        var spec = StreamingTable.builder("rows").columns(120).header("ID").build();
        TableRowSource.Factory rows = () -> new TableRowSource() {
            private int row;
            @Override public Optional<TableRow> next() {
                return row < 3 ? Optional.of(new TableRow(List.of(new TableCell("R" + ++row, CellFormat.of(12))))) : Optional.empty();
            }
        };
        engine.export(spec, rows).backend(new PdfSpoolBackend(temporary.toFile())).to(output.resolve("stream.pdf").toString());
        engine.exportReplay(spec, rows).backend(new PdfSpoolBackend(temporary.toString())).to(output.resolve("replay.pdf").toFile());
        engine.exportSemantic(spec, rows).backend(new PdfSpoolBackend(temporary)).to(output.resolve("semantic.pdf").toString());
        new HtmlAdapter().export(body).to(output.resolve("report.html").toString());
        new MarkdownAdapter().export(body).to(output.resolve("report.md").toFile());

        var xml = XmlTemplateWriter.from(body).prepare();
        xml.to(output.resolve("template.zip").toString());
        xml.to(output.resolve("template-file.zip").toFile());
        xml.bundle().write(output.resolve("bundle.zip").toString());
        xml.bundle().write(output.resolve("bundle-file.zip").toFile());
        var docx = new DocxAdapter(); var encoded = new ByteArrayOutputStream(); docx.write(body, encoded);
        var converted = docx.conversion(encoded.toByteArray()).prepare(docx.conversionTarget());
        converted.to(output.resolve("converted.docx").toString());
        converted.to(output.resolve("converted-file.docx").toFile());
        var recovered = XmlModelRecovery.capture(converted).prepare();
        recovered.writeDocument(output.resolve("recovered.docx").toString());
        recovered.writeCompanion(output.resolve("companion.zip").toFile());
        recovered.writeEmbedded(output.resolve("embedded.docx").toString());

        var adapter = new PdfAdapter();
        var input = adapter.input(output.resolve("direct.pdf").toString(), "");
        var fileInput = adapter.input(output.resolve("direct.pdf").toFile(), "");
        adapter.copy(input.pages(0), output.resolve("copy.pdf").toFile());
        adapter.merge(List.of(input.pages(0), fileInput.pages(0)), output.resolve("merged.pdf").toString(), ExecutionOptions.DEFAULT);
        var optimized = adapter.optimize(input).prepare();
        optimized.to(output.resolve("optimized.pdf").toString());
        optimized.to(output.resolve("optimized-file.pdf").toFile());
        adapter.editPages(input).prepare().to(output.resolve("edited.pdf").toFile());
        var data = adapter.formData(input).prepare();
        data.to(output.resolve("form.xfdf").toString()); data.to(output.resolve("form-file.xfdf").toFile());
        var images = adapter.extractImages(input.pages(0)).prepare();
        images.toZip(output.resolve("images.zip").toString()); images.toZip(output.resolve("images-file.zip").toFile());
        try (var files = Files.list(temporary)) {
            if (files.findAny().isPresent()) { throw new IllegalStateException("temporary spool files remain"); }
        }
        System.out.println("Local file convenience APIs completed: " + output);
    }
}
