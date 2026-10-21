// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.components.ComponentDocumentLayout;
import com.mirakyux.export4j.components.ComponentDocumentRenderer;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Navigation;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.text.ParagraphLayout;

class XmlNumberedLevelsIntegrationTest {
    private static final PageSettings PAGE = PageSettings.builder().size(520, 360).margins(16).build();
    private static final ParagraphFormat BODY = ParagraphFormat.builder().fontSize(10).lineHeight(18).spaceAfter(0).build();
    private static FontGroup regular;
    private static FontGroup bold;

    @BeforeAll static void fonts() throws IOException {
        regular = font("LiberationSans-Regular.ttf"); bold = font("LiberationSans-Bold.ttf");
    }

    private static FontGroup font(String name) throws IOException {
        try (var input = XmlNumberedLevelsIntegrationTest.class.getResourceAsStream("/fonts/" + name)) {
            if (input == null) { throw new IOException("missing test font"); }
            return FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }
    private static String xml(String content) { return "<document version='1'>" + content + "</document>"; }
    private static String item(String id, String list, int level, int restart, String text) {
        return "<numbered id='" + id + "' list-id='" + list + "' level='" + level + "' restart='" + restart + "'>" + text + "</numbered>";
    }
    private static String ref(String id, String target, int width) {
        return "<p id='" + id + "'><number-reference target='" + target + "' width='" + width + "'/></p>";
    }
    private static String label(int level) { return String.join(".", java.util.Collections.nCopies(level, "1")); }
    private static XmlTemplate compiler() { return XmlTemplate.builder().pageSettings(PAGE).bodyFormat(BODY).build(); }
    private static Document document(String content) { return compiler().compile(xml(content)).render(Map.of()); }
    private static DocumentEngine engine() { return DocumentEngine.builder(regular).build(); }
    private static List<ParagraphFragment> fragments(ComponentDocumentLayout result) {
        return result.body().pages().stream().flatMap(page -> page.fragments().stream()).toList();
    }
    private static ParagraphFragment fragment(ComponentDocumentLayout result, String id) {
        return fragments(result).stream().filter(value -> value.blockId().equals(id)).findFirst().orElseThrow();
    }
    private static String logical(ParagraphLayout paragraph) {
        return paragraph.lines().stream().map(line -> line.outlines().isEmpty()
                ? line.runs().stream().map(run -> run.text()).collect(Collectors.joining())
                : line.outlines().stream().map(outline -> outline.logicalText()).collect(Collectors.joining()))
                .collect(Collectors.joining());
    }
    private static List<String> texts(ComponentDocumentLayout result) {
        return fragments(result).stream().map(value -> logical(value.layout())).toList();
    }
    private static String nineLevels(boolean styled, boolean references, int lastWidth) {
        var source = new StringBuilder();
        for (int level = 1; level <= 9; level++) {
            String text = styled ? "<span role='strong' color='#224488'>Level" + level + "</span>" : "A";
            source.append(item("n" + level, "steps", level, 0, text));
        }
        if (references) {
            for (int level = 1; level <= 9; level++) { source.append(ref("r" + level, "n" + level, level == 9 ? lastWidth : 100)); }
        }
        return source.toString();
    }

    @Test void allNineLevelsRenderFullLabelsIndentationPhysicalStylesAndResolvedReferences() {
        var source = document(nineLevels(true, true, 100));
        var result = DocumentEngine.builder(regular).fontRole("strong", bold).build().layout(source);
        assertEquals(1, result.body().pages().size()); assertEquals(18, fragments(result).size());
        for (int level = 1; level <= 9; level++) {
            var numbered = fragment(result, "n" + level);
            assertEquals(label(level) + ". Level" + level, logical(numbered.layout()));
            assertEquals((level - 1) * 15.0, numbered.layout().lines().getFirst().offsetX(), 0.0001);
            assertEquals(level - 1, numbered.blockIndex()); assertEquals(0, numbered.firstLineIndex());
            assertEquals(0, numbered.layout().lines().getFirst().sourceStart());
            assertEquals((label(level) + ". Level" + level).length(), numbered.layout().lines().getFirst().sourceEnd());
            var reference = fragment(result, "r" + level);
            assertEquals(label(level), logical(reference.layout()));
            assertEquals(0, reference.layout().lines().getFirst().sourceStart());
            assertEquals(1, reference.layout().lines().getFirst().sourceEnd());
            var link = result.display().extras().links().get(level - 1);
            assertEquals(numbered.origin(), link.destination());
            assertEquals(new Rect(reference.origin().x(), reference.origin().y(), 100, 18), link.bounds());
        }
        var runs = result.display().pages().stream().flatMap(page -> page.commands().stream())
                .filter(DrawCommand.GlyphText.class::isInstance).map(DrawCommand.GlyphText.class::cast).toList();
        var styled = runs.stream().filter(value -> value.run().text().startsWith("Level")).toList();
        assertEquals(9, styled.size());
        assertTrue(styled.stream().allMatch(value -> value.run().font().id().equals(bold.fonts().getFirst().data().id()) && value.run().rgb() == 0x224488));
        assertTrue(runs.stream().filter(value -> !value.run().text().startsWith("Level"))
                .allMatch(value -> value.run().font().id().equals(regular.fonts().getFirst().data().id()) && value.run().rgb() == 0));
        assertEquals(9, result.display().extras().links().size());
        assertTrue(result.display().extras().links().stream().allMatch(link -> link.pageIndex() == 0 && link.targetPageIndex() == 0));
        assertTrue(fragments(result).stream().flatMap(value -> value.layout().lines().stream()).allMatch(line -> line.fields().isEmpty()));
    }

    @Test void parentAndChildRestartsClearOnlyTheirDescendantsAndReferencesKeepHistoricalLabels() {
        String source = item("a", "steps", 1, 3, "A") + item("b", "steps", 2, 5, "B") + item("c", "steps", 3, 0, "C")
                + item("d", "steps", 2, 8, "D") + item("e", "steps", 3, 0, "E") + item("f", "steps", 1, 0, "F")
                + item("g", "steps", 2, 0, "G") + item("h", "steps", 3, 2, "H") + ref("old", "c", 100) + ref("new", "h", 100);
        assertEquals(List.of("3. A", "3.5. B", "3.5.1. C", "3.8. D", "3.8.1. E", "4. F", "4.1. G", "4.1.2. H", "3.5.1", "4.1.2"),
                texts(engine().layout(document(source))));
    }

    @Test void missingParentsSkippedLevelsAndIndependentListsFailDuringLayoutBeforeBackendOrBytes() {
        for (String source : List.of(item("child", "steps", 2, 7, "A"),
                item("root", "steps", 1, 0, "A") + item("child", "steps", 3, 0, "B"),
                item("root", "a", 1, 0, "A") + item("child", "b", 2, 0, "B"),
                item("root", "steps", 1, 0, "A") + item("child", "steps", 2, 0, "B")
                        + item("grandchild", "steps", 3, 0, "C") + item("reset", "steps", 1, 2, "D")
                        + item("orphan", "steps", 3, 7, "E"))) {
            var bound = document(source);
            var failure = assertThrows(IllegalArgumentException.class, () -> engine().layout(bound));
            assertEquals("numbered list cannot skip levels", failure.getMessage());
            assertNoOutput(bound, IllegalArgumentException.class);
        }
        var valid = document(item("a", "a", 1, 0, "A") + item("b", "b", 1, 4, "B")
                + item("c", "a", 2, 0, "C") + item("d", "b", 2, 0, "D"));
        assertEquals(List.of("1. A", "4. B", "1.1. C", "4.1. D"), texts(engine().layout(valid)));
    }

    @Test void validButUnparentedHiddenAndZeroIterationItemsFailOnlyWhenActuallyBoundIntoLayout() {
        var condition = compiler().compile(xml("<p id='safe'>Safe</p><if test='visible'><then>" + item("child", "steps", 9, 0, "A") + "</then></if>"));
        assertEquals(List.of("Safe"), texts(engine().layout(condition.render(Map.of("visible", false)))));
        assertThrows(IllegalArgumentException.class, () -> engine().layout(condition.render(Map.of("visible", true))));
        var loop = compiler().compile(xml("<p id='safe'>Safe</p><each items='items' as='entry'>" + item("child", "steps", 2, 0, "${entry}") + "</each>"));
        assertEquals(List.of("Safe"), texts(engine().layout(loop.render(Map.of("items", List.of())))));
        assertThrows(IllegalArgumentException.class, () -> engine().layout(loop.render(Map.of("items", List.of("A")))));
    }

    @Test void selectedParentsAndEmptyParentIterationsAreValidatedAfterActualControlFlowExpansion() {
        var conditional = compiler().compile(xml("<if test='visible'><then>" + item("parent", "steps", 1, 3, "Parent")
                + "</then></if>" + item("child", "steps", 2, 0, "Child") + ref("ref", "child", 100)));
        assertEquals(List.of("3. Parent", "3.1. Child", "3.1"), texts(engine().layout(conditional.render(Map.of("visible", true)))));
        assertThrows(IllegalArgumentException.class, () -> engine().layout(conditional.render(Map.of("visible", false))));
        var repeatedParents = compiler().compile(xml("<each items='items' as='entry'>" + item("parent", "steps", 1, 0, "${entry}")
                + "</each>" + item("child", "steps", 2, 0, "Child") + ref("ref", "child", 100)));
        assertEquals(List.of("1. A", "2. B", "2.1. Child", "2.1"), texts(engine().layout(repeatedParents.render(Map.of("items", List.of("A", "B"))))));
        assertThrows(IllegalArgumentException.class, () -> engine().layout(repeatedParents.render(Map.of("items", List.of()))));
    }

    @Test void iterationReferencesUseFullLabelsWhileIncludePrefixesOwnIndependentListCounters() throws IOException {
        String part = "<fragment version='1'>" + item("root", "checks", 1, 0, "Root") + item("child", "checks", 2, 0, "Child")
                + ref("ref", "child", 100) + "</fragment>";
        var compiler = XmlTemplate.builder().pageSettings(PAGE).bodyFormat(BODY).fragment("part", part).build();
        var source = compiler.compile(xml(item("main", "checks", 1, 7, "Main")
                + "<each items='items' as='entry'><include ref='part' prefix='copy'/></each>"
                + "<include ref='part' prefix='other'/>" + item("tail", "checks", 1, 0, "Main"))).render(Map.of("items", List.of("A", "B")));
        var items = source.blocks().stream().filter(NumberedItem.class::isInstance).map(NumberedItem.class::cast).toList();
        assertEquals(List.of("main", "copy-root@0", "copy-child@0", "copy-root@1", "copy-child@1", "other-root", "other-child", "tail"), items.stream().map(NumberedItem::id).toList());
        assertEquals(List.of("checks", "copy-checks", "copy-checks", "copy-checks", "copy-checks", "other-checks", "other-checks", "checks"), items.stream().map(NumberedItem::listId).toList());
        assertEquals(List.of("copy-child@0", "copy-child@1", "other-child"), source.blocks().stream()
                .filter(Paragraph.class::isInstance).map(Paragraph.class::cast).map(value -> value.fields().getFirst().field().targetId()).toList());
        var result = engine().layout(source);
        assertEquals(List.of("7. Main", "1. Root", "1.1. Child", "1.1", "2. Root", "2.1. Child", "2.1", "1. Root", "1.1. Child", "1.1", "8. Main"), texts(result));
        assertEquals(3, result.display().extras().links().size());
        assertDocxRoundTrip(source);
        var unparented = XmlTemplate.builder().pageSettings(PAGE).bodyFormat(BODY)
                .fragment("child", "<fragment version='1'>" + item("child", "checks", 2, 0, "Child") + "</fragment>").build()
                .compile(xml(item("main", "checks", 1, 0, "Main") + "<include ref='child' prefix='copy'/>")).render(Map.of());
        assertThrows(IllegalArgumentException.class, () -> engine().layout(unparented));
    }

    @Test void nestedIterationsRelocateBothAncestorAndChildReferenceTargetsWithoutResettingSharedLists() {
        String part = "<fragment version='1'><each items='groups' as='group'>" + item("parent", "steps", 1, 0, "${group.name}")
                + "<each items='group.children' as='child'>" + item("child", "steps", 2, 0, "${child}")
                + "<p id='ref'><number-reference target='parent' width='100'/>/<number-reference target='child' width='100'/></p>"
                + "</each></each></fragment>";
        var compiled = XmlTemplate.builder().pageSettings(PAGE).bodyFormat(BODY).fragment("part", part).build().compile(xml("<include ref='part' prefix='copy'/>"));
        var source = compiled.render(Map.of("groups", List.of(Map.of("name", "A", "children", List.of("X", "Y")), Map.of("name", "B", "children", List.of("Z")))));
        var result = engine().layout(source);
        assertEquals(List.of("1. A", "1.1. X", "1/1.1", "1.2. Y", "1/1.2", "2. B", "2.1. Z", "2/2.1"), texts(result));
        assertEquals(List.of("copy-parent@0", "copy-child@0@0", "copy-ref@0@0", "copy-child@0@1", "copy-ref@0@1", "copy-parent@1", "copy-child@1@0", "copy-ref@1@0"),
                source.blocks().stream().map(value -> value.id()).toList());
        assertEquals(List.of(List.of("copy-parent@0", "copy-child@0@0"), List.of("copy-parent@0", "copy-child@0@1"), List.of("copy-parent@1", "copy-child@1@0")),
                source.paragraphs().stream().map(value -> value.fields().stream().map(span -> span.field().targetId()).toList()).toList());
        assertEquals(6, result.display().extras().links().size());
    }

    @Test void integerMaximumCountersRenderExactlyAndContinuationOverflowFailsBeforeOutputAtAnyDepth() {
        String maximum = item("maximum", "steps", 1, Integer.MAX_VALUE, "A");
        assertEquals(List.of("2147483647. A", "2147483647"), texts(engine().layout(document(maximum + ref("ref", "maximum", 100)))));
        var overflow = document(maximum + item("overflow", "steps", 1, 0, "B"));
        assertThrows(ArithmeticException.class, () -> engine().layout(overflow)); assertNoOutput(overflow, ArithmeticException.class);
        assertEquals(List.of("2147483647. A", "1. B"), texts(engine().layout(document(maximum + item("reset", "steps", 1, 1, "B")))));
        var deep = new StringBuilder();
        for (int level = 1; level <= 8; level++) { deep.append(item("n" + level, "steps", level, 0, "A")); }
        deep.append(item("maximum", "steps", 9, Integer.MAX_VALUE, "A"));
        assertEquals(label(8) + ".2147483647. A", texts(engine().layout(document(deep.toString()))).getLast());
        var deepOverflow = document(deep + item("overflow", "steps", 9, 0, "B"));
        assertThrows(ArithmeticException.class, () -> engine().layout(deepOverflow)); assertNoOutput(deepOverflow, ArithmeticException.class);
    }

    @Test void allNineXmlLevelsAndCompleteReferenceModelsSurviveStrictDocxRoundTrip() throws IOException {
        var source = document(nineLevels(true, true, 100));
        assertDocxRoundTrip(source);
        assertDocxRoundTrip(document(item("a", "steps", 1, 3, "A") + item("b", "steps", 2, 5, "B")
                + item("c", "steps", 3, 0, "C") + item("d", "steps", 2, 8, "D") + item("e", "steps", 3, 0, "E")
                + item("f", "steps", 1, 0, "F") + item("g", "steps", 2, 0, "G") + ref("old", "c", 100)));
    }

    @Test void docxRejectsExpandedMissingParentsAndCounterOverflowBeforeWritingAnyPackageBytes() {
        var missing = document(item("parent", "a", 1, 0, "Parent") + item("child", "b", 2, 7, "Child"));
        var missingOutput = new ByteArrayOutputStream();
        var failure = assertThrows(IOException.class, () -> new DocxAdapter().write(missing, missingOutput));
        assertEquals("numbered list skips levels", failure.getMessage()); assertEquals(0, missingOutput.size());
        var overflow = document(item("maximum", "steps", 1, Integer.MAX_VALUE, "A") + item("overflow", "steps", 1, 0, "B"));
        var overflowOutput = new ByteArrayOutputStream();
        assertThrows(ArithmeticException.class, () -> new DocxAdapter().write(overflow, overflowOutput)); assertEquals(0, overflowOutput.size());
    }

    @Test void fullDeepReferenceValuesMustFitTheirExplicitReservedWidthBeforeAnyOutput() {
        var source = document(nineLevels(false, true, 1));
        var failure = assertThrows(DocumentLayoutException.class, () -> engine().layout(source));
        assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, failure.code()); assertEquals("r9", failure.blockId());
        assertNoOutput(source, DocumentLayoutException.class);
        assertEquals(label(9), logical(fragment(engine().layout(document(nineLevels(false, true, 100))), "r9").layout()));
    }

