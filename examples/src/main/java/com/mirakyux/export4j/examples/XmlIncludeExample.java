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
import com.mirakyux.export4j.model.Paragraph;
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

/** Explicit offline fragments, isolated local identifiers and lexical field references. */
public final class XmlIncludeExample {
    private static final Path OUTPUT = Path.of("examples/build/xml-includes");
    private static final String PANEL = """
            <fragment version="1">
              <heading id="topic" level="2">STATIC-PANEL ${title}</heading>
              <p id="note" style="note">STATIC-NOTE ${status}</p>
              <p><span style="emphasis">STATIC-KEY</span> STATIC-VALUE ${status}</p>
              <numbered id="step-a" list-id="steps">STATIC-STEP-A</numbered>
              <numbered id="step-b" list-id="steps">STATIC-STEP-B</numbered>
              <table id="detail" row-split="false">
                <columns><column weight="1"/><column weight="1"/></columns>
                <row><cell>STATIC-CELL-A ${status}</cell><cell>STATIC-CELL-B ${title}</cell></row>
              </table>
            </fragment>
            """;
    private static final String STATIC = """
            <document version="1" title="Offline included panels" author="Export4j synthetic example">
              <heading id="report" level="1">XML-INCLUDE-STATIC</heading>
              <include ref="panel" prefix="first"/>
              <p id="separator">BETWEEN-PANELS Explicit local namespaces.</p>
              <include ref="panel" prefix="second"/>
            </document>
            """;
    private static final String RECORD = """
            <fragment version="1">
              <heading id="topic" level="2">RECORD-HEADING ${record.code}</heading>
              <p id="note" style="note">RECORD-STATUS ${record.status}</p>
              <numbered id="step" list-id="checks">LOCAL-TARGET ${record.code}</numbered>
              <p id="local">LOCAL-FIELDS ${record.code} page=<page-number width="16"/> total=<page-count width="16"/> target=<page-reference target="topic" width="16"/> number=<number-reference target="step" width="16"/></p>
              <p id="global">GLOBAL-FIELDS ${record.code} page=<page-reference target="global-target" width="16"/> number=<number-reference target="global-target" width="16"/></p>
              <if test="record.show"><then><p id="shown">TRUE-BRANCH ${record.code}</p></then><else><p id="hidden">FALSE-BRANCH ${record.code}</p></else></if>
            </fragment>
            """;
    private static final String CONCLUSION = """
            <fragment version="1">
              <p id="summary" style="note">INCLUDE-CONCLUSION ${result} global=<number-reference target="global-target" width="16"/>.</p>
            </fragment>
            """;
    private static final String INTERACTIVE = """
            <document version="1" title="Included lexical references">
              <heading id="interactive-report" level="1">XML-INCLUDE-INTERACTIVE</heading>
              <numbered id="global-target" list-id="global-list" restart="7">GLOBAL-TARGET External source.</numbered>
              <p id="overview">INCLUDE-OVERVIEW The next page contains two lexical copies.</p>
              <page-break id="body-break"/>
              <each items="records" as="record"><include ref="record" prefix="record"/></each>
              <if test="approved"><then><include ref="conclusion" prefix="approved"/></then><else><include ref="conclusion" prefix="pending"/></else></if>
            </document>
            """;

    private XmlIncludeExample() { }

