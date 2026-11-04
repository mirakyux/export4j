// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.ColumnFlow;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocxColumnFlowConversionTest {
    @Test void containerPageReferencesBeforeInsideAndAfterFlowRetargetToFirstRetainedBlock() throws Exception {
        var field = new InlineField(InlineField.Kind.REFERENCE_PAGE, "article", 42, new InlineStyle("bold", 12, 0x224488, -2, true, false));
        var firstTable = Table.builder("first-table").columns(new ColumnWidth.Fraction(1)).row("TABLE").build();
        for (var first : List.<Block>of(new Paragraph("empty-first", "", ParagraphFormat.of(12)), firstTable)) {
            var source = Document.builder()
                    .add(Paragraph.mixed("before", List.of(field), ParagraphFormat.of(12)))
                    .add(new ColumnFlow("article", List.of(first, Paragraph.mixed("inside", List.of(field), ParagraphFormat.of(12))), 2, 18, true))
                    .add(Paragraph.mixed("after", List.of(field), ParagraphFormat.of(12))).build();
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                var policy = ProcessingPolicy.builder().feature("COLUMN_FLOW", action, ProcessingPolicy.Treatment.DEGRADE).build();
                var adapter = new DocxAdapter();
                var prepared = DocumentConversion.from(source).policy(policy).prepare(adapter.conversionTarget());
                var output = new ByteArrayOutputStream(); var actual = prepared.to(output);
                var body = adapter.read(new ByteArrayInputStream(output.toByteArray()), DocxAdapter.Policy.STRICT).document().sections().getFirst().blocks();
                assertEquals(List.of("before", first.id(), "inside", "after"), body.stream().map(Block::id).toList());
                assertEquals(first, body.get(1));
                for (int index : List.of(0, 2, 3)) {
                    assertEquals(List.of(new Paragraph.FieldSpan(0, field.toBuilder().targetId(first.id()).build())),
                            assertInstanceOf(Paragraph.class, body.get(index)).fields());
                }
                assertEquals(3, actual.report().issues().stream().filter(issue -> issue.effects().contains("PAGE_REFERENCE_TARGET_REPLACED_WITH_FIRST_RETAINED_BLOCK")).count());
                assertEquals(prepared.preview().issues(), actual.report().issues());
            }
        }
    }

    private static Document source() {
        var link = new Paragraph("link", List.of(new InlineText("LINK", InlineStyle.of(12), "https://example.com/columns")), ParagraphFormat.of(12));
        var table = Table.builder("table").columns(new ColumnWidth.Fraction(1)).header("HEADER").row("CELL").build();
        return Document.builder().paragraph("before", "BEFORE")
                .add(new ColumnFlow("article", List.of(link, table, new Paragraph("last", "COLUMN-END", ParagraphFormat.of(12))), 3, 16, true))
                .paragraph("after", "AFTER").build();
    }
    private static SectionedDocument sections(Document source) {
        return new SectionedDocument(source.metadata(), List.of(new Section("document", source.pageSettings(), source.blocks(), 0)));
    }

    @Test void directAndStrictColumnExportRejectBeforeWritingAnyBytes() throws Exception {
        var source = source(); var adapter = new DocxAdapter(); var output = new ByteArrayOutputStream();
        output.write("existing".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> adapter.write(source, output));
        assertEquals("existing", output.toString(StandardCharsets.UTF_8));
        var failure = assertThrows(ProcessingException.class, () -> adapter.conversionTarget().writer()
                .write(sections(source), List.of(), ProcessingPolicy.STRICT, output));
        assertEquals("COLUMN_FLOW", failure.issue().feature());
        assertEquals("article", failure.issue().location().blockId());
        assertEquals("existing", output.toString(StandardCharsets.UTF_8));
    }

    @Test void explicitDegradationRetainsParagraphsTableLinksAndTrailingBodyWhileReportingLayoutLoss() throws Exception {
        var source = source(); var adapter = new DocxAdapter();
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var policy = ProcessingPolicy.builder().feature("COLUMN_FLOW", action, ProcessingPolicy.Treatment.DEGRADE).build();
            var prepared = DocumentConversion.from(source).policy(policy).prepare(adapter.conversionTarget());
            var bytes = new ByteArrayOutputStream(); var actual = prepared.to(bytes);
            assertEquals(prepared.preview().issues(), actual.report().issues());
            assertEquals(1, actual.report().losses().size());
            assertEquals(action == ProcessingPolicy.Action.WARN ? 1 : 0, actual.report().warnings().size());
            var issue = actual.report().losses().getFirst();
            assertEquals("COLUMN_FLOW", issue.feature()); assertEquals(ProcessingPolicy.Treatment.DEGRADE, issue.treatment());
            assertEquals(List.of("LAYOUT_REMOVED_CONTENT_RETAINED"), issue.effects());
            assertEquals("BODY", issue.location().region()); assertEquals("article", issue.location().blockId());
            var restored = adapter.read(new ByteArrayInputStream(bytes.toByteArray()), DocxAdapter.Policy.STRICT).document();
            var body = restored.sections().getFirst().blocks();
            assertEquals(List.of("before", "link", "table", "last", "after"), body.stream().map(Block::id).toList());
            assertEquals("LINK", assertInstanceOf(Paragraph.class, body.get(1)).text());
            assertEquals("https://example.com/columns", ((Paragraph) body.get(1)).inlines().getFirst().hyperlink());
            assertEquals(List.of("HEADER", "CELL"), assertInstanceOf(Table.class, body.get(2)).rows().stream()
                    .map(row -> row.cells().getFirst().text()).toList());
            assertEquals("COLUMN-END", ((Paragraph) body.get(3)).text()); assertEquals("AFTER", ((Paragraph) body.getLast()).text());
            assertInstanceOf(ColumnFlow.class, actual.document().sections().getFirst().blocks().get(1));
        }
    }

    @Test void columnContentCannotBeDiscardedOrPreservedByAnUnavailableTreatment() throws Exception {
        var output = new ByteArrayOutputStream(); output.write("existing".getBytes(StandardCharsets.UTF_8));
        for (var treatment : List.of(ProcessingPolicy.Treatment.DISCARD, ProcessingPolicy.Treatment.PRESERVE,
                ProcessingPolicy.Treatment.SKIP_BLOCK)) {
            var policy = ProcessingPolicy.builder().feature("COLUMN_FLOW", ProcessingPolicy.Action.IGNORE, treatment).build();
            assertThrows(IllegalArgumentException.class, () -> new DocxAdapter().conversionTarget().writer()
                    .write(sections(source()), List.of(), policy, output));
            assertEquals("existing", output.toString(StandardCharsets.UTF_8));
        }
    }
}
