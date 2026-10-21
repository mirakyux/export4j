// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Navigation;
import com.mirakyux.export4j.components.PageRegionTemplates;
import com.mirakyux.export4j.components.SectionTemplate;
import com.mirakyux.export4j.components.TableOfContents;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.text.ParagraphStyle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlPageRegionsTest {
    private static final String PAGE = "<page width='240' height='160' margin='10'/>";
    private static final String BANDS = "<header height='20' gap='5'><p id='h'>HEAD-${name}</p></header>"
            + "<footer height='20' gap='5'><p id='f'>PAGE <page-number width='20'/> OF <page-count width='20'/></p></footer>";
    private static FontGroup regular;
    private static FontGroup bold;
    @BeforeAll static void fonts() throws IOException {
        regular = font("LiberationSans-Regular.ttf"); bold = font("LiberationSans-Bold.ttf");
    }

    @Test void spaceIsReservedBeforeNaturalPaginationAndFinalFieldsRepeatWithoutDuplicatingBodyLines() {
        var template = compiler().compile(xml(PAGE + BANDS + "<p id='body'>A\nB\nC\nD\nE\nF\nG</p>"));
        var pageDocument = template.renderWithPageRegions(Map.of("name", "Report"));
        var result = DocumentEngine.builder(regular).build().layout(pageDocument);
        assertEquals(2, result.body().pages().size());
        assertEquals(List.of(0, 4), result.body().pages().stream().map(page -> page.fragments().getFirst().firstLineIndex()).toList());
        assertEquals("ABCDEFG", result.body().pages().stream().flatMap(page -> page.fragments().stream())
                .flatMap(fragment -> fragment.layout().lines().stream()).flatMap(line -> line.runs().stream())
                .map(run -> run.text()).collect(java.util.stream.Collectors.joining()));
        assertTrue(result.body().pages().stream().allMatch(page -> page.contentBounds().equals(new Rect(10, 35, 220, 90))));
        assertEquals(List.of("HEAD-Report", "PAGE 1 OF 2", "HEAD-Report", "PAGE 2 OF 2"),
                result.decorations().stream().map(placed -> text(placed.layout())).toList());
        assertEquals(List.of(new Rect(10, 10, 220, 20), new Rect(10, 130, 220, 20),
                new Rect(10, 10, 220, 20), new Rect(10, 130, 220, 20)),
                result.decorations().stream().map(placed -> placed.viewport()).toList());
        assertTrue(result.contextualDiagnostics().isEmpty());
    }

    @Test void themeDefaultsAreIndependentAndLocalResetsAndNamedCompleteStylesKeepTheirMeaning() {
        var theme = DocumentTheme.builder().bodyFormat(format -> format.fontSize(22))
                .headerStyle(style -> style.fontRole("bold").rgb(0x224488).format(format -> format.fontSize(11).lineHeight(18)))
                .footerStyle(style -> style.rgb(0x336633))
                .inlineStyle("accent", ink -> ink.fontSize(9).rgb(0x883366)).build();
        var template = compilerBuilder().theme(theme).bodyText(text -> text.fontRole("other").rgb(0x111111)).build().compile(xml(PAGE
                + "<header height='30'><p>Brand <span role='' color='#000000'>Reset</span> <span style='accent'>Named</span></p></header>"
                + "<footer height='20'><p>Footer</p></footer>"));
        var value = template.renderWithPageRegions(Map.of());
        var runs = value.pageRegions().header().paragraphs().getFirst().inlines();
        assertEquals("bold", runs.getFirst().style().fontRole());
        assertEquals(0x224488, runs.getFirst().style().rgb());
        assertEquals(11, runs.getFirst().style().fontSize());
        assertEquals("", runs.get(1).style().fontRole());
        assertEquals(0, runs.get(1).style().rgb());
        assertEquals(theme.inlineStyle("accent"), runs.getLast().style());
        var result = DocumentEngine.builder(regular).fontRole("bold", bold).build().layout(value);
        var glyph = (DrawCommand.GlyphText) result.decorations().getFirst().layout().commands().getFirst();
        assertEquals(bold.fonts().getFirst().data().id(), glyph.run().font().id());
        assertEquals(10, value.pageRegions().footer().paragraphs().getFirst().format().fontSize());
        assertEquals(theme, theme.toBuilder().build());
        assertNotEquals(theme, theme.toBuilder().footerStyle(style -> style.rgb(0)).build());
    }

    @Test void absentRegionsKeepLegacyRenderAndLayoutWhileOldEntryRefusesToDropDeclaredBands() {
        var plain = compiler().compile(xml(PAGE + "<p>A</p>"));
        var engine = DocumentEngine.builder(regular).build();
        assertEquals(engine.layout(plain.render(Map.of())), engine.layout(plain.renderWithPageRegions(Map.of())));
        var template = compiler().compile(xml(PAGE + BANDS));
        assertEquals(DocumentTemplate.BindingException.Code.UNSUPPORTED_BLOCK,
                assertThrows(DocumentTemplate.BindingException.class, () -> template.render(Map.of("name", "A"))).code());
        assertEquals(template, template.toBuilder().build());
    }

    @Test void pageRegionsUseSharedBindingBudgetsAndRejectMissingValues() {
        var template = compiler().compile(xml(PAGE + BANDS + "<p>Body</p>"));
        assertEquals(DocumentTemplate.BindingException.Code.MISSING_VALUE,
                assertThrows(DocumentTemplate.BindingException.class, () -> template.renderWithPageRegions(Map.of())).code());
        var limited = template.toBuilder().limits(new DocumentTemplate.Limits(1_000, 2, 10, 1_000, 20)).build();
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentTemplate.BindingException.class, () -> limited.renderWithPageRegions(Map.of("name", "A"))).code());
        var textLimited = template.toBuilder().limits(new DocumentTemplate.Limits(1_000, 10, 10, 20, 20)).build();
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentTemplate.BindingException.class, () -> textLimited.renderWithPageRegions(Map.of("name", "ABCDEFGHIJKLMNOP"))).code());
    }

    @Test void grammarRejectsMisplacedDuplicateDynamicAndUnsupportedRegionsEvenInsideUntakenBranches() {
        for (var content : List.of("<p>Body</p>" + BANDS, BANDS + BANDS, BANDS + "<page/>",
                "<each items='items' as='item'>" + BANDS + "</each>",
                "<if test='flag'><then/><else>" + BANDS + "</else></if>",
                "<header height='${secret}'><p>Text</p></header>", "<header height='0'><p>Content</p></header>",
                "<header><p>Text</p></header>", "<header height='20'><table/></header>",
                "<header height='20'><p><page-reference target='body' width='20'/></p></header>",
                "<header height='20'><p><link href='https://example.com'>Link</link></p></header>",
                "<header height='20'><p><image ref='image' width='10' height='10'/></p></header>")) {
            assertThrows(TemplateException.class, () -> compiler().compile(xml(PAGE + content)), content);
        }
        assertThrows(TemplateException.class, () -> compiler().compile(xml(PAGE + "<header height='20'><p id='body'>A</p></header><p id='body'>B</p>")));
        assertThrows(TemplateException.class, () -> XmlTemplate.builder().fragment("band", "<fragment><header height='20'><p>A</p></header></fragment>")
                .build().compile(xml(PAGE + "<include ref='band' prefix='x'/>")));
    }

    @Test void compileBudgetsAndDiagnosticsRemainBoundedAndDoNotExposeSource() {
        String secret = "PRIVATE-CONTENT";
        var failure = assertThrows(TemplateException.class, () -> compiler().compile(xml(PAGE
                + "<header height='20'><p>" + secret + "<span color='" + secret + "'>X</span></p></header>")));
        assertTrue(failure.line() > 0); assertFalse(failure.getMessage().contains(secret));
        var limited = compilerBuilder().limits(new XmlTemplate.Limits(2_000, 4, 8, 1_000)).build();
        assertEquals(TemplateException.Code.BUDGET_EXCEEDED,
                assertThrows(TemplateException.class, () -> limited.compile(xml(PAGE + BANDS))).code());
        String many = "<header height='20'>" + "<p/>".repeat(257) + "</header>";
        assertThrows(TemplateException.class, () -> compiler().compile(xml(PAGE + many)));
        assertThrows(TemplateException.class, () -> compiler().compile("<!DOCTYPE document [<!ENTITY x SYSTEM 'https://example.com'>]>"
                + xml(PAGE + "<header height='20'><p>&x;</p></header>")));
    }

    @Test void overflowAndUnknownRolesFailInsteadOfClippingAndConfiguredTemplatesConflict() {
        var engine = DocumentEngine.builder(regular).build();
        for (var content : List.of("<header height='5'><p>A</p></header>",
                "<header height='20'><p>A\nB</p></header>", "<header height='150'><p>A</p></header>",
                "<header height='20'><p role='unknown'>A</p></header>",
                "<header height='20'><p><page-number width='0.01'/></p></header>")) {
            var value = compiler().compile(xml(PAGE + content + "<p>B</p>")).renderWithPageRegions(Map.of());
            assertThrows(IllegalArgumentException.class, () -> engine.layout(value), content);
        }
        var value = compiler().compile(xml(PAGE + BANDS)).renderWithPageRegions(Map.of("name", "A"));
        assertThrows(IllegalStateException.class, () -> DocumentEngine.builder(regular)
                .pageTemplate(PageRegionTemplates.from(value.pageRegions())).build().layout(value));
    }

    @Test void finalFieldsIncludeContentsOffsetAndRespectRestartedSectionNumbers() {
        var value = compiler().compile(xml(PAGE + BANDS + "<p id='body'>Body</p>" )).renderWithPageRegions(Map.of("name", "A"));
        var document = new SectionedDocument(value.document().metadata(), List.of(new Section("s", value.document().pageSettings(),
                value.document().blocks(), 8)));
        var navigation = Navigation.builder().bookmark("Body", "body", 1)
                .contents(new TableOfContents(true, "Contents", ParagraphStyle.of(10))).build();
        var result = DocumentEngine.builder(regular).build().layout(document,
                List.of(SectionTemplate.uniform(PageRegionTemplates.from(value.pageRegions()))), navigation, List.of());
        assertEquals(1, result.bodyPageOffset());
        assertEquals(List.of("HEAD-A", "PAGE 1 OF 2", "HEAD-A", "PAGE 8 OF 2"),
                result.decorations().stream().map(placed -> text(placed.layout())).toList());
    }

    @Test void failedRegionLayoutNeverInvokesTheBackendAndPreservesTheOldAtomicTarget(@TempDir Path directory) throws IOException {
        var document = compiler().compile(xml(PAGE + "<header height='5'><p>Too tall</p></header><p>Body</p>"))
                .renderWithPageRegions(Map.of());
        var target = directory.resolve("report.pdf");
        Files.writeString(target, "PREVIOUS-REPORT");
        var called = new AtomicBoolean();
        var request = DocumentEngine.builder(regular).build().export(document).backend((display, output) -> {
            called.set(true); output.write(1);
        });
        assertThrows(IllegalArgumentException.class, () -> request.to(target));
        assertFalse(called.get());
        assertEquals("PREVIOUS-REPORT", Files.readString(target));
        try (var files = Files.list(directory)) { assertEquals(List.of(target), files.toList()); }
    }

    @Test void missingGlyphWarningsUseConcatenatedBandSourceOffsetsAndActualRegionPage() {
        var value = compiler().compile(xml(PAGE + "<header height='35'><p>A</p><p>B&#x1f600;</p></header><p>Body</p>"))
                .renderWithPageRegions(Map.of());
        var engine = DocumentEngine.builder(FontGroup.placeholder(regular.fonts(), '?')).build();
        var diagnostics = engine.layout(value).contextualDiagnostics();
        assertEquals(1, diagnostics.size());
        assertEquals("HEADER", diagnostics.getFirst().region());
        assertEquals(0, diagnostics.getFirst().pageIndex());
        assertEquals(2, diagnostics.getFirst().diagnostic().sourceStart());
        assertEquals(4, diagnostics.getFirst().diagnostic().sourceEnd());
    }

    @Test void cancellationDuringRegionCompositionAndBindingRestoresExecutionScope() throws IOException {
        var template = compiler().compile(xml(PAGE + BANDS + "<p>Body</p>"));
        var value = template.renderWithPageRegions(Map.of("name", "A"));
        var component = PageRegionTemplates.from(value.pageRegions()).header().placements().getFirst().component();
        var cancelled = new AtomicBoolean();
        assertThrows(ExecutionException.class, () -> ExecutionContext.execute(ExecutionOptions.builder().cancelled(cancelled::get).build(), () -> {
            cancelled.set(true); return component.layout(new ComponentContext(220, 20, regular, 0, 1, 1, Map.of()));
        }));
        assertThrows(ExecutionException.class, () -> ExecutionContext.execute(ExecutionOptions.builder().cancelled(() -> true).build(),
                () -> template.renderWithPageRegions(Map.of("name", "A"))));
        assertEquals("HEAD-A", text(component.layout(new ComponentContext(220, 20, regular, 0, 1, 1, Map.of()))));
    }

    @Test void buildersSnapshotPageParagraphsAndRegionThemeCallbacks() {
        var retained = new ArrayList<com.mirakyux.export4j.model.PageRegionStyle.Builder>();
        var theme = DocumentTheme.builder().headerStyle(style -> { retained.add(style); style.rgb(0x224488); }).build();
        retained.getFirst().rgb(0x883366);
        var template = compilerBuilder().theme(theme).build().compile(xml(PAGE + "<header height='20'><p>A</p></header>"));
        assertEquals(0x224488, template.pageRegions().header().paragraphs().getFirst().inlines().getFirst().style().rgb());
        var values = new ArrayList<>(template.pageRegions().header().paragraphs());
        var copied = template.pageRegions().header().toBuilder().paragraphs(values).build(); values.clear();
        assertEquals(1, copied.paragraphs().size());
        assertEquals(PageRegions.EMPTY, compiler().compile(xml(PAGE)).pageRegions());
    }

    private static XmlTemplate.Builder compilerBuilder() {
        return XmlTemplate.builder().bodyFormat(ParagraphFormat.builder().fontSize(10).lineHeight(20).spaceAfter(0).build());
    }
    private static XmlTemplate compiler() { return compilerBuilder().build(); }
    private static String xml(String content) { return "<document version='1'>" + content + "</document>"; }
    private static FontGroup font(String name) throws IOException {
        try (var input = XmlPageRegionsTest.class.getResourceAsStream("/fonts/" + name)) {
            if (input == null) { throw new IOException("missing test font"); }
            return FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }
    private static String text(ComponentLayout layout) {
        return layout.commands().stream().map(command -> switch (command) {
            case DrawCommand.GlyphText glyph -> glyph.run().text();
            case DrawCommand.OutlineText outline -> outline.logicalText();
            default -> "";
        }).collect(java.util.stream.Collectors.joining());
    }
}
