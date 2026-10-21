// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.PageBand;
import com.mirakyux.export4j.layout.core.PageComposer;
import com.mirakyux.export4j.layout.core.PageComposition;
import com.mirakyux.export4j.layout.core.PageableComponent;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.layout.core.Placement;
import com.mirakyux.export4j.layout.core.Position;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextLayoutException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageRegionFontRolesTest {
    private static final PageSettings PAGE = new PageSettings(new PageSize(300, 240), PageMargins.uniform(10));
    private static FontGroup regular;
    private static FontGroup bold;
    private static FontGroup italic;

    @BeforeAll static void loadFonts() throws IOException {
        regular = font("LiberationSans-Regular.ttf");
        bold = font("LiberationSans-Bold.ttf");
        italic = font("LiberationSans-Italic.ttf");
    }

    @Test void engineRolesReachHeaderFooterWatermarkAndForegroundWhileTheDefaultFontRemainsIndependent() {
        var seen = new ArrayList<ComponentContext>();
        var template = new PageTemplate(band(roleText(context -> "Header", seen::add)),
                band(roleText(context -> "Footer", seen::add)),
                List.of(overlay(roleText(context -> "Watermark", seen::add))),
                List.of(overlay(roleText(context -> "Foreground", seen::add))));
        var result = DocumentEngine.builder(regular).fontRole("brand", bold).pageTemplate(template).build().layout(document());
        assertEquals(4, seen.size());
        for (var context : seen) {
            assertEquals(regular, context.fontGroup());
            assertEquals(Map.of("brand", bold), context.fontRoles());
            assertEquals(0, context.pageIndex());
            assertEquals(1, context.pageCount());
            assertEquals(1, context.displayPageNumber());
        }
        assertEquals(List.of(PageComposition.Region.BACKGROUND, PageComposition.Region.HEADER,
                PageComposition.Region.FOOTER, PageComposition.Region.FOREGROUND),
                result.decorations().stream().map(PageComposition.Placed::region).toList());
        assertEquals(List.of("Watermark", "Header", "Footer", "Foreground"),
                result.decorations().stream().map(placed -> text(placed.layout())).toList());
        result.decorations().forEach(placed -> assertFont(placed.layout(), bold));
        var bodyRun = glyphs(result.display().pages().getFirst().commands()).stream()
                .filter(value -> value.run().text().equals("Body")).findFirst().orElseThrow();
        assertEquals(regular.fonts().getFirst().data().id(), bodyRun.run().font().id());
        assertEquals(new Rect(10, 40, 280, 160), result.body().pages().getFirst().contentBounds());
        assertTrue(result.contextualDiagnostics().isEmpty());
    }

    @Test void rolesAndFinalPageContextSurviveContentsOffsetFirstOddEvenVariantsAndSectionNumberRestart() {
        var first = new Section("a", PAGE, threePages("a"), 0);
        var second = new Section("b", PAGE, threePages("b"), 8);
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(first, second));
        var seen = new ArrayList<ComponentContext>();
        var templates = List.of(variants("A", seen::add), variants("B", seen::add));
        var navigation = Navigation.builder().bookmark("Section A", "a1", 1).bookmark("Section B", "b1", 1)
                .contents(new TableOfContents(true, "Contents", ParagraphStyle.of(10))).build();
        var result = DocumentEngine.builder(regular).fontRole("brand", bold).build()
                .layout(source, templates, navigation, List.of());
        assertEquals(1, result.bodyPageOffset());
        assertEquals(7, result.display().pages().size());
        assertEquals(List.of("A-ODD 1/7", "A-FIRST 2/7", "A-ODD 3/7", "A-EVEN 4/7",
                "B-FIRST 8/7", "B-ODD 9/7", "B-EVEN 10/7"),
                result.decorations().stream().map(placed -> text(placed.layout())).toList());
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6), seen.stream().map(ComponentContext::pageIndex).toList());
        assertEquals(List.of(1, 2, 3, 4, 8, 9, 10), seen.stream().map(ComponentContext::displayPageNumber).toList());
        assertTrue(seen.stream().allMatch(context -> context.pageCount() == 7 && context.fontRoles().equals(Map.of("brand", bold))));
        result.decorations().forEach(placed -> assertFont(placed.layout(), bold));
        assertEquals(List.of(1, 4), result.display().extras().bookmarks().stream().map(bookmark -> bookmark.pageIndex()).toList());
    }

    @Test void fontAndStyleScopesPreserveRolesViewportAndFinalContextInEitherNestingOrder() {
        var style = ParagraphStyle.builder().fontSize(14).lineHeight(24).build();
        var color = new RgbColor(0.2, 0.4, 0.6);
        for (boolean fontOutside : List.of(true, false)) {
            var seen = new ArrayList<ComponentContext>();
            var probe = roleText(context -> "Scoped", context -> {
                seen.add(context);
                assertEquals(italic, context.fontGroup());
                assertEquals(Map.of("brand", bold), context.fontRoles());
                assertEquals(style, context.defaultTextStyle());
                assertEquals(color, context.defaultTextColor());
                assertEquals(1, context.pageCount());
                assertEquals(1, context.displayPageNumber());
                assertEquals(context.fontRoles(), context.viewport(100, 24).fontRoles());
            });
            Component scope = fontOutside ? new FontScope(new StyleScope(probe, style, color), italic)
                    : new StyleScope(new FontScope(probe, italic), style, color);
            var result = DocumentEngine.builder(regular).fontRole("brand", bold).pageTemplate(header(scope)).build().layout(document());
            assertEquals(1, seen.size());
            var layout = result.decorations().getFirst().layout();
            assertFont(layout, bold);
            for (var glyph : glyphs(layout.commands())) {
                assertEquals(14, glyph.run().fontSize());
                assertEquals(color, glyph.color());
            }
        }
    }

    @Test void directCompositionSnapshotsTheRoleMapBeforeAnyPlacementAndExposesAnImmutableContext() {
        var roles = new HashMap<String, FontGroup>();
        roles.put("brand", bold);
        var calls = new AtomicInteger();
        Component probe = roleText(context -> "Role", context -> {
            calls.incrementAndGet();
            assertEquals(Map.of("brand", bold), context.fontRoles());
            roles.clear();
            roles.put("brand", italic);
            roles.put("later", regular);
            assertThrows(UnsupportedOperationException.class, () -> context.fontRoles().put("injected", regular));
        });
        var template = new PageTemplate(new PageBand(30, 0, List.of(
                new Placement(probe, new Position.Fractional(0, 0, .5, 1)),
                new Placement(probe, new Position.Fractional(.5, 0, .5, 1)))), PageBand.EMPTY, List.of(), List.of());
        var composer = new PageComposer();
        var composed = composer.composeDetailed(blank(2), specs(template, 2), regular, roles);
        assertEquals(4, calls.get());
        composed.placements().forEach(placed -> assertFont(placed.layout(), bold));
        assertEquals(Map.of("brand", italic, "later", regular), roles);
        var fresh = composer.composeDetailed(blank(1), specs(header(roleText(context -> "New", ignored -> { })), 1), regular, roles);
        assertFont(fresh.placements().getFirst().layout(), italic);
    }

    @Test void rendererSnapshotsRolesBeforeBodyCallbacksSoBodyAndFinalPageRegionsUseTheSameFonts() {
        var roles = new HashMap<String, FontGroup>();
        roles.put("brand", bold);
        Component body = roleText(context -> "BodyRole", context -> {
            assertEquals(Map.of("brand", bold), context.fontRoles());
            roles.clear();
            roles.put("brand", italic);
        });
        var source = new SectionedDocument(DocumentMetadata.EMPTY,
                List.of(new Section("section", PAGE, List.of(new ComponentBlock("body", body)), 0)));
        var result = new ComponentDocumentRenderer().layout(source,
                List.of(SectionTemplate.uniform(header(roleText(context -> "HeaderRole", context -> assertEquals(bold, context.fontRoles().get("brand")))))),
                Navigation.EMPTY, regular, roles, List.of());
        assertEquals(Map.of("brand", italic), roles);
        assertEquals(2, glyphs(result.display().pages().getFirst().commands()).size());
        for (var glyph : glyphs(result.display().pages().getFirst().commands())) {
            assertEquals(bold.fonts().getFirst().data().id(), glyph.run().font().id());
        }
    }

    @Test void concurrentEngineLayoutsKeepIndependentRoleRegistrationsAndFinalContexts() throws Exception {
        var template = header(roleText(context -> "Page " + context.displayPageNumber() + "/" + context.pageCount(),
                context -> assertEquals(regular, context.fontGroup())));
        var first = DocumentEngine.builder(regular).fontRole("brand", bold).pageTemplate(template).build();
        var second = DocumentEngine.builder(regular).fontRole("brand", italic).pageTemplate(template).build();
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(4);
        var futures = new ArrayList<Future<ComponentDocumentLayout>>();
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (int index = 0; index < 24; index++) {
                var engine = index % 2 == 0 ? first : second;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return engine.layout(document());
                }));
            }
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            for (int index = 0; index < futures.size(); index++) {
                var result = futures.get(index).get(10, TimeUnit.SECONDS);
                assertEquals("Page 1/1", text(result.decorations().getFirst().layout()));
                assertFont(result.decorations().getFirst().layout(), index % 2 == 0 ? bold : italic);
                assertTrue(result.contextualDiagnostics().isEmpty());
            }
        }
    }

    @Test void everyLegacyComposerOverloadKeepsAnEmptyRoleMapAndItsExistingDrawing() {
        var calls = new AtomicInteger();
        Component legacy = context -> {
            calls.incrementAndGet();
            assertTrue(context.fontRoles().isEmpty());
            return TextComponent.builder("Legacy").build().layout(context);
        };
        var template = header(legacy);
        var composer = new PageComposer();
        var body = blank(1);
        var pageSpecs = specs(template, 1);
        var settings = composer.composeDetailed(body, PAGE, template, regular.fonts());
        var faces = composer.composeDetailed(body, pageSpecs, regular.fonts());
        var group = composer.composeDetailed(body, pageSpecs, regular);
        var explicit = composer.composeDetailed(body, pageSpecs, regular, Map.of());
        var display = composer.compose(body, PAGE, template, regular.fonts());
        assertEquals(5, calls.get());
        assertEquals(settings, faces);
        assertEquals(settings, group);
        assertEquals(settings, explicit);
        assertEquals(settings.display(), display);
        assertFont(settings.placements().getFirst().layout(), regular);
    }

    @Test void roleMapNullsFailBeforeInvokingAnyCustomPageComponent() {
        var calls = new AtomicInteger();
        var template = header(context -> {
            calls.incrementAndGet();
            return TextComponent.builder("Never").build().layout(context);
        });
        var composer = new PageComposer();
        assertThrows(NullPointerException.class, () -> composer.composeDetailed(blank(1), specs(template, 1), regular, null));
        var nullKey = new HashMap<String, FontGroup>();
        nullKey.put(null, bold);
        var nullValue = new HashMap<String, FontGroup>();
        nullValue.put("brand", null);
        for (var roles : List.of(nullKey, nullValue)) {
            assertThrows(NullPointerException.class, () -> composer.composeDetailed(blank(1), specs(template, 1), regular, roles));
        }
        assertEquals(0, calls.get());
    }

    @Test void roleMissingGlyphPolicyRetainsPageRegionUtf16DiagnosticsAndDoesNotUseTheDefaultPolicy() {
        var replacement = FontGroup.placeholder(bold.fonts(), '?');
        var template = header(roleText(context -> "A😀B", ignored -> { }));
        var result = DocumentEngine.builder(regular).fontRole("brand", replacement).pageTemplate(template).build().layout(document());
        var warnings = result.contextualDiagnostics().stream()
                .filter(value -> value.diagnostic().code() == TextDiagnostic.Code.MISSING_GLYPH_REPLACED).toList();
        assertEquals(1, warnings.size());
        var warning = warnings.getFirst();
        assertEquals("HEADER", warning.region());
        assertEquals(0, warning.pageIndex());
        assertEquals(1, warning.diagnostic().sourceStart());
        assertEquals(3, warning.diagnostic().sourceEnd());
        assertEquals(0x1f600, warning.diagnostic().sourceCodePoint());
        assertEquals(bold.fonts().getFirst().data().id(), warning.diagnostic().fontId());
        assertFont(result.decorations().getFirst().layout(), bold);
        var strictRole = DocumentEngine.builder(FontGroup.placeholder(regular.fonts(), '?'))
                .fontRole("brand", bold).pageTemplate(template).build();
        var failure = assertThrows(TextLayoutException.class, () -> strictRole.layout(document()));
        assertEquals(TextLayoutException.Code.MISSING_GLYPH, failure.code());
    }

    @Test void addingRoleContextDoesNotAllowAMultiFragmentComponentInAFixedPageRegion() {
        PageableComponent split = context -> {
            assertEquals(bold, context.fontRoles().get("brand"));
            var part = TextComponent.builder("Part").build().layout(context.withFonts(context.fontRoles().get("brand")));
            return List.of(part, part);
        };
        var failure = assertThrows(IllegalArgumentException.class, () -> DocumentEngine.builder(regular).fontRole("brand", bold)
                .pageTemplate(header(split)).build().layout(document()));
        assertTrue(failure.getMessage().contains("flow pagination"));
    }

    @Test void fixedPageRegionsContinueToRejectDeferredSemanticTableContent() {
        var table = Table.builder("semantic").cellFormat(CellFormat.of(10)).row("Deferred").build();
        var semantic = new SemanticTable(table);
        for (var region : PageComposition.Region.values()) {
            var template = switch (region) {
                case HEADER -> new PageTemplate(new PageBand(60, 0, List.of(full(semantic))), PageBand.EMPTY, List.of(), List.of());
                case FOOTER -> new PageTemplate(PageBand.EMPTY, new PageBand(60, 0, List.of(full(semantic))), List.of(), List.of());
                case BACKGROUND -> new PageTemplate(PageBand.EMPTY, PageBand.EMPTY, List.of(overlay(semantic)), List.of());
                case FOREGROUND -> new PageTemplate(PageBand.EMPTY, PageBand.EMPTY, List.of(), List.of(overlay(semantic)));
            };
            var failure = assertThrows(IllegalStateException.class, () -> DocumentEngine.builder(regular).fontRole("brand", bold)
                    .pageTemplate(template).build().layout(document()));
            assertTrue(failure.getMessage().contains("semantic composition"));
        }
    }

    private static FontGroup font(String name) throws IOException {
        try (var input = PageRegionFontRolesTest.class.getResourceAsStream("/fonts/" + name)) {
            if (input == null) { throw new IOException("missing test font"); }
            return FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    private static Component roleText(Function<ComponentContext, String> value, Consumer<ComponentContext> observer) {
        return context -> {
            observer.accept(context);
            var selected = context.fontRoles().get("brand");
            if (selected == null) { throw new IllegalArgumentException("missing named page role"); }
            return TextComponent.builder(value.apply(context)).build().layout(context.withFonts(selected));
        };
    }

    private static PageBand band(Component component) { return new PageBand(30, 0, List.of(full(component))); }
    private static PageTemplate header(Component component) { return new PageTemplate(band(component), PageBand.EMPTY, List.of(), List.of()); }
    private static Placement full(Component component) { return new Placement(component, new Position.Fractional(0, 0, 1, 1)); }
    private static Placement overlay(Component component) { return new Placement(component, new Position.Absolute(new Rect(10, 80, 280, 60))); }
    private static Document document() { return new Document(DocumentMetadata.EMPTY, PAGE, List.of(new Paragraph("body", "Body", ParagraphFormat.of(10)))); }

    private static List<Block> threePages(String prefix) {
        return List.of(new Paragraph(prefix + "1", "First", ParagraphFormat.of(10)), new PageBreak(prefix + "-break1"),
                new Paragraph(prefix + "2", "Second", ParagraphFormat.of(10)), new PageBreak(prefix + "-break2"),
                new Paragraph(prefix + "3", "Third", ParagraphFormat.of(10)));
    }

    private static SectionTemplate variants(String prefix, Consumer<ComponentContext> observer) {
        return new SectionTemplate(variant(prefix + "-FIRST", observer), variant(prefix + "-ODD", observer), variant(prefix + "-EVEN", observer));
    }

    private static PageTemplate variant(String label, Consumer<ComponentContext> observer) {
        return header(roleText(context -> label + " " + context.displayPageNumber() + "/" + context.pageCount(), observer));
    }

    private static DisplayDocument blank(int pages) {
        return new DisplayDocument(DocumentMetadata.EMPTY,
                java.util.stream.IntStream.range(0, pages).mapToObj(ignored -> new DisplayPage(PAGE.size(), List.of())).toList());
    }

    private static List<PageComposer.PageSpec> specs(PageTemplate template, int pages) {
        return java.util.stream.IntStream.range(0, pages).mapToObj(index -> new PageComposer.PageSpec(PAGE, template, index + 1)).toList();
    }

    private static List<DrawCommand.GlyphText> glyphs(List<DrawCommand> commands) {
        return commands.stream().filter(DrawCommand.GlyphText.class::isInstance).map(DrawCommand.GlyphText.class::cast).toList();
    }

    private static String text(ComponentLayout layout) {
        return glyphs(layout.commands()).stream().map(glyph -> glyph.run().text()).collect(Collectors.joining());
    }

    private static void assertFont(ComponentLayout layout, FontGroup expected) {
        var drawn = glyphs(layout.commands());
        assertFalse(drawn.isEmpty());
        drawn.forEach(glyph -> assertEquals(expected.fonts().getFirst().data().id(), glyph.run().font().id()));
    }
}
