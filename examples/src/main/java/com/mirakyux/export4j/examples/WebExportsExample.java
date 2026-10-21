// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.web.ExportPolicy;
import com.mirakyux.export4j.adapter.web.ExportResult;
import com.mirakyux.export4j.adapter.web.FontStyle;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.adapter.web.UnsupportedAction;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/** Self-contained web output and Markdown content export; all sample assets are generated offline. */
public final class WebExportsExample {
    private WebExportsExample() { }
    public static void main(String[] args) throws IOException {
        Path output = Path.of("examples/build/web-exports"); Files.createDirectories(output);
        var body = ParagraphFormat.of(12);
        var strong = new InlineStyle("strong", 12, 0, 0, false, false);
        var face = new FontStyle("Arial", 700, false);
        var mark = new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB);
        var drawing = mark.createGraphics();
        try { drawing.setColor(new Color(0x2463a1)); drawing.fillRect(0, 0, 24, 24); drawing.setColor(Color.WHITE); drawing.fillRect(6, 6, 12, 12); }
        finally { drawing.dispose(); }
        var bytes = new ByteArrayOutputStream();
        try { if (!ImageIO.write(mark, "png", bytes)) { throw new IOException("PNG writer unavailable"); } } finally { mark.flush(); }
        Files.write(output.resolve("logo.png"), bytes.toByteArray());
        var logo = new InlineImage(ImageResource.load(bytes.toByteArray()), 24, 24, 0, "Blue square logo", "https://example.com/report");
        var headerLogo = new InlineImage(logo.resource(), 24, 24, 0, logo.altText(), "");
        var header = PageRegion.builder().height(30).paragraph(Paragraph.mixed("brand", List.of(headerLogo, new InlineText(" EXPORT4J", strong)), body)).build();
        var footer = PageRegion.builder().height(24).paragraph(Paragraph.mixed("footer", List.of(new InlineText("PAGE ", InlineStyle.of(12)),
                new InlineField(InlineField.Kind.PAGE, "", 24, InlineStyle.of(12))), body)).build();
        var inner = Table.text("inner", List.of("Nested header"), List.of(List.of("NESTED-ONLY")));
        var table = Table.builder("merged").columns(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1))
                .headerCells(TableCell.builder().text("MERGED-HEADER").columnSpan(2).build())
                .row(TableCell.builder().blocks(List.of(new Paragraph("before", "BEFORE-NESTED", body), inner,
                        new Paragraph("after", "AFTER-NESTED", body))).build(), TableCell.builder().text("VERTICAL-ONLY").rowSpan(2).build())
                .row("FINAL-CELL").build();
        var source = new SectionedDocument(new DocumentMetadata("Export4j web report", "Export4j"), List.of(
                new Section("report", PageSettings.A4, List.of(new Heading("title", "Web export report", 1),
                        Paragraph.mixed("intro", List.of(new InlineText("A searchable report with ", InlineStyle.of(12)),
                                new InlineText("explicit styles", strong), new InlineText(" and a link.", InlineStyle.of(12), "https://example.com/a%20b?q=1&x=2")), body),
                        new Paragraph("literal", "Literal markup: <script>alert(1)</script> & | * [text] 😀", body),
                        Paragraph.mixed("run-boundaries", List.of(new InlineText("a", InlineStyle.of(12)),
                                new InlineText("BOLD!", strong), new InlineText("NEXT", strong),
                                new InlineText("z &amp;", InlineStyle.of(12))), body),
                        Paragraph.mixed("linked-image", List.of(logo), body),
                        new BulletItem("bullet", "BULLET-ONLY", "•", 1, body),
                        new NumberedItem("number", "steps", "STEP-ONLY", 3, body),
                        new NumberedItem("restart", "steps", "RESTART-ONLY", 8, body), table),
                        0, new PageRegions(header, footer)),
                new Section("closing", PageSettings.A4, List.of(new PageBreak("break"), new Paragraph("last", "CLOSING-ONLY", body)), 8)));
        var warn = ExportPolicy.builder().defaultAction(UnsupportedAction.WARN).build();
        var html = HtmlAdapter.builder().policy(warn).fontRole("strong", face).build().export(source).to(output.resolve("report.html"));
        var markdown = MarkdownAdapter.builder().policy(warn).fontRole("strong", face).build().export(source).to(output.resolve("report.md"));
        var content = Document.builder().heading("Content export", 1).paragraph("PLAIN-ONLY & | <tag> 中文")
                .bullet("ITEM-ONLY").numbered("steps", "NUMBER-ONLY")
                .table(List.of("Name", "Value"), List.of(List.of("CELL-ONLY", "2 | 3"))).build();
        var strict = new MarkdownAdapter().export(content).to(output.resolve("content.md"));
        new HtmlAdapter().export(content).to(output.resolve("content.html"));
        Files.writeString(output.resolve("diagnostics.tsv"), "target\tfeature\tregion\tsection\tblock\toffset\n"
                + warnings("html", html) + warnings("markdown", markdown) + warnings("strict", strict));
        System.out.println("Web export examples: " + output);
    }
    private static String warnings(String target, ExportResult result) {
        var text = new StringBuilder();
        for (var warning : result.diagnostics()) {
            var location = warning.location();
            text.append(target).append('\t').append(warning.feature()).append('\t').append(location.region())
                    .append('\t').append(location.sectionIndex()).append('\t').append(location.blockId())
                    .append('\t').append(location.sourceOffset()).append('\n');
        }
        return text.toString();
    }
}
