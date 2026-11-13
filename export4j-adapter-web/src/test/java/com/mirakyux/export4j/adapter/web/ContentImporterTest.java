// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.InlineVector;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.model.SourceContentParser;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.VectorResource;
import com.mirakyux.export4j.model.processing.ProcessingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentImporterTest {
    private static final SourceContentParser.Remaining ROOM = new SourceContentParser.Remaining(100_000, 10_000, 10_000, 100_000, 64);
    private static final VectorResource VECTOR = VectorResource.of(8, 8, 0, List.of(
            new VectorResource.Segment(VectorResource.Operation.MOVE, List.of(0.0, 0.0)),
            new VectorResource.Segment(VectorResource.Operation.LINE, List.of(8.0, -8.0)),
            new VectorResource.Segment(VectorResource.Operation.CLOSE, List.of())));

    private static ContentOptions options(ContentOptions.Format format) { return ContentOptions.builder().format(format).build(); }
    private static ContentOptions.Resolved resolved(ContentOptions.Format format) { return options(format).resolve(ContentOptions.defaults()); }
    private static Paragraph first(ImportResult result) { return (Paragraph) result.document().blocks().getFirst(); }

    @Test void cssFamilyMappingMaySelectDefaultPhysicalFontsForItsRegularRole() throws Exception {
        var importer = ContentImporter.builder().defaults(options(ContentOptions.Format.HTML))
                .fontFamily("Fixture Sans", "", "bold", "italic", "boldItalic").build();
        var paragraph = first(importer.read("<p style='font-family:Fixture Sans'>plain <b>bold</b> <i>italic</i> <b><i>both</i></b></p>").execute());
        assertEquals("", paragraph.inlines().stream().filter(run -> run.text().startsWith("plain")).findFirst().orElseThrow().style().fontRole());
        assertEquals("bold", paragraph.inlines().stream().filter(run -> run.text().equals("bold")).findFirst().orElseThrow().style().fontRole());
        assertEquals("italic", paragraph.inlines().stream().filter(run -> run.text().equals("italic")).findFirst().orElseThrow().style().fontRole());
        assertEquals("boldItalic", paragraph.inlines().stream().filter(run -> run.text().equals("both")).findFirst().orElseThrow().style().fontRole());
    }

    @Test void cssFamilyMappingRejectsMalformedRolesInEverySlotButKeepsFamilyKeyValidation() {
        for (String invalid : List.of(" ", "bad-role", "two words", "9role", "x".repeat(257))) {
            for (int index = 0; index < 4; index++) {
                var roles = new String[] {"", "bold", "italic", "boldItalic"}; roles[index] = invalid;
                assertThrows(IllegalArgumentException.class, () -> ContentImporter.builder().fontFamily("Fixture Sans", roles[0], roles[1], roles[2], roles[3]));
            }
        }
        for (int index = 0; index < 4; index++) {
            var roles = new String[] {"", "bold", "italic", "boldItalic"}; roles[index] = null;
            assertThrows(NullPointerException.class, () -> ContentImporter.builder().fontFamily("Fixture Sans", roles[0], roles[1], roles[2], roles[3]));
        }
        assertThrows(IllegalArgumentException.class, () -> ContentImporter.builder().fontFamily(" ", "", "bold", "italic", "boldItalic"));
        assertThrows(NullPointerException.class, () -> ContentImporter.builder().fontFamily(null, "", "bold", "italic", "boldItalic"));
    }

    @Test void textIsDefaultAndPreservesMarkupAndTemplateSyntaxExactly() throws Exception {
        String source = "<b>literal</b> **text** $a\\_b$ ${name}\r\n😀";
        var result = ContentImporter.builder().build().read(source).execute();
        assertEquals(source, first(result).text());
        assertTrue(result.report().issues().isEmpty());
    }

    @Test void localOverridesInheritIndependentlyAndDoNotAffectSiblings() throws Exception {
        var importer = ContentImporter.builder().defaults(ContentOptions.builder().fontRole("body").rgb(0x123456)
                .fontSize(18).lineHeight(32).spaceBefore(4).spaceAfter(7).alignment(ParagraphAlignment.RIGHT)
                .firstLineIndent(2).subsequentLineIndent(3).keepTogether(true).keepWithNext(true)
                .pageBreakBefore(true).minLinesBeforeBreak(2).minLinesAfterBreak(3)
                .lineBreakPolicy(LineBreakPolicy.CJK_STRICT).rotation(9).build()).build();
        var local = first(importer.read("a").options(ContentOptions.builder().fontRole("").rgb(0).spaceBefore(0)
                .keepTogether(false).keepWithNext(false).pageBreakBefore(false).rotation(0).build()).execute());
        assertEquals("", local.inlines().getFirst().style().fontRole()); assertEquals(0, local.inlines().getFirst().style().rgb());
        assertEquals(18, local.format().fontSize()); assertEquals(32, local.format().lineHeight());
        assertEquals(0, local.format().spaceBefore()); assertEquals(7, local.format().spaceAfter());
        assertFalse(local.format().keepTogether()); assertFalse(local.format().pagination().keepWithNext());
        assertFalse(local.format().pagination().pageBreakBefore()); assertEquals(0, local.format().rotation());
        assertEquals(ParagraphAlignment.RIGHT, local.format().alignment()); assertEquals(LineBreakPolicy.CJK_STRICT, local.format().lineBreakPolicy());
        var sibling = first(importer.read("b").execute());
        assertEquals("body", sibling.inlines().getFirst().style().fontRole()); assertEquals(0x123456, sibling.inlines().getFirst().style().rgb());
        assertTrue(sibling.format().keepTogether()); assertEquals(9, sibling.format().rotation());
    }

    @Test void markdownFormulaOffProtectsOriginalTeXFromCommonMarkAndDoesNotEnableHtml() throws Exception {
        String tex = "$a\\_b \\{x\\}  + **literal**$";
        var importer = ContentImporter.builder().defaults(options(ContentOptions.Format.MARKDOWN)).build();
        assertEquals("Before " + tex + " after", first(importer.read("Before " + tex + " after").execute()).text());
        var error = assertThrows(ProcessingException.class, () -> importer.read("<div>HTML</div>").execute());
        assertEquals("RAW_HTML", error.issue().feature());
    }

    @Test void disabledFormulasUseOnlyGeneralInputWorkBudgetsRatherThanRenderingLimits() throws Exception {
        String source = "$long\\_formula$ $another\\_long\\_formula$";
        var importer = ContentImporter.builder().maxFormulas(1).maxFormulaUtf16(1).maxVectorSegments(1).build();
        assertEquals(source, first(importer.read(source).options(options(ContentOptions.Format.MARKDOWN)).execute()).text());
        assertEquals(source, first(importer.read("<p>" + source + "</p>").options(options(ContentOptions.Format.HTML)).execute()).text());
    }

    @Test void decodedMarkdownEntitiesCannotImpersonateLiteralFormulaPlaceholders() throws Exception {
        var importer = ContentImporter.builder().defaults(options(ContentOptions.Format.MARKDOWN)).build();
        var result = importer.read("EXPORT4JCONTEN&#84;0END $x$").execute();
        assertEquals("EXPORT4JCONTENT0END $x$", first(result).text());
    }

    @Test void decodedMarkdownImageDestinationsCannotImpersonateGeneratedFormulaResources() throws Exception {
        var importer = ContentImporter.builder().defaults(ContentOptions.builder().format(ContentOptions.Format.MARKDOWN).formulas(true).build())
                .formulaProcessor((tex, display, size, rgb, segments) -> new InlineVector(VECTOR, tex, rgb, "")).build();
        var error = assertThrows(ProcessingException.class, () -> importer.read("![](export4j-content:EXPORT4JCONTEN&#84;0END) $x$").execute());
        assertEquals("IMAGE_RESOURCE", error.issue().feature());
    }

    @Test void markdownTableOffPreservesOriginalCrLfAndEscapedPipesAfterFormulaRewrite() throws Exception {
        String table = "| A |\r\n|---|\r\n| x\\|y |";
        var importer = ContentImporter.builder().defaults(ContentOptions.builder().format(ContentOptions.Format.MARKDOWN).tables(false).build()).build();
        var result = importer.read("Before $a\\_b$\r\n\r\n" + table + "\r\n\r\nAfter").execute();
        assertEquals(3, result.document().blocks().size()); assertEquals(table, ((Paragraph) result.document().blocks().get(1)).text());
        assertEquals("Before $a\\_b$", first(result).text());
    }

    @Test void markdownCellAndTableDefaultsReachEveryProducedCellWithoutOverridingColumnAlignment() throws Exception {
        var cell = CellFormat.builder().fontSize(17).lineHeight(29).alignment(ParagraphAlignment.CENTER).lineBreakPolicy(LineBreakPolicy.CJK_STRICT).build();
        var tableFormat = TableFormat.builder().padding(3).spaceBefore(4).spaceAfter(5).allowRowSplit(true).drawBorders(false).rotation(8).build();
        var importer = ContentImporter.builder().defaults(ContentOptions.builder().format(ContentOptions.Format.MARKDOWN)
                .fontRole("body").rgb(0x345678).cellFormat(cell).tableFormat(tableFormat).build()).build();
        var table = (Table) importer.read("| A | B |\n|---|---:|\n| c | d |").execute().document().blocks().getFirst();
        assertEquals(tableFormat, table.format());
        for (var row : table.rows()) {
            assertEquals(cell, row.cells().getFirst().format()); assertEquals(cell.withAlignment(ParagraphAlignment.RIGHT), row.cells().get(1).format());
            assertEquals("body", row.cells().getFirst().inlines().getFirst().style().fontRole());
            assertEquals(17, row.cells().getFirst().inlines().getFirst().style().fontSize());
            assertEquals(0x345678, row.cells().getFirst().inlines().getFirst().style().rgb());
        }
    }

    @Test void htmlTableOffUsesOriginalSourceRatherThanNormalizedOuterHtml() throws Exception {
        String source = "<TABLE data-note='keep' >\r\n<TBODY><TR><TD>A &amp; B</TD></TR></TBODY></TABLE>";
        var importer = ContentImporter.builder().defaults(ContentOptions.builder().format(ContentOptions.Format.HTML).tables(false).build()).build();
        assertEquals(source, first(importer.read(source).execute()).text());
        assertThrows(IllegalArgumentException.class, () -> importer.read("<table onclick='bad()'><tr><td>x</td></tr></table>").execute());
    }

    @Test void htmlCssRemainsExplicitAndOtherwiseInheritsFullBodyAndCellDefaults() throws Exception {
        var cell = CellFormat.builder().fontSize(17).lineHeight(29).alignment(ParagraphAlignment.RIGHT).lineBreakPolicy(LineBreakPolicy.CJK_STRICT).build();
        var tableFormat = TableFormat.builder().padding(2).spaceBefore(3).spaceAfter(4).allowRowSplit(true).drawBorders(false).rotation(7).build();
        var importer = ContentImporter.builder().defaults(ContentOptions.builder().format(ContentOptions.Format.HTML).fontRole("body").rgb(0x234567)
                .fontSize(15).lineHeight(26).alignment(ParagraphAlignment.CENTER).spaceBefore(4).spaceAfter(8)
                .keepTogether(true).keepWithNext(true).minLinesBeforeBreak(2).minLinesAfterBreak(3)
                .cellFormat(cell).tableFormat(tableFormat).build()).build();
        var result = importer.read("<p>base <span style='color:red;font-size:19pt'>red</span></p><table><tr><td>cell</td><td style='font-size:20pt;line-height:31pt;text-align:left'>own</td></tr></table>").execute();
        var paragraph = first(result);
        assertEquals(15, paragraph.format().fontSize()); assertEquals(26, paragraph.format().lineHeight());
        assertEquals(ParagraphAlignment.CENTER, paragraph.format().alignment()); assertEquals(4, paragraph.format().spaceBefore()); assertEquals(8, paragraph.format().spaceAfter());
        assertTrue(paragraph.format().keepTogether()); assertTrue(paragraph.format().pagination().keepWithNext());
        assertEquals(2, paragraph.format().pagination().minLinesBeforeBreak()); assertEquals(3, paragraph.format().pagination().minLinesAfterBreak());
        var red = paragraph.inlines().stream().filter(run -> run.text().equals("red")).findFirst().orElseThrow();
        assertEquals(0xff0000, red.style().rgb()); assertEquals(19, red.style().fontSize()); assertEquals("body", red.style().fontRole());
        var table = (Table) result.document().blocks().get(1); assertEquals(tableFormat, table.format());
        assertEquals(cell, table.rows().getFirst().cells().getFirst().format());
        assertEquals(new CellFormat(20, 31, ParagraphAlignment.LEFT, LineBreakPolicy.CJK_STRICT), table.rows().getFirst().cells().get(1).format());
    }

    @Test void htmlFormulaOffPreservesTeXWhitespaceWithoutChangingOtherHtmlWhitespaceRules() throws Exception {
        String tex = "$a  \\_b\t+ c$";
        var result = ContentImporter.builder().defaults(options(ContentOptions.Format.HTML)).build().read("<p>before  " + tex + "  after &amp;</p>").execute();
        assertEquals("before " + tex + " after &", first(result).text());
    }

    @Test void explicitFormulaProcessorReceivesLocalSizeColorAndRemainingSegmentsAcrossFormats() throws Exception {
        var calls = new ArrayList<String>();
        var importer = ContentImporter.builder().formulaProcessor((tex, display, size, rgb, segments) -> {
            calls.add(tex + ":" + size + ":" + rgb + ":" + segments); return new InlineVector(VECTOR, tex, rgb, "");
        }).maxVectorSegments(9).build();
        try (var session = importer.openSession()) {
            for (var format : List.of(ContentOptions.Format.TEXT, ContentOptions.Format.MARKDOWN, ContentOptions.Format.HTML)) {
                var options = ContentOptions.builder().format(format).formulas(true).fontSize(19).rgb(0x123456).build().resolve(ContentOptions.defaults());
                var blocks = session.parse(format.name(), format == ContentOptions.Format.HTML ? "<p>$x$</p>" : "$x$", options, ROOM);
                var vector = (InlineVector) ((Paragraph) blocks.getFirst()).content().getFirst();
                assertEquals("x", vector.logicalText()); assertEquals(0x123456, vector.rgb());
            }
        }
        assertEquals(List.of("x:19.0:1193046:9", "x:19.0:1193046:6", "x:19.0:1193046:3"), calls);
    }

    @Test void markdownTableFormulaUsesCellSizeAndDisabledTableNeverRendersItsContents() throws Exception {
        var sizes = new ArrayList<Double>();
        var importer = ContentImporter.builder().formulaProcessor((tex, display, size, rgb, segments) -> {
            sizes.add(size); return new InlineVector(VECTOR, tex, rgb, "");
        }).defaults(ContentOptions.builder().format(ContentOptions.Format.MARKDOWN).formulas(true).fontSize(12).cellFormat(CellFormat.of(21)).build()).build();
        importer.read("| A |\n|---|\n| $x$ |").execute(); assertEquals(List.of(21.0), sizes);
        importer.read("| A |\n|---|\n| $y$ |").options(ContentOptions.builder().tables(false).build()).execute(); assertEquals(List.of(21.0), sizes);
    }

    @Test void enablingFormulaParsingWithoutAssemblyFailsEvenForFormulaFreeSource() {
        assertThrows(IllegalStateException.class, () -> ContentImporter.builder().defaults(ContentOptions.builder().formulas(true).build()).build().read("plain").execute());
    }

    @Test void formulaScannerProtectsMarkdownCodeLinksImagesAndHtml() {
        var spans = FormulaSyntax.markdown("`$code$` [text $link$](https://example.org/) ![$alt$](image) <span title='$html$'>text</span>\n\n$x$", 10_000, 10, 100);
        assertEquals(List.of("x"), spans.stream().map(FormulaSyntax.Span::tex).toList());
    }

    @Test void htmlFormulaParserIgnoresLinkAttributeAndCodeText() throws Exception {
        var calls = new AtomicInteger();
        var importer = ContentImporter.builder().defaults(ContentOptions.builder().format(ContentOptions.Format.HTML).formulas(true).build())
                .formulaProcessor((tex, display, size, rgb, segments) -> { calls.incrementAndGet(); return new InlineVector(VECTOR, tex, rgb, ""); }).build();
        importer.read("<p><a href='https://example.org/$attribute$'>$link$</a> $body$</p>").execute(); assertEquals(1, calls.get());
        var code = importer.read("<p><code>$code$</code></p>").execute();
        assertEquals("$code$", first(code).text()); assertEquals(1, calls.get());
    }

    @Test void sessionAccumulatesInputTableSlotsFormulaCountsAndWorkInsteadOfResettingPerChunk() throws Exception {
        var inputLimited = ContentImporter.builder().limits(new ImportLimits(5, 100, 1000, 64, 1000, 100, 100, 10_000)).build();
        try (var session = inputLimited.openSession()) {
            session.parse("one", "abc", ContentOptions.defaults(), ROOM);
            assertThrows(IllegalArgumentException.class, () -> session.parse("two", "def", ContentOptions.defaults(), ROOM));
        }
        var tableLimited = ContentImporter.builder().limits(new ImportLimits(1000, 1000, 1000, 64, 1000, 3, 100, 10_000)).build();
        try (var session = tableLimited.openSession()) {
            session.parse("one", "| A |\n|---|\n| b |", resolved(ContentOptions.Format.MARKDOWN), ROOM);
            assertThrows(IllegalArgumentException.class, () -> session.parse("two", "| C |\n|---|\n| d |", resolved(ContentOptions.Format.MARKDOWN), ROOM));
        }
        var calls = new AtomicInteger();
        var formulaLimited = ContentImporter.builder().maxFormulas(1).formulaProcessor((tex, display, size, rgb, segments) -> {
            calls.incrementAndGet(); return new InlineVector(VECTOR, tex, rgb, "");
        }).build();
        try (var session = formulaLimited.openSession()) {
            var options = ContentOptions.builder().formulas(true).build().resolve(ContentOptions.defaults());
            session.parse("one", "$x$", options, ROOM);
            assertThrows(FormulaSyntax.LimitExceededException.class, () -> session.parse("two", "$y$", options, ROOM));
            assertEquals(1, calls.get());
        }
        var workLimited = ContentImporter.builder().limits(new ImportLimits(1000, 1000, 1000, 64, 1000, 100, 100, 10)).build();
        try (var session = workLimited.openSession()) {
            session.parse("one", "abcd", ContentOptions.defaults(), ROOM);
            assertThrows(IllegalArgumentException.class, () -> session.parse("two", "ab", ContentOptions.defaults(), ROOM));
        }
    }

    @Test void sessionNamespacesPreventCollisionsIncludingExplicitHtmlIdsAndNestedTableBlocks() {
        var importer = ContentImporter.builder().build();
        try (var session = importer.openSession()) {
            String source = "<p id='same'>a</p><table id='table'><tr><td><p id='nested'>b</p></td></tr></table>";
            var one = session.parse("one", source, resolved(ContentOptions.Format.HTML), ROOM);
            var two = session.parse("two", source, resolved(ContentOptions.Format.HTML), ROOM);
            var all = new ArrayList<>(one); all.addAll(two);
            new Document(com.mirakyux.export4j.model.DocumentMetadata.EMPTY, com.mirakyux.export4j.model.PageSettings.A4, all);
            SemanticTree.walk(one, visit -> assertTrue(visit.block().id().startsWith("one:")));
            SemanticTree.walk(two, visit -> assertTrue(visit.block().id().startsWith("two:")));
            assertThrows(IllegalArgumentException.class, () -> session.parse("one", "again", ContentOptions.defaults(), ROOM));
        }
    }

    @Test void strictLossDiagnosticsMapBackToOriginalSourceAfterFormulaRewrite() {
        String source = "$a\\_b$ then <i>raw</i>";
        var importer = ContentImporter.builder().defaults(options(ContentOptions.Format.MARKDOWN)).build();
        var error = assertThrows(ProcessingException.class, () -> importer.read(source).execute());
        assertEquals("RAW_HTML", error.issue().feature());
        assertEquals(source.indexOf("<i>"), error.issue().location().startUtf16());
        assertEquals(source.indexOf("<i>") + 3, error.issue().location().endUtf16());
        var beforeFormula = assertThrows(ProcessingException.class, () -> importer.read("<i>$a\\_b$").execute());
        assertEquals(0, beforeFormula.issue().location().startUtf16());
        assertEquals(3, beforeFormula.issue().location().endUtf16());
    }

    @Test void explicitVectorsShareTheSessionSegmentBudgetAndTheirObjectCharacterConsumesOutputAllowance() {
        var vector = new InlineVector(VECTOR, "", 0, "");
        var importer = ContentImporter.builder().maxVectorSegments(5).vector("mark", vector).build();
        try (var session = importer.openSession()) {
            session.parse("one", "![](mark)", resolved(ContentOptions.Format.MARKDOWN), ROOM);
            assertThrows(IllegalArgumentException.class, () -> session.parse("two", "<p><img src='mark'></p>", resolved(ContentOptions.Format.HTML), ROOM));
        }
        try (var session = importer.openSession()) {
            assertThrows(IllegalArgumentException.class, () -> session.parse("zero", "![](mark)", resolved(ContentOptions.Format.MARKDOWN),
                    new SourceContentParser.Remaining(1000, 100, 100, 0, 64)));
        }
        try (var session = importer.openSession()) {
            var block = (Paragraph) session.parse("one", "![](mark)", resolved(ContentOptions.Format.MARKDOWN),
                    new SourceContentParser.Remaining(1000, 100, 100, 1, 64)).getFirst();
            assertEquals("\uFFFC", block.text());
        }
    }

    @Test void formulaRendererReceivesTheRemainingOutputPathAllowanceAndIsNotCalledForZeroTextAllowance() {
        var received = new ArrayList<Integer>();
        var importer = ContentImporter.builder().formulaProcessor((tex, display, size, rgb, segments) -> {
            received.add(segments);
            if (segments < VECTOR.segmentCount()) { throw new IllegalArgumentException("small segment budget"); }
            return new InlineVector(VECTOR, tex, rgb, "");
        }).build();
        var options = ContentOptions.builder().formulas(true).build().resolve(ContentOptions.defaults());
        try (var session = importer.openSession()) {
            assertEquals("small segment budget", assertThrows(IllegalArgumentException.class, () -> session.parse("paths", "$x$", options,
                    new SourceContentParser.Remaining(4, 100, 100, 100, 64))).getMessage());
            assertEquals(List.of(2), received);
            assertThrows(IllegalArgumentException.class, () -> session.parse("onePoint", "$x$", options,
                    new SourceContentParser.Remaining(1000, 100, 100, 1, 64)));
            assertEquals(List.of(2), received);
            assertThrows(IllegalArgumentException.class, () -> session.parse("text", "$x$", options,
                    new SourceContentParser.Remaining(1000, 100, 100, 0, 64)));
            assertEquals(List.of(2), received);
        }
    }

    @Test void parserReservationIsReleasedWhenDepthValidationFailsAfterParsing() {
        var importer = ContentImporter.builder().limits(new ImportLimits(1000, 1000, 40, 64, 1000, 100, 100, 10_000)).build();
        try (var session = importer.openSession()) {
            assertThrows(IllegalArgumentException.class, () -> session.parse("deep", "abcdef", resolved(ContentOptions.Format.MARKDOWN),
                    new SourceContentParser.Remaining(100, 100, 100, 100, 1)));
            assertEquals("x", ((Paragraph) session.parse("next", "x", resolved(ContentOptions.Format.MARKDOWN), ROOM).getFirst()).text());
        }
    }

    @Test void outputAllowancesAndParserReservationsFailBeforeRendererAndReleaseReservations() {
        var calls = new AtomicInteger();
        var importer = ContentImporter.builder().formulaProcessor((tex, display, size, rgb, segments) -> {
            calls.incrementAndGet(); return new InlineVector(VECTOR, tex, rgb, "");
        }).build();
        try (var session = importer.openSession()) {
            var math = ContentOptions.builder().format(ContentOptions.Format.MARKDOWN).formulas(true).build().resolve(ContentOptions.defaults());
            assertThrows(IllegalArgumentException.class, () -> session.parse("large", "$x$", math, new SourceContentParser.Remaining(2, 100, 100, 100, 64)));
            assertEquals(0, calls.get());
            assertEquals(1, session.parse("small", "$x$", math, ROOM).size());
            assertEquals(1, calls.get());
            assertThrows(IllegalArgumentException.class, () -> session.parse("row", "| A |\n|---|\n| b |", resolved(ContentOptions.Format.MARKDOWN), new SourceContentParser.Remaining(1000, 100, 0, 100, 64)));
            assertThrows(IllegalArgumentException.class, () -> session.parse("chars", "😀a", ContentOptions.defaults(), new SourceContentParser.Remaining(1000, 100, 100, 1, 64)));
        }
    }

    @Test void templateSourceOutputIsNotInterpolatedAgainAndLoopBudgetsRemainShared() {
        var importer = ContentImporter.builder().limits(new ImportLimits(9, 1000, 1000, 64, 1000, 100, 100, 10_000)).build();
        var source = DocumentTemplate.Source.value("body", "item.text", ContentOptions.defaults(), importer);
        var template = DocumentTemplate.builder().nodes(List.of(new DocumentTemplate.Each("items", "item", List.of(source)))).build();
        var literal = template.render(Map.of("items", List.of(Map.of("text", "${name}")), "name", "changed"));
        assertEquals("${name}", literal.paragraphs().getFirst().text());
        assertThrows(IllegalArgumentException.class, () -> template.render(Map.of("items", List.of(Map.of("text", "12345"), Map.of("text", "67890")))));
    }

    @Test void explicitMixedMarkdownPreservesHtmlTablesAndRendersFormulasAtResolvedCellMetrics() throws Exception {
        var seen = new ArrayList<String>();
        var importer = ContentImporter.builder().defaults(ContentOptions.builder().format(ContentOptions.Format.MARKDOWN)
                .rawHtml(true).formulas(true).cellFormat(CellFormat.of(19)).build())
                .formulaProcessor((tex, display, size, rgb, segments) -> { seen.add(tex + ":" + size); return new InlineVector(VECTOR, tex, rgb, ""); }).build();
        String source = "Before $x < 1$ and $y > 2$.\n\n<table><tr><td>$z$</td></tr></table>\n\n`$literal$`";
        var result = importer.read(source).execute();
        assertEquals(List.of("x < 1:12.0", "y > 2:12.0", "z:19.0"), seen);
        assertEquals(3, result.document().blocks().size());
        assertEquals(2, first(result).vectors().size());
        var table = (Table) result.document().blocks().get(1);
        assertEquals(0, table.headerRows()); assertEquals(1, table.rows().size());
        assertEquals("z", table.rows().getFirst().cells().getFirst().vectors().getFirst().vector().logicalText());
        assertEquals("$literal$", ((Paragraph) result.document().blocks().getLast()).text());
        assertTrue(result.report().issues().isEmpty());
    }

    @Test void htmlFormulaIsolationPrecedesTagParsingAndDecodesCharacterReferencesOnce() throws Exception {
        var seen = new ArrayList<String>();
        var importer = ContentImporter.builder().defaults(ContentOptions.builder().format(ContentOptions.Format.HTML).formulas(true).build())
                .formulaProcessor((tex, display, size, rgb, segments) -> { seen.add(tex); return new InlineVector(VECTOR, tex, rgb, ""); }).build();
        importer.read("<p>$x<y$ and $a&gt;b$ <a href='https://example.org/$attribute$'>$link$</a> <code>$code$</code></p>").execute();
        assertEquals(List.of("x<y", "a>b"), seen);
    }

    @Test void headingAndBothListKindsKeepFormulaObjectsWithAndWithoutRawHtml() throws Exception {
        for (boolean raw : List.of(false, true)) {
            var importer = ContentImporter.builder().defaults(ContentOptions.builder().format(ContentOptions.Format.MARKDOWN)
                    .rawHtml(raw).formulas(true).build()).formulaProcessor((tex, display, size, rgb, segments) -> new InlineVector(VECTOR, tex, rgb, "")).build();
            var blocks = importer.read("# Heading $x$\n\n- Item $y$\n\n1. Ordered $z$\n").execute().document().blocks();
            assertEquals("x", ((Heading) blocks.get(0)).paragraph().vectors().getFirst().vector().logicalText());
            assertEquals("y", ((BulletItem) blocks.get(1)).paragraph().vectors().getFirst().vector().logicalText());
            assertEquals("z", ((NumberedItem) blocks.get(2)).paragraph().vectors().getFirst().vector().logicalText());
        }
    }

    @Test void mixedMarkupKeepsCodeWhitespaceAndLiteralDollarsAndDoesNotActivateEmbeddedCodeTags() throws Exception {
        String source = "```html\n<pre>$code$</pre>  \n  next\n```\n\nAfter $x$";
        var calls = new AtomicInteger();
        var importer = ContentImporter.builder().defaults(ContentOptions.builder().format(ContentOptions.Format.MARKDOWN).rawHtml(true).formulas(true).build())
                .formulaProcessor((tex, display, size, rgb, segments) -> { calls.incrementAndGet(); return new InlineVector(VECTOR, tex, rgb, ""); }).build();
        var result = importer.read(source).execute();
        assertEquals("<pre>$code$</pre>  \n  next\n", first(result).text());
        assertEquals(1, calls.get());
    }

    @Test void mixedHtmlRejectsUnknownAttributesAtOriginalSourceAndCannotImpersonateFormulaMarkers() throws Exception {
        String source = "Before $x$.\n\n<div data-extra='unknown'>keep</div>";
        var importer = ContentImporter.builder().defaults(ContentOptions.builder().format(ContentOptions.Format.MARKDOWN).rawHtml(true).formulas(true).build())
                .formulaProcessor((tex, display, size, rgb, segments) -> new InlineVector(VECTOR, tex, rgb, "")).build();
        var error = assertThrows(ProcessingException.class, () -> importer.read(source).execute());
        assertEquals("HTML_ATTRIBUTE", error.issue().feature());
        assertEquals("<div data-extra='unknown'>keep</div>", source.substring(error.issue().location().startUtf16(), error.issue().location().endUtf16()));
        var result = importer.read("<p>EXPORT4JCONTEN&#84;0END $x$</p>").execute();
        assertEquals("EXPORT4JCONTENT0END \uFFFC", first(result).text());
        assertEquals(1, first(result).vectors().size());
        assertThrows(IllegalArgumentException.class, () -> importer.read("<p onclick='bad()'>$x$</p>").execute());
    }

    @Test void disabledMixedTablesPreserveOriginalSourceAndNeverRenderTheirFormulas() throws Exception {
        var calls = new AtomicInteger();
        var importer = ContentImporter.builder().defaults(ContentOptions.builder().format(ContentOptions.Format.MARKDOWN)
                .rawHtml(true).tables(false).formulas(true).build()).formulaProcessor((tex, display, size, rgb, segments) -> {
                    calls.incrementAndGet(); return new InlineVector(VECTOR, tex, rgb, "");
                }).build();
        for (String table : List.of("| A |\r\n|---|\r\n| $x$ |", "<table><tr><td>$x$</td></tr></table>")) {
            assertEquals(table, first(importer.read(table).execute()).text());
        }
        assertEquals(0, calls.get());
    }

    @Test void headerOnlyAndHeaderSpansKeepActualRowsWithoutInventingBodyContent() throws Exception {
        var importer = ContentImporter.builder().defaults(options(ContentOptions.Format.HTML)).build();
        var table = (Table) importer.read("<table><thead><tr><th rowspan='2'>A</th><th>B</th></tr></thead><tbody><tr><td>C</td></tr></tbody></table>").execute().document().blocks().getFirst();
        assertEquals(0, table.headerRows()); assertEquals(2, table.rows().size());
        assertEquals(2, table.rows().getFirst().cells().getFirst().rowSpan());
        assertEquals("bold", table.rows().getFirst().cells().getFirst().inlines().getFirst().style().fontRole());
        assertEquals("C", table.rows().get(1).cells().getFirst().text());
        var one = (Table) importer.read("<table><tr><th>Only</th></tr></table>").execute().document().blocks().getFirst();
        assertEquals(0, one.headerRows()); assertEquals(1, one.rows().size());
    }

    @Test void parserNodeLimitCountsActualNodesRatherThanHypotheticalFourNodesPerCharacter() throws Exception {
        String source = ("x".repeat(400) + "\n\n").repeat(70);
        var importer = ContentImporter.builder().defaults(options(ContentOptions.Format.MARKDOWN)).build();
        assertEquals(70, importer.read(source).execute().document().blocks().size());
        var small = ContentImporter.builder().limits(new ImportLimits(100_000, 1000, 8, 64, 100_000, 1000, 100, 1_000_000))
                .defaults(options(ContentOptions.Format.MARKDOWN)).build();
        assertThrows(IllegalArgumentException.class, () -> small.read("a\n\nb\n\nc\n\nd").execute());
    }

    @Test void cancellationPropagatesAndClosedSessionCannotBeReused() throws Exception {
        var importer = ContentImporter.builder().build();
        assertThrows(ExecutionException.class, () -> importer.read("body").execution(ExecutionOptions.builder().cancelled(() -> true).build()).execute());
        assertEquals("body", first(importer.read("body").execute()).text());
        var session = importer.openSession(); session.close();
        assertThrows(IllegalStateException.class, () -> session.parse("closed", "body", ContentOptions.defaults(), ROOM));
        assertThrows(ExecutionException.class, () -> ExecutionContext.execute(ExecutionOptions.builder().cancelled(() -> true).build(), () -> {
            try (var another = importer.openSession()) { another.parse("cancel", "body", ContentOptions.defaults(), ROOM); }
            return null;
        }));
    }
}
