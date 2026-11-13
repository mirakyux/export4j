// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PdfSemanticOrderingTest {
    @Test void thousandsOfSeparateParagraphsUseBoundedWorkWithoutRecursiveStackGrowth() throws IOException {
        var source = new ArrayList<PdfSemanticImporter.Item>();
        var expected = new ArrayList<String>();
        for (int i = 0; i < 3_000; i++) {
            String id = "paragraph-" + i; expected.add(id);
            source.add(item(id, 0, i * 20, 300, 15));
        }
        Collections.reverse(source);
        var ordered = PdfSemanticAnalysis.order(source, new Budget(10_000), 0);
        assertEquals(expected, ordered.stream().map(value -> value.block().id()).toList());
    }

    @Test void spanningHeadingsKeepIndependentColumnBandsAndOriginalItems() throws IOException {
        var upper = new PdfSemanticImporter.Item(new Heading("upper", "Upper", 1), 0, 0, 300, 15);
        var lower = new PdfSemanticImporter.Item(new Heading("lower", "Lower", 1), 0, 100, 300, 15);
        var a = item("a", 0, 30, 100, 15); var b = item("b", 0, 60, 100, 15);
        var c = item("c", 200, 30, 100, 15); var d = item("d", 200, 60, 100, 15);
        var e = item("e", 0, 130, 100, 15); var f = item("f", 200, 130, 100, 15);
        var ordered = PdfSemanticAnalysis.order(List.of(d, f, lower, b, c, upper, e, a), new Budget(10_000), 0);
        assertEquals(List.of(upper, a, b, c, d, lower, e, f), ordered);
    }

    @Test void unsuccessfulSeparatorComparisonsConsumeTheExistingWorkBudget() {
        var source = new ArrayList<PdfSemanticImporter.Item>();
        for (int i = 0; i < 64; i++) { source.add(item("overlap-" + i, 0, 0, 300, 15)); }
        var failure = assertThrows(IOException.class, () -> PdfSemanticAnalysis.order(source, new Budget(200), 0));
        assertEquals("ordering work budget exceeded", failure.getMessage());
    }

    private static PdfSemanticImporter.Item item(String id, double x, double y, double width, double height) {
        return new PdfSemanticImporter.Item(new Paragraph(id, "Paragraph.", ParagraphFormat.of(12)), x, y, width, height);
    }

    private static final class Budget implements PdfSemanticAnalysis.Context {
        private int remaining;
        private final List<PreparedPdfImport.Source> sources = new ArrayList<>();
        Budget(int maximum) { remaining = maximum; }
        @Override public String id() { return "unused"; }
        @Override public void work() throws IOException {
            if (--remaining < 0) { throw new IOException("ordering work budget exceeded"); }
        }
        @Override public void inferred(String effect, int page) { }
        @Override public List<PreparedPdfImport.Source> sources() { return sources; }
    }
}
