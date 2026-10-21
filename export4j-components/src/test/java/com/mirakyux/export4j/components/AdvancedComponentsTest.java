// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.layout.core.PageBand;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.layout.core.Placement;
import com.mirakyux.export4j.layout.core.Position;
import com.mirakyux.export4j.model.Caption;
import com.mirakyux.export4j.model.CrossReference;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.text.ParagraphStyle;

class AdvancedComponentsTest {
    @Test
    void fontGroupPolicyPropagatesToContentsBodyNestedComponentsAndPageRegions() {
        var latin = fonts.getFirst();
        var group = com.mirakyux.export4j.font.FontGroup.placeholder(List.of(latin), '?');
        var doc = new SectionedDocument(META, List.of(new Section("s", SETTINGS, List.of(new Heading("h", "中文", 1, ParagraphFormat.of(12)),
                new ComponentBlock("nested", new FlowColumn(List.of(new TextComponent("字", ParagraphStyle.of(10), RgbColor.BLACK)),
                        PageMargins.uniform(4), 0, FlowColumn.Alignment.START), 0, 0)), 0)));
        var result = new ComponentDocumentRenderer().layout(doc, List.of(template("首页", "奇页", "偶页")), Navigation.fromHeadings(doc),
                group, Map.of(), List.of());
        var warnings = result.diagnostics().stream().filter(d -> d.severity() == com.mirakyux.export4j.text.TextDiagnostic.Severity.WARNING).toList();
        assertTrue(warnings.size() >= 9);
        assertTrue(warnings.stream().anyMatch(d -> d.sourceCodePoint() == '字'));
        assertTrue(warnings.stream().allMatch(d -> d.replacementCodePoint() == '?'));
        assertThrows(com.mirakyux.export4j.layout.core.DocumentLayoutException.class, () -> new ComponentDocumentRenderer().layout(doc,
                List.of(SectionTemplate.uniform(PageTemplate.EMPTY)), Navigation.EMPTY,
                com.mirakyux.export4j.font.FontGroup.strict(List.of(latin)), Map.of(), List.of()));
    }
    private static List<FontFace> fonts;
    private static final DocumentMetadata META = new DocumentMetadata("Advanced", "Export4j");
    private static final PageSettings SETTINGS = new PageSettings(new PageSize(300, 220), PageMargins.uniform(12));

