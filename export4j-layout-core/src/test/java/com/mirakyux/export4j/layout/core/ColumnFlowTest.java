// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.FontMetrics;
import com.mirakyux.export4j.font.GlyphMetrics;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.DocumentLayout;
import com.mirakyux.export4j.layout.LayoutFragment;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnFlow;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.SimpleTextShaper;
import com.mirakyux.export4j.text.TextLayoutLimits;
import com.mirakyux.export4j.text.TextShaper;

class ColumnFlowTest {
    private static final ParagraphFormat FORMAT = new ParagraphFormat(10, 10, 0, 0,
            ParagraphAlignment.LEFT, 0, 0, false);
    private static FontFace font;
    private static ImageResource image;

    @BeforeAll
    static void fonts() throws IOException {
        FontFace real;
        try (var input = ColumnFlowTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            real = TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
        font = new FontFace() {
            @Override public FontData data() { return real.data(); }
            @Override public FontMetrics metrics() { return new FontMetrics(0.7, 0.2, 0); }
            @Override public Optional<GlyphMetrics> glyph(int codePoint) { return Optional.of(new GlyphMetrics(1, 1)); }
        };
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", bytes);
        image = ImageResource.load(bytes.toByteArray());
    }

    @Test
    void balancedDoubleAndTripleColumnsResumeFullWidthOnTheSamePage() {
        var first = columns("two", 2, true, new Paragraph("a", "A\nB\nC\nD", FORMAT));
        var table = Table.builder("full-table").cellFormat(new CellFormat(10, 10, ParagraphAlignment.LEFT))
                .format(new TableFormat(PageMargins.uniform(0), 0, 0)).row("T").build();
        var figure = Paragraph.mixed("full-figure", List.of(new InlineImage(image, 140, 5, 0, "wide figure")),
                FORMAT.toBuilder().lineHeight(20).build());
        var next = columns("three", 3, true, new Paragraph("b", "E\nF\nG\nH\nI\nJ", FORMAT));
        var result = layout(140, 100, first, table, figure, next, new Paragraph("tail", "Z", FORMAT));
        assertEquals(1, result.pages().size());
        var elements = result.pages().getFirst().elements();
        var firstMarker = (ComponentFragment) elements.stream().filter(f -> f.blockId().equals("two")).findFirst().orElseThrow();
        var lastMarker = (ComponentFragment) elements.stream().filter(f -> f.blockId().equals("three")).findFirst().orElseThrow();
        var full = (TableFragment) elements.stream().filter(f -> f.blockId().equals("full-table")).findFirst().orElseThrow();
        assertEquals(20, firstMarker.layout().height());
        assertEquals(firstMarker.origin().y() + 20, full.origin().y());
        assertEquals(140, full.width());
        assertEquals(1, paragraphs(result, "full-figure").getFirst().layout().lines().getFirst().images().size());
        assertEquals(full.origin().y() + full.height() + 20, lastMarker.origin().y());
        assertEquals(20, lastMarker.layout().height());
        assertEquals(lastMarker.origin().y() + 20, elements.getLast().origin().y());
        assertEquals(List.of(0, 0, 3, 3, 3), result.pages().getFirst().fragments().stream()
                .filter(p -> p.blockId().equals("a") || p.blockId().equals("b")).map(ParagraphFragment::blockIndex).toList());
        assertContained(result);
        DocumentPainter.paint(result);
    }

    @Test
    void midPageColumnsPreserveFirstAndLastParagraphSpacing() {
        var before = new Paragraph("before", "A", FORMAT.toBuilder().spaceAfter(7).build());
        var inside = new Paragraph("inside", "B", FORMAT.toBuilder().spaceBefore(11).spaceAfter(13).build());
        var after = new Paragraph("after", "C", FORMAT.toBuilder().spaceBefore(17).build());
        var ordinary = layout(100, 100, before, inside, after);
        for (int count : List.of(1, 2)) {
            for (boolean balanced : List.of(false, true)) {
                var result = layout(100, 100, before, columns("flow", count, balanced, inside), after);
                assertEquals(1, result.pages().size());
                assertEquals(38, paragraphs(result, "inside").getFirst().origin().y());
                assertEquals(78, paragraphs(result, "after").getFirst().origin().y());
                assertEquals(paragraphs(ordinary, "inside").getFirst().origin(), paragraphs(result, "inside").getFirst().origin());
                assertEquals(paragraphs(ordinary, "after").getFirst().origin(), paragraphs(result, "after").getFirst().origin());
                assertContained(result);
            }
        }
    }

    @Test
    void midPageColumnsPreserveFirstAndLastTableSpacing() {
        var before = new Paragraph("before", "A", FORMAT.toBuilder().spaceAfter(7).build());
        var table = Table.builder("inside").cellFormat(new CellFormat(10, 10, ParagraphAlignment.LEFT))
                .format(new TableFormat(PageMargins.uniform(0), 11, 13)).row("B").build();
        var after = new Paragraph("after", "C", FORMAT.toBuilder().spaceBefore(17).build());
        for (int count : List.of(1, 2)) {
            var result = layout(100, 100, before, columns("flow", count, true, table), after);
            var fragment = (TableFragment) result.pages().getFirst().elements().stream()
                    .filter(TableFragment.class::isInstance).findFirst().orElseThrow();
            assertEquals(1, result.pages().size());
            assertEquals(38, fragment.origin().y());
            assertEquals(78, paragraphs(result, "after").getFirst().origin().y());
            assertContained(result);
        }
    }

    @Test
    void balancedColumnsResumeAfterTheHighestColumnAndFinalLogicalSpacing() {
        var inside = new Paragraph("inside", "A\nB\nC\nD\nE",
                FORMAT.toBuilder().spaceBefore(11).spaceAfter(13).build());
        var result = layout(100, 100,
                new Paragraph("before", "X", FORMAT.toBuilder().spaceAfter(7).build()),
                columns("flow", 2, true, inside),
                new Paragraph("after", "Z", FORMAT.toBuilder().spaceBefore(17).build()));
        var fragments = paragraphs(result, "inside");
        assertEquals(1, result.pages().size());
        assertEquals(List.of(0, 2), fragments.stream().map(ParagraphFragment::firstLineIndex).toList());
        assertEquals(List.of(38.0, 27.0), fragments.stream().map(p -> p.origin().y()).toList());
        assertEquals("ABCDE", logical(fragments));
        assertEquals(88, paragraphs(result, "after").getFirst().origin().y());
        assertContained(result);
    }

    @Test
    void continuedColumnsSuppressLeadingSpacingAtColumnAndPageEdges() {
        var inside = new Paragraph("inside", "A\nB\nC\nD\nE\nF",
                FORMAT.toBuilder().spaceBefore(11).spaceAfter(13).build());
        var result = layout(100, 50,
                new Paragraph("before", "X", FORMAT.toBuilder().spaceAfter(7).build()),
                columns("flow", 2, false, inside),
                new Paragraph("after", "Z", FORMAT.toBuilder().spaceBefore(17).build()));
        var fragments = paragraphs(result, "inside");
        assertEquals(2, result.pages().size());
        assertEquals(List.of(0, 2, 5), fragments.stream().map(ParagraphFragment::firstLineIndex).toList());
        assertEquals(List.of(38.0, 27.0, 10.0), fragments.stream().map(p -> p.origin().y()).toList());
        assertEquals("ABCDEF", logical(fragments));
        assertEquals(50, paragraphs(result, "after").getFirst().origin().y());
        assertEquals("after", result.pages().get(1).elements().getLast().blockId());
        assertContained(result);
    }

    @Test
    void pageEdgesSuppressContainerSpacingWithoutCreatingTrailingPages() {
        var inside = new Paragraph("inside", "A", FORMAT.toBuilder().spaceBefore(100).spaceAfter(100).build());
        for (int count : List.of(1, 2)) {
            var flow = columns("flow", count, true, inside);
            var alone = layout(100, 20, flow);
            assertEquals(1, alone.pages().size());
            assertEquals(10, paragraphs(alone, "inside").getFirst().origin().y());
            var followed = layout(100, 20, flow,
                    new Paragraph("after", "B", FORMAT.toBuilder().spaceBefore(100).build()));
            assertEquals(2, followed.pages().size());
            assertEquals(10, paragraphs(followed, "after").getFirst().origin().y());
            assertEquals("after", followed.pages().get(1).elements().getFirst().blockId());
            assertContained(followed);
        }
    }

    @Test
    void precedingSpacingCannotFollowColumnsOntoAFreshPage() {
        var before = new Paragraph("before", "X", FORMAT.toBuilder().spaceAfter(100).build());
        var paragraph = new Paragraph("inside", "A\nB\nC", FORMAT.toBuilder()
                .lineHeight(15).spaceBefore(11).keepTogether(true).build());
        var table = Table.builder("inside-table").cellFormat(new CellFormat(10, 15, ParagraphAlignment.LEFT))
                .format(new TableFormat(PageMargins.uniform(0), 11, 0)).row("A\nB\nC").build();
        for (int count : List.of(1, 2, 3)) {
            for (boolean balanced : List.of(false, true)) {
                for (Block child : List.of(paragraph, table)) {
                    var result = layout(100, 50, before, columns("flow", count, balanced, child));
                    assertEquals(2, result.pages().size());
                    assertEquals(List.of("before"), result.pages().getFirst().elements().stream().map(LayoutFragment::blockId).toList());
                    var fragment = result.pages().getLast().elements().stream()
                            .filter(f -> f.blockId().equals(child.id())).findFirst().orElseThrow();
                    assertEquals(10, fragment.origin().x());
                    assertEquals(10, fragment.origin().y());
                    assertContained(result);
                }
            }
        }
    }

    @Test
    void precedingSpacingCannotCrossAnExclusionIntoTheNextFreeBand() {
        for (double spaceAfter : List.of(15.0, 50.0)) {
            for (double spaceBefore : List.of(0.0, 11.0)) {
                for (int count : List.of(1, 2, 3)) {
                    for (boolean balanced : List.of(false, true)) {
                        var before = new Paragraph("before", "X", FORMAT.toBuilder().spaceAfter(spaceAfter).build());
                        var inside = new Paragraph("inside", "A", FORMAT.toBuilder().spaceBefore(spaceBefore).build());
                        var source = document(100, 100, before, columns("flow", count, balanced, inside));
                        var result = new DocumentLayouter().layout(source, FontGroup.strict(List.of(font)), Map.of(),
                                page -> page == 0 ? List.of(new Rect(10, 30, 100, 10)) : List.of());
                        assertEquals(1, result.pages().size());
                        assertEquals(10, paragraphs(result, "inside").getFirst().origin().x());
                        assertEquals(40, paragraphs(result, "inside").getFirst().origin().y());
                        assertEquals("A", logical(paragraphs(result, "inside")));
                        assertContained(result);
                    }
                }
            }
        }
    }

    @Test
    void headingKeepChainIncludesTheFirstColumnChildsLeadingSpacing() {
        var heading = new Paragraph("heading", "H", FORMAT.withPagination(new PaginationPolicy(true, false, 1, 1)));
        var inside = new Paragraph("inside", "A", FORMAT.toBuilder().spaceBefore(11).build());
        var result = layout(100, 40, new Paragraph("before", "X", FORMAT), heading,
                columns("flow", 1, false, inside));
        assertEquals(2, result.pages().size());
        assertEquals(List.of("before"), result.pages().getFirst().elements().stream().map(LayoutFragment::blockId).toList());
        assertEquals(10, paragraphs(result, "heading").getFirst().origin().y());
        assertEquals(31, paragraphs(result, "inside").getFirst().origin().y());
        assertContained(result);
    }

    @Test
    void headingKeepChainUsesActualColumnWidthsWhenOnlyTheLeadingColumnFits() {
        var heading = new Paragraph("heading", "H", FORMAT.withPagination(new PaginationPolicy(true, false, 1, 1)));
        var paragraph = new Paragraph("inside", "ABCDEFGHIJ", FORMAT.toBuilder().spaceBefore(11).keepTogether(true).build());
        var table = Table.builder("inside-table").cellFormat(new CellFormat(10, 10, ParagraphAlignment.LEFT))
                .format(new TableFormat(PageMargins.uniform(0), 11, 0)).row("ABCDEFGHIJ").build();
        var splitBlocks = Table.builder("inside-blocks").format(new TableFormat(PageMargins.uniform(0), 11, 0, true))
                .row(TableCell.builder().blocks(List.of(paragraph)).build()).build();
        var nestedTable = Table.builder("inside-nested").format(new TableFormat(PageMargins.uniform(0), 11, 0, true))
                .row(TableCell.builder().blocks(List.of(table)).build()).build();
        for (int count : List.of(2, 3)) {
            for (boolean balanced : List.of(false, true)) {
                for (Block child : List.of(paragraph, table, splitBlocks, nestedTable)) {
                    var columns = new ColumnFlow("flow", List.of(child), count, count == 2 ? 10 : 0, balanced,
                            count == 2 ? List.of(2.0, 1.0) : List.of(3.0, 1.0, 1.0));
                    var result = layout(100, 50, new Paragraph("before", "X", FORMAT), heading, columns);
                    assertEquals(2, result.pages().size());
                    assertEquals(List.of("before"), result.pages().getFirst().elements().stream().map(LayoutFragment::blockId).toList());
                    assertEquals(10, paragraphs(result, "heading").getFirst().origin().y());
                    var fragment = result.pages().getLast().elements().stream()
                            .filter(f -> f.blockId().equals(child.id())).findFirst().orElseThrow();
                    assertEquals(10, fragment.origin().x());
                    assertEquals(31, fragment.origin().y());
                    assertContained(result);
                }
            }
        }
    }

    @Test
    void headingKeepChainCanStartTheChildInALaterColumnWithoutLeadingSpacing() {
        var heading = new Paragraph("heading", "H", FORMAT.withPagination(new PaginationPolicy(true, false, 1, 1)));
        var inside = new Paragraph("inside", "A", FORMAT.toBuilder().spaceBefore(100).build());
        for (int count : List.of(2, 3)) {
            var result = layout(100, 20, heading, columns("flow", count, true, inside));
            assertEquals(1, result.pages().size());
            assertEquals(10, paragraphs(result, "heading").getFirst().origin().y());
            assertEquals(20, paragraphs(result, "inside").getFirst().origin().y());
            assertTrue(paragraphs(result, "inside").getFirst().origin().x() > 10);
            assertContained(result);
        }
    }

    @Test
    void headingKeepChainMovesPastAnExclusionTogetherWithItsColumnChild() {
        var heading = new Paragraph("heading", "H", FORMAT.withPagination(new PaginationPolicy(true, false, 1, 1)));
        var inside = new Paragraph("inside", "A", FORMAT.toBuilder().spaceBefore(11).build());
        var source = document(100, 100, new Paragraph("before", "X", FORMAT), heading,
                columns("flow", 1, true, inside));
        var result = new DocumentLayouter().layout(source, FontGroup.strict(List.of(font)), Map.of(),
                page -> page == 0 ? List.of(new Rect(10, 30, 100, 10)) : List.of());
        assertEquals(1, result.pages().size());
        assertEquals(40, paragraphs(result, "heading").getFirst().origin().y());
        assertEquals(61, paragraphs(result, "inside").getFirst().origin().y());
        assertContained(result);
    }

    @Test
    void headingKeepChainProbesPreserveTheOutputBudgetAndAtomicImageSource() {
        var heading = new Paragraph("heading", "H", FORMAT.withPagination(new PaginationPolicy(true, false, 1, 1)));
        var inside = Paragraph.mixed("inside", List.of(new InlineImage(image, 50, 5, 0, "figure")),
                FORMAT.toBuilder().lineHeight(20).spaceBefore(11).build());
        var source = document(100, 50, new Paragraph("before", "X", FORMAT), heading,
                new ColumnFlow("flow", List.of(inside), 2, 10, true, List.of(1.0, 2.0)));
        var limits = new DocumentLayoutLimits(4, 3, 3, 1);
        var result = new DocumentLayouter(new ParagraphLayouter(), limits).layout(source, List.of(font));
        assertEquals(1, result.pages().size());
        var fragment = paragraphs(result, "inside").getFirst();
        assertEquals(50, fragment.origin().x());
        assertEquals(30, fragment.origin().y());
        assertEquals(0, fragment.layout().lines().getFirst().sourceStart());
        assertEquals(1, fragment.layout().lines().getFirst().sourceEnd());
        assertEquals(1, fragment.layout().lines().getFirst().images().size());
        assertContained(result);
    }

    @Test
    void threeColumnsUseRemainingHeightThenContinueWithoutDuplicateSource() {
        String text = IntStream.range(0, 15).mapToObj(Integer::toString).collect(Collectors.joining("\n"));
        var result = layout(140, 40, new Paragraph("before", "X\nY", FORMAT),
                columns("flow", 3, false, new Paragraph("source", text, FORMAT)));
        assertEquals(2, result.pages().size());
        var fragments = paragraphs(result, "source");
        assertEquals(List.of(2, 2, 2, 4, 4, 1), fragments.stream().map(p -> p.layout().lines().size()).toList());
        assertEquals(List.of(0, 2, 4, 6, 10, 14), fragments.stream().map(ParagraphFragment::firstLineIndex).toList());
        assertEquals(text.replace("\n", ""), logical(fragments));
        assertContained(result);
    }

    @Test
    void unequalWidthsKeepOriginalUtf16RangesAndIndentationAcrossColumns() {
        String text = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcd";
        var format = FORMAT.toBuilder().firstLineIndent(10).build();
        var flow = new ColumnFlow("unequal", List.of(new Paragraph("p", text, format)), 2, 10, false, List.of(1.0, 2.0));
        var result = layout(100, 20, flow);
        var fragments = paragraphs(result, "p");
        assertEquals(List.of(30.0, 60.0, 30.0, 60.0), fragments.stream().map(p -> p.layout().width()).toList());
        assertEquals(List.of(0, 5, 17, 23), fragments.stream().map(p -> p.layout().lines().getFirst().sourceStart()).toList());
        assertEquals(10, fragments.getFirst().layout().lines().getFirst().offsetX());
        assertTrue(fragments.stream().skip(1).allMatch(p -> p.layout().lines().getFirst().offsetX() == 0));
        assertEquals(text, logical(fragments));
        int expected = 0;
        for (var line : fragments.stream().flatMap(p -> p.layout().lines().stream()).toList()) {
            assertEquals(expected, line.sourceStart()); expected = line.sourceEnd();
        }
        assertEquals(text.length(), expected);
        assertContained(result);
    }

    @Test
    void tableHeadersRepeatAcrossColumnsAndBodyRowsOccurOnce() {
        var builder = Table.builder("table").cellFormat(new CellFormat(10, 10, ParagraphAlignment.LEFT))
                .format(new TableFormat(PageMargins.uniform(0), 0, 0)).header("H");
        IntStream.range(0, 7).forEach(i -> builder.row(Integer.toString(i)));
        var result = layout(100, 30, columns("flow", 2, false, builder.build()));
        var tables = result.pages().stream().flatMap(p -> p.elements().stream()).filter(TableFragment.class::isInstance)
                .map(TableFragment.class::cast).toList();
        assertEquals(4, tables.size());
        assertEquals(List.of(false, true, true, true), tables.stream().map(t -> t.rows().getFirst().repeatedHeader()).toList());
        assertEquals(IntStream.rangeClosed(1, 7).boxed().toList(), tables.stream().flatMap(t -> t.rows().stream())
                .filter(row -> !row.header()).map(row -> row.rowIndex()).toList());
        assertContained(result);
    }

    @Test
    void fixedWidthTableSkipsAnImpossibleNarrowColumnBeforeMeasuringIt() {
        var table = Table.builder("fixed").columns(new ColumnWidth.Fixed(50))
                .cellFormat(new CellFormat(10, 10, ParagraphAlignment.LEFT))
                .format(new TableFormat(PageMargins.uniform(0), 0, 0)).row("T").build();
        var source = new ColumnFlow("flow", List.of(table, new Paragraph("after", "A", FORMAT)),
                2, 10, false, List.of(1.0, 2.0));
        var result = layout(100, 30, source);
        assertEquals(1, result.pages().size());
        var fragment = (TableFragment) result.pages().getFirst().elements().stream()
                .filter(TableFragment.class::isInstance).findFirst().orElseThrow();
        assertEquals(50, fragment.origin().x());
        assertEquals(50, fragment.width());
        assertEquals(1, fragment.rows().size());
        assertEquals(50, paragraphs(result, "after").getFirst().origin().x());
        assertEquals(fragment.origin().y() + fragment.height(), paragraphs(result, "after").getFirst().origin().y());
        assertContained(result);
    }

    @Test
    void atomicInlineImageSkipsNarrowColumnWithoutLosingItsOriginalPosition() {
        var paragraph = Paragraph.mixed("p", List.of(new InlineImage(image, 50, 5, 0, "figure")),
                FORMAT.toBuilder().lineHeight(20).build());
        var source = new ColumnFlow("unequal", List.of(paragraph), 2, 10, true, List.of(1.0, 2.0));
        var heading = new Paragraph("heading", "H", FORMAT.withPagination(
                new com.mirakyux.export4j.model.PaginationPolicy(true, false, 1, 1)));
        var result = layout(100, 30, heading, source);
        assertEquals(1, result.pages().size());
        var fragment = paragraphs(result, "p").getFirst();
        assertEquals(50, fragment.origin().x());
        assertEquals(20, fragment.origin().y());
        assertEquals(0, fragment.layout().lines().getFirst().sourceStart());
        assertEquals(1, fragment.layout().lines().getFirst().sourceEnd());
        assertEquals(1, fragment.layout().lines().getFirst().images().size());
        DocumentPainter.paint(result);
    }

    @Test
    void deferredAtomicImageMustFitTheColumnAfterItsFirstLineIndent() {
        var format = FORMAT.toBuilder().lineHeight(20).firstLineIndent(10).build();
        var paragraph = Paragraph.mixed("p", List.of(new InlineImage(image, 30, 5, 0, "indented figure")), format);
        var source = new ColumnFlow("unequal", List.of(paragraph), 2, 10, false, List.of(1.0, 2.0));
        var result = layout(100, 30, source);
        var fragments = paragraphs(result, "p");
        assertEquals(1, fragments.size());
        var fragment = fragments.getFirst();
        assertEquals(50, fragment.origin().x());
        var line = fragment.layout().lines().getFirst();
        assertEquals(10, line.offsetX());
        assertEquals(30, line.width());
        assertEquals(0, line.sourceStart());
        assertEquals(1, line.sourceEnd());
        assertTrue(line.offsetX() + line.width() <= fragment.layout().width());
    }

    @Test
    void shortRemainingBandMovesToTheNextPageAndResumesFullWidth() {
        var result = layout(100, 35, new Paragraph("before", "A\nB\nC", FORMAT),
                columns("two", 2, true, new Paragraph("inside", "D\nE\nF\nG", FORMAT)),
                new Paragraph("after", "H", FORMAT));
        assertEquals(2, result.pages().size());
        assertEquals(List.of("before"), result.pages().getFirst().elements().stream().map(LayoutFragment::blockId).toList());
        assertEquals(30, result.pages().get(1).elements().getLast().origin().y());
        assertContained(result);
    }

    @Test
    void physicalExclusionsAreAppliedToEveryColumnWithoutConsumingSource() {
        var source = document(100, 60, columns("two", 2, false, new Paragraph("p", "A\nB\nC\nD\nE\nF", FORMAT)));
        var result = new DocumentLayouter().layout(source, FontGroup.strict(List.of(font)), Map.of(),
                page -> page == 0 ? List.of(new Rect(10, 30, 100, 10)) : List.of());
        assertEquals(1, result.pages().size());
        assertEquals("ABCDEF", logical(paragraphs(result, "p")));
        for (var paragraph : paragraphs(result, "p")) {
            assertTrue(paragraph.origin().y() + paragraph.layout().height() <= 30 || paragraph.origin().y() >= 40);
        }
        assertContained(result);
    }

    @Test
    void sharedBudgetsIncludeColumnDescendantsAndOutputOnlyChargesSelectedTrials() {
        var source = document(100, 100, columns("flow", 2, true, new Paragraph("p", "A\nB\nC\nD", FORMAT)));
        var limits = new DocumentLayoutLimits(2, 7, 4, 1);
        var result = new DocumentLayouter(new ParagraphLayouter(), limits).layout(source, List.of(font));
        assertEquals(4, paragraphs(result, "p").stream().mapToInt(p -> p.layout().lines().size()).sum());
        var count = assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter(new ParagraphLayouter(),
                new DocumentLayoutLimits(1, 7, 4, 1)).layout(source, List.of(font)));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, count.code());
        var lines = assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter(new ParagraphLayouter(),
                new DocumentLayoutLimits(2, 7, 3, 1)).layout(source, List.of(font)));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, lines.code());
        var tooTall = new Paragraph("tall", "A\nB\nC", FORMAT.toBuilder().keepTogether(true).build());
        assertEquals(DocumentLayoutException.Code.CONTENT_TOO_TALL, assertThrows(DocumentLayoutException.class,
                () -> layout(100, 20, columns("flow", 2, true, tooTall))).code());
    }

    @Test
    void unbalancedTrialDoesNotRejectAnOtherwiseValidBalancedOutputLineBudget() {
        var source = document(110, 1000, new ColumnFlow("flow", List.of(new Paragraph("p", "A".repeat(100), FORMAT)),
                2, 0, true, List.of(1.0, 10.0)));
        var limits = new DocumentLayoutLimits(2, 100, 20, 1);
        var result = new DocumentLayouter(new ParagraphLayouter(), limits).layout(source, List.of(font));
        var fragments = paragraphs(result, "p");
        assertEquals(List.of(10, 9), fragments.stream().map(p -> p.layout().lines().size()).toList());
        assertEquals("A".repeat(100), logical(fragments));
        assertContained(result);
    }

    @Test
    void cancellationDuringShapingLeavesTheReusableLayouterAndScopeIntact() {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var simple = new SimpleTextShaper();
        TextShaper controlled = (text, fonts, size, sourceIndex) -> {
            var result = simple.shape(text, fonts, size, sourceIndex);
            cancelled.set(true);
            return result;
        };
        var layouter = new DocumentLayouter(new ParagraphLayouter(controlled, TextLayoutLimits.DEFAULT), DocumentLayoutLimits.DEFAULT);
        var source = document(100, 40, columns("flow", 2, true, new Paragraph("p", "A\nB\nC\nD", FORMAT)));
        var options = ExecutionOptions.builder().cancelled(cancelled::get).build();
        var failure = assertThrows(ExecutionException.class,
                () -> ExecutionContext.execute(options, () -> layouter.layout(source, List.of(font))));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        ExecutionContext.checkpoint();
        assertEquals(1, layouter.layout(source, List.of(font)).pages().size());
    }

    private static ColumnFlow columns(String id, int count, boolean balanced, Block child) {
        return new ColumnFlow(id, List.of(child), count, 10, balanced);
    }
    private static Document document(double width, double height, Block... blocks) {
        return new Document(DocumentMetadata.EMPTY, new PageSettings(new PageSize(width + 20, height + 20),
                PageMargins.uniform(10)), List.of(blocks));
    }
    private static DocumentLayout layout(double width, double height, Block... blocks) {
        return new DocumentLayouter().layout(document(width, height, blocks), List.of(font));
    }
    private static List<ParagraphFragment> paragraphs(DocumentLayout result, String id) {
        return result.pages().stream().flatMap(p -> p.fragments().stream()).filter(p -> p.blockId().equals(id)).toList();
    }
    private static String logical(List<ParagraphFragment> fragments) {
        return fragments.stream().flatMap(p -> p.layout().lines().stream()).flatMap(l -> l.runs().stream())
                .map(run -> run.text()).collect(Collectors.joining());
    }
    private static void assertContained(DocumentLayout result) {
        for (var page : result.pages()) {
            var bounds = page.contentBounds();
            for (var fragment : page.elements()) {
                double width, height;
                if (fragment instanceof ParagraphFragment p) { width = p.layout().width(); height = p.layout().height(); }
                else if (fragment instanceof TableFragment t) { width = t.width(); height = t.height(); }
                else { var c = (ComponentFragment) fragment; width = c.layout().width(); height = c.layout().height(); }
                assertTrue(fragment.origin().x() >= bounds.x());
                assertTrue(fragment.origin().y() >= bounds.y());
                assertTrue(fragment.origin().x() + width <= bounds.x() + bounds.width() + 1e-9);
                assertTrue(fragment.origin().y() + height <= bounds.y() + bounds.height() + 1e-9);
            }
        }
    }
}
