// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.CrossReference;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.text.GlyphRun;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextLayoutException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListMarkerRenderingTest {
    private static final PageSettings PAGE = new PageSettings(new PageSize(300, 240), PageMargins.uniform(10));
    private static final ParagraphFormat FORMAT = ParagraphFormat.builder().fontSize(10).lineHeight(24).spaceAfter(0).build();
    private static final InlineStyle MARKER = new InlineStyle("marker", 14, 0x0000ff, -2, true, true);
    private static FontGroup regular;
    private static FontGroup bold;
    private static FontGroup italic;

    @BeforeAll static void loadFonts() throws IOException {
        regular = font("LiberationSans-Regular.ttf");
        bold = font("LiberationSans-Bold.ttf");
        italic = font("LiberationSans-Italic.ttf");
    }

    @Test void legacyAndExplicitDefaultMarkersProduceIdenticalPlainAndRichOutput() {
        var body = new InlineText("RICH", new InlineStyle("body", 10, 0x882244, 0, true, false), "https://example.com");
        List<Block> legacy = List.of(new BulletItem("plain-b", "PLAIN", "*", 1, FORMAT),
                new NumberedItem("plain-n", "list", "PLAIN", 0, FORMAT),
                BulletItem.rich("rich-b", List.of(body), "*", 1, FORMAT),
                NumberedItem.rich("rich-n", "list", List.of(body), 0, FORMAT, 1));
        List<Block> explicit = List.of(new BulletItem("plain-b", "PLAIN", "*", 1, FORMAT, List.of(), InlineStyle.of(10)),
                new NumberedItem("plain-n", "list", "PLAIN", 0, FORMAT, 1, List.of(), InlineStyle.of(10)),
                BulletItem.rich("rich-b", List.of(body), "*", 1, FORMAT, InlineStyle.of(10)),
                NumberedItem.rich("rich-n", "list", List.of(body), 0, FORMAT, 1, InlineStyle.of(10)));
        assertEquals(engine().layout(document(legacy)), engine().layout(document(explicit)));
        var lowered = SemanticBlocks.compile(sectioned(legacy), List.of()).document().sections().getFirst().blocks();
        assertTrue(((Paragraph) lowered.getFirst()).inlines().isEmpty());
        assertTrue(((Paragraph) lowered.get(1)).inlines().isEmpty());
    }

    @Test void markerFontColorDecorationsAndShiftStaySeparateFromTheFirstBodyRunAndItsLink() {
        var body = new InlineText("BODY", new InlineStyle("body", 10, 0xff0000, 0, false, false), "https://example.com");
        var item = NumberedItem.builder().id("item").listId("list").inlines(List.of(body)).format(FORMAT).markerStyle(MARKER).build();
        var result = engine().layout(document(List.of(item)));
        var runs = runs(result);
        assertEquals(List.of("1. ", "BODY"), runs.stream().map(GlyphRun::text).toList());
        assertEquals(italic.fonts().getFirst().data().id(), runs.getFirst().font().id());
        assertEquals(14, runs.getFirst().fontSize());
        assertEquals(0x0000ff, runs.getFirst().rgb());
        assertEquals(-2, runs.getFirst().baselineShift());
        assertTrue(runs.getFirst().underline());
        assertTrue(runs.getFirst().strikeThrough());
        assertEquals(bold.fonts().getFirst().data().id(), runs.get(1).font().id());
        assertEquals(10, runs.get(1).fontSize());
        assertEquals(0xff0000, runs.get(1).rgb());
        assertFalse(runs.get(1).underline());
        assertFalse(runs.get(1).strikeThrough());
        assertEquals(1, result.display().extras().externalLinks().size());
        var strokes = result.display().pages().getFirst().commands().stream().filter(DrawCommand.StrokePath.class::isInstance)
                .map(DrawCommand.StrokePath.class::cast).toList();
        assertEquals(2, strokes.size());
        assertTrue(strokes.stream().allMatch(stroke -> stroke.color().equals(new RgbColor(0, 0, 1))));
        assertEquals("BODY", item.text());
        assertEquals(body, item.inlines().getFirst());
    }

    @Test void plainBodiesBecomeExplicitDefaultRunsWithoutInheritingTheMarkersStyle() {
        var item = BulletItem.builder().id("item").marker("*").text("BODY").format(FORMAT).markerStyle(MARKER).build();
        var runs = runs(engine().layout(document(List.of(item))));
        assertEquals(List.of("* ", "BODY"), runs.stream().map(GlyphRun::text).toList());
        assertEquals(italic.fonts().getFirst().data().id(), runs.getFirst().font().id());
        assertEquals(regular.fonts().getFirst().data().id(), runs.get(1).font().id());
        assertEquals(10, runs.get(1).fontSize());
        assertEquals(0, runs.get(1).rgb());
        assertEquals(0, runs.get(1).baselineShift());
    }

    @Test void markerOnlyFontPoliciesRetainOriginalPrefixUtf16Diagnostics() {
        var item = BulletItem.builder().id("emoji-marker").marker("😀").text("BODY").format(FORMAT)
                .markerStyle(new InlineStyle("warn", 10, 0x0000ff, 0, false, false)).build();
        var replacement = FontGroup.placeholder(italic.fonts(), '?');
        var engine = DocumentEngine.builder(regular).fontRole("warn", replacement).build();
        var result = engine.layout(document(List.of(item)));
        var warning = result.contextualDiagnostics().stream().filter(value -> value.diagnostic().code() == TextDiagnostic.Code.MISSING_GLYPH_REPLACED)
                .findFirst().orElseThrow();
        assertEquals("emoji-marker", warning.blockId());
        assertEquals(0, warning.diagnostic().sourceStart());
        assertEquals(2, warning.diagnostic().sourceEnd());
        assertEquals(0x1f600, warning.diagnostic().sourceCodePoint());
        assertEquals(italic.fonts().getFirst().data().id(), warning.diagnostic().fontId());
        var strict = DocumentEngine.builder(FontGroup.placeholder(regular.fonts(), '?')).fontRole("warn", italic).build();
        var failure = assertThrows(DocumentLayoutException.class, () -> strict.layout(document(List.of(item))));
        assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, failure.code());
        assertEquals(0, failure.sourceIndex());
        assertEquals(TextLayoutException.Code.MISSING_GLYPH, ((TextLayoutException) failure.getCause()).code());
    }

    @Test void bodyWarningsAreOffsetByThePrefixAndUseTheBodyPolicy() {
        var item = BulletItem.builder().id("body-warning").marker("*").text("A😀B").format(FORMAT).markerStyle(MARKER).build();
        var engine = DocumentEngine.builder(FontGroup.placeholder(regular.fonts(), '?')).fontRole("marker", italic).build();
        var result = engine.layout(document(List.of(item)));
        var warnings = result.contextualDiagnostics().stream().filter(value -> value.diagnostic().code() == TextDiagnostic.Code.MISSING_GLYPH_REPLACED).toList();
        assertEquals(1, warnings.size());
        assertEquals(3, warnings.getFirst().diagnostic().sourceStart());
        assertEquals(5, warnings.getFirst().diagnostic().sourceEnd());
        assertEquals(regular.fonts().getFirst().data().id(), warnings.getFirst().diagnostic().fontId());
    }

    @Test void unregisteredMarkerRolesFailAtPrefixZeroIncludingAnEmptyBody() {
        for (String body : List.of("BODY", "")) {
            var item = NumberedItem.builder().id("unknown").listId("list").text(body).format(FORMAT)
                    .markerStyle(new InlineStyle("unknown", 10, 0, 0, false, false)).build();
            var failure = assertThrows(DocumentLayoutException.class, () -> engine().layout(document(List.of(item))));
            assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, failure.code());
            assertEquals("unknown", failure.blockId());
            assertEquals(0, failure.sourceIndex());
        }
    }

    @Test void markerSizeAndShiftMustFitTheExistingFixedLineFrame() {
        for (var marker : List.of(new InlineStyle("marker", 40, 0, 0, false, false),
                new InlineStyle("marker", 10, 0, -30, false, false))) {
            var item = NumberedItem.builder().id("oversized").listId("list").text("BODY").format(FORMAT).markerStyle(marker).build();
            var failure = assertThrows(DocumentLayoutException.class, () -> engine().layout(document(List.of(item))));
            assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, failure.code());
            assertEquals(TextLayoutException.Code.LINE_HEIGHT_TOO_SMALL, ((TextLayoutException) failure.getCause()).code());
        }
    }

    @Test void multilevelRestartsReferencesAndContinuationAcrossSectionsKeepIndependentMarkers() {
        var top = numbered("top", 1, 3, "TOP");
        var child = numbered("child", 2, 4, "CHILD");
        var next = numbered("next", 1, 0, "NEXT");
        var nextChild = numbered("next-child", 2, 0, "NEXT-CHILD");
        var restart = numbered("restart", 1, 8, "RESTART");
        var reference = new CrossReference("reference", "See ", "child", CrossReference.Kind.NUMBER, FORMAT);
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(
                new Section("first", PAGE, List.of(top, child, next), 0),
                new Section("second", PAGE, List.of(nextChild, restart, reference), 7)));
        var lowered = SemanticBlocks.compile(source, List.of());
        assertEquals(Map.of("top", "3", "child", "3.4", "next", "4", "next-child", "4.1", "restart", "8"), lowered.labels());
        var result = engine().layout(source);
        assertEquals(List.of("3. TOP", "3.4. CHILD", "4. NEXT", "4.1. NEXT-CHILD", "8. RESTART"),
                result.body().pages().stream().flatMap(page -> page.fragments().stream()).map(ListMarkerRenderingTest::logical).toList());
        assertTrue(displayText(result).contains("See 3.4"));
        assertEquals(1, result.display().extras().links().size());
        var childFragment = result.body().pages().stream().flatMap(page -> page.fragments().stream())
                .filter(fragment -> fragment.paragraphId().equals("child")).findFirst().orElseThrow();
        assertEquals(15, childFragment.layout().lines().getFirst().offsetX());
        assertEquals(italic.fonts().getFirst().data().id(), childFragment.layout().lines().getFirst().runs().getFirst().font().id());
    }

    @Test void templatesKeepMarkerRolesForRepeatedItemsAndIndependentlyPrefixedListCopies() {
        var prototype = NumberedItem.builder().id("fragment.item").listId("fragment.list").text("${item.name}")
                .format(FORMAT).markerStyle(MARKER).build();
        var copy = prototype.toBuilder().id("other.item").listId("other.list").build();
        var template = DocumentTemplate.builder().pageSettings(PAGE).each("items", "item", nodes -> nodes.block(prototype).block(copy)).build();
        var document = template.render(Map.of("items", List.of(Map.of("name", "Alpha"), Map.of("name", "Beta"))));
        var result = engine().layout(document);
        assertEquals(List.of("1. Alpha", "1. Alpha", "2. Beta", "2. Beta"),
                result.body().pages().stream().flatMap(page -> page.fragments().stream()).map(ListMarkerRenderingTest::logical).toList());
        assertEquals(List.of("fragment.item@0", "other.item@0", "fragment.item@1", "other.item@1"),
                result.body().pages().stream().flatMap(page -> page.fragments().stream()).map(ParagraphFragment::paragraphId).toList());
        assertTrue(result.body().pages().stream().flatMap(page -> page.fragments().stream())
                .allMatch(fragment -> fragment.layout().lines().getFirst().runs().getFirst().font().id().equals(italic.fonts().getFirst().data().id())));
    }

    @Test void generatedPrefixStillConsumesSourceAndOutputLineBudgets() {
        var item = NumberedItem.builder().id("budget").listId("list").text("A").format(FORMAT).markerStyle(MARKER).build();
        var document = document(List.of(item));
        var exact = renderWithLimits(document, new DocumentLayoutLimits(10, 4, 10, 10));
        assertEquals("1. A", displayText(exact));
        var failure = assertThrows(DocumentLayoutException.class,
                () -> renderWithLimits(document, new DocumentLayoutLimits(10, 3, 10, 10)));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        var twoLines = document(List.of(item.toBuilder().text("A\nB").build()));
        var lineFailure = assertThrows(DocumentLayoutException.class,
                () -> renderWithLimits(twoLines, new DocumentLayoutLimits(10, 100, 1, 10)));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, lineFailure.code());
    }

    @Test void cancellationWhileLayingOutStyledMarkersRestoresExecutionAndAllowsRetry() throws IOException {
        var document = document(List.of(NumberedItem.builder().id("cancel").listId("list").text("BODY ".repeat(1_000))
                .format(FORMAT).markerStyle(MARKER).build()));
        var checks = new AtomicInteger();
        var options = ExecutionOptions.builder().cancelled(() -> checks.incrementAndGet() > 6).build();
        var engine = engine();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options, () -> engine.layout(document)));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        assertTrue(checks.get() > 6);
        ExecutionContext.checkpoint();
        var recovered = engine.layout(document(List.of(numbered("recovered", 1, 0, "BODY"))));
        assertEquals("1. BODY", displayText(recovered));
    }

    private static NumberedItem numbered(String id, int level, int restartAt, String text) {
        return NumberedItem.builder().id(id).listId("list").text(text).level(level).restartAt(restartAt).format(FORMAT).markerStyle(MARKER).build();
    }

    private static DocumentEngine engine() { return DocumentEngine.builder(regular).fontRole("marker", italic).fontRole("body", bold).build(); }
    private static Document document(List<Block> blocks) { return new Document(DocumentMetadata.EMPTY, PAGE, blocks); }
    private static SectionedDocument sectioned(List<Block> blocks) { return new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("section", PAGE, blocks, 0))); }

    private static ComponentDocumentLayout renderWithLimits(Document document, DocumentLayoutLimits limits) {
        return new ComponentDocumentRenderer(limits, ComponentLimits.DEFAULT).layout(sectioned(document.blocks()),
                List.of(SectionTemplate.uniform(PageTemplate.EMPTY)), Navigation.EMPTY, regular, Map.of("marker", italic), List.of());
    }

    private static FontGroup font(String name) throws IOException {
        try (var input = ListMarkerRenderingTest.class.getResourceAsStream("/fonts/" + name)) {
            if (input == null) { throw new IOException("missing test font"); }
            return FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    private static List<GlyphRun> runs(ComponentDocumentLayout result) {
        return result.body().pages().stream().flatMap(page -> page.fragments().stream())
                .flatMap(fragment -> fragment.layout().lines().stream()).flatMap(line -> line.runs().stream()).toList();
    }

    private static String logical(ParagraphFragment fragment) {
        return fragment.layout().lines().stream().map(line -> line.outlines().isEmpty()
                ? line.runs().stream().map(GlyphRun::text).collect(Collectors.joining())
                : line.outlines().stream().map(outline -> outline.logicalText()).collect(Collectors.joining())).collect(Collectors.joining());
    }

    private static String displayText(ComponentDocumentLayout result) {
        return result.display().pages().stream().flatMap(page -> page.commands().stream()).map(command -> {
            if (command instanceof DrawCommand.GlyphText text) { return text.run().text(); }
            if (command instanceof DrawCommand.OutlineText text) { return text.logicalText(); }
            return "";
        }).collect(Collectors.joining());
    }
}
