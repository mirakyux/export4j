// SPDX-License-Identifier: Apache-2.0
package consumer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.TableCell;

/** Uses only published theme and authoring APIs, with explicitly supplied physical fonts. */
final class ThemeConsumer {
    private ThemeConsumer() { }

    static void verify(FontFace font, Path output) throws IOException {
        var theme = DocumentTheme.builder()
                .bodyFormat(format -> format.fontSize(11).lineHeight(20))
                .headingFormat(1, format -> format.fontSize(18).lineHeight(28))
                .cellFormat(format -> format.fontSize(10).lineHeight(18))
                .tableFormat(format -> format.padding(4).spaceAfter(8))
                .paragraphStyle("note", format -> format.fontSize(10).lineHeight(18))
                .inlineStyle("emphasis", ink -> ink.fontRole("bold").rgb(0x224488).underline(true))
                .build();
        var document = Documents.builder().theme(theme)
                .pageSettings(page -> page.size(400, 400).margins(20))
                .heading("THEME-CONSUMER", 1)
                .paragraph("THEME-BODY")
                .paragraph("THEME-NOTE", theme.paragraphStyle("note"))
                .paragraph(List.of(new InlineText("THEME-KEY ", theme.inlineStyle("emphasis")),
                        new InlineText("THEME-VALUE", InlineStyle.of(11))))
                .table(table -> table.header("ITEM", "STATE").row("FIELD", "OK")
                        .row(TableCell.builder().text("EXPLICIT").format(CellFormat.builder()
                                        .fontSize(12).lineHeight(20).build()).build(),
                                TableCell.builder().text("INHERITED").format(theme.cellFormat()).build()))
                .build();
        var bold = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Bold.ttf")));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(font)))
                .fontRole("bold", FontGroup.strict(List.of(bold))).build();
        var result = engine.export(document).backend(new PdfBackend()).to(output.resolve("consumer-theme.pdf"));
        if (result.display().pages().size() != 1 || !result.diagnostics().isEmpty()) {
            throw new IllegalStateException("theme public caller lost deterministic single-page layout");
        }
        engine.export(document).backend(new SvgBackend()).to(output.resolve("consumer-theme.svg"));
        try (var stream = Files.newOutputStream(output.resolve("consumer-theme.docx"))) {
            new DocxAdapter().write(document, stream);
        }
    }
}
