// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.edit.DocumentEditor;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingPolicy.Action;
import com.mirakyux.export4j.model.processing.ProcessingPolicy.Treatment;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlImportTest {
    @Test void ownExplicitListMarkersNeverBecomeBodyTextAcrossThreeCycles() throws Exception {
        for (var action : List.of(Action.WARN, Action.IGNORE)) {
            var document = new HtmlImporter().read("<ul><li>BULLET</li></ul><ol start='7'><li>NUMBER</li></ol>").execute().document();
            var policy = ProcessingPolicy.builder().defaultRule(action, Treatment.DEGRADE).feature("HTML_METADATA", action, Treatment.DISCARD).build();
            for (int round = 0; round < 3; round++) {
                var bytes = new ByteArrayOutputStream(); DocumentConversion.from(document).policy(policy).prepare(new HtmlAdapter().conversionTarget()).to(bytes);
                document = HtmlImporter.builder().policy(policy).build().read(bytes.toString(StandardCharsets.UTF_8)).execute().document();
                var bullet = (BulletItem) document.blocks().getFirst(); var number = (NumberedItem) document.blocks().getLast();
                assertEquals("BULLET", bullet.text()); assertEquals("•", bullet.marker());
                assertEquals("NUMBER", number.text()); assertEquals(7, number.restartAt());
            }
        }
    }
    @Test void ordinaryMarkerClassStaysLiteralAndComplexExplicitMarkerRequiresRealLoss() throws Exception {
        var ordinary = new HtmlImporter().read("<ul><li><span class='marker'>PREFIX</span>BODY</li></ul>").execute();
        assertEquals("PREFIXBODY", ((BulletItem) ordinary.document().blocks().getFirst()).text());
        String complex = "<ol><li><span class='marker' data-export4j-marker='1' data-export4j-level='2'>1.2.</span>BODY</li></ol>";
        var failure = assertThrows(ProcessingException.class, () -> new HtmlImporter().read(complex).execute());
        assertEquals("LIST_MARKER", failure.issue().feature());
        var lowered = lenient(Action.WARN).read(complex).execute();
        assertEquals("1.2.BODY", ((Paragraph) lowered.document().blocks().getFirst()).text());
        assertTrue(lowered.report().issues().stream().anyMatch(issue -> issue.effects().contains("LIST_STRUCTURE_FLATTENED")));
        for (String marker : List.of("<span class='marker' data-export4j-marker='2' data-export4j-level='1'>1.</span>",
                "<span class='marker' data-export4j-marker='1' data-export4j-level='0'>1.</span>",
                "<span class='marker' data-export4j-marker='1' data-export4j-level='1'><b>1.</b></span>")) {
            assertThrows(IllegalArgumentException.class, () -> lenient(Action.IGNORE).read("<ol><li>" + marker + "BODY</li></ol>").execute());
        }
    }
    @Test void columnGroupsUseExplicitLossAndKeepTheRealCellGrid() throws Exception {
        String source = "<table id='t'><colgroup><col span='2'></colgroup><tbody><tr><td>A</td><td>B</td></tr></tbody></table>";
        var failure = assertThrows(ProcessingException.class, () -> new HtmlImporter().read(source).execute());
        assertEquals("TABLE_COLUMNS", failure.issue().feature());
        for (var action : List.of(Action.WARN, Action.IGNORE)) {
            var result = lenient(action).read(source).execute();
            var table = (Table) result.document().blocks().getFirst();
            assertEquals(List.of("A", "B"), table.rows().getFirst().cells().stream().map(cell -> cell.text()).toList());
            assertEquals(List.of(new com.mirakyux.export4j.model.ColumnWidth.Fraction(1), new com.mirakyux.export4j.model.ColumnWidth.Fraction(1)), table.columns());
            assertEquals(1, result.report().losses().size());
            assertEquals("COLUMNS_REPLACED_WITH_EQUAL_FRACTIONS", result.report().issues().getFirst().effects().getFirst());
            assertEquals(action == Action.IGNORE, result.report().warnings().isEmpty());
        }
    }
    @Test void exportedTableColumnGroupsCanBeReimportedWithoutDroppingCellsOrSpans() throws Exception {
        var source = new HtmlImporter().read("<table id='t'><tr><td>A</td><td>B</td></tr><tr><td colspan='2'>MERGED</td></tr></table>").execute();
        var bytes = new ByteArrayOutputStream(); DocumentConversion.from(source.document()).prepare(new HtmlAdapter().conversionTarget()).to(bytes);
        var policy = ProcessingPolicy.builder().defaultRule(Action.WARN, Treatment.DEGRADE).feature("HTML_METADATA", Action.WARN, Treatment.DISCARD).build();
        var result = HtmlImporter.builder().policy(policy).build().read(bytes.toString(StandardCharsets.UTF_8)).execute();
        var table = result.document().blocks().stream().filter(Table.class::isInstance).map(Table.class::cast).findFirst().orElseThrow();
        assertEquals("MERGED", ((Paragraph) table.rows().get(1).cells().getFirst().blocks().getFirst()).text());
        assertEquals(2, table.rows().get(1).cells().getFirst().columnSpan());
        assertEquals(2, table.columns().size());
        assertTrue(result.report().losses().stream().anyMatch(issue -> issue.feature().equals("TABLE_COLUMNS")));
    }
    @Test void singleParagraphHeaderCellsRequireExplicitFlatteningAndRetainInlineText() throws Exception {
        String source = "<table><thead><tr><td><p id='header'>HEADER</p></td></tr></thead><tbody><tr><td>BODY</td></tr></tbody></table>";
        var failure = assertThrows(ProcessingException.class, () -> new HtmlImporter().read(source).execute());
        assertEquals("TABLE_HEADER_PARAGRAPH", failure.issue().feature());
        for (var action : List.of(Action.WARN, Action.IGNORE)) {
            var result = lenient(action).read(source).execute();
            var table = (Table) result.document().blocks().getFirst();
            assertEquals(1, table.headerRows()); assertFalse(table.rows().getFirst().cells().getFirst().hasBlocks());
            assertEquals("HEADER", table.rows().getFirst().cells().getFirst().text());
            assertEquals("BODY", table.rows().get(1).cells().getFirst().text());
            assertEquals(1, result.report().losses().size());
            var discard = HtmlImporter.builder().policy(ProcessingPolicy.builder().feature("TABLE_HEADER_PARAGRAPH", action, Treatment.DISCARD).build())
                    .build().read(source).execute();
            assertEquals("", ((Table) discard.document().blocks().getFirst()).rows().getFirst().cells().getFirst().text());
        }
    }
    @Test void invalidOrOverBudgetColumnDeclarationsCannotBeIgnored() {
        String rows = "<tbody><tr><td>A</td><td>B</td></tr></tbody>";
        for (String group : List.of("<colgroup span='0'></colgroup>", "<colgroup span='2147483647'></colgroup>",
                "<colgroup span='3'></colgroup>", "<colgroup span='1'><col></colgroup>",
                "<colgroup><col span='-1'></colgroup>")) {
            assertThrows(IllegalArgumentException.class, () -> lenient(Action.IGNORE).read("<table>" + group + rows + "</table>").execute());
        }
        var defaults = ImportLimits.DEFAULT;
        var limits = new ImportLimits(defaults.maxInputUtf16(), defaults.maxLineUtf16(), defaults.maxNodes(), defaults.maxDepth(),
                defaults.maxTextUtf16(), 1, defaults.maxIssues(), defaults.maxWork());
        assertThrows(IllegalArgumentException.class, () -> HtmlImporter.builder().limits(limits)
                .policy(ProcessingPolicy.builder().defaultRule(Action.IGNORE, Treatment.DEGRADE).build()).build()
                .read("<table><colgroup span='2'></colgroup>" + rows + "</table>").execute());
        assertThrows(IllegalArgumentException.class, () -> lenient(Action.IGNORE)
                .read("<table><colgroup><col onload='x'></colgroup>" + rows + "</table>").execute());
    }
    @Test void ownExportRestrictiveCspCanBeExplicitlyDiscardedButActiveHttpDirectivesStayRejected() throws Exception {
        var source = new HtmlImporter().read("<p id='p'>VISIBLE-ROUNDTRIP</p>").execute().document();
        var encoded = new ByteArrayOutputStream();
        DocumentConversion.from(source).prepare(new HtmlAdapter().conversionTarget()).to(encoded);
        String html = encoded.toString(StandardCharsets.UTF_8);
        assertTrue(html.contains("Content-Security-Policy"));
        for (var action : List.of(Action.WARN, Action.IGNORE)) {
            var policy = ProcessingPolicy.builder().defaultRule(action, Treatment.DEGRADE).feature("HTML_METADATA", action, Treatment.DISCARD).build();
            var result = HtmlImporter.builder().policy(policy).build().read(html).execute();
            assertEquals("VISIBLE-ROUNDTRIP", result.document().paragraphs().getFirst().text());
            assertTrue(result.report().losses().stream().anyMatch(issue -> issue.feature().equals("HTML_METADATA")));
            assertEquals(action == Action.IGNORE, result.report().warnings().isEmpty());
            for (String active : List.of("<meta http-equiv='refresh' content='0;url=https://example.org'>",
                    "<meta http-equiv='Content-Security-Policy' content=\"default-src *; report-uri https://example.org\">",
                    "<div http-equiv='Content-Security-Policy' content=\"default-src 'none'\">x</div>")) {
                assertThrows(IllegalArgumentException.class, () -> HtmlImporter.builder().policy(policy).build().read(active).execute());
            }
        }
        assertThrows(ProcessingException.class, () -> new HtmlImporter().read(html).execute());
    }
    private static HtmlImporter lenient(Action action) {
        return HtmlImporter.builder().policy(ProcessingPolicy.builder().defaultRule(action, Treatment.DEGRADE).build()).build();
    }
    @Test void basicHtmlEntitiesAndWhitespaceMapToSemanticBlocks() throws Exception {
        var result = new HtmlImporter().read("<!doctype html><h1 id=title>Title</h1><p id=p> A  <strong>B</strong>\n C &amp; D&nbsp;E<br>F ${name} 😀 </p>").execute();
        assertEquals("title", result.document().blocks().getFirst().id());
        assertEquals("Title", ((Heading) result.document().blocks().getFirst()).text());
        assertEquals("A B C & D E\nF ${name} 😀", ((Paragraph) result.document().blocks().get(1)).text());
        assertTrue(result.report().losses().isEmpty());
    }
    @Test void cascadeHonorsImportantInlineSpecificityAndSourceOrder() throws Exception {
        var result = new HtmlImporter().read("""
                <style>p { color: blue; } .chosen { color: green; } #p { color: red; }
                p.chosen { color: #123456 !important; } .chosen { color: #abc !important; }</style>
                <p id=p class=chosen style="color: black">Text <span style="color: white !important">Child</span></p>
                """).execute();
        var runs = ((Paragraph) result.document().blocks().getFirst()).inlines();
        assertEquals(0x123456, runs.getFirst().style().rgb()); assertEquals(0xffffff, runs.getLast().style().rgb());
        var inline = new HtmlImporter().read("<style>#p {color:red}</style><p id=p style='color:blue'>T</p>").execute();
        assertEquals(0x0000ff, ((Paragraph) inline.document().blocks().getFirst()).inlines().getFirst().style().rgb());
    }
    @Test void descendantAndChildSelectorsAndInheritedValuesAreDeterministic() throws Exception {
        var result = new HtmlImporter().read("<style>div > p {font-size: 16px;line-height:2} div .x {text-align:right}</style><div><p class=x>A <span>B</span></p></div>").execute();
        var paragraph = (Paragraph) result.document().blocks().getFirst();
        assertEquals(12, paragraph.format().fontSize()); assertEquals(24, paragraph.format().lineHeight());
        assertEquals(ParagraphAlignment.RIGHT, paragraph.format().alignment());
        assertTrue(paragraph.inlines().stream().allMatch(run -> run.style().fontSize() == 12));
    }
    @Test void physicalFontVariantsAndDecorationsArePreserved() throws Exception {
        var importer = HtmlImporter.builder().fontFamily("Explicit", "regular", "strong", "emphasis", "combined").build();
        var result = importer.read("<p style='font-family:Explicit'><b><i>BOTH</i></b><em>ITALIC</em><u><span style='text-decoration:none'>UNDER</span></u><s>STRIKE</s></p>").execute();
        var runs = ((Paragraph) result.document().blocks().getFirst()).inlines();
        assertEquals("combined", runs.getFirst().style().fontRole()); assertEquals("emphasis", runs.get(1).style().fontRole());
        assertTrue(runs.get(2).style().underline()); assertTrue(runs.getLast().style().strikeThrough());
    }
    @Test void paragraphGeometryUsesPtAndPxWithoutBrowserPagination() throws Exception {
        var result = lenient(Action.WARN).read("<p style='font-size:10pt;line-height:15pt;margin-top:4px;margin-bottom:5pt;margin-left:8px;text-indent:2pt;text-align:justify'>Body</p>").execute();
        var format = ((Paragraph) result.document().blocks().getFirst()).format();
        assertEquals(3, format.spaceBefore()); assertEquals(5, format.spaceAfter());
        assertEquals(8, format.firstLineIndent()); assertEquals(6, format.subsequentLineIndent());
        assertEquals(ParagraphAlignment.JUSTIFY, format.alignment());
        assertEquals(2, result.report().losses().size());
    }
    @Test void nestedListsPreserveStartAndModelNumberingIntegrity() throws Exception {
        var result = new HtmlImporter().read("<ol start=3><li id=a>Parent<ol><li>Child</li></ol></li><li>Next</li></ol><ul><li>Bullet<ol start=2><li>Mixed</li></ol></li></ul>").execute();
        var ordered = result.document().blocks().stream().filter(NumberedItem.class::isInstance).map(NumberedItem.class::cast).toList();
        assertEquals(List.of(1, 2, 1, 1), ordered.stream().map(NumberedItem::level).toList());
        assertEquals(List.of(3, 1, 4, 2), ordered.stream().map(NumberedItem::restartAt).toList());
        assertEquals(1, DocumentEditor.edit(result.document()).find("Child").matches().size());
    }
    @Test void tableSpansUsePhysicalAnchorsAndSourceCellPaths() throws Exception {
        var result = lenient(Action.WARN).read("<table id=t><thead><tr><th colspan=2>Header</th></tr></thead><tbody><tr><td rowspan=2>A</td><td>B</td></tr><tr><td title=x>C</td></tr></tbody></table>").execute();
        var table = (Table) result.document().blocks().getFirst();
        assertEquals(2, table.columns().size()); assertEquals(1, table.headerRows());
        assertEquals(2, table.rows().get(1).cells().getFirst().rowSpan());
        assertEquals(1, table.grid().anchorRows().get(2).getFirst().columnIndex());
        var issue = result.report().losses().getFirst();
        assertEquals(2, issue.location().cellPath().getFirst().rowIndex()); assertEquals(1, issue.location().cellPath().getFirst().columnIndex());
        assertEquals("HTML_ATTRIBUTE", issue.feature());
    }
    @Test void blockCellsKeepOrderedParagraphsAndAtomicNestedTable() throws Exception {
        var result = new HtmlImporter().read("<table id=outer><tr><td><p id=first>Before</p><table id=inner><tr><td>Inside</td></tr></table><p id=last>After</p></td></tr></table>").execute();
        var cell = ((Table) result.document().blocks().getFirst()).rows().getFirst().cells().getFirst();
        assertTrue(cell.hasBlocks()); assertEquals(List.of("first", "inner", "last"), cell.blocks().stream().map(block -> block.id()).toList());
        assertEquals(1, DocumentEditor.edit(result.document()).find("Inside").matches().size());
    }
    @Test void malformedTableAndInvalidUnicodeCannotBeIgnored() {
        var importer = lenient(Action.IGNORE);
        assertThrows(IllegalArgumentException.class, () -> importer.read("<table><tr><td colspan=2>A</td></tr><tr><td>B</td></tr></table>").execute());
        assertThrows(IllegalArgumentException.class, () -> importer.read("<p>\ud800</p>").execute());
        assertThrows(IllegalArgumentException.class, () -> importer.read("<p>\0</p>").execute());
        assertThrows(IllegalArgumentException.class, () -> importer.read("<p id=x>A</p><p id=x>B</p>").execute());
    }
    @Test void missingImageSupportsAltDegradationOrExplicitDiscard() throws Exception {
        String source = "<p>Before <img src=secret.png alt=ALT> After</p>";
        assertThrows(ProcessingException.class, () -> new HtmlImporter().read(source).execute());
        var degraded = lenient(Action.WARN).read(source).execute();
        assertEquals("Before ALT After", ((Paragraph) degraded.document().blocks().getFirst()).text());
        var discarded = HtmlImporter.builder().policy(ProcessingPolicy.builder().feature("IMAGE_RESOURCE", Action.IGNORE, Treatment.DISCARD).build()).build().read(source).execute();
        assertEquals("Before After", ((Paragraph) discarded.document().blocks().getFirst()).text());
        assertEquals(1, discarded.report().losses().size()); assertTrue(discarded.report().warnings().isEmpty());
    }
    @Test void imageBytesAltHyperlinkAndAspectRatioUseExplicitResources() throws Exception {
        var bytes = image(); var image = new InlineImage(ImageResource.load(bytes), 12, 6, 0, "registered");
        var builder = HtmlImporter.builder().image("local.png", image); var importer = builder.build();
        builder.image("local.png", image.toBuilder().width(1).build());
        var result = importer.read("<p><a href='https://example.org/a'><img src=local.png alt=Source width=32></a></p>").execute();
        var actual = ((Paragraph) result.document().blocks().getFirst()).images().getFirst().image();
        assertEquals(24, actual.width()); assertEquals(12, actual.height()); assertEquals("Source", actual.altText());
        assertEquals("https://example.org/a", actual.hyperlink()); assertArrayEquals(bytes, actual.resource().bytes());
    }
    @Test void passiveRelativeLinksRequireExplicitBaseAndInternalLinksReportLoss() throws Exception {
        assertThrows(ProcessingException.class, () -> new HtmlImporter().read("<p><a href='../next'>Next</a></p>").execute());
        var result = HtmlImporter.builder().baseUri("https://example.org/docs/").build().read("<p><a href='../next'>Next</a></p>").execute();
        assertEquals("https://example.org/next", ((Paragraph) result.document().blocks().getFirst()).inlines().getFirst().hyperlink());
        assertEquals("", ((Paragraph) lenient(Action.WARN).read("<p><a href='#p'>Label</a></p>").execute().document().blocks().getFirst()).inlines().getFirst().hyperlink());
    }
    @Test void dangerousContentCannotHideInsideDiscardedOrHiddenSubtrees() {
        var importer = lenient(Action.IGNORE);
        for (var source : List.of("<script>alert(1)</script>", "<div style='display:none'><img src=x onerror=evil></div>",
                "<p><a href='java&#115;cript:evil'>x</a></p>", "<link rel=stylesheet href='https://example.org/x'>",
                "<style>p{background:u\\72l(x)}</style>", "<style>@import 'https://example.org/x';</style>",
                "<style>p{color:red;background:url(x)}</style>", "<!DOCTYPE html SYSTEM 'file:///secret'><p>x</p>")) {
            assertThrows(IllegalArgumentException.class, () -> importer.read(source).execute());
        }
    }
    @Test void unsupportedStylesElementsAndAttributesStayInActualIgnoreReport() throws Exception {
        String source = "<style>.unused {float:left}</style><p title=label style='position:absolute'>Text <mark>Marked</mark></p>";
        var warned = lenient(Action.WARN).read(source).prepare(); var ignored = lenient(Action.IGNORE).read(source).execute();
        assertEquals(warned.execute().document(), ignored.document());
        assertEquals(warned.preview().issues(), warned.execute().report().issues());
        assertEquals(4, ignored.report().losses().size()); assertTrue(ignored.report().warnings().isEmpty());
        assertFalse(ignored.report().issues().stream().anyMatch(issue -> issue.reason().contains("label")));
    }
    @Test void hiddenTextNeedsExplicitDiscardAndPreserveCannotBeInvented() throws Exception {
        String source = "<p style='display:none'>Hidden</p><p>Visible</p>";
        assertThrows(ProcessingException.class, () -> new HtmlImporter().read(source).execute());
        var policy = ProcessingPolicy.builder().feature("HIDDEN_CONTENT", Action.WARN, Treatment.DISCARD).build();
        var result = HtmlImporter.builder().policy(policy).build().read(source).execute();
        assertEquals(1, result.document().blocks().size()); assertEquals(1, result.report().losses().size());
        var unavailable = ProcessingPolicy.builder().defaultRule(Action.IGNORE, Treatment.PRESERVE).build();
        assertThrows(IllegalArgumentException.class, () -> HtmlImporter.builder().policy(unavailable).build().read("<mark>x</mark>").execute());
    }
    @Test void sourceRangesUseOriginalUtf16WithCrLfAndEmoji() throws Exception {
        String source = "<p>😀</p>\r\n<p title=private>Text</p>";
        var issue = lenient(Action.WARN).read(source).execute().report().losses().getFirst();
        assertEquals(source.indexOf("<p title"), issue.location().startUtf16());
        assertEquals(source.length(), issue.location().endUtf16());
    }
    @Test void budgetsAndCancellationAreIndependentOfLossPolicy() {
        var limits = new ImportLimits(1000, 1000, 100, 8, 10, 4, 2, 1000);
        var importer = HtmlImporter.builder().limits(limits).build();
        assertThrows(IllegalArgumentException.class, () -> importer.read("<p>12345678901</p>").execute());
        assertThrows(IllegalArgumentException.class, () -> importer.read("<table><tr><td colspan=99999>x</td></tr></table>").execute());
        assertThrows(IllegalArgumentException.class, () -> importer.read("<div>".repeat(20) + "x" + "</div>".repeat(20)).execute());
        assertThrows(ExecutionException.class, () -> new HtmlImporter().read("<p>x</p>")
                .execution(ExecutionOptions.builder().cancelled(() -> true).build()).execute());
    }
    @Test void importerNeverFetchesEvenAnExplicitHttpImageKey() throws Exception {
        var requests = new AtomicInteger(); var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); }); server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/image";
            var resource = new InlineImage(ImageResource.load(image()), 12, 6, 0, "alt");
            new HtmlImporter().read("<p><a href='" + url + "'>Passive</a></p>").execute();
            HtmlImporter.builder().image(url, resource).build().read("<p><img src='" + url + "' alt=ALT></p>").execute();
            assertEquals(0, requests.get());
        } finally { server.stop(0); }
    }
    @Test void generatedIdsCannotCollideWithSourceAnchorsAndWhitespaceKeepsLinkOwnership() throws Exception {
        var result = new HtmlImporter().read("Bare text<p id=html-1><a href='https://example.org/link'>A </a><b>B</b></p>").execute();
        assertFalse(result.document().blocks().getFirst().id().equals("html-1"));
        assertEquals(1, DocumentEditor.edit(result.document()).find("A").matches().size());
        var runs = ((Paragraph) result.document().blocks().getLast()).inlines();
        var space = runs.stream().filter(run -> run.text().equals(" ")).findFirst().orElseThrow();
        assertEquals("https://example.org/link", space.hyperlink()); assertEquals("", space.style().fontRole());
    }
    @Test void cssNormalLineHeightScalesAndUnavailableTreatmentsStayErrors() throws Exception {
        var paragraph = (Paragraph) new HtmlImporter().read("<p style='font-size:24pt'>Large</p>").execute().document().blocks().getFirst();
        assertEquals(36, paragraph.format().lineHeight());
        var unavailable = ProcessingPolicy.builder().defaultRule(Action.WARN, Treatment.DISCARD).build();
        assertThrows(IllegalArgumentException.class, () -> HtmlImporter.builder().policy(unavailable).build().read("<p style='margin-top:2pt'>X</p>").execute());
        var failure = assertThrows(ProcessingException.class, () -> new HtmlImporter().read("<p style='font-family:Unknown'>X</p>").execute());
        assertEquals("CSS_FONT_FAMILY", failure.issue().feature());
    }
    @Test void sourceRepairsAndHiddenBodyAreExplicitLosses() throws Exception {
        var failure = assertThrows(ProcessingException.class, () -> new HtmlImporter().read("<p>A</span>B</p>").execute());
        assertEquals("HTML_REPAIR", failure.issue().feature());
        var policy = ProcessingPolicy.builder().feature("HIDDEN_CONTENT", Action.IGNORE, Treatment.DISCARD).build();
        var result = HtmlImporter.builder().policy(policy).build().read("<body style='display:none'>Bare secret<p>More secret</p></body>").execute();
        assertTrue(result.document().blocks().isEmpty()); assertEquals(1, result.report().losses().size());
    }
    @Test void importedSourceCanBeEditedAndExportedWithoutTemplateEvaluation() throws Exception {
        var source = new HtmlImporter().read("<p id=p>OLD ${name} <b>Strong</b></p>").execute();
        var edited = DocumentEditor.edit(source.document()).replaceText("OLD", "READY").execute().document();
        var bytes = new ByteArrayOutputStream();
        HtmlAdapter.builder().fontRole("bold", new FontStyle("Explicit", 700, false)).build().export(edited).to(bytes);
        var output = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(output.contains("READY")); assertTrue(output.contains("${name}")); assertFalse(output.contains("OLD"));
    }
    private static byte[] image() throws Exception {
        var image = new BufferedImage(12, 6, BufferedImage.TYPE_INT_RGB); var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "PNG", output)); return output.toByteArray();
    }
}
