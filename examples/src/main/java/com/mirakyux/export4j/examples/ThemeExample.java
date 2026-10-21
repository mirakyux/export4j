// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.ComponentDocumentLayout;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.FlowColumn;
import com.mirakyux.export4j.components.FlowText;
import com.mirakyux.export4j.components.StyleScope;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.TableCell;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Same self-authored report under two themes, plus unchanged default authoring and local component overrides. */
public final class ThemeExample {
    private static final Path OUTPUT = Path.of("examples/build/themes");
    private static final PageSettings REPORT_PAGE = PageSettings.builder().size(500, 640).margins(24).build();
    private static final PageSettings COMPONENT_PAGE = PageSettings.builder().size(500, 400).margins(24).build();
    private static final RgbColor GREEN = new RgbColor(.1, .5, .2);

    private ThemeExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var regular = AuthoringExample.face("LiberationSans-Regular.ttf");
        var bold = AuthoringExample.face("LiberationSans-Bold.ttf");
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(regular)))
                .fontRole("bold", FontGroup.strict(List.of(bold))).build();
        var blue = theme(false);
        var compact = theme(true);
        var defaults = DocumentTheme.builder()
                .paragraphStyle("note", format -> format.fontSize(9).lineHeight(16).firstLineIndent(0).subsequentLineIndent(0))
                .inlineStyle("emphasis", style -> style.fontRole("bold").rgb(0x224488).underline(true))
                .build();
        write(engine, "blue", report(blue, true), components(blue, true));
        write(engine, "compact", report(compact, true), components(compact, true));
        var plain = report(defaults, false);
        var themed = report(defaults, true);
        if (!plain.blocks().equals(themed.blocks())) {
            throw new IllegalStateException("Default theme changed semantic authoring values");
        }
        write(engine, "plain-default", plain, components(defaults, false));
        write(engine, "themed-default", themed, components(defaults, true));
        Files.writeString(OUTPUT.resolve("fonts.json"), "{\"fonts\":["
                + "{\"resource\":\"LiberationSans-Regular.ttf\",\"sha256\":\"" + regular.data().id() + "\",\"bytes\":" + regular.data().byteSize() + "},"
                + "{\"resource\":\"LiberationSans-Bold.ttf\",\"sha256\":\"" + bold.data().id() + "\",\"bytes\":" + bold.data().byteSize() + "}]}\n",
                StandardCharsets.UTF_8);
        Files.writeString(OUTPUT.resolve("default-compatibility.json"), "{\"semanticBlocksEqual\":true}\n", StandardCharsets.UTF_8);
    }

    private static DocumentTheme theme(boolean compact) {
        double size = compact ? 10 : 11;
        var theme = DocumentTheme.builder()
                .bodyFormat(format -> format.fontSize(size).lineHeight(compact ? 16 : 20)
                        .firstLineIndent(compact ? 0 : 4).subsequentLineIndent(compact ? 4 : 8).spaceAfter(4))
                .cellFormat(format -> format.fontSize(compact ? 9 : 10).lineHeight(compact ? 14 : 18))
                .tableFormat(format -> format.padding(compact ? 2 : 4).spaceAfter(6))
                .paragraphStyle("note", format -> format.fontSize(9).lineHeight(16)
                        .firstLineIndent(0).subsequentLineIndent(0).spaceAfter(4))
                .inlineStyle("emphasis", style -> style.fontRole("bold")
                        .rgb(compact ? 0x884422 : 0x224488).underline(true));
        for (int level = 1; level <= 6; level++) {
            double headingSize = (compact ? 19 : 21) - level;
            theme.headingFormat(level, format -> format.fontSize(headingSize).lineHeight(headingSize + 8)
                    .spaceBefore(4).spaceAfter(2));
        }
        return theme.build();
    }

    private static Document report(DocumentTheme theme, boolean applyTheme) {
        var report = Documents.builder().title("Quarterly review").author("Export4j synthetic example").pageSettings(REPORT_PAGE);
        if (applyTheme) { report.theme(theme); }
        var pinned = CellFormat.builder().fontSize(12).lineHeight(20).build();
        return report
                .heading("REPORT-TITLE Quarterly review", 1)
                .paragraph("BODY-OVERVIEW Records are reviewed once; the report reuses one theme.\n"
                        + "BODY-DETAIL The same structure accepts compact or spacious typography.")
                .paragraph("NAMED-NOTE Figures are synthetic and safe to redistribute.", theme.paragraphStyle("note"))
                .paragraph(List.of(new InlineText("KEY ", theme.inlineStyle("emphasis")),
                        new InlineText("VALUE Accepted after review.", InlineStyle.of(theme.bodyFormat().fontSize()))))
                .heading("SECTION-2 Results", 2)
                .table(table -> table.header("ITEM", "RESULT").row("REVIEWED", "12")
                        .row(new TableCell("PIN12", pinned), new TableCell("UNCHANGED", pinned)))
                .heading("SECTION-3 Method", 3)
                .paragraph("BODY-METHOD Each source record contributes one reviewed result.")
                .heading("SECTION-4 Provenance", 4)
                .paragraph("BODY-SOURCE No external report, font discovery or remote resource is used.")
                .heading("SECTION-5 Limits", 5)
                .heading("SECTION-6 Follow-up", 6)
                .paragraph("BODY-END Named styles resolve when content is authored.")
                .add(new Paragraph("pinned", "EXPLICIT-BODY 12 pt, black and zero indent remain explicit.",
                        ParagraphFormat.builder().fontSize(12).lineHeight(20).spaceAfter(0).build()))
                .build();
    }

    private static Document components(DocumentTheme theme, boolean applyTheme) {
        var report = Documents.builder().title("Component typography review").pageSettings(COMPONENT_PAGE);
        if (applyTheme) { report.theme(theme); }
        report.paragraph("COMPONENT-OVERVIEW Local styles keep explicit choices.")
                .column(column -> column.padding(2).gap(2)
                        .text("INHERITED First line uses body defaults.\nINHERITED-NEXT Continuation uses body defaults.")
                        .add(StyleScope.builder(FlowColumn.builder().gap(2)
                                .text("LOCAL-SCOPE Local scope provides 14 pt and green.")
                                .text("EXPLICIT12 Explicit 12 pt, black and zero indent.",
                                        text -> text.fontSize(12).firstLineIndent(0).color(RgbColor.BLACK))
                                .add(FlowText.builder("RESTORED Local font size is inherited again.")
                                        .fontSize(12).inheritFontSize().build()).build())
                                .fontSize(14).lineHeight(24).firstLineIndent(6).color(GREEN).build())
                        .text("OUTSIDE Outer body defaults resume here."))
                .grid(grid -> grid.columns(1, 1).defaults(cell -> cell.padding(3))
                        .row(row -> row.cell("GRID-INHERITED").cell(FlowText.builder("GRID-PIN12")
                                .fontSize(12).lineHeight(24).firstLineIndent(0).build())))
                .bodyFormat(format -> format.fontSize(12).lineHeight(20))
                .column(column -> column.text("SNAPSHOT12 Only subsequent components change."));
        if (applyTheme) { report.theme(theme); }
        else { report.bodyFormat(ParagraphFormat.of(12)); }
        return report.column(column -> column.text("THEME-RESTORED Theme defaults resume for new content.")).build();
    }

    private static void write(DocumentEngine engine, String name, Document semantic, Document components) throws IOException {
        var report = engine.export(semantic).backend(new PdfBackend()).to(OUTPUT.resolve(name + "-report.pdf"));
        var component = engine.export(components).backend(new PdfBackend()).to(OUTPUT.resolve(name + "-components.pdf"));
        svg(name + "-report", report);
        svg(name + "-components", component);
        if (!report.diagnostics().isEmpty() || !component.diagnostics().isEmpty()) {
            throw new IllegalStateException("Theme fixture has unexpected diagnostics");
        }
        var adapter = new DocxAdapter();
        try (var output = Files.newOutputStream(OUTPUT.resolve(name + "-report.docx"))) { adapter.write(semantic, output); }
        try (var input = Files.newInputStream(OUTPUT.resolve(name + "-report.docx"))) {
            var imported = adapter.read(input, DocxAdapter.Policy.STRICT);
            if (!imported.diagnostics().isEmpty() || !nativeParagraphRepresentation(semantic.blocks())
                    .equals(nativeParagraphRepresentation(imported.document().sections().getFirst().blocks()))) {
                throw new IOException("Themed semantic report did not round-trip strictly");
            }
        }
        System.out.println("Theme " + name + ": " + report.display().pages().size() + " semantic / "
                + component.display().pages().size() + " component pages");
    }

    private static void svg(String name, ComponentDocumentLayout layout) throws IOException {
        try (var output = Files.newOutputStream(OUTPUT.resolve(name + ".svg"))) { new SvgBackend().write(layout.display(), output); }
    }

    private static List<Block> nativeParagraphRepresentation(List<Block> blocks) {
        // Native OOXML expands an implicit plain paragraph to one explicit default run.
        // Normalize only that representation; IDs, complete formats and rich content stay exact.
        return blocks.stream().<Block>map(block -> {
            if (block instanceof Paragraph paragraph && paragraph.inlines().isEmpty()
                    && paragraph.images().isEmpty() && paragraph.fields().isEmpty()) {
                return new Paragraph(paragraph.id(), List.of(new InlineText(paragraph.text(),
                        InlineStyle.of(paragraph.format().fontSize()))), paragraph.format());
            }
            return block;
        }).toList();
    }
}