    @Test void loweredSourceBudgetsCountEveryGeneratedNumberPrefixBeforeLayout() {
        var source = document(nineLevels(false, false, 0));
        var result = limited(source, new DocumentLayoutLimits(9, 108, 9, 1));
        assertEquals(9, fragments(result).size());
        assertEquals(108, texts(result).stream().mapToInt(String::length).sum());
        var failure = assertThrows(DocumentLayoutException.class, () -> limited(source, new DocumentLayoutLimits(9, 107, 9, 1)));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
    }

    @Test void finalOutputBudgetCountsEveryCompleteGeneratedReferenceValue() {
        var source = document(nineLevels(false, true, 100));
        var result = limited(source, new DocumentLayoutLimits(18, 189, 18, 1));
        assertEquals(18, fragments(result).size()); assertEquals(1, result.body().pages().size());
        assertEquals(189, texts(result).stream().mapToInt(String::length).sum());
        var failure = assertThrows(IllegalArgumentException.class, () -> limited(source, new DocumentLayoutLimits(18, 188, 18, 1)));
        assertEquals(IllegalArgumentException.class, failure.getClass());
        assertEquals("sectioned document combined output budget exceeded", failure.getMessage());
        for (var limits : List.of(new DocumentLayoutLimits(17, 189, 18, 1), new DocumentLayoutLimits(18, 189, 17, 1))) {
            var budgetFailure = assertThrows(DocumentLayoutException.class, () -> limited(source, limits));
            assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, budgetFailure.code());
        }
    }

    @Test void highLevelIndentationCannotBeSilentlyShrunkToFitAnInsufficientContentWidth() {
        var page = PageSettings.builder().size(152, 360).margins(16).build();
        var source = XmlTemplate.builder().pageSettings(page).bodyFormat(BODY).build().compile(xml(nineLevels(false, false, 0))).render(Map.of());
        var failure = assertThrows(DocumentLayoutException.class, () -> engine().layout(source));
        assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, failure.code()); assertEquals("n9", failure.blockId());
        assertNoOutput(source, DocumentLayoutException.class);
    }

    @Test void missingGlyphDiagnosticsKeepLoweredUtf16SourceAndTheNumberedBlockIdentity() {
        var source = document(item("parent", "steps", 1, 0, "Parent") + item("child", "steps", 2, 0, "A😀Z"));
        var item = (NumberedItem) source.blocks().getLast();
        assertEquals("A😀Z", item.text()); assertEquals("A😀Z", item.inlines().getFirst().text());
        var result = DocumentEngine.builder(FontGroup.placeholder(regular.fonts(), '?')).build().layout(source);
        var warning = result.contextualDiagnostics().getFirst();
        assertEquals("child", warning.blockId()); assertEquals(0, warning.pageIndex()); assertEquals("BODY", warning.region());
        assertEquals(6, warning.diagnostic().sourceStart()); assertEquals(8, warning.diagnostic().sourceEnd());
        assertEquals(0x1f600, warning.diagnostic().sourceCodePoint());
        var line = fragment(result, "child").layout().lines().getFirst();
        assertEquals(0, line.sourceStart()); assertEquals(9, line.sourceEnd());
    }

    @Test void oneNumberedBodyContinuesAcrossPagesWithoutRepeatingPrefixOrLosingLineSourcesAndTargets() {
        var page = PageSettings.builder().size(220, 104).margins(16).build();
        String body = String.join("\n", List.of("LineA", "LineB", "LineC", "LineD", "LineE", "LineF", "LineG", "LineH", "LineI"));
        String content = item("parent", "steps", 1, 3, "Parent") + item("child", "steps", 2, 4, body)
                + item("tail", "steps", 2, 0, "Tail") + ref("ref", "child", 100);
        var source = XmlTemplate.builder().pageSettings(page).bodyFormat(BODY).build().compile(xml(content)).render(Map.of());
        var result = engine().layout(source);
        assertTrue(result.body().pages().size() >= 3);
        var parts = fragments(result).stream().filter(value -> value.blockId().equals("child")).toList();
        assertTrue(parts.size() >= 3);
        assertEquals("3.4. " + body.replace("\n", ""), parts.stream().map(value -> logical(value.layout())).collect(Collectors.joining()));
        String lowered = "3.4. " + body;
        int firstLine = 0; int sourceOffset = 0;
        for (var part : parts) {
            assertEquals(1, part.blockIndex()); assertEquals(firstLine, part.firstLineIndex());
            for (var line : part.layout().lines()) {
                assertEquals(sourceOffset, line.sourceStart());
                int end = lowered.indexOf('\n', sourceOffset);
                if (end < 0) { end = lowered.length(); }
                assertEquals(end, line.sourceEnd());
                sourceOffset = end < lowered.length() ? end + 1 : end;
                assertEquals(15, line.offsetX(), 0.0001);
            }
            firstLine += part.layout().lines().size();
        }
        assertEquals(9, firstLine); assertEquals(("3.4. " + body).length(), sourceOffset);
        assertEquals("3.5. Tail", logical(fragment(result, "tail").layout()));
        assertEquals("3.4", logical(fragment(result, "ref").layout()));
        var link = result.display().extras().links().getFirst();
        assertTrue(link.pageIndex() > 0); assertEquals(0, link.targetPageIndex()); assertEquals(parts.getFirst().origin(), link.destination());
        var reference = fragment(result, "ref");
        assertEquals(new Rect(reference.origin().x(), reference.origin().y(), 100, 18), link.bounds());
        var limited = assertThrows(DocumentLayoutException.class, () -> limited(source, new DocumentLayoutLimits(4, 1000, 20, 2)));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, limited.code());
    }

    @Test void cancellationDuringNumberedBodyLayoutRestoresScopeAndKeepsSharedEngineAndTemplateReusable() {
        var compiled = compiler().compile(xml(nineLevels(false, true, 100))); var source = compiled.render(Map.of()); var engine = engine();
        var cancelled = new AtomicBoolean(); var observed = new ArrayList<String>();
        var options = ExecutionOptions.builder().cancelled(cancelled::get).progress(progress -> {
            if (progress.stage().equals("BODY")) { observed.add(progress.blockId()); if (progress.blockId().equals("n5")) { cancelled.set(true); } }
        }).build();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options, () -> engine.layout(source)));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code()); assertEquals("n5", failure.context().blockId()); assertTrue(observed.contains("n5"));
        ExecutionContext.checkpoint(); assertEquals(source, compiled.render(Map.of()));
        assertEquals(label(9), logical(fragment(engine.layout(source), "r9").layout()));
    }

    @Test void sharedNumberedListContinuesAcrossSectionAndDisplayedPageNumberRestarts() {
        var source = document(item("root", "steps", 1, 3, "A") + item("child", "steps", 2, 4, "B")
                + item("next", "steps", 2, 0, "C") + ref("ref", "next", 100));
        var sectioned = new SectionedDocument(source.metadata(), List.of(new Section("a", PAGE, source.blocks().subList(0, 2), 0),
                new Section("b", PAGE, source.blocks().subList(2, 4), 9)));
        var result = engine().layout(sectioned);
        assertEquals(2, result.body().pages().size()); assertEquals(List.of("3. A", "3.4. B", "3.5. C", "3.5"), texts(result));
        assertEquals(1, result.display().extras().links().getFirst().pageIndex()); assertEquals(1, result.display().extras().links().getFirst().targetPageIndex());
    }

    private static ComponentDocumentLayout limited(Document source, DocumentLayoutLimits limits) {
        return new ComponentDocumentRenderer(limits, ComponentLimits.DEFAULT).layout(source, PageTemplate.EMPTY, Navigation.EMPTY, regular);
    }

    private static void assertDocxRoundTrip(Document source) throws IOException {
        var output = new ByteArrayOutputStream(); new DocxAdapter().write(source, output);
        var restored = new DocxAdapter().read(new ByteArrayInputStream(output.toByteArray()), DocxAdapter.Policy.STRICT);
        assertTrue(restored.diagnostics().isEmpty());
        assertEquals(source.metadata(), restored.document().metadata());
        assertEquals(source.pageSettings(), restored.document().sections().getFirst().pageSettings());
        assertEquals(source.blocks().stream().map(XmlNumberedLevelsIntegrationTest::canonicalNumberedDefaultRun).toList(),
                restored.document().sections().getFirst().blocks().stream().map(XmlNumberedLevelsIntegrationTest::canonicalNumberedDefaultRun).toList());
    }

    private static Block canonicalNumberedDefaultRun(Block block) {
        if (block instanceof NumberedItem item
                && item.inlines().equals(List.of(new InlineText(item.text(), InlineStyle.of(item.format().fontSize()))))) {
            // The DOCX reader stores this exact complete default run as plain numbered text.
            return new NumberedItem(item.id(), item.listId(), item.text(), item.restartAt(), item.format(), item.level());
        }
        return block;
    }
    private static <T extends Throwable> void assertNoOutput(Document source, Class<T> type) {
        var called = new AtomicBoolean(); var output = new ByteArrayOutputStream();
        assertThrows(type, () -> engine().export(source).backend((display, target) -> { called.set(true); target.write(42); }).to(output));
        assertFalse(called.get()); assertEquals(0, output.size());
    }
}
