// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageOverlay;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;
import com.mirakyux.export4j.text.TextLayoutException;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.model.PaginationPolicy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FixedBodyAvoidanceTest {
    private static FontGroup fonts;
    private static final PageSettings PAGE = PageSettings.builder().size(300, 240).margins(20).build();
    private static final ParagraphFormat FORMAT = ParagraphFormat.builder().fontSize(10).lineHeight(15).spaceAfter(0).build();

    @BeforeAll static void fonts() throws IOException {
        try (var input = FixedBodyAvoidanceTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    private static Paragraph lines(String id, int count) {
        return new Paragraph(id, IntStream.range(0, count).mapToObj(n -> "Line " + n).collect(java.util.stream.Collectors.joining("\n")), FORMAT);
    }
    private static PageOverlay overlay(int page, PageOverlay.BodyFlow flow, PageOverlay.SizeMode heightMode) {
        return new PageOverlay(20, 65, 100, new PageRegion(40, 0, List.of(new Paragraph("fixed", "Fixed", FORMAT))),
                page, PageOverlay.SizeMode.FIXED, heightMode, flow);
    }
    private static ComponentDocumentLayout render(List<Block> blocks, PageOverlay overlay) {
        return new ComponentDocumentRenderer().layout(new Document(DocumentMetadata.EMPTY, PAGE, blocks),
                PageRegionTemplates.from(PageRegions.builder().foreground(overlay).build()), Navigation.EMPTY, fonts);
    }

    @Test void overlapKeepsBodyGeometryWhileAvoidanceRetainsAboveAndResumesBelowOnSamePage() {
        var overlap = render(List.of(lines("body", 8)), overlay(1, PageOverlay.BodyFlow.OVERLAP, PageOverlay.SizeMode.FIXED));
        assertEquals(1, overlap.body().pages().getFirst().elements().size());
        var avoided = render(List.of(lines("body", 8)), overlay(1, PageOverlay.BodyFlow.AVOID, PageOverlay.SizeMode.FIXED));
        var fragments = avoided.body().pages().getFirst().elements().stream().map(ParagraphFragment.class::cast).toList();
        assertEquals(List.of(20.0, 105.0), fragments.stream().map(part -> part.origin().y()).toList());
        assertEquals(List.of(0, 3), fragments.stream().map(ParagraphFragment::firstLineIndex).toList());
        assertEquals(8, fragments.stream().mapToInt(part -> part.layout().lines().size()).sum());
        assertEquals(1, avoided.display().pages().size());
        assertEquals(65, avoided.decorations().getFirst().viewport().y());
    }

    @Test void automaticHeightUsesMeasuredContentAndOnlySelectedPhysicalPageIsExcluded() {
        var result = render(List.of(lines("body", 25)), overlay(2, PageOverlay.BodyFlow.AVOID, PageOverlay.SizeMode.AUTO));
        assertEquals(2, result.body().pages().size());
        assertEquals(1, result.body().pages().getFirst().elements().size());
        var second = result.body().pages().get(1).elements().stream().map(ParagraphFragment.class::cast).toList();
        assertEquals(List.of(20.0, 80.0), second.stream().map(part -> part.origin().y()).toList());
        assertEquals(25, result.body().pages().stream().flatMap(p -> p.elements().stream()).map(ParagraphFragment.class::cast)
                .mapToInt(p -> p.layout().lines().size()).sum());
        assertEquals(1, result.decorations().size());
    }

    @Test void tablesContinueAroundObstacleWithoutHeaderOnlyFragmentsOrRepeatingSourceRows() {
        var builder = Table.builder("body").columns(new ColumnWidth.Fraction(1)).cellFormat(CellFormat.of(10))
                .format(new TableFormat(PageMargins.uniform(0), 0, 0)).header("Header");
        IntStream.range(0, 9).forEach(row -> builder.row("Row " + row));
        var result = render(List.of(builder.build()), overlay(1, PageOverlay.BodyFlow.AVOID, PageOverlay.SizeMode.FIXED));
        var rows = new ArrayList<Integer>();
        for (var page : result.body().pages()) {
            for (var element : page.elements()) {
                var table = (TableFragment) element;
                assertTrue(table.rows().size() >= 2);
                if (page.pageIndex() == 0) {
                    assertTrue(table.origin().y() + table.height() <= 65 || table.origin().y() >= 105);
                }
                table.rows().stream().filter(row -> !row.header()).forEach(row -> rows.add(row.rowIndex()));
            }
        }
        assertEquals(IntStream.rangeClosed(1, 9).boxed().toList(), rows);
    }

    @Test void selectedSectionVariantAndPhysicalPageAreResolvedBeforeBodyFlow() {
        var first = PageRegionTemplates.from(PageRegions.EMPTY);
        var other = PageRegionTemplates.from(PageRegions.builder().foreground(overlay(0, PageOverlay.BodyFlow.AVOID, PageOverlay.SizeMode.FIXED)).build());
        var document = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("first", PAGE, List.of(lines("body", 25)), 1)));
        var result = new ComponentDocumentRenderer().layout(document, List.of(new SectionTemplate(first, other, other)),
                Navigation.EMPTY, fonts, Map.of(), List.of());
        assertEquals(1, result.body().pages().getFirst().elements().size());
        assertEquals(2, result.body().pages().get(1).elements().size());
    }

    @Test void overlappingObstaclesMergeAndImpossibleRepeatingAvoidanceStopsAtPageBudget() {
        var document = new Document(DocumentMetadata.EMPTY, PAGE, List.of(lines("body", 8)));
        var layout = new DocumentLayouter().layout(document, fonts, Map.of(), page -> List.of(
                new Rect(20, 65, 20, 30), new Rect(40, 80, 20, 25)));
        assertEquals(List.of(20.0, 105.0), layout.pages().getFirst().elements().stream().map(p -> p.origin().y()).toList());
        var limited = new DocumentLayouter(new ParagraphLayouter(), new DocumentLayoutLimits(10, 1000, 1000, 2), ComponentLimits.DEFAULT);
        var failure = assertThrows(DocumentLayoutException.class, () -> limited.layout(document, fonts, Map.of(),
                page -> List.of(new Rect(20, 20, 260, 200))));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
    }

    @Test void footnotesMoveAboveBottomObstacleAndTheirBandRemainsOutsideBody() {
        var box = new PageOverlay(20, 195, 100, new PageRegion(25, 0, List.of(new Paragraph("fixed", "Fixed", FORMAT))),
                1, PageOverlay.SizeMode.FIXED, PageOverlay.SizeMode.FIXED, PageOverlay.BodyFlow.AVOID);
        var template = PageRegionTemplates.sectionFrom(PageRegions.builder().foreground(box).build());
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", PAGE, List.of(lines("body", 5)), 1)));
        var result = new ComponentDocumentRenderer().layout(source, List.of(template), Navigation.EMPTY, fonts, Map.of(),
                List.of(new Note("note", "body", "A footnote", Note.Kind.FOOTNOTE)));
        var bounds = result.body().pages().getFirst().contentBounds();
        var glyph = result.footnotes().getFirst().commands().stream()
                .filter(com.mirakyux.export4j.graphics.DrawCommand.GlyphText.class::isInstance)
                .map(com.mirakyux.export4j.graphics.DrawCommand.GlyphText.class::cast).findFirst().orElseThrow();
        assertTrue(glyph.baseline().y() < 195);
        assertTrue(glyph.baseline().y() > bounds.y() + bounds.height());
        assertEquals(1, result.display().pages().size());
    }

    @Test void nativeFlowTextAdaptsEveryFreeRegionAndKeepsItsFullSource() {
        var flow = new FlowText(lines("text", 30).text(), new ParagraphStyle(10, 15, 0, 0, TextAlignment.LEFT),
                RgbColor.BLACK, PaginationPolicy.DEFAULT);
        var result = render(List.of(new ComponentBlock("body", flow)), overlay(0, PageOverlay.BodyFlow.AVOID, PageOverlay.SizeMode.FIXED));
        assertEquals(3, result.body().pages().size());
        int lines = 0;
        for (var page : result.body().pages()) {
            assertEquals(List.of(20.0, 105.0), page.elements().stream().map(p -> p.origin().y()).toList());
            for (var part : page.elements()) {
                var component = (ComponentFragment) part;
                assertTrue(component.origin().y() + component.layout().height() <= 65 || component.origin().y() >= 105);
                lines += component.layout().textLines();
            }
        }
        assertEquals(30, lines);
    }

    @Test void nestedFlowExpandsActualGapPlanBeyondSixteenRegionsWithoutLosingOrRepeatingText() {
        var style = new ParagraphStyle(10, 15, 0, 0, TextAlignment.LEFT);
        var flow = new FlowColumn(List.of(
                new FlowText(lines("a", 60).text(), style, RgbColor.BLACK, PaginationPolicy.DEFAULT),
                new FlowText(lines("b", 60).text(), style, RgbColor.BLACK, PaginationPolicy.DEFAULT)),
                PageMargins.uniform(0), 0, FlowColumn.Alignment.START);
        var result = render(List.of(new ComponentBlock("body", flow)), overlay(0, PageOverlay.BodyFlow.AVOID, PageOverlay.SizeMode.FIXED));
        var parts = result.body().pages().stream().flatMap(page -> page.elements().stream()).map(ComponentFragment.class::cast).toList();
        assertEquals(120, parts.stream().mapToInt(part -> part.layout().textLines()).sum());
        assertTrue(parts.size() > 16);
        assertTrue(parts.stream().allMatch(part -> part.origin().y() + part.layout().height() <= 65 || part.origin().y() >= 105));
        var onePage = new ComponentDocumentRenderer(new DocumentLayoutLimits(10, 1000, 1000, 1), ComponentLimits.DEFAULT);
        var shortFlow = new ComponentBlock("body", new FlowText(lines("text", 8).text(), style, RgbColor.BLACK, PaginationPolicy.DEFAULT));
        var shortResult = onePage.layout(new Document(DocumentMetadata.EMPTY, PAGE, List.of(shortFlow)),
                PageRegionTemplates.from(PageRegions.builder().foreground(overlay(1, PageOverlay.BodyFlow.AVOID, PageOverlay.SizeMode.FIXED)).build()),
                Navigation.EMPTY, fonts);
        assertEquals(1, shortResult.body().pages().size());
        assertEquals(2, shortResult.body().pages().getFirst().elements().size());
    }

    @Test void semanticTableComponentsRetainDeferredSourceAcrossRepeatedAvoidanceRegions() {
        var builder = Table.builder("source").columns(new ColumnWidth.Fraction(1)).cellFormat(CellFormat.of(10))
                .format(new TableFormat(PageMargins.uniform(0), 0, 0)).header("Header");
        IntStream.range(0, 35).forEach(row -> builder.row("Row " + row));
        var result = render(List.of(new ComponentBlock("body", new SemanticTable(builder.build()))),
                overlay(0, PageOverlay.BodyFlow.AVOID, PageOverlay.SizeMode.FIXED));
        var originalRows = new ArrayList<Integer>();
        for (var page : result.body().pages()) {
            for (var element : page.elements()) {
                var component = (ComponentFragment) element;
                assertTrue(component.origin().y() + component.layout().height() <= 65 || component.origin().y() >= 105);
                for (var placement : component.layout().semanticPlacements()) {
                    placement.fragment().rows().stream().filter(row -> !row.header()).forEach(row -> originalRows.add(row.rowIndex()));
                }
            }
        }
        assertEquals(IntStream.rangeClosed(1, 35).boxed().toList(), originalRows);
    }

    @Test void fixedBoxOverflowIdentifiesItsContentAndHeightForBothBodyFlowPolicies() {
        for (var flow : PageOverlay.BodyFlow.values()) {
            var box = new PageOverlay(20, 65, 100, new PageRegion(24, 0, List.of(lines("fixed", 2))),
                    1, PageOverlay.SizeMode.FIXED, PageOverlay.SizeMode.FIXED, flow);
            var failure = assertThrows(DocumentLayoutException.class, () -> render(List.of(lines("body", 1)), box));
            assertEquals(DocumentLayoutException.Code.CONTENT_TOO_TALL, failure.code());
            assertEquals("fixed", failure.blockId());
            assertEquals(0, failure.pageIndex());
            assertTrue(failure.getMessage().contains("24.0pt"));
            assertTrue(failure.getMessage().contains("increase the region height"));
            assertTrue(failure.getMessage().contains("height-mode=AUTO"));
            var cause = (DocumentLayoutException) failure.getCause();
            assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, cause.code());
            assertEquals("document page budget exceeded", cause.getMessage());
        }
    }

    @Test void fixedBoxLineBudgetRemainsABudgetFailureForBothBodyFlowPolicies() {
        for (var flow : PageOverlay.BodyFlow.values()) {
            var content = new Paragraph("fixed", "a\n".repeat(10_000) + "a", FORMAT);
            var box = new PageOverlay(20, 65, 100, new PageRegion(24, 0, List.of(content)),
                    1, PageOverlay.SizeMode.FIXED, PageOverlay.SizeMode.FIXED, flow);
            var failure = assertThrows(DocumentLayoutException.class, () -> render(List.of(lines("body", 1)), box));
            assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
            assertEquals("fixed", failure.blockId());
            assertEquals(TextLayoutException.Code.BUDGET_EXCEEDED, ((TextLayoutException) failure.getCause()).code());
        }
    }
}
