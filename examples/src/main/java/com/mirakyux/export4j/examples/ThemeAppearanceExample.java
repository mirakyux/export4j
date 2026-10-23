// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.FlowText;
import com.mirakyux.export4j.components.FontScope;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Authoring-time theme cascade, complete local values and scoped component defaults. */
public final class ThemeAppearanceExample {
    private ThemeAppearanceExample() { }

    public static void main(String[] args) throws IOException {
        var output = Path.of("examples/build/theme-appearance");
        Files.createDirectories(output);
        var regular = FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf")));
        var bold = FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Bold.ttf")));
        var engine = DocumentEngine.builder(regular).fontRole("brand", bold).fontRole("regular", regular).build();
        var page = PageSettings.builder().size(360, 380).margins(24).build();
        var theme = DocumentTheme.builder().fontRole("brand").rgb(0x224488)
                .bodyFormat(format -> format.fontSize(11).lineHeight(22).spaceAfter(0))
                .headingFormat(1, format -> format.fontSize(16).lineHeight(26).spaceBefore(0).spaceAfter(0))
                .headingRgb(1, 0x884422).cellFontRole("regular").cellRgb(0x446622).footerRgb(0x666666)
                .inlineStyle("explicit", new InlineStyle("", 11, 0, 0, false, false)).build();
        var semantic = Document.builder().pageSettings(page).theme(theme).heading("JAVA-TITLE", 1).paragraph("JAVA-BODY")
                .bullet("JAVA-BULLET").numbered("steps", "JAVA-NUMBER")
                .table(List.of("JAVA-HEADER"), List.of(List.of("JAVA-CELL")))
                .paragraph(List.of(new InlineText("JAVA-EXPLICIT", theme.inlineStyle("explicit"))))
                .theme(theme.toBuilder().inheritBodyRgb().rgb(0x883366).bodyFontRole("").build()).paragraph("JAVA-LATER").build();
        write(engine, semantic, output, "semantic");
        var components = Documents.builder().pageSettings(page).theme(theme)
                .column(column -> column.text("COMPONENT-INHERIT")
                        .add(new FontScope(FlowText.builder("COMPONENT-LOCAL").color(RgbColor.BLACK).build(), regular)))
                .theme(theme.toBuilder().bodyFontRole("").bodyRgb(0).build()).component(FlowText.builder("COMPONENT-RESET").build()).build();
        write(engine, components, output, "components");
        var xml = XmlTemplate.builder().theme(theme).pageSettings(page).build().compile("""
                <document version='1'>
                  <header height='22' gap='4'><p>XML-HEADER</p></header>
                  <footer height='22' gap='4'><p>XML-FOOTER <page-number width='12'/>/<page-count width='12'/></p></footer>
                  <heading level='1'>XML-TITLE</heading><p>XML-BODY</p>
                  <table><columns><column weight='1'/></columns><row><cell>XML-CELL</cell></row></table>
                  <p role='' color='#000000'>XML-RESET</p><p><span style='explicit'>XML-EXPLICIT</span></p>
                </document>
                """).renderWithPageRegions(Map.of());
        var result = engine.export(xml).backend(new PdfBackend()).to(output.resolve("xml.pdf"));
        if (result.display().pages().size() != 1 || !result.contextualDiagnostics().isEmpty()) { throw new IllegalStateException("unexpected theme output"); }
        try (var stream = Files.newOutputStream(output.resolve("xml.svg"))) { new SvgBackend().write(result.display(), stream); }
        try (var stream = Files.newOutputStream(output.resolve("xml.docx"))) { new DocxAdapter().write(xml, stream); }
    }

    private static void write(DocumentEngine engine, Document document, Path output, String name) throws IOException {
        var result = engine.export(document).backend(new PdfBackend()).to(output.resolve(name + ".pdf"));
        if (result.display().pages().size() != 1 || !result.contextualDiagnostics().isEmpty()) { throw new IllegalStateException("unexpected theme output"); }
        try (var stream = Files.newOutputStream(output.resolve(name + ".svg"))) { new SvgBackend().write(result.display(), stream); }
        if (name.equals("semantic")) {
            try (var stream = Files.newOutputStream(output.resolve(name + ".docx"))) { new DocxAdapter().write(document, stream); }
        }
    }
}
