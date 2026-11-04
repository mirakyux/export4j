// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.Paragraph;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class XmlTemplateConditionTest {
    private static final String CHOICES = """
            <document version='1'>
              <if test='status' operator='EQ' value='1'><then><p id='one'>One</p></then><else>
                <if test='status' operator='EQ' value='2'><then><p id='two'>Two</p></then><else>
                  <if test='status' operator='EQ' value='3'><then><p id='three'>Three</p></then>
                    <else><p id='other'>Other</p></else></if>
                </else></if>
              </else></if>
            </document>
            """;

    @Test void nestedElseComparisonsSelectOneAlternativeAndPreserveEverySourceBranch() {
        var template = XmlTemplate.builder().build().compile(CHOICES);
        for (int value = 1; value <= 4; value++) {
            assertEquals(List.of(List.of("One", "Two", "Three", "Other").get(value - 1)), template.render(Map.of("status", value))
                    .paragraphs().stream().map(Paragraph::text).toList());
        }
        var outer = (DocumentTemplate.When) template.nodes().getFirst();
        assertEquals(1, outer.yes().size());
        var second = (DocumentTemplate.When) outer.no().getFirst();
        var third = (DocumentTemplate.When) second.no().getFirst();
        assertEquals("Other", ((Paragraph) ((DocumentTemplate.ValueBlock) third.no().getFirst()).prototype()).text());
    }

    @Test void scalarOperatorsUseExactNumbersTextAndTheCurrentLoopScope() {
        var compiler = XmlTemplate.builder().build();
        var exact = compiler.compile(document("<if test='n' operator='EQ' value='9007199254740993'><then><p>Exact</p></then><else><p>Other</p></else></if>"));
        assertEquals("Exact", exact.render(Map.of("n", new BigInteger("9007199254740993"))).paragraphs().getFirst().text());
        assertEquals("Other", exact.render(Map.of("n", new BigInteger("9007199254740992"))).paragraphs().getFirst().text());
        var scoped = compiler.compile(document("<each items='items' as='item'><if test='item.label' operator='CONTAINS' value='yes'><then><p id='match'>Matched</p></then><else><p id='other'>Other</p></else></if></each>"));
        assertEquals(List.of("Matched", "Other"), scoped.render(Map.of("item", Map.of("label", "no"), "items", List.of(Map.of("label", "yes"), Map.of("label", "no"))))
                .paragraphs().stream().map(Paragraph::text).toList());
        var legacy = compiler.compile(document("<if test='visible'><then><p>Yes</p></then><else><p>No</p></else></if>"));
        assertEquals("Yes", legacy.render(Map.of("visible", true)).paragraphs().getFirst().text());
        assertEquals("No", legacy.render(Map.of("visible", false)).paragraphs().getFirst().text());
    }

    @Test void invalidPredicatesAndInactiveBranchesAreStillCompiledAndDiagnosticsAreSanitized() {
        var compiler = XmlTemplate.builder().build();
        for (var attributes : List.of("operator='UNKNOWN'", "operator='GT' value='private-number'", "operator='TRUE' value='true'", "value='secret'")) {
            var failure = assertThrows(TemplateException.class, () -> compiler.compile(document("<if test='status' " + attributes + "><then><p>Yes</p></then></if>")));
            assertEquals(TemplateException.Code.INVALID_VALUE, failure.code());
            assertFalse(failure.getMessage().contains("private-number"));
            assertFalse(failure.getMessage().contains("secret"));
        }
        var inactive = document("<if test='status' operator='EQ' value='1'><then><p>Safe</p></then><else><if test='status' operator='GT' value='secret'><then><p>Hidden</p></then></if></else></if>");
        assertEquals(TemplateException.Code.INVALID_VALUE, assertThrows(TemplateException.class, () -> compiler.compile(inactive)).code());
        var malformed = document("<if test='status' operator='EQ' value='1'><then><p>Safe</p></then><else><if test='status' operator='EQ' value='2'><then><p>${bad()}</p></then></if></else></if>");
        assertEquals(TemplateException.Code.INVALID_EXPRESSION, assertThrows(TemplateException.class, () -> compiler.compile(malformed)).code());
        assertEquals(DocumentTemplate.BindingException.Code.TYPE_MISMATCH, assertThrows(DocumentTemplate.BindingException.class,
                () -> compiler.compile(document("<if test='status' operator='GT' value='1'><then><p>Safe</p></then></if>")).render(Map.of("status", "private-text"))).code());
    }

    @Test void nestedComparisonAlternativesDoNotBypassSourceDepthBudget() {
        var compiler = XmlTemplate.builder().limits(new XmlTemplate.Limits(10_000, 100, 5, 10_000)).build();
        assertEquals(TemplateException.Code.BUDGET_EXCEEDED, assertThrows(TemplateException.class, () -> compiler.compile(CHOICES)).code());
    }

    private static String document(String body) { return "<document version='1'>" + body + "</document>"; }
}
