// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.docx.DocxMath;
import com.mirakyux.export4j.adapter.docx.DocxMathDocument;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownMathTest {
    private final MarkdownMath math = new MarkdownMath();

    @Test void protectsActualMarkdownContextsAndCurrency() {
        String source = "Before $x_1$ after\r\n\r\n`$code$` and \\$escaped\\$\r\n"
                + "[link $label$](https://example.com/$path$) ![$alt$](asset:$image$)\r\n"
                + "<span title='$attribute$'>text</span>\r\n\r\n"
                + "```tex\r\n$code$\r\n```\r\n\r\n    $indented$\r\n\r\n"
                + "Cost $5 and $10. Equation $y$ then.\r\n\r\n$$\r\n\\frac{1}{2}\r\n$$\r\n";
        var found = math.scan(source);
        assertEquals(List.of("x_1", "y", "\\frac{1}{2}"), found.stream().map(MarkdownMath.FormulaSpan::tex).toList());
        assertTrue(found.get(2).display());
        for (var span : found) { assertTrue(source.substring(span.start(), span.end()).startsWith("$")); }
    }

    @Test void utf16AndEscapeParityRemainSourceBased() {
        String source = "😀 $a$ \\\\$b$ \\$not\\$";
        var found = math.scan(source);
        assertEquals(List.of("a", "b"), found.stream().map(MarkdownMath.FormulaSpan::tex).toList());
        assertEquals(3, found.getFirst().start());
    }

    @Test void doesNotRescanLongUnmatchedCurrencyPrefixes() {
        assertTrue(math.scan("$a ".repeat(20_000)).isEmpty());
        assertEquals(List.of("x"), math.scan("$bad $x$").stream().map(MarkdownMath.FormulaSpan::tex).toList());
    }

    @Test void nativeOccurrencesAndTableAnchorsSurviveStandardDocxRoundtrip() throws Exception {
        String source = "Before $x^2$ after and $x^2$.\n\n"
                + "| Type | Equation |\n| --- | --- |\n| Fraction | $\\frac{a}{b}$ |\n\n"
                + "$$\n\\sqrt[3]{x}\n$$\n";
        var result = math.docx(source);
        assertTrue(result.degradations().isEmpty());
        assertTrue(result.importReport().issues().isEmpty());
        var equations = result.document().equations();
        assertEquals(4, equations.size());
        assertEquals(equations.get(0).expression(), equations.get(1).expression());
        assertFalse(equations.get(0).anchor().equals(equations.get(1).anchor()));
        assertEquals(7, equations.get(0).anchor().utf16());
        assertTrue(equations.get(2).expression() instanceof DocxMath.Fraction);
        assertEquals(DocxMathDocument.Placement.DISPLAY_CENTER, equations.get(3).placement());
        var output = new ByteArrayOutputStream();
        new DocxAdapter().writeMath(result.document(), output);
        var restored = new DocxAdapter().readMath(new ByteArrayInputStream(output.toByteArray()), DocxAdapter.Policy.STRICT).document();
        assertEquals(equations.stream().map(DocxMathDocument.Equation::expression).toList(),
                restored.equations().stream().map(DocxMathDocument.Equation::expression).toList());
        assertEquals(equations.stream().map(DocxMathDocument.Equation::anchor).toList(),
                restored.equations().stream().map(DocxMathDocument.Equation::anchor).toList());
        var table = (Table) result.document().semantic().body().sections().getFirst().blocks().get(1);
        assertFalse(table.rows().get(1).cells().get(1).hasBlocks());
    }

    @Test void stylesAroundNativeMarkerRemainAndMarkerUsesDefaultStyle() throws Exception {
        var result = math.docx("**Before $x$ after**");
        var p = (Paragraph) result.document().semantic().body().sections().getFirst().blocks().getFirst();
        assertEquals("Before \ufffc after", p.text());
        assertEquals(3, p.inlines().size());
        assertEquals("bold", p.inlines().getFirst().style().fontRole());
        assertEquals("", p.inlines().get(1).style().fontRole());
        assertEquals("bold", p.inlines().getLast().style().fontRole());
    }

    @Test void explicitFallbackRetainsLiteralTexAndReportsEachFailure() throws Exception {
        var permissive = MarkdownMath.builder().fallback(MarkdownMath.Fallback.KEEP_SOURCE).build();
        var result = permissive.docx("Before $\\unknown{a_b}$ and $x$ after.");
        assertEquals(1, result.degradations().size());
        assertEquals(MathException.Reason.UNSUPPORTED_SYNTAX, result.degradations().getFirst().reason());
        var p = (Paragraph) result.document().semantic().body().sections().getFirst().blocks().getFirst();
        assertEquals("Before $\\unknown{a_b}$ and \ufffc after.", p.text());
        assertEquals(1, result.document().equations().size());
        assertThrows(MathException.class, () -> math.docx("$\\unknown{x}$"));
    }

    @Test void inputFormulaAndTotalGeometryLimitsCannotFallback() {
        var builder = MarkdownMath.builder().fallback(MarkdownMath.Fallback.KEEP_SOURCE);
        assertLimit(() -> builder.maxFormulas(1).build().scan("$a$ and $b$"));
        assertLimit(() -> builder.maxInputLength(3).build().scan("long"));
        assertLimit(() -> MarkdownMath.builder().fallback(MarkdownMath.Fallback.KEEP_SOURCE)
                .options(MathOptions.builder().maxSourceLength(2).build()).build().docx("$long$"));
        assertLimit(() -> MarkdownMath.builder().fallback(MarkdownMath.Fallback.KEEP_SOURCE)
                .maxTotalSegments(4).build().docx("$a$ and $b$"));
    }

    @Test void cancellationSurvivesFallbackAndCanRecoverOnSameThread() throws Exception {
        var control = ExecutionOptions.builder().cancelled(() -> true).build();
        var permissive = MarkdownMath.builder().fallback(MarkdownMath.Fallback.KEEP_SOURCE).build();
        var failure = assertThrows(ExecutionException.class,
                () -> ExecutionContext.execute(control, () -> permissive.docx("$x$")));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        assertEquals(1, permissive.docx("$x$").document().equations().size());
    }

    @Test void vectorImportRegistersResourcesAndRetainsMixedText() throws Exception {
        var result = math.vector("Before $x^2$ after.", importer -> importer.paragraphFormat(
                ParagraphFormat.builder().fontSize(12).lineHeight(40).build()));
        var p = (Paragraph) result.document().blocks().getFirst();
        assertEquals("Before \ufffc after.", p.text());
        assertEquals(7, p.vectors().getFirst().sourceIndex());
        assertEquals("x^2", p.vectors().getFirst().vector().logicalText());
    }

    @Test void importWarningsAndErrorsPointBackToOriginalSource() throws Exception {
        String source = "$x$ 😀 [label](relative)";
        var policy = ProcessingPolicy.builder().feature("RELATIVE_LINK", ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DEGRADE).build();
        var result = math.vector(source, importer -> importer.policy(policy));
        assertEquals(source.indexOf("[label]"), result.importReport().issues().getFirst().location().startUtf16());
        assertEquals(source.length(), result.importReport().issues().getFirst().location().endUtf16());
        var rejected = assertThrows(ProcessingException.class, () -> math.vector(source));
        assertEquals(source.indexOf("[label]"), rejected.issue().location().startUtf16());
        assertEquals(source.length(), rejected.issue().location().endUtf16());
    }

    @Test void namespaceCollisionInputAndMixedContainersBindEverySlot() throws Exception {
        String source = "x".repeat(2_000) + "export4j-formula:0:3 $x$";
        assertEquals(1, math.docx(source).document().equations().size());
        var policy = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DEGRADE).build();
        var heading = math.docx("# $x$", importer -> importer.policy(policy));
        var item = math.docx("- $x$", importer -> importer.policy(policy));
        assertEquals(1, heading.document().equations().size());
        assertEquals(1, item.document().equations().size());
        assertTrue(heading.importReport().issues().isEmpty());
        assertTrue(item.importReport().issues().isEmpty());
        assertEquals(0, heading.document().equations().getFirst().anchor().utf16());
        assertEquals(0, item.document().equations().getFirst().anchor().utf16());
    }

    private static void assertLimit(org.junit.jupiter.api.function.Executable operation) {
        assertEquals(MathException.Reason.LIMIT_EXCEEDED, assertThrows(MathException.class, operation).reason());
    }
}
