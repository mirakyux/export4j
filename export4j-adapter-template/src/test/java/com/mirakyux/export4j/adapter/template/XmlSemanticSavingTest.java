// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.Caption;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageDocument;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlSemanticSavingTest {
    private static final ParagraphFormat FORMAT = ParagraphFormat.builder().fontSize(11).lineHeight(20).spaceAfter(5).build();
    private static final InlineStyle STYLE = new InlineStyle("bold", 11, 0x224488, -2, true, false);
    private static final List<Note> NOTES = List.of(new Note("foot", "body", "Foot ${raw} & <literal>\r\n😀", Note.Kind.FOOTNOTE),
            new Note("tail", "caption", "End ${raw", Note.Kind.ENDNOTE), new Note("list-note", "number", "List note", Note.Kind.FOOTNOTE));

    @Test void threeCyclesPreserveCompleteSemanticsAndExactSource() throws IOException {
        var original = source();
        var bundle = XmlTemplateWriter.from(original).notes(NOTES).prepare().bundle();
        String xml = bundle.source();
        for (int i = 0; i < 3; i++) {
            var bound = XmlTemplateBundle.read(bundle.encode()).compile().renderComplete(Map.of());
            assertEquals(original, bound.document()); assertEquals(NOTES, bound.notes());
            var prepared = XmlTemplateWriter.from(bound.document()).notes(bound.notes()).prepare();
            assertTrue(prepared.preview().issues().isEmpty()); assertEquals(xml, prepared.bundle().source());
            bundle = prepared.bundle();
        }
    }

    @Test void legacyEntriesRejectNotesWhileCompleteBindingAndBuilderRetainLiteralMode() throws IOException {
        var template = XmlTemplateWriter.from(source()).notes(NOTES).prepare().bundle().compile();
        for (Runnable render : List.<Runnable>of(() -> template.render(Map.of()), () -> template.renderWithPageRegions(Map.of()),
                () -> template.renderSections(Map.of()))) { assertThrows(DocumentTemplate.BindingException.class, render::run); }
        assertEquals(template.renderComplete(Map.of()), template.toBuilder().build().renderComplete(Map.of()));
    }

    @Test void singleDocumentAndRegionEntriesUseCompleteNotesWithoutLosingBands() throws IOException {
        var paragraph = Paragraph.mixed("p", List.of(new InlineText("Body", STYLE)), FORMAT);
        var document = new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(paragraph));
        var note = new Note("n", "p", "Legacy note", Note.Kind.FOOTNOTE);
        var regions = new PageRegions(new PageRegion(30, 2, List.of(Paragraph.mixed("header", List.of(new InlineText("Header", InlineStyle.of(11))), FORMAT))), PageRegion.EMPTY);
        var saved = XmlTemplateWriter.from(new PageDocument(document, regions)).notes(List.of(note)).prepare().bundle().compile().renderComplete(Map.of());
        assertEquals(document.blocks(), saved.document().sections().getFirst().blocks());
        assertEquals(regions, saved.document().sections().getFirst().pageRegions()); assertEquals(List.of(note), saved.notes());
    }

    @Test void conversionRetainsNotesAndCaptionWhenEditingOtherText() throws IOException {
        var bundle = XmlTemplateWriter.from(source()).notes(NOTES).prepare().bundle();
        var prepared = bundle.conversion().edit(editor -> editor.replaceText("body", "Body", "Edit"))
                .prepare(XmlTemplateWriter.conversionTarget());
        var bytes = new ByteArrayOutputStream(); var actual = prepared.to(bytes);
        var reopened = XmlTemplateBundle.read(bytes.toByteArray()).compile().renderComplete(Map.of());
        assertEquals(NOTES, reopened.notes()); assertEquals(NOTES, actual.notes());
        assertEquals(actual.document(), reopened.document()); assertEquals(prepared.preview().issues(), actual.report().issues());
        assertEquals(1, actual.changes().size());
        assertTrue(((Paragraph) reopened.document().sections().getFirst().blocks().get(1)).text().startsWith("Edit"));
    }

    @Test void noteAndAnchorRenamingUsesOneCollisionFreeMapIncludingMarkerTargets() throws IOException {
        var body = Paragraph.mixed("invalid anchor", List.of(new InlineField(InlineField.Kind.NOTE_MARKER, "invalid note", 12, STYLE)), FORMAT);
        var document = new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(body, new Caption("saved-1", "Figure", "Reserved", FORMAT)));
        var prepared = XmlTemplateWriter.from(document).notes(List.of(new Note("invalid note", "invalid anchor", "Note", Note.Kind.FOOTNOTE)))
                .policy(ProcessingPolicy.builder().feature("IDENTIFIER", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build()).prepare();
        var bound = prepared.bundle().compile().renderComplete(Map.of());
        var restored = (Paragraph) bound.document().sections().getFirst().blocks().getFirst();
        assertEquals("saved-2", bound.notes().getFirst().id()); assertEquals("saved-3", restored.id());
        assertEquals(restored.id(), bound.notes().getFirst().paragraphId());
        assertEquals(bound.notes().getFirst().id(), restored.fields().getFirst().field().targetId());
        assertEquals(2, prepared.preview().losses().size());
    }

    @Test void malformedRelationshipsCannotBeIgnoredByAnyAction() {
        var document = source();
        for (var notes : List.of(List.<Note>of(), List.of(new Note("foot", "caption", "Wrong owner", Note.Kind.FOOTNOTE)),
                List.of(new Note("foot", "absent", "Missing", Note.Kind.FOOTNOTE)),
                List.of(new Note("foot", "body", "One", Note.Kind.FOOTNOTE), new Note("foot", "body", "Two", Note.Kind.ENDNOTE)),
                List.of(new Note("body", "body", "ID collision", Note.Kind.FOOTNOTE)))) {
            for (var action : ProcessingPolicy.Action.values()) {
                assertThrows(IllegalArgumentException.class, () -> XmlTemplateWriter.from(document).notes(notes)
                        .policy(ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DISCARD).build()).prepare());
            }
        }
        var repeated = Paragraph.mixed("body", List.of(new InlineField(InlineField.Kind.NOTE_MARKER, "n", 12, STYLE),
                new InlineField(InlineField.Kind.NOTE_MARKER, "n", 12, STYLE)), FORMAT);
        assertThrows(IllegalArgumentException.class, () -> XmlTemplateWriter.from(new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(repeated)))
                .notes(List.of(new Note("n", "body", "Duplicate marker", Note.Kind.FOOTNOTE))).prepare());
    }

    @Test void noteTextBindsInRootScopeAndSharesTextAndWorkBudgets() {
        String xml = "<notes><note id='n' paragraph='p' kind='FOOTNOTE'>${value}</note></notes><p id='p'>Body</p>";
        var template = compile(xml);
        assertEquals("Bound", template.renderComplete(Map.of("value", "Bound")).notes().getFirst().text());
        assertThrows(DocumentTemplate.BindingException.class, () -> template.renderComplete(Map.of()));
        var small = XmlTemplate.builder().bindingLimits(new DocumentTemplate.Limits(100, 10, 10, 10, 10)).build().compile(wrap(xml));
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentTemplate.BindingException.class, () -> small.renderComplete(Map.of("value", "Long note content"))).code());
        var lowWork = template.toBuilder().limits(new DocumentTemplate.Limits(5, 10, 10, 100, 10)).build();
        assertThrows(DocumentTemplate.BindingException.class, () -> lowWork.renderComplete(Map.of("value", "N")));
    }

    @Test void staticNoteAnchorsCannotSilentlyFollowConditionalOrRepeatedCopies() {
        var template = compile("<notes><note id='n' paragraph='p' kind='ENDNOTE'>Note</note></notes>"
                + "<if test='shown'><then><p id='p'>Body</p></then><else><p>Other</p></else></if>");
        assertEquals("p", template.renderComplete(Map.of("shown", true)).notes().getFirst().paragraphId());
        assertThrows(IllegalArgumentException.class, () -> template.renderComplete(Map.of("shown", false)));
        var loop = compile("<notes><note id='n' paragraph='p' kind='ENDNOTE'>Note</note></notes>"
                + "<each items='items' as='item'><p id='p'>${item}</p></each>");
        for (var items : List.of(List.of(), List.of("One"), List.of("One", "Two"))) {
            assertThrows(IllegalArgumentException.class, () -> loop.renderComplete(Map.of("items", items)));
        }
    }

    @Test void captionIdsAndReferencesRelocateInIncludesAndLoopCopies() {
        var compiler = XmlTemplate.builder().fragment("part", "<fragment version='1'><caption id='c' kind='Figure'>${item}</caption>"
                + "<p id='r'><number-reference target='c' width='70'/></p></fragment>").build();
        var bound = compiler.compile(wrap("<each items='items' as='item'><include ref='part' prefix='copy'/></each>"))
                .renderSections(Map.of("items", List.of("One", "Two")));
        var blocks = bound.sections().getFirst().blocks();
        for (int index = 0; index < 2; index++) {
            var caption = (Caption) blocks.get(index * 2); var ref = (Paragraph) blocks.get(index * 2 + 1);
            assertEquals("copy-c@" + index, caption.id()); assertEquals(caption.id(), ref.fields().getFirst().field().targetId());
            assertEquals("Figure", caption.kind()); assertEquals(index == 0 ? "One" : "Two", caption.text());
        }
    }

    @Test void textBlockLinksBindLexicallyButNeverAdmitImagesFieldsOrNestedLinks() {
        var template = compile("<each items='items' as='item'><heading level='1'><link href='https://example.com/${item}'><span color='#224488'>${item}</span></link></heading></each>");
        var headings = template.render(Map.of("items", List.of("one", "two"))).blocks();
        assertEquals("https://example.com/two", ((Heading) headings.get(1)).inlines().getFirst().hyperlink());
        for (String object : List.of("<image ref='x' width='10' height='10'/>", "<page-number width='20'/>",
                "<note-marker target='n' width='10'/>", "<link href='https://example.org'>Nested</link>")) {
            for (String tag : List.of("heading level='1'", "bullet", "numbered list-id='steps'")) {
                assertThrows(TemplateException.class, () -> compile("<" + tag + "><link href='https://example.com'>" + object + "</link></" + tag.split(" ")[0] + ">"));
            }
        }
    }

    @Test void invalidNotesAndCaptionsAreRejectedAtCompileTimeEvenInHiddenBranches() {
        for (String xml : List.of("<notes unknown='x'/><p>X</p>", "<notes><note id='n' paragraph='p' kind='UNKNOWN'>X</note></notes><p id='p'>X</p>",
                "<notes><note id='p' paragraph='p' kind='ENDNOTE'>X</note></notes><p id='p'>X</p>",
                "<notes><note id='n' paragraph='${p}' kind='ENDNOTE'>X</note></notes><p>X</p>",
                "<notes><note id='n' paragraph='p' kind='ENDNOTE'><span>X</span></note></notes><p id='p'>X</p>",
                "<p>X</p><notes/>", "<each items='items' as='item'><notes/></each>",
                "<if test='flag'><then><p>X</p></then><else><caption kind='Figure'><span>X</span></caption></else></if>",
                "<caption kind=' '>X</caption>", "<caption kind='Figure' role='bold'>X</caption>")) {
            assertThrows(TemplateException.class, () -> compile(xml), xml);
        }
        // A declared note ID is reserved before auto IDs in regions or body are generated.
        var template = compile("<header height='30'><p>Header</p></header><notes><note id='xml-1' paragraph='p' kind='ENDNOTE'>N</note></notes><p id='p'>Body</p>");
        assertEquals("xml-2", template.renderComplete(Map.of()).document().sections().getFirst().pageRegions().header().blocks().getFirst().id());
    }

    @Test void noteSavingAndBindingRespectCancellationAndWriterBudgets() {
        assertThrows(IllegalArgumentException.class, () -> XmlTemplateWriter.from(source()).notes(NOTES)
                .limits(new XmlTemplateWriter.Limits(7, 100, 100)).prepare());
        assertThrows(ExecutionException.class, () -> XmlTemplateWriter.from(source()).notes(NOTES)
                .execution(ExecutionOptions.builder().cancelled(() -> true).build()).prepare());
        assertThrows(ExecutionException.class, () -> ExecutionContext.execute(ExecutionOptions.builder().cancelled(() -> true).build(),
                () -> compile("<p>X</p>").renderComplete(Map.of())));
    }

    private static DocumentTemplate compile(String content) { return XmlTemplate.builder().build().compile(wrap(content)); }
    private static String wrap(String content) { return "<document version='1'>" + content + "</document>"; }
    private static SectionedDocument source() {
        var body = Paragraph.mixed("body", List.of(new InlineText("Body ${raw}", STYLE),
                new InlineField(InlineField.Kind.NOTE_MARKER, "foot", 12, STYLE), new InlineText(" after", STYLE)), FORMAT);
        return new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("first", PageSettings.A4, List.of(
                Heading.rich("h", List.of(new InlineText("Heading", STYLE, "https://example.com/heading")), 1, FORMAT), body,
                new Caption("caption", "Figure ${raw}", "Caption ${raw", FORMAT),
                BulletItem.rich("bullet", List.of(new InlineText("Bullet", STYLE, "https://example.com/bullet")), "-", 1, FORMAT, STYLE),
                NumberedItem.rich("number", "steps", List.of(new InlineText("Number", STYLE, "https://example.com/number")), 1, FORMAT, 1, STYLE)), 1),
                new Section("second", PageSettings.A4, List.of(new Caption("c2", "Figure ${raw}", "Second", FORMAT)), 8)));
    }
}
