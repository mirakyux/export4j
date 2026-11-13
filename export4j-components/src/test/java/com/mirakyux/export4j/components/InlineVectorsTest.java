// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.*;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.model.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class InlineVectorsTest {
    private static final PageSettings PAGE = new PageSettings(new PageSize(200, 150), PageMargins.uniform(10));
    private static final ParagraphFormat FORMAT = new ParagraphFormat(12, 22, 0, 0, ParagraphAlignment.LEFT, 0, 0, false);

    private static FontGroup fonts() throws IOException {
        try (var input = InlineVectorsTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            return FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    private static InlineVector vector(double width) {
        var segments = List.of(new VectorResource.Segment(VectorResource.Operation.MOVE, List.of(0.0, -2.0)),
                new VectorResource.Segment(VectorResource.Operation.LINE, List.of(width, -2.0)),
                new VectorResource.Segment(VectorResource.Operation.LINE, List.of(width, -1.0)),
                new VectorResource.Segment(VectorResource.Operation.LINE, List.of(0.0, -1.0)),
                new VectorResource.Segment(VectorResource.Operation.CLOSE, List.of()));
        return new InlineVector(VectorResource.of(width, 12, 3, segments), "\\frac{a}{b}", 0x203040, "https://example.com/formula");
    }

    @Test void semanticParagraphPreservesVectorInkAndLinksAfterItsOneSourceMarker() throws IOException {
        var source = Paragraph.mixed("p", List.of(new InlineText("Before ", InlineStyle.of(12)), vector(24),
                new InlineText(" After", InlineStyle.of(12), "https://example.com/after")), FORMAT);
        var result = DocumentEngine.builder(fonts()).build().layout(new Document(DocumentMetadata.EMPTY, PAGE, List.of(source)));
        var line = ((ParagraphFragment) result.body().pages().getFirst().elements().getFirst()).layout().lines().getFirst();
        assertEquals(1, line.outlines().stream().filter(value -> value.logicalText().equals("\\frac{a}{b}"))
                .findFirst().orElseThrow().sourceLength());
        var commands = result.display().pages().getFirst().commands();
        assertTrue(commands.stream().anyMatch(DrawCommand.FillPath.class::isInstance));
        assertFalse(commands.stream().anyMatch(DrawCommand.Image.class::isInstance));
        assertTrue(result.display().extras().externalLinks().stream()
                .anyMatch(link -> link.destination().uri().equals("https://example.com/after")));
        var formula = result.display().extras().externalLinks().stream()
                .filter(link -> link.destination().uri().equals("https://example.com/formula")).findFirst().orElseThrow();
        assertEquals(24, formula.bounds().width(), .001);
        assertTrue(result.display().extras().externalLinks().stream()
                .filter(link -> link.destination().uri().equals("https://example.com/after"))
                .allMatch(link -> link.bounds().x() >= formula.bounds().x() + formula.bounds().width()));
    }

    @Test void automaticColumnsRespectAtomicVectorWidthAndLogicalTextBudget() throws IOException {
        var cell = TableCell.mixed(List.of(vector(70)), new CellFormat(12, 22, ParagraphAlignment.LEFT));
        var table = new Table("t", List.of(new ColumnWidth.Auto(1, 160)), List.of(new TableRow(List.of(cell))), 0,
                new TableFormat(PageMargins.uniform(2), 0, 0));
        var engine = DocumentEngine.builder(fonts()).build();
        var result = engine.layout(new Document(DocumentMetadata.EMPTY, PAGE, List.of(table)));
        var fragment = (TableFragment) result.body().pages().getFirst().elements().getFirst();
        assertTrue(fragment.rows().getFirst().cells().getFirst().bounds().width() >= 74);
        var bounded = DocumentEngine.builder(fonts()).layoutLimits(value -> value.maxCodePoints(4)).build();
        var failure = assertThrows(DocumentLayoutException.class,
                () -> bounded.layout(new Document(DocumentMetadata.EMPTY, PAGE, List.of(table))));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
    }

    @Test void replayProofsIncludeVectorGeometryEvenWhenMarkerAndLogicalTextAreIdentical() throws IOException {
        var a = TableCell.mixed(List.of(vector(24)), CellFormat.of(12));
        var b = TableCell.mixed(List.of(vector(25)), CellFormat.of(12));
        assertEquals(a.text(), b.text());
        var first = new StreamingSourceDigest(); first.add(new TableRow(List.of(a)));
        var second = new StreamingSourceDigest(); second.add(new TableRow(List.of(b)));
        assertFalse(Arrays.equals(first.finish(), second.finish()));
        var p = new PagedReplayDigest.Source(); p.add(Paragraph.mixed("p", List.of(vector(24)), FORMAT));
        var q = new PagedReplayDigest.Source(); q.add(Paragraph.mixed("p", List.of(vector(25)), FORMAT));
        assertFalse(Arrays.equals(p.finish(), q.finish()));
    }

    @Test void repeatedSharedGeometryFailsBeforeExpandingBeyondTheShapingBudget() throws IOException {
        var resource = VectorResource.of(1, 1, 0, java.util.Collections.nCopies(10_000,
                new VectorResource.Segment(VectorResource.Operation.MOVE, List.of(0.0, 0.0))));
        var paragraph = Paragraph.mixed("bounded", java.util.Collections.nCopies(201, new InlineVector(resource, "x")), FORMAT);
        var engine = DocumentEngine.builder(fonts()).build();
        var failure = assertThrows(DocumentLayoutException.class,
                () -> engine.layout(new Document(DocumentMetadata.EMPTY, PAGE, List.of(paragraph))));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals(200, failure.sourceIndex());
    }

    @Test void streamingCountsVectorReadingTextAndPreservesEveryLinkedPlacement() throws IOException {
        var value = vector(24);
        var row = new TableRow(List.of(TableCell.mixed(List.of(value), new CellFormat(12, 22, ParagraphAlignment.LEFT))));
        var table = StreamingTable.builder("stream").columns(180).pageSettings(PAGE).padding(0).build();
        var pages = new java.util.ArrayList<com.mirakyux.export4j.graphics.DisplayPage>();
        var links = new java.util.ArrayList<com.mirakyux.export4j.graphics.DocumentExtras.ExternalLink>();
        TableRowSource.Factory rows = () -> new TableRowSource() {
            int index;
            @Override public java.util.Optional<TableRow> next() {
                return index++ < 10 ? java.util.Optional.of(row) : java.util.Optional.empty();
            }
            @Override public void close() { }
        };
        var engine = DocumentEngine.builder(fonts()).build();
        var summary = engine.export(table, rows).backend((metadata, output) -> new com.mirakyux.export4j.graphics.PageWriter() {
            @Override public void accept(com.mirakyux.export4j.graphics.DisplayPage page) { pages.add(page); }
            @Override public void accept(com.mirakyux.export4j.graphics.DisplayPage page,
                    List<com.mirakyux.export4j.graphics.DocumentExtras.ExternalLink> interactions) {
                pages.add(page); links.addAll(interactions);
            }
            @Override public void finish() { }
            @Override public void close() { }
        }).to(new java.io.ByteArrayOutputStream());
        assertTrue(summary.pages() > 1);
        assertEquals(10L * (1 + value.logicalText().codePointCount(0, value.logicalText().length())), summary.sourceCodePoints());
        assertEquals(10, links.size());
        assertEquals(10, pages.stream().flatMap(page -> page.commands().stream())
                .filter(DrawCommand.FillPath.class::isInstance).count());
        var failure = assertThrows(DocumentLayoutException.class, () -> engine.export(table, rows)
                .limits(limits -> limits.maxCellCodePoints(4)).backend((metadata, output) -> new com.mirakyux.export4j.graphics.PageWriter() {
                    @Override public void accept(com.mirakyux.export4j.graphics.DisplayPage page) { fail("over-budget vector was emitted"); }
                    @Override public void finish() { }
                    @Override public void close() { }
                }).to(new java.io.ByteArrayOutputStream()));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
    }

    @Test void semanticAndReplayStreamsShareTheLogicalTextBudgetAcrossSourceGroups() throws IOException {
        var row = new TableRow(List.of(TableCell.mixed(List.of(vector(24)), new CellFormat(12, 22, ParagraphAlignment.LEFT))));
        var table = StreamingTable.builder("stream").columns(180).pageSettings(PAGE).padding(0).semanticCells(true).build();
        TableRowSource.Factory rows = () -> new TableRowSource() {
            int index;
            @Override public java.util.Optional<TableRow> next() {
                return index++ < 2 ? java.util.Optional.of(row) : java.util.Optional.empty();
            }
            @Override public void close() { }
        };
        var engine = DocumentEngine.builder(fonts()).build();
        var limits = com.mirakyux.export4j.layout.core.StreamingTableLimits.DEFAULT.toBuilder()
                .document(com.mirakyux.export4j.layout.core.DocumentLayoutLimits.DEFAULT.toBuilder().maxCodePoints(15).build()).build();
        for (boolean replay : List.of(false, true)) {
            var request = replay ? engine.exportReplay(table, rows) : engine.exportSemantic(table, rows);
            var failure = assertThrows(DocumentLayoutException.class, () -> request.limits(limits)
                    .backend((metadata, output) -> new com.mirakyux.export4j.graphics.PageWriter() {
                        @Override public void accept(com.mirakyux.export4j.graphics.DisplayPage page) { }
                        @Override public void finish() { fail("over-budget source stream completed"); }
                        @Override public void close() { }
                    }).to(new java.io.ByteArrayOutputStream()));
            assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
            assertEquals(1, failure.rowIndex());
        }
    }
}
