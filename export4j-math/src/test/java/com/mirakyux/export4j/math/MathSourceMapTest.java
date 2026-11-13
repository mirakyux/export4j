// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math;

import com.mirakyux.export4j.adapter.web.MarkdownImporter;
import com.mirakyux.export4j.model.InlineVector;
import com.mirakyux.export4j.model.VectorResource;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MathSourceMapTest {
    @Test void actualRelativeLinkDiagnosticReturnsOriginalUtf16Range() throws Exception {
        String original = "$x$ 😀 [label](relative)";
        String placeholder = "![](slot:0)";
        String rewritten = placeholder + original.substring(3);
        var resource = VectorResource.of(1, 1, 0, List.of(
                new VectorResource.Segment(VectorResource.Operation.MOVE, List.of(0.0, 0.0)),
                new VectorResource.Segment(VectorResource.Operation.LINE, List.of(1.0, -1.0))));
        var policy = ProcessingPolicy.builder().feature("RELATIVE_LINK", ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DEGRADE).build();
        var imported = MarkdownImporter.builder().policy(policy)
                .vector("slot:0", new InlineVector(resource, "x")).build().read(rewritten).execute();
        assertEquals(1, imported.report().issues().size());
        assertNotEquals(original.indexOf("[label]"), imported.report().issues().getFirst().location().startUtf16());
        var report = MathSourceMap.map(imported.report(), original.length(), rewritten.length(), List.of(
                new MathSourceMap.Replacement(0, 3, 0, placeholder.length())));
        var issue = report.issues().getFirst();
        assertEquals(original.indexOf("[label]"), issue.location().startUtf16());
        assertEquals(original.length(), issue.location().endUtf16());
        assertEquals(imported.report().phase(), report.phase());
        assertEquals(imported.report().issues().getFirst().effects(), issue.effects());
        assertEquals("RELATIVE_LINK", issue.feature());
    }

    @Test void generatedRangesExpandToFullOriginalOccurrenceAndGapsStayExact() {
        var replacements = List.of(new MathSourceMap.Replacement(2, 5, 2, 14),
                new MathSourceMap.Replacement(8, 11, 17, 20));
        var issues = List.of(issue(0, 2), issue(3, 7), issue(14, 17), issue(18, 19),
                issue(21, 22), issue(3, 19), issue(17, 17), issue(-1, -1));
        var report = MathSourceMap.map(new ProcessingReport(ProcessingReport.Phase.PREVIEW, issues),
                13, 22, replacements);
        assertEquals(List.of(List.of(0, 2), List.of(2, 5), List.of(5, 8), List.of(8, 11),
                        List.of(12, 13), List.of(2, 11), List.of(8, 11), List.of(-1, -1)),
                report.issues().stream().map(value -> List.of(value.location().startUtf16(), value.location().endUtf16())).toList());
        assertEquals(ProcessingReport.Phase.PREVIEW, report.phase());
        for (var value : report.issues()) {
            assertEquals("paragraph", value.location().blockId());
            assertEquals("BODY", value.location().region());
            assertEquals(ProcessingPolicy.Action.IGNORE, value.action());
            assertEquals(ProcessingPolicy.Treatment.DEGRADE, value.treatment());
        }
    }

    @Test void escapedFallbackLengthChangesAreMappedBeforeSubsequentIssues() {
        String original = "$\\unknown{a_b}$ after";
        String rewritten = "\\$\\\\unknown\\{a\\_b\\}\\$ after";
        int originalEnd = original.indexOf(" after");
        int rewrittenEnd = rewritten.indexOf(" after");
        var report = MathSourceMap.map(new ProcessingReport(ProcessingReport.Phase.ACTUAL,
                List.of(issue(0, rewrittenEnd), issue(rewrittenEnd + 1, rewritten.length()))),
                original.length(), rewritten.length(), List.of(
                        new MathSourceMap.Replacement(0, originalEnd, 0, rewrittenEnd)));
        assertEquals(originalEnd, report.issues().getFirst().location().endUtf16());
        assertEquals(originalEnd + 1, report.issues().getLast().location().startUtf16());
        assertEquals(original.length(), report.issues().getLast().location().endUtf16());
    }

    @Test void invalidMapsCannotReturnPlausibleButWrongSourcePositions() {
        var report = new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of(issue(0, 1)));
        assertThrows(IllegalArgumentException.class, () -> MathSourceMap.map(report, 3, 6, List.of()));
        assertThrows(IllegalArgumentException.class, () -> MathSourceMap.map(report, 3, 6,
                List.of(new MathSourceMap.Replacement(1, 3, 0, 6))));
        assertThrows(IllegalArgumentException.class, () -> MathSourceMap.map(
                new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of(issue(0, 7))), 3, 6,
                List.of(new MathSourceMap.Replacement(0, 3, 0, 6))));
    }

    private static ProcessingIssue issue(int start, int end) {
        return new ProcessingIssue("IMPORT", "MARKDOWN", "MODEL", "TEST", "IMPORT",
                new ProcessingIssue.Location("section", "BODY", "paragraph", List.of(), start, end),
                "retained diagnostic", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE,
                List.of("TEXT_RETAINED"));
    }
}
