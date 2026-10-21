// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

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
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.layout.core.DocumentPainter;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.Caption;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextOutline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineFieldsTest {
    private static final DocumentMetadata META = new DocumentMetadata("Fields", "Export4j");
    private static final PageSettings SETTINGS = new PageSettings(new PageSize(380, 220), PageMargins.uniform(16));
    private static final ParagraphFormat FORMAT = new ParagraphFormat(12, 22, 0, 0, ParagraphAlignment.LEFT, 0, 0, false);
    private static FontGroup fonts;

    @BeforeAll
    static void fonts() throws IOException {
        var faces = new ArrayList<FontFace>();
        for (var name : List.of("LiberationSans-Regular.ttf", "LXGWWenKaiTC-Regular.ttf")) {
            try (var input = InlineFieldsTest.class.getResourceAsStream("/fonts/" + name)) {
                faces.add(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES));
            }
        }
        fonts = FontGroup.strict(faces);
    }

    @Test
    void finalFieldsUseContentsOffsetRestartedNumbersForwardLabelsAndExactLinks() {
        var paragraph = Paragraph.mixed("fields", List.of(text("page="), field(InlineField.Kind.PAGE, "", 18),
                text(" total="), field(InlineField.Kind.PAGES, "", 18), text(" target="), field(InlineField.Kind.REFERENCE_PAGE, "figure", 18),
                text(" number="), field(InlineField.Kind.REFERENCE_NUMBER, "figure", 70)), FORMAT);
        var source = new SectionedDocument(META, List.of(
                new Section("a", SETTINGS, List.of(new Heading("h", "Heading", 1, FORMAT), paragraph), 0),
                new Section("b", SETTINGS, List.of(new Caption("figure", "Figure", "Target", FORMAT)), 7)));
        var result = render(source, Navigation.fromHeadings(source), fonts, List.of());
        assertEquals(1, result.bodyPageOffset());
        assertEquals(3, result.display().pages().size());
        assertTrue(logical(result.body().pages().getFirst().fragments().getLast()).contains("page=2 total=3 target=7 number=Figure 1"));
        var references = result.display().extras().links().stream().filter(link -> link.pageIndex() == 1).toList();
        assertEquals(2, references.size());
        assertTrue(references.stream().allMatch(link -> link.targetPageIndex() == 2));
        assertEquals(List.of(18.0, 70.0), references.stream().map(link -> link.bounds().width()).toList());
    }

    @Test
    void resolutionPreservesLineGeometrySourceRangesAndUtf16WarningsAroundFields() {
        var group = FontGroup.placeholder(List.of(fonts.fonts().getFirst()), '?');
        var source = Paragraph.mixed("source", List.of(text("😀"), field(InlineField.Kind.PAGE, "", 24), text("中 tail tail tail tail tail")), FORMAT);
        var document = new Document(META, new PageSettings(new PageSize(150, 140), PageMargins.uniform(10)), List.of(source));
        var raw = new DocumentLayouter().layout(document, group);
        assertThrows(IllegalArgumentException.class, () -> DocumentPainter.paint(raw));
        var resolved = new ComponentDocumentRenderer().layout(document, PageTemplate.EMPTY, Navigation.EMPTY, group);
        var before = raw.pages().stream().flatMap(page -> page.fragments().stream()).flatMap(p -> p.layout().lines().stream()).toList();
        var after = resolved.body().pages().stream().flatMap(page -> page.fragments().stream()).flatMap(p -> p.layout().lines().stream()).toList();
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            var a = before.get(i); var b = after.get(i);
            assertEquals(List.of(a.sourceStart(), a.sourceEnd(), a.offsetX(), a.baselineY(), a.width()),
                    List.of(b.sourceStart(), b.sourceEnd(), b.offsetX(), b.baselineY(), b.width()));
            assertTrue(b.fields().isEmpty());
        }
        var warnings = resolved.diagnostics().stream().filter(d -> d.severity() == TextDiagnostic.Severity.WARNING).toList();
        assertEquals(List.of(0, 3), warnings.stream().map(TextDiagnostic::sourceStart).toList());
        assertEquals(List.of(2, 4), warnings.stream().map(TextDiagnostic::sourceEnd).toList());
        assertEquals(List.of(0x1f600, (int) '中'), warnings.stream().map(TextDiagnostic::sourceCodePoint).toList());
    }

    @Test
    void footnotesFollowTheirInlineMarkerLinesRatherThanParagraphEnd() {
        var content = new ArrayList<InlineContent>();
        content.add(text("START")); content.add(marker("early")); content.add(text("\n" + "Body line\n".repeat(17) + "END")); content.add(marker("late"));
        var p = Paragraph.mixed("body", content, FORMAT);
        var settings = new PageSettings(new PageSize(260, 160), PageMargins.uniform(10));
        var source = new SectionedDocument(META, List.of(new Section("s", settings, List.of(p), 0)));
        var notes = List.of(new Note("early", "body", "Early footnote", Note.Kind.FOOTNOTE),
                new Note("late", "body", "Late footnote", Note.Kind.FOOTNOTE));
        var result = render(source, Navigation.EMPTY, fonts, notes);
        assertTrue(result.body().pages().size() >= 4);
        assertTrue(commands(result.footnotes().getFirst().commands()).contains("[1] Early footnote"));
        int late = -1;
        for (var page : result.body().pages()) {
            if (page.fragments().stream().anyMatch(fragment -> logical(fragment).contains("END[2]"))) { late = page.pageIndex(); }
        }
        assertTrue(late > 0);
        assertTrue(commands(result.footnotes().get(late).commands()).contains("[2] Late footnote"));
        String body = result.body().pages().stream().flatMap(page -> page.fragments().stream()).map(InlineFieldsTest::logical).collect(java.util.stream.Collectors.joining());
        assertEquals(1, body.split("\\[1\\]", -1).length - 1);
        assertEquals(1, body.split("\\[2\\]", -1).length - 1);
    }

    @Test
    void longInlineFootnoteContinuesAndEndnoteIsNotAppendedTwice() {
        var p = Paragraph.mixed("p", List.of(text("START"), marker("foot"), text(" END"), marker("end"),
                text(" TOTAL="), field(InlineField.Kind.PAGES, "", 24)), FORMAT);
        var source = one(p);
        var result = render(source, Navigation.EMPTY, fonts, List.of(
                new Note("foot", "p", "Long footnote line\n".repeat(16), Note.Kind.FOOTNOTE),
                new Note("end", "p", "Endnote text", Note.Kind.ENDNOTE)));
        assertTrue(result.footnotes().size() > 1);
        assertEquals(5, result.display().pages().size());
        String all = commands(result.display().pages().stream().flatMap(page -> page.commands().stream()).toList());
        assertTrue(all.contains("START[1] END[2]"));
        assertTrue(all.contains("TOTAL=5"));
        assertEquals(16, all.split("Long footnote line", -1).length - 1);
        assertEquals(1, all.split("Endnote text", -1).length - 1);
    }

    @Test
    void fieldsCannotOverflowShrinkOrResolveUnknownTargets() {
        for (var field : List.of(field(InlineField.Kind.PAGE, "", .1), field(InlineField.Kind.REFERENCE_PAGE, "unknown", 30),
                field(InlineField.Kind.REFERENCE_NUMBER, "p", 30))) {
            var source = one(Paragraph.mixed("p", List.of(text("before"), field), FORMAT));
            var error = assertThrows(DocumentLayoutException.class, () -> render(source, Navigation.EMPTY, fonts, List.of()));
            assertEquals("p", error.blockId());
            assertEquals(6, error.sourceIndex());
            assertEquals(0, error.pageIndex());
        }
    }

    @Test
    void noteMarkersRejectMissingDuplicateAndWrongParagraphBindings() {
        for (var content : List.of(List.<InlineContent>of(marker("note")), List.<InlineContent>of(marker("note"), marker("note")))) {
            var source = one(Paragraph.mixed("p", content, FORMAT));
            assertThrows(IllegalArgumentException.class, () -> render(source, Navigation.EMPTY, fonts, List.of()));
            if (content.size() == 2) {
                assertThrows(IllegalArgumentException.class, () -> render(source, Navigation.EMPTY, fonts,
                        List.of(new Note("note", "p", "note", Note.Kind.FOOTNOTE))));
            }
            assertThrows(IllegalArgumentException.class, () -> render(source, Navigation.EMPTY, fonts,
                    List.of(new Note("note", "other", "note", Note.Kind.FOOTNOTE))));
        }
    }

    @Test
    void generatedFieldTextUsesExplicitRolePoliciesAndReturnsContextualWarnings() {
        var p = Paragraph.mixed("p", List.of(new InlineField(InlineField.Kind.REFERENCE_NUMBER, "caption", 100,
                new InlineStyle("label", 12, 0x285080, 0, true, false))), FORMAT);
        var source = new SectionedDocument(META, List.of(new Section("s", SETTINGS,
                List.of(p, new Caption("caption", "图", "Caption", FORMAT)), 0)));
        var renderer = new ComponentDocumentRenderer();
        var templates = List.of(SectionTemplate.uniform(PageTemplate.EMPTY));
        var group = FontGroup.placeholder(List.of(fonts.fonts().getFirst()), '?');
        var result = renderer.layout(source, templates, Navigation.EMPTY, fonts, Map.of("label", group), List.of());
        assertTrue(result.contextualDiagnostics().stream().anyMatch(d -> d.region().equals("INLINE_FIELD:0") && d.diagnostic().sourceCodePoint() == '图'));
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.sourceCodePoint() == '图'));
        assertThrows(DocumentLayoutException.class, () -> renderer.layout(source, templates, Navigation.EMPTY, fonts,
                Map.of("label", FontGroup.strict(List.of(fonts.fonts().getFirst()))), List.of()));
        assertThrows(DocumentLayoutException.class, () -> renderer.layout(source, templates, Navigation.EMPTY, fonts, Map.of(), List.of()));
    }

    @Test
    void referenceFieldsCountAgainstCombinedInteractionBudget() {
        var p = Paragraph.mixed("p", List.of(field(InlineField.Kind.REFERENCE_PAGE, "p", 30), field(InlineField.Kind.REFERENCE_PAGE, "p", 30)), FORMAT);
        var small = new ComponentLimits(1000, 10000, 1);
        var error = assertThrows(DocumentLayoutException.class, () -> new ComponentDocumentRenderer(DocumentLayoutLimits.DEFAULT, small)
                .layout(one(p), List.of(SectionTemplate.uniform(PageTemplate.EMPTY)), Navigation.EMPTY, fonts, Map.of(), List.of()));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, error.code());
        var repeated = Paragraph.mixed("repeated", List.of(field(InlineField.Kind.REFERENCE_NUMBER, "caption", 70),
                field(InlineField.Kind.REFERENCE_NUMBER, "caption", 70), field(InlineField.Kind.REFERENCE_NUMBER, "caption", 70)), FORMAT);
        var source = new SectionedDocument(META, List.of(new Section("s", SETTINGS,
                List.of(repeated, new Caption("caption", "ABCDEFG", "c", FORMAT)), 0)));
        var d = DocumentLayoutLimits.DEFAULT;
        var limits = new DocumentLayoutLimits(d.maxParagraphs(), 15, d.maxLines(), d.maxPages(), d.maxTableRows(), d.maxTableColumns(), d.maxCells());
        var expansion = assertThrows(DocumentLayoutException.class, () -> new ComponentDocumentRenderer(limits, ComponentLimits.DEFAULT)
                .layout(source, List.of(SectionTemplate.uniform(PageTemplate.EMPTY)), Navigation.EMPTY, fonts, Map.of(), List.of()));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, expansion.code());
        assertEquals("repeated", expansion.blockId());
        assertEquals(1, expansion.sourceIndex());
    }

    @Test
    void continuationPageFieldsAndFollowingExternalLinksUseTheOriginalReservedGeometry() {
        var p = Paragraph.mixed("p", List.of(text("START"), field(InlineField.Kind.PAGE, "", 24),
                text("\n" + "Line\n".repeat(8) + "END"), field(InlineField.Kind.PAGE, "", 24),
                new InlineText("LINK", InlineStyle.of(12), "https://example.com/field")), FORMAT);
        var settings = new PageSettings(new PageSize(260, 120), PageMargins.uniform(10));
        var document = new Document(META, settings, List.of(p));
        var raw = new DocumentLayouter().layout(document, fonts);
        var result = new ComponentDocumentRenderer().layout(document, PageTemplate.EMPTY, Navigation.EMPTY, fonts);
        assertEquals(3, result.body().pages().size());
        assertTrue(logical(result.body().pages().getFirst().fragments().getFirst()).startsWith("START1"));
        assertTrue(logical(result.body().pages().getLast().fragments().getFirst()).contains("END3LINK"));
        var last = raw.pages().getLast().fragments().getFirst();
        var line = last.layout().lines().getLast();
        var box = line.fields().getFirst();
        var link = result.display().extras().externalLinks().getFirst();
        assertEquals(2, link.pageIndex());
        assertTrue(link.bounds().x() >= last.origin().x() + line.offsetX() + box.x() + box.field().width());
    }

    private static SectionedDocument one(Paragraph p) { return new SectionedDocument(META, List.of(new Section("s", SETTINGS, List.<Block>of(p), 0))); }
    private static InlineText text(String value) { return new InlineText(value, InlineStyle.of(12)); }
    private static InlineField field(InlineField.Kind kind, String target, double width) { return new InlineField(kind, target, width, InlineStyle.of(12)); }
    private static InlineField marker(String id) { return new InlineField(InlineField.Kind.NOTE_MARKER, id, 18, new InlineStyle("", 8, 0x285080, -3.6, false, false)); }
    private static ComponentDocumentLayout render(SectionedDocument source, Navigation navigation, FontGroup group, List<Note> notes) {
        return new ComponentDocumentRenderer().layout(source, source.sections().stream().map(s -> SectionTemplate.uniform(PageTemplate.EMPTY)).toList(), navigation, group, Map.of(), notes);
    }
    private static String logical(ParagraphFragment p) {
        return p.layout().lines().stream().flatMap(line -> line.outlines().stream()).map(TextOutline::logicalText).collect(java.util.stream.Collectors.joining());
    }
    private static String commands(List<DrawCommand> commands) {
        var value = new StringBuilder();
        for (var command : commands) {
            if (command instanceof DrawCommand.OutlineText outline) { value.append(outline.logicalText()); }
            else if (command instanceof DrawCommand.GlyphText glyph) { value.append(glyph.run().text()); }
        }
        return value.toString();
    }
}
