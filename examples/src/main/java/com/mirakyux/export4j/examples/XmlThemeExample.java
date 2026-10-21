// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** One original XML structure under two themes, plus a separate interactive semantic fixture. */
public final class XmlThemeExample {
    private static final Path OUTPUT = Path.of("examples/build/xml-themes");
    private static final PageSettings PAGE = PageSettings.builder().size(500, 760).margins(24).build();
    private static final String REPORT = """
            <document version="1" title="Themed XML review" author="Export4j synthetic example">
              <heading level="1">XML-TITLE ${title}</heading>
              <p>BODY-A One XML structure accepts two typography themes.&#10;BODY-B Binding replaces data while preserving complete styles.</p>
              <p style="report-note">NAMED-P All records are synthetic.</p>
              <p style="report-note" size="12" space-before="0" space-after="0">OVERRIDE12 Explicit size retains the XML 1.5 line-height rule.</p>
              <p><span style="emphasis">KEY</span> VALUE <span style="emphasis" role="" color="#000000" underline="false" strike="false">RESET</span></p>
              <p>RAISED-BASE <span style="raised">RAISED</span> END</p>
              <heading level="2">SECTION-2 <span style="emphasis">HEADING-KEY</span></heading>
              <heading level="3">SECTION-3 Method</heading>
              <heading level="4">SECTION-4 Provenance</heading>
              <heading level="5">SECTION-5 Limits</heading>
              <heading level="6">SECTION-6 Results</heading>
              <table row-split="false">
                <columns><column weight="1"/><column weight="1"/></columns>
                <row>
                  <cell size="8" line-height="14">
                    <p>BLOCK-LOCAL Outer cell sets 8 pt.</p>
                    <p style="report-note">NAMED-CELL Complete named spacing.</p>
                    <table row-split="false">
                      <columns><column weight="1"/></columns>
                      <row><cell>INNER-DEFAULT Theme cell defaults.</cell></row>
                    </table>
                    <p>BLOCK-END Outer local defaults resume.</p>
                  </cell>
                  <cell>DEFAULT-CELL Theme cell defaults.</cell>
                </row>
              </table>
              <rows items="records" as="record">
                <columns><column weight="1"/><column weight="1"/></columns>
                <header><cell>RECORD</cell><cell>STATUS</cell></header>
                <row><cell>${record.code}</cell><cell><span style="emphasis">${record.status}</span></cell></row>
              </rows>
              <p>BODY-END ${result}</p>
            </document>
            """;
    private static final String INTERACTIVE = """
            <document version="1" title="Themed XML interactions">
              <heading level="1">XML-INTERACTIVE ${title}</heading>
              <p style="report-note"><link href="https://example.com/xml-theme-text"><span style="emphasis">LINK-KEY</span></link> <link href="https://example.com/xml-theme-image"><image ref="mark" width="12" height="12" alt="Explicit offline marker"/></link></p>
              <p>XML-FIELDS page=<page-number width="16"/> total=<page-count width="16"/> target=<page-reference target="target" width="16"/> number=<number-reference target="target" width="16"/></p>
              <numbered id="target" list-id="checks" restart="3">NUMBER-TARGET Reviewed.</numbered>
            </document>
            """;

    private XmlThemeExample() { }

