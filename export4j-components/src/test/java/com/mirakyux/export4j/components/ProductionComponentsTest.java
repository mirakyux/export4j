// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.CrossReference;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;

class ProductionComponentsTest {
    private static FontGroup fonts;
    private static final ParagraphStyle STYLE = new ParagraphStyle(12, 26, 0, 0, TextAlignment.LEFT);
    @BeforeAll static void fonts() throws IOException {
        List<FontFace> faces = new ArrayList<>();
        for (String name : List.of("LiberationSans-Regular.ttf", "NotoNaskhArabic-Regular.ttf", "LXGWWenKaiTC-Regular.ttf")) {
            try (var input = ProductionComponentsTest.class.getResourceAsStream("/fonts/" + name)) {
                faces.add(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES));
            }
        }
        fonts = FontGroup.strict(faces);
    }
    private static ComponentContext context(double w, double h) { return new ComponentContext(w, h, fonts, 0, 0, 1); }
    private static String outlines(List<com.mirakyux.export4j.layout.ComponentLayout> parts) {
        return parts.stream().flatMap(p -> p.commands().stream()).filter(DrawCommand.OutlineText.class::isInstance)
                .map(DrawCommand.OutlineText.class::cast).map(DrawCommand.OutlineText::logicalText).collect(java.util.stream.Collectors.joining());
    }

    @Test void kerningChangesMeasuredAdvancesAndBidiPreservesLogicalSource() {
        var kerned = new OpenTypeText("AVATAR", STYLE, RgbColor.BLACK).measure(context(500, 100), line -> 500, line -> 0);
        var plain = new OpenTypeText("AVATAR", STYLE, RgbColor.BLACK, OpenTypeText.Direction.AUTO, false, false, false)
                .measure(context(500, 100), line -> 500, line -> 0);
        assertTrue(kerned.lines().getFirst().width() < plain.lines().getFirst().width());
        String source = "English العربية لا 123 שָׁלוֹם a\u0301 ffi";
        var parts = new OpenTypeText(source, STYLE, RgbColor.BLACK).layoutParts(context(160, 78));
        assertEquals(source, outlines(parts));
        assertTrue(parts.stream().flatMap(p -> p.commands().stream()).filter(DrawCommand.OutlineText.class::isInstance)
                .map(DrawCommand.OutlineText.class::cast).anyMatch(p -> !p.paths().isEmpty()));
    }

    @Test void graphemesAndCjkPunctuationAreKeptTogether() {
        String source = "a\u0301a\u0301a\u0301（中文），测试。";
        var result = new OpenTypeText(source, STYLE, RgbColor.BLACK).measure(context(40, 200), line -> 40, line -> 0);
        assertEquals(source, result.lines().stream().map(l -> l.command().logicalText()).collect(java.util.stream.Collectors.joining()));
        for (var line : result.lines()) {
            assertTrue(line.command().logicalText().codePointAt(0) != 0x301);
            assertTrue(com.mirakyux.export4j.text.LineBreakRules.allowed(source, line.sourceEnd()));
        }
        assertThrows(IllegalArgumentException.class, () -> new OpenTypeText("a\u0301", STYLE, RgbColor.BLACK).layoutParts(context(1, 100)));
    }

    @Test void missingGlyphPolicyAndSourceRangesApplyToOpenType() {
        var latin = fonts.fonts().getFirst();
        var replace = new ComponentContext(200, 100, FontGroup.placeholder(List.of(latin), '?'), 0, 0, 1);
        var parts = new OpenTypeText("A\udbff\udfffB", STYLE, RgbColor.BLACK).layoutParts(replace);
        var warning = parts.getFirst().diagnostics().getFirst();
        assertEquals(1, warning.sourceStart()); assertEquals(3, warning.sourceEnd()); assertEquals(0x10ffff, warning.sourceCodePoint());
        assertThrows(IllegalArgumentException.class, () -> new OpenTypeText("\udbff\udfff", STYLE, RgbColor.BLACK).layoutParts(context(100, 100)));
        assertThrows(IllegalArgumentException.class, () -> new OpenTypeText("\ud800", STYLE, RgbColor.BLACK));
    }

    @Test void columnsAndFloatPreserveOrderAcrossPages() {
        String source = java.util.stream.IntStream.rangeClosed(1, 40).mapToObj(i -> "Line-" + i + "\n").collect(java.util.stream.Collectors.joining()).stripTrailing();
        var text = new OpenTypeText(source, STYLE, RgbColor.BLACK);
        var columns = new MultiColumn(text, 2, 12).layoutParts(context(250, 104));
        assertEquals(5, columns.size());
        assertEquals(source.replace("\n", ""), outlines(columns));
        var floating = (com.mirakyux.export4j.layout.core.Component) ctx -> new com.mirakyux.export4j.layout.ComponentLayout(ctx.width(), ctx.height(), 0,
                List.of(new DrawCommand.FillRect(new Rect(0, 0, ctx.width(), ctx.height()), new RgbColor(0.2, 0.6, 0.8))), List.of());
        String words = "floating text expands below the image and continues on later pages ".repeat(20);
        var parts = new FloatText(new OpenTypeText(words, STYLE, RgbColor.BLACK), floating, 70, 52, 10, FloatText.Side.LEFT).layoutParts(context(250, 104), 78);
        assertEquals(words, outlines(parts));
        assertEquals(1, parts.stream().flatMap(p -> p.commands().stream()).filter(DrawCommand.FillRect.class::isInstance).count());
        assertTrue(parts.getFirst().height() <= 78);
    }

    @Test void multilevelListsReferencesAndContextualWarningsReachFinalPages() {
        var f = ParagraphFormat.of(12);
        var blocks = List.<Block>of(new NumberedItem("a", "list", "One", 0, f, 1), new NumberedItem("b", "list", "Child", 0, f, 2),
                new NumberedItem("c", "list", "Next", 0, f, 1), new BulletItem("bullet", "Item", "•", 2, f),
                new CrossReference("ref", "A long reference label that must wrap over several lines: ", "b", CrossReference.Kind.NUMBER, f), new Paragraph("warning", "中文", f));
        var source = new Document(DocumentMetadata.EMPTY, new PageSettings(new PageSize(180, 300), PageMargins.uniform(10)), blocks);
        var result = DocumentEngine.builder(FontGroup.placeholder(List.of(fonts.fonts().getFirst()), '?')).build().layout(source);
        String body = result.body().pages().stream().flatMap(p -> p.fragments().stream()).flatMap(p -> p.layout().lines().stream()).flatMap(l -> l.runs().stream())
                .map(com.mirakyux.export4j.text.GlyphRun::text).collect(java.util.stream.Collectors.joining());
        assertTrue(body.contains("1. One1.1. Child2. Next• Item"));
        assertTrue(result.resolvedReferences().getFirst().textLines() > 1);
        assertEquals(2, result.contextualDiagnostics().stream().filter(d -> d.blockId().equals("warning") && d.pageIndex() >= 0).count());
        assertThrows(IllegalArgumentException.class, () -> DocumentEngine.builder(fonts).build().layout(new Document(DocumentMetadata.EMPTY, PageSettings.A4,
                List.of(new NumberedItem("bad", "list", "skipped", 0, f, 2)))));
    }

    @Test void newFootnoteStartsOnItsAnchorPageWhileAnEarlierNoteContinues() {
        var settings = new PageSettings(new PageSize(250, 220), PageMargins.uniform(10));
        var document = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", settings, List.of(
                new Paragraph("a", "First anchor", ParagraphFormat.of(12)), new com.mirakyux.export4j.model.PageBreak("break"),
                new Paragraph("b", "Second anchor", ParagraphFormat.of(12))), 0)));
        var result = new ComponentDocumentRenderer().layout(document, List.of(SectionTemplate.uniform(PageTemplate.EMPTY)), Navigation.EMPTY,
                FontGroup.strict(List.of(fonts.fonts().getFirst())), Map.of(), List.of(
                        new Note("n1", "a", "Long note\n".repeat(30), Note.Kind.FOOTNOTE),
                        new Note("n2", "b", "NEW-ANCHOR", Note.Kind.FOOTNOTE)));
        String secondPage = result.footnotes().get(1).commands().stream().filter(DrawCommand.GlyphText.class::isInstance)
                .map(DrawCommand.GlyphText.class::cast).map(t -> t.run().text()).collect(java.util.stream.Collectors.joining());
        assertTrue(secondPage.contains("[2] NEW-ANCHOR"));
        String all = result.footnotes().stream().flatMap(p -> p.commands().stream()).filter(DrawCommand.GlyphText.class::isInstance)
                .map(DrawCommand.GlyphText.class::cast).map(t -> t.run().text()).collect(java.util.stream.Collectors.joining());
        assertEquals(30, all.split("Long note", -1).length - 1);
        var tooMany = List.of(new Note("n1", "a", "A", Note.Kind.FOOTNOTE), new Note("n2", "a", "B", Note.Kind.FOOTNOTE),
                new Note("n3", "a", "C", Note.Kind.FOOTNOTE), new Note("n4", "a", "D", Note.Kind.FOOTNOTE));
        assertThrows(IllegalArgumentException.class, () -> new ComponentDocumentRenderer().layout(document,
                List.of(SectionTemplate.uniform(PageTemplate.EMPTY)), Navigation.EMPTY, fonts, Map.of(), tooMany));
    }

    @Test void longFootnoteContinuesWithoutLosingOrDuplicatingLines() {
        String source = java.util.stream.IntStream.rangeClosed(1, 35).mapToObj(i -> "NOTE-" + i).collect(java.util.stream.Collectors.joining("\n"));
        var settings = new PageSettings(new PageSize(250, 220), PageMargins.uniform(10));
        var document = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", settings, List.of(new Paragraph("p", "Anchor", ParagraphFormat.of(12))), 0)));
        var result = new ComponentDocumentRenderer().layout(document, List.of(SectionTemplate.uniform(PageTemplate.EMPTY)), Navigation.EMPTY,
                FontGroup.strict(List.of(fonts.fonts().getFirst())), Map.of(),
                List.of(new Note("n", "p", source, Note.Kind.FOOTNOTE)));
        assertTrue(result.display().pages().size() > 1);
        String drawn = result.footnotes().stream().flatMap(p -> p.commands().stream()).filter(DrawCommand.GlyphText.class::isInstance)
                .map(DrawCommand.GlyphText.class::cast).map(t -> t.run().text()).collect(java.util.stream.Collectors.joining());
        assertEquals("[1] " + source.replace("\n", ""), drawn);
        assertTrue(result.footnotes().stream().allMatch(p -> p.textLines() > 0));
    }
}
