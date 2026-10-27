// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.model.CrossReference;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PaginationPolicy;
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

class XmlCrossReferenceSavingTest {
    private static final ParagraphFormat FORMAT = ParagraphFormat.builder().fontSize(11).lineHeight(20)
            .firstLineIndent(3).subsequentLineIndent(4).spaceBefore(2).spaceAfter(6)
            .pagination(PaginationPolicy.BALANCED).build();

    @Test void threeCyclesPreserveKindsTargetsPrefixesFormatsAndSectionPageNumbers() throws IOException {
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(
                new Section("one", PageSettings.A4, List.of(
                        reference("page", "Page ${literal\r\n😀 & <raw> ", "target", CrossReference.Kind.PAGE),
                        reference("number", "", "target", CrossReference.Kind.NUMBER)), 3),
                new Section("two", PageSettings.A4, List.of(NumberedItem.rich("target", "steps",
                        List.of(new InlineText("Target", InlineStyle.of(11))), 7, FORMAT, 1)), 12)));
        var bundle = XmlTemplateWriter.from(source).prepare().bundle();
        String xml = bundle.source();
        for (int i = 0; i < 3; i++) {
            var restored = XmlTemplateBundle.read(bundle.encode()).compile().renderComplete(Map.of());
            assertEquals(source, restored.document()); assertTrue(restored.notes().isEmpty());
            var next = XmlTemplateWriter.from(restored.document()).prepare();
            assertTrue(next.preview().issues().isEmpty()); assertEquals(xml, next.bundle().source());
            bundle = next.bundle();
        }
    }

    @Test void prefixesAndTargetsBindInLexicalScopeAndShareTextBudgets() {
        var template = compile("<cross-reference id='r' kind='PAGE' target='${target}'>${prefix}</cross-reference><p id='p'>Body</p>");
        var bound = template.render(Map.of("target", "p", "prefix", "See "));
        assertEquals(new CrossReference("r", "See ", "p", CrossReference.Kind.PAGE, ParagraphFormat.of(12)), bound.blocks().getFirst());
        assertThrows(DocumentTemplate.BindingException.class, () -> template.render(Map.of("prefix", "See ")));
        assertThrows(IllegalArgumentException.class, () -> template.render(Map.of("target", " ", "prefix", "See ")));
        var limited = template.toBuilder().limits(new DocumentTemplate.Limits(100, 10, 10, 18, 10)).build();
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentTemplate.BindingException.class, () -> limited.render(Map.of("target", "p", "prefix", "Too much text"))).code());
    }

    @Test void nestedIncludesAndLoopCopiesRelocateStaticTargetsButKeepDynamicFinalIds() {
        var compiler = XmlTemplate.builder()
                .fragment("leaf", "<fragment version='1'><p id='p'>${item}</p></fragment>")
                .fragment("panel", "<fragment version='1'><include ref='leaf' prefix='child'/>"
                        + "<cross-reference id='local' kind='PAGE' target='child-p'>Local </cross-reference>"
                        + "<cross-reference id='external' kind='PAGE' target='outside'>Outside </cross-reference>"
                        + "<cross-reference id='dynamic' kind='PAGE' target='${finalId}'>Dynamic </cross-reference></fragment>").build();
        var bound = compiler.compile(wrap("<p id='outside'>Outside</p><each items='items' as='item'>"
                + "<include ref='panel' prefix='copy'/></each>"))
                .render(Map.of("items", List.of("One", "Two"), "finalId", "copy-child-p@1"));
        for (int i = 0; i < 2; i++) {
            int start = 1 + i * 4;
            assertEquals("copy-child-p@" + i, bound.blocks().get(start).id());
            assertEquals("copy-local@" + i, bound.blocks().get(start + 1).id());
            assertEquals("copy-child-p@" + i, ((CrossReference) bound.blocks().get(start + 1)).targetId());
            assertEquals("outside", ((CrossReference) bound.blocks().get(start + 2)).targetId());
            assertEquals("copy-child-p@1", ((CrossReference) bound.blocks().get(start + 3)).targetId());
        }
    }

    @Test void javaEachAndConditionalTargetsUseTheSameCopyScope() {
        var template = DocumentTemplate.builder().each("items", "item", copy -> {
            copy.block(reference("r", "${item} ", "p", CrossReference.Kind.PAGE));
            copy.when("shown", yes -> yes.block(new Paragraph("p", "Body", FORMAT)), no -> { });
        }).build();
        var bound = template.render(Map.of("items", List.of("One", "Two"), "shown", true));
        assertEquals("p@0", ((CrossReference) bound.blocks().getFirst()).targetId());
        assertEquals("p@1", ((CrossReference) bound.blocks().get(2)).targetId());
        assertEquals("Two ", ((CrossReference) bound.blocks().get(2)).prefix());
    }

    @Test void identifierRenamingKeepsForwardTargetsAndReportsOnlyActualLosses() throws IOException {
        var source = new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(
                reference("invalid reference", "See ", "invalid target", CrossReference.Kind.PAGE),
                new Paragraph("saved-1", "Reserved", FORMAT), new Paragraph("invalid target", "Target", FORMAT)));
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var prepared = XmlTemplateWriter.from(source).policy(ProcessingPolicy.builder()
                    .feature("IDENTIFIER", action, ProcessingPolicy.Treatment.DEGRADE).build()).prepare();
            var blocks = prepared.bundle().compile().render(Map.of()).blocks();
            assertEquals("saved-2", blocks.getFirst().id()); assertEquals("saved-3", blocks.get(2).id());
            assertEquals(blocks.get(2).id(), ((CrossReference) blocks.getFirst()).targetId());
            assertEquals(2, prepared.preview().losses().size());
            assertEquals(action == ProcessingPolicy.Action.WARN ? 2 : 0, prepared.preview().warnings().size());
        }
    }

    @Test void conversionCanRetargetAnIndependentBlockWithoutChangingUnrelatedContent() throws IOException {
        var bundle = XmlTemplateWriter.from(new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(
                reference("r", "Before ", "a", CrossReference.Kind.PAGE), new Paragraph("a", "A", FORMAT), new Paragraph("b", "B", FORMAT))))
                .prepare().bundle();
        var replacement = reference("r", "After ", "b", CrossReference.Kind.PAGE);
        var prepared = bundle.conversion().edit(editor -> editor.replaceBlock("r", replacement)).prepare(XmlTemplateWriter.conversionTarget());
        var bytes = new ByteArrayOutputStream(); var result = prepared.to(bytes);
        var reopened = XmlTemplateBundle.read(bytes.toByteArray()).compile().renderComplete(Map.of());
        assertEquals(replacement, reopened.document().sections().getFirst().blocks().getFirst());
        assertEquals(result.document(), reopened.document()); assertEquals(1, result.changes().size());
        assertTrue(result.report().issues().isEmpty()); assertEquals(prepared.preview().issues(), result.report().issues());
    }

    @Test void invalidReferenceRelationshipsCannotBeIgnoredDuringConversion() throws IOException {
        for (var kind : CrossReference.Kind.values()) {
            var bundle = XmlTemplateWriter.from(new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(
                    reference("r", "See ", kind == CrossReference.Kind.PAGE ? "missing" : "p", kind), new Paragraph("p", "Body", FORMAT))))
                    .prepare().bundle();
            for (var action : ProcessingPolicy.Action.values()) {
                assertThrows(IllegalArgumentException.class, () -> bundle.conversion().policy(ProcessingPolicy.builder()
                        .defaultRule(action, ProcessingPolicy.Treatment.DISCARD).build()).prepare(XmlTemplateWriter.conversionTarget()));
            }
        }
    }

    @Test void malformedOrMisplacedReferencesFailEvenInUnselectedBranches() {
        for (String block : List.of("<cross-reference kind='OTHER' target='p'/>", "<cross-reference kind='PAGE'/>",
                "<cross-reference target='p'/>", "<cross-reference kind='PAGE' target='bad target'/>",
                "<cross-reference kind='${kind}' target='p'/>", "<cross-reference kind='PAGE' target='p' role='bold'/>",
                "<cross-reference kind='PAGE' target='p'><span>Nested</span></cross-reference>",
                "<cross-reference kind='PAGE' target='${broken'/>", "<cross-reference kind='PAGE' target='p' width='20'/>")) {
            assertThrows(TemplateException.class, () -> compile("<if test='shown'><then><p>X</p></then><else>" + block + "</else></if>"));
        }
        String ref = "<cross-reference kind='PAGE' target='p'/>";
        for (String content : List.of("<p>" + ref + "</p>", "<header height='30'>" + ref + "</header><p id='p'>X</p>",
                "<table><columns><column weight='1'/></columns><row><cell>" + ref + "</cell></row></table>")) {
            assertThrows(TemplateException.class, () -> compile(content));
        }
    }

    @Test void referencesCountAgainstExpansionAndWriterBudgetsAndCancellation() throws IOException {
        var document = new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(
                reference("r", "See ", "p", CrossReference.Kind.PAGE), new Paragraph("p", "Body", FORMAT)));
        assertThrows(IllegalArgumentException.class, () -> XmlTemplateWriter.from(document).limits(new XmlTemplateWriter.Limits(1, 10, 10)).prepare());
        var template = XmlTemplateWriter.from(document).prepare().bundle().compile().toBuilder()
                .limits(new DocumentTemplate.Limits(100, 1, 10, 100, 10)).build();
        assertThrows(DocumentTemplate.BindingException.class, () -> template.render(Map.of()));
        assertThrows(com.mirakyux.export4j.model.ExecutionException.class, () -> XmlTemplateWriter.from(document)
                .execution(ExecutionOptions.builder().cancelled(() -> true).build()).prepare());
    }

    private static CrossReference reference(String id, String prefix, String target, CrossReference.Kind kind) {
        return new CrossReference(id, prefix, target, kind, FORMAT);
    }
    private static DocumentTemplate compile(String body) { return XmlTemplate.builder().build().compile(wrap(body)); }
    private static String wrap(String body) { return "<document version='1'>" + body + "</document>"; }
}
