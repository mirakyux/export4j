// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfFont;
import com.mirakyux.export4j.adapter.pdf.PdfPageEditor;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/** Edits existing pages without reflow, retaining original content, resources and metadata. */
public final class PdfPageEditingExample {
    private PdfPageEditingExample() { }
    public static void main(String[] args) throws IOException {
        Path output = Path.of("examples/build/pdf-page-editing"); Files.createDirectories(output);
        var face = AuthoringExample.face("LiberationSans-Regular.ttf"); var engine = DocumentEngine.builder(FontGroup.strict(List.of(face))).build();
        var source = Document.builder().pageSettings(page -> page.size(320, 240).margins(24))
                .paragraph("PAGE-A SOURCE-CONTENT").paragraph("The source stays editable and searchable.")
                .add(new PageBreak("break")).paragraph("PAGE-B SCALE-AND-ROTATE").build();
        engine.export(source).backend(new PdfBackend()).to(output.resolve("source.pdf"));
        var raster = new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB); var graphics = raster.createGraphics();
        graphics.setColor(new Color(0x2266aa)); graphics.fillRect(0, 0, 30, 20); graphics.dispose();
        var png = new ByteArrayOutputStream(); ImageIO.write(raster, "png", png); raster.flush();
        var adapter = new PdfAdapter(); var input = adapter.input(output.resolve("source.pdf"), "");
        var style = new PdfPageEditor.TextStyle(PdfFont.of(face.data().bytes()), 14, 0xaa3322, .7);
        var policy = ProcessingPolicy.builder().feature("PAGE_CROP", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
        var prepared = adapter.editPages(input).policy(policy)
                .text(0, "WATERMARK 1 / 2", 24, 115, style, PdfPageEditor.Layer.UNDER_CONTENT)
                .image(0, ImageResource.load(png.toByteArray()), new PdfPageEditor.Rectangle(220, 140, 60, 40), PdfPageEditor.Layer.OVER_CONTENT)
                .crop(0, new PdfPageEditor.Rectangle(10, 10, 300, 220))
                .scale(1, .75).rotate(1, 90)
                .text(1, "PAGE 2 / 2", 24, 95, style, PdfPageEditor.Layer.OVER_CONTENT).prepare();
        var result = prepared.to(output.resolve("edited.pdf"));
        if (result.changes() != 6 || result.pageCount() != 2 || !prepared.preview().issues().equals(result.report().issues())) { throw new IllegalStateException("PDF page edit reconciliation failed"); }
        Files.writeString(output.resolve("losses.tsv"), result.report().issues().getFirst().feature() + "\t" + result.report().issues().getFirst().effects().getFirst() + "\n");
    }
}