    /** Writes self-authored source strings and fixtures; the compiler performs no resource I/O. */
    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var regular = AuthoringExample.face("LiberationSans-Regular.ttf");
        var bold = AuthoringExample.face("LiberationSans-Bold.ttf");
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(regular)))
                .fontRole("bold", FontGroup.strict(List.of(bold))).build();
        var theme = theme();
        var data = Map.<String, Object>of("title", "Review", "status", "OK", "result", "Approved",
                "approved", true, "records", List.of(
                        Map.of("code", "REC-A", "status", "CHECK-A", "show", true),
                        Map.of("code", "REC-B", "status", "CHECK-B", "show", false)));
        var document = XmlTemplate.builder().theme(theme).fragment("panel", PANEL)
                .pageSettings(PageSettings.builder().size(500, 620).margins(24).build())
                .build().compile(STATIC).render(data);
        staticStructure(document);
        var result = engine.export(document).backend(new PdfBackend()).to(OUTPUT.resolve("static.pdf"));
        if (result.display().pages().size() != 1 || !result.diagnostics().isEmpty()) {
            throw new IllegalStateException("Offline included static fixture must have one page and no diagnostics");
        }
        try (var output = Files.newOutputStream(OUTPUT.resolve("static.svg"))) {
            new SvgBackend().write(result.display(), output);
        }
        docx(document, "static");

        var interactive = XmlTemplate.builder().theme(theme)
                .fragment("record", RECORD).fragment("conclusion", CONCLUSION)
                .pageSettings(PageSettings.builder().size(500, 420).margins(24).build())
                .build().compile(INTERACTIVE).render(data);
        interactiveStructure(interactive);
        var interactiveResult = engine.export(interactive).backend(new PdfBackend()).to(OUTPUT.resolve("interactive.pdf"));
        if (interactiveResult.display().pages().size() != 2 || !interactiveResult.diagnostics().isEmpty()) {
            throw new IllegalStateException("Offline included references must have two pages and no diagnostics");
        }
        var rejected = new ByteArrayOutputStream();
        try {
            new SvgBackend().write(interactiveResult.display(), rejected);
            throw new IllegalStateException("SVG must reject included interactive extras");
        } catch (IllegalArgumentException expected) {
            if (!"static backends require an explicit display without interactive extras".equals(expected.getMessage())
                    || rejected.size() != 0) { throw expected; }
        }
        Files.writeString(OUTPUT.resolve("static-rejected.txt"), "INCLUDED_INTERACTIVE_SVG_REJECTED_BEFORE_WRITE\n", StandardCharsets.UTF_8);
        docx(interactive, "interactive");
        Files.writeString(OUTPUT.resolve("static.xml"), STATIC, StandardCharsets.UTF_8);
        Files.writeString(OUTPUT.resolve("panel.xml"), PANEL, StandardCharsets.UTF_8);
        Files.writeString(OUTPUT.resolve("interactive.xml"), INTERACTIVE, StandardCharsets.UTF_8);
        Files.writeString(OUTPUT.resolve("record.xml"), RECORD, StandardCharsets.UTF_8);
        Files.writeString(OUTPUT.resolve("conclusion.xml"), CONCLUSION, StandardCharsets.UTF_8);
        Files.writeString(OUTPUT.resolve("fonts.json"), "{\"fonts\":["
                + "{\"resource\":\"LiberationSans-Regular.ttf\",\"sha256\":\"" + regular.data().id() + "\",\"bytes\":" + regular.data().byteSize() + "},"
                + "{\"resource\":\"LiberationSans-Bold.ttf\",\"sha256\":\"" + bold.data().id() + "\",\"bytes\":" + bold.data().byteSize() + "}]}\n",
                StandardCharsets.UTF_8);
        System.out.println("Offline XML includes: one static page and two reference pages");
    }

    private static DocumentTheme theme() {
        return DocumentTheme.builder()
                .bodyFormat(value -> value.fontSize(11).lineHeight(20).spaceBefore(0).spaceAfter(3))
                .headingFormat(1, value -> value.fontSize(18).lineHeight(28).spaceBefore(0).spaceAfter(6))
                .headingFormat(2, value -> value.fontSize(14).lineHeight(22).spaceBefore(0).spaceAfter(3))
                .cellFormat(value -> value.fontSize(10).lineHeight(18))
                .tableFormat(value -> value.padding(3).spaceBefore(0).spaceAfter(6))
                .paragraphStyle("note", value -> value.fontSize(9).lineHeight(18).spaceBefore(0).spaceAfter(2))
                .inlineStyle("emphasis", value -> value.fontRole("bold").fontSize(11).rgb(0x224488).underline(true))
                .build();
    }

    private static void staticStructure(Document document) {
        var blocks = document.blocks();
        if (blocks.size() != 14 || !blocks.get(1).id().equals("first-topic")
                || !blocks.get(8).id().equals("second-topic")
                || !blocks.get(3).id().startsWith("first-xml-") || !blocks.get(10).id().startsWith("second-xml-")) {
            throw new IllegalStateException("Included panel IDs or automatic local namespaces were lost");
        }
        for (int index : List.of(4, 5, 11, 12)) {
            String prefix = index < 7 ? "first" : "second";
            if (!(blocks.get(index) instanceof NumberedItem item) || !item.listId().equals(prefix + "-steps")) {
                throw new IllegalStateException("Included numbered lists escaped their local namespaces");
            }
        }
    }

    private static void interactiveStructure(Document document) {
        for (int index = 0; index < 2; index++) {
            String suffix = "@" + index;
            targets(document, "record-local" + suffix, List.of("", "", "record-topic" + suffix, "record-step" + suffix));
            targets(document, "record-global" + suffix, List.of("global-target", "global-target"));
        }
        targets(document, "approved-summary", List.of("global-target"));
        var ids = document.blocks().stream().map(Block::id).toList();
        if (!ids.contains("record-shown@0") || !ids.contains("record-hidden@1")
                || ids.contains("record-hidden@0") || ids.contains("record-shown@1") || ids.contains("pending-summary")) {
            throw new IllegalStateException("Included fragments did not retain their lexical branch scopes");
        }
    }

    private static void targets(Document document, String id, List<String> expected) {
        var block = document.blocks().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
        if (!(block instanceof Paragraph paragraph)
                || !paragraph.fields().stream().map(value -> value.field().targetId()).toList().equals(expected)) {
            throw new IllegalStateException("Included field target did not retain its lexical source: " + id);
        }
    }

    private static void docx(Document document, String name) throws IOException {
        var path = OUTPUT.resolve(name + ".docx");
        var adapter = new DocxAdapter();
        try (var output = Files.newOutputStream(path)) { adapter.write(document, output); }
        try (var input = Files.newInputStream(path)) {
            var imported = adapter.read(input, DocxAdapter.Policy.STRICT);
            if (!imported.diagnostics().isEmpty() || !nativeValues(document.blocks())
                    .equals(nativeValues(imported.document().sections().getFirst().blocks()))) {
                throw new IOException("Included native DOCX semantic values did not round-trip strictly");
            }
        }
    }

    private static List<Block> nativeValues(List<Block> blocks) {
        // Native headings/lists/inline cells omit one redundant, unlinked default run.
        // Only this equivalent representation is normalized; complete styles and source values stay exact.
        return blocks.stream().<Block>map(block -> switch (block) {
            case Heading heading -> new Heading(heading.id(), heading.text(), heading.level(), heading.format(),
                    nativeRuns(heading.inlines(), heading.text(), heading.format().fontSize()));
            case NumberedItem item -> new NumberedItem(item.id(), item.listId(), item.text(), item.restartAt(), item.format(), item.level(),
                    nativeRuns(item.inlines(), item.text(), item.format().fontSize()));
            case Table table -> new Table(table.id(), table.columns(), table.rows().stream().map(row -> new TableRow(
                    row.cells().stream().map(XmlIncludeExample::nativeCell).toList())).toList(), table.headerRows(), table.format());
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
