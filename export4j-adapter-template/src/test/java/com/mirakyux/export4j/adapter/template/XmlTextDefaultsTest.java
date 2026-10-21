// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.text.TextDiagnostic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlTextDefaultsTest {
    private static final PageSettings PAGE = PageSettings.builder().size(300, 260).margins(10).build();
    private static final ParagraphFormat FORMAT = ParagraphFormat.builder().fontSize(10).lineHeight(24).spaceAfter(0).build();
    private static final XmlTemplate.TextDefaults BODY = new XmlTemplate.TextDefaults("brand", 0x224488);
    private static final XmlTemplate.TextDefaults CELL = new XmlTemplate.TextDefaults("cell", 0x882244);
    private static FontGroup regular;
    private static FontGroup bold;

    @BeforeAll static void fonts() throws IOException {
        regular = font("LiberationSans-Regular.ttf");
        bold = font("LiberationSans-Bold.ttf");
    }

    @Test void explicitlyEmptyDefaultsKeepLegacyDocumentsAndActualLayoutExactly() {
        String source = xml("<p>A<span>B</span></p><heading level='1' size='10' line-height='24'>Heading</heading>"
                + "<numbered list-id='list'>Number</numbered><bullet>Bullet</bullet>" + table("table", "<cell>Cell</cell>"));
        var plain = base().build().compile(source).render(Map.of());
        var explicit = base().bodyText(XmlTemplate.TextDefaults.DEFAULT).cellText(XmlTemplate.TextDefaults.DEFAULT)
                .headingText(1, XmlTemplate.TextDefaults.DEFAULT).build().compile(source).render(Map.of());
        assertEquals(plain, explicit);
        var engine = DocumentEngine.builder(regular).build();
        assertEquals(engine.layout(plain), engine.layout(explicit));
        assertEquals(XmlTemplate.TextDefaults.DEFAULT, XmlTemplate.TextDefaults.builder().build());
    }

    @Test void bodyListAndHeadingDefaultsAreIndependentAndMarkersUseTheBlockAppearance() {
        var compiler = base().bodyText(BODY).headingText(1, value -> value.fontRole("heading").rgb(0x446622)).build();
        var document = compiler.compile(xml("<p id='body'>Body</p><numbered id='n' list-id='list'>Number</numbered><bullet id='b'>Bullet</bullet>"
                + "<heading id='h1' level='1' size='10'>H1</heading><heading id='h2' level='2' size='10'>H2</heading>")).render(Map.of());
        assertAppearance(((Paragraph) document.blocks().getFirst()).inlines(), BODY, 10);
        var numbered = (NumberedItem) document.blocks().get(1);
        var bullet = (BulletItem) document.blocks().get(2);
        assertEquals(style(BODY, 10), numbered.markerStyle());
        assertEquals(style(BODY, 10), bullet.markerStyle());
        assertAppearance(numbered.inlines(), BODY, 10);
        assertAppearance(bullet.inlines(), BODY, 10);
        assertAppearance(((Heading) document.blocks().get(3)).inlines(), new XmlTemplate.TextDefaults("heading", 0x446622), 10);
        assertAppearance(((Heading) document.blocks().get(4)).inlines(), XmlTemplate.TextDefaults.DEFAULT, 10);
    }

    @Test void everyHeadingLevelAcceptsItsOwnDefaultsAndItsLocalOverrides() {
        var builder = base().bodyText(BODY);
        var source = new StringBuilder();
        for (int level = 1; level <= 6; level++) {
            int selected = level;
            builder.headingText(level, value -> value.fontRole("heading" + selected).rgb(selected));
            source.append("<heading level='").append(level).append("' size='10'>H</heading>");
        }
        var document = builder.build().compile(xml(source.toString())).render(Map.of());
        for (int level = 1; level <= 6; level++) {
            var heading = (Heading) document.blocks().get(level - 1);
            assertAppearance(heading.inlines(), new XmlTemplate.TextDefaults("heading" + level, level), 10);
        }
        var reset = builder.build().compile(xml("<heading level='3' size='10' role='' color='#000000'>Clear</heading>")).render(Map.of());
        assertAppearance(((Heading) reset.blocks().getFirst()).inlines(), XmlTemplate.TextDefaults.DEFAULT, 10);
    }

    @Test void explicitEmptyRoleAndBlackOverrideOnlyTheirOwnProperty() {
        var document = base().bodyText(BODY).build().compile(xml("<p role=''>Role</p><p color='#000000'>Color</p>"
                + "<p role='' color='#000000'>Both</p><p role='local' color='#abcdef'>Local</p>")).render(Map.of());
        assertAppearance(document.paragraphs().get(0).inlines(), new XmlTemplate.TextDefaults("", BODY.rgb()), 10);
        assertAppearance(document.paragraphs().get(1).inlines(), new XmlTemplate.TextDefaults("brand", 0), 10);
        assertAppearance(document.paragraphs().get(2).inlines(), XmlTemplate.TextDefaults.DEFAULT, 10);
        assertAppearance(document.paragraphs().get(3).inlines(), new XmlTemplate.TextDefaults("local", 0xabcdef), 10);
    }

    @Test void unstyledSpansLinksAndFieldsInheritTheLocalBlockAppearance() {
        var document = base().bodyText(BODY).build().compile(xml("<p id='p' role='local' color='#123456'>A<span>B</span>"
                + "<link href='https://example.com'>C<span>D</span></link><page-number width='20'/></p>")).render(Map.of());
        var paragraph = document.paragraphs().getFirst();
        assertEquals("ABCD\ufffc", paragraph.text());
        assertAppearance(paragraph.inlines(), new XmlTemplate.TextDefaults("local", 0x123456), 10);
        assertEquals(List.of("", "", "https://example.com", "https://example.com", ""), paragraph.inlines().stream().map(InlineText::hyperlink).toList());
        assertEquals(style(new XmlTemplate.TextDefaults("local", 0x123456), 10), paragraph.fields().getFirst().field().style());
    }

    @Test void namedSpanIsCompleteWhileNamedParagraphChangesOnlyGeometry() {
        var ink = new InlineStyle("ink", 8, 0x446622, 2, true, true);
        var named = ParagraphFormat.builder().fontSize(14).lineHeight(28).spaceAfter(0).build();
        var theme = DocumentTheme.builder().paragraphStyle("note", named).inlineStyle("ink", ink).build();
        var paragraph = base().bodyText(BODY).theme(theme).build().compile(xml("<p style='note'>A<span style='ink'>B</span>"
                + "<span style='ink' role='' color='#000000' size='9'>C</span>D</p>")).render(Map.of()).paragraphs().getFirst();
        assertEquals(named, paragraph.format());
        assertEquals(style(BODY, 14), paragraph.inlines().get(0).style());
        assertEquals(ink, paragraph.inlines().get(1).style());
        assertEquals(new InlineStyle("", 9, 0, 2, true, true), paragraph.inlines().get(2).style());
        assertEquals(style(BODY, 14), paragraph.inlines().get(3).style());
    }

    @Test void geometryAndThemeSettersPreserveAllAppearanceDefaultsAndSnapshots() {
        var builder = base().bodyText(BODY).cellText(CELL).headingText(1, BODY);
        var theme = DocumentTheme.builder().bodyFormat(format -> format.fontSize(13).lineHeight(26))
                .cellFormat(new CellFormat(11, 24, com.mirakyux.export4j.model.ParagraphAlignment.LEFT)).build();
        builder.theme(theme).bodyFormat(ParagraphFormat.of(12)).cellFormat(CellFormat.of(9));
        var compiler = builder.build();
        builder.bodyText(XmlTemplate.TextDefaults.DEFAULT).cellText(XmlTemplate.TextDefaults.DEFAULT).headingText(1, XmlTemplate.TextDefaults.DEFAULT);
        var document = compiler.compile(xml("<p>Body</p><heading level='1' size='10'>Heading</heading>" + table("table", "<cell>Cell</cell>"))).render(Map.of());
        assertAppearance(document.paragraphs().getFirst().inlines(), BODY, 12);
        assertAppearance(((Heading) document.blocks().get(1)).inlines(), BODY, 10);
        assertAppearance(((Table) document.blocks().get(2)).rows().getFirst().cells().getFirst().inlines(), CELL, 9);
    }

    @Test void cellLocalDefaultsReachBlockParagraphsAndInnerTablesRestartTheGlobalCellDefaults() {
        String inner = table("inner", "<cell>Inner</cell>");
        String source = table("outer", "<cell role='outer' color='#123456'><p id='before'>Before</p>" + inner
                + "<p id='clear' role='' color='#000000'>Clear</p><p id='after'>After</p></cell>");
        var table = (Table) base().bodyText(BODY).cellText(CELL).build().compile(xml(source)).render(Map.of()).blocks().getFirst();
        var children = table.rows().getFirst().cells().getFirst().blocks();
        var outer = new XmlTemplate.TextDefaults("outer", 0x123456);
        assertAppearance(((Paragraph) children.get(0)).inlines(), outer, 10);
        assertAppearance(((Table) children.get(1)).rows().getFirst().cells().getFirst().inlines(), CELL, 10);
        assertAppearance(((Paragraph) children.get(2)).inlines(), XmlTemplate.TextDefaults.DEFAULT, 10);
        assertAppearance(((Paragraph) children.get(3)).inlines(), outer, 10);
    }

    @Test void dynamicRowsHeadersAndEmptyCellsKeepTheirOwnAppearance() {
        String source = "<rows items='items' as='item'><columns><column weight='1'/></columns><header><cell>Head</cell></header>"
                + "<row><cell role='local' color='#123456'><span>${item}</span></cell></row></rows>";
        var table = (Table) base().cellText(CELL).build().compile(xml(source)).render(Map.of("items", List.of("Alpha", "Beta"))).blocks().getFirst();
        assertAppearance(table.rows().getFirst().cells().getFirst().inlines(), CELL, 10);
        for (var row : table.rows().subList(1, 3)) {
            assertAppearance(row.cells().getFirst().inlines(), new XmlTemplate.TextDefaults("local", 0x123456), 10);
        }
        var empty = (Table) base().cellText(CELL).build().compile(xml(table("empty", "<cell/>"))).render(Map.of()).blocks().getFirst();
        assertEquals("", empty.rows().getFirst().cells().getFirst().text());
        assertAppearance(empty.rows().getFirst().cells().getFirst().inlines(), CELL, 10);
    }

    @Test void allFourFieldsInheritAppearanceAndResolveWithoutChangingTheirSourcePositions() {
        String content = "<numbered id='n' list-id='list'>Number</numbered><p id='target'>Target</p><p id='fields'>"
                + "<page-number width='20'/><page-count width='20'/><page-reference target='target' width='20'/>"
                + "<number-reference target='n' width='20'/></p>";
        var document = base().bodyText(BODY).build().compile(xml(content)).render(Map.of());
        var paragraph = document.paragraphs().getLast();
        assertEquals("\ufffc\ufffc\ufffc\ufffc", paragraph.text());
        assertEquals(List.of(0, 1, 2, 3), paragraph.fields().stream().map(Paragraph.FieldSpan::sourceIndex).toList());
        assertEquals(List.of(InlineField.Kind.PAGE, InlineField.Kind.PAGES, InlineField.Kind.REFERENCE_PAGE, InlineField.Kind.REFERENCE_NUMBER),
                paragraph.fields().stream().map(field -> field.field().kind()).toList());
        assertTrue(paragraph.fields().stream().allMatch(field -> field.field().style().equals(style(BODY, 10))));
        var result = DocumentEngine.builder(regular).fontRole("brand", bold).build().layout(document);
        String logical = result.display().pages().stream().flatMap(page -> page.commands().stream()).map(command -> {
            if (command instanceof DrawCommand.GlyphText text) { return text.run().text(); }
            if (command instanceof DrawCommand.OutlineText text) { return text.logicalText(); }
            return "";
        }).collect(Collectors.joining());
        assertTrue(logical.endsWith("1111"));
        assertEquals(2, result.display().extras().links().size());
        assertTrue(result.contextualDiagnostics().isEmpty());
    }

    @Test void imageObjectMarkerKeepsItsDedicatedFormatInsteadOfUsingTheTextRole() throws IOException {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        var resource = ImageResource.load(bytes.toByteArray());
        var document = base().bodyText(BODY).image("mark", resource).build()
                .compile(xml("<p id='image'><image ref='mark' width='8' height='9' alt='Mark'/></p>")).render(Map.of());
        var paragraph = document.paragraphs().getFirst();
        assertEquals("\ufffc", paragraph.text());
        assertEquals(InlineStyle.of(10), paragraph.inlines().getFirst().style());
        assertEquals(0, paragraph.images().getFirst().sourceIndex());
        var result = DocumentEngine.builder(regular).build().layout(document);
        assertEquals(1, result.display().pages().stream().flatMap(page -> page.commands().stream()).filter(DrawCommand.Image.class::isInstance).count());
        assertTrue(result.contextualDiagnostics().isEmpty());
    }

    @Test void configuredBodyAppearanceUsesTheRealNamedFontAndKeepsItsMissingGlyphUtf16Policy() {
        var document = base().bodyText(BODY).build().compile(xml("<p id='body'>A😀B</p>")).render(Map.of());
        var engine = DocumentEngine.builder(regular).fontRole("brand", FontGroup.placeholder(bold.fonts(), '?')).build();
        var result = engine.layout(document);
        var glyphs = result.display().pages().stream().flatMap(page -> page.commands().stream())
                .filter(DrawCommand.GlyphText.class::isInstance).map(DrawCommand.GlyphText.class::cast).toList();
        assertFalse(glyphs.isEmpty());
        assertTrue(glyphs.stream().allMatch(run -> run.run().font().id().equals(bold.fonts().getFirst().data().id()) && run.run().rgb() == BODY.rgb()));
        var warning = result.contextualDiagnostics().stream().filter(value -> value.diagnostic().code() == TextDiagnostic.Code.MISSING_GLYPH_REPLACED)
                .findFirst().orElseThrow();
        assertEquals("body", warning.blockId());
        assertEquals(1, warning.diagnostic().sourceStart());
        assertEquals(3, warning.diagnostic().sourceEnd());
        assertEquals(bold.fonts().getFirst().data().id(), warning.diagnostic().fontId());
        var strict = DocumentEngine.builder(FontGroup.placeholder(regular.fonts(), '?')).fontRole("brand", bold).build();
        assertEquals(1, assertThrows(DocumentLayoutException.class, () -> strict.layout(document)).sourceIndex());
    }

    @Test void validUnregisteredRolesRemainInTheModelAndFailAtTheOriginalEmptyOrNonemptyBlock() {
        for (String body : List.of("SECRET", "", "<span/>")) {
            var document = base().bodyText(new XmlTemplate.TextDefaults("unregistered", 0x123456)).build()
                    .compile(xml("<p id='unknown'>" + body + "</p>")).render(Map.of());
            assertAppearance(document.paragraphs().getFirst().inlines(), new XmlTemplate.TextDefaults("unregistered", 0x123456), 10);
            var failure = assertThrows(DocumentLayoutException.class, () -> DocumentEngine.builder(regular).build().layout(document));
            assertEquals("unknown", failure.blockId());
            assertEquals(0, failure.sourceIndex());
            assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, failure.code());
            assertFalse(failure.getMessage().contains("SECRET"));
        }
    }

    @Test void injectedDataStaysLiteralAndNeverReplacesTheConfiguredAppearance() {
        String injected = "</p><p role='evil' color='#ff0000'>${unbound}&text";
        var document = base().bodyText(BODY).build().compile(xml("<p>${value}</p>")).render(Map.of("value", injected));
        assertEquals(1, document.blocks().size());
        assertEquals(injected, document.paragraphs().getFirst().text());
        assertAppearance(document.paragraphs().getFirst().inlines(), BODY, 10);
    }

    @Test void defaultsAndLocalOverridesSurviveBothBranchesEachAndOfflineFragments() {
        String part = "<fragment version='1'><p id='p'>${item}</p><numbered id='n' list-id='list' role='local' color='#123456'>${item}</numbered></fragment>";
        String source = "<if test='flag'><then><p id='yes'>Yes</p></then><else><p id='no' role='' color='#000000'>No</p></else></if>"
                + "<each items='items' as='item'><include ref='part' prefix='loop'/></each>";
        var compiled = base().bodyText(BODY).fragment("part", part).build().compile(xml(source));
        var document = compiled.render(Map.of("flag", true, "items", List.of("Alpha", "Beta")));
        assertEquals(List.of("yes", "loop-p@0", "loop-n@0", "loop-p@1", "loop-n@1"), document.blocks().stream().map(block -> block.id()).toList());
        assertAppearance(document.paragraphs().getFirst().inlines(), BODY, 10);
        assertAppearance(document.paragraphs().get(1).inlines(), BODY, 10);
        assertEquals(style(new XmlTemplate.TextDefaults("local", 0x123456), 10), ((NumberedItem) document.blocks().get(2)).markerStyle());
        var no = compiled.render(Map.of("flag", false, "items", List.of()));
        assertAppearance(no.paragraphs().getFirst().inlines(), XmlTemplate.TextDefaults.DEFAULT, 10);
    }

    @Test void invalidRoleOrColorFailsDuringCompilationEvenInInactiveBranchesAndFragments() {
        for (String attributes : List.of("role='bad-role'", "role='${role}'", "color='#abc'", "color='${color}'")) {
            var compiler = base().bodyText(BODY).build();
            String bad = "<p " + attributes + ">SECRET</p>";
            for (String container : List.of(bad, "<if test='flag'><then><p>Safe</p></then><else>" + bad + "</else></if>",
                    "<each items='items' as='item'>" + bad + "</each>")) {
                var failure = assertThrows(TemplateException.class, () -> compiler.compile(xml(container)));
                assertEquals(TemplateException.Code.INVALID_VALUE, failure.code());
                assertFalse(failure.getMessage().contains("SECRET"));
            }
            var fragmentCompiler = base().fragment("bad", "<fragment version='1'>" + bad + "</fragment>").build();
            var fragment = assertThrows(TemplateException.class, () -> fragmentCompiler.compile(xml("<include ref='bad' prefix='x'/>")));
            assertEquals("bad", fragment.fragmentName().orElseThrow());
        }
    }

    @Test void textDefaultsAndHeadingLevelsValidateAtTheConfigurationBoundary() {
        assertEquals(BODY, BODY.toBuilder().build());
        assertEquals(new XmlTemplate.TextDefaults("", BODY.rgb()), BODY.toBuilder().fontRole("").build());
        for (String name : List.of(" ", "0bad", "bad-role", "${role}", "a/b", "a".repeat(257))) {
            assertThrows(IllegalArgumentException.class, () -> new XmlTemplate.TextDefaults(name, 0));
            assertThrows(IllegalArgumentException.class, () -> XmlTemplate.TextDefaults.builder().fontRole(name));
        }
        assertEquals("a".repeat(256), new XmlTemplate.TextDefaults("a".repeat(256), 0xffffff).fontRole());
        assertThrows(NullPointerException.class, () -> new XmlTemplate.TextDefaults(null, 0));
        assertThrows(IllegalArgumentException.class, () -> new XmlTemplate.TextDefaults("", -1));
        assertThrows(IllegalArgumentException.class, () -> new XmlTemplate.TextDefaults("", 0x1000000));
        for (int level : List.of(0, 7)) {
            var calls = new AtomicInteger();
            assertThrows(IllegalArgumentException.class, () -> base().headingText(level, BODY));
            assertThrows(IllegalArgumentException.class, () -> base().headingText(level, value -> calls.incrementAndGet()));
            assertEquals(0, calls.get());
        }
    }

    @Test void callbacksSnapshotSynchronousEditsAndFailedEditsLeaveTheBuilderUnchanged() {
        var retained = new AtomicReference<XmlTemplate.TextDefaults.Builder>();
        var builder = base().bodyText(value -> { retained.set(value); value.fontRole("brand").rgb(0x224488); })
                .cellText(CELL).headingText(1, BODY);
        var compiler = builder.build();
        retained.get().fontRole("later").rgb(0xff0000);
        assertAppearance(compiler.compile(xml("<p>Body</p>")).render(Map.of()).paragraphs().getFirst().inlines(), BODY, 10);
        var before = builder.build().compile(xml("<p>Body</p><heading level='1' size='10'>Heading</heading>" + table("t", "<cell>Cell</cell>"))).render(Map.of());
        assertThrows(IllegalStateException.class, () -> builder.bodyText(value -> { value.rgb(0xff0000); throw new IllegalStateException("stop"); }));
        assertThrows(IllegalArgumentException.class, () -> builder.cellText(value -> value.fontRole("bad-role")));
        assertThrows(IllegalStateException.class, () -> builder.headingText(1, value -> { value.fontRole("later"); throw new IllegalStateException("stop"); }));
        var after = builder.build().compile(xml("<p>Body</p><heading level='1' size='10'>Heading</heading>" + table("t", "<cell>Cell</cell>"))).render(Map.of());
        assertEquals(before, after);
    }

    @Test void existingParseAndBindingBudgetsRemainEffectiveWithAppearanceDefaults() {
        String source = xml("<p>X</p>");
        var exact = base().bodyText(BODY).limits(new XmlTemplate.Limits(source.length(), 2, 2, 1))
                .bindingLimits(new DocumentTemplate.Limits(100, 1, 1, 1, 8)).build();
        assertAppearance(exact.compile(source).render(Map.of()).paragraphs().getFirst().inlines(), BODY, 10);
        var tooShort = base().bodyText(BODY).limits(new XmlTemplate.Limits(source.length() - 1, 2, 2, 1)).build();
        assertEquals(TemplateException.Code.BUDGET_EXCEEDED, assertThrows(TemplateException.class, () -> tooShort.compile(source)).code());
        var tooMany = base().bodyText(BODY).bindingLimits(new DocumentTemplate.Limits(100, 1, 1, 100, 8)).build()
                .compile(xml("<p>X</p><p>Y</p>"));
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentTemplate.BindingException.class, () -> tooMany.render(Map.of())).code());
    }

    @Test void cancellationDuringConfiguredCompilationRestoresExecutionAndCompilerReuse() throws IOException {
        var compiler = base().bodyText(BODY).build();
        var checks = new AtomicInteger();
        var options = ExecutionOptions.builder().cancelled(() -> checks.incrementAndGet() > 8).build();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options,
                () -> compiler.compile(xml("<p>X</p>".repeat(50)))));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        assertTrue(checks.get() > 8);
        ExecutionContext.checkpoint();
        assertAppearance(compiler.compile(xml("<p>Recovered</p>")).render(Map.of()).paragraphs().getFirst().inlines(), BODY, 10);
    }

    private static XmlTemplate.Builder base() { return XmlTemplate.builder().pageSettings(PAGE).bodyFormat(FORMAT).cellFormat(new CellFormat(10, 24, com.mirakyux.export4j.model.ParagraphAlignment.LEFT)); }
    private static String xml(String content) { return "<document version='1'>" + content + "</document>"; }
    private static String table(String id, String cells) { return "<table id='" + id + "'><columns><column weight='1'/></columns><row>" + cells + "</row></table>"; }
    private static InlineStyle style(XmlTemplate.TextDefaults appearance, double size) { return new InlineStyle(appearance.fontRole(), size, appearance.rgb(), 0, false, false); }

    private static void assertAppearance(List<InlineText> runs, XmlTemplate.TextDefaults appearance, double size) {
        assertFalse(runs.isEmpty());
        assertTrue(runs.stream().allMatch(run -> run.style().equals(style(appearance, size))));
    }

    private static FontGroup font(String name) throws IOException {
        try (var input = XmlTextDefaultsTest.class.getResourceAsStream("/fonts/" + name)) {
            if (input == null) { throw new IOException("missing test font"); }
            return FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }
}
