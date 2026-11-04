// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.GridLayout;
import com.mirakyux.export4j.model.GridItem;
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
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
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

class DocxGridLayoutConversionTest {
    private static Paragraph references(String id) {
        return Paragraph.mixed(id, List.of(
                new InlineField(InlineField.Kind.REFERENCE_PAGE, "article", 30, InlineStyle.of(12)),
                new InlineField(InlineField.Kind.REFERENCE_PAGE, "left", 30, InlineStyle.of(12)),
                new InlineField(InlineField.Kind.REFERENCE_PAGE, "right", 30, InlineStyle.of(12))), ParagraphFormat.of(12));
    }

    @Test void gridAndItemReferencesRetargetAcrossForwardBackwardAndNestedCellFields() throws Exception {
        var table = new Table("first-table", List.of(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1)),
                List.of(new TableRow(List.of(
                        TableCell.builder().blocks(List.of(references("nested"))).build(),
                        TableCell.mixed(references("inline").content(), CellFormat.of(12))))), 0, TableFormat.DEFAULT);
        var empty = new Paragraph("empty-right", "", ParagraphFormat.of(12));
        var source = Document.builder().add(references("before"))
                .add(new GridLayout("article", 2, 12, 12, List.of(
                        new GridItem("left", 1, 1, 1, List.of(table)), new GridItem("right", 1, 2, 1, List.of(empty)))))
                .add(references("after")).build();
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var policy = ProcessingPolicy.builder().feature("GRID_LAYOUT", action, ProcessingPolicy.Treatment.DEGRADE).build();
            var adapter = new DocxAdapter();
            var prepared = DocumentConversion.from(source).policy(policy).prepare(adapter.conversionTarget());
            var output = new ByteArrayOutputStream(); var actual = prepared.to(output);
            var body = adapter.read(new ByteArrayInputStream(output.toByteArray()), DocxAdapter.Policy.STRICT).document().sections().getFirst().blocks();
            assertEquals(List.of("before", "first-table", "empty-right", "after"), body.stream().map(Block::id).toList());
            var restoredTable = assertInstanceOf(Table.class, body.get(1));
            var cells = restoredTable.rows().getFirst().cells();
            for (var paragraph : List.of((Paragraph) body.getFirst(), (Paragraph) cells.getFirst().blocks().getFirst(), (Paragraph) body.getLast())) {
                assertEquals(List.of("first-table", "first-table", "empty-right"), paragraph.fields().stream().map(span -> span.field().targetId()).toList());
                assertEquals(List.of(0, 1, 2), paragraph.fields().stream().map(Paragraph.FieldSpan::sourceIndex).toList());
            }
            assertEquals(List.of("first-table", "first-table", "empty-right"), cells.get(1).fields().stream().map(span -> span.field().targetId()).toList());
            assertEquals(empty, body.get(2));
            assertEquals(12, actual.report().issues().stream().filter(issue -> issue.effects().contains("PAGE_REFERENCE_TARGET_REPLACED_WITH_FIRST_RETAINED_BLOCK")).count());
            assertEquals(prepared.preview().issues(), actual.report().issues());
        }
    }

    private static Document source() {
        var link = new Paragraph("link", List.of(new InlineText("LINK", InlineStyle.of(12), "https://example.com/columns")), ParagraphFormat.of(12));
        var table = Table.builder("table").columns(new ColumnWidth.Fraction(1)).header("HEADER").row("CELL").build();
        return Document.builder().paragraph("before", "BEFORE")
                .add(new GridLayout("article", 12, 16, 11, List.of(
                        new GridItem("left", 1, 1, 8, List.of(link, table)),
                        new GridItem("right", 1, 9, 4, List.of(new Paragraph("last", "COLUMN-END", ParagraphFormat.of(12)))))))
                .paragraph("after", "AFTER").build();
    }
    private static SectionedDocument sections(Document source) {
        return new SectionedDocument(source.metadata(), List.of(new Section("document", source.pageSettings(), source.blocks(), 0)));
    }

    @Test void directAndStrictGridExportRejectBeforeWritingAnyBytes() throws Exception {
        var source = source(); var adapter = new DocxAdapter(); var output = new ByteArrayOutputStream();
        output.write("existing".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> adapter.write(source, output));
        assertEquals("existing", output.toString(StandardCharsets.UTF_8));
        var failure = assertThrows(ProcessingException.class, () -> adapter.conversionTarget().writer()
                .write(sections(source), List.of(), ProcessingPolicy.STRICT, output));
        assertEquals("GRID_LAYOUT", failure.issue().feature());
        assertEquals("article", failure.issue().location().blockId());
        assertEquals("existing", output.toString(StandardCharsets.UTF_8));
    }

    @Test void explicitDegradationRetainsParagraphsTableLinksAndTrailingBodyWhileReportingLayoutLoss() throws Exception {
        var source = source(); var adapter = new DocxAdapter();
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var policy = ProcessingPolicy.builder().feature("GRID_LAYOUT", action, ProcessingPolicy.Treatment.DEGRADE).build();
            var prepared = DocumentConversion.from(source).policy(policy).prepare(adapter.conversionTarget());
            var bytes = new ByteArrayOutputStream(); var actual = prepared.to(bytes);
            assertEquals(prepared.preview().issues(), actual.report().issues());
            assertEquals(1, actual.report().losses().size());
            assertEquals(action == ProcessingPolicy.Action.WARN ? 1 : 0, actual.report().warnings().size());
            var issue = actual.report().losses().getFirst();
            assertEquals("GRID_LAYOUT", issue.feature()); assertEquals(ProcessingPolicy.Treatment.DEGRADE, issue.treatment());
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
            assertInstanceOf(GridLayout.class, actual.document().sections().getFirst().blocks().get(1));
        }
    }

    @Test void gridContentCannotBeDiscardedOrPreservedByAnUnavailableTreatment() throws Exception {
        var output = new ByteArrayOutputStream(); output.write("existing".getBytes(StandardCharsets.UTF_8));
        for (var treatment : List.of(ProcessingPolicy.Treatment.DISCARD, ProcessingPolicy.Treatment.PRESERVE,
                ProcessingPolicy.Treatment.SKIP_BLOCK)) {
            var policy = ProcessingPolicy.builder().feature("GRID_LAYOUT", ProcessingPolicy.Action.IGNORE, treatment).build();
            assertThrows(IllegalArgumentException.class, () -> new DocxAdapter().conversionTarget().writer()
                    .write(sections(source()), List.of(), policy, output));
            assertEquals("existing", output.toString(StandardCharsets.UTF_8));
        }
    }
}