    /** Writes self-authored fixtures using only explicit, registered local resources. */
    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var regular = AuthoringExample.face("LiberationSans-Regular.ttf");
        var bold = AuthoringExample.face("LiberationSans-Bold.ttf");
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(regular)))
                .fontRole("bold", FontGroup.strict(List.of(bold))).build();
        var data = Map.<String, Object>of("title", "Quarterly review", "result", "Accepted after review.",
                "records", List.of(Map.of("code", "ROW-01", "status", "ACCEPT-01"),
                        Map.of("code", "ROW-02", "status", "ACCEPT-02")));
        Files.writeString(OUTPUT.resolve("report.xml"), REPORT, StandardCharsets.UTF_8);
        Files.writeString(OUTPUT.resolve("interactive.xml"), INTERACTIVE, StandardCharsets.UTF_8);
        for (boolean compact : List.of(false, true)) {
            var name = compact ? "compact" : "blue";
            var document = XmlTemplate.builder().theme(theme(compact)).pageSettings(PAGE).build()
                    .compile(REPORT).render(data);
            var result = engine.export(document).backend(new PdfBackend()).to(OUTPUT.resolve(name + ".pdf"));
            if (result.display().pages().size() != 1 || !result.diagnostics().isEmpty()) {
                throw new IllegalStateException("XML theme report must have one page and no diagnostics");
            }
            try (var output = Files.newOutputStream(OUTPUT.resolve(name + ".svg"))) {
                new SvgBackend().write(result.display(), output);
            }
            docx(document, name);
        }
        var interactive = XmlTemplate.builder().theme(theme(false))
                .pageSettings(PageSettings.builder().size(500, 260).margins(24).build())
                .image("mark", AuthoringExample.image().resource()).build().compile(INTERACTIVE).render(data);
        var result = engine.export(interactive).backend(new PdfBackend()).to(OUTPUT.resolve("interactive.pdf"));
        if (result.display().pages().size() != 1 || !result.diagnostics().isEmpty()) {
            throw new IllegalStateException("XML theme interaction fixture must have one page and no diagnostics");
        }
        var rejected = new ByteArrayOutputStream();
        try {
            new SvgBackend().write(result.display(), rejected);
            throw new IllegalStateException("SVG must reject interactive extras");
        } catch (IllegalArgumentException expected) {
            if (!"static backends require an explicit display without interactive extras".equals(expected.getMessage())
                    || rejected.size() != 0) {
                throw expected;
            }
        }
        Files.writeString(OUTPUT.resolve("static-rejected.txt"), "INTERACTIVE_SVG_REJECTED_BEFORE_WRITE\n", StandardCharsets.UTF_8);
        docx(interactive, "interactive");
        Files.writeString(OUTPUT.resolve("fonts.json"), "{\"fonts\":["
                + "{\"resource\":\"LiberationSans-Regular.ttf\",\"sha256\":\"" + regular.data().id() + "\",\"bytes\":" + regular.data().byteSize() + "},"
                + "{\"resource\":\"LiberationSans-Bold.ttf\",\"sha256\":\"" + bold.data().id() + "\",\"bytes\":" + bold.data().byteSize() + "}]}\n",
                StandardCharsets.UTF_8);
        System.out.println("XML themes: two static pages and one interactive page");
    }

    private static DocumentTheme theme(boolean compact) {
        var theme = DocumentTheme.builder()
                .bodyFormat(format -> format.fontSize(compact ? 10 : 11).lineHeight(compact ? 16 : 20)
                        .firstLineIndent(compact ? 0 : 4).subsequentLineIndent(compact ? 4 : 8).spaceAfter(4))
                .cellFormat(format -> format.fontSize(compact ? 9 : 10).lineHeight(compact ? 14 : 18))
                .tableFormat(format -> format.padding(compact ? 2 : 4).spaceAfter(6))
                .paragraphStyle("report-note", format -> format.fontSize(9).lineHeight(18)
                        .firstLineIndent(0).subsequentLineIndent(0).spaceBefore(3).spaceAfter(5))
                .inlineStyle("emphasis", style -> style.fontRole("bold").rgb(compact ? 0x884422 : 0x224488).underline(true))
                .inlineStyle("raised", style -> style.fontRole("bold").fontSize(8)
                        .rgb(compact ? 0x884422 : 0x224488).baselineShift(-2));
        for (int level = 1; level <= 6; level++) {
            double size = (compact ? 19 : 21) - level;
            theme.headingFormat(level, format -> format.fontSize(size).lineHeight(size + 8).spaceBefore(4).spaceAfter(2));
        }
        return theme.build();
    }

    private static void docx(Document document, String name) throws IOException {
        var adapter = new DocxAdapter();
        var path = OUTPUT.resolve(name + ".docx");
        try (var output = Files.newOutputStream(path)) { adapter.write(document, output); }
        try (var input = Files.newInputStream(path)) {
            var imported = adapter.read(input, DocxAdapter.Policy.STRICT);
            if (!imported.diagnostics().isEmpty() || !nativeValues(document.blocks())
                    .equals(nativeValues(imported.document().sections().getFirst().blocks()))) {
                throw new IOException("Themed XML semantic values did not round-trip strictly");
            }
        }
    }

    private static List<Block> nativeValues(List<Block> blocks) {
        // Native headings/lists/inline cells omit one redundant, unlinked default run.
        // Only that representation is normalized; complete formats, IDs, rich values and hierarchy stay exact.
        return blocks.stream().<Block>map(block -> switch (block) {
            case Heading heading -> new Heading(heading.id(), heading.text(), heading.level(), heading.format(),
                    nativeRuns(heading.inlines(), heading.text(), heading.format().fontSize()));
            case NumberedItem item -> new NumberedItem(item.id(), item.listId(), item.text(), item.restartAt(), item.format(), item.level(),
                    nativeRuns(item.inlines(), item.text(), item.format().fontSize()));
            case Table table -> new Table(table.id(), table.columns(), table.rows().stream().map(row -> new TableRow(
                    row.cells().stream().map(XmlThemeExample::nativeCell).toList())).toList(), table.headerRows(), table.format());
            default -> block;
        }).toList();
    }

    private static TableCell nativeCell(TableCell cell) {
        if (cell.hasBlocks()) { return cell.toBuilder().blocks(nativeValues(cell.blocks())).build(); }
        return new TableCell(cell.text(), cell.format(), nativeRuns(cell.inlines(), cell.text(), cell.format().fontSize()),
                cell.images(), cell.fields(), cell.columnSpan(), cell.rowSpan());
    }

    private static List<InlineText> nativeRuns(List<InlineText> runs, String text, double size) {
        return runs.equals(List.of(new InlineText(text, InlineStyle.of(size)))) ? List.of() : runs;
    }
}