    @BeforeAll
    static void fonts() throws IOException {
        try (var input = AdvancedComponentsTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES));
        }
        try (var input = AdvancedComponentsTest.class.getResourceAsStream("/fonts/LXGWWenKaiTC-Regular.ttf")) {
            fonts = List.of(fonts.getFirst(), TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES));
        }
    }

    @Test
    void nestedColumnsPaginateWithPaddingAndPreserveTextOrder() {
        var flow = new FlowColumn(List.of(new FlowColumn(List.of(new FlowText("A\nB\nC\nD\nE\nF\nG\nH",
                ParagraphStyle.of(10), RgbColor.BLACK, PaginationPolicy.BALANCED)), PageMargins.uniform(4), 3, FlowColumn.Alignment.CENTER)),
                PageMargins.uniform(4), 5, FlowColumn.Alignment.END);
        var settings = new PageSettings(new PageSize(160, 80), PageMargins.uniform(10));
        var result = new DocumentLayouter().layout(new Document(META, settings, List.of(new ComponentBlock("nested", flow, 0, 0))), fonts);
        assertTrue(result.pages().size() >= 3);
        var display = com.mirakyux.export4j.layout.core.DocumentPainter.paint(result);
        assertEquals("ABCDEFGH", glyphText(display));
        for (var page : result.pages()) {
            var fragment = (com.mirakyux.export4j.layout.ComponentFragment) page.elements().getFirst();
            assertTrue(fragment.layout().height() <= settings.contentHeight());
        }
    }

    @Test
    void mergedRowsStayOnOnePageAndHeadersRepeat() {
        var text = new TextComponent("CELL", ParagraphStyle.of(10), RgbColor.BLACK);
        var cells = List.of(cell(0, 0, 1, 2, text), cell(1, 0, 2, 1, text), cell(1, 1, 1, 1, text), cell(2, 1, 1, 1, text),
                cell(3, 0, 1, 2, text));
        var grid = new GridTable(4, List.of(1.0, 1.0), 1, cells);
        var parts = grid.layoutParts(new ComponentContext(180, 64, fonts, 0, 0));
        assertEquals(2, parts.size());
        var adaptive = grid.layoutParts(new ComponentContext(180, 100, fonts, 0, 0), 60);
        assertEquals(2, adaptive.size());
        assertTrue(adaptive.getFirst().height() <= 60);
        assertEquals(List.of(4, 2), parts.stream().map(p -> (int) p.commands().stream().filter(DrawCommand.GlyphText.class::isInstance).count()).toList());
        assertThrows(IllegalArgumentException.class, () -> grid.layoutParts(new ComponentContext(180, 40, fonts, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new GridTable(2, List.of(1.0), 1, List.of(cell(0, 0, 2, 1, text))));
        assertThrows(IllegalArgumentException.class, () -> new GridTable(2, List.of(1.0), 0, List.of(cell(0, 0, 1, 1, text))));
    }

    @Test
    void sectionsResolveMixedSizesRestartedNumbersAndAutomaticContentsLinks() {
        var a = new Section("a", SETTINGS, List.of(new Heading("a-title", "Portrait", 1, ParagraphFormat.of(12))), 0);
        var bSettings = new PageSettings(new PageSize(400, 200), PageMargins.uniform(12));
        var b = new Section("b", bSettings, List.of(new Heading("b-title", "Landscape", 1, ParagraphFormat.of(12))), 7);
        var document = new SectionedDocument(META, List.of(a, b));
        var template = template("FIRST", "ODD", "EVEN");
        var result = new ComponentDocumentRenderer().layout(document, List.of(template, template), Navigation.fromHeadings(document), fonts, Map.of());
        assertEquals(1, result.bodyPageOffset());
        assertEquals(List.of(300.0, 300.0, 400.0), result.display().pages().stream().map(page -> page.size().width()).toList());
        assertEquals(List.of(1, 2), result.display().extras().links().stream().map(link -> link.targetPageIndex()).toList());
        assertTrue(pageText(result.display().pages().getFirst()).contains("7"));
        assertTrue(pageText(result.display().pages().get(1)).contains("FIRST 2"));
        assertTrue(pageText(result.display().pages().get(2)).contains("FIRST 7"));
        assertEquals(1, result.body().pages().get(1).elements().getFirst().blockIndex());
    }

    @Test
    void contentsUseOddEvenTemplatesAndBodyKeepsItsSectionFirstTemplate() {
        var blocks = java.util.stream.IntStream.range(0, 14)
                .mapToObj(index -> (com.mirakyux.export4j.model.Block) new Heading("h" + index,
                        "Heading " + index, 1, ParagraphFormat.of(12))).toList();
        var document = new SectionedDocument(META, List.of(new Section("s", SETTINGS, blocks, 0)));
        var result = new ComponentDocumentRenderer().layout(document, List.of(template("FIRST", "ODD", "EVEN")),
                Navigation.fromHeadings(document), fonts, Map.of());
        assertTrue(result.bodyPageOffset() >= 2);
        assertTrue(pageText(result.display().pages().get(0)).contains("ODD 1"));
        assertTrue(pageText(result.display().pages().get(1)).contains("EVEN 2"));
        assertTrue(pageText(result.display().pages().get(result.bodyPageOffset())).contains("FIRST " + (result.bodyPageOffset() + 1)));
    }

    @Test
    void oddEvenTemplatesFollowDisplayedSectionNumbers() {
        var blocks = List.<com.mirakyux.export4j.model.Block>of(new Paragraph("a", "A", ParagraphFormat.of(10)),
                new com.mirakyux.export4j.model.PageBreak("break1"), new Paragraph("b", "B", ParagraphFormat.of(10)),
                new com.mirakyux.export4j.model.PageBreak("break2"), new Paragraph("c", "C", ParagraphFormat.of(10)));
        var document = new SectionedDocument(META, List.of(new Section("s", SETTINGS, blocks, 8)));
        var result = new ComponentDocumentRenderer().layout(document, List.of(template("FIRST", "ODD", "EVEN")), Navigation.EMPTY, fonts, Map.of());
        assertTrue(pageText(result.display().pages().get(0)).contains("FIRST 8"));
        assertTrue(pageText(result.display().pages().get(1)).contains("ODD 9"));
        assertTrue(pageText(result.display().pages().get(2)).contains("EVEN 10"));
    }

    @Test
    void semanticCountersReferencesAndNotesResolveAfterPagination() {
        var format = ParagraphFormat.of(10);
        var section = new Section("s", SETTINGS, List.of(new NumberedItem("i1", "list", "One", 0, format),
                new NumberedItem("i2", "list", "Two", 0, format), new Caption("fig", "Figure", "A diagram", format),
                new CrossReference("ref", "See ", "fig", CrossReference.Kind.NUMBER, format),
                new CrossReference("page-ref", "On page ", "fig", CrossReference.Kind.PAGE, format)), 3);
        var result = new ComponentDocumentRenderer().layout(new SectionedDocument(META, List.of(section)),
                List.of(SectionTemplate.uniform(PageTemplate.EMPTY)), Navigation.EMPTY, fonts, Map.of(),
                List.of(new Note("foot", "i1", "Footnote content", Note.Kind.FOOTNOTE), new Note("end", "i2", "Endnote content", Note.Kind.ENDNOTE)));
        String text = glyphText(result.display());
        assertTrue(text.contains("1. One[1]"));
        assertTrue(text.contains("2. Two[2]"));
        assertTrue(text.contains("See Figure 1"));
        assertTrue(text.contains("On page 3"));
        assertTrue(text.contains("[1] Footnote content"));
        assertTrue(text.contains("[2] Endnote content"));
        assertEquals(2, result.display().extras().links().size());
        var foot = result.footnotes().getFirst().commands().stream().filter(DrawCommand.GlyphText.class::isInstance).map(DrawCommand.GlyphText.class::cast).findFirst().orElseThrow();
        var bounds = result.body().pages().getFirst().contentBounds();
        assertTrue(foot.baseline().y() > bounds.y() + bounds.height());
    }

    @Test
    void svgPreservesVectorsAndRejectsExternalOrUnsupportedFeatures() {
        var svg = SvgComponent.parse("<svg viewBox='0 0 100 50'><g fill='#22aa88'><rect x='1' y='1' width='98' height='48'/><path d='M 5 5 L 25 5 C 30 5 30 20 25 20 Z'/></g></svg>");
        var drawing = svg.layout(new ComponentContext(200, 200, fonts, 0, 0));
        assertEquals(200, drawing.width());
        assertEquals(100, drawing.height());
        assertEquals(2, drawing.commands().stream().filter(DrawCommand.FillPath.class::isInstance).count());
        assertThrows(IllegalArgumentException.class, () -> SvgComponent.parse("<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><svg viewBox='0 0 10 10'/>"));
        assertThrows(IllegalArgumentException.class, () -> SvgComponent.parse("<svg viewBox='0 0 10 10'><image href='https://example.com/x'/></svg>"));
        assertThrows(IllegalArgumentException.class, () -> SvgComponent.parse("<svg viewBox='0 0 10 10'><g transform='rotate(20)'/></svg>"));
        assertThrows(IllegalArgumentException.class, () -> SvgComponent.parse("<svg width='20px' viewBox='0 0 10 10'/>"));
    }

    private static GridTable.Cell cell(int row, int col, int rows, int cols, com.mirakyux.export4j.layout.core.Component component) {
        return new GridTable.Cell(row, col, rows, cols, component, PageMargins.uniform(2), FlowColumn.Alignment.CENTER, RgbColor.WHITE, RgbColor.BLACK, 0.5);
    }

    private static SectionTemplate template(String first, String odd, String even) {
        return new SectionTemplate(header(first), header(odd), header(even));
    }

    private static PageTemplate header(String text) {
        return new PageTemplate(new PageBand(18, 4, List.of(new Placement(new PageNumberComponent(text + " {page}", ParagraphStyle.of(10), RgbColor.BLACK),
                new Position.Fractional(0, 0, 1, 1)))), PageBand.EMPTY, List.of(), List.of());
    }

    private static String pageText(com.mirakyux.export4j.graphics.DisplayPage page) {
        return page.commands().stream().filter(DrawCommand.GlyphText.class::isInstance).map(DrawCommand.GlyphText.class::cast)
                .map(command -> command.run().text()).collect(java.util.stream.Collectors.joining());
    }

    private static String glyphText(com.mirakyux.export4j.graphics.DisplayDocument display) {
        return display.pages().stream().map(AdvancedComponentsTest::pageText).collect(java.util.stream.Collectors.joining());
    }
}
