// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingPolicy.Action;
import com.mirakyux.export4j.model.processing.ProcessingPolicy.Treatment;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentConversionTest {
    @Test void untouchedContentLossIsReportedAndIgnoreOnlyChangesWarnings() throws Exception {
        String source = "<p id=edit>OLD</p><p id=untouched style='color:red'>Keep</p>";
        var warn = ProcessingPolicy.builder().defaultRule(Action.WARN, Treatment.DEGRADE).build();
        var ignore = ProcessingPolicy.builder().defaultRule(Action.IGNORE, Treatment.DEGRADE).build();
        var warned = new HtmlImporter().conversion(source).policy(warn).edit(editor -> editor.replaceText("OLD", "READY")).prepare(new MarkdownAdapter().conversionTarget());
        var ignored = new HtmlImporter().conversion(source).policy(ignore).edit(editor -> editor.replaceText("OLD", "READY")).prepare(new MarkdownAdapter().conversionTarget());
        var a = new ByteArrayOutputStream(); var warnedResult = warned.to(a); var b = new ByteArrayOutputStream(); var ignoredResult = ignored.to(b);
        assertEquals(a.toString(), b.toString()); assertTrue(a.toString(StandardCharsets.UTF_8).contains("READY"));
        assertEquals(1, ignoredResult.report().losses().size()); assertTrue(ignoredResult.report().warnings().isEmpty());
        var loss = ignoredResult.report().losses().getFirst(); assertEquals("TEXT_STYLE", loss.feature()); assertEquals("untouched", loss.location().blockId());
        assertEquals(warned.preview().issues(), warnedResult.report().issues());
        assertEquals(0xff0000, ((Paragraph) ignored.candidate().sections().getFirst().blocks().get(1)).inlines().getFirst().style().rgb());
    }
    @Test void strictExportFailureOccursDuringPreparationBeforeDestinationIo() {
        var error = assertThrows(ProcessingException.class, () -> new HtmlImporter().conversion("<p style='color:red'>X</p>").prepare(new MarkdownAdapter().conversionTarget()));
        assertEquals("EXPORT", error.issue().stage()); assertEquals("TEXT_STYLE", error.issue().feature());
    }
    @Test void oneReportContainsImportEditingAndExportLosses() throws Exception {
        var policy = ProcessingPolicy.builder().defaultRule(Action.WARN, Treatment.DEGRADE).build();
        var target = MarkdownAdapter.builder().fontRole("bold", new FontStyle("sans-serif", 700, false)).build().conversionTarget();
        var prepared = new HtmlImporter().conversion("<blockquote><p>Quote</p></blockquote><p><b>A</b>B</p>")
                .policy(policy).edit(editor -> editor.replaceText("AB", "Z")).prepare(target);
        var output = new ByteArrayOutputStream(); var result = prepared.to(output);
        assertTrue(result.report().losses().stream().anyMatch(issue -> issue.stage().equals("IMPORT") && issue.feature().equals("BLOCK_QUOTE")));
        assertTrue(result.report().losses().stream().anyMatch(issue -> issue.stage().equals("EDIT") && issue.feature().equals("MIXED_RUN_STYLE")));
        assertTrue(result.report().losses().stream().anyMatch(issue -> issue.stage().equals("EXPORT") && issue.feature().equals("PARAGRAPH_STYLE")));
        assertEquals(prepared.preview().issues(), result.report().issues());
    }
    @Test void sharedPolicyPriorityIsOperationThenFeatureThenFormatThenDefault() throws Exception {
        var policy = ProcessingPolicy.builder().defaultRule(Action.IGNORE, Treatment.DEGRADE)
                .format("MARKDOWN", Action.WARN, Treatment.DEGRADE)
                .feature("TEXT_STYLE", Action.ERROR, Treatment.DEGRADE).operation("EXPORT", Action.IGNORE, Treatment.DEGRADE).build();
        var prepared = new HtmlImporter().conversion("<p style='color:red'>X</p>").policy(policy).prepare(new MarkdownAdapter().conversionTarget());
        var result = prepared.to(new ByteArrayOutputStream());
        assertEquals(Action.IGNORE, result.report().issues().getFirst().action()); assertTrue(result.report().warnings().isEmpty());
    }
    @Test void unavailableTreatmentsFailAndMetadataRemovalIsExplicit() throws Exception {
        var discard = ProcessingPolicy.builder().defaultRule(Action.IGNORE, Treatment.DISCARD).build();
        assertThrows(IllegalArgumentException.class, () -> new HtmlImporter().conversion("<p style='color:red'>X</p>").policy(discard).prepare(new MarkdownAdapter().conversionTarget()));
        var document = Document.builder().metadata(new com.mirakyux.export4j.model.DocumentMetadata("Title", "Author")).paragraph("p", "Body").build();
        var prepared = DocumentConversion.from(document).policy(discard).prepare(new MarkdownAdapter().conversionTarget());
        assertEquals("METADATA", prepared.preview().losses().getFirst().feature());
    }
    @Test void externalNotesAreCheckedEvenWhenTheirAnchorIsNotEdited() throws Exception {
        var document = Document.builder().paragraph("p", "Body").build();
        var input = DocumentConversion.Input.of(document, new com.mirakyux.export4j.model.processing.ProcessingReport(com.mirakyux.export4j.model.processing.ProcessingReport.Phase.ACTUAL, List.of()));
        var note = new Note("n", "p", "Private note", Note.Kind.FOOTNOTE);
        var policy = ProcessingPolicy.builder().feature("NOTES", Action.IGNORE, Treatment.DISCARD).build();
        var prepared = DocumentConversion.from(input.document(), List.of(note)).policy(policy).prepare(new HtmlAdapter().conversionTarget());
        var output = new ByteArrayOutputStream(); var result = prepared.to(output);
        assertEquals(1, result.report().losses().size()); assertEquals("NOTES", result.report().issues().getFirst().feature());
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("Private note")); assertEquals(List.of(note), result.notes());
    }
    @Test void markdownToHtmlUsesRealSourceAndTargetFactories() throws Exception {
        var target = HtmlAdapter.builder().fontRole("bold", new FontStyle("sans-serif", 700, false)).build().conversionTarget();
        var prepared = new MarkdownImporter().conversion("# Title\n\nOLD **Strong**").edit(editor -> editor.replaceText("OLD", "READY")).prepare(target);
        var output = new ByteArrayOutputStream(); var result = prepared.to(output);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("READY")); assertTrue(result.report().losses().isEmpty());
        assertEquals("MARKDOWN", prepared.sourceFormat()); assertEquals("HTML", prepared.targetFormat());
    }
}
