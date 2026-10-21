// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Synthetic list markers have independent real fonts, colors and decorations. */
public final class ListMarkerExample {
    private static final Path OUTPUT = Path.of("examples/build/list-markers");
    private ListMarkerExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var format = ParagraphFormat.builder().fontSize(10).lineHeight(20).spaceBefore(0).spaceAfter(0).build();
        var marker = InlineStyle.builder().fontRole("bold").fontSize(12).rgb(0x224488).underline(true).build();
        var italic = InlineStyle.builder().fontRole("italic").fontSize(10).rgb(0x883366).build();
        var source = Document.builder().metadata(new DocumentMetadata("Independent list marker styles", "Export4j synthetic example"))
                .pageSettings(PageSettings.builder().size(360, 280).margins(20).build())
                .add(NumberedItem.builder().id("parent").listId("checks").text("BODY-ONE")
                        .format(format).restartAt(3).markerStyle(marker).build())
                .add(NumberedItem.builder().id("child").listId("checks").inlines(List.of(new InlineText("BODY-TWO", italic)))
                        .format(format).level(2).markerStyle(marker).build())
                .add(BulletItem.builder().id("bullet").text("BULLET-BODY").marker("*").format(format)
                        .markerStyle(style -> style.fontRole("italic").rgb(0x336633).strikeThrough(true)).build())
                .add(new NumberedItem("legacy", "legacy-list", "LEGACY-BODY", 0, format))
                .add(Paragraph.mixed("reference", List.of(new InlineText("REF ", InlineStyle.of(10)),
                        InlineField.builder().kind(InlineField.Kind.REFERENCE_NUMBER).targetId("child").width(36).style(InlineStyle.of(10)).build()), format))
                .add(new PageBreak("break"))
                .add(NumberedItem.builder().id("next").listId("checks").text("BODY-THREE").format(format).markerStyle(marker).build())
                .build();
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf"))))
                .fontRole("bold", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Bold.ttf"))))
                .fontRole("italic", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Italic.ttf")))).build();
        var result = engine.export(source).backend(new PdfBackend()).to(OUTPUT.resolve("report.pdf"));
        if (result.display().pages().size() != 2 || !result.contextualDiagnostics().isEmpty()) {
            throw new IllegalStateException("Independent marker styles must retain both pages");
        }
        // Separate static preview: PDF retains its reference link, SVG contains only resolved drawing.
        var preview = new DisplayDocument(result.display().metadata(), result.display().pages());
        try (var output = Files.newOutputStream(OUTPUT.resolve("report.svg"))) { new SvgBackend().write(preview, output); }
        var adapter = new DocxAdapter();
        try (var output = Files.newOutputStream(OUTPUT.resolve("report.docx"))) { adapter.write(source, output); }
        try (var input = Files.newInputStream(OUTPUT.resolve("report.docx"))) {
            var imported = adapter.read(input, DocxAdapter.Policy.STRICT);
            if (!imported.diagnostics().isEmpty()) { throw new IOException("List marker DOCX styles lost semantics"); }
            engine.export(imported.document()).backend(new PdfBackend()).to(OUTPUT.resolve("imported.pdf"));
        }
        System.out.println("Independent list marker sample: 2 pages plus DOCX round-trip");
    }
}
