// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.GridLayout;
import com.mirakyux.export4j.model.GridItem;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
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
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GridLayoutExportTest {
    private static Document source() {
        var link = new Paragraph("link", List.of(new InlineText("LINK", InlineStyle.of(12), "https://example.com/columns")), ParagraphFormat.of(12));
        var table = Table.builder("table").columns(new ColumnWidth.Fraction(1)).header("HEADER").row("CELL").build();
        return Document.builder().paragraph("before", "BEFORE")
                .add(new GridLayout("article", 12, 16, 11, List.of(
                        new GridItem("left", 1, 1, 8, List.of(link, table)),
                        new GridItem("right", 1, 9, 4, List.of(new Paragraph("last", "COLUMNEND", ParagraphFormat.of(12)))))))
                .paragraph("after", "AFTER").build();
    }
    private static SectionedDocument sections() {
        var source = source();
        return new SectionedDocument(source.metadata(), List.of(new Section("document", source.pageSettings(), source.blocks(), 0)));
    }
    private static ProcessingPolicy degrade(ProcessingPolicy.Action action) {
        return ProcessingPolicy.builder().feature("GRID_LAYOUT", action, ProcessingPolicy.Treatment.DEGRADE).build();
    }

    @Test void oldExportPoliciesAndStrictConversionRejectGridBeforeWritingAnyBytes() throws Exception {
        var output = new ByteArrayOutputStream(); output.write("existing".getBytes(StandardCharsets.UTF_8));
        for (var action : UnsupportedAction.values()) {
            var policy = ExportPolicy.builder().defaultAction(action).build();
            for (var request : List.of(HtmlAdapter.builder().policy(policy).build().export(source()),
                    MarkdownAdapter.builder().policy(policy).build().export(source()))) {
                var failure = assertThrows(ExportException.class, () -> request.to(output));
                assertEquals(ExportFeature.GRID_LAYOUT, failure.diagnostic().feature());
                assertEquals("article", failure.diagnostic().location().blockId());
                assertEquals("existing", output.toString(StandardCharsets.UTF_8));
            }
        }
        for (var target : List.of(new HtmlAdapter().conversionTarget(), new MarkdownAdapter().conversionTarget())) {
            var failure = assertThrows(ProcessingException.class, () -> target.writer().write(sections(), List.of(), ProcessingPolicy.STRICT, output));
            assertEquals("GRID_LAYOUT", failure.issue().feature()); assertEquals("existing", output.toString(StandardCharsets.UTF_8));
        }
    }

    @Test void explicitDegradationPreservesGridReadingOrderLinksTablesAndTrailingBody() throws Exception {
        for (var target : List.of(new HtmlAdapter().conversionTarget(), new MarkdownAdapter().conversionTarget())) {
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                var prepared = DocumentConversion.from(source()).policy(degrade(action)).prepare(target);
                var output = new ByteArrayOutputStream(); var actual = prepared.to(output);
                assertEquals(prepared.preview().issues(), actual.report().issues());
                assertEquals(1, actual.report().losses().size());
                assertEquals(action == ProcessingPolicy.Action.WARN ? 1 : 0, actual.report().warnings().size());
                var issue = actual.report().losses().getFirst();
                assertEquals("GRID_LAYOUT", issue.feature()); assertEquals(ProcessingPolicy.Treatment.DEGRADE, issue.treatment());
                assertEquals(List.of("LAYOUT_REMOVED_CONTENT_RETAINED"), issue.effects());
                assertEquals("article", issue.location().blockId()); assertEquals("BODY", issue.location().region());
                String text = output.toString(StandardCharsets.UTF_8);
                int previous = -1;
                for (String token : List.of("BEFORE", "LINK", "HEADER", "CELL", "COLUMNEND", "AFTER")) {
                    int index = text.indexOf(token); assertTrue(index > previous, token); previous = index;
                }
                assertTrue(text.contains("https://example.com/columns"));
                assertTrue(text.contains(target.format().equals("HTML") ? "<table" : "| HEADER |"));
                assertInstanceOf(GridLayout.class, actual.document().sections().getFirst().blocks().get(1));
            }
        }
    }

    @Test void unsupportedTreatmentsCannotDiscardGridAndFailWithoutChangingTheStream() throws Exception {
        var output = new ByteArrayOutputStream(); output.write("existing".getBytes(StandardCharsets.UTF_8));
        for (var target : List.of(new HtmlAdapter().conversionTarget(), new MarkdownAdapter().conversionTarget())) {
            for (var treatment : List.of(ProcessingPolicy.Treatment.DISCARD, ProcessingPolicy.Treatment.PRESERVE,
                    ProcessingPolicy.Treatment.SKIP_BLOCK)) {
                var policy = ProcessingPolicy.builder().feature("GRID_LAYOUT", ProcessingPolicy.Action.IGNORE, treatment).build();
                assertThrows(IllegalArgumentException.class, () -> target.writer().write(sections(), List.of(), policy, output));
                assertEquals("existing", output.toString(StandardCharsets.UTF_8));
            }
        }
    }

    @Test void degradedGridStillChargeDescendantsToOriginalSourceBudgets() throws Exception {
        var limits = ExportLimits.builder().maxBlocks(3).build();
        var output = new ByteArrayOutputStream(); output.write("existing".getBytes(StandardCharsets.UTF_8));
        for (var target : List.of(HtmlAdapter.builder().limits(limits).build().conversionTarget(),
                MarkdownAdapter.builder().limits(limits).build().conversionTarget())) {
            var failure = assertThrows(ExportException.class, () -> target.writer()
                    .write(sections(), List.of(), degrade(ProcessingPolicy.Action.IGNORE), output));
            assertEquals(ExportException.Code.LIMIT_EXCEEDED, failure.code());
            assertEquals("link", failure.diagnostic().location().blockId());
            assertEquals("existing", output.toString(StandardCharsets.UTF_8));
        }
    }
}
