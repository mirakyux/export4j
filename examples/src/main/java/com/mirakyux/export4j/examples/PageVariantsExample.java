// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.DocumentTheme;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Semantic first/even bands with section numbering, explicit blank pages and native DOCX. */
public final class PageVariantsExample {
    private PageVariantsExample() { }

    public static void main(String[] args) throws IOException {
        var output = Path.of("examples/build/page-variants");
        Files.createDirectories(output);
        var theme = DocumentTheme.builder().bodyFormat(format -> format.fontSize(11).lineHeight(22).spaceAfter(0))
                .headerStyle(style -> style.fontRole("bold").rgb(0x224488).format(format -> format.spaceAfter(0)))
                .footerStyle(style -> style.rgb(0x446622).format(format -> format.spaceAfter(0)))
                .cellFormat(format -> format.fontSize(10).lineHeight(15)).tableFormat(format -> format.padding(4).spaceAfter(0)).build();
        String fields = "<p>PAGE <page-number width='18'/> OF <page-count width='18'/></p>";
        String xml = """
                <document version='1' title='Semantic page variants'>
                  <page width='360' height='260' margin='20'/>
                  <header height='30' gap='4'><p>ODD-H</p>%s</header>
                  <footer height='30' gap='4'><p>ODD-F</p>%s</footer>
                  <header variant='even' height='48' gap='4'>
                    <table><columns><column width='28'/><column weight='1'/></columns><row>
                      <cell><image ref='mark' width='12' height='12' alt='EVEN-MARK'/></cell><cell role='bold' color='#224488'>EVEN-H</cell>
                    </row></table>%s
                  </header>
                  <footer variant='even' height='30' gap='4'><p>EVEN-F</p>%s</footer>
                  <section id='a' first-page-number='2'>
                    <header variant='first' height='30' gap='4'><p>FIRST-H</p>%s</header>
                    <footer variant='first' height='30' gap='4'><p>FIRST-F</p>%s</footer>
                    <p>A-ONE</p><page-break/><p>A-TWO</p><page-break/><p>A-THREE</p>
                  </section>
                  <section id='b' first-page-number='8'><p>B-ONE</p><page-break/><p>B-TWO</p></section>
                  <section id='c'><header variant='first' height='0'/><footer variant='first' height='0'/>
                    <p>C-ONE</p><page-break/><p>C-TWO</p>
                  </section>
                </document>
                """.formatted(fields, fields, fields, fields, fields, fields);
        var source = XmlTemplate.builder().theme(theme).image("mark", AuthoringExample.image().resource()).build().compile(xml).renderSections(Map.of());
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf"))))
                .fontRole("bold", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Bold.ttf")))).build();
        var result = engine.export(source).backend(new PdfBackend()).to(output.resolve("variants.pdf"));
        if (result.display().pages().size() != 7 || !result.contextualDiagnostics().isEmpty()) { throw new IllegalStateException("page variant fixture differs"); }
        try (var stream = Files.newOutputStream(output.resolve("variants.svg"))) { new SvgBackend().write(result.display(), stream); }
        var adapter = new DocxAdapter();
        try (var stream = Files.newOutputStream(output.resolve("variants.docx"))) { adapter.write(source, stream); }
        try (var stream = Files.newInputStream(output.resolve("variants.docx"))) {
            var back = adapter.read(stream, DocxAdapter.Policy.STRICT);
            if (!back.diagnostics().isEmpty()) { throw new IllegalStateException("page variants lost during native DOCX read"); }
            engine.write(back.document(), new PdfBackend(), output.resolve("roundtrip.pdf"));
        }
    }
}
