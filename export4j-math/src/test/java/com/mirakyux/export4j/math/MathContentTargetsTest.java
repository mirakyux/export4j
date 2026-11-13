// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math;

import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Paragraph;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Single-target compilation must not inherit the other target's final representation limits. */
class MathContentTargetsTest {
    @Test void visualOnlyDoesNotRequireAnOmmlRepresentation() throws Exception {
        var math = MathContent.builder().build();
        for (String tex : List.of("\\bar{x}", "\\overline{x}")) {
            var visual = math.readVisual("Before $" + tex + "$ after.");
            assertEquals(List.of(new MathContent.Formula(0, tex, false)), visual.formulas());
            assertTrue(visual.degradations().isEmpty());
            var paragraph = (Paragraph) visual.document().blocks().getFirst();
            assertEquals(1, paragraph.vectors().size());
            assertTrue(paragraph.vectors().getFirst().vector().resource().width() > 0);
            assertThrows(MathException.class, () -> math.readDocx("$" + tex + "$"));
            // Existing callers still request both targets and keep their strict contract.
            assertThrows(MathException.class, () -> math.read("$" + tex + "$"));
        }
    }

    @Test void singleTargetResultsMatchTheCorrespondingDualTargetOutput() throws Exception {
        var math = MathContent.builder().build();
        String source = "Repeated $x$ and $x$.\n\n$$\n\\frac{1}{2}\n$$\n";
        var dual = math.read(source);
        var visual = math.readVisual(source);
        var word = math.readDocx(source);
        assertEquals(dual.document(), visual.document());
        assertEquals(dual.docx(), word.document());
        assertEquals(dual.formulas(), visual.formulas());
        assertEquals(dual.formulas(), word.formulas());
        assertEquals(3, word.document().equations().stream().map(e -> e.anchor()).distinct().count());
        assertTrue(visual.degradations().isEmpty());
        assertTrue(word.degradations().isEmpty());
    }

    @Test void representationDiagnosticsOnlyDescribeTheRequestedTarget() throws Exception {
        var math = MathContent.builder().fallbacks(List.of(MathContent.Fallback.TEXT)).build();
        String source = "Before $\\unknown{x_y}$ after.";
        var visual = math.readVisual(source);
        var word = math.readDocx(source);
        assertEquals(source, ((Paragraph) visual.document().blocks().getFirst()).text());
        assertEquals(1, visual.degradations().size());
        assertEquals(MathContent.Target.VISUAL, visual.degradations().getFirst().target());
        assertEquals(1, word.degradations().size());
        assertEquals(MathContent.Target.DOCX, word.degradations().getFirst().target());
        assertTrue(word.document().equations().isEmpty());
        assertEquals(2, math.read(source).degradations().size());
    }

    @Test void singleTargetCallbacksExecuteOnceAndDoNotLeakTheParser() throws Exception {
        var math = MathContent.builder().build();
        var calls = new AtomicInteger();
        var borrowed = new AtomicReference<MathContentSource>();
        MathContent.Author author = parser -> {
            calls.incrementAndGet();
            borrowed.set(parser);
            var first = parser.read("first", "$x$").execute().document();
            var second = parser.read("second", "$y$").execute().document();
            return Document.builder().add(first.blocks().getFirst()).add(second.blocks().getFirst()).build();
        };
        var visual = math.compileVisual(author);
        assertEquals(1, calls.get());
        assertEquals(2, visual.formulas().size());
        assertThrows(IllegalStateException.class, () -> borrowed.get().read("late"));
        var word = math.compileDocx(author);
        assertEquals(2, calls.get());
        assertEquals(2, word.document().equations().size());
        assertThrows(IllegalStateException.class, () -> borrowed.get().read("late"));
    }

    @Test void xmlSharesOccurrenceIdentityAndTheCumulativeBudget() throws Exception {
        String xml = """
                <document version="1" content-format="MARKDOWN" formulas="true">
                  <content id="first"><![CDATA[$x$]]></content>
                  <content id="second"><![CDATA[$x$]]></content>
                </document>
                """;
        MathContent.Author author = parser -> XmlTemplate.builder().contentParser(parser)
                .build().compile(xml).render(Map.of());
        var visual = MathContent.builder().build().compileVisual(author);
        assertEquals(2, visual.formulas().size());
        var first = (Paragraph) visual.document().blocks().getFirst();
        var second = (Paragraph) visual.document().blocks().getLast();
        assertFalse(first.id().equals(second.id()));
        var word = MathContent.builder().build().compileDocx(author);
        assertEquals(2, word.document().equations().stream().map(e -> e.anchor()).distinct().count());
        var limited = MathContent.builder().maxFormulas(1)
                .fallbacks(List.of(MathContent.Fallback.TEXT)).build();
        assertThrows(MathException.class, () -> limited.compileVisual(author));
        assertThrows(MathException.class, () -> limited.compileDocx(author));
    }

    @Test void bothTargetsRejectDiscardedFormulasAndPreserveCancellation() {
        var math = MathContent.builder().fallbacks(List.of(MathContent.Fallback.TEXT)).build();
        var borrowed = new AtomicReference<MathContentSource>();
        MathContent.Author discarded = parser -> {
            borrowed.set(parser);
            parser.read("$x$").execute();
            return Document.builder().paragraph("discarded").build();
        };
        assertThrows(IllegalArgumentException.class, () -> math.compileVisual(discarded));
        assertThrows(IllegalStateException.class, () -> borrowed.get().read("late"));
        assertThrows(IllegalArgumentException.class, () -> math.compileDocx(discarded));
        assertThrows(IllegalStateException.class, () -> borrowed.get().read("late"));
        var options = ExecutionOptions.builder().cancelled(() -> true).build();
        assertThrows(ExecutionException.class,
                () -> ExecutionContext.execute(options, () -> math.readVisual("$x$")));
        assertThrows(ExecutionException.class,
                () -> ExecutionContext.execute(options, () -> math.readDocx("$x$")));
    }

    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void immutableCompilerKeepsConcurrentTargetStateSeparate() throws Exception {
        var math = MathContent.builder().build();
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> visual = () -> math.readVisual("$\\bar{x}$ and $\\bar{x}$").formulas().size();
            Callable<Integer> word = () -> math.readDocx("$x$ and $y$").document().equations().size();
            var futures = executor.invokeAll(List.of(visual, word, visual, word), 20, TimeUnit.SECONDS);
            for (var future : futures) { assertEquals(2, future.get(1, TimeUnit.SECONDS)); }
        }
    }
}
