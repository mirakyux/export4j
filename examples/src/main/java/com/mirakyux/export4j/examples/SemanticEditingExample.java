// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.web.ExportPolicy;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.adapter.web.UnsupportedAction;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageDocument;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.edit.DocumentEditor;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Real semantic editing followed by PDF, DOCX, HTML and Markdown output. All fixture data is synthetic. */
public final class SemanticEditingExample {
    private SemanticEditingExample() { }
    public static void main(String[] args) throws IOException {
        Path output = Path.of("examples/build/semantic-editing"); Files.createDirectories(output);
        var normal = InlineStyle.of(12); var blue = normal.toBuilder().rgb(0x2456a0).build();
        var format = ParagraphFormat.of(12);
        var intro = new Paragraph("intro", List.of(new InlineText("EDIT", blue, "https://example.org/edit"),
                new InlineText("ME", normal), new InlineText(" KEEP-LINK", normal, "https://example.org/keep")), format);
        var nested = Table.text("nested", List.of("INNER-HEADER"), List.of(List.of("INNER-OLD")));
        var table = Table.builder("table").columns(new com.mirakyux.export4j.model.ColumnWidth.Fraction(1),
                new com.mirakyux.export4j.model.ColumnWidth.Fraction(1))
                .row(TableCell.builder().blocks(List.of(new Paragraph("before", "BEFORE-NESTED", format), nested,
                        new Paragraph("after", "AFTER-NESTED", format))).build(), TableCell.builder().text("CELL-OLD").build()).build();
        var source = new PageDocument(Document.builder().heading("Semantic editing", 1).add(intro).add(table)
                .paragraph("tail", "TAIL-UNCHANGED").build(), new PageRegions(
                        new PageRegion(24, 8, List.of(new Paragraph("header", "HEADER-OLD", format))),
                        new PageRegion(24, 8, List.of(Paragraph.mixed("footer", List.of(new InlineText("PAGE ", normal),
                                new InlineField(InlineField.Kind.PAGE, "", 24, normal)), format)))));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf")))).build();
        engine.export(source).backend(new PdfBackend()).to(output.resolve("before.pdf"));
        var policy = ProcessingPolicy.builder().feature("MIXED_RUN_STYLE", ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DEGRADE).build();
        var prepared = DocumentEditor.edit(source).policy(policy).replaceText("OLD", "READY")
                .replaceText("intro", "EDITME", "EDITED").insertBefore("tail", new Paragraph("inserted", "INSERTED-ONCE", format)).prepare();
        var result = prepared.execute();
        if (result.report().losses().size() != 1 || !prepared.preview().issues().equals(result.report().issues())
                || !((Paragraph) source.document().blocks().get(1)).text().startsWith("EDITME")) {
            throw new IllegalStateException("semantic editing/report snapshot differs");
        }
        engine.export(result.document()).backend(new PdfBackend()).to(output.resolve("edited.pdf"));
        try (var stream = Files.newOutputStream(output.resolve("edited.docx"))) { new DocxAdapter().write(result.document(), stream); }
        var webPolicy = ExportPolicy.builder().defaultAction(UnsupportedAction.WARN).build();
        HtmlAdapter.builder().policy(webPolicy).build().export(result.document()).to(output.resolve("edited.html"));
        MarkdownAdapter.builder().policy(webPolicy).build().export(result.document()).to(output.resolve("edited.md"));
        var report = new StringBuilder("phase\tfeature\taction\ttreatment\tblock\tstart\tend\n");
        for (var issue : result.report().issues()) {
            report.append("ACTUAL\t").append(issue.feature()).append('\t').append(issue.action()).append('\t')
                    .append(issue.treatment()).append('\t').append(issue.location().blockId()).append('\t')
                    .append(issue.location().startUtf16()).append('\t').append(issue.location().endUtf16()).append('\n');
        }
        Files.writeString(output.resolve("losses.tsv"), report);
        System.out.println("Semantic editing: " + result.changes().size() + " changes, 1 reported degradation");
    }
}
