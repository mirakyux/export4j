// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DocumentTemplateSourceTest {
    private static final ParagraphFormat FORMAT = ParagraphFormat.of(12);

    private static final class RecordingParser implements SourceContentParser {
        private final AtomicInteger opens = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private final List<Remaining> allowances = new ArrayList<>();
        @Override public Session openSession() {
            opens.incrementAndGet();
            return new Session() {
                @Override public List<Block> parse(String id, String source, ContentOptions.Resolved options, Remaining remaining) {
                    allowances.add(remaining);
                    return List.of(new Paragraph(id, source, options.paragraphFormat(),
                            List.of(new InlineText(source, options.textAppearance().style(options.paragraphFormat().fontSize())))));
                }
                @Override public void close() { closes.incrementAndGet(); }
            };
        }
    }

    @Test void literalAndWholeValueInputsAreNeverInterpolatedAgainAndLoopIdsRemainStable() {
        var parser = new RecordingParser();
        var local = ContentOptions.builder().fontRole("brand").rgb(0x123456).fontSize(10)
                .build().resolve(ContentOptions.defaults());
        var template = DocumentTemplate.builder()
                .node(DocumentTemplate.Source.literal("literal", "${secret}", local, parser))
                .each("items", "item", nodes -> nodes.node(DocumentTemplate.Source.value("line", "item.text", local, parser)))
                .build();
        var data = Map.of("secret", "MUST NOT SUBSTITUTE", "items", List.of(Map.of("text", "${secret}"), Map.of("text", "A😀")));
        var result = template.render(data);
        assertEquals(List.of("literal", "line@0", "line@1"), result.blocks().stream().map(Block::id).toList());
        assertEquals(List.of("${secret}", "${secret}", "A😀"), result.paragraphs().stream().map(Paragraph::text).toList());
        assertEquals("brand", result.paragraphs().getFirst().inlines().getFirst().style().fontRole());
        assertEquals(0x123456, result.paragraphs().getFirst().inlines().getFirst().style().rgb());
        assertEquals(1, parser.opens.get()); assertEquals(1, parser.closes.get());
        assertTrue(parser.allowances.get(1).maxBlocks() < parser.allowances.get(0).maxBlocks());
        assertTrue(parser.allowances.get(2).maxCodePoints() < parser.allowances.get(1).maxCodePoints());
        assertEquals(result, template.render(data));
        assertEquals(2, parser.opens.get()); assertEquals(2, parser.closes.get());
    }

    @Test void sectionsShareOneSessionAndTemplateBudgetWithoutResettingCounters() {
        var parser = new RecordingParser();
        var template = DocumentTemplate.builder().sections(List.of(
                new DocumentTemplate.SectionNode("one", PageSettings.A4,
                        List.of(DocumentTemplate.Source.literal("a", "A", parser)), 0, PageRegions.EMPTY),
                new DocumentTemplate.SectionNode("two", PageSettings.A4,
                        List.of(DocumentTemplate.Source.literal("b", "B", parser)), 0, PageRegions.EMPTY)))
                .limits(new DocumentTemplate.Limits(100, 2, 10, 100, 10)).build();
        assertEquals(2, template.renderSections(Map.of()).sections().size());
        assertEquals(List.of(2, 1), parser.allowances.stream().map(SourceContentParser.Remaining::maxBlocks).toList());
        assertEquals(1, parser.opens.get()); assertEquals(1, parser.closes.get());
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentTemplate.BindingException.class,
                        () -> template.toBuilder().limits(new DocumentTemplate.Limits(100, 1, 10, 100, 10)).build()
                                .renderSections(Map.of())).code());
        assertEquals(2, parser.closes.get());
    }

    @Test void equalParserObjectsStillReceiveIndependentIdentityScopedSessions() {
        class EqualParser implements SourceContentParser {
            private final String prefix;
            private int opens;
            private EqualParser(String prefix) { this.prefix = prefix; }
            @Override public Session openSession() {
                opens++;
                return (id, source, options, remaining) -> List.of(new Paragraph(id, prefix + source, FORMAT));
            }
            @Override public boolean equals(Object value) { return value instanceof EqualParser; }
            @Override public int hashCode() { return 0; }
        }
        var first = new EqualParser("one:"); var second = new EqualParser("two:");
        var template = DocumentTemplate.builder().node(DocumentTemplate.Source.literal("a", "A", first))
                .node(DocumentTemplate.Source.literal("b", "B", second)).build();
        assertEquals(List.of("one:A", "two:B"), template.render(Map.of()).paragraphs().stream().map(Paragraph::text).toList());
        assertEquals(1, first.opens); assertEquals(1, second.opens);
    }

    @Test void wrongValueTypeOrMissingPathCannotFallBackToText() {
        var parser = new RecordingParser();
        var template = DocumentTemplate.builder().node(DocumentTemplate.Source.value("body", "body", parser)).build();
        assertEquals(DocumentTemplate.BindingException.Code.MISSING_VALUE,
                assertThrows(DocumentTemplate.BindingException.class, () -> template.render(Map.of())).code());
        assertEquals(DocumentTemplate.BindingException.Code.TYPE_MISMATCH,
                assertThrows(DocumentTemplate.BindingException.class, () -> template.render(Map.of("body", 42))).code());
        assertEquals(0, parser.opens.get());
    }

    @Test void parserOutputIsIndependentlyCheckedForTextRowsDepthAndVectorWork() {
        SourceContentParser text = () -> (id, source, options, remaining) -> List.of(new Paragraph(id, "x".repeat(80), FORMAT));
        var bounded = new DocumentTemplate.Limits(100, 10, 10, 30, 10);
        assertBudget(text, bounded);
        SourceContentParser table = () -> (id, source, options, remaining) -> List.of(Table.builder(id).row("A").row("B").build());
        assertBudget(table, new DocumentTemplate.Limits(100, 10, 1, 100, 10));
        SourceContentParser nested = () -> (id, source, options, remaining) -> List.of(Table.builder(id)
                .row(TableCell.builder().paragraph(new Paragraph(id + ":p", "A", FORMAT)).build()).build());
        assertBudget(nested, new DocumentTemplate.Limits(100, 10, 10, 100, 1));
        var path = new ArrayList<VectorResource.Segment>();
        path.add(new VectorResource.Segment(VectorResource.Operation.MOVE, List.of(0.0, 0.0)));
        for (int i = 0; i < 30; i++) { path.add(new VectorResource.Segment(VectorResource.Operation.LINE, List.of(1.0, 0.0))); }
        var vector = new InlineVector(VectorResource.of(2, 2, 0, path), "x");
        SourceContentParser vectors = () -> (id, source, options, remaining) -> List.of(Paragraph.mixed(id, List.of(vector), FORMAT));
        assertBudget(vectors, new DocumentTemplate.Limits(20, 10, 10, 100, 10));
    }

    private static void assertBudget(SourceContentParser parser, DocumentTemplate.Limits limits) {
        var template = DocumentTemplate.builder().limits(limits)
                .node(DocumentTemplate.Source.literal("s", "", parser)).build();
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentTemplate.BindingException.class, () -> template.render(Map.of())).code());
    }

    @Test void parserIdsCannotEscapeTheSourceNamespaceOrCollideWithExistingBlocks() {
        SourceContentParser invalid = () -> (id, source, options, remaining) -> List.of(new Paragraph("outside", "A", FORMAT));
        var template = DocumentTemplate.builder().node(DocumentTemplate.Source.literal("s", "A", invalid)).build();
        assertEquals(DocumentTemplate.BindingException.Code.INVALID_VALUE,
                assertThrows(DocumentTemplate.BindingException.class, () -> template.render(Map.of())).code());
        var parser = new RecordingParser();
        assertThrows(IllegalArgumentException.class, () -> DocumentTemplate.builder()
                .block(new Paragraph("same", "A", FORMAT)).node(DocumentTemplate.Source.literal("same", "B", parser))
                .build().render(Map.of()));
    }

    @Test void sessionsCloseOnFailureAndCancellationWithPrimaryFailurePreserved() {
        var primary = new IllegalStateException("parser failure");
        var cleanup = new IllegalStateException("cleanup failure");
        SourceContentParser failure = () -> new SourceContentParser.Session() {
            @Override public List<Block> parse(String id, String source, ContentOptions.Resolved options, SourceContentParser.Remaining remaining) {
                throw primary;
            }
            @Override public void close() { throw cleanup; }
        };
        var template = DocumentTemplate.builder().node(DocumentTemplate.Source.literal("s", "A", failure)).build();
        assertSame(primary, assertThrows(IllegalStateException.class, () -> template.render(Map.of())));
        assertEquals(List.of(cleanup), List.of(primary.getSuppressed()));
        var cancelled = new AtomicBoolean();
        var closes = new AtomicInteger();
        SourceContentParser cancellation = () -> new SourceContentParser.Session() {
            @Override public List<Block> parse(String id, String source, ContentOptions.Resolved options, SourceContentParser.Remaining remaining) {
                cancelled.set(true); return List.of(new Paragraph(id, "A", FORMAT));
            }
            @Override public void close() { closes.incrementAndGet(); }
        };
        var cancelTemplate = DocumentTemplate.builder().node(DocumentTemplate.Source.literal("s", "A", cancellation)).build();
        assertEquals(ExecutionException.Code.CANCELLED, assertThrows(ExecutionException.class,
                () -> ExecutionContext.execute(ExecutionOptions.builder().cancelled(cancelled::get).build(),
                        () -> cancelTemplate.render(Map.of()))).code());
        assertEquals(1, closes.get());
    }

    @Test void dynamicCellsShareBindingScopePreserveSpansAndReturnOnlySemanticBlocks() {
        var parser = new RecordingParser();
        var prototype = TableCell.builder().format(CellFormat.of(9)).columnSpan(2)
                .paragraph("placeholder", "unused").build();
        var content = new DocumentTemplate.CellContent(prototype,
                List.of(DocumentTemplate.Source.value("content", "item.text", parser)));
        var template = DocumentTemplate.builder().cellContents(List.of(content))
                .node(new DocumentTemplate.Rows("table", "items", "item",
                        List.of(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1)), List.of(),
                        new TableRow(List.of(prototype)), TableFormat.DEFAULT)).build();
        var result = (Table) template.render(Map.of("items", List.of(Map.of("text", "${secret}"), Map.of("text", "B")))).blocks().getFirst();
        var first = result.rows().getFirst().cells().getFirst();
        assertEquals(2, first.columnSpan()); assertEquals(CellFormat.of(9), first.format());
        assertEquals("${secret}", ((Paragraph) first.blocks().getFirst()).text());
        assertEquals("content@row:table:0", first.blocks().getFirst().id());
        assertEquals("content@row:table:1", result.rows().get(1).cells().getFirst().blocks().getFirst().id());
        assertEquals(1, parser.opens.get()); assertEquals(1, parser.closes.get());
        assertSame(prototype, template.toBuilder().build().cellContents().getFirst().prototype());
        assertEquals("unused", ((Paragraph) prototype.blocks().getFirst()).text());
    }

    @Test void sourcePlacementRetainsColumnGridAndCellTypeRestrictions() {
        var parser = new RecordingParser();
        var source = DocumentTemplate.Source.literal("s", "A", parser);
        var columns = DocumentTemplate.builder().columns("columns", 2, 12, false, List.of(), nodes -> nodes.node(source)).build();
        assertEquals("A", ((Paragraph) ((ColumnFlow) columns.render(Map.of()).blocks().getFirst()).blocks().getFirst()).text());
        assertThrows(IllegalArgumentException.class, () -> new DocumentTemplate.Grid("grid", 2, 0, 0, List.of(source)));
        SourceContentParser heading = () -> (id, text, options, remaining) -> List.of(new Heading(id, text, 1, FORMAT));
        var invalid = DocumentTemplate.builder().columns("columns", 2, 12, false, List.of(),
                nodes -> nodes.node(DocumentTemplate.Source.literal("h", "Heading", heading))).build();
        assertThrows(IllegalArgumentException.class, () -> invalid.render(Map.of()));
        var empty = new TableCell("", CellFormat.of(12));
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentTemplate.CellContent(empty, List.of(new DocumentTemplate.ValueBlock(new PageBreak("p")))));
        assertFalse(Block.class.isInstance(source));
    }

    @Test void equalCellsKeepIdentityMappingsAndEmptyDynamicContentPreservesTheCell() {
        var first = new TableCell("original", CellFormat.of(12));
        var second = new TableCell("original", CellFormat.of(12));
        var table = Table.builder("table").row(first, second).build();
        var source = DocumentTemplate.Source.literal("content", "new", new RecordingParser());
        var template = DocumentTemplate.builder().block(table)
                .cellContents(List.of(new DocumentTemplate.CellContent(first, List.of(source)))).build();
        var bound = (Table) template.render(Map.of()).blocks().getFirst();
        assertEquals("new", ((Paragraph) bound.rows().getFirst().cells().getFirst().blocks().getFirst()).text());
        assertEquals("original", bound.rows().getFirst().cells().getLast().text());
        SourceContentParser empty = () -> (id, text, options, remaining) -> List.of();
        var emptyTemplate = template.toBuilder().cellContents(List.of(new DocumentTemplate.CellContent(first,
                List.of(DocumentTemplate.Source.literal("content", "", empty))))).build();
        var emptyCell = ((Table) emptyTemplate.render(Map.of()).blocks().getFirst()).rows().getFirst().cells().getFirst();
        assertFalse(emptyCell.hasBlocks()); assertEquals("", emptyCell.text());
        assertEquals(1, emptyCell.columnSpan()); assertEquals(first.format(), emptyCell.format());
    }

    @Test void equalPageRegionsDoNotSkipIdentityScopedCellContentOrItsFailures() {
        var firstCell = new TableCell("placeholder", CellFormat.of(12));
        var secondCell = new TableCell("placeholder", CellFormat.of(12));
        var firstBand = new PageRegion(30, 0, List.of(Table.builder("band-table").row(firstCell).build()));
        var secondBand = new PageRegion(30, 0, List.of(Table.builder("band-table").row(secondCell).build()));
        assertEquals(firstBand, secondBand);
        var sources = new ArrayList<String>();
        var rejectSecond = new AtomicBoolean();
        var rejection = new IllegalStateException("second source rejected");
        SourceContentParser parser = () -> (id, source, options, remaining) -> {
            sources.add(source);
            if (source.equals("second") && rejectSecond.get()) { throw rejection; }
            return List.of();
        };
        var template = DocumentTemplate.builder().sections(List.of(
                new DocumentTemplate.SectionNode("one", PageSettings.A4, List.of(), 0,
                        PageRegions.builder().header(firstBand).build()),
                new DocumentTemplate.SectionNode("two", PageSettings.A4, List.of(), 0,
                        PageRegions.builder().header(secondBand).build())))
                .cellContents(List.of(
                        new DocumentTemplate.CellContent(firstCell,
                                List.of(DocumentTemplate.Source.literal("first-content", "first", parser))),
                        new DocumentTemplate.CellContent(secondCell,
                                List.of(DocumentTemplate.Source.literal("second-content", "second", parser)))))
                .build();
        var rendered = template.renderSections(Map.of());
        assertEquals(List.of("first", "second"), sources);
        assertEquals(rendered.sections().getFirst().pageRegions().header(),
                rendered.sections().getLast().pageRegions().header());
        sources.clear();
        rejectSecond.set(true);
        assertSame(rejection, assertThrows(IllegalStateException.class, () -> template.renderSections(Map.of())));
        assertEquals(List.of("first", "second"), sources);
    }
}
