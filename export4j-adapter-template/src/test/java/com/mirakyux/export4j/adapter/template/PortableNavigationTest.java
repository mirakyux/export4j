// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentNavigation;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageDocument;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.edit.DocumentEditor;
import com.mirakyux.export4j.model.edit.EditLimits;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents recovery from dropping explicit appearance, current titles or static target validity. */
class PortableNavigationTest {
    private static SectionedDocument source(DocumentNavigation navigation) {
        return new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("section", PageSettings.A4,
                List.of(Heading.rich("heading", List.of(new InlineText("ORIGINAL", InlineStyle.of(24))), 1),
                        Paragraph.mixed("body", List.of(new InlineText("BODY", InlineStyle.of(12))), ParagraphFormat.of(12))), 0)), navigation);
    }
    @Test void fullDeclarationAndExplicitBlackSurviveThreeXmlCyclesAndRealEditing() throws Exception {
        var contents = new DocumentNavigation.Contents(true, "Contents ${literal}", DocumentNavigation.TextStyle.of(10),
                Map.of(DocumentNavigation.Part.TITLE, new DocumentNavigation.TextStyle(16, 24, 2, 3, ParagraphAlignment.CENTER, LineBreakPolicy.CJK_STRICT)),
                Map.of(DocumentNavigation.Part.TITLE, 0, DocumentNavigation.Part.ENTRY, 0x224488));
        var original = source(DocumentNavigation.builder().headings(true).maxHeadingLevel(3).contents(contents).build());
        var current = original;
        String previous = null;
        for (int cycle = 0; cycle < 3; cycle++) {
            var saved = XmlTemplateWriter.from(current).prepare().bundle();
            if (previous != null) { assertEquals(previous, saved.source()); }
            previous = saved.source();
            current = XmlTemplateBundle.read(saved.encode()).compile().renderComplete(Map.of()).document();
            assertEquals(original, current);
        }
        var edited = DocumentEditor.edit(current).replaceText("heading", "ORIGINAL", "EDITED").execute().document();
        var reopened = XmlTemplateWriter.from(edited).prepare().bundle().compile().renderSections(Map.of());
        assertEquals(edited, reopened);
        assertEquals("EDITED", ((Heading) reopened.sections().getFirst().blocks().getFirst()).text());
        assertEquals(contents, reopened.navigation().contents());
    }
    @Test void manualTargetsSurviveButDanglingTargetsRequireExplicitRepair() throws Exception {
        var original = source(DocumentNavigation.builder().bookmark("Fixed ${literal}", "body", 1).build());
        var reopened = XmlTemplateWriter.from(original).prepare().bundle().compile().renderSections(Map.of());
        assertEquals(original, reopened);
        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.edit(reopened).remove("body").execute());
        var edited = DocumentEditor.edit(reopened).remove("body").navigation(DocumentNavigation.NONE).execute();
        assertEquals(DocumentNavigation.NONE, edited.document().navigation());
        assertEquals(List.of("REMOVE_BLOCK", "SET_NAVIGATION"), edited.changes().stream().map(DocumentEditor.Change::operation).toList());
        assertEquals(2, original.sections().getFirst().blocks().size());
    }
    @Test void legacyNarrowEntrypointsRefuseNavigationAndFinalBindingValidatesTargets() {
        var template = XmlTemplate.builder().build().compile("""
                <document version='1'><navigation><bookmark title='Literal ${title}' target='body' level='1'/></navigation><p id='body'>Body</p></document>
                """);
        assertThrows(DocumentTemplate.BindingException.class, () -> template.render(Map.of()));
        assertThrows(DocumentTemplate.BindingException.class, () -> template.renderWithPageRegions(Map.of()));
        var narrow = new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(new Paragraph("body", "Body", ParagraphFormat.of(12))));
        var navigation = DocumentNavigation.builder().bookmark("Title", "body", 1).build();
        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.edit(narrow).navigation(navigation).execute());
        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.edit(new PageDocument(narrow, PageRegions.EMPTY)).navigation(navigation).execute());
        assertEquals("Literal ${title}", template.renderSections(Map.of()).navigation().bookmarks().getFirst().title());
        var bad = XmlTemplate.builder().build().compile("<document version='1'><navigation><bookmark title='Bad' target='missing' level='1'/></navigation><p>Body</p></document>");
        assertThrows(IllegalArgumentException.class, () -> bad.renderComplete(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> XmlTemplateWriter.from(source(DocumentNavigation.builder().bookmark("Bad", "missing", 1).build())).prepare());
    }
    @Test void malformedHierarchyDuplicateAppearanceAndMisplacedDeclarationsAreRejected() {
        for (String declaration : List.of(
                "<navigation headings='true'><bookmark title='Bad' target='body' level='1'/></navigation>",
                "<navigation><bookmark title='Bad' target='body' level='2'/></navigation>",
                "<navigation><bookmark title='Bad' target='${dynamic}' level='1'/></navigation>",
                "<navigation><contents/><bookmark title='Bad' target='body' level='1'/></navigation>",
                "<navigation><contents><color part='TITLE' value='#000000'/><color part='TITLE' value='#112233'/></contents></navigation>",
                "<navigation><contents><typography part='TITLE'/><typography part='TITLE'/></contents></navigation>",
                "<navigation><contents><color part='ENTRY' value='bad'/></contents></navigation>",
                "<navigation/><navigation/>")) {
            assertThrows(TemplateException.class, () -> XmlTemplate.builder().build().compile("<document version='1'>" + declaration + "<p id='body'>Body</p></document>"), declaration);
        }
        assertThrows(TemplateException.class, () -> XmlTemplate.builder().build().compile("<document version='1'><p>Body</p><navigation/></document>"));
    }
    @Test void navigationTextSharesBindingAndEditingBudgetsEvenWhenItWillBeDiscarded() throws Exception {
        var original = source(DocumentNavigation.builder().bookmark("X".repeat(100), "body", 1).build());
        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.edit(original).limits(new EditLimits(10, 20, 50, 10, 10, 1000)).execute());
        var template = XmlTemplateWriter.from(original).prepare().bundle().compile();
        var small = new DocumentTemplate.Limits(100, 20, 20, 50, 8);
        assertThrows(DocumentTemplate.BindingException.class, () -> template.toBuilder().limits(small).build().renderSections(Map.of()));
    }
}
