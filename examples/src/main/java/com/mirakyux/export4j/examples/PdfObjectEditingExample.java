// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfFont;
import com.mirakyux.export4j.adapter.pdf.PdfObjects;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.FormStyle;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Four real field types, passive objects and opaque attachments, followed by explicit flattening. */
public final class PdfObjectEditingExample {
    private PdfObjectEditingExample() { }
    public static void main(String[] args) throws IOException {
        Path output = Path.of("examples/build/pdf-object-editing"); Files.createDirectories(output);
        var face = AuthoringExample.face("LiberationSans-Regular.ttf"); var engine = DocumentEngine.builder(FontGroup.strict(List.of(face))).build();
        var body = Document.builder().pageSettings(page -> page.size(360, 320).margins(24)).paragraph("PDF OBJECT EDITING")
                .paragraph("Text / choice / checkbox / radio below")
                .add(new PageBreak("next")).paragraph("PAGE TWO TARGET").build();
        var scratch = new ByteArrayOutputStream(); var display = engine.export(body).backend(new PdfBackend()).to(scratch).display();
        var style = FormStyle.builder().fontSize(12).color(new RgbColor(.1, .2, .6)).build();
        var extras = new DocumentExtras(List.of(new DocumentExtras.Bookmark("ORIGINAL OUTLINE", 1, 1, new Point(24, 24))), List.of(), List.of(), List.of(
                new DocumentExtras.TextField(0, new Rect(24, 105, 200, 28), "name", "NAME-OLD", false, List.of(face.data()), style),
                new DocumentExtras.ChoiceField(0, new Rect(24, 150, 200, 28), "status", List.of("Draft", "Ready"), "Draft", List.of(face.data()), style),
                new DocumentExtras.CheckBox(0, new Rect(24, 200, 22, 22), "approved", false),
                new DocumentExtras.RadioGroup(0, new Rect(24, 245, 110, 22), "mode", List.of("One", "Two"), "One")));
        try (var stream = Files.newOutputStream(output.resolve("source.pdf"))) { new PdfBackend().write(new DisplayDocument(display.metadata(), display.pages(), extras), stream); }
        var adapter = new PdfAdapter(); var font = PdfFont.of(face.data().bytes());
        var prepared = adapter.editObjects(adapter.input(output.resolve("source.pdf"), ""))
                .fillText("name", "NAME-READY", font, 12).fillChoice("status", "Ready", font, 12).setChecked("approved", true).selectRadio("mode", "Two")
                .metadata("Title", "Edited object fixture").addComment(0, new PdfAdapter.Box(280, 200, 24, 24), "COMMENT-KEEP")
                .addLink(0, new PdfAdapter.Box(250, 150, 60, 20), "https://example.org/review")
                .addPageLink(0, new PdfAdapter.Box(250, 120, 60, 20), 1)
                .bookmarks(List.of(new PdfObjects.Bookmark("REPORT", 0, List.of(new PdfObjects.Bookmark("DETAIL", 1, List.of())))))
                .attachment("evidence.txt", "ATTACHMENT-KEEP".getBytes(StandardCharsets.UTF_8), "text/plain").prepare();
        var result = prepared.to(output.resolve("edited.pdf"));
        if (result.changes() != 10 || !result.report().issues().isEmpty() || !prepared.preview().issues().equals(result.report().issues())) { throw new IllegalStateException("PDF object edit report mismatch"); }
        var flattenPolicy = ProcessingPolicy.builder().feature("FORMS", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD).build();
        var flattened = adapter.editObjects(adapter.input(output.resolve("edited.pdf"), "")).policy(flattenPolicy).flattenForms().prepare();
        flattened.to(output.resolve("flattened.pdf"));
        var snapshot = adapter.readObjects(adapter.input(output.resolve("edited.pdf"), ""));
        Files.writeString(output.resolve("objects.tsv"), snapshot.fields().stream().map(field -> field.name() + "\t" + field.value()).collect(java.util.stream.Collectors.joining("\n")) + "\n");
        Files.writeString(output.resolve("losses.tsv"), flattened.preview().losses().getFirst().feature() + "\t" + flattened.preview().losses().getFirst().effects().getFirst() + "\n");
        var compositionPolicy = ProcessingPolicy.builder().feature("DOCUMENT_METADATA", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD).build();
        var first = adapter.input(output.resolve("edited.pdf"), ""); var second = adapter.input(output.resolve("edited.pdf"), "");
        var composed = adapter.composeObjects().policy(compositionPolicy).add(first.allPages(), "report").add(second.pages(1, 0), "copy").prepare();
        composed.to(output.resolve("composed.pdf"));
        var selectionPolicy = ProcessingPolicy.builder().feature("LINK_TARGET", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
                .feature("BOOKMARK_TARGET", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
        var selected = adapter.composeObjects().policy(selectionPolicy).add(first.pages(0), "selected").prepare(); selected.to(output.resolve("selected.pdf"));
        Files.writeString(output.resolve("composition-losses.tsv"), java.util.stream.Stream.concat(composed.preview().issues().stream(), selected.preview().issues().stream())
                .map(issue -> issue.feature() + "\t" + issue.effects().getFirst()).collect(java.util.stream.Collectors.joining("\n")) + "\n");
    }
}
