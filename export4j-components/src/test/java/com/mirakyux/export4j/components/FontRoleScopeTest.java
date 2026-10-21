// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.PageBand;
import com.mirakyux.export4j.layout.core.PageableComponent;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.layout.core.Placement;
import com.mirakyux.export4j.layout.core.Position;
import com.mirakyux.export4j.layout.core.RegionFlow;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FontRoleScopeTest {
    private static final ParagraphStyle STYLE = ParagraphStyle.builder().fontSize(10).lineHeight(20).build();
    private static final RgbColor BLUE = new RgbColor(0, 0, 1);
    private static FontGroup regular;
    private static FontGroup bold;
    private static FontGroup italic;

    @BeforeAll static void loadFonts() throws IOException {
        regular = font("LiberationSans-Regular.ttf");
        bold = font("LiberationSans-Bold.ttf");
        italic = font("LiberationSans-Italic.ttf");
    }

    @Test void namedFontAndItsMissingGlyphPolicyReplaceOnlyTheRegionalDefault() {
        var replacement = FontGroup.placeholder(bold.fonts(), '?');
        var context = context(100, Map.of("brand", replacement));
        var part = new FontRoleScope(TextComponent.builder("A😀B").build(), "brand").layout(context);
        assertEquals(bold.fonts().getFirst().data().id(), glyphs(part).getFirst().run().font().id());
        var warning = part.diagnostics().stream().filter(item -> item.code() == TextDiagnostic.Code.MISSING_GLYPH_REPLACED)
                .findFirst().orElseThrow();
        assertEquals(1, warning.sourceStart());
        assertEquals(3, warning.sourceEnd());
        assertEquals(0x1f600, warning.sourceCodePoint());
        assertEquals(bold.fonts().getFirst().data().id(), warning.fontId());
        assertEquals(regular, context.fontGroup());
        assertEquals(Map.of("brand", replacement), context.fontRoles());
        var strict = context.withFonts(FontGroup.placeholder(regular.fonts(), '?'));
        strict = new ComponentContext(strict.width(), strict.height(), strict.fontGroup(), 0, 0, 1, Map.of("brand", bold));
        var strictContext = strict;
        assertEquals(TextLayoutException.Code.MISSING_GLYPH, assertThrows(TextLayoutException.class,
                () -> new FontRoleScope(TextComponent.builder("😀").build(), "brand").layout(strictContext)).code());
    }

    @Test void unknownRoleFailsBeforeAnyChildCallbackForEveryEntryPoint() {
        var calls = new AtomicInteger();
        Component child = viewport -> { calls.incrementAndGet(); return new Spacer(1).layout(viewport); };
        var scope = new FontRoleScope(child, "unknown");
        var context = context(100, Map.of("brand", bold));
        assertThrows(IllegalArgumentException.class, () -> scope.layout(context));
        assertThrows(IllegalArgumentException.class, () -> scope.layoutParts(context));
        assertThrows(IllegalArgumentException.class, () -> scope.layoutParts(context, 20));
        assertThrows(IllegalArgumentException.class, () -> scope.layoutRegions(context, new FlowRegions(List.of(5.0, 20.0), 100)));
        assertEquals(0, calls.get());
    }

    @Test void builderValuesAreIndependentAndNamesAreValidatedWithoutChangingTheOldScope() {
        var content = TextComponent.builder("SOURCE").build();
        var builder = FontRoleScope.builder(content).fontRole("brand");
        var source = builder.build();
        builder.fontRole("italic").content(TextComponent.builder("LATER").build());
        assertSame(content, source.content());
        assertEquals("brand", source.fontRole());
        assertEquals(source, source.toBuilder().build());
        assertEquals("italic", source.toBuilder().fontRole("italic").build().fontRole());
        for (String name : List.of("", " ", "\t\n")) {
            assertThrows(IllegalArgumentException.class, () -> new FontRoleScope(content, name));
        }
        assertThrows(NullPointerException.class, () -> new FontRoleScope(null, "brand"));
        assertThrows(NullPointerException.class, () -> new FontRoleScope(content, null));
        var context = context(100, Map.of());
        var old = new FontScope(content, bold);
        assertEquals(content.layout(context.withFonts(bold)), old.layout(context));
        assertEquals(old, old.toBuilder().build());
        assertEquals(2, FontScope.class.getRecordComponents().length);
    }

    @Test void roleAndStyleScopesPreserveTheFinalContextInEitherOrder() {
        for (boolean roleOutside : List.of(true, false)) {
            var seen = new ArrayList<ComponentContext>();
            Component probe = viewport -> {
                seen.add(viewport);
                return TextComponent.builder("SCOPED").build().layout(viewport);
            };
            Component scoped = roleOutside ? new FontRoleScope(new StyleScope(probe, STYLE, BLUE), "brand")
                    : new StyleScope(new FontRoleScope(probe, "brand"), STYLE, BLUE);
            var original = new ComponentContext(240, 100, regular, 2, 5, 9, Map.of("brand", bold));
            var part = scoped.layout(original);
            assertEquals(1, seen.size());
            assertEquals(original.withFonts(bold).withTextDefaults(STYLE, BLUE), seen.getFirst());
            assertEquals(20, part.height());
            assertTrue(glyphs(part).stream().allMatch(run -> run.color().equals(BLUE) && run.run().fontSize() == 10));
            assertEquals(regular, original.fontGroup());
        }
    }

    @Test void innerFontSelectionWinsAcrossExplicitAndNamedScopes() {
        var content = TextComponent.builder("FONT").build();
        var context = context(100, Map.of("brand", bold, "italic", italic));
        List<Component> scopes = List.of(new FontRoleScope(new FontScope(content, italic), "brand"),
                new FontScope(new FontRoleScope(content, "italic"), bold),
                new FontRoleScope(new FontRoleScope(content, "italic"), "brand"));
        for (var scope : scopes) {
            assertEquals(italic.fonts().getFirst().data().id(), glyphs(scope.layout(context)).getFirst().run().font().id());
        }
    }

    @Test void shortRegionsRemainOrderedAndSourceIsNeitherReshapedNorRepeated() {
        String source = IntStream.rangeClosed(1, 7).mapToObj(index -> "LINE" + index).collect(Collectors.joining("\n"));
        var content = StyleScope.builder(FlowText.builder(source).build()).style(STYLE).color(BLUE).build();
        var scope = new FontRoleScope(content, "brand");
        var context = context(80, Map.of("brand", bold));
        var regions = new FlowRegions(List.of(5.0, 40.0), 80);
        var parts = scope.layoutRegions(context, regions);
        assertEquals(content.layoutRegions(context.withFonts(bold), regions), parts);
        assertEquals(List.of(1, 2, 3), parts.stream().map(RegionFlow.Fragment::regionIndex).toList());
        assertEquals(List.of(40.0, 80.0, 20.0), parts.stream().map(part -> part.layout().height()).toList());
        assertEquals(source.replace("\n", ""), parts.stream().map(part -> logical(part.layout())).collect(Collectors.joining()));
    }

    @Test void atomicWidthsAndColumnAlignmentSurviveRoleAndStyleScopes() {
        var mark = CheckMark.builder().size(9).build();
        var scope = new FontRoleScope(StyleScope.builder(mark).style(STYLE).build(), "brand");
        var context = context(100, Map.of("brand", bold));
        assertEquals(9, scope.layout(context).width());
        assertEquals(List.of(9.0), scope.layoutParts(context, 4).stream().map(ComponentLayout::width).toList());
        var parts = scope.layoutRegions(context, new FlowRegions(List.of(4.0, 10.0), 100));
        assertEquals(List.of(1), parts.stream().map(RegionFlow.Fragment::regionIndex).toList());
        assertEquals(9, parts.getFirst().layout().width());
        var column = FlowColumn.builder().alignment(FlowColumn.Alignment.CENTER).add(scope).build().layout(context);
        var transform = column.commands().stream().filter(DrawCommand.Transform.class::isInstance)
                .map(DrawCommand.Transform.class::cast).findFirst().orElseThrow();
        assertEquals(115.5, transform.matrix().e());
    }

    @Test void legacyPageableChildrenKeepAtomicWidthsAndUseAllShortRegions() {
        var calls = new AtomicInteger();
        PageableComponent child = viewport -> {
            calls.incrementAndGet();
            assertEquals(bold, viewport.fontGroup());
            return List.of(CheckMark.builder().size(9).build().layout(viewport), CheckMark.builder().size(12).build().layout(viewport));
        };
        var scope = new FontRoleScope(child, "brand");
        var context = context(20, Map.of("brand", bold));
        var parts = scope.layoutRegions(context, new FlowRegions(List.of(4.0, 10.0, 5.0), 20));
        assertEquals(1, calls.get());
        assertEquals(List.of(1, 3), parts.stream().map(RegionFlow.Fragment::regionIndex).toList());
        assertEquals(List.of(9.0, 12.0), parts.stream().map(part -> part.layout().width()).toList());
        assertThrows(IllegalArgumentException.class, () -> scope.layout(context));
    }

    @Test void semanticSourcesKeepTheirRealFontPolicyAndCompleteFragmentSequence() {
        var builder = Table.builder("source").cellFormat(new CellFormat(10, 20, com.mirakyux.export4j.model.ParagraphAlignment.LEFT)).header("H");
        for (int index = 0; index < 20; index++) { builder.row("R" + index); }
        var table = builder.build();
        var replacement = FontGroup.placeholder(bold.fonts(), '?');
        var context = context(200, Map.of("brand", replacement));
        var scope = new FontRoleScope(new SemanticTable(table), "brand");
        var parts = scope.layoutRegions(context, new FlowRegions(List.of(96.0, 96.0), 200));
        assertEquals(List.of(0, 1), parts.subList(0, 2).stream().map(RegionFlow.Fragment::regionIndex).toList());
        var placements = parts.stream().flatMap(part -> part.layout().semanticPlacements().stream()).toList();
        for (int index = 0; index < placements.size(); index++) {
            var placement = placements.get(index);
            assertSame(table, placement.source());
            assertEquals(replacement, placement.fonts());
            assertEquals(index, placement.fragmentIndex());
            assertEquals(placements.size(), placement.fragmentCount());
        }
        parts.forEach(part -> assertThrows(IllegalStateException.class, part.layout()::commands));
    }

    @Test void finalPageNumbersUseTheNamedFontAfterContentsOffsetAndSectionRestart() {
        var page = new PageSettings(new PageSize(300, 240), PageMargins.uniform(10));
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("section", page,
                List.of(new Paragraph("first", "FIRST", ParagraphFormat.of(10)), new PageBreak("break"),
                        new Paragraph("second", "SECOND", ParagraphFormat.of(10))), 7)));
        var pageNumber = new FontRoleScope(PageNumberComponent.builder("{page}/{pages}").build(), "brand");
        var template = new PageTemplate(new PageBand(30, 0, List.of(new Placement(pageNumber,
                new Position.Fractional(0, 0, 1, 1)))), PageBand.EMPTY, List.of(), List.of());
        var navigation = Navigation.builder().bookmark("First", "first", 1)
                .contents(new TableOfContents(true, "Contents", ParagraphStyle.of(10))).build();
        var result = DocumentEngine.builder(regular).fontRole("brand", bold).build()
                .layout(source, List.of(SectionTemplate.uniform(template)), navigation, List.of());
        assertEquals(1, result.bodyPageOffset());
        assertEquals(List.of("1/3", "7/3", "8/3"), result.decorations().stream().map(item -> logical(item.layout())).toList());
        assertTrue(result.decorations().stream().flatMap(item -> glyphs(item.layout()).stream())
                .allMatch(run -> run.run().font().id().equals(bold.fonts().getFirst().data().id())));
    }

    @Test void cooperativeCancellationInsideScopedFlowRestoresTheExecutionState() throws IOException {
        var scope = new FontRoleScope(FlowText.builder("CANCEL ".repeat(2_000)).build(), "brand");
        var context = context(100, Map.of("brand", bold));
        var checks = new AtomicInteger();
        var options = ExecutionOptions.builder().cancelled(() -> checks.incrementAndGet() > 6).build();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options,
                () -> scope.layoutRegions(context, new FlowRegions(List.of(5.0, 40.0), 100))));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        assertTrue(checks.get() > 6);
        ExecutionContext.checkpoint();
        assertEquals("RECOVERED", logical(new FontRoleScope(TextComponent.builder("RECOVERED").build(), "brand").layout(context)));
    }

    private static ComponentContext context(double height, Map<String, FontGroup> roles) {
        return new ComponentContext(240, height, regular, 0, 0, 1, roles);
    }

    private static FontGroup font(String name) throws IOException {
        try (var input = FontRoleScopeTest.class.getResourceAsStream("/fonts/" + name)) {
            if (input == null) { throw new IOException("missing test font"); }
            return FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    private static List<DrawCommand.GlyphText> glyphs(ComponentLayout part) {
        return part.commands().stream().filter(DrawCommand.GlyphText.class::isInstance).map(DrawCommand.GlyphText.class::cast).toList();
    }

    private static String logical(ComponentLayout part) {
        return part.commands().stream().map(command -> {
            if (command instanceof DrawCommand.GlyphText text) { return text.run().text(); }
            if (command instanceof DrawCommand.OutlineText text) { return text.logicalText(); }
            return "";
        }).collect(Collectors.joining());
    }
}
