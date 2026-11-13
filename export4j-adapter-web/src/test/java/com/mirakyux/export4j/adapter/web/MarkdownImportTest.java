// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.edit.DocumentEditor;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingPolicy.Action;
import com.mirakyux.export4j.model.processing.ProcessingPolicy.Treatment;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownImportTest {
    private static ProcessingPolicy policy(Action action, Treatment treatment) {
        return ProcessingPolicy.builder().defaultRule(action, treatment).build();
    }
    private static MarkdownImporter lenient() { return MarkdownImporter.builder().policy(policy(Action.WARN, Treatment.DEGRADE)).build(); }
    @Test void commonmarkTextEntitiesAndTemplateSyntaxStayLiteral() throws Exception {
        var source = "# Title\n\nHello &amp; ${name} \\*literal\\* 😀\nsoft  \nhard";
        var result = new MarkdownImporter().read(source).execute();
        assertEquals("Title", ((Heading) result.document().blocks().getFirst()).text());
        assertEquals("Hello & ${name} *literal* 😀 soft\nhard", ((Paragraph) result.document().blocks().get(1)).text());
        assertTrue(result.report().issues().isEmpty());
    }
    @Test void strongEmphasisCombinedAndStrikeRetainExplicitRoles() throws Exception {
        var result = new MarkdownImporter().read("**bold** *italic* ***both*** ~~gone~~").execute();
        var runs = ((Paragraph) result.document().blocks().getFirst()).inlines();
        assertEquals("bold", runs.stream().filter(run -> run.text().equals("bold")).findFirst().orElseThrow().style().fontRole());
        assertEquals("italic", runs.stream().filter(run -> run.text().equals("italic")).findFirst().orElseThrow().style().fontRole());
        assertEquals("boldItalic", runs.stream().filter(run -> run.text().equals("both")).findFirst().orElseThrow().style().fontRole());
        assertTrue(runs.stream().filter(run -> run.text().equals("gone")).findFirst().orElseThrow().style().strikeThrough());
    }
    @Test void gfmTableAlignmentEscapedPipesAndEmptyCellsAreSemantic() throws Exception {
        var result = new MarkdownImporter().read("| Left | Right |\n|:---|---:|\n| a\\|b | |\n| c | d |").execute();
        var table = (Table) result.document().blocks().getFirst();
        assertEquals(1, table.headerRows()); assertEquals(3, table.rows().size());
        assertEquals("a|b", table.rows().get(1).cells().getFirst().text());
        assertEquals("", table.rows().get(1).cells().get(1).text());
        assertEquals(ParagraphAlignment.RIGHT, table.rows().get(2).cells().get(1).format().alignment());
        assertEquals(ParagraphAlignment.LEFT, table.rows().getFirst().cells().getFirst().format().alignment());
    }
    @Test void omittedCellFormatKeepsExistingDefaultsBasedOnTheFinalParagraphFontSize() throws Exception {
        var builder = MarkdownImporter.builder().paragraphFormat(ParagraphFormat.of(9));
        builder.paragraphFormat(ParagraphFormat.builder().fontSize(20).lineHeight(64)
                .alignment(ParagraphAlignment.RIGHT).lineBreakPolicy(LineBreakPolicy.CJK_STRICT).build());
        var result = builder.build().read("Body\n\n| A |\n|---|\n| **B** |").execute();
        var paragraph = (Paragraph) result.document().blocks().getFirst();
        assertEquals(64, paragraph.format().lineHeight());
        var table = (Table) result.document().blocks().get(1);
        assertEquals(CellFormat.of(20), table.rows().getFirst().cells().getFirst().format());
        var bodyCell = table.rows().get(1).cells().getFirst();
        assertEquals(CellFormat.of(20), bodyCell.format());
        assertEquals(20, bodyCell.inlines().getFirst().style().fontSize());
    }
    @Test void explicitCellMetricsControlInlineTextWhileGfmAlignmentRemainsAuthoritative() throws Exception {
        var cellFormat = CellFormat.builder().fontSize(18).lineHeight(64).alignment(ParagraphAlignment.CENTER)
                .lineBreakPolicy(LineBreakPolicy.CJK_STRICT).build();
        var importer = MarkdownImporter.builder().paragraphFormat(ParagraphFormat.of(10)).cellFormat(cellFormat).build();
        var result = importer.read("Body\n\n| Plain | Strong | Link |\n|---|---:|:---|\n| a | **b** | [c](https://example.org/) |").execute();
        assertEquals(10, ((Paragraph) result.document().blocks().getFirst()).format().fontSize());
        var table = (Table) result.document().blocks().get(1);
        for (var row : table.rows()) {
            assertEquals(cellFormat, row.cells().getFirst().format());
            assertEquals(cellFormat.withAlignment(ParagraphAlignment.RIGHT), row.cells().get(1).format());
            assertEquals(cellFormat.withAlignment(ParagraphAlignment.LEFT), row.cells().get(2).format());
            for (var cell : row.cells()) {
                assertTrue(cell.inlines().stream().allMatch(run -> run.style().fontSize() == 18));
            }
        }
        assertEquals("bold", table.rows().get(1).cells().get(1).inlines().getFirst().style().fontRole());
        assertEquals("https://example.org/", table.rows().get(1).cells().get(2).inlines().getFirst().hyperlink());
    }
    @Test void cellFormatAndConversionAreSnapshotsOfTheBuiltImporter() throws Exception {
        var format = CellFormat.builder().fontSize(17).lineHeight(64).build();
        var builder = MarkdownImporter.builder().cellFormat(format);
        var importer = builder.build();
        builder.cellFormat(CellFormat.of(8)).paragraphFormat(ParagraphFormat.of(8));
        String source = "| A |\n|---|\n| **B** |";
        var direct = (Table) importer.read(source).execute().document().blocks().getFirst();
        var target = new DocumentConversion.Target("MODEL", (document, notes, policy, output) ->
                new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of()));
        var converted = (Table) importer.conversion(source).prepare(target).candidate().sections().getFirst().blocks().getFirst();
        assertEquals(direct, converted);
        assertEquals(format, converted.rows().get(1).cells().getFirst().format());
        assertEquals(17, converted.rows().get(1).cells().getFirst().inlines().getFirst().style().fontSize());
    }
    @Test void headerOnlyTableKeepsItsConfiguredCellMetricsWithoutAddingRows() throws Exception {
        var format = CellFormat.builder().fontSize(18).lineHeight(64).alignment(ParagraphAlignment.CENTER).build();
        var importer = MarkdownImporter.builder().cellFormat(format).policy(policy(Action.WARN, Treatment.DEGRADE)).build();
        var result = importer.read("| A |\n|---|").execute();
        var table = (Table) result.document().blocks().getFirst();
        assertEquals(format, table.rows().getFirst().cells().getFirst().format());
        assertEquals(1, table.rows().size()); assertEquals(0, table.headerRows());
        assertTrue(result.report().losses().isEmpty());
    }
    @Test void nestedNumberingUsesRealParentCountersAndRestarts() throws Exception {
        var result = new MarkdownImporter().read("3. parent\n   1. nested one\n   2. nested two\n4. next\n\n- bullet\n  - deep\n").execute();
        var items = result.document().blocks();
        assertEquals(List.of(1, 2, 2, 1), items.stream().filter(NumberedItem.class::isInstance).map(NumberedItem.class::cast).map(NumberedItem::level).toList());
        var first = (NumberedItem) items.getFirst(); assertEquals(3, first.restartAt());
        assertTrue(items.stream().filter(NumberedItem.class::isInstance).map(NumberedItem.class::cast).allMatch(item -> item.listId().equals(first.listId())));
        assertEquals(List.of(1, 2), items.stream().filter(BulletItem.class::isInstance).map(BulletItem.class::cast).map(BulletItem::level).toList());
        assertEquals(2, DocumentEditor.edit(result.document()).find("nested").matches().size());
    }
    @Test void mixedNestedListKindsKeepIndentWithoutInventingNumberParents() throws Exception {
        var result = new MarkdownImporter().read("- bullet\n\n  2. first\n  3. second\n     - child").execute();
        var ordered = (NumberedItem) result.document().blocks().get(1);
        assertEquals(1, ordered.level()); assertEquals(2, ordered.restartAt());
        assertEquals(18, ordered.format().firstLineIndent());
        assertEquals(1, DocumentEditor.edit(result.document()).find("second").matches().size());
    }
    @Test void quoteLossIsReportedWhileCodePreservesContentAndWhitespace() throws Exception {
        String source = "> quote\n\n```java\na < b;\n```\n\ninline `a  b`";
        assertThrows(ProcessingException.class, () -> new MarkdownImporter().read(source).prepare());
        var warned = lenient().read(source).prepare();
        var ignored = MarkdownImporter.builder().policy(policy(Action.IGNORE, Treatment.DEGRADE)).build().read(source).execute();
        assertEquals(warned.execute().document(), ignored.document());
        assertEquals(warned.preview().issues(), warned.execute().report().issues());
        assertEquals(1, ignored.report().losses().size()); assertTrue(ignored.report().warnings().isEmpty());
        assertEquals("BLOCK_QUOTE", ignored.report().losses().getFirst().feature());
        var blocks = ignored.document().blocks();
        assertEquals(18, ((Paragraph) blocks.getFirst()).format().firstLineIndent());
        assertEquals("a < b;\n", ((Paragraph) blocks.get(1)).text());
        assertEquals("code", ((Paragraph) blocks.get(1)).inlines().getFirst().style().fontRole());
        assertEquals("inline a  b", ((Paragraph) blocks.get(2)).text());
    }
    @Test void missingImageSupportsAltDegradationOrExplicitDiscard() throws Exception {
        var source = "before ![ALT](secret/private.png) after";
        assertThrows(ProcessingException.class, () -> new MarkdownImporter().read(source).execute());
        var degraded = lenient().read(source).execute();
        assertEquals("before ALT after", ((Paragraph) degraded.document().blocks().getFirst()).text());
        assertFalse(degraded.report().toString().contains("secret/private.png"));
        var discarded = MarkdownImporter.builder().policy(policy(Action.IGNORE, Treatment.DISCARD)).build().read(source).execute();
        assertEquals("before  after", ((Paragraph) discarded.document().blocks().getFirst()).text());
        assertEquals(Treatment.DISCARD, discarded.report().losses().getFirst().treatment());
    }
    @Test void registeredImagePreservesBytesDimensionsAndOuterLinkWithoutFetching() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/image.png";
            var resource = image(); var importer = MarkdownImporter.builder().image(url, resource).build();
            var result = importer.read("[![ALT](" + url + ")](https://example.org/keep)").execute();
            var paragraph = (Paragraph) result.document().blocks().getFirst(); var actual = paragraph.images().getFirst().image();
            assertArrayEquals(resource.resource().bytes(), actual.resource().bytes());
            assertEquals(12, actual.width()); assertEquals("ALT", actual.altText());
            assertEquals("https://example.org/keep", actual.hyperlink()); assertEquals(0, requests.get());
        } finally { server.stop(0); }
    }
    @Test void linkReferencesAndExplicitRelativeBaseRemainPassive() throws Exception {
        var importer = MarkdownImporter.builder().baseUri("https://example.org/docs/").build();
        var result = importer.read("[reference][id] [relative](../guide)\n\n[id]: https://example.org/ref").execute();
        var runs = ((Paragraph) result.document().blocks().getFirst()).inlines();
        assertEquals("https://example.org/ref", runs.getFirst().hyperlink());
        assertEquals("https://example.org/guide", runs.getLast().hyperlink());
        assertThrows(ProcessingException.class, () -> new MarkdownImporter().read("[relative](guide)").execute());
        var degraded = lenient().read("[relative](guide)").execute();
        assertEquals("relative", ((Paragraph) degraded.document().blocks().getFirst()).text());
        assertEquals("", ((Paragraph) degraded.document().blocks().getFirst()).inlines().getFirst().hyperlink());
    }
    @Test void sourceRangesIncludeOriginalUtf16AndPhysicalTableCoordinates() throws Exception {
        var source = "😀\r\n\r\n| A | B |\r\n|---|---|\r\n| x | [code](https://example.org/ \"tip\") |\r\n";
        var result = lenient().read(source).execute(); var issue = result.report().losses().getFirst();
        assertEquals("LINK_TITLE", issue.feature());
        assertEquals("[code](https://example.org/ \"tip\")", source.substring(issue.location().startUtf16(), issue.location().endUtf16()));
        assertEquals(1, issue.location().cellPath().getFirst().rowIndex());
        assertEquals(1, issue.location().cellPath().getFirst().columnIndex());
    }
    @Test void scriptMarkupAndUnsafeLinksCannotBeIgnored() {
        var importer = MarkdownImporter.builder().policy(policy(Action.IGNORE, Treatment.DISCARD)).build();
        for (String source : List.of("<script>alert(1)</script>", "<img src=x onerror='alert(1)'>", "[bad](javascript:alert%281%29)",
                "[bad](file:///private)", "[bad](https://user:secret@example.org/)", "![bad](javascript:alert)")) {
            assertThrows(IllegalArgumentException.class, () -> importer.read(source).execute(), source);
        }
    }
    @Test void safeRawHtmlIsLiteralOnlyThroughExplicitLossPolicy() throws Exception {
        var result = lenient().read("before <b>bold</b><br>after").execute();
        assertEquals("before <b>bold</b>\nafter", ((Paragraph) result.document().blocks().getFirst()).text());
        assertEquals(2, result.report().losses().size());
    }
    @Test void taskAndMultiParagraphListItemsHaveQueryableSemanticLoss() throws Exception {
        var task = lenient().read("- [x] done").execute();
        assertEquals("TASK_LIST", task.report().losses().getFirst().feature());
        assertEquals("[x] done", ((BulletItem) task.document().blocks().getFirst()).text());
        var loose = lenient().read("- first\n\n  continuation\n\n  ```txt\n  code\n  ```").execute();
        assertEquals(3, loose.document().blocks().size());
        assertEquals(2, loose.report().losses().stream().filter(issue -> issue.feature().equals("LIST_ITEM_STRUCTURE")).count());
    }
    @Test void headerOnlyTableIsKeptOnceWithoutInventingAnEmptyRow() throws Exception {
        String source = "| A | B |\n|---|---|";
        var result = new MarkdownImporter().read(source).execute();
        var table = (Table) result.document().blocks().getFirst();
        assertEquals(1, table.rows().size()); assertEquals(0, table.headerRows());
        assertTrue(result.report().losses().isEmpty());
    }
    @Test void zeroNumberingRequiresExplicitLossWhileHeadingImagesRemainObjects() throws Exception {
        assertThrows(ProcessingException.class, () -> new MarkdownImporter().read("0. zero\n1. next").execute());
        var numbered = lenient().read("0. zero\n1. next").execute();
        assertEquals(1, ((NumberedItem) numbered.document().blocks().getFirst()).restartAt());
        assertEquals("NUMBERING_START", numbered.report().losses().getFirst().feature());
        var strictHeading = MarkdownImporter.builder().image("logo", image()).build().read("# ![ALT](logo)").execute();
        assertEquals(1, ((Heading) strictHeading.document().blocks().getFirst()).images().size());
        var heading = MarkdownImporter.builder().policy(policy(Action.WARN, Treatment.DEGRADE)).image("logo", image()).build().read("# ![ALT](logo)").execute();
        assertEquals("\uFFFC", ((Heading) heading.document().blocks().getFirst()).text());
        assertEquals("ALT", ((Heading) heading.document().blocks().getFirst()).images().getFirst().image().altText());
        assertTrue(heading.report().losses().isEmpty());
    }
    @Test void executableMarkupInsideCodeRemainsInertTextAndTitlesReportRemoval() throws Exception {
        var result = lenient().read("```html\n<script>literal</script>\n```\n\n[label](https://example.org/ \"title\")").execute();
        assertEquals("<script>literal</script>\n", ((Paragraph) result.document().blocks().getFirst()).text());
        assertEquals(List.of("LINK_TITLE"), result.report().losses().stream().map(issue -> issue.feature()).toList());
        assertThrows(IllegalArgumentException.class, () -> MarkdownImporter.builder().policy(policy(Action.IGNORE, Treatment.PRESERVE)).build().read("> quote").execute());
    }
    @Test void metadataConfigAndPreparedCandidateAreSnapshots() throws Exception {
        var builder = MarkdownImporter.builder().metadata(new DocumentMetadata("First", "Author"));
        var importer = builder.build(); builder.metadata(DocumentMetadata.EMPTY);
        var request = importer.read("text"); var prepared = request.prepare();
        request.execution(ExecutionOptions.builder().cancelled(() -> true).build());
        assertEquals("First", prepared.execute().document().metadata().title());
        assertThrows(ExecutionException.class, request::execute);
    }
    @Test void budgetsInvalidUnicodeAndCancellationCannotBeIgnored() throws Exception {
        var limits = new ImportLimits(100, 10, 50, 5, 100, 2, 2, 1_000);
        var importer = MarkdownImporter.builder().limits(limits).policy(policy(Action.IGNORE, Treatment.DEGRADE)).build();
        assertThrows(IllegalArgumentException.class, () -> importer.read("long line xxxxxxxxxx").execute());
        assertThrows(IllegalArgumentException.class, () -> importer.read("|a|b|\n|-|-|\n|c|d|").execute());
        assertThrows(IllegalArgumentException.class, () -> importer.read("bad \ud800").execute());
        assertThrows(IllegalArgumentException.class, () -> importer.read("bad \u0000").execute());
        var checks = new AtomicInteger();
        assertThrows(ExecutionException.class, () -> new MarkdownImporter().read("p\n\n".repeat(500))
                .execution(ExecutionOptions.builder().cancelled(() -> checks.incrementAndGet() > 30).build()).execute());
        ExecutionContext.checkpoint();
        var cancelled = new AtomicBoolean(false);
        var prepared = new MarkdownImporter().read("text").execution(ExecutionOptions.builder().cancelled(cancelled::get).build()).prepare();
        cancelled.set(true); assertThrows(ExecutionException.class, prepared::execute);
    }
    @Test void unchangedSemanticSubsetCanBeEditedAndReexported() throws Exception {
        var imported = new MarkdownImporter().read("# Report\n\nold [link](https://example.org/)\n\n| A | B |\n|---|---|\n|old|2|").execute();
        var edited = DocumentEditor.edit(imported.document()).replaceText("old", "new").execute();
        var bytes = new ByteArrayOutputStream(); new MarkdownAdapter().export(edited.document()).to(bytes);
        var text = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("new")); assertFalse(text.contains("old"));
        assertTrue(text.contains("https://example.org/"));
        var roundTrip = new MarkdownImporter().read(text).execute();
        assertEquals(2, DocumentEditor.edit(roundTrip.document()).find("new").matches().size());
    }
    private static InlineImage image() throws Exception {
        var bytes = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new InlineImage(ImageResource.load(bytes.toByteArray()), 12, 12, 0, "registered", "");
    }
}
