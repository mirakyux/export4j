// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math;

import com.mirakyux.export4j.adapter.docx.DocxMath;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TexToDocxTest {
    @Test void producesEditableFractionRadicalAndCombinedScripts() {
        var actual = parse("\\frac{x_1^2}{\\sqrt[3]{y}}");
        var expected = new DocxMath.Fraction(
                new DocxMath.SubSuperscript(italic("x"), plain("1"), plain("2")),
                new DocxMath.Radical(italic("y"), Optional.of(plain("3"))));
        assertEquals(expected, actual);
        assertEquals(new DocxMath.Fraction(italic("a"), italic("b")), parse("\\frac a b"));
        assertEquals(new DocxMath.Superscript(italic("x"), italic("α")), parse("x^\\alpha"));
    }

    @Test void explicitInnerStylesOverrideTheirLexicalOuterStyle() {
        assertEquals(new DocxMath.Sequence(List.of(
                new DocxMath.Run("x", DocxMath.Style.BOLD),
                new DocxMath.Run("y", DocxMath.Style.ITALIC),
                new DocxMath.Run("z", DocxMath.Style.BOLD))), parse("\\mathbf{x\\mathit{y}z}"));
        assertEquals(new DocxMath.Fraction(new DocxMath.Run("x", DocxMath.Style.BOLD),
                new DocxMath.Run("y", DocxMath.Style.BOLD)), parse("\\mathbf{\\frac{x}{y}}"));
    }

    @Test void textWhitespaceEscapesAndSupplementaryCharactersRemainExact() {
        assertEquals(new DocxMath.Run("rate  = 5% & x_1 $", DocxMath.Style.NORMAL_TEXT),
                parse("\\text{rate  = 5\\% \\& x\\_1 \\$}"));
        assertEquals(new DocxMath.Run("汉😀 𝑥", DocxMath.Style.NORMAL_TEXT), parse("\\text{汉😀 𝑥}"));
        assertEquals(italic("𝑥"), parse("𝑥"));
        assertEquals(new DocxMath.Sequence(List.of(plain("sin"), italic("x"))), parse("\\sin x"));
        assertEquals(new DocxMath.Sequence(List.of(italic("α"), plain("≤"), plain("Γ"))), parse("\\alpha \\leq \\Gamma"));
    }

    @Test void rejectsUnmodeledConstructsRatherThanInventingWordSemantics() {
        for (var source : List.of("\\sum_{i=1}^n x_i", "\\int x", "\\lim_{x=0}",
                "\\begin{matrix}x&y\\end{matrix}", "\\left(x\\right)", "\\hat{x}",
                "\\displaystyle x", "\\dfrac{x}{y}", "\\!x", "x%comment", "\\text{a_b}")) {
            failure(source, MathException.Reason.UNSUPPORTED_SYNTAX);
        }
    }

    @Test void rejectsIncompleteMalformedAndDuplicateScriptArguments() {
        for (var source : List.of("", " ", "x^", "x_", "_x", "x^a^b", "x_a_b", "x^{a", "x}",
                "\\frac{x}", "\\sqrt[2{x}", "\\sqrt[n^]]x", "\\text{unfinished", "\\", "\\frac{x}{y}}")) {
            failure(source, MathException.Reason.INVALID_SYNTAX);
        }
        failure("\ud800", MathException.Reason.INVALID_SYNTAX);
        failure("x\u0000y", MathException.Reason.INVALID_SYNTAX);
    }

    @Test void allRecursiveArgumentPathsAndCommandCountsAreBounded() {
        var depth = MathOptions.builder().maxDepth(3).build();
        for (var source : List.of("\\sqrt\\sqrt\\sqrt\\sqrt x", "\\frac\\frac\\frac a b c d",
                "x^{x^{x^{x}}}", "\\mathrm\\mathrm\\mathrm x", "\\sqrt[\\sqrt[\\sqrt[2]x]x]x")) {
            var error = assertThrows(MathException.class, () -> TexToDocx.parse(source, depth));
            assertEquals(MathException.Reason.LIMIT_EXCEEDED, error.reason(), source);
        }
        var commands = MathOptions.builder().maxCommands(2).build();
        assertEquals(new DocxMath.Sequence(List.of(italic("α"), italic("β"))), TexToDocx.parse("\\alpha\\beta", commands));
        assertEquals(MathException.Reason.LIMIT_EXCEEDED,
                assertThrows(MathException.class, () -> TexToDocx.parse("\\alpha\\beta\\gamma", commands)).reason());
        assertEquals(MathException.Reason.LIMIT_EXCEEDED,
                assertThrows(MathException.class, () -> TexToDocx.parse("\\unknown\\alpha\\beta", commands)).reason());
        assertEquals(MathException.Reason.LIMIT_EXCEEDED,
                assertThrows(MathException.class, () -> TexToDocx.parse("\\unknown{{{{x}}}}", depth)).reason());
        assertEquals(MathException.Reason.LIMIT_EXCEEDED,
                assertThrows(MathException.class, () -> TexToDocx.parse("x".repeat(5), MathOptions.builder().maxSourceLength(4).build())).reason());
    }

    @Test void diagnosticsDoNotDiscloseInputAndCancellationCanInterruptParsing() throws IOException {
        var error = assertThrows(MathException.class, () -> parse("\\privateSecretCommand"));
        assertFalse(error.getMessage().contains("Secret"));
        assertEquals(MathException.Reason.UNSUPPORTED_SYNTAX, error.reason());
        var checks = new AtomicInteger();
        var cancelled = ExecutionOptions.builder().cancelled(() -> checks.incrementAndGet() > 8).build();
        var cancellation = assertThrows(ExecutionException.class,
                () -> ExecutionContext.execute(cancelled, () -> parse("x".repeat(100))));
        assertEquals(ExecutionException.Code.CANCELLED, cancellation.code());
        assertTrue(checks.get() > 8);
        assertEquals(italic("x"), parse("x"));
    }

    @Test void malformedPrefixesCannotHideLaterBudgetsOrEnableFallback() {
        var commands = MathOptions.builder().maxCommands(1).build();
        for (String prefix : List.of("}", "\u0000", "\ud800", "\uffff")) {
            assertEquals(MathException.Reason.LIMIT_EXCEEDED,
                    assertThrows(MathException.class, () -> TexToDocx.parse(prefix + "\\alpha\\beta", commands)).reason());
            failure(prefix + "x", MathException.Reason.INVALID_SYNTAX);
        }
        assertEquals(MathException.Reason.LIMIT_EXCEEDED,
                assertThrows(MathException.class, () -> TexToDocx.parse("}}}" + "{{{{x}}}}",
                        MathOptions.builder().maxDepth(3).build())).reason());
        var permissive = MarkdownMath.builder().options(commands).fallback(MarkdownMath.Fallback.KEEP_SOURCE).build();
        assertEquals(MathException.Reason.LIMIT_EXCEEDED,
                assertThrows(MathException.class, () -> permissive.docx("$}\\alpha\\beta$")).reason());
    }

    private static DocxMath parse(String source) { return TexToDocx.parse(source, MathOptions.DEFAULT); }
    private static DocxMath.Run italic(String text) { return new DocxMath.Run(text, DocxMath.Style.ITALIC); }
    private static DocxMath.Run plain(String text) { return new DocxMath.Run(text, DocxMath.Style.PLAIN); }
    private static void failure(String source, MathException.Reason reason) {
        assertEquals(reason, assertThrows(MathException.class, () -> parse(source)).reason(), source);
    }
}
